package com.zorken;

import android.content.*;
import android.os.Build;
import android.os.Environment;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class Shell {
    static final String[] ALIASES = {"/storage/emulated/0/", "/storage/self/primary/", "/sdcard/", "/data/media/0/"};
    static final String VAULT = ".zorken";
    static final String EXT = ".zk";
    static final String IDX = "zk_idx";
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
        String id = (rel.hashCode() & 0x7fffffff) + "_" + real.getName();
        return new String[]{real.getPath(), base + "/" + VAULT + "/" + id};
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
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                    String l; while ((l = r.readLine()) != null) synchronized (out) { out.append(l).append('\n'); }
                } catch (Exception ignored) {}
            });
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

    static Set<String> pathSet(Context c) {
        return Common.strings(Common.prefs(c), "paths");
    }

    static Set<File> vaultDirs(Set<String> paths) {
        Set<File> out = new LinkedHashSet<>();
        out.add(new File("/storage/emulated/0/" + VAULT));
        for (String p : paths) { String[] r = resolve(p); if (r != null) out.add(new File(r[1]).getParentFile()); }
        return out;
    }

    static List<File> containers(Set<File> dirs) {
        List<File> out = new ArrayList<>();
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) {
                String n = f.getName();
                if (!f.isFile() || n.endsWith(".tmp") || n.endsWith(".zktmp")) continue;
                if (Vault.isContainer(f)) out.add(f);
            }
        }
        return out;
    }

    static byte[] validBundle(byte[] b) {
        if (b == null) return null;
        try { Vault.publicKey(b); return b; } catch (Exception e) { return null; }
    }

    static byte[] bundle(Context c) {
        SharedPreferences sp = Common.prefs(c);
        String b64 = sp.getString("vbundle", null);
        if (b64 != null) try { byte[] b = validBundle(Base64.getDecoder().decode(b64)); if (b != null) return b; } catch (Exception ignored) {}
        byte[] found = null;
        for (File f : lockedContainers(c)) { found = validBundle(Vault.embeddedBundle(f)); if (found != null) break; }
        if (found != null) sp.edit().putString("vbundle", Base64.getEncoder().encodeToString(found)).apply();
        return found;
    }

    static void storeBundle(Context c, byte[] b) {
        Common.prefs(c).edit().putString("vbundle", Base64.getEncoder().encodeToString(b)).commit();
    }

    public static boolean passwordSetQuick(Context c) {
        String b64 = Common.prefs(c).getString("vbundle", null);
        try { return b64 != null && validBundle(Base64.getDecoder().decode(b64)) != null; } catch (Exception e) { return false; }
    }
    public static boolean passwordSet(Context c) { return bundle(c) != null; }

    public static boolean anyContainer(Context c) {
        return !lockedContainers(c).isEmpty();
    }

    public static String setPassword(Context c, String oldSecret, String newPw) throws Exception {
        synchronized (LOCK) {
            byte[] b = bundle(c);
            byte[] nb;
            try {
                nb = b == null ? Vault.newBundle(newPw.toCharArray())
                               : Vault.reseal(b, oldSecret.toCharArray(), newPw.toCharArray());
            } catch (java.security.GeneralSecurityException e) { throw new IOException("current password is wrong"); }
            storeBundle(c, nb);
            int ok = 0, bad = 0;
            for (File f : lockedContainers(c)) { if (Vault.rewriteHeader(f, nb)) ok++; else bad++; }
            return "vault password " + (b == null ? "set" : "changed") + ", " + ok + " container(s) updated" + (bad > 0 ? ", " + bad + " could not be updated (they still open with the app's copy of the key)" : "");
        }
    }

    static PrivateKey unlock(Context c, String secret) throws Exception {
        byte[] b = bundle(c);
        if (b == null) throw new IOException("no lock key found");
        try { return Vault.unlock(b, secret.toCharArray()); }
        catch (java.security.GeneralSecurityException e) { throw new IOException("wrong password"); }
    }

    public static boolean checkSecret(Context c, String secret) {
        try { return unlock(c, secret) != null; } catch (Exception e) { return false; }
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

    static boolean deleteTree(Context c, File f) {
        if (Files.isSymbolicLink(f.toPath())) return f.delete();
        if (f.isFile()) return FreeSpaceWiper.secureDelete(c, f);
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        boolean ok = true;
        if (kids != null) for (File k : kids) ok &= deleteTree(c, k);
        return (ok ? FreeSpaceWiper.secureDeleteDir(f) : f.delete()) && ok;
    }

    static void cleanVault(File vault) {
        String[] kids = vault.list();
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
        Vault.PROGRESS = n -> { pDone += n; progWrite(c, false); };
    }
    static void progName(Context c, String name) { pName = name; if (c != null) progWrite(c, true); }
    static void progItem(Context c) { pDone++; if (c != null) progWrite(c, true); }
    static void progWrite(Context c, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - pLast < 400) return;
        pLast = now;
        Common.prefs(c).edit().putString("zk_phase", pPhase).putString("zk_name", pName == null ? "" : pName)
            .putLong("zk_done", Math.min(pDone, pTotal)).putLong("zk_total", pTotal).apply();
    }
    static synchronized void progEnd(Context c, String phase) {
        if (phase != null && !phase.equals(pPhase)) return;
        Vault.PROGRESS = null; pPhase = null;
        if (c != null) Common.prefs(c).edit().remove("zk_phase").remove("zk_name").remove("zk_done").remove("zk_total").apply();
    }

    static String mb(long b) { return (b >> 20) + " MB"; }

    public static boolean protect(Context c, final BooleanSupplier cancel) {
        List<String[]> todo = waiting(c);
        if (todo.isEmpty()) return false;
        if (applyActive.get() > 0) return true;
        byte[] b = bundle(c);
        if (b == null) { save(c, "lock", "NOT LOCKED: no vault password yet. Open Zorken and set one."); return false; }
        StringBuilder log = new StringBuilder();
        boolean yielded = false;
        try {
            BooleanSupplier stop = () -> stopReq || cancel.getAsBoolean();
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
                long total = 0, done = 0;
                boolean failed = false;
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
                        byte[] bb = validBundle(bundle(c));
                        if (bb == null) bb = b;
                        File out = Common.randomFile(vault, EXT);
                        File tmp = new File(out.getPath() + ".tmp");
                        try {
                            long free = vault.getUsableSpace();
                            if (free < bytes + (64L << 20))
                                throw new IOException("not enough free space (" + mb(free) + " free, needs " + mb(bytes + (64L << 20)) + ")");
                            Vault.encrypt(batch, tmp, bb, new File(r[0]).getParent(), stop);
                            if (!tmp.renameTo(out)) throw new IOException("cannot rename container");
                            indexAdd(c, out.getName(), name);
                            for (Vault.Entry e : batch) if (!e.dir) FreeSpaceWiper.secureDelete(c, e.f);
                            FreeSpaceWiper.markDue(c);
                            done += bytes;
                        } catch (Vault.Stopped e) {
                            tmp.delete();
                            yielded = stopReq;
                            log.append("paused: ").append(name).append(" (").append(mb(done)).append(" of ").append(mb(total)).append(" locked, rest continues next run)\n");
                            break outer;
                        } catch (Exception e) {
                            tmp.delete();
                            log.append("FAILED to lock ").append(name).append(": ").append(e).append(" (").append(mb(done)).append(" of ").append(mb(total)).append(" locked so far)\n");
                            failed = true;
                            break;
                        }
                    }
                }
                if (!failed && i >= all.size()) synchronized (LOCK) {
                    boolean gone = deleteTree(c, plain);
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
        if (cancel.getAsBoolean()) return;
        FreeSpaceWiper.runIfDue(c, () -> stopReq || cancel.getAsBoolean());
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
        Set<File> touched = new LinkedHashSet<>();
        List<File> locked = hide ? new ArrayList<File>() : containers(vaultDirs(paths));
        for (File v : vaultDirs(paths)) touched.add(v);
        if (hide) progStart(c, "hide", paths.size());
        else {
            long sum = 0;
            for (File f : locked) sum += f.length();
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
                    if (!real.exists()) { log.append(plain.exists() || !containers(vaultDirs(paths)).isEmpty() ? "already hidden: " : "not found: ").append(p).append('\n'); continue; }
                    File vault = plain.getParentFile();
                    if (!vault.isDirectory() && !vault.mkdirs()) { log.append("FAILED (cannot create .zorken): ").append(p).append('\n'); continue; }
                    boolean ok = !plain.exists() ? move(real, plain, log) : merge(real, plain);
                    log.append(ok ? "hidden: " : "FAILED: ").append(p).append('\n');
                } else {
                    if (!plain.exists() && locked.isEmpty()) { log.append(real.exists() ? "already visible: " : "nothing to restore: ").append(p).append('\n'); continue; }
                    boolean ok = true;
                    if (plain.exists()) ok = !real.exists() ? move(plain, real, log) : merge(plain, real);
                    log.append(ok ? "restored: " : "FAILED: ").append(p).append('\n');
                }
            } catch (Exception e) { log.append("error: ").append(p).append(" ").append(e).append('\n'); }
            if (hide) progItem(c);
        }
        if (!hide && !locked.isEmpty()) restoreContainers(c, locked, pin, log);
        } finally { progEnd(c, hide ? "hide" : "restore"); }
        if (!hide) for (File v : touched) cleanVault(v);
        return log.toString();
    }

    private static void restoreContainers(Context c, List<File> locked, String secret, StringBuilder log) {
        PrivateKey priv = null;
        String err = null;
        byte[] mine = bundle(c);
        try { priv = unlock(c, secret); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
        if (priv == null) { log.append("LOCKED (").append(err).append("): ").append(locked.size()).append(" locked container(s)\n"); return; }
        BooleanSupplier never = () -> false;
        int ok = 0;
        for (File f : locked) {
            try {
                long clash;
                try { clash = Vault.extractAuto(f, priv, never); }
                catch (Exception first) {
                    byte[] own = Vault.embeddedBundle(f);
                    if (own == null || mine == null || Arrays.equals(own, mine)) throw first;
                    PrivateKey alt;
                    try { alt = Vault.unlock(own, secret.toCharArray()); } catch (Exception e2) { throw first; }
                    clash = Vault.extractAuto(f, alt, never);
                }
                if (clash == 0) { f.delete(); ok++; }
                else log.append("  FAILED: kept ").append(f.getName()).append(" in .zorken: ").append(clash).append(" file(s) clash with different files already in place\n");
            } catch (Exception e) { log.append("  could not open ").append(f.getName()).append(": ").append(e).append('\n'); }
        }
        log.append("restored ").append(ok).append(" of ").append(locked.size()).append(" locked container(s)\n");
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
        if (paths.isEmpty() && containers(vaultDirs(paths)).isEmpty()) return save(c, "restore", "no files selected");
        return save(c, "restore", apply(paths, false, c, pin));
    }

    static List<File> lockedContainers(Context c) { return containers(vaultDirs(pathSet(c))); }

    static void indexAdd(Context c, String container, String itemName) {
        SharedPreferences sp = Common.prefs(c);
        Set<String> s = Common.strings(sp, IDX);
        s.add(container + "|" + itemName);
        sp.edit().putStringSet(IDX, s).apply();
    }

    static String indexName(Context c, String container) {
        for (String e : Common.strings(Common.prefs(c), IDX)) {
            int k = e.indexOf('|');
            if (k > 0 && e.substring(0, k).equals(container)) return e.substring(k + 1);
        }
        return null;
    }

    static void indexPrune(Context c, List<File> existing) {
        SharedPreferences sp = Common.prefs(c);
        Set<String> old = Common.strings(sp, IDX);
        if (old.isEmpty()) return;
        Set<String> names = new HashSet<>();
        for (File f : existing) names.add(f.getName());
        Set<String> keep = new HashSet<>();
        for (String e : old) { int k = e.indexOf('|'); if (k > 0 && names.contains(e.substring(0, k))) keep.add(e); }
        if (keep.size() != old.size()) sp.edit().putStringSet(IDX, keep).apply();
    }

    public static String restoreOneSync(Context c, File container, String secret) {
        applyActive.incrementAndGet();
        try {
            stopReq = true;
            synchronized (LOCK) {
                stopReq = false;
                if (!hasAccess()) return save(c, "restore", "No storage access. Grant 'All files access' to Zorken.");
                StringBuilder log = new StringBuilder();
                List<File> one = new ArrayList<>();
                one.add(container);
                progStart(c, "restore", Math.max(1, container.length()));
                try { restoreContainers(c, one, secret, log); } finally { progEnd(c, "restore"); }
                cleanVault(container.getParentFile());
                return save(c, "restore", log.toString());
            }
        } finally { applyActive.decrementAndGet(); }
    }
}
