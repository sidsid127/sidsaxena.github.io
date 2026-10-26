package com.sidsaxena.daybook;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

/** Lets you choose what a widget shows (also reachable from the widget's ⚙ button). */
public class WidgetConfig extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        setResult(RESULT_CANCELED, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        int current = java.util.Arrays.asList(Widgets.VIEWS).indexOf(Widgets.view(this, id));
        new AlertDialog.Builder(this)
                .setTitle("What should this widget show?")
                .setSingleChoiceItems(Widgets.VIEW_NAMES, Math.max(0, current), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Reminders.prefs(WidgetConfig.this).edit().putString("widget_" + id, Widgets.VIEWS[which]).commit();
                        AppWidgetManager m = AppWidgetManager.getInstance(WidgetConfig.this);
                        Widgets.update(WidgetConfig.this, m, id);
                        m.notifyAppWidgetViewDataChanged(new int[]{id}, R.id.w_list);
                        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
                        d.dismiss();
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface d) {
                        // Keep the widget (default view) when added without choosing.
                        if (!Reminders.prefs(WidgetConfig.this).contains("widget_" + id)) {
                            Reminders.prefs(WidgetConfig.this).edit().putString("widget_" + id, "today").commit();
                            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
                        }
                        finish();
                    }
                })
                .show();
    }
}
