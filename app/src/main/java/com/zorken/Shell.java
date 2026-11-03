package com.zorken;

import android.content.*;
import android.os.Build;
import android.os.Environment;
import java.io.*;
import java.util.*;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class Shell {
    static final String[] ALIASES = {"/storage/emulated/0/", "/storage/self/primary/", "/sdcard/", "/data/media/0/"};
    static final String PREFIX = ".zkh_";
    static final String VAULT = ".zorken";
    static final String EXT = ".zk";
    static final String KEYS = ".keys";
    static final Object LOCK = new Object();
    static final AtomicInteger applyActive = new AtomicInteger();
    static volatile boolean stopReq;

    static String[] resolve(String p) {
        String base = null, rel = null;
        for (String a : ALIASES) if (p.startsWith(a)) { base = "/storage/emulated/0"; rel = p.substring(a.length()); break; }
        if (base == null && p.startsWith("/storage/")) {
            String r = p.substring(9);
            int s = r.indexOf('/');
            if (s > 0) {
                String vol = r.substring(0, s);
                if (!vol.equals("emulated") && !vol.equals("self")) { base = "/storage/" + vol; rel = r.substring(s + 1); }
            }
        }
        if (base == null || rel.isEmpty() || rel.contains("..") || rel.contains("'") || rel.contains("\n")) return null;
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        String low = rel.toLowerCase(Locale.ROOT);
        if (low.equals(VAULT) || low.startsWith(VAULT + "/")) return null;
        if (low.equals("android") || low.startsWith("android/")) return null;
        File real = new File(base + "/" + rel);
        if (real.getName().startsWith(PREFIX)) return null;
        String id = (rel.hashCode() & 0x7fffffff) + "_" + real.getName();
        return new String[]{real.getPath(), base + "/" + VAULT + "/" + id, real.getParent() + "/" + PREFIX + id,
            base + "/" + VAULT + "/" + (rel.hashCode() & 0x7fffffff)};
    }

    static boolean merge(File src, File dst) {
        boolean ok = true;
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) ok &= merge(k, new File(dst, k.getName()));
            return src.delete() && ok;
        }
        if (dst.exists()) return false;
        return src.renameTo(dst);
    }

    static boolean hasAccess() { return Environment.isExternalStorageManager(); }

    static String lower(String p) {
        if (p.startsWith("/storage/emulated/0/")) return "/data/media/0/" + p.substring(20);
        if (p.startsWith("/storage/")) return "/mnt/media_rw/" + p.substring(9);
        return null;
    }

    static String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final StringBuilder out = new StringBuilder();
            final InputStream in = p.getInputStream();
            Thread t = new Thread(new Runnable() { public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                    String l; while ((l = r.readLine()) != null) synchronized (out) { out.append(l).append('\n'); }
                } catch (Exception ignored) {}
            }});
            t.start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            t.join(1000);
            synchronized (out) { return out.toString(); }
        } catch (Exception e) { return null; }
    }

    static boolean move(File from, File to, StringBuilder log) {
        if (from.renameTo(to)) return true;
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) { log.append("  rename refused: ").append(e).append('\n'); }
        String lf = lower(from.getPath()), lt = lower(to.getPath());
        if (lf != null && lt != null && !lf.contains("'") && !lt.contains("'")) {
            String o = su("mv '" + lf + "' '" + lt + "' && echo ZKOK");
            if (o != null && o.contains("ZKOK")) { log.append("  done with root fallback\n"); return true; }
            log.append("  root fallback not available (allow Zorken in KernelSU Superuser to enable it)\n");
        }
        if (from.isDirectory()) {
            boolean ok = merge(from, to);
            log.append(ok ? "  moved file by file\n" : "  file-by-file move incomplete\n");
            return ok;
        }
        return false;
    }

    static List<File> containers(String prefix) {
        File p = new File(prefix), dir = p.getParentFile();
        String pre = p.getName() + ".";
        TreeMap<Integer, File> m = new TreeMap<>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith(pre) || !n.endsWith(EXT)) continue;
            String mid = n.substring(pre.length(), n.length() - EXT.length());
            if (mid.matches("\\d{1,6}")) m.put(Integer.parseInt(mid), f);
        }
        return new ArrayList<>(m.values());
    }

    static Set<String> pathSet(Context c) {
        return new HashSet<>(Common.prefs(c).getStringSet("paths", new HashSet<String>()));
    }

    static Set<File> vaultDirs(Set<String> paths) {
        Set<File> out = new LinkedHashSet<>();
        out.add(new File("/storage/emulated/0/" + VAULT));
        for (String p : paths) { String[] r = resolve(p); if (r != null) out.add(new File(r[1]).getParentFile()); }
        return out;
    }

    static byte[] readAll(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } catch (Exception e) { return null; }
    }

    static void writeKeys(File vault, byte[] bundle) {
        try {
            File k = new File(vault, KEYS);
            byte[] cur = k.exists() ? readAll(k) : null;
            if (cur != null && Arrays.equals(cur, bundle)) return;
            if (!vault.isDirectory() && !vault.mkdirs()) return;
            File t = new File(vault, KEYS + ".tmp");
            try (FileOutputStream o = new FileOutputStream(t)) { o.write(bundle); o.getFD().sync(); }
            if (!t.renameTo(k)) t.delete();
        } catch (Exception ignored) {}
    }

    static byte[] bundle(Context c) {
        SharedPreferences sp = Common.prefs(c);
        String b64 = sp.getString("vbundle", null);
        if (b64 != null) try { return Base64.getDecoder().decode(b64); } catch (Exception ignored) {}
        for (File v : vaultDirs(pathSet(c))) {
            File k = new File(v, KEYS);
            byte[] b = k.exists() ? readAll(k) : null;
            if (b == null) continue;
            try {
                Vault.publicKey(b);
                sp.edit().putString("vbundle", Base64.getEncoder().encodeToString(b)).apply();
                return b;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static byte[] vkey(Context c) { return Common.unhex(Common.prefs(c).getString("vkey", null)); }

    static void storeBundle(Context c, byte[] b) {
        Common.prefs(c).edit().putString("vbundle", Base64.getEncoder().encodeToString(b)).commit();
        for (File v : vaultDirs(pathSet(c))) if (v.isDirectory()) writeKeys(v, b);
    }

    public static void ensureBundle(Context c, String pin) {
        try {
            byte[] b = bundle(c), vk = vkey(c);
            if (b == null) storeBundle(c, Vault.newBundle(pin.toCharArray(), vk));
            else if (vk != null && !Vault.usesVkey(b)) storeBundle(c, Vault.rewrap(b, pin.toCharArray(), pin.toCharArray(), vk));
        } catch (Exception e) { save(c, "key", "lock key not updated: " + e); }
    }

    public static void rewrapPin(Context c, String oldPin, String newPin) {
        try {
            byte[] b = bundle(c);
            if (b != null) storeBundle(c, Vault.rewrap(b, oldPin.toCharArray(), newPin.toCharArray(), vkey(c)));
        } catch (Exception e) { save(c, "key", "lock key not re-protected: " + e); }
    }

    static PrivateKey unlock(Context c, String pin) throws Exception {
        byte[] b = bundle(c);
        if (b == null) throw new IOException("no lock key found");
        try { return Vault.unlock(b, pin.toCharArray(), vkey(c)); }
        catch (java.security.GeneralSecurityException e) { throw new IOException("wrong PIN"); }
        catch (IllegalStateException e) { throw new IOException("device key missing - enable the module, reboot, open the app once"); }
    }

    static List<String[]> waiting(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String p : pathSet(c)) { String[] r = resolve(p); if (r != null && new File(r[1]).exists()) out.add(r); }
        return out;
    }

    public static int pending(Context c) { return waiting(c).size(); }

    static boolean hasProblems(String log) {
        return log.contains("FAILED") || log.contains("LOCKED") || log.contains("could not open");
    }

    static boolean deleteTree(File f) {
        if (Files.isSymbolicLink(f.toPath())) return f.delete();
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        boolean ok = true;
        if (kids != null) for (File k : kids) ok &= deleteTree(k);
        return f.delete() && ok;
    }

    static void cleanVault(File vault) {
        String[] kids = vault.list();
        if (kids == null) return;
        if (kids.length == 1 && kids[0].equals(KEYS)) { new File(vault, KEYS).delete(); kids = vault.list(); }
        if (kids != null && kids.length == 0) vault.delete();
    }

    static final long BATCH = 256L << 20;

    static void plan(File f, String name, List<Vault.Entry> out) {
        if (Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null || kids.length == 0) { out.add(new Vault.Entry(f, name, true)); return; }
            Arrays.sort(kids);
            for (File k : kids) plan(k, name + "/" + k.getName(), out);
        } else if (f.isFile()) out.add(new Vault.Entry(f, name, false));
    }

    static long pDone, pTotal, pLast;
    static String pPhase, pName;

    static long sizeOf(File f) {
        if (Files.isSymbolicLink(f.toPath())) return 0;
        if (f.isFile()) return f.length();
        long n = 0; File[] k = f.listFiles();
        if (k != null) for (File x : k) n += sizeOf(x);
        return n;
    }

    static synchronized void progStart(final Context c, String phase, long total) {
        pPhase = phase; pTotal = Math.max(1, total); pDone = 0; pName = ""; pLast = 0;
        if (c == null) { Vault.PROGRESS = null; return; }
        progWrite(c, true);
        Vault.PROGRESS = new java.util.function.LongConsumer() { public void accept(long n) { pDone += n; progWrite(c, false); } };
    }
    static void progName(Context c, String name) { pName = name; if (c != null) progWrite(c, true); }
    static void progItem(Context c) { pDone++; if (c != null) progWrite(c, true); }
    static void progWrite(Context c, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - pLast < 400) return;
        pLast = now;
        Common.prefs(c).edit().putString("zk_phase", pPhase).putString("zk_name", pName == null ? "" : pName)
            .putLong("zk_done", Math.min(pDone, pTotal)).putLong("zk_total", pTotal).putLong("zk_ts", now).apply();
    }
    static synchronized void progEnd(Context c, String phase) {
        if (phase != null && !phase.equals(pPhase)) return;
        Vault.PROGRESS = null; pPhase = null;
        if (c != null) Common.prefs(c).edit().remove("zk_phase").remove("zk_name").remove("zk_done").remove("zk_total").remove("zk_ts").apply();
    }

    static String mb(long b) { return (b >> 20) + " MB"; }

    public static boolean protect(Context c, final BooleanSupplier cancel) {
        List<String[]> todo = waiting(c);
        if (todo.isEmpty()) return false;
        if (applyActive.get() > 0) return true;
        byte[] b = bundle(c);
        if (b == null) { save(c, "lock", "NOT LOCKED: no lock key yet. Open Zorken and unlock once."); return false; }
        StringBuilder log = new StringBuilder();
        boolean yielded = false;
        try {
            PublicKey pub = Vault.publicKey(b);
            BooleanSupplier stop = new BooleanSupplier() { public boolean getAsBoolean() { return stopReq || cancel.getAsBoolean(); } };
            long sum = 0;
            for (String[] r : todo) sum += sizeOf(new File(r[1]));
            progStart(c, "lock", sum * 2);
            outer:
            for (String[] r : todo) {
                String name = new File(r[0]).getName();
                progName(c, name);
                File plain = new File(r[1]);
                List<Vault.Entry> all = new ArrayList<>();
                synchronized (LOCK) { if (!plain.exists()) continue; plan(plain, name, all); }
                long total = 0, done = 0; int step = 0;
                for (Vault.Entry e : all) if (!e.dir) total += e.f.length();
                int i = 0;
                while (i < all.size()) {
                    if (stopReq) { yielded = true; break outer; }
                    if (cancel.getAsBoolean()) break outer;
                    List<Vault.Entry> batch = new ArrayList<>();
                    long bytes = 0;
                    while (i < all.size()) {
                        Vault.Entry e = all.get(i);
                        long len = e.dir ? 0 : e.f.length();
                        if (!batch.isEmpty() && bytes + len > BATCH) break;
                        batch.add(e); bytes += len; i++;
                        if (bytes >= BATCH) break;
                    }
                    synchronized (LOCK) {
                        if (!plain.exists()) break;
                        File vault = plain.getParentFile();
                        List<File> have = containers(r[3]);
                        int n = 0;
                        if (!have.isEmpty()) { String nm = have.get(have.size() - 1).getName(); n = Integer.parseInt(nm.substring(new File(r[3]).getName().length() + 1, nm.length() - EXT.length())) + 1; }
                        File out = new File(r[3] + "." + n + EXT), tmp = new File(out.getPath() + ".tmp");
                        try {
                            long free = vault.getUsableSpace();
                            if (free < bytes + (64L << 20))
                                throw new IOException("not enough free space (" + mb(free) + " free, needs " + mb(bytes + (64L << 20)) + ")");
                            writeKeys(vault, b);
                            Vault.encrypt(batch, tmp, pub, stop);
                            if (!tmp.renameTo(out)) throw new IOException("cannot rename container");
                            for (Vault.Entry e : batch) if (!e.dir) e.f.delete();
                            done += bytes; step++;
                        } catch (Vault.Stopped e) {
                            tmp.delete();
                            yielded = stopReq;
                            log.append("paused: ").append(name).append(" (").append(mb(done)).append(" of ").append(mb(total)).append(" locked, rest continues next run)\n");
                            break outer;
                        } catch (Exception e) {
                            tmp.delete();
                            log.append("FAILED to lock ").append(name).append(": ").append(e).append(" (").append(mb(done)).append(" of ").append(mb(total)).append(" locked so far)\n");
                            break;
                        }
                    }
                }
                if (i >= all.size()) synchronized (LOCK) {
                    boolean gone = deleteTree(plain);
                    log.append(gone ? "locked: " : "locked (plain copy not fully removed): ").append(name).append('\n');
                }
            }
        } catch (Exception e) { log.append("FAILED: ").append(e).append('\n'); }
        progEnd(c, "lock");
        if (log.length() > 0) save(c, "lock", log.toString());
        return yielded;
    }

    public static void protectAll(Context c, BooleanSupplier cancel) throws InterruptedException {
        for (int n = 0; n < 20 && protect(c, cancel) && !cancel.getAsBoolean(); n++) Thread.sleep(500);
    }

    static String apply(Set<String> paths, boolean hide, Context c, String pin) {
        applyActive.incrementAndGet();
        try {
            stopReq = true;
            synchronized (LOCK) {
                stopReq = false;
                return apply0(paths, hide, c, pin);
            }
        } finally { applyActive.decrementAndGet(); }
    }

    private static String apply0(Set<String> paths, boolean hide, Context c, String pin) {
        if (!hasAccess()) return "No storage access. Grant 'All files access' to Zorken.";
        StringBuilder log = new StringBuilder("Android " + Build.VERSION.RELEASE + " / " + Build.MANUFACTURER + "\n");
        PrivateKey priv = null;
        String lockErr = null;
        boolean triedUnlock = false;
        Set<File> touched = new LinkedHashSet<>();
        if (hide) progStart(c, "hide", paths.size());
        else {
            long sum = 0;
            for (String p : paths) { String[] r = resolve(p); if (r != null) for (File f : containers(r[3])) sum += f.length(); }
            progStart(c, "restore", sum);
        }
        try {
        for (String p : paths) {
            String[] r = resolve(p);
            if (r == null) { log.append("skipped (unsupported path): ").append(p).append('\n'); continue; }
            File real = new File(r[0]), plain = new File(r[1]);
            touched.add(plain.getParentFile());
            progName(c, real.getName());
            try {
                if (hide) {
                    if (!real.exists()) { log.append(plain.exists() || !containers(r[3]).isEmpty() ? "already hidden: " : "not found: ").append(p).append('\n'); continue; }
                    File vault = plain.getParentFile();
                    if (!vault.isDirectory() && !vault.mkdirs()) { log.append("FAILED (cannot create .zorken): ").append(p).append('\n'); continue; }
                    boolean ok = !plain.exists() ? move(real, plain, log) : merge(real, plain);
                    log.append(ok ? "hidden: " : "FAILED: ").append(p).append('\n');
                } else {
                    File old = new File(r[2]);
                    if (!plain.exists() && old.exists()) plain = old;
                    List<File> cs = containers(r[3]);
                    if (!plain.exists() && cs.isEmpty()) { log.append(real.exists() ? "already visible: " : "nothing to restore: ").append(p).append('\n'); continue; }
                    boolean ok = true;
                    if (plain.exists()) ok = !real.exists() ? move(plain, real, log) : merge(plain, real);
                    if (!cs.isEmpty()) {
                        if (!triedUnlock) {
                            triedUnlock = true;
                            try { priv = unlock(c, pin); } catch (Exception e) { lockErr = String.valueOf(e.getMessage()); }
                        }
                        if (priv == null) { ok = false; log.append("LOCKED (").append(lockErr).append("): ").append(p).append('\n'); continue; }
                        File parent = real.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) { log.append("FAILED (cannot create folder): ").append(p).append('\n'); continue; }
                        for (File f : cs) {
                            try {
                                long clash = Vault.extract(f, priv, parent, new BooleanSupplier() { public boolean getAsBoolean() { return false; } });
                                if (clash == 0) f.delete();
                                else { ok = false; log.append("  kept ").append(f.getName()).append(" in .zorken: ").append(clash).append(" file(s) clash with different files already in place\n"); }
                            }
                            catch (Exception e) { ok = false; log.append("  could not open ").append(f.getName()).append(": ").append(e).append('\n'); }
                        }
                    }
                    log.append(ok ? "restored: " : "FAILED: ").append(p).append('\n');
                }
            } catch (Exception e) { log.append("error: ").append(p).append(" ").append(e).append('\n'); }
            if (hide) progItem(c);
        }
        } finally { progEnd(c, hide ? "hide" : "restore"); }
        if (!hide) for (File v : touched) cleanVault(v);
        return log.toString();
    }

    static String save(Context c, String tag, String out) {
        AppLog.add(c, tag, out);
        return tag + " @ " + new Date() + "\n" + out;
    }

    public static String hideSync(Context c) {
        Set<String> paths = pathSet(c);
        if (paths.isEmpty()) return save(c, "hide", "no files selected");
        return save(c, "hide", apply(paths, true, c, null));
    }
    public static String restoreSync(Context c, String pin) {
        Set<String> paths = pathSet(c);
        if (paths.isEmpty()) return save(c, "restore", "no files selected");
        return save(c, "restore", apply(paths, false, c, pin));
    }
}
