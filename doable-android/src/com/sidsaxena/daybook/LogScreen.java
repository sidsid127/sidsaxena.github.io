package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Timestamped notes of what you did, grouped by day. */
class LogScreen extends Screen {

    private final TextView summary;
    private final ListView list;
    private final EditText input;

    /** Rows: Db.Entry or Util.Header (day). */
    private final List<Object> rows = new ArrayList<Object>();
    private final RowAdapter adapter = new RowAdapter();

    LogScreen(MainActivity activity) {
        super(activity, R.layout.screen_log);
        summary = (TextView) root.findViewById(R.id.summary);
        list = (ListView) root.findViewById(R.id.list);
        input = (EditText) root.findViewById(R.id.input);
        TextView empty = (TextView) root.findViewById(R.id.empty);
        empty.setText(R.string.empty_log);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        root.findViewById(R.id.add_btn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { add(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    add();
                    return true;
                }
                return false;
            }
        });
        input.setHorizontallyScrolling(false);
        input.setMaxLines(4);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (row instanceof Db.Entry) edit((Db.Entry) row);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (!(row instanceof Db.Entry)) return false;
                confirmDelete((Db.Entry) row);
                return true;
            }
        });
    }

    @Override
    String title() {
        return a.getString(R.string.module_log);
    }

    @Override
    void onShow() {
        reload();
    }

    private void reload() {
        rows.clear();
        long lastDay = -1;
        int todayCount = 0;
        long today = Util.startOfDay(System.currentTimeMillis());
        List<Db.Entry> entries = db.entries();
        for (Db.Entry e : entries) {
            long day = Util.startOfDay(e.ts);
            if (day != lastDay) {
                rows.add(new Util.Header(Util.dayLabel(day), 0));
                lastDay = day;
            }
            if (day == today) todayCount++;
            rows.add(e);
        }
        summary.setText(todayCount + " logged today · " + entries.size() + " total");
        adapter.notifyDataSetChanged();
    }

    private void add() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        db.addEntry(text, System.currentTimeMillis());
        input.setText("");
        reload();
        list.setSelection(0);
    }

    private void edit(final Db.Entry entry) {
        final long[] ts = {entry.ts};
        LinearLayout box = Util.dialogBox(a);
        final EditText field = Util.field(a, entry.text);
        box.addView(field);
        final Button timeButton = new Button(a, null, android.R.attr.borderlessButtonStyle);
        timeButton.setText(Util.fullTimestamp(a, entry.ts));
        timeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final Calendar c = Calendar.getInstance();
                c.setTimeInMillis(ts[0]);
                new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(DatePicker view, int y, int m, int d) {
                        c.set(y, m, d);
                        new TimePickerDialog(a, new TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(TimePicker view, int h, int min) {
                                c.set(Calendar.HOUR_OF_DAY, h);
                                c.set(Calendar.MINUTE, min);
                                ts[0] = c.getTimeInMillis();
                                timeButton.setText(Util.fullTimestamp(a, ts[0]));
                            }
                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(a)).show();
                    }
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        box.addView(timeButton);

        new AlertDialog.Builder(a)
                .setTitle("Edit entry")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String t = field.getText().toString().trim();
                        if (!t.isEmpty()) db.updateEntry(entry.id, t, ts[0]);
                        reload();
                    }
                })
                .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { confirmDelete(entry); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(final Db.Entry entry) {
        Util.confirm(a, "Delete?", entry.text, "Delete", new Runnable() {
            public void run() {
                db.deleteEntry(entry.id);
                reload();
            }
        });
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public int getViewTypeCount() { return 2; }
        public boolean areAllItemsEnabled() { return false; }
        public int getItemViewType(int position) { return rows.get(position) instanceof Db.Entry ? 0 : 1; }
        public boolean isEnabled(int position) { return getItemViewType(position) == 0; }

        public View getView(int position, View view, ViewGroup parent) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (getItemViewType(position) == 0) {
                if (view == null) view = inf.inflate(R.layout.item_log, parent, false);
                Db.Entry e = (Db.Entry) rows.get(position);
                ((TextView) view.findViewById(R.id.time)).setText(Util.timeOfDay(a, e.ts));
                ((TextView) view.findViewById(R.id.text)).setText(e.text);
                return view;
            }
            if (view == null) view = inf.inflate(R.layout.item_header, parent, false);
            ((TextView) view).setText(((Util.Header) rows.get(position)).text);
            return view;
        }
    }
}
