package com.zorken;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class FreeSpaceWiper {
    public static final String PREF_ENABLED = "wipe_free";
    static final String PREF_DUE = "wipe_due";

    static final String DIR = ".zk_wipe";
    static final long CHUNK = 256L << 20;
    static final int BUF = 1 << 20;
    static final long RESERVE = 256L << 20;

    static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private FreeSpaceWiper() {}

    static boolean isEnabled(Context c) { return Common.prefs(c).getBoolean(PREF_ENABLED, false); }

    static boolean isDue(Context c) {
        SharedPreferences sp = Common.prefs(c);
        return sp.getBoolean(PREF_ENABLED, false) && sp.getBoolean(PREF_DUE, false);
    }

    static void markDue(Context c) {
        if (isEnabled(c)) Common.prefs(c).edit().putBoolean(PREF_DUE, true).apply();
    }

    static void clearDue(Context c) { Common.prefs(c).edit().remove(PREF_DUE).apply(); }

    static void runIfDue(Context c, BooleanSupplier stop) {
        if (!isDue(c) || Shell.pending(c) != 0 || Shell.applyActive.get() > 0) return;
        if (!Shell.hasAccess()) { AppLog.add(c, "wipe", "Skipped: no storage access"); return; }
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            if (wipe(c, stop)) clearDue(c);
        } finally { RUNNING.set(false); }
    }

    static boolean secureDelete(Context c, File f) {
        try {
            if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isFile()) {
                long len = f.length();
                if (len > 0) {
                    Cipher ci = randomStream();
                    byte[] zeros = new byte[BUF], buf = new byte[BUF];
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                        long done = 0;
                        while (done < len) {
                            int n = (int) Math.min(BUF, len - done);
                            n = ci.update(zeros, 0, n, buf);
                            raf.write(buf, 0, n);
                            done += n;
                        }
                        raf.getFD().sync();
                    }
                }
                File dir = f.getParentFile(), t = f;
                if (dir != null) {
                    File rn = Common.randomFile(dir, "");
                    if (f.renameTo(rn)) t = rn;
                }
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(t, "rw")) { raf.setLength(0); raf.getFD().sync(); }
                return t.delete();
            }
        } catch (Throwable e) {
            AppLog.add(c, "wipe", "Secure delete failed for one file, using normal delete: " + e);
        }
        return f.delete();
    }

    static boolean secureDeleteDir(File d) {
        File dir = d.getParentFile(), t = d;
        if (dir != null) {
            File rn = Common.randomFile(dir, "");
            if (d.renameTo(rn)) t = rn;
        }
        if (t.delete()) return true;
        if (t != d) t.renameTo(d);
        return false;
    }

    static List<File> volumes(Context c) {
        List<File> out = new ArrayList<>();
        java.util.Set<File> roots = new java.util.LinkedHashSet<>();
        for (File vault : Shell.vaultDirs(Shell.pathSet(c))) {
            File root = vault.getParentFile();
            if (root != null && root.isDirectory()) roots.add(root);
        }
        for (File root : roots) out.add(new File(root, DIR));
        return out;
    }

    static void cleanupStale(Context c) {
        try {
            for (File d : volumes(c)) if (d.exists()) removeFiller(d);
        } catch (Throwable t) { AppLog.add(c, "wipe", "Cleanup of old filler files failed: " + t); }
    }

    static boolean wipe(Context c, BooleanSupplier stop) {
        List<File> vols = volumes(c);
        if (vols.isEmpty()) return false;
        long total = 0;
        for (File d : vols) { File p = d.getParentFile(); if (p != null && p.isDirectory()) total += Math.max(0, free(p) - RESERVE); }
        if (total <= 0) { AppLog.add(c, "wipe", "Nothing to wipe: free space is within the safety reserve"); return true; }

        AppLog.add(c, "wipe", "Started: about " + Shell.mb(total) + " of free space");
        Shell.progStart(c, "wipe", total);
        boolean complete = true;
        long written = 0;
        try {
            for (File dir : vols) {
                File parent = dir.getParentFile();
                if (parent == null || !parent.isDirectory()) continue;
                long n = fill(c, dir, stop);
                written += Math.max(0, n);
                if (n < 0) { complete = false; break; }
            }
        } finally {
            Shell.progEnd(c, "wipe");
        }
        AppLog.add(c, "wipe", (complete ? "Finished: " : "Stopped: ") + Shell.mb(written) + " overwritten");
        return complete;
    }

    private static long fill(Context c, File dir, BooleanSupplier stop) {
        long written = 0;
        boolean finished = false;
        try {
            if (dir.exists()) removeFiller(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);

            Cipher ci = randomStream();
            byte[] zeros = new byte[BUF], buf = new byte[BUF];

            int idx = 0;
            while (!stop.getAsBoolean()) {
                if (free(dir) - RESERVE <= 0) { finished = true; break; }
                File f = new File(dir, (idx++) + ".bin");
                long inChunk = 0;
                try (FileOutputStream o = new FileOutputStream(f)) {
                    while (inChunk < CHUNK) {
                        if (stop.getAsBoolean()) break;
                        long room = free(dir) - RESERVE;
                        if (room <= 0) { finished = true; break; }
                        int n = (int) Math.min(Math.min(BUF, CHUNK - inChunk), room);
                        n = ci.update(zeros, 0, n, buf);
                        o.write(buf, 0, n);
                        inChunk += n; written += n;
                        progress(n);
                    }
                    o.getFD().sync();
                } catch (IOException e) {
                    if (isNoSpace(e)) { finished = true; }
                    else throw e;
                }
                if (finished) break;
            }
        } catch (Throwable t) {
            AppLog.add(c, "wipe", "Failed: " + t);
        } finally {
            removeFiller(dir);
        }
        return finished ? written : -1;
    }

    private static Cipher randomStream() throws GeneralSecurityException {
        byte[] key = new byte[16], iv = new byte[16];
        SecureRandom r = new SecureRandom();
        r.nextBytes(key); r.nextBytes(iv);
        Cipher ci = Cipher.getInstance("AES/CTR/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return ci;
    }

    private static void progress(long n) {
        java.util.function.LongConsumer p = Vault.PROGRESS;
        if (p != null && n > 0) p.accept(n);
    }

    private static long free(File f) {
        try { return new StatFs(f.getPath()).getAvailableBytes(); }
        catch (Throwable t) { return f.getUsableSpace(); }
    }

    private static boolean isNoSpace(IOException e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("enospc") || m.contains("no space left");
    }

    private static void removeFiller(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) k.delete();
        dir.delete();
    }
}
