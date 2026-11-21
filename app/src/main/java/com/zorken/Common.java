package com.zorken;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Common {
    public static final int MODULE_VER = 7;
    public static final String PINHASH = "[0-9a-f]{32}\\$[0-9a-f]{64}";
    static final int PIN_ITER = 100000;
    private static final SecureRandom RND = new SecureRandom();
    public static final String PERM = "com.zorken.permission.IPC";

    public static final String ACT_CMD = "com.zorken.CMD";
    public static final String ACT_HIDE_FILES = "com.zorken.HIDE_FILES";
    public static final String ACT_PONG = "com.zorken.PONG";
    public static final String ACT_ACK = "com.zorken.ACK";
    public static final String ACT_LOG = "com.zorken.LOG";

    public static long backoffSec(int fails) {
        return fails < 5 ? 0 : Math.min(900L, 15L << Math.min(6, fails - 5));
    }

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("zk", 0); }

    public static Set<String> strings(SharedPreferences sp, String key) {
        return new HashSet<>(sp.getStringSet(key, new HashSet<String>()));
    }

    public static int limit(int n) { return Math.max(1, Math.min(10, n)); }

    public static String randHex(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return hex(b);
    }

    public static File randomFile(File dir, String suffix) {
        File f;
        do { f = new File(dir, randHex(8) + suffix); } while (f.exists());
        return f;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    public static String hex(byte[] d) {
        char[] o = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) { o[2 * i] = HEX[(d[i] >> 4) & 15]; o[2 * i + 1] = HEX[d[i] & 15]; }
        return new String(o);
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static byte[] pbkdf2(char[] secret, byte[] salt, int iter) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iter, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static byte[] stretch(String pin, byte[] salt) throws GeneralSecurityException {
        return pbkdf2(pin.toCharArray(), salt, PIN_ITER);
    }

    public static String hashPin(String pin) {
        try {
            byte[] salt = new byte[16];
            RND.nextBytes(salt);
            return hex(salt) + "$" + hex(stretch(pin, salt));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static boolean checkPin(String pin, String stored) {
        if (pin == null || stored == null || !stored.matches(PINHASH)) return false;
        try {
            int k = stored.indexOf('$');
            return MessageDigest.isEqual(stretch(pin, unhex(stored.substring(0, k))), unhex(stored.substring(k + 1)));
        } catch (GeneralSecurityException e) { return false; }
    }

    public static boolean isCoreName(String p) {
        return p.equals("com.zorken") || p.equals("android") || p.equals("com.android.systemui")
            || p.equals("com.android.settings") || p.equals("com.android.phone")
            || p.equals("com.android.server.telecom") || p.startsWith("com.android.providers.")
            || p.equals("com.google.android.gms") || p.equals("com.google.android.gsf")
            || p.contains("permissioncontroller") || p.contains("packageinstaller")
            || p.contains(".dialer") || p.equals("com.android.launcher") || p.equals("com.android.launcher3");
    }
}
