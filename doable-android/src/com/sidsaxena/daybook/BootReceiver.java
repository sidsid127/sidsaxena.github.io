package com.sidsaxena.daybook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-plans reminders after a restart, an app update or a clock / time-zone change. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            Reminders.fire(ctx.getApplicationContext());
        }
    }
}
