package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The to-do list: categories, priorities, due dates. */
class TasksScreen extends Screen {

    private static final int MENU_SHOW_DONE = 100;
    private static final int MENU_SHARE = 101;
    private static final int MENU_CLEAR_DONE = 102;

    /** Completed tasks stay visible (struck through) for this long, unless "show completed" is on. */
    private static final long DONE_VISIBLE_MS = 12L * 60 * 60 * 1000;

    static final String[] PRIORITY_NAMES = {"None", "Low", "Medium", "High"};
    private static final int[] PRIORITY_COLORS = {
            R.color.prio_none, R.color.prio_low, R.color.prio_med, R.color.prio_high};

    private long pendingDue; // due date picked for the next task to be added, 0 = none
    private long selTop;     // selected top-level category filter, 0 = all
    private long selSub;     // selected sub-category filter, 0 = all of selTop
    private boolean showDone;
    private boolean grouped; // list is split into category sections (category label hidden on rows)
    private Db.Task dragging; // task being dragged by its handle

    private final TextView summary;
    private final ListView list;
    private final EditText input;
    private final ImageButton dueBtn;
    private final View subScroll;
    private final LinearLayout chipsTop, chipsSub;

    private List<Db.Category> categories = new ArrayList<Db.Category>();
    private final Map<Long, Db.Category> categoryById = new HashMap<Long, Db.Category>();

    /** Rows shown in the list: Db.Task or Util.Header. */
    private final List<Object> rows = new ArrayList<Object>();
    private final RowAdapter adapter = new RowAdapter();

    TasksScreen(MainActivity activity) {
        super(activity, R.layout.screen_tasks);
        selTop = a.prefs.getLong("selTop", 0);
        selSub = a.prefs.getLong("selSub", 0);
        showDone = a.prefs.getBoolean("showDone", false);

        summary = (TextView) root.findViewById(R.id.summary);
        list = (ListView) root.findViewById(R.id.list);
        input = (EditText) root.findViewById(R.id.input);
        dueBtn = (ImageButton) root.findViewById(R.id.due_btn);
        subScroll = root.findViewById(R.id.sub_scroll);
        chipsTop = (LinearLayout) root.findViewById(R.id.chips_top);
        chipsSub = (LinearLayout) root.findViewById(R.id.chips_sub);
        TextView empty = (TextView) root.findViewById(R.id.empty);
        empty.setText(R.string.empty_tasks);

        list.setAdapter(adapter);
        list.setEmptyView(empty);

        root.findViewById(R.id.add_btn).setOnClickListener(new View.OnClickListener() {
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
        // Wrap long text while keeping the keyboard's "done" key.
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
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (!(row instanceof Db.Task)) return false;
                confirmDelete((Db.Task) row);
                return true;
            }
        });
    }

    @Override
    String title() {
        return a.getString(R.string.app_name);
    }

    @Override
    void onShow() {
        // Relative labels ("Today", "Overdue") and the 12-hour completed window depend on the clock.
        reload();
    }

    // ---------------------------------------------------------------- categories

    private void loadCategories() {
        categories = db.categories();
        categoryById.clear();
        for (Db.Category k : categories) categoryById.put(k.id, k);
        // Selected filter may have been deleted.
        if (selTop != 0 && !categoryById.containsKey(selTop)) selTop = 0;
        if (selSub != 0 && (!categoryById.containsKey(selSub) || categoryById.get(selSub).parent != selTop)) selSub = 0;
    }

    private void selectFilter(long top, long sub) {
        selTop = top;
        selSub = sub;
        a.prefs.edit().putLong("selTop", selTop).putLong("selSub", selSub).apply();
        reload();
    }

    private void buildChips() {
        chipsTop.removeAllViews();
        chipsSub.removeAllViews();

        chipsTop.addView(chip(a.getString(R.string.all), Util.color(a, R.color.primary), selTop == 0,
                new View.OnClickListener() {
                    public void onClick(View v) { selectFilter(0, 0); }
                }, null));
        for (final Db.Category k : categories) {
            if (k.parent != 0) continue;
            chipsTop.addView(chip(k.name, k.color, selTop == k.id, new View.OnClickListener() {
                public void onClick(View v) { selectFilter(k.id, 0); }
            }, k));
        }
        chipsTop.addView(chip("+ Category", Util.color(a, R.color.muted), false,
                new View.OnClickListener() {
                    public void onClick(View v) { promptNewCategory(0); }
                }, null));

        if (selTop == 0) {
            subScroll.setVisibility(View.GONE);
            return;
        }
        subScroll.setVisibility(View.VISIBLE);
        final Db.Category top = categoryById.get(selTop);
        chipsSub.addView(chip("All " + top.name, top.color, selSub == 0, new View.OnClickListener() {
            public void onClick(View v) { selectFilter(top.id, 0); }
        }, null));
        for (final Db.Category k : categories) {
            if (k.parent != top.id) continue;
            chipsSub.addView(chip(k.name, k.color, selSub == k.id, new View.OnClickListener() {
                public void onClick(View v) { selectFilter(top.id, k.id); }
            }, k));
        }
        chipsSub.addView(chip("+ Sub-category", Util.color(a, R.color.muted), false,
                new View.OnClickListener() {
                    public void onClick(View v) { promptNewCategory(top.id); }
                }, null));
    }

    /** Filter chip; long-pressing a category chip offers rename/delete. */
    private TextView chip(String text, int color, boolean selected, View.OnClickListener click,
                          final Db.Category category) {
        TextView t = Util.chip(a, text, color, selected);
        t.setOnClickListener(click);
        if (category != null) {
            t.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    categoryOptions(category);
                    return true;
                }
            });
        }
        return t;
    }

    private void promptNewCategory(final long parent) {
        Util.prompt(a, parent == 0 ? "New category" : "New sub-category in " + categoryById.get(parent).name, "",
                parent == 0 ? "e.g. Family, Health" : "e.g. Customer A", "Add", new Util.TextEntered() {
                    public void onText(String name) {
                        long id = db.addCategory(name, parent);
                        if (parent == 0) selectFilter(id, 0);
                        else selectFilter(parent, id);
                    }
                });
    }

    private void categoryOptions(final Db.Category k) {
        new AlertDialog.Builder(a)
                .setTitle(k.name)
                .setItems(new String[]{"Rename", "Delete"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            Util.prompt(a, "Rename category", k.name, "", "Save", new Util.TextEntered() {
                                public void onText(String name) {
                                    db.renameCategory(k.id, name);
                                    reload();
                                }
                            });
                        } else {
                            String where = k.parent == 0 ? "will have no category" : "move to " + categoryById.get(k.parent).name;
                            Util.confirm(a, "Delete \"" + k.name + "\"?",
                                    (k.parent == 0 ? "Its sub-categories are deleted too. " : "")
                                            + "Tasks are not deleted, they " + where + ".", "Delete", new Runnable() {
                                        public void run() {
                                            db.deleteCategory(k);
                                            reload();
                                        }
                                    });
                        }
                    }
                })
                .show();
    }

    private String categoryPath(long id) {
        Db.Category k = categoryById.get(id);
        if (k == null) return "";
        Db.Category parent = categoryById.get(k.parent);
        return parent == null ? k.name : parent.name + " › " + k.name;
    }

    private boolean matchesFilter(Db.Task t) {
        if (selTop == 0) return true;
        if (selSub != 0) return t.category == selSub;
        if (t.category == selTop) return true;
        Db.Category k = categoryById.get(t.category);
        return k != null && k.parent == selTop;
    }

    private void updateInputHint() {
        long target = selSub != 0 ? selSub : selTop;
        String hint = "Add a task";
        if (target != 0 && categoryById.containsKey(target)) hint += " to " + categoryById.get(target).name;
        if (pendingDue > 0) hint += " · " + Util.dueLabel(pendingDue).toLowerCase(Locale.getDefault());
        input.setHint(hint + "…");
    }

    // ---------------------------------------------------------------- list contents

    /** Highest priority first, then your own drag order, then soonest due date, then newest. */
    private static final Comparator<Db.Task> BY_IMPORTANCE = new Comparator<Db.Task>() {
        public int compare(Db.Task x, Db.Task y) {
            if (x.priority != y.priority) return y.priority - x.priority;
            if (x.position != y.position) return x.position < y.position ? -1 : 1;
            long dx = x.due > 0 ? x.due : Long.MAX_VALUE, dy = y.due > 0 ? y.due : Long.MAX_VALUE;
            if (dx != dy) return dx < dy ? -1 : 1;
            return x.created > y.created ? -1 : (x.created < y.created ? 1 : 0);
        }
    };

    void reload() {
        loadCategories();
        buildChips();
        updateInputHint();
        rows.clear();

        long now = System.currentTimeMillis();
        long today = Util.startOfDay(now);
        List<Db.Task> open = new ArrayList<Db.Task>();
        List<Db.Task> done = new ArrayList<Db.Task>();
        int high = 0, overdue = 0, hiddenDone = 0;
        for (Db.Task t : db.tasks()) {
            if (!matchesFilter(t)) continue;
            if (!t.done) {
                open.add(t);
                if (t.priority == 3) high++;
                if (t.due > 0 && t.due < today) overdue++;
            } else if (showDone || now - t.doneAt < DONE_VISIBLE_MS) {
                done.add(t);
            } else {
                hiddenDone++;
            }
        }
        Collections.sort(open, BY_IMPORTANCE);
        Collections.sort(done, new Comparator<Db.Task>() {
            public int compare(Db.Task x, Db.Task y) {
                return x.doneAt > y.doneAt ? -1 : (x.doneAt < y.doneAt ? 1 : 0);
            }
        });

        // Group open tasks by (sub-)category when they span more than one; high priority ones go on top.
        LinkedHashMap<Long, List<Db.Task>> groups = new LinkedHashMap<Long, List<Db.Task>>();
        for (Db.Category k : categories) groups.put(k.id, new ArrayList<Db.Task>());
        groups.put(0L, new ArrayList<Db.Task>());
        int nonEmpty = 0;
        for (Db.Task t : open) {
            List<Db.Task> g = groups.get(categoryById.containsKey(t.category) ? t.category : 0L);
            if (g.isEmpty()) nonEmpty++;
            g.add(t);
        }
        grouped = nonEmpty > 1;
        if (!grouped) {
            rows.addAll(open);
        } else {
            List<Db.Task> urgent = new ArrayList<Db.Task>();
            for (Db.Task t : open) if (t.priority == 3) urgent.add(t);
            if (!urgent.isEmpty()) {
                rows.add(new Util.Header("⚑ High priority", Util.color(a, R.color.prio_high)));
                rows.addAll(urgent);
            }
            for (Map.Entry<Long, List<Db.Task>> e : groups.entrySet()) {
                List<Db.Task> rest = new ArrayList<Db.Task>();
                for (Db.Task t : e.getValue()) if (t.priority != 3) rest.add(t);
                if (rest.isEmpty()) continue;
                Db.Category k = categoryById.get(e.getKey());
                rows.add(k == null ? new Util.Header("No category", 0)
                        : new Util.Header("● " + categoryPath(k.id), Util.readable(a, k.color)));
                rows.addAll(rest);
            }
        }
        if (!done.isEmpty()) {
            rows.add(new Util.Header(showDone ? "Completed" : "Completed · hidden after 12 hours", 0));
            rows.addAll(done);
        }

        StringBuilder s = new StringBuilder();
        s.append(open.size()).append(" open");
        if (high > 0) s.append(" · ").append(high).append(" high priority");
        if (overdue > 0) s.append(" · ").append(overdue).append(" overdue");
        if (hiddenDone > 0) s.append(" · ").append(hiddenDone).append(" older completed hidden");
        summary.setText(s);
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- add / edit / delete

    private void addFromInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        db.addTask(text, pendingDue, selSub != 0 ? selSub : selTop, 0);
        pendingDue = 0;
        input.setText("");
        reload();
        list.setSelection(0);
    }

    private void editTask(final Db.Task task) {
        final long[] due = {task.due};
        LinearLayout box = Util.dialogBox(a);
        final EditText field = Util.field(a, task.title);
        box.addView(field);

        box.addView(Util.label(a, "Category"));
        final List<Long> catIds = new ArrayList<Long>();
        List<String> catNames = new ArrayList<String>();
        catIds.add(0L);
        catNames.add("No category");
        for (Db.Category k : categories) {
            catIds.add(k.id);
            catNames.add(k.parent == 0 ? k.name : "    " + categoryPath(k.id));
        }
        final Spinner catSpinner = new Spinner(a);
        catSpinner.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, catNames));
        catSpinner.setSelection(Math.max(0, catIds.indexOf(task.category)));
        box.addView(catSpinner);

        box.addView(Util.label(a, "Priority"));
        final Spinner prioSpinner = new Spinner(a);
        prioSpinner.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item,
                PRIORITY_NAMES));
        prioSpinner.setSelection(task.priority);
        box.addView(prioSpinner);

        box.addView(Util.label(a, "Due date"));
        final Button dueButton = new Button(a, null, android.R.attr.borderlessButtonStyle);
        dueButton.setText(task.due > 0 ? Util.dueLabel(task.due) : a.getString(R.string.set_due));
        dueButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(due[0], new DatePicked() {
                    public void onPicked(long day) {
                        due[0] = day;
                        dueButton.setText(day > 0 ? Util.dueLabel(day) : a.getString(R.string.set_due));
                    }
                });
            }
        });
        box.addView(dueButton);

        new AlertDialog.Builder(a)
                .setTitle("Edit task")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String t = field.getText().toString().trim();
                        if (t.isEmpty()) t = task.title;
                        db.updateTask(task.id, t, due[0], catIds.get(catSpinner.getSelectedItemPosition()),
                                prioSpinner.getSelectedItemPosition());
                        reload();
                    }
                })
                .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { confirmDelete(task); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(final Db.Task task) {
        Util.confirm(a, "Delete?", task.title, "Delete", new Runnable() {
            public void run() {
                db.deleteTask(task.id);
                reload();
            }
        });
    }

    private void choosePriority(final Db.Task t) {
        new AlertDialog.Builder(a)
                .setTitle("Priority")
                .setSingleChoiceItems(PRIORITY_NAMES, t.priority, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        db.setPriority(t.id, which);
                        d.dismiss();
                        reload();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- menu

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_SHOW_DONE, 0, "Show all completed tasks").setCheckable(true);
        menu.add(0, MENU_SHARE, 1, "Export / share tasks");
        menu.add(0, MENU_CLEAR_DONE, 2, "Delete completed tasks");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        menu.findItem(MENU_SHOW_DONE).setChecked(showDone);
    }

    @Override
    boolean onMenu(int id) {
        switch (id) {
            case MENU_SHOW_DONE:
                showDone = !showDone;
                a.prefs.edit().putBoolean("showDone", showDone).apply();
                reload();
                return true;
            case MENU_SHARE:
                a.shareText(a.getString(R.string.app_name) + " tasks", exportText());
                return true;
            case MENU_CLEAR_DONE:
                Util.confirm(a, "Delete completed tasks?", "This permanently deletes all completed tasks.", "Delete",
                        new Runnable() {
                            public void run() {
                                int n = db.clearDone();
                                Toast.makeText(a, "Deleted " + n + " task(s)", Toast.LENGTH_SHORT).show();
                                reload();
                            }
                        });
                return true;
        }
        return false;
    }

    private String exportText() {
        loadCategories();
        List<Db.Task> tasks = db.tasks();
        StringBuilder sb = new StringBuilder("TO-DO\n");
        List<Long> groups = new ArrayList<Long>();
        for (Db.Category k : categories) groups.add(k.id);
        groups.add(0L);
        for (long g : groups) {
            StringBuilder part = new StringBuilder();
            for (Db.Task t : tasks) {
                if (t.category != g) continue;
                part.append(t.done ? "[x] " : "[ ] ").append(t.title);
                if (t.priority > 0) part.append("  !").append(PRIORITY_NAMES[t.priority]);
                if (t.due > 0 && !t.done) part.append("  (").append(Util.dueLabel(t.due)).append(")");
                part.append('\n');
            }
            if (part.length() == 0) continue;
            sb.append('\n').append(g == 0 ? "No category" : categoryPath(g)).append('\n').append(part);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- dates

    private interface DatePicked {
        void onPicked(long value);
    }

    /** Date picker for due dates; offers "No date" to clear. Reports local midnight in millis. */
    private void pickDate(long current, final DatePicked callback) {
        Calendar c = Calendar.getInstance();
        if (current > 0) c.setTimeInMillis(current);
        DatePickerDialog dlg = new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
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

    // ---------------------------------------------------------------- drag to reorder

    /**
     * Drag a task by its handle to reorder it among neighbouring tasks of the same priority (in the same
     * section). The new order is remembered.
     */
    private boolean onDrag(Db.Task t, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragging = t;
                list.requestDisallowInterceptTouchEvent(true);
                adapter.notifyDataSetChanged();
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (dragging == null) return false;
                int[] loc = new int[2];
                list.getLocationOnScreen(loc);
                int y = (int) e.getRawY() - loc[1];
                int edge = Util.dp(a, 56);
                if (y < edge) list.smoothScrollBy(-Util.dp(a, 12), 16);
                else if (y > list.getHeight() - edge) list.smoothScrollBy(Util.dp(a, 12), 16);
                int target = list.pointToPosition(Util.dp(a, 40), Math.max(0, Math.min(y, list.getHeight() - 1)));
                int from = rows.indexOf(dragging);
                if (target == android.widget.AdapterView.INVALID_POSITION || from < 0 || target == from) return true;
                int[] block = blockAround(from);
                target = Math.max(block[0], Math.min(block[1], target));
                if (target != from) {
                    rows.remove(from);
                    rows.add(target, dragging);
                    adapter.notifyDataSetChanged();
                }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                if (dragging == null) return false;
                int at = rows.indexOf(dragging);
                if (at >= 0) {
                    int[] block = blockAround(at);
                    List<Db.Task> ordered = new ArrayList<Db.Task>();
                    for (int i = block[0]; i <= block[1]; i++) ordered.add((Db.Task) rows.get(i));
                    db.setPositions(ordered);
                }
                dragging = null;
                reload();
                return true;
            }
        }
        return false;
    }

    /** First and last row index of the run of open tasks with the same priority around row i. */
    private int[] blockAround(int i) {
        int p = ((Db.Task) rows.get(i)).priority;
        int lo = i, hi = i;
        while (lo - 1 >= 0 && sameBlock(rows.get(lo - 1), p)) lo--;
        while (hi + 1 < rows.size() && sameBlock(rows.get(hi + 1), p)) hi++;
        return new int[]{lo, hi};
    }

    private static boolean sameBlock(Object row, int priority) {
        return row instanceof Db.Task && !((Db.Task) row).done && ((Db.Task) row).priority == priority;
    }

    // ---------------------------------------------------------------- list adapter

    private class RowAdapter extends BaseAdapter {
        private static final int TYPE_TASK = 0, TYPE_HEADER = 1;

        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public int getViewTypeCount() { return 2; }
        public boolean areAllItemsEnabled() { return false; }

        public int getItemViewType(int position) {
            return rows.get(position) instanceof Db.Task ? TYPE_TASK : TYPE_HEADER;
        }

        public boolean isEnabled(int position) {
            return getItemViewType(position) == TYPE_TASK;
        }

        public View getView(int position, View view, ViewGroup parent) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (getItemViewType(position) == TYPE_TASK) {
                if (view == null) view = inf.inflate(R.layout.item_task, parent, false);
                bindTask(view, (Db.Task) rows.get(position));
                return view;
            }
            if (view == null) view = inf.inflate(R.layout.item_header, parent, false);
            Util.Header h = (Util.Header) rows.get(position);
            ((TextView) view).setText(h.text);
            ((TextView) view).setTextColor(h.color != 0 ? h.color : Util.color(a, R.color.muted));
            return view;
        }

        private void bindTask(View view, final Db.Task t) {
            Context ctx = view.getContext();
            CheckBox check = (CheckBox) view.findViewById(R.id.check);
            TextView title = (TextView) view.findViewById(R.id.title);
            TextView due = (TextView) view.findViewById(R.id.due);
            TextView cat = (TextView) view.findViewById(R.id.category);
            View prio = view.findViewById(R.id.prio);
            ImageView prioIcon = (ImageView) view.findViewById(R.id.prio_icon);
            TextView prioLabel = (TextView) view.findViewById(R.id.prio_label);

            View handle = view.findViewById(R.id.drag);
            handle.setVisibility(t.done ? View.INVISIBLE : View.VISIBLE);
            handle.setOnTouchListener(t.done ? null : new View.OnTouchListener() {
                public boolean onTouch(View v, android.view.MotionEvent e) { return onDrag(t, e); }
            });
            view.setAlpha(dragging != null && dragging.id == t.id ? 0.55f : 1f);

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
                title.setTextColor(Util.color(ctx, R.color.muted));
            } else {
                title.setPaintFlags(title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                title.setTextColor(Util.color(ctx, R.color.text));
            }

            // Due date, or a light-red "+ Add due date" shortcut.
            View.OnClickListener pickDue = new View.OnClickListener() {
                public void onClick(View v) {
                    pickDate(t.due, new DatePicked() {
                        public void onPicked(long day) {
                            db.setDue(t.id, day);
                            reload();
                        }
                    });
                }
            };
            if (t.done) {
                due.setText("Done " + Util.fullTimestamp(ctx, t.doneAt));
                due.setTextColor(Util.color(ctx, R.color.muted));
                due.setOnClickListener(null);
                due.setClickable(false);
            } else if (t.due > 0) {
                boolean overdue = Util.daysFromToday(t.due) < 0;
                due.setText(Util.dueLabel(t.due));
                due.setTextColor(Util.color(ctx, overdue ? R.color.overdue : R.color.primary_text));
                due.setOnClickListener(pickDue);
            } else {
                due.setText(R.string.add_due);
                due.setTextColor(Util.color(ctx, R.color.add_due));
                due.setOnClickListener(pickDue);
            }

            // In category sections the header already names the category (except in "High priority").
            Db.Category k = categoryById.get(t.category);
            boolean showCat = k != null && t.category != selSub
                    && (!grouped || (t.priority == 3 && !t.done) || t.done);
            if (showCat) {
                cat.setVisibility(View.VISIBLE);
                cat.setText("● " + categoryPath(t.category));
                cat.setTextColor(Util.readable(ctx, k.color));
            } else {
                cat.setVisibility(View.GONE);
            }

            // Priority flag: tap cycles None → Low → Medium → High, long-press picks directly.
            int color = Util.color(ctx, PRIORITY_COLORS[t.priority]);
            prioIcon.setImageResource(t.priority == 0 ? R.drawable.ic_flag_outline : R.drawable.ic_flag);
            prioIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            prioLabel.setText(t.priority == 0 ? "" : PRIORITY_NAMES[t.priority]);
            prioLabel.setVisibility(t.priority == 0 ? View.GONE : View.VISIBLE);
            prioLabel.setTextColor(color);
            prio.setAlpha(t.done ? 0.4f : 1f);
            prio.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    db.setPriority(t.id, (t.priority + 1) % 4);
                    reload();
                }
            });
            prio.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    choosePriority(t);
                    return true;
                }
            });
        }
    }
}
