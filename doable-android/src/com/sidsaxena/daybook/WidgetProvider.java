package com.sidsaxena.daybook;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class WidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        for (int id : ids) Widgets.update(ctx, m, id);
        m.notifyAppWidgetViewDataChanged(ids, R.id.w_list);
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        for (int id : ids) Reminders.prefs(ctx).edit().remove("widget_" + id).apply();
    }
}
