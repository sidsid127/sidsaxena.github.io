package com.sidsaxena.daybook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarm and notification buttons (Done, Snooze). Not exported. */
public class Notify extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, final Intent intent) {
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    handle(ctx.getApplicationContext(), intent);
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }

    private static void handle(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        long id = intent.getLongExtra("id", 0);
        Db db = new Db(ctx);
        if (Reminders.ACTION_FIRE.equals(action)) {
            Reminders.fire(ctx);
            return;
        }
        Reminders.cancel(ctx, intent.getIntExtra("nid", 0));
        if (Reminders.ACTION_TASK_DONE.equals(action)) {
            Db.Task t = db.task(id);
            if (t != null) TaskOps.setDone(db, t, true);
        } else if (Reminders.ACTION_SNOOZE.equals(action)) {
            Db.Task t = db.task(id);
            if (t != null) {
                t.snooze = System.currentTimeMillis() + 3600 * 1000;
                db.saveTask(t);
            }
        } else if (Reminders.ACTION_HABIT_DONE.equals(action)) {
            Store st = new Store(db);
            long today = Util.startOfDay(System.currentTimeMillis());
            st.setHabitCount(id, today, st.habitCount(id, today) + 1);
        } else if (Widgets.ACTION_TOGGLE.equals(action)) {
            Widgets.toggle(ctx, db, intent);
        }
        Reminders.refresh(ctx);
    }
}
