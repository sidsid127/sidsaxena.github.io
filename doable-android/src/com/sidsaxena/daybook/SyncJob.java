package com.sidsaxena.daybook;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Background sync about every 15 minutes when online (Android's shortest interval), so reminders and widgets follow changes made on other
 * devices even when Doable isn't opened. It also keeps households in step and finishes invitations sent to people
 * who joined Doable meanwhile (their key has to be wrapped by a member's device).
 */
public class SyncJob extends JobService {
    private static final int ID = 4711;

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    static void cancel(Context c) {
        ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final boolean sync = SyncEngine.enabled(this) && SyncEngine.syncKey(this) != null;
        final boolean sharing = Account.signedIn(this);
        if (!sync && !sharing) return false;
        new Thread(new Runnable() {
            public void run() {
                if (sync) {
                    try {
                        SyncEngine.run(SyncJob.this);
                    } catch (Exception ignored) {
                        // shown in the sync status next time Doable opens
                    }
                }
                if (sharing) {
                    try {
                        Spaces.refresh(SyncJob.this);
                        HouseLog.notices(SyncJob.this);
                        Spaces.round(SyncJob.this);
                        HouseLog.announce(SyncJob.this, false);
                    } catch (Exception ignored) {
                        // next time
                    }
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
