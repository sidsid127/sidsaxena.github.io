package com.sidsaxena.daybook;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Background sync about every 2 hours when online, so reminders and widgets follow changes made on other
 * devices even when Doable isn't opened.
 */
public class SyncJob extends JobService {
    private static final int ID = 4711;

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(2 * 3600 * 1000L)
                .setPersisted(true)
                .build());
    }

    static void cancel(Context c) {
        ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!SyncEngine.enabled(this) || SyncEngine.syncKey(this) == null) return false;
        new Thread(new Runnable() {
            public void run() {
                try {
                    SyncEngine.run(SyncJob.this);
                } catch (Exception ignored) {
                    // shown in the sync status next time Doable opens
                }
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
