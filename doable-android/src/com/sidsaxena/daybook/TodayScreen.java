package com.sidsaxena.daybook;

import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The day at a glance: tasks due, habits, birthdays & dates, diary, the current trip and the shopping list. */
class TodayScreen extends Page {

    TodayScreen(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return a.getString(R.string.module_today);
    }

    @Override
    void render() {
        body.removeAllViews();
        long today = Util.startOfDay(System.currentTimeMillis());
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);

        TextView hello = new TextView(a);
        hello.setText((hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening") + "\n"
                + new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()));
        hello.setTextSize(22);
        hello.setTextColor(Util.color(a, R.color.text));
        hello.setPadding(dp(6), dp(12), dp(6), dp(4));
        body.addView(hello);

        quickButtons();
        tasks(today);
        habits(today);
        dates(today);
        diary(today);
        trip(today);
        shopping();
    }

    private void quickButtons() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(2));
        String[] labels = {"+ Task", "+ Note", "+ Item", "+ Expense"};
        final String[] modules = {"tasks", "notes", "grocery", "trips"};
        final String[] quick = {"task", "note", "grocery", "expense"};
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView b = pill(labels[i], false, new Runnable() {
                public void run() { a.go(modules[k], 0, quick[k]); }
            });
            row.addView(b);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        body.addView(hs);
    }

    private void tasks(final long today) {
        if (!a.isModuleVisible("tasks")) return;
        List<Db.Task> earlier = new ArrayList<Db.Task>(), due = new ArrayList<Db.Task>();
        for (Db.Task t : db.tasks()) {
            if (t.done || t.due == 0 || t.due > today) continue;
            if (t.due < today) earlier.add(t);
            else due.add(t);
        }
        // Oldest first, so nothing slips further down the longer it waits.
        Collections.sort(earlier, new Comparator<Db.Task>() {
            public int compare(Db.Task x, Db.Task y) {
                if (x.due != y.due) return x.due < y.due ? -1 : 1;
                return y.priority - x.priority;
            }
        });
        Collections.sort(due, new Comparator<Db.Task>() {
            public int compare(Db.Task x, Db.Task y) {
                if (x.priority != y.priority) return y.priority - x.priority;
                return x.time - y.time;
            }
        });
        if (!earlier.isEmpty()) {
            header("⏰ Still open from earlier days · " + earlier.size()).setTextColor(Util.color(a, R.color.overdue));
            LinearLayout card = card();
            taskRows(card, earlier, today, 15);
            if (earlier.size() > 1) {
                link(card, "Move all " + earlier.size() + " to today", new Runnable() {
                    public void run() { moveAll(earlier(today), today); }
                });
            }
        }
        header("To-do today" + (due.isEmpty() ? "" : " · " + due.size()));
        LinearLayout card = card();
        if (due.isEmpty()) {
            row(card, "🎉", "Nothing else due today", "Tap to see all tasks", null, new Runnable() {
                public void run() { a.go("tasks", 0, null); }
            }, null);
            return;
        }
        taskRows(card, due, today, 10);
    }

    private List<Db.Task> earlier(long today) {
        List<Db.Task> out = new ArrayList<Db.Task>();
        for (Db.Task t : db.tasks()) if (!t.done && t.due != 0 && t.due < today) out.add(t);
        return out;
    }

    private void moveAll(List<Db.Task> tasks, long day) {
        for (Db.Task t : tasks) {
            t.due = day;
            t.snooze = 0;
            db.saveTask(t);
        }
        Reminders.refresh(a);
        render();
    }

    private void taskRows(LinearLayout card, List<Db.Task> list, final long today, int max) {
        int shown = 0;
        for (final Db.Task t : list) {
            if (shown++ == max) {
                link(card, "Show all " + list.size() + " in To-do", new Runnable() {
                    public void run() { a.go("tasks", 0, null); }
                });
                break;
            }
            int late = -Util.daysFromToday(t.due);
            String sub = late <= 0 ? "" : (late == 1 ? "From yesterday" : late + " days overdue · " + Util.shortDate(t.due));
            if (t.time >= 0) sub += (sub.isEmpty() ? "" : " · ") + "🔔 " + TasksScreen.clock(t.time);
            if (!t.repeat.isEmpty()) sub += (sub.isEmpty() ? "" : " · ") + "🔁";
            final TextView box = pill("✓", false, null);
            LinearLayout r = row(card, t.priority == 3 ? "⚑" : t.priority == 2 ? "◆" : "○", t.title, sub, box, new Runnable() {
                public void run() { a.go("tasks", t.id, null); }
            }, late > 0 ? new Runnable() {
                public void run() { reschedule(t, today); }
            } : null);
            if (late > 0) ((TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(1)).setTextColor(Util.color(a, R.color.overdue));
            box.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    TaskOps.setDone(db, t, true);
                    Reminders.refresh(a);
                    render();
                }
            });
        }
    }

    /** Long-press on an overdue task: move it, or open it. */
    private void reschedule(final Db.Task t, final long today) {
        new android.app.AlertDialog.Builder(a)
                .setTitle(t.title)
                .setItems(new String[]{"Move to today", "Move to tomorrow", "Move to next week", "Open"},
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which == 3) {
                                    a.go("tasks", t.id, null);
                                    return;
                                }
                                Calendar c = Calendar.getInstance();
                                c.setTimeInMillis(today);
                                c.add(Calendar.DAY_OF_MONTH, which == 0 ? 0 : which == 1 ? 1 : 7);
                                List<Db.Task> one = new ArrayList<Db.Task>();
                                one.add(t);
                                moveAll(one, Util.startOfDay(c.getTimeInMillis()));
                            }
                        })
                .show();
    }

    private void habits(final long today) {
        if (!a.isModuleVisible("habits")) return;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(today);
        List<Store.Habit> list = new ArrayList<Store.Habit>();
        for (Store.Habit h : st.habits(false)) if (HabitLogic.scheduled(h.days, c)) list.add(h);
        if (list.isEmpty()) return;
        header("Habits");
        LinearLayout card = card();
        for (final Store.Habit h : list) {
            final int n = st.habitCount(h.id, today);
            int[] streak = HabitLogic.streaks(h.quit, h.target, h.days, st.habitLog(h.id), today, h.created);
            String sub = (h.quit ? (n == 0 ? "On track today" : "Slipped today") : h.target > 1 ? n + " of " + h.target + " today" : n > 0 ? "Done today" : "Not done yet")
                    + (streak[0] > 0 ? " · 🔥 " + streak[0] : "");
            TextView end = h.quit ? null : pill(n >= h.target ? "✓" : "+1", n >= h.target, new Runnable() {
                public void run() {
                    st.setHabitCount(h.id, today, n + 1);
                    Reminders.refresh(a);
                    render();
                }
            });
            row(card, h.emoji.isEmpty() ? (h.quit ? "🚫" : "✔") : h.emoji, h.name, sub, end, new Runnable() {
                public void run() { a.go("habits", h.id, null); }
            }, null);
        }
        yesterdayHabits(card, today);
    }

    /** Build habits that were due yesterday but never logged: offer to log them now. */
    private void yesterdayHabits(LinearLayout card, long today) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(today);
        c.add(Calendar.DAY_OF_MONTH, -1);
        final long yesterday = Util.startOfDay(c.getTimeInMillis());
        final List<Store.Habit> missed = new ArrayList<Store.Habit>();
        for (Store.Habit h : st.habits(false)) {
            if (!h.quit && h.created <= yesterday && HabitLogic.scheduled(h.days, c) && st.habitCount(h.id, yesterday) < h.target) missed.add(h);
        }
        if (missed.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (Store.Habit h : missed) names.append(names.length() == 0 ? "" : ", ").append(h.name);
        row(card, "↩", "Yesterday not logged", names + " · tap if you did them", null, new Runnable() {
            public void run() {
                final String[] items = new String[missed.size()];
                final boolean[] on = new boolean[missed.size()];
                for (int i = 0; i < items.length; i++) items[i] = missed.get(i).name;
                new android.app.AlertDialog.Builder(a)
                        .setTitle("Done yesterday?")
                        .setMultiChoiceItems(items, on, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(android.content.DialogInterface d, int which, boolean checked) { on[which] = checked; }
                        })
                        .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                for (int i = 0; i < on.length; i++) {
                                    if (on[i]) st.setHabitCount(missed.get(i).id, yesterday, missed.get(i).target);
                                }
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }, null);
    }

    private void dates(long today) {
        if (!a.isModuleVisible("dates")) return;
        final List<Object[]> soon = new ArrayList<Object[]>();
        for (Store.DateItem d : st.dates()) {
            long occ = Store.nextOccurrence(d, today);
            int days = Util.daysFromToday(occ);
            // Upcoming week, plus things that passed without being dealt with: one-off dates of the last
            // week (contract renewal, appointment) and documents that have expired.
            if ((days >= 0 && days <= 7) || (days < 0 && (d.fromWallet || days >= -7))) soon.add(new Object[]{occ, d});
        }
        if (soon.isEmpty()) return;
        Collections.sort(soon, new Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) { return Long.compare((Long) x[0], (Long) y[0]); }
        });
        header("Dates");
        LinearLayout card = card();
        for (Object[] o : soon) {
            final Store.DateItem d = (Store.DateItem) o[1];
            long occ = (Long) o[0];
            LinearLayout r = row(card, DatesScreen.emoji(d), d.title, DatesScreen.when(d, occ), null, new Runnable() {
                public void run() {
                    if (d.fromWallet) a.go("wallet", d.walletId, null);
                    else a.go("dates", d.id, null);
                }
            }, null);
            if (occ < today) ((TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(1)).setTextColor(Util.color(a, R.color.overdue));
        }
    }

    private void diary(final long today) {
        if (!a.isModuleVisible("diary")) return;
        Db.Diary d = db.diaryFor(today);
        header("My Diary");
        LinearLayout card = card();
        if (d == null || d.isEmpty()) {
            row(card, "📝", "How was your day?", "Write today's entry", null, new Runnable() {
                public void run() { a.go("diary", 0, "diary"); }
            }, null);
        } else {
            String text = d.text.trim();
            if (text.length() > 80) text = text.substring(0, 80) + "…";
            row(card, d.mood > 0 ? DiaryScreen.MOODS[d.mood] : "📖", "Today's entry", text, null, new Runnable() {
                public void run() { a.go("diary", today, null); }
            }, null);
        }
        // Yesterday left blank (only once the diary is in use, so new users aren't nagged).
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(today);
        c.add(Calendar.DAY_OF_MONTH, -1);
        final long yesterday = Util.startOfDay(c.getTimeInMillis());
        Db.Diary y = db.diaryFor(yesterday);
        if ((y == null || y.isEmpty()) && !db.diary().isEmpty()) {
            row(card, "↩", "Yesterday has no entry", "Write it while you still remember", null, new Runnable() {
                public void run() { a.go("diary", yesterday, null); }
            }, null);
        }
    }

    private void trip(long today) {
        if (!a.isModuleVisible("trips")) return;
        final Db.Trip t = TripsScreen.currentTrip(db);
        if (t == null || t.start == 0) return;
        int days = Util.daysFromToday(t.start);
        boolean now = t.start <= today && (t.finish == 0 || t.finish >= today);
        if (!now && (days < 0 || days > 30)) return;
        header("Trip");
        String sub = now ? "Happening now" + (t.destination.isEmpty() ? "" : " · " + t.destination)
                : (days == 1 ? "Starts tomorrow" : "Starts in " + days + " days") + (t.destination.isEmpty() ? "" : " · " + t.destination);
        Store.Checklist packing = st.checklistForTrip(t.id);
        if (!now && packing != null && packing.total > 0) sub += " · packed " + packing.done + "/" + packing.total;
        row(card(), t.kind == Db.BUSINESS ? "💼" : "🧳", t.name, sub, null, new Runnable() {
            public void run() { a.go("trips", t.id, null); }
        }, null);
    }

    private void shopping() {
        if (!a.isModuleVisible("grocery")) return;
        int n = 0;
        for (Db.Item it : db.items()) if (!it.done) n++;
        if (n == 0) return;
        header("Shopping");
        row(card(), "🛒", n + " item" + (n == 1 ? "" : "s") + " to buy", "Open the shopping list", null, new Runnable() {
            public void run() { a.go("grocery", 0, null); }
        }, null);
    }
}
