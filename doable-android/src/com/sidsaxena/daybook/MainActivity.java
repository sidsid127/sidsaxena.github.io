package com.sidsaxena.daybook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.KeyguardManager;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
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
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int MODE_TASKS = 0;
    private static final int MODE_LOG = 1;

    private static final int MENU_SHOW_DONE = 1;
    private static final int MENU_LOG = 2;
    private static final int MENU_LOCK = 3;
    private static final int MENU_SHARE = 4;
    private static final int MENU_CLEAR_DONE = 5;

    private static final int REQ_UNLOCK = 1;

    /** Completed tasks stay visible (struck through) for this long, unless "show completed" is on. */
    private static final long DONE_VISIBLE_MS = 12L * 60 * 60 * 1000;

    private static final String[] PRIORITY_NAMES = {"None", "Low", "Medium", "High"};
    private static final int[] PRIORITY_COLORS = {
            R.color.prio_none, R.color.prio_low, R.color.prio_med, R.color.prio_high};

    private Db db;
    private SharedPreferences prefs;
    private int mode;
    private long pendingDue; // due date picked for the next task to be added, 0 = none
    private long selTop;     // selected top-level category filter, 0 = all
    private long selSub;     // selected sub-category filter, 0 = all of selTop
    private boolean showDone;

    // App lock state
    private boolean locked;
    private boolean authInProgress;
    private boolean leavingForExternal; // launched the share sheet or the system lock screen

    private TextView summary, empty;
    private ListView list;
    private EditText input;
    private ImageButton dueBtn;
    private View chipBar, subScroll, lockView;
    private LinearLayout chipsTop, chipsSub;

    private List<Db.Category> categories = new ArrayList<Db.Category>();
    private final Map<Long, Db.Category> categoryById = new HashMap<Long, Db.Category>();

    /** Rows shown in the list: Db.Task, Db.Entry, or String (day header in the log). */
    private final List<Object> rows = new ArrayList<Object>();
    private final RowAdapter adapter = new RowAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new Db(this);
        prefs = getSharedPreferences("daybook", MODE_PRIVATE);
        selTop = prefs.getLong("selTop", 0);
        selSub = prefs.getLong("selSub", 0);
        showDone = prefs.getBoolean("showDone", false);

        summary = (TextView) findViewById(R.id.summary);
        empty = (TextView) findViewById(R.id.empty);
        list = (ListView) findViewById(R.id.list);
        input = (EditText) findViewById(R.id.input);
        dueBtn = (ImageButton) findViewById(R.id.due_btn);
        chipBar = findViewById(R.id.chip_bar);
        subScroll = findViewById(R.id.sub_scroll);
        chipsTop = (LinearLayout) findViewById(R.id.chips_top);
        chipsSub = (LinearLayout) findViewById(R.id.chips_sub);
        lockView = findViewById(R.id.lock_view);

        list.setAdapter(adapter);
        list.setEmptyView(empty);

        findViewById(R.id.add_btn).setOnClickListener(new View.OnClickListener() {
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

        findViewById(R.id.unlock_btn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { authenticate(); }
        });

        locked = lockEnabled() && (savedInstanceState == null || savedInstanceState.getBoolean("locked", true));
        setMode(MODE_TASKS);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("locked", locked);
    }

    @Override
    protected void onStart() {
        super.onStart();
        leavingForExternal = false;
        showLockScreen(locked);
        if (locked && !authInProgress) {
            lockView.post(new Runnable() {
                public void run() { if (locked && !authInProgress) authenticate(); }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Relative labels ("Today", "Overdue") and the 12-hour completed window depend on the clock.
        reload();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (lockEnabled() && !leavingForExternal && !isChangingConfigurations()) {
            locked = true;
            showLockScreen(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (mode == MODE_LOG) setMode(MODE_TASKS);
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- app lock

    private boolean lockEnabled() {
        return prefs.getBoolean("lock", false);
    }

    private void authenticate() {
        String title = "Unlock " + getString(R.string.app_name);
        if (Build.VERSION.SDK_INT >= 28) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                    .setTitle(title)
                    .setSubtitle("Use your fingerprint or phone screen lock");
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            authInProgress = true;
            try {
                b.build().authenticate(new CancellationSignal(), getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                authInProgress = false;
                                unlock();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                authInProgress = false; // stay on the lock screen; "Unlock" retries
                                if (code == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                                        || code == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                                    // Phone has no screen lock any more, nothing to check against.
                                    unlock();
                                }
                            }
                        });
            } catch (RuntimeException e) {
                authInProgress = false;
                unlock();
            }
        } else {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            Intent i = km.createConfirmDeviceCredentialIntent(title, "Use your fingerprint or phone screen lock");
            if (i == null) { // no screen lock set on the phone
                unlock();
                return;
            }
            authInProgress = true;
            leavingForExternal = true;
            startActivityForResult(i, REQ_UNLOCK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_UNLOCK) {
            authInProgress = false;
            if (resultCode == RESULT_OK) unlock();
        }
    }

    private void unlock() {
        locked = false;
        showLockScreen(false);
    }

    /** The lock screen also hides the action bar so its menu (e.g. turning the lock off) is unreachable. */
    private void showLockScreen(boolean show) {
        lockView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (getActionBar() != null) {
            if (show) getActionBar().hide();
            else getActionBar().show();
        }
        if (show) {
            input.clearFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    private void toggleLock() {
        if (lockEnabled()) {
            prefs.edit().putBoolean("lock", false).apply();
            Toast.makeText(this, "Fingerprint lock turned off", Toast.LENGTH_SHORT).show();
            return;
        }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!km.isDeviceSecure()) {
            new AlertDialog.Builder(this)
                    .setTitle("Set up a screen lock first")
                    .setMessage("To lock this app, add a fingerprint and a PIN/pattern in your phone's "
                            + "Settings → Security. Then turn this on again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        prefs.edit().putBoolean("lock", true).apply();
        Toast.makeText(this, "Locked whenever you leave the app. Unlock with your fingerprint.",
                Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- modes & categories

    private void setMode(int newMode) {
        mode = newMode;
        boolean tasks = mode == MODE_TASKS;
        chipBar.setVisibility(tasks ? View.VISIBLE : View.GONE);
        dueBtn.setVisibility(tasks ? View.VISIBLE : View.GONE);
        empty.setText(tasks ? R.string.empty_tasks : R.string.empty_log);
        if (getActionBar() != null) {
            getActionBar().setTitle(tasks ? getString(R.string.app_name) : getString(R.string.menu_log));
            getActionBar().setDisplayHomeAsUpEnabled(!tasks);
        }
        invalidateOptionsMenu();
        updateInputHint();
        reload();
    }

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
        prefs.edit().putLong("selTop", selTop).putLong("selSub", selSub).apply();
        updateInputHint();
        reload();
    }

    private void buildChips() {
        chipsTop.removeAllViews();
        chipsSub.removeAllViews();

        chipsTop.addView(chip(getString(R.string.all), getResources().getColor(R.color.primary), selTop == 0,
                new View.OnClickListener() {
                    public void onClick(View v) { selectFilter(0, 0); }
                }, null));
        for (final Db.Category k : categories) {
            if (k.parent != 0) continue;
            chipsTop.addView(chip(k.name, k.color, selTop == k.id, new View.OnClickListener() {
                public void onClick(View v) { selectFilter(k.id, 0); }
            }, k));
        }
        chipsTop.addView(chip("+ Category", getResources().getColor(R.color.muted), false,
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
        chipsSub.addView(chip("+ Sub-category", getResources().getColor(R.color.muted), false,
                new View.OnClickListener() {
                    public void onClick(View v) { promptNewCategory(top.id); }
                }, null));
    }

    /** A pill-shaped filter chip. Long-pressing a category chip offers rename/delete. */
    private TextView chip(String text, int color, boolean selected, View.OnClickListener click,
                          final Db.Category category) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (selected) {
            bg.setColor(color);
            t.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0xFFFFFFFF);
            bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x66000000);
            t.setTextColor(color);
        }
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(3), dp(4), dp(3));
        t.setLayoutParams(lp);
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
        final EditText field = dialogField("");
        field.setHint(parent == 0 ? "e.g. Family, Health" : "e.g. Customer A");
        new AlertDialog.Builder(this)
                .setTitle(parent == 0 ? "New category" : "New sub-category in " + categoryById.get(parent).name)
                .setView(wrap(field))
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String name = field.getText().toString().trim();
                        if (name.isEmpty()) return;
                        long id = db.addCategory(name, parent);
                        if (parent == 0) selectFilter(id, 0);
                        else selectFilter(parent, id);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        showKeyboard(field);
    }

    private void categoryOptions(final Db.Category k) {
        new AlertDialog.Builder(this)
                .setTitle(k.name)
                .setItems(new String[]{"Rename", "Delete"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) renameCategory(k);
                        else deleteCategory(k);
                    }
                })
                .show();
    }

    private void renameCategory(final Db.Category k) {
        final EditText field = dialogField(k.name);
        new AlertDialog.Builder(this)
                .setTitle("Rename category")
                .setView(wrap(field))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String name = field.getText().toString().trim();
                        if (!name.isEmpty()) db.renameCategory(k.id, name);
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteCategory(final Db.Category k) {
        String where = k.parent == 0 ? "will have no category" : "move to " + categoryById.get(k.parent).name;
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + k.name + "\"?")
                .setMessage((k.parent == 0 ? "Its sub-categories are deleted too. " : "")
                        + "Tasks are not deleted, they " + where + ".")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        db.deleteCategory(k);
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null)
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
        if (mode == MODE_LOG) {
            input.setHint(R.string.hint_log);
            return;
        }
        long target = selSub != 0 ? selSub : selTop;
        String hint = "Add a task";
        if (target != 0 && categoryById.containsKey(target)) hint += " to " + categoryById.get(target).name;
        if (pendingDue > 0) hint += " · " + dueLabel(pendingDue).toLowerCase(Locale.getDefault());
        input.setHint(hint + "…");
    }

    // ---------------------------------------------------------------- list contents

    private void reload() {
        rows.clear();
        if (mode == MODE_TASKS) {
            loadCategories();
            buildChips();
            updateInputHint();
            long now = System.currentTimeMillis();
            long today = startOfDay(now);
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
            Collections.sort(open, new Comparator<Db.Task>() {
                public int compare(Db.Task a, Db.Task b) {
                    if (a.priority != b.priority) return b.priority - a.priority;
                    long da = a.due > 0 ? a.due : Long.MAX_VALUE, dbb = b.due > 0 ? b.due : Long.MAX_VALUE;
                    if (da != dbb) return da < dbb ? -1 : 1;
                    return a.created > b.created ? -1 : (a.created < b.created ? 1 : 0);
                }
            });
            Collections.sort(done, new Comparator<Db.Task>() {
                public int compare(Db.Task a, Db.Task b) {
                    return a.doneAt > b.doneAt ? -1 : (a.doneAt < b.doneAt ? 1 : 0);
                }
            });
            rows.addAll(open);
            if (!done.isEmpty()) {
                rows.add(showDone ? "Completed" : "Completed · hidden after 12 hours");
                rows.addAll(done);
            }

            StringBuilder s = new StringBuilder();
            s.append(open.size()).append(" open");
            if (high > 0) s.append(" · ").append(high).append(" high priority");
            if (overdue > 0) s.append(" · ").append(overdue).append(" overdue");
            if (hiddenDone > 0) s.append(" · ").append(hiddenDone).append(" older completed hidden");
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
            db.addTask(text, pendingDue, selSub != 0 ? selSub : selTop, 0);
            pendingDue = 0;
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

        box.addView(label("Category"));
        final List<Long> catIds = new ArrayList<Long>();
        List<String> catNames = new ArrayList<String>();
        catIds.add(0L);
        catNames.add("No category");
        for (Db.Category k : categories) {
            catIds.add(k.id);
            catNames.add(k.parent == 0 ? k.name : "    " + categoryPath(k.id));
        }
        final Spinner catSpinner = new Spinner(this);
        catSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, catNames));
        catSpinner.setSelection(Math.max(0, catIds.indexOf(task.category)));
        box.addView(catSpinner);

        box.addView(label("Priority"));
        final Spinner prioSpinner = new Spinner(this);
        prioSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                PRIORITY_NAMES));
        prioSpinner.setSelection(task.priority);
        box.addView(prioSpinner);

        box.addView(label("Due date"));
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

    private void choosePriority(final Db.Task t) {
        new AlertDialog.Builder(this)
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

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        return box;
    }

    private View wrap(View v) {
        LinearLayout box = dialogBox();
        box.addView(v);
        return box;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(getResources().getColor(R.color.muted));
        t.setPadding(dp(4), dp(14), 0, 0);
        return t;
    }

    private EditText dialogField(String text) {
        EditText field = new EditText(this);
        field.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setText(text);
        field.setSelection(text.length());
        return field;
    }

    private void showKeyboard(final EditText field) {
        field.requestFocus();
        field.postDelayed(new Runnable() {
            public void run() {
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .showSoftInput(field, 0);
            }
        }, 200);
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SHOW_DONE, 0, R.string.menu_show_done).setCheckable(true);
        menu.add(0, MENU_LOG, 1, R.string.menu_log);
        menu.add(0, MENU_LOCK, 2, R.string.menu_lock).setCheckable(true);
        menu.add(0, MENU_SHARE, 3, R.string.menu_share);
        menu.add(0, MENU_CLEAR_DONE, 4, R.string.menu_clear_done);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean tasks = mode == MODE_TASKS;
        menu.findItem(MENU_SHOW_DONE).setChecked(showDone).setVisible(tasks);
        menu.findItem(MENU_LOG).setVisible(tasks);
        menu.findItem(MENU_LOCK).setChecked(lockEnabled());
        menu.findItem(MENU_CLEAR_DONE).setVisible(tasks);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (locked) return true;
        switch (item.getItemId()) {
            case android.R.id.home:
                setMode(MODE_TASKS);
                return true;
            case MENU_SHOW_DONE:
                showDone = !showDone;
                prefs.edit().putBoolean("showDone", showDone).apply();
                reload();
                return true;
            case MENU_LOG:
                setMode(MODE_LOG);
                return true;
            case MENU_LOCK:
                toggleLock();
                return true;
            case MENU_SHARE:
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name) + " export");
                send.putExtra(Intent.EXTRA_TEXT, exportText());
                leavingForExternal = true;
                startActivity(Intent.createChooser(send, getString(R.string.menu_share)));
                return true;
            case MENU_CLEAR_DONE:
                new AlertDialog.Builder(this)
                        .setMessage("Permanently delete all completed tasks?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                int n = db.clearDone();
                                Toast.makeText(MainActivity.this, "Deleted " + n + " task(s)", Toast.LENGTH_SHORT).show();
                                reload();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String exportText() {
        loadCategories();
        List<Db.Task> tasks = db.tasks();
        StringBuilder sb = new StringBuilder("TO-DO\n");
        List<Long> groups = new ArrayList<Long>();
        groups.add(0L);
        for (Db.Category k : categories) groups.add(k.id);
        for (long g : groups) {
            StringBuilder part = new StringBuilder();
            for (Db.Task t : tasks) {
                if (t.category != g) continue;
                part.append(t.done ? "[x] " : "[ ] ").append(t.title);
                if (t.priority > 0) part.append("  !").append(PRIORITY_NAMES[t.priority]);
                if (t.due > 0 && !t.done) part.append("  (").append(dueLabel(t.due)).append(")");
                part.append('\n');
            }
            if (part.length() == 0) continue;
            sb.append('\n').append(g == 0 ? "No category" : categoryPath(g)).append('\n').append(part);
        }
        List<Db.Entry> entries = db.entries();
        if (!entries.isEmpty()) {
            sb.append("\n\nACTIVITY LOG\n");
            long lastDay = -1;
            for (Db.Entry e : entries) {
                long day = startOfDay(e.ts);
                if (day != lastDay) {
                    sb.append('\n').append(new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(new Date(day))).append('\n');
                    lastDay = day;
                }
                sb.append(timeOfDay(e.ts)).append("  ").append(e.text).append('\n');
            }
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
        public boolean areAllItemsEnabled() { return false; }

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
            TextView due = (TextView) view.findViewById(R.id.due);
            TextView cat = (TextView) view.findViewById(R.id.category);
            View prio = view.findViewById(R.id.prio);
            ImageView prioIcon = (ImageView) view.findViewById(R.id.prio_icon);
            TextView prioLabel = (TextView) view.findViewById(R.id.prio_label);

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
                due.setText("Done " + fullTimestamp(t.doneAt));
                due.setTextColor(ctx.getResources().getColor(R.color.muted));
                due.setOnClickListener(null);
                due.setClickable(false);
            } else if (t.due > 0) {
                boolean overdue = daysFromToday(t.due) < 0;
                due.setText(dueLabel(t.due));
                due.setTextColor(ctx.getResources().getColor(overdue ? R.color.overdue : R.color.primary));
                due.setOnClickListener(pickDue);
            } else {
                due.setText(R.string.add_due);
                due.setTextColor(ctx.getResources().getColor(R.color.add_due));
                due.setOnClickListener(pickDue);
            }

            Db.Category k = categoryById.get(t.category);
            if (k != null && t.category != selSub) {
                cat.setVisibility(View.VISIBLE);
                cat.setText("● " + categoryPath(t.category));
                cat.setTextColor(k.color);
            } else {
                cat.setVisibility(View.GONE);
            }

            // Priority flag: tap cycles None → Low → Medium → High, long-press picks directly.
            int color = ctx.getResources().getColor(PRIORITY_COLORS[t.priority]);
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
