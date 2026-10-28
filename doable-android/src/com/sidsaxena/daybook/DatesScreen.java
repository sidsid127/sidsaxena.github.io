package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.view.Menu;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TimePicker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Birthdays, anniversaries, contract renewals and document expiries (wallet expiries appear here too),
 * sorted by when they come up next, with reminders a chosen number of days before.
 */
class DatesScreen extends Page {

    private static final int MENU_TIME = 180;
    private static final String[] REMIND_LABELS = {"No reminder", "On the day", "1 day before", "3 days before",
            "1 week before", "2 weeks before", "1 month before"};
    private static final int[] REMIND_DAYS = {-1, 0, 1, 3, 7, 14, 30};

    DatesScreen(MainActivity a) {
        super(a);
        setFab(new Runnable() {
            public void run() { edit(new Store.DateItem()); }
        });
    }

    @Override
    String title() {
        return a.getString(R.string.module_dates);
    }

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_TIME, 0, "Reminder time…");
    }

    @Override
    boolean onMenu(int id) {
        if (id != MENU_TIME) return false;
        int m = Reminders.datesTime(a);
        new TimePickerDialog(a, new TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(TimePicker v, int h, int min) {
                a.prefs.edit().putInt("datesRemind", h * 60 + min).apply();
                Reminders.refresh(a);
            }
        }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(a)).show();
        return true;
    }

    @Override
    void open(long id) {
        for (Store.DateItem d : st.dates()) if (!d.fromWallet && d.id == id) edit(d);
    }

    static String emoji(Store.DateItem d) {
        if (d.fromWallet) return "🪪";
        switch (d.kind) {
            case "Birthday": return "🎂";
            case "Anniversary": return "💍";
            case "Name day": return "🎉";
            case "Contract renewal": return "📑";
            case "Expiry": return "⏳";
            case "Appointment": return "🗓";
            default: return "📅";
        }
    }

    /** "Today", "Tomorrow · turns 40", "Fri, 14 Nov · in 19 days". */
    static String when(Store.DateItem d, long occ) {
        int days = Util.daysFromToday(occ);
        String s = days == 0 ? "Today" : days == 1 ? "Tomorrow" : days < 0
                ? "Was " + new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(occ))
                : Util.shortDate(occ) + " · in " + days + " days";
        if (d.yearly && d.year > 0) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(occ);
            int n = c.get(Calendar.YEAR) - d.year;
            if (n > 0) s += d.kind.equals("Birthday") ? " · turns " + n : " · " + n + (n == 1 ? " year" : " years");
        }
        return s;
    }

    @Override
    void render() {
        body.removeAllViews();
        final long today = Util.startOfDay(System.currentTimeMillis());
        List<Object[]> all = new ArrayList<Object[]>();
        for (Store.DateItem d : st.dates()) all.add(new Object[]{Store.nextOccurrence(d, today), d});
        if (all.isEmpty()) {
            empty("Birthdays, anniversaries, contract renewals, passport expiry…\nTap + to add one. You get a reminder before each.");
            return;
        }
        Collections.sort(all, new Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) {
                long a = (Long) x[0], b = (Long) y[0];
                boolean pa = a < today, pb = b < today;  // past one-off dates last
                if (pa != pb) return pa ? 1 : -1;
                return pa ? Long.compare(b, a) : Long.compare(a, b);
            }
        });
        String section = null;
        LinearLayout card = null;
        for (Object[] o : all) {
            long occ = (Long) o[0];
            final Store.DateItem d = (Store.DateItem) o[1];
            int days = Util.daysFromToday(occ);
            String s = days < 0 ? "Past" : days <= 7 ? "This week" : days <= 31 ? "Next 30 days" : "Later";
            if (!s.equals(section)) {
                section = s;
                header(s);
                card = card();
            }
            String sub = when(d, occ) + (d.fromWallet ? " · from Wallet" : " · " + d.kind)
                    + (d.remindDays >= 0 ? " · 🔔" : "");
            row(card, emoji(d), (d.fromWallet ? "" : SpacesUi.badge(a, "dates", d.id)) + d.title, sub, null, new Runnable() {
                public void run() {
                    if (d.fromWallet) a.go("wallet", d.walletId, null);
                    else edit(d);
                }
            }, d.fromWallet ? null : new Runnable() {
                public void run() { delete(d); }
            });
        }
    }

    private void delete(final Store.DateItem d) {
        Util.confirm(a, "Delete \"" + d.title + "\"?", null, "Delete", new Runnable() {
            public void run() {
                st.deleteDate(d.id);
                Reminders.refresh(a);
                render();
            }
        });
    }

    private void edit(final Store.DateItem d) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "What"));
        final EditText title = Util.field(a, d.title);
        title.setHint("e.g. Mum's birthday");
        box.addView(title);

        box.addView(Util.label(a, "Kind"));
        final Spinner kind = new Spinner(a);
        kind.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, Store.DATE_KINDS));
        kind.setSelection(Math.max(0, java.util.Arrays.asList(Store.DATE_KINDS).indexOf(d.kind)));
        box.addView(kind);

        box.addView(Util.label(a, "Date"));
        Calendar now = Calendar.getInstance();
        final int[] ymd = d.id == 0
                ? new int[]{now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)}
                : new int[]{d.year > 0 ? d.year : now.get(Calendar.YEAR), d.month, d.day};
        final android.widget.Button dateBtn = new android.widget.Button(a, null, android.R.attr.borderlessButtonStyle);
        dateBtn.setText(Util.dayLabel(Store.dayOf(ymd[0], ymd[1], ymd[2])));
        dateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new android.app.DatePickerDialog(a, new android.app.DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(DatePicker p, int y, int m, int day) {
                        ymd[0] = y;
                        ymd[1] = m + 1;
                        ymd[2] = day;
                        dateBtn.setText(Util.dayLabel(Store.dayOf(y, m + 1, day)));
                    }
                }, ymd[0], ymd[1] - 1, ymd[2]).show();
            }
        });
        box.addView(dateBtn);

        final CheckBox yearly = new CheckBox(a);
        yearly.setText("Every year");
        yearly.setChecked(d.yearly);
        box.addView(yearly);
        final CheckBox knowYear = new CheckBox(a);
        knowYear.setText("I know the year (shows age / years)");
        knowYear.setChecked(d.id == 0 || d.year > 0);
        box.addView(knowYear);
        yearly.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                knowYear.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        });
        knowYear.setVisibility(d.yearly ? View.VISIBLE : View.GONE);
        kind.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;

            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (first) {
                    first = false;
                    return;
                }
                String k = Store.DATE_KINDS[pos];
                yearly.setChecked(k.equals("Birthday") || k.equals("Anniversary") || k.equals("Name day"));
            }

            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        box.addView(Util.label(a, "Remind me"));
        final Spinner remind = new Spinner(a);
        remind.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, REMIND_LABELS));
        int ri = 2;
        for (int i = 0; i < REMIND_DAYS.length; i++) if (REMIND_DAYS[i] == d.remindDays) ri = i;
        remind.setSelection(ri);
        box.addView(remind);

        if (d.id != 0 && Account.signedIn(a)) box.addView(SpacesUi.shareRow(a, "dates", "dates", d.id, new Runnable() {
            public void run() { render(); }
        }));
        box.addView(Util.label(a, "Notes"));
        final EditText notes = Util.field(a, d.notes);
        notes.setHint("Gift ideas, contract number…");
        box.addView(notes);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(d.id == 0 ? "New date" : "Edit date")
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        String t = title.getText().toString().trim();
                        if (t.isEmpty()) return;
                        d.title = t;
                        d.kind = Store.DATE_KINDS[kind.getSelectedItemPosition()];
                        d.month = ymd[1];
                        d.day = ymd[2];
                        d.yearly = yearly.isChecked();
                        d.year = !d.yearly || knowYear.isChecked() ? ymd[0] : 0;
                        d.remindDays = REMIND_DAYS[remind.getSelectedItemPosition()];
                        d.notes = notes.getText().toString().trim();
                        st.saveDate(d);
                        if (d.remindDays >= 0) a.ensureNotifications();
                        Reminders.refresh(a);
                        render();
                    }
                })
                .setNegativeButton("Cancel", null);
        if (d.id != 0) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dlg, int which) { delete(d); }
            });
        }
        b.show();
        if (d.id == 0) Util.showKeyboard(title);
    }
}
