package com.zorken;
import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;
public class Common {
    public static final int MODULE_VER = 4;
    public static final String HEX64 = "[0-9a-f]{64}";

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("zk", 0); }

    public static int limit(int n) { return Math.max(1, Math.min(10, n)); }

    public static String randHex(int n) {
        byte[] b = new byte[n];
        new java.security.SecureRandom().nextBytes(b);
        return hex(b);
    }
    public static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }
    public static byte[] unhex(String s) {
        if (s == null || s.length() < 2 || s.length() % 2 != 0) return null;
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) {
            int h = Character.digit(s.charAt(2 * i), 16), l = Character.digit(s.charAt(2 * i + 1), 16);
            if (h < 0 || l < 0) return null;
            o[i] = (byte) (h * 16 + l);
        }
        return o;
    }

    public static String sha(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(("zk:" + s).getBytes("UTF-8")));
        } catch (Exception e) { return ""; }
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
