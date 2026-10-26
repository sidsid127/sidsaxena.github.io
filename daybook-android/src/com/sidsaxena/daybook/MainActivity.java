package com.sidsaxena.daybook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int MODE_TASKS = 0;
    private static final int MODE_LOG = 1;
    private static final int MENU_CLEAR_DONE = 1;
    private static final int MENU_SHARE = 2;

    private Db db;
    private SharedPreferences prefs;
    private int mode;
    private long pendingDue; // due date picked for the next task to be added, 0 = none

    private TextView tabTasks, tabLog, summary, empty;
    private ListView list;
    private EditText input;
    private ImageButton dueBtn;

    /** Rows shown in the list: Db.Task, Db.Entry, or String (day header in the log). */
    private final List<Object> rows = new ArrayList<Object>();
    private final RowAdapter adapter = new RowAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new Db(this);
        prefs = getSharedPreferences("daybook", MODE_PRIVATE);

        tabTasks = (TextView) findViewById(R.id.tab_tasks);
        tabLog = (TextView) findViewById(R.id.tab_log);
        summary = (TextView) findViewById(R.id.summary);
        empty = (TextView) findViewById(R.id.empty);
        list = (ListView) findViewById(R.id.list);
        input = (EditText) findViewById(R.id.input);
        dueBtn = (ImageButton) findViewById(R.id.due_btn);
        Button addBtn = (Button) findViewById(R.id.add_btn);

        list.setAdapter(adapter);
        list.setEmptyView(empty);

        tabTasks.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setMode(MODE_TASKS); }
        });
        tabLog.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setMode(MODE_LOG); }
        });
        addBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addFromInput(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addFromInput();
                    return true;
                }
                return false;
            }
        });
        // textMultiLine would otherwise turn the keyboard's action key into "new line".
        input.setHorizontallyScrolling(false);
        input.setMaxLines(4);

        dueBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(pendingDue, new DatePicked() {
                    public void onPicked(long day) {
                        pendingDue = day;
                        updateInputHint();
                    }
                });
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (row instanceof Db.Task) editTask((Db.Task) row);
                else if (row instanceof Db.Entry) editEntry((Db.Entry) row);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (row instanceof String) return false;
                confirmDelete(row);
                return true;
            }
        });

        setMode(prefs.getInt("mode", MODE_TASKS));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Relative labels ("Today", "Overdue") depend on the current date.
        reload();
    }

    // ---------------------------------------------------------------- modes

    private void setMode(int newMode) {
        mode = newMode;
        prefs.edit().putInt("mode", mode).apply();
        styleTab(tabTasks, mode == MODE_TASKS);
        styleTab(tabLog, mode == MODE_LOG);
        dueBtn.setVisibility(mode == MODE_TASKS ? View.VISIBLE : View.GONE);
        empty.setText(mode == MODE_TASKS ? R.string.empty_tasks : R.string.empty_log);
        updateInputHint();
        reload();
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? 0xFFFFFFFF : 0x99FFFFFF);
        tab.setPaintFlags(selected ? tab.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG
                : tab.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
    }

    private void updateInputHint() {
        if (mode == MODE_LOG) {
            input.setHint(R.string.hint_log);
        } else if (pendingDue > 0) {
            input.setHint("Add a task " + dueLabel(pendingDue).toLowerCase(Locale.getDefault()) + "…");
        } else {
            input.setHint(R.string.hint_task);
        }
    }

    private void reload() {
        rows.clear();
        if (mode == MODE_TASKS) {
            int open = 0, done = 0, overdue = 0;
            long today = startOfDay(System.currentTimeMillis());
            for (Db.Task t : db.tasks()) {
                rows.add(t);
                if (t.done) done++;
                else {
                    open++;
                    if (t.due > 0 && t.due < today) overdue++;
                }
            }
            String s = open + " open · " + done + " done";
            if (overdue > 0) s += " · " + overdue + " overdue";
            summary.setText(s);
        } else {
            long lastDay = -1;
            int todayCount = 0;
            long today = startOfDay(System.currentTimeMillis());
            List<Db.Entry> entries = db.entries();
            for (Db.Entry e : entries) {
                long day = startOfDay(e.ts);
                if (day != lastDay) {
                    rows.add(dayLabel(day));
                    lastDay = day;
                }
                if (day == today) todayCount++;
                rows.add(e);
            }
            summary.setText(todayCount + " logged today · " + entries.size() + " total");
        }
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- add / edit / delete

    private void addFromInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (mode == MODE_TASKS) {
            db.addTask(text, pendingDue);
            pendingDue = 0;
            updateInputHint();
        } else {
            db.addEntry(text, System.currentTimeMillis());
        }
        input.setText("");
        reload();
        list.setSelection(0);
    }

    private void editTask(final Db.Task task) {
        final long[] due = {task.due};
        LinearLayout box = dialogBox();
        final EditText field = dialogField(task.title);
        box.addView(field);
        final Button dueButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        dueButton.setText(task.due > 0 ? dueLabel(task.due) : getString(R.string.set_due));
        dueButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(due[0], new DatePicked() {
                    public void onPicked(long day) {
                        due[0] = day;
                        dueButton.setText(day > 0 ? dueLabel(day) : getString(R.string.set_due));
                    }
                });
            }
        });
        box.addView(dueButton);

        new AlertDialog.Builder(this)
                .setTitle("Edit task")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String t = field.getText().toString().trim();
                        if (!t.isEmpty()) db.updateTask(task.id, t, due[0]);
                        reload();
                    }
                })
                .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { confirmDelete(task); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editEntry(final Db.Entry entry) {
        final long[] ts = {entry.ts};
        LinearLayout box = dialogBox();
        final EditText field = dialogField(entry.text);
        box.addView(field);
        final Button timeButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        timeButton.setText(fullTimestamp(entry.ts));
        timeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDateTime(ts[0], new DatePicked() {
                    public void onPicked(long when) {
                        ts[0] = when;
                        timeButton.setText(fullTimestamp(when));
                    }
                });
            }
        });
        box.addView(timeButton);

        new AlertDialog.Builder(this)
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

    private void confirmDelete(final Object row) {
        String what = row instanceof Db.Task ? ((Db.Task) row).title : ((Db.Entry) row).text;
        new AlertDialog.Builder(this)
                .setTitle("Delete?")
                .setMessage(what)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (row instanceof Db.Task) db.deleteTask(((Db.Task) row).id);
                        else db.deleteEntry(((Db.Entry) row).id);
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        return box;
    }

    private EditText dialogField(String text) {
        EditText field = new EditText(this);
        field.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setText(text);
        field.setSelection(text.length());
        return field;
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SHARE, 0, R.string.menu_share);
        menu.add(0, MENU_CLEAR_DONE, 1, R.string.menu_clear_done);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CLEAR_DONE) {
            new AlertDialog.Builder(this)
                    .setMessage("Remove all completed tasks?")
                    .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            int n = db.clearDone();
                            Toast.makeText(MainActivity.this, "Removed " + n + " task(s)", Toast.LENGTH_SHORT).show();
                            reload();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        if (item.getItemId() == MENU_SHARE) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Daybook export");
            send.putExtra(Intent.EXTRA_TEXT, exportText());
            startActivity(Intent.createChooser(send, getString(R.string.menu_share)));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String exportText() {
        StringBuilder sb = new StringBuilder("TO-DO\n");
        for (Db.Task t : db.tasks()) {
            sb.append(t.done ? "[x] " : "[ ] ").append(t.title);
            if (t.due > 0 && !t.done) sb.append("  (").append(dueLabel(t.due)).append(")");
            sb.append('\n');
        }
        sb.append("\nACTIVITY LOG\n");
        long lastDay = -1;
        for (Db.Entry e : db.entries()) {
            long day = startOfDay(e.ts);
            if (day != lastDay) {
                sb.append('\n').append(new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(new Date(day))).append('\n');
                lastDay = day;
            }
            sb.append(timeOfDay(e.ts)).append("  ").append(e.text).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- date helpers

    private interface DatePicked {
        void onPicked(long value);
    }

    /** Date picker for due dates; offers "No date" to clear. Reports local midnight in millis. */
    private void pickDate(long current, final DatePicked callback) {
        Calendar c = Calendar.getInstance();
        if (current > 0) c.setTimeInMillis(current);
        DatePickerDialog dlg = new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker view, int y, int m, int d) {
                Calendar picked = Calendar.getInstance();
                picked.clear();
                picked.set(y, m, d);
                callback.onPicked(picked.getTimeInMillis());
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dlg.setButton(DialogInterface.BUTTON_NEUTRAL, "No date", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { callback.onPicked(0); }
        });
        dlg.show();
    }

    private void pickDateTime(long current, final DatePicked callback) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(current);
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker view, int y, int m, int d) {
                c.set(y, m, d);
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    public void onTimeSet(TimePicker view, int h, int min) {
                        c.set(Calendar.HOUR_OF_DAY, h);
                        c.set(Calendar.MINUTE, min);
                        callback.onPicked(c.getTimeInMillis());
                    }
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                        DateFormat.is24HourFormat(MainActivity.this)).show();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Whole days from today to the given day (negative = past). */
    private static int daysFromToday(long day) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(startOfDay(System.currentTimeMillis()));
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(startOfDay(day));
        int diff = 0;
        while (a.before(b)) { a.add(Calendar.DAY_OF_MONTH, 1); diff++; }
        while (a.after(b)) { a.add(Calendar.DAY_OF_MONTH, -1); diff--; }
        return diff;
    }

    private static String shortDate(long day) {
        return new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(new Date(day));
    }

    private static String dueLabel(long day) {
        int d = daysFromToday(day);
        if (d == 0) return "Due today";
        if (d == 1) return "Due tomorrow";
        if (d < 0) return "Overdue · " + shortDate(day);
        return "Due " + shortDate(day);
    }

    private static String dayLabel(long day) {
        int d = daysFromToday(day);
        if (d == 0) return "Today";
        if (d == -1) return "Yesterday";
        return new SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault()).format(new Date(day));
    }

    private String timeOfDay(long millis) {
        return DateFormat.getTimeFormat(this).format(new Date(millis));
    }

    private String fullTimestamp(long millis) {
        return shortDate(millis) + ", " + timeOfDay(millis);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- list adapter

    private class RowAdapter extends BaseAdapter {
        private static final int TYPE_TASK = 0, TYPE_ENTRY = 1, TYPE_HEADER = 2;

        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public int getViewTypeCount() { return 3; }

        public int getItemViewType(int position) {
            Object row = rows.get(position);
            if (row instanceof Db.Task) return TYPE_TASK;
            if (row instanceof Db.Entry) return TYPE_ENTRY;
            return TYPE_HEADER;
        }

        public boolean isEnabled(int position) {
            return getItemViewType(position) != TYPE_HEADER;
        }

        public View getView(int position, View view, ViewGroup parent) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            switch (getItemViewType(position)) {
                case TYPE_TASK:
                    if (view == null) view = inf.inflate(R.layout.item_task, parent, false);
                    bindTask(view, (Db.Task) rows.get(position));
                    return view;
                case TYPE_ENTRY:
                    if (view == null) view = inf.inflate(R.layout.item_log, parent, false);
                    Db.Entry e = (Db.Entry) rows.get(position);
                    ((TextView) view.findViewById(R.id.time)).setText(timeOfDay(e.ts));
                    ((TextView) view.findViewById(R.id.text)).setText(e.text);
                    return view;
                default:
                    if (view == null) view = inf.inflate(R.layout.item_header, parent, false);
                    ((TextView) view).setText((String) rows.get(position));
                    return view;
            }
        }

        private void bindTask(View view, final Db.Task t) {
            Context ctx = view.getContext();
            CheckBox check = (CheckBox) view.findViewById(R.id.check);
            TextView title = (TextView) view.findViewById(R.id.title);
            TextView meta = (TextView) view.findViewById(R.id.meta);

            check.setOnCheckedChangeListener(null);
            check.setChecked(t.done);
            check.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    db.setDone(t.id, ((CheckBox) v).isChecked());
                    reload();
                }
            });

            title.setText(t.title);
            if (t.done) {
                title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                title.setTextColor(ctx.getResources().getColor(R.color.muted));
            } else {
                title.setPaintFlags(title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                title.setTextColor(ctx.getResources().getColor(R.color.text));
            }

            if (t.done) {
                meta.setVisibility(View.VISIBLE);
                meta.setTextColor(ctx.getResources().getColor(R.color.muted));
                meta.setText("Done " + fullTimestamp(t.doneAt));
            } else if (t.due > 0) {
                meta.setVisibility(View.VISIBLE);
                boolean overdue = daysFromToday(t.due) < 0;
                meta.setTextColor(ctx.getResources().getColor(overdue ? R.color.overdue : R.color.primary));
                meta.setText(dueLabel(t.due));
            } else {
                meta.setVisibility(View.GONE);
            }
        }
    }
}
