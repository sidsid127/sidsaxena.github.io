package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Small shared helpers: dates, colours and simple dialogs. */
final class Util {
    private Util() {}

    /** Section header row in lists; colour 0 means the default muted colour. */
    static class Header {
        final String text;
        final int color;

        Header(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    interface TextEntered {
        void onText(String text);
    }

    // ---- dates ----

    static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Whole days from today to the given day (negative = past). */
    static int daysFromToday(long day) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(startOfDay(System.currentTimeMillis()));
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(startOfDay(day));
        int diff = 0;
        while (a.before(b)) { a.add(Calendar.DAY_OF_MONTH, 1); diff++; }
        while (a.after(b)) { a.add(Calendar.DAY_OF_MONTH, -1); diff--; }
        return diff;
    }

    static String shortDate(long day) {
        return new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(new Date(day));
    }

    static String dueLabel(long day) {
        int d = daysFromToday(day);
        if (d == 0) return "Due today";
        if (d == 1) return "Due tomorrow";
        if (d < 0) return "Overdue · " + shortDate(day);
        return "Due " + shortDate(day);
    }

    static String dayLabel(long day) {
        int d = daysFromToday(day);
        if (d == 0) return "Today";
        if (d == -1) return "Yesterday";
        return new SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault()).format(new Date(day));
    }

    static String timeOfDay(Context c, long millis) {
        return DateFormat.getTimeFormat(c).format(new Date(millis));
    }

    static String fullTimestamp(Context c, long millis) {
        return shortDate(millis) + ", " + timeOfDay(c, millis);
    }

    /** "14:05" today, "Yesterday", or a short date — like the Notes app list. */
    static String relativeStamp(Context c, long millis) {
        int d = daysFromToday(millis);
        if (d == 0) return timeOfDay(c, millis);
        if (d == -1) return "Yesterday";
        if (d > -7) return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date(millis));
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(new Date(millis));
    }

    // ---- colours ----

    static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Category/shop colours are lightened in dark mode so they stay readable. */
    static int readable(Context c, int color) {
        if (!isNight(c)) return color;
        return Color.rgb(mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)));
    }

    private static int mix(int channel) {
        return (channel * 2 + 255) / 3;
    }

    static int color(Context c, int res) {
        return c.getResources().getColor(res);
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A pill-shaped chip, filled when selected. */
    static TextView chip(Context c, String text, int color, boolean selected) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 18));
        if (selected) {
            bg.setColor(color);
            t.setTextColor(0xFFFFFFFF);
        } else {
            int shown = readable(c, color);
            bg.setColor(color(c, R.color.card));
            bg.setStroke(dp(c, 1), (shown & 0x00FFFFFF) | 0x77000000);
            t.setTextColor(shown);
        }
        t.setBackground(bg);
        // LinearLayout params (a MarginLayoutParams) so margins survive in both LinearLayout and FlowLayout.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 4), dp(c, 3), dp(c, 4), dp(c, 3));
        t.setLayoutParams(lp);
        return t;
    }

    // ---- dialogs ----

    static LinearLayout dialogBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        return box;
    }

    static View wrap(Context c, View v) {
        LinearLayout box = dialogBox(c);
        box.addView(v);
        return box;
    }

    static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(color(c, R.color.muted));
        t.setPadding(dp(c, 4), dp(c, 14), 0, 0);
        return t;
    }

    static EditText field(Context c, String text) {
        EditText field = new EditText(c);
        field.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setText(text);
        field.setSelection(text.length());
        return field;
    }

    static void showKeyboard(final EditText field) {
        field.requestFocus();
        field.postDelayed(new Runnable() {
            public void run() {
                ((InputMethodManager) field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .showSoftInput(field, 0);
            }
        }, 200);
    }

    static void hideKeyboard(View v) {
        ((InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** One-line text prompt with OK/Cancel. */
    static void prompt(Context c, String title, String value, String hint, String ok, final TextEntered onOk) {
        final EditText f = field(c, value);
        f.setHint(hint);
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(wrap(c, f))
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String v = f.getText().toString().trim();
                        if (!v.isEmpty()) onOk.onText(v);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        showKeyboard(f);
    }

    static void confirm(Context c, String title, String message, String ok, final Runnable onOk) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { onOk.run(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
