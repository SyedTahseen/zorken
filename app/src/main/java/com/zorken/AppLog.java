package com.zorken;

import android.content.Context;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppLog {
    static final String FILE = "zorken.log";
    static final long MAX = 256 * 1024, KEEP = 160 * 1024;
    static final Object LOCK = new Object();

    public static void add(Context c, String tag, String msg) {
        if (c == null || msg == null) return;
        try {
            synchronized (LOCK) {
                File f = new File(c.getApplicationContext().getFilesDir(), FILE);
                if (f.length() > MAX) trim(f);
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
                StringBuilder sb = new StringBuilder();
                for (String line : msg.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    sb.append(ts).append(" [").append(tag).append("] ").append(line).append('\n');
                }
                if (sb.length() == 0) return;
                try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(sb.toString().getBytes("UTF-8")); }
            }
        } catch (Throwable ignored) {}
    }

    public static String read(Context c) {
        try {
            synchronized (LOCK) {
                File f = new File(c.getApplicationContext().getFilesDir(), FILE);
                byte[] b = Shell.readAll(f);
                return b == null ? "" : new String(b, "UTF-8");
            }
        } catch (Throwable t) { return ""; }
    }

    public static void clear(Context c) {
        synchronized (LOCK) { new File(c.getApplicationContext().getFilesDir(), FILE).delete(); }
    }

    private static void trim(File f) {
        try {
            byte[] b = Shell.readAll(f);
            if (b == null || b.length <= KEEP) return;
            int from = b.length - (int) KEEP;
            while (from < b.length && b[from] != '\n') from++;
            from++;
            try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(b, Math.min(from, b.length), b.length - Math.min(from, b.length)); }
        } catch (Throwable ignored) {}
    }
}
