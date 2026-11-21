package com.zorken;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public class EncryptJob extends JobService {
    static final int ID = 7001;
    private volatile boolean cancel;

    public static void schedule(Context c) { schedule(c, true); }

    public static void schedule(Context c, boolean allowService) {
        try {
            if ((Shell.pending(c) == 0 && !FreeSpaceWiper.isDue(c)) || LockService.RUNNING.get()) return;
            if (allowService && LockService.start(c)) return;
            scheduleJob(c);
        } catch (Throwable t) { AppLog.add(c, "lock", "Could not schedule the background job: " + t); }
    }

    static void scheduleJob(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(ID) != null) return;
            ComponentName cn = new ComponentName(c, EncryptJob.class);
            try { js.schedule(new JobInfo.Builder(ID, cn).setExpedited(true).build()); }
            catch (Exception e) { js.schedule(new JobInfo.Builder(ID, cn).build()); }
        } catch (Throwable t) { AppLog.add(c, "lock", "Could not start the background job: " + t); }
    }

    @Override public boolean onStartJob(final JobParameters p) {
        cancel = false;
        new Thread(() -> {
            try {
                Shell.protectAll(this, () -> cancel);
            } catch (Throwable t) { AppLog.add(this, "lock", "background job failed: " + t); }
            jobFinished(p, cancel);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { cancel = true; return true; }
}
