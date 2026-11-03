package com.zorken;

import android.app.Application;

public class ZorkenApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                AppLog.add(ZorkenApp.this, "crash", "thread " + t.getName() + ": " + android.util.Log.getStackTraceString(e));
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}
