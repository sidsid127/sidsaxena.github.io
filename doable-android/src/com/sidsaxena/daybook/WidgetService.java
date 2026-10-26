package com.sidsaxena.daybook;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** Supplies the rows of a widget's list. */
public class WidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        final int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
        final Context ctx = getApplicationContext();
        return new RemoteViewsFactory() {
            private List<Widgets.Row> rows = new ArrayList<Widgets.Row>();

            public void onCreate() {}

            public void onDataSetChanged() {
                rows = Widgets.rows(ctx, Widgets.view(ctx, widgetId));
            }

            public void onDestroy() {}

            public int getCount() { return rows.size(); }

            public RemoteViews getViewAt(int position) {
                RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_row);
                if (position >= rows.size()) return rv;
                Widgets.Row r = rows.get(position);
                rv.setTextViewText(R.id.r_icon, r.icon);
                rv.setTextViewText(R.id.r_title, r.title);
                rv.setTextViewText(R.id.r_sub, r.sub);
                rv.setViewVisibility(R.id.r_sub, r.sub.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                Intent fill = new Intent().putExtra("kind", r.kind).putExtra("rowId", r.id);
                rv.setOnClickFillInIntent(R.id.r_root, fill);
                return rv;
            }

            public RemoteViews getLoadingView() { return null; }

            public int getViewTypeCount() { return 1; }

            public long getItemId(int position) { return position; }

            public boolean hasStableIds() { return false; }
        };
    }
}
