package com.sidsaxena.daybook;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * One alarm for all reminders (tasks, diary, habits, important dates). It is set for the earliest upcoming
 * reminder; when it goes off, every reminder that is due is shown and the next alarm is set.
 */
final class Reminders {
    private Reminders() {}

    static final String ACTION_FIRE = "com.sidsaxena.daybook.FIRE";
    static final String ACTION_TASK_DONE = "com.sidsaxena.daybook.TASK_DONE";
    static final String ACTION_SNOOZE = "com.sidsaxena.daybook.SNOOZE";
    static final String ACTION_HABIT_DONE = "com.sidsaxena.daybook.HABIT_DONE";

    static final class Occ {
        long time;
        String channel, title, text, module, kind;
        long id;

        Occ(long time, String channel, String title, String text, String module, String kind, long id) {
            this.time = time;
            this.channel = channel;
            this.title = title;
            this.text = text;
            this.module = module;
            this.kind = kind;
            this.id = id;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("daybook", Context.MODE_PRIVATE);
    }

    /** Diary reminder time in minutes after midnight, -1 = off. */
    static int diaryTime(Context c) {
        return prefs(c).getInt("diaryRemind", -1);
    }

    static int datesTime(Context c) {
        return prefs(c).getInt("datesRemind", 9 * 60);
    }

    /** All reminders with from < time <= to. */
    static List<Occ> occurrences(Context ctx, long from, long to) {
        List<Occ> out = new ArrayList<Occ>();
        Db db = new Db(ctx);
        Store st = new Store(db);
        for (Db.Task t : db.tasks()) {
            long at = t.remindAt();
            if (at > from && at <= to) {
                out.add(new Occ(at, "tasks", t.title, (t.priority == 3 ? "High priority · " : "")
                        + (t.repeat.isEmpty() ? "Task reminder" : Recurrence.label(t.repeat)), "tasks", "task", t.id));
            }
        }
        int diary = diaryTime(ctx);
        List<Long> days = days(from, to);
        if (diary >= 0) {
            for (long d : days) {
                long at = d + diary * 60000L + Db.dstFix(d, diary);
                if (at > from && at <= to && db.diaryFor(d) == null) {
                    out.add(new Occ(at, "diary", "How was your day?", "Take a minute to write today's diary entry.",
                            "diary", "diary", d));
                }
            }
        }
        for (Store.Habit h : st.habits(false)) {
            for (long d : days) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(d);
                if (!HabitLogic.scheduled(h.days, c)) continue;
                for (int minutes : habitTimes(h.remind)) {
                    long at = d + minutes * 60000L + Db.dstFix(d, minutes);
                    if (at <= from || at > to) continue;
                    int count = st.habitCount(h.id, d);
                    if (!h.quit && count >= h.target) continue; // already done today
                    String title = (h.emoji.isEmpty() ? "" : h.emoji + " ") + h.name;
                    String text = h.quit ? "Keep your streak going today." : h.target > 1
                            ? count + " of " + h.target + " done today" : "Time for your habit.";
                    out.add(new Occ(at, "habits", title, text, "habits", "habit", h.id));
                }
            }
        }
        int dt = datesTime(ctx);
        long today = Util.startOfDay(System.currentTimeMillis());
        for (Store.DateItem di : st.dates()) {
            if (di.remindDays < 0) continue;
            long occ = Store.nextOccurrence(di, today);
            if (occ < today) continue;
            int[] before = di.remindDays == 0 ? new int[]{0} : new int[]{di.remindDays, 0};
            for (int b : before) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(occ);
                c.add(Calendar.DAY_OF_MONTH, -b);
                long at = c.getTimeInMillis() + dt * 60000L + Db.dstFix(c.getTimeInMillis(), dt);
                if (at > from && at <= to) {
                    String when = b == 0 ? "Today" : b == 1 ? "Tomorrow" : "In " + b + " days";
                    String age = di.yearly && di.year > 0 && di.kind.equals("Birthday")
                            ? " (turns " + (yearOf(occ) - di.year) + ")" : "";
                    out.add(new Occ(at, "dates", di.title, when + " · " + di.kind + age, di.fromWallet ? "wallet" : "dates",
                            di.fromWallet ? "wallet" : "date", di.fromWallet ? di.walletId : di.id));
                }
            }
        }
        return out;
    }

    private static int yearOf(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR);
    }

    /** "08:00,20:00" → [480, 1200]; "every:120:08:00-20:00" → every 2 hours from 8 to 20. */
    static List<Integer> habitTimes(String spec) {
        List<Integer> out = new ArrayList<Integer>();
        if (spec == null || spec.isEmpty()) return out;
        try {
            if (spec.startsWith("every:")) {
                String[] p = spec.split(":", 3);
                int step = Math.max(15, Integer.parseInt(p[1]));
                String[] range = p[2].split("-");
                int a = minutes(range[0]), b = minutes(range[1]);
                for (int m = a; m <= b; m += step) out.add(m);
            } else {
                for (String t : spec.split(",")) if (!t.trim().isEmpty()) out.add(minutes(t.trim()));
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    static int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    }

    private static List<Long> days(long from, long to) {
        List<Long> out = new ArrayList<Long>();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Util.startOfDay(from));
        for (int i = 0; i < 40 && c.getTimeInMillis() <= to; i++) {
            out.add(c.getTimeInMillis());
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    // ---------------------------------------------------------------- scheduling

    /** Re-plans the alarm (call after any change) and refreshes home-screen widgets. */
    static void refresh(Context ctx) {
        schedule(ctx);
        Widgets.updateAll(ctx);
    }

    static void schedule(Context ctx) {
        long now = System.currentTimeMillis();
        long next = 0;
        for (Occ o : occurrences(ctx, now, now + 8L * 24 * 3600 * 1000)) if (next == 0 || o.time < next) next = o.time;
        if (next == 0) next = now + 24L * 3600 * 1000; // look again tomorrow
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, new Intent(ctx, Notify.class).setAction(ACTION_FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    /** Shows everything due since the last check, then plans the next alarm. */
    static void fire(Context ctx) {
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(ctx);
        long last = p.getLong("lastFired", now - 60000);
        last = Math.max(last, now - 12L * 3600 * 1000); // after the phone was off: at most the last 12 hours
        for (Occ o : occurrences(ctx, last, now + 30000)) show(ctx, o);
        p.edit().putLong("lastFired", now + 30000).apply();
        refresh(ctx);
    }

    // ---------------------------------------------------------------- notifications

    static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("tasks", "Task reminders", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel("habits", "Habit reminders", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel("diary", "Diary reminder", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel("dates", "Birthdays & important dates", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static int notificationId(String kind, long id) {
        return (kind + ":" + id).hashCode();
    }

    @SuppressWarnings("deprecation")
    private static void show(Context ctx, Occ o) {
        createChannels(ctx);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, o.channel) : new Notification.Builder(ctx);
        Intent open = new Intent(ctx, MainActivity.class).putExtra("module", o.module).putExtra("item", o.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int nid = notificationId(o.kind, o.id);
        b.setSmallIcon(R.drawable.ic_todo)
                .setColor(ctx.getResources().getColor(R.color.primary))
                .setContentTitle(o.title)
                .setContentText(o.text)
                .setAutoCancel(true)
                .setWhen(o.time)
                .setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(ctx, nid, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(o.channel.equals("tasks") ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT)
                .setDefaults(Notification.DEFAULT_ALL);
        if (o.kind.equals("task")) {
            b.addAction(new Notification.Action.Builder(null, "Done", action(ctx, ACTION_TASK_DONE, o.id, nid)).build());
            b.addAction(new Notification.Action.Builder(null, "Snooze 1 h", action(ctx, ACTION_SNOOZE, o.id, nid)).build());
        } else if (o.kind.equals("habit")) {
            b.addAction(new Notification.Action.Builder(null, "Done ✓", action(ctx, ACTION_HABIT_DONE, o.id, nid)).build());
        }
        try {
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(nid, b.build());
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    private static PendingIntent action(Context ctx, String action, long id, int nid) {
        Intent i = new Intent(ctx, Notify.class).setAction(action).putExtra("id", id).putExtra("nid", nid);
        return PendingIntent.getBroadcast(ctx, nid ^ action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancel(Context ctx, int nid) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(nid);
    }
}
