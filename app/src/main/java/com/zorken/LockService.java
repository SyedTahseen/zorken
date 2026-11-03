package com.zorken;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class LockService extends Service {
    static final String CH = "zk_lock";
    static final int NID = 7002;
    static final String ACT_RESTORE = "com.zorken.RESTORE";
    static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    static final AtomicBoolean RESTORING = new AtomicBoolean(false);
    static volatile String pendingPin;
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean cancel;
    private PowerManager.WakeLock wl;
    private Thread ticker;

    static boolean start(Context c) {
        try {
            c.getApplicationContext().startForegroundService(new Intent(c, LockService.class));
            return true;
        } catch (Throwable t) { return false; }
    }

    static boolean restore(Context c, String pin) {
        pendingPin = pin;
        try {
            c.getApplicationContext().startForegroundService(new Intent(c, LockService.class).setAction(ACT_RESTORE));
            return true;
        } catch (Throwable t) { pendingPin = null; return false; }
    }

    private Notification note(String text, int pct) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "Locking files", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder nb = new Notification.Builder(this, CH).setContentTitle("Zorken").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true);
        if (pct >= 0) nb.setProgress(100, pct, false);
        return nb.build();
    }

    private void enterForeground(String text) {
        if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(NID, note(text, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NID, note(text, -1));
    }

    private void done() {
        if (active.decrementAndGet() > 0) return;
        if (wl != null && wl.isHeld()) wl.release();
        stopForeground(true);
        stopSelf();
    }

    private void begin() {
        if (active.getAndIncrement() == 0) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zk:lock"); wl.acquire(6 * 60 * 60 * 1000L); }
            startTicker();
        }
    }

    private void startTicker() {
        final Context c = getApplicationContext();
        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ticker = new Thread(new Runnable() { public void run() {
            try {
                while (active.get() > 0) {
                    SharedPreferences sp = Common.prefs(c);
                    String ph = sp.getString("zk_phase", null);
                    long t = sp.getLong("zk_total", 0), d = sp.getLong("zk_done", 0);
                    if (ph != null && t > 0 && nm != null) {
                        int pct = (int) Math.min(100, d * 100 / t);
                        String what = "restore".equals(ph) ? "Restoring " : "hide".equals(ph) ? "Hiding " : "Locking ";
                        nm.notify(NID, note(what + sp.getString("zk_name", "") + " - " + pct + "%", pct));
                    }
                    Thread.sleep(1000);
                }
            } catch (Throwable ignored) {}
        } });
        ticker.setDaemon(true);
        ticker.start();
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        final boolean restore = i != null && ACT_RESTORE.equals(i.getAction());
        try { enterForeground(restore ? "Restoring files…" : "Locking hidden files…"); }
        catch (Throwable t) { AppLog.add(getApplicationContext(), restore ? "restore" : "lock", "could not start foreground service: " + t); if (active.get() == 0) stopSelf(); return START_NOT_STICKY; }
        final Context c = getApplicationContext();

        if (restore) {
            final String pin = pendingPin;
            pendingPin = null;
            if (pin == null || !RESTORING.compareAndSet(false, true)) { if (active.get() == 0) stopSelf(); return START_NOT_STICKY; }
            begin();
            new Thread(new Runnable() { public void run() {
                try {
                    String res = Shell.restoreSync(c, pin);
                    Common.prefs(c).edit().putBoolean("hidden", false).putBoolean("restore_fail", Shell.hasProblems(res)).commit();
                } catch (Throwable t) { AppLog.add(c, "restore", "service failed: " + t);
                } finally { RESTORING.set(false); done(); }
            } }).start();
            return START_NOT_STICKY;
        }

        if (!RUNNING.compareAndSet(false, true)) { if (active.get() == 0) stopSelf(); return START_NOT_STICKY; }
        begin();
        new Thread(new Runnable() { public void run() {
            try {
                Shell.protectAll(c, new BooleanSupplier() { public boolean getAsBoolean() { return cancel; } });
            } catch (Throwable t) { AppLog.add(c, "lock", "service failed: " + t);
            } finally { RUNNING.set(false); done(); }
        } }).start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { cancel = true; super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
