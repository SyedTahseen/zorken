package com.zorken;

import android.app.Application;

public class ZorkenApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        new Thread(() -> FreeSpaceWiper.cleanupStale(this)).start();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            AppLog.add(this, "crash", "thread " + t.getName() + ": " + android.util.Log.getStackTraceString(e));
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
