package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Habits to build (drink water 8×, read, walk) and habits to quit (no sweets), with streaks,
 * a 7-day history you can tap to correct, and reminders at set times or every few hours.
 */
class HabitsScreen extends Page {

    private static final int MENU_ARCHIVED = 170;
    private static final String[] DAY_LETTERS = {"S", "M", "T", "W", "T", "F", "S"};
    private static final String[] REMIND_MODES = {"No reminder", "At set times", "Every few hours"};

    /** Suggestions shown when there are no habits yet: emoji, name, quit, target, reminder spec. */
    private static final String[][] IDEAS = {
            {"💧", "Drink a glass of water", "0", "8", "every:120:08:00-20:00"},
            {"🍬", "No sweets", "1", "1", "20:00"},
            {"🚶", "Walk 10,000 steps", "0", "1", "18:00"},
            {"📖", "Read 20 minutes", "0", "1", "21:30"},
            {"🧘", "Meditate", "0", "1", "07:30"},
            {"💊", "Take vitamins", "0", "1", "08:00"},
            {"📵", "No phone after 22:00", "1", "1", "21:45"},
    };

    private boolean showArchived;

    HabitsScreen(MainActivity a) {
        super(a);
        setFab(new Runnable() {
            public void run() { edit(new Store.Habit()); }
        });
    }

    @Override
    String title() {
        return showArchived ? "Archived habits" : a.getString(R.string.module_habits);
    }

    @Override
    boolean homeIsBack() {
        return showArchived;
    }

    @Override
    boolean onBack() {
        if (!showArchived) return false;
        showArchived = false;
        a.screenChanged();
        render();
        return true;
    }

    @Override
    void onCreateMenu(Menu menu) {
        if (!showArchived) menu.add(0, MENU_ARCHIVED, 0, "Archived habits");
    }

    @Override
    boolean onMenu(int id) {
        if (id != MENU_ARCHIVED) return false;
        showArchived = true;
        a.screenChanged();
        render();
        return true;
    }

    @Override
    void open(long id) {
        for (Store.Habit h : st.habits(false)) if (h.id == id) edit(h);
    }

    @Override
    void render() {
        body.removeAllViews();
        fab.setVisibility(showArchived ? View.GONE : View.VISIBLE);
        List<Store.Habit> list = st.habits(showArchived);
        long today = Util.startOfDay(System.currentTimeMillis());
        if (list.isEmpty()) {
            if (showArchived) {
                empty("No archived habits.");
                return;
            }
            empty("Build good habits and break bad ones.\nTap + or start with an idea:");
            LinearLayout card = card();
            for (final String[] idea : IDEAS) {
                row(card, idea[0], idea[1], idea[2].equals("1") ? "Quit · streak of days without" : "Build"
                        + (idea[3].equals("1") ? "" : " · " + idea[3] + "× a day"), null, new Runnable() {
                    public void run() {
                        Store.Habit h = new Store.Habit();
                        h.emoji = idea[0];
                        h.name = idea[1];
                        h.quit = idea[2].equals("1");
                        h.target = Integer.parseInt(idea[3]);
                        h.remind = idea[4];
                        edit(h);
                    }
                }, null);
            }
            return;
        }
        for (Store.Habit h : list) habitCard(h, today);
    }

    private void habitCard(final Store.Habit h, final long today) {
        LinearLayout card = card();
        final Map<Long, Integer> log = st.habitLog(h.id);
        final int n = log.containsKey(today) ? log.get(today) : 0;
        int[] streak = HabitLogic.streaks(h.quit, h.target, h.days, log, today, h.created);

        String sub;
        if (h.quit) sub = "🔥 " + streak[0] + " day" + (streak[0] == 1 ? "" : "s") + " clean · best " + streak[1];
        else sub = (h.target > 1 ? n + " of " + h.target + " today · " : "") + "🔥 " + streak[0] + " · best " + streak[1];
        if (!h.remind.isEmpty()) sub += " · 🔔";

        TextView end;
        if (showArchived) {
            end = null;
        } else if (h.quit) {
            end = pill(n > 0 ? "Slipped" : "Slip?", n > 0, new Runnable() {
                public void run() {
                    st.setHabitCount(h.id, today, n > 0 ? 0 : 1);
                    changed();
                }
            });
        } else {
            end = pill(n >= h.target ? "✓" : h.target > 1 ? "+1" : "Done", n >= h.target, new Runnable() {
                public void run() {
                    st.setHabitCount(h.id, today, n + 1);
                    changed();
                }
            });
            end.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    st.setHabitCount(h.id, today, Math.max(0, n - 1));
                    changed();
                    return true;
                }
            });
        }
        row(card, h.emoji.isEmpty() ? (h.quit ? "🚫" : "✔") : h.emoji, h.name, sub, end, new Runnable() {
            public void run() { edit(h); }
        }, new Runnable() {
            public void run() { actions(h); }
        });

        // Last 7 days; tap a day to correct it.
        LinearLayout week = new LinearLayout(a);
        week.setOrientation(LinearLayout.HORIZONTAL);
        week.setPadding(dp(56), 0, dp(12), dp(8));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(today);
        c.add(Calendar.DAY_OF_MONTH, -6);
        for (int i = 0; i < 7; i++) {
            final long day = c.getTimeInMillis();
            boolean scheduled = HabitLogic.scheduled(h.days, c);
            boolean before = day < h.created;
            final Integer count = log.get(day);
            boolean ok = HabitLogic.success(h.quit, h.target, count);
            TextView d = new TextView(a);
            d.setGravity(Gravity.CENTER);
            d.setTextSize(11);
            String mark = !scheduled || before ? "·" : ok ? "●" : day == today ? "○" : h.quit ? "✕" : "○";
            d.setText(DAY_LETTERS[c.get(Calendar.DAY_OF_WEEK) - 1] + "\n" + mark);
            d.setTextColor(ok && scheduled && !before ? Util.color(a, R.color.primary_text)
                    : !ok && scheduled && !before && h.quit ? Util.color(a, R.color.overdue) : Util.color(a, R.color.muted));
            if (scheduled && !showArchived) {
                d.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        int now = count == null ? 0 : count;
                        if (h.quit) st.setHabitCount(h.id, day, now > 0 ? 0 : 1);
                        else st.setHabitCount(h.id, day, now >= h.target ? 0 : h.target);
                        changed();
                    }
                });
            }
            week.addView(d, new LinearLayout.LayoutParams(0, dp(36), 1));
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        card.addView(week);
    }

    private void changed() {
        Reminders.refresh(a);
        render();
    }

    private void actions(final Store.Habit h) {
        String[] items = {"Edit", h.archived ? "Restore" : "Archive (keep history)", "Delete"};
        new AlertDialog.Builder(a)
                .setTitle(h.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) edit(h);
                        else if (which == 1) {
                            h.archived = !h.archived;
                            st.saveHabit(h);
                            changed();
                        } else {
                            Util.confirm(a, "Delete \"" + h.name + "\"?", "Its history and streaks are deleted too.", "Delete", new Runnable() {
                                public void run() {
                                    st.deleteHabit(h.id);
                                    changed();
                                }
                            });
                        }
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- editor

    private void edit(final Store.Habit h) {
        LinearLayout box = Util.dialogBox(a);

        box.addView(Util.label(a, "Habit"));
        LinearLayout nameRow = new LinearLayout(a);
        final EditText emoji = Util.field(a, h.emoji);
        emoji.setHint("🙂");
        emoji.setGravity(Gravity.CENTER);
        nameRow.addView(emoji, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
        final EditText name = Util.field(a, h.name);
        name.setHint("e.g. Drink a glass of water");
        nameRow.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(nameRow);

        final RadioGroup kind = new RadioGroup(a);
        kind.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton build = new RadioButton(a);
        build.setText("Build");
        build.setId(View.generateViewId());
        final RadioButton quit = new RadioButton(a);
        quit.setText("Quit (no …)");
        quit.setId(View.generateViewId());
        kind.addView(build);
        kind.addView(quit);
        kind.check(h.quit ? quit.getId() : build.getId());
        box.addView(kind);

        final TextView targetLabel = Util.label(a, "Times per day");
        box.addView(targetLabel);
        final EditText target = Util.field(a, String.valueOf(Math.max(1, h.target)));
        target.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(target);
        RadioGroup.OnCheckedChangeListener kindChanged = new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) {
                int vis = id == quit.getId() ? View.GONE : View.VISIBLE;
                targetLabel.setVisibility(vis);
                target.setVisibility(vis);
            }
        };
        kind.setOnCheckedChangeListener(kindChanged);
        kindChanged.onCheckedChanged(kind, kind.getCheckedRadioButtonId());

        box.addView(Util.label(a, "Days"));
        LinearLayout days = new LinearLayout(a);
        final boolean[] on = new boolean[7];
        for (int i = 0; i < 7; i++) on[i] = (h.days & (1 << i)) != 0;
        // Monday first
        for (int k = 0; k < 7; k++) {
            final int i = (k + 1) % 7;
            final TextView chip = dayChip(DAY_LETTERS[i], on[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    on[i] = !on[i];
                    styleDay((TextView) v, on[i]);
                }
            });
            days.addView(chip);
        }
        box.addView(days);

        box.addView(Util.label(a, "Reminder"));
        final Spinner mode = new Spinner(a);
        mode.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, REMIND_MODES));
        box.addView(mode);
        final EditText times = Util.field(a, "");
        times.setHint("Times, e.g. 08:00, 20:00");
        box.addView(times);
        final LinearLayout every = new LinearLayout(a);
        every.setGravity(Gravity.CENTER_VERTICAL);
        final EditText hours = Util.field(a, "2");
        hours.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        every.addView(text("Every"));
        every.addView(hours, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
        every.addView(text("h from"));
        final EditText from = Util.field(a, "08:00");
        every.addView(from, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        every.addView(text("to"));
        final EditText to = Util.field(a, "20:00");
        every.addView(to, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(every);

        if (h.remind.startsWith("every:")) {
            mode.setSelection(2);
            try {
                String[] p = h.remind.split(":", 3);
                int mins = Integer.parseInt(p[1]);
                hours.setText(mins % 60 == 0 ? String.valueOf(mins / 60) : String.valueOf(mins / 60.0));
                String[] range = p[2].split("-");
                from.setText(range[0]);
                to.setText(range[1]);
            } catch (RuntimeException ignored) {
            }
        } else if (!h.remind.isEmpty()) {
            mode.setSelection(1);
            times.setText(h.remind.replace(",", ", "));
        }
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                times.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                every.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
            }

            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(h.id == 0 ? "New habit" : "Edit habit")
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) return;
                        h.name = n;
                        h.emoji = emoji.getText().toString().trim();
                        h.quit = kind.getCheckedRadioButtonId() == quit.getId();
                        try {
                            h.target = Math.max(1, Math.min(99, Integer.parseInt(target.getText().toString().trim())));
                        } catch (NumberFormatException e) {
                            h.target = 1;
                        }
                        int mask = 0;
                        for (int i = 0; i < 7; i++) if (on[i]) mask |= 1 << i;
                        h.days = mask == 0 ? 127 : mask;
                        h.remind = HabitLogic.reminderSpec(mode.getSelectedItemPosition(), times.getText().toString(),
                                hours.getText().toString(), from.getText().toString(), to.getText().toString());
                        if (mode.getSelectedItemPosition() > 0 && h.remind.isEmpty()) {
                            Toast.makeText(a, "Reminder times not understood, use e.g. 08:00", Toast.LENGTH_LONG).show();
                        }
                        st.saveHabit(h);
                        if (!h.remind.isEmpty()) a.ensureNotifications();
                        changed();
                    }
                })
                .setNegativeButton("Cancel", null);
        if (h.id != 0) {
            b.setNeutralButton("More…", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { actions(h); }
            });
        }
        b.show();
        if (h.id == 0 && h.name.isEmpty()) Util.showKeyboard(name);
    }

    private TextView text(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Util.color(a, R.color.muted));
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    private TextView dayChip(String letter, boolean on) {
        TextView t = new TextView(a);
        t.setText(letter);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        t.setLayoutParams(lp);
        styleDay(t, on);
        return t;
    }

    private void styleDay(TextView t, boolean on) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (on) bg.setColor(Util.color(a, R.color.primary));
        else bg.setStroke(dp(1), Util.color(a, R.color.muted));
        t.setBackground(bg);
        t.setTextColor(on ? 0xFFFFFFFF : Util.color(a, R.color.muted));
    }
}
