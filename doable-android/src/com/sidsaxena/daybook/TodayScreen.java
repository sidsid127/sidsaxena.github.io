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

    private void tasks(long today) {
        if (!a.isModuleVisible("tasks")) return;
        List<Db.Task> due = new ArrayList<Db.Task>();
        for (Db.Task t : db.tasks()) if (!t.done && t.due != 0 && t.due <= today) due.add(t);
        Collections.sort(due, new Comparator<Db.Task>() {
            public int compare(Db.Task x, Db.Task y) {
                if (x.priority != y.priority) return y.priority - x.priority;
                return x.due < y.due ? -1 : x.due > y.due ? 1 : 0;
            }
        });
        header("To-do today" + (due.isEmpty() ? "" : " · " + due.size()));
        LinearLayout card = card();
        if (due.isEmpty()) {
            row(card, "🎉", "Nothing due today", "Tap to see all tasks", null, new Runnable() {
                public void run() { a.go("tasks", 0, null); }
            }, null);
            return;
        }
        int shown = 0;
        for (final Db.Task t : due) {
            if (shown++ == 8) {
                link(card, "Show all " + due.size() + " tasks", new Runnable() {
                    public void run() { a.go("tasks", 0, null); }
                });
                break;
            }
            String sub = t.due < today ? Util.dueLabel(t.due) : "";
            if (t.time >= 0) sub += (sub.isEmpty() ? "" : " · ") + "🔔 " + TasksScreen.clock(t.time);
            if (!t.repeat.isEmpty()) sub += (sub.isEmpty() ? "" : " · ") + "🔁";
            final TextView box = pill("✓", false, null);
            LinearLayout r = row(card, t.priority == 3 ? "⚑" : t.priority == 2 ? "◆" : "○", t.title, sub, box, new Runnable() {
                public void run() { a.go("tasks", t.id, null); }
            }, null);
            if (t.due < today) ((TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(1)).setTextColor(Util.color(a, R.color.overdue));
            box.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    TaskOps.setDone(db, t, true);
                    Reminders.refresh(a);
                    render();
                }
            });
        }
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
    }

    private void dates(long today) {
        if (!a.isModuleVisible("dates")) return;
        final List<Object[]> soon = new ArrayList<Object[]>();
        for (Store.DateItem d : st.dates()) {
            long occ = Store.nextOccurrence(d, today);
            if (occ >= today && Util.daysFromToday(occ) <= 7) soon.add(new Object[]{occ, d});
        }
        if (soon.isEmpty()) return;
        Collections.sort(soon, new Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) { return Long.compare((Long) x[0], (Long) y[0]); }
        });
        header("Coming up");
        LinearLayout card = card();
        for (Object[] o : soon) {
            final Store.DateItem d = (Store.DateItem) o[1];
            long occ = (Long) o[0];
            row(card, DatesScreen.emoji(d), d.title, DatesScreen.when(d, occ), null, new Runnable() {
                public void run() {
                    if (d.fromWallet) a.go("wallet", d.walletId, null);
                    else a.go("dates", d.id, null);
                }
            }, null);
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
