package com.sidsaxena.daybook;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Home-screen widget: one widget type with several views (today, to-do, groceries, habits, dates, quick add). */
final class Widgets {
    private Widgets() {}

    static final String ACTION_TOGGLE = "com.sidsaxena.daybook.WIDGET_TOGGLE";
    static final String[] VIEWS = {"today", "tasks", "grocery", "habits", "dates", "quick"};
    static final String[] VIEW_NAMES = {"Today", "To-do", "Groceries", "Habits", "Upcoming dates", "Quick add buttons"};

    static String view(Context c, int widgetId) {
        return Reminders.prefs(c).getString("widget_" + widgetId, "today");
    }

    static final class Row {
        String kind, icon, title, sub = "";
        long id;

        Row(String kind, long id, String icon, String title, String sub) {
            this.kind = kind;
            this.id = id;
            this.icon = icon;
            this.title = title;
            this.sub = sub;
        }
    }

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
        for (int id : ids) update(ctx, m, id);
        if (ids.length > 0) m.notifyAppWidgetViewDataChanged(ids, R.id.w_list);
    }

    @SuppressWarnings("deprecation")
    static void update(Context ctx, AppWidgetManager m, int widgetId) {
        String v = view(ctx, widgetId);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        int idx = java.util.Arrays.asList(VIEWS).indexOf(v);
        rv.setTextViewText(R.id.w_title, idx == 0 ? "Doable · Today" : VIEW_NAMES[Math.max(0, idx)]);
        String module = v.equals("today") || v.equals("quick") ? "today" : v;
        rv.setOnClickPendingIntent(R.id.w_title, open(ctx, widgetId * 10 + 1, module, null));
        String quick = v.equals("grocery") ? "grocery" : v.equals("habits") ? null : v.equals("dates") ? null : "task";
        rv.setViewVisibility(R.id.w_add, quick == null ? View.GONE : View.VISIBLE);
        if (quick != null) rv.setOnClickPendingIntent(R.id.w_add, open(ctx, widgetId * 10 + 2, module, quick));
        Intent cfg = new Intent(ctx, WidgetConfig.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("doable://widget/" + widgetId));
        rv.setOnClickPendingIntent(R.id.w_settings, PendingIntent.getActivity(ctx, widgetId * 10 + 3, cfg,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        boolean isQuick = v.equals("quick");
        rv.setViewVisibility(R.id.w_quick, isQuick ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.w_list, isQuick ? View.GONE : View.VISIBLE);
        if (isQuick) {
            rv.setViewVisibility(R.id.w_empty, View.GONE);
            int[] btn = {R.id.q1, R.id.q2, R.id.q3, R.id.q4, R.id.q5};
            String[] what = {"task", "grocery", "note", "expense", "diary"};
            String[] mod = {"tasks", "grocery", "notes", "trips", "diary"};
            for (int i = 0; i < btn.length; i++) rv.setOnClickPendingIntent(btn[i], open(ctx, widgetId * 10 + 4 + i * 1000, mod[i], what[i]));
        } else {
            Intent svc = new Intent(ctx, WidgetService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .setData(Uri.parse("doable://widget-list/" + widgetId + "/" + v));
            rv.setRemoteAdapter(R.id.w_list, svc);
            rv.setEmptyView(R.id.w_list, R.id.w_empty);
            rv.setTextViewText(R.id.w_empty, v.equals("grocery") ? "Shopping list is empty" : v.equals("habits")
                    ? "No habits for today" : v.equals("dates") ? "No upcoming dates" : "All done for today 🎉");
            Intent tpl = new Intent(ctx, Notify.class).setAction(ACTION_TOGGLE);
            rv.setPendingIntentTemplate(R.id.w_list, PendingIntent.getBroadcast(ctx, widgetId, tpl,
                    PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)));
        }
        m.updateAppWidget(widgetId, rv);
    }

    static PendingIntent open(Context ctx, int code, String module, String quick) {
        Intent i = new Intent(ctx, MainActivity.class).putExtra("module", module)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (quick != null) i.putExtra("quick", quick);
        i.setData(Uri.parse("doable://open/" + code));
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** A row was tapped: tick the task / buy the item / count the habit. */
    static void toggle(Context ctx, Db db, Intent intent) {
        String kind = intent.getStringExtra("kind");
        long id = intent.getLongExtra("rowId", 0);
        if ("task".equals(kind)) {
            Db.Task t = db.task(id);
            if (t != null) TaskOps.setDone(db, t, true);
        } else if ("item".equals(kind)) {
            db.deleteItem(id);
        } else if ("habit".equals(kind)) {
            Store st = new Store(db);
            long today = Util.startOfDay(System.currentTimeMillis());
            st.setHabitCount(id, today, st.habitCount(id, today) + 1);
        }
    }

    // ---------------------------------------------------------------- data

    static List<Row> rows(Context ctx, String view) {
        Db db = Db.get(ctx);
        Store st = new Store(db);
        List<Row> out = new ArrayList<Row>();
        long today = Util.startOfDay(System.currentTimeMillis());
        if (view.equals("tasks") || view.equals("today")) {
            List<Db.Task> open = new ArrayList<Db.Task>();
            for (Db.Task t : db.tasks()) {
                if (t.done) continue;
                if (view.equals("today") && (t.due == 0 || t.due > today)) continue;
                open.add(t);
            }
            Collections.sort(open, new Comparator<Db.Task>() {
                public int compare(Db.Task x, Db.Task y) {
                    long dx = x.due > 0 ? x.due : Long.MAX_VALUE, dy = y.due > 0 ? y.due : Long.MAX_VALUE;
                    if (x.priority != y.priority) return y.priority - x.priority;
                    return dx < dy ? -1 : dx > dy ? 1 : 0;
                }
            });
            for (Db.Task t : open) {
                String sub = t.due == 0 ? "" : Util.dueLabel(t.due)
                        + (t.time >= 0 ? " · " + String.format(java.util.Locale.getDefault(), "%02d:%02d", t.time / 60, t.time % 60) : "");
                out.add(new Row("task", t.id, t.priority == 3 ? "⚑" : "○", t.title, sub));
            }
        }
        if (view.equals("habits") || view.equals("today")) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(today);
            for (Store.Habit h : st.habits(false)) {
                if (!HabitLogic.scheduled(h.days, c)) continue;
                int n = st.habitCount(h.id, today);
                if (h.quit) {
                    if (view.equals("habits")) out.add(new Row("info", h.id, h.emoji.isEmpty() ? "🚫" : h.emoji, h.name,
                            n == 0 ? "On track today" : "Slipped today"));
                    continue;
                }
                if (view.equals("today") && n >= h.target) continue;
                out.add(new Row("habit", h.id, h.emoji.isEmpty() ? "✔" : h.emoji, h.name,
                        n >= h.target ? "Done ✓" : h.target > 1 ? n + " of " + h.target + " · tap to count" : "Tap when done"));
            }
        }
        if (view.equals("dates") || view.equals("today")) {
            List<Object[]> upcoming = new ArrayList<Object[]>();
            for (Store.DateItem d : st.dates()) {
                long occ = Store.nextOccurrence(d, today);
                if (occ < today) continue;
                int days = Util.daysFromToday(occ);
                if (view.equals("today") && days > 3) continue;
                upcoming.add(new Object[]{occ, d});
            }
            Collections.sort(upcoming, new Comparator<Object[]>() {
                public int compare(Object[] x, Object[] y) { return Long.compare((Long) x[0], (Long) y[0]); }
            });
            for (int i = 0; i < Math.min(upcoming.size(), 20); i++) {
                long occ = (Long) upcoming.get(i)[0];
                Store.DateItem d = (Store.DateItem) upcoming.get(i)[1];
                int days = Util.daysFromToday(occ);
                out.add(new Row("info", d.id, d.kind.equals("Birthday") ? "🎂" : "📅", d.title,
                        (days == 0 ? "Today" : days == 1 ? "Tomorrow" : "In " + days + " days") + " · " + d.kind));
            }
        }
        if (view.equals("grocery")) {
            List<Db.Shop> shops = db.shops();
            for (Db.Item it : db.items()) {
                String shop = "Any shop";
                for (Db.Shop s : shops) if (s.id == it.shop) shop = s.name;
                out.add(new Row("item", it.id, "○", (it.qty.isEmpty() ? "" : it.qty + " × ") + it.name, shop));
            }
        }
        if (view.equals("today")) {
            int items = db.items().size();
            if (items > 0) out.add(new Row("info", 0, "🛒", items + " item" + (items == 1 ? "" : "s") + " on the shopping list", ""));
        }
        return out;
    }
}
