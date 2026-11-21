package com.zorken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Vault {
    static final int CHUNK = 1 << 20;
    static final int ITER = 600000;
    static final byte[] KMAGIC2 = {'Z', 'K', 'K', '2'};
    static final byte[] FMAGIC2 = {'Z', 'K', 'E', '2'};
    static final SecureRandom RND = new SecureRandom();

    public static class Stopped extends IOException { Stopped() { super("stopped"); } }

    static final class Counts { long files, dirs, bytes, skipped; }

    public static volatile java.util.function.LongConsumer PROGRESS;
    private static void report(long n) { java.util.function.LongConsumer p = PROGRESS; if (p != null && n > 0) p.accept(n); }

    private Vault() {}

    static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) m.update(p);
        return m.doFinal();
    }

    static Cipher gcm(int mode, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c;
    }

    private static final class Bundle {
        int iter; byte[] salt, pub, head, iv, ct;
    }

    private static Bundle parse(byte[] b) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(b));
        byte[] m = new byte[4];
        d.readFully(m);
        if (!Arrays.equals(m, KMAGIC2)) throw new IOException("not a key bundle");
        Bundle x = new Bundle();
        d.readUnsignedByte();
        x.iter = d.readInt();
        if (x.iter < 1000 || x.iter > 10000000) throw new IOException("bad key bundle");
        x.salt = new byte[16];
        d.readFully(x.salt);
        x.pub = new byte[d.readUnsignedShort()];
        d.readFully(x.pub);
        int headLen = 4 + 1 + 4 + 16 + 2 + x.pub.length;
        x.head = Arrays.copyOf(b, headLen);
        x.iv = new byte[12];
        d.readFully(x.iv);
        x.ct = Arrays.copyOfRange(b, headLen + 12, b.length);
        if (x.ct.length < 16) throw new IOException("bad key bundle");
        return x;
    }

    private static byte[] seal(byte[] pub, byte[] privPkcs8, char[] pin) throws GeneralSecurityException, IOException {
        byte[] salt = new byte[16], iv = new byte[12];
        RND.nextBytes(salt);
        RND.nextBytes(iv);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.write(KMAGIC2);
        d.writeByte(0);
        d.writeInt(ITER);
        d.write(salt);
        d.writeShort(pub.length);
        d.write(pub);
        byte[] head = bo.toByteArray();
        byte[] k = Common.pbkdf2(pin, salt, ITER);
        Cipher c = gcm(Cipher.ENCRYPT_MODE, k, iv);
        c.updateAAD(head);
        byte[] ct = c.doFinal(privPkcs8);
        bo.reset();
        bo.write(head);
        bo.write(iv);
        bo.write(ct);
        return bo.toByteArray();
    }

    public static byte[] newBundle(char[] pin) throws GeneralSecurityException, IOException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        return seal(kp.getPublic().getEncoded(), kp.getPrivate().getEncoded(), pin);
    }

    public static PublicKey publicKey(byte[] bundle) throws GeneralSecurityException, IOException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(parse(bundle).pub));
    }

    private static byte[] privateBytes(byte[] bundle, char[] pin) throws GeneralSecurityException, IOException {
        Bundle x = parse(bundle);
        byte[] k = Common.pbkdf2(pin, x.salt, x.iter);
        Cipher c = gcm(Cipher.DECRYPT_MODE, k, x.iv);
        c.updateAAD(x.head);
        return c.doFinal(x.ct);
    }

    public static PrivateKey unlock(byte[] bundle, char[] pin) throws GeneralSecurityException, IOException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateBytes(bundle, pin)));
    }

    public static byte[] reseal(byte[] bundle, char[] oldSecret, char[] newSecret) throws GeneralSecurityException, IOException {
        byte[] raw = privateBytes(bundle, oldSecret);
        return seal(parse(bundle).pub, raw, newSecret);
    }

    private static byte[] itemKey(PrivateKey mine, PublicKey theirs, byte[] ephPubEnc) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        return hmac(ka.generateSecret(), "ZKE1-key".getBytes(StandardCharsets.UTF_8), ephPubEnc);
    }

    private static byte[] ivFor(long idx) {
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++) iv[11 - i] = (byte) (idx >>> (8 * i));
        return iv;
    }

    private static byte[] aadFor(long idx, boolean fin) {
        byte[] a = new byte[9];
        for (int i = 0; i < 8; i++) a[7 - i] = (byte) (idx >>> (8 * i));
        a[8] = (byte) (fin ? 1 : 0);
        return a;
    }

    private static final class ChunkOut extends OutputStream {
        private final OutputStream out; private final byte[] key; private final byte[] buf = new byte[CHUNK];
        private int n; private long idx; private boolean closed;
        ChunkOut(OutputStream out, byte[] key) { this.out = out; this.key = key; }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (n == CHUNK) emit(false);
                int k = Math.min(len, CHUNK - n);
                System.arraycopy(b, off, buf, n, k);
                n += k; off += k; len -= k;
            }
        }
        private void emit(boolean fin) throws IOException {
            try {
                Cipher c = gcm(Cipher.ENCRYPT_MODE, key, ivFor(idx));
                c.updateAAD(aadFor(idx, fin));
                byte[] ct = c.doFinal(buf, 0, n);
                new DataOutputStream(out).writeInt(ct.length);
                out.write(ct);
            } catch (GeneralSecurityException e) { throw new IOException(e); }
            n = 0; idx++;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            emit(true);
            out.flush();
        }
    }

    private static final class ChunkIn extends InputStream {
        private final InputStream in; private final byte[] key;
        private byte[] cur = new byte[0]; private int pos; private long idx;
        private boolean started, done; private int nextLen;
        ChunkIn(InputStream in, byte[] key) { this.in = in; this.key = key; }

        private int readLen() throws IOException {
            int b0 = in.read();
            if (b0 < 0) return -1;
            int b1 = in.read(), b2 = in.read(), b3 = in.read();
            if ((b1 | b2 | b3) < 0) throw new EOFException("truncated container");
            return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        }

        private boolean fill() throws IOException {
            if (done) return false;
            if (!started) { started = true; nextLen = readLen(); }
            if (nextLen < 0) { done = true; return false; }
            int len = nextLen;
            if (len < 16 || len > CHUNK + 16) throw new IOException("corrupt container");
            byte[] ct = new byte[len];
            new DataInputStream(in).readFully(ct);
            nextLen = readLen();
            boolean fin = nextLen < 0;
            try {
                Cipher c = gcm(Cipher.DECRYPT_MODE, key, ivFor(idx));
                c.updateAAD(aadFor(idx, fin));
                cur = c.doFinal(ct);
            } catch (GeneralSecurityException e) { throw new IOException("container damaged or truncated", e); }
            pos = 0; idx++;
            if (fin) done = true;
            return true;
        }

        @Override public int read() throws IOException {
            while (pos >= cur.length) if (!fill()) return -1;
            return cur[pos++] & 0xff;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= cur.length) if (!fill()) return -1;
            int k = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, k);
            pos += k;
            return k;
        }
    }

    private static void writeName(DataOutputStream d, String name) throws IOException {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        d.writeShort(nb.length);
        d.write(nb);
    }

    public static final class Entry {
        public final File f; public final String name; public final boolean dir;
        public Entry(File f, String name, boolean dir) { this.f = f; this.name = name; this.dir = dir; }
    }

    private static void writeEntry(DataOutputStream d, Entry e, Counts c, BooleanSupplier stop) throws IOException {
        if (stop.getAsBoolean()) throw new Stopped();
        if (e.dir) { d.writeByte(2); writeName(d, e.name); c.dirs++; return; }
        long size = e.f.length();
        d.writeByte(1);
        writeName(d, e.name);
        d.writeLong(size);
        d.writeLong(e.f.lastModified());
        c.files++; c.bytes += size;
        byte[] buf = new byte[1 << 16];
        long left = size;
        try (InputStream in = new FileInputStream(e.f)) {
            while (left > 0) {
                if (stop.getAsBoolean()) throw new Stopped();
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new IOException("file changed while locking: " + e.f);
                d.write(buf, 0, r);
                left -= r;
                report(r);
            }
        }
    }

    private static File checkedDest(String parent) throws IOException {
        if (parent.isEmpty() || parent.indexOf('\0') >= 0 || Shell.resolve(parent + "/x") == null) throw new IOException("bad destination in container");
        File f = new File(parent);
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException("cannot create " + f);
        return f;
    }

    private static File target(File dst, String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\0') >= 0) throw new IOException("bad name in container");
        for (String s : name.split("/", -1)) if (s.isEmpty() || s.equals(".") || s.equals("..")) throw new IOException("bad name in container");
        File f = new File(dst, name);
        if (!f.getCanonicalPath().startsWith(dst.getCanonicalPath() + File.separator)) throw new IOException("bad name in container");
        return f;
    }

    private static Counts read(File container, PrivateKey priv, byte[] knownKey, File dst, boolean useMeta, BooleanSupplier stop) throws Exception {
        Counts cnt = new Counts();
        boolean metaSeen = false;
        try (InputStream raw = new BufferedInputStream(new FileInputStream(container), 1 << 16)) {
            DataInputStream h = new DataInputStream(raw);
            byte[] m = new byte[4];
            h.readFully(m);
            if (!Arrays.equals(m, FMAGIC2)) throw new IOException("not a Zorken container");
            h.readFully(new byte[h.readUnsignedShort()]);
            byte[] eph = new byte[h.readUnsignedShort()];
            h.readFully(eph);
            byte[] key = knownKey != null ? knownKey
                : itemKey(priv, KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(eph)), eph);
            DataInputStream d = new DataInputStream(new BufferedInputStream(new ChunkIn(raw, key), 1 << 16));
            byte[] buf = new byte[1 << 16];
            while (true) {
                int t = d.readUnsignedByte();
                if (t == 0) break;
                byte[] nb = new byte[d.readUnsignedShort()];
                d.readFully(nb);
                String name = new String(nb, StandardCharsets.UTF_8);
                if (t == 2) {
                    cnt.dirs++;
                    if (dst != null) { File f = target(dst, name); if (!f.isDirectory() && !f.mkdirs()) throw new IOException("cannot create " + f); }
                } else if (t == 1) {
                    long size = d.readLong(), mt = d.readLong();
                    cnt.files++; cnt.bytes += size;
                    File f = dst == null ? null : target(dst, name);
                    File tmp = null;
                    OutputStream o = null;
                    if (f != null && f.exists() && (!f.isFile() || f.length() != size)) cnt.skipped++;
                    if (f != null && !f.exists()) {
                        File par = f.getParentFile();
                        if (!par.isDirectory() && !par.mkdirs()) throw new IOException("cannot create " + par);
                        tmp = new File(f.getPath() + ".zktmp");
                        o = new FileOutputStream(tmp);
                    }
                    try {
                        long left = size;
                        while (left > 0) {
                            if (stop.getAsBoolean()) throw new Stopped();
                            int r = d.read(buf, 0, (int) Math.min(buf.length, left));
                            if (r < 0) throw new EOFException("truncated container");
                            if (o != null) o.write(buf, 0, r);
                            left -= r;
                            report(r);
                        }
                        if (o != null) { o.close(); o = null; }
                        if (tmp != null) {
                            if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
                            f.setLastModified(mt);
                            tmp = null;
                        }
                    } finally {
                        if (o != null) try { o.close(); } catch (IOException ignored) {}
                        if (tmp != null) tmp.delete();
                    }
                } else if (t == 3) {
                    metaSeen = true;
                    if (useMeta) dst = checkedDest(name);
                } else throw new IOException("corrupt container");
            }
            if (d.read() != -1) throw new IOException("unexpected data after container end");
            if (useMeta && !metaSeen) throw new IOException("container has no destination");
        }
        return cnt;
    }

    public static void encrypt(java.util.List<Entry> entries, File outTmp, byte[] bundle, String destParent, BooleanSupplier stop) throws Exception {
        PublicKey recipient = publicKey(bundle);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair eph = g.generateKeyPair();
        byte[] ephPub = eph.getPublic().getEncoded();
        byte[] key = itemKey(eph.getPrivate(), recipient, ephPub);
        Counts w = new Counts();
        FileOutputStream fos = new FileOutputStream(outTmp);
        try {
            BufferedOutputStream bo = new BufferedOutputStream(fos, 1 << 18);
            DataOutputStream h = new DataOutputStream(bo);
            h.write(FMAGIC2);
            h.writeShort(bundle.length);
            h.write(bundle);
            h.writeShort(ephPub.length);
            h.write(ephPub);
            DataOutputStream d = new DataOutputStream(new ChunkOut(bo, key));
            d.writeByte(3); writeName(d, destParent);
            for (Entry e : entries) writeEntry(d, e, w, stop);
            d.writeByte(0);
            d.close();
            bo.flush();
            fos.getFD().sync();
        } finally { fos.close(); }
        Counts r = read(outTmp, null, key, null, false, stop);
        if (r.files != w.files || r.dirs != w.dirs || r.bytes != w.bytes) throw new IOException("verification mismatch");
    }

    public static long extractAuto(File container, PrivateKey priv, BooleanSupplier stop) throws Exception {
        return read(container, priv, null, null, true, stop).skipped;
    }

    public static boolean isContainer(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] m = new byte[4];
            return new DataInputStream(in).read(m) == 4 && Arrays.equals(m, FMAGIC2);
        } catch (IOException e) { return false; }
    }

    public static byte[] embeddedBundle(File f) {
        try (DataInputStream d = new DataInputStream(new FileInputStream(f))) {
            byte[] m = new byte[4];
            d.readFully(m);
            if (!Arrays.equals(m, FMAGIC2)) return null;
            int len = d.readUnsignedShort();
            if (len < 40) return null;
            byte[] b = new byte[len];
            d.readFully(b);
            return b;
        } catch (IOException e) { return null; }
    }

    public static boolean rewriteHeader(File f, byte[] bundle) {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            byte[] m = new byte[4];
            r.readFully(m);
            if (!Arrays.equals(m, FMAGIC2) || r.readUnsignedShort() != bundle.length) return false;
            r.write(bundle);
            r.getFD().sync();
            return true;
        } catch (IOException e) { return false; }
    }
}
