package com.zorken;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.function.BooleanSupplier;

public class EncryptJob extends JobService {
    static final int ID = 7001;
    private volatile boolean cancel;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (Shell.pending(c) == 0 || LockService.RUNNING.get()) return;
            if (LockService.start(c)) return;
            if (js == null || js.getPendingJob(ID) != null) return;
            ComponentName cn = new ComponentName(c, EncryptJob.class);
            try { js.schedule(new JobInfo.Builder(ID, cn).setExpedited(true).build()); }
            catch (Exception e) { js.schedule(new JobInfo.Builder(ID, cn).build()); }
        } catch (Throwable ignored) {}
    }

    @Override public boolean onStartJob(final JobParameters p) {
        cancel = false;
        new Thread(new Runnable() { public void run() {
            try {
                Shell.protectAll(EncryptJob.this, new BooleanSupplier() { public boolean getAsBoolean() { return cancel; } });
            } catch (Throwable t) { AppLog.add(EncryptJob.this, "lock", "background job failed: " + t); }
            jobFinished(p, cancel);
        } }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { cancel = true; return true; }
}
