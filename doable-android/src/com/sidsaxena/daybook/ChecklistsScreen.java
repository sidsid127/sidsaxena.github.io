package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable checklists: packing lists, "before leaving home", onboarding steps… Templates are copied into
 * a fresh list each time; lists can be reset and reused.
 */
class ChecklistsScreen extends Page {

    private static final int MENU_RESET = 190, MENU_RENAME = 191, MENU_TEMPLATE = 192, MENU_DELETE = 193, MENU_SHARE = 194;

    private boolean templates;
    private Store.Checklist open;   // null = overview

    ChecklistsScreen(MainActivity a) {
        super(a);
        setFab(new Runnable() {
            public void run() { newList(); }
        });
    }

    @Override
    String title() {
        return open != null ? open.title : a.getString(R.string.module_checklists);
    }

    @Override
    boolean homeIsBack() {
        return open != null;
    }

    @Override
    boolean onBack() {
        if (open == null) return false;
        close();
        return true;
    }

    @Override
    void open(long id) {
        for (boolean t : new boolean[]{false, true}) {
            for (Store.Checklist l : st.checklists(t)) {
                if (l.id == id) {
                    templates = t;
                    show(l);
                    return;
                }
            }
        }
    }

    private void show(Store.Checklist l) {
        open = l;
        a.screenChanged();
        render();
        scroll.scrollTo(0, 0);
    }

    private void close() {
        open = null;
        Util.hideKeyboard(body);
        a.screenChanged();
        render();
    }

    @Override
    void render() {
        body.removeAllViews();
        if (open != null) {
            renderList();
            return;
        }
        fab.setVisibility(View.VISIBLE);
        LinearLayout tabs = new LinearLayout(a);
        tabs.setPadding(0, dp(4), 0, dp(4));
        for (final boolean t : new boolean[]{false, true}) {
            TextView chip = Util.chip(a, t ? "Templates" : "My checklists", Util.color(a, R.color.primary), templates == t);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    templates = t;
                    render();
                }
            });
            tabs.addView(chip);
        }
        body.addView(tabs);
        List<Store.Checklist> lists = st.checklists(templates);
        if (lists.isEmpty()) {
            empty(templates ? "No templates yet.\nTap + to make one, e.g. for a business trip."
                    : "No checklists yet.\nTap + and start from a template (packing lists, before leaving home…).");
            return;
        }
        LinearLayout card = card();
        for (final Store.Checklist l : lists) {
            String sub = templates ? l.total + " item" + (l.total == 1 ? "" : "s")
                    : l.done + " of " + l.total + " done" + (l.trip != 0 ? " · trip packing list" : "");
            row(card, templates ? "📋" : l.total > 0 && l.done == l.total ? "✅" : "☑", l.title, sub, null, new Runnable() {
                public void run() { show(l); }
            }, new Runnable() {
                public void run() { listActions(l); }
            });
        }
    }

    private void renderList() {
        fab.setVisibility(View.GONE);
        final List<Store.CheckItem> items = st.checkItems(open.id);
        int done = 0;
        for (Store.CheckItem i : items) if (i.done) done++;
        TextView progress = header(templates ? "Template · " + items.size() + " items"
                : done + " of " + items.size() + " done" + (items.size() > 0 && done == items.size() ? " 🎉" : ""));
        progress.setGravity(Gravity.START);

        LinearLayout card = card();
        for (final Store.CheckItem i : items) {
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(8), 0, dp(8), 0);
            final CheckBox cb = new CheckBox(a);
            cb.setChecked(i.done);
            cb.setText(i.text);
            cb.setTextSize(16);
            cb.setTextColor(Util.color(a, i.done ? R.color.muted : R.color.text));
            if (i.done) cb.setPaintFlags(cb.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            cb.setMinHeight(dp(48));
            cb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    st.setCheckItem(i.id, i.text, cb.isChecked());
                    render();
                }
            });
            cb.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    itemActions(i);
                    return true;
                }
            });
            r.addView(cb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            card.addView(r);
        }

        // Add row
        LinearLayout add = new LinearLayout(a);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(4), dp(8), dp(4), 0);
        final EditText field = new EditText(a);
        field.setHint("Add an item…");
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setBackgroundResource(R.drawable.input_bg);
        field.setPadding(dp(16), dp(10), dp(16), dp(10));
        field.setTextColor(Util.color(a, R.color.text));
        field.setHintTextColor(Util.color(a, R.color.muted));
        add.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(a);
        b.setText(R.string.add);
        add.addView(b);
        body.addView(add);
        final Runnable addIt = new Runnable() {
            public void run() {
                String t = field.getText().toString().trim();
                if (t.isEmpty()) return;
                // One item per line, so a pasted list works too.
                for (String line : t.split("\n")) {
                    line = line.replaceFirst("^[-•*☐☑\\s]+", "").trim();
                    if (!line.isEmpty()) st.addCheckItem(open.id, line);
                }
                render();
                EditText again = (EditText) ((LinearLayout) body.getChildAt(body.getChildCount() - 1)).getChildAt(0);
                again.requestFocus();
                scroll.post(new Runnable() {
                    public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        };
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addIt.run(); }
        });
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                addIt.run();
                return true;
            }
        });
    }

    private void itemActions(final Store.CheckItem i) {
        new AlertDialog.Builder(a)
                .setItems(new String[]{"Edit", "Delete"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            Util.prompt(a, "Edit item", i.text, "", "Save", new Util.TextEntered() {
                                public void onText(String text) {
                                    st.setCheckItem(i.id, text, i.done);
                                    render();
                                }
                            });
                        } else {
                            st.deleteCheckItem(i.id);
                            render();
                        }
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- menu

    @Override
    void onCreateMenu(Menu menu) {
        if (open == null) return;
        if (!templates) menu.add(0, MENU_RESET, 0, "Untick all (reuse)");
        menu.add(0, MENU_SHARE, 1, "Share as text");
        menu.add(0, MENU_RENAME, 2, "Rename");
        menu.add(0, MENU_TEMPLATE, 3, templates ? "Start a checklist from this" : "Save as template");
        menu.add(0, MENU_DELETE, 4, "Delete");
    }

    @Override
    boolean onMenu(int id) {
        if (open == null) return false;
        switch (id) {
            case MENU_RESET:
                st.resetChecklist(open.id);
                render();
                return true;
            case MENU_SHARE:
                StringBuilder sb = new StringBuilder(open.title).append('\n');
                for (Store.CheckItem i : st.checkItems(open.id)) sb.append(i.done ? "☑ " : "☐ ").append(i.text).append('\n');
                a.shareText(open.title, sb.toString());
                return true;
            case MENU_RENAME:
                Util.prompt(a, "Rename", open.title, "", "Save", new Util.TextEntered() {
                    public void onText(String text) {
                        st.renameChecklist(open.id, text);
                        open.title = text;
                        a.screenChanged();
                    }
                });
                return true;
            case MENU_TEMPLATE:
                if (templates) {
                    long nid = st.copyChecklist(open.id, open.title, false, 0);
                    templates = false;
                    open(nid);
                } else {
                    st.copyChecklist(open.id, open.title, true, 0);
                    st.resetChecklist(open.id);
                    android.widget.Toast.makeText(a, "Saved as a template", android.widget.Toast.LENGTH_SHORT).show();
                    render();
                }
                return true;
            case MENU_DELETE:
                listActionsDelete(open);
                return true;
        }
        return false;
    }

    private void listActions(final Store.Checklist l) {
        new AlertDialog.Builder(a)
                .setTitle(l.title)
                .setItems(new String[]{"Open", "Duplicate", "Delete"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) show(l);
                        else if (which == 1) {
                            st.copyChecklist(l.id, l.title + " (copy)", l.template, 0);
                            render();
                        } else listActionsDelete(l);
                    }
                })
                .show();
    }

    private void listActionsDelete(final Store.Checklist l) {
        Util.confirm(a, "Delete \"" + l.title + "\"?", null, "Delete", new Runnable() {
            public void run() {
                st.deleteChecklist(l.id);
                if (open != null && open.id == l.id) close();
                else render();
            }
        });
    }

    private void newList() {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Name"));
        final EditText name = Util.field(a, "");
        name.setHint(templates ? "e.g. Customer workshop" : "e.g. Weekend in Paris");
        box.addView(name);
        final List<Store.Checklist> tpl = st.checklists(true);
        final Spinner from = new Spinner(a);
        if (!templates && !tpl.isEmpty()) {
            box.addView(Util.label(a, "Start from template"));
            List<String> names = new ArrayList<String>();
            names.add("Empty list");
            for (Store.Checklist t : tpl) names.add(t.title);
            from.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, names));
            box.addView(from);
        }
        new AlertDialog.Builder(a)
                .setTitle(templates ? "New template" : "New checklist")
                .setView(box)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String n = name.getText().toString().trim();
                        int pick = from.getAdapter() == null ? 0 : from.getSelectedItemPosition();
                        if (n.isEmpty()) n = pick > 0 ? tpl.get(pick - 1).title : "Checklist";
                        long id = pick > 0 ? st.copyChecklist(tpl.get(pick - 1).id, n, false, 0) : st.addChecklist(n, templates, 0);
                        open(id);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        Util.showKeyboard(name);
    }
}
