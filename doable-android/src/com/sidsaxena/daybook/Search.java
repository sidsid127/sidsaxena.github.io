package com.sidsaxena.daybook;

import android.app.Dialog;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searches every module at once; tapping a result opens it in its module. */
final class Search {

    static final class Hit {
        final String module, icon, title, sub;
        final long id;

        Hit(String module, long id, String icon, String title, String sub) {
            this.module = module;
            this.id = id;
            this.icon = icon;
            this.title = title;
            this.sub = sub;
        }
    }

    private static final int MAX = 80;

    private final MainActivity a;
    private final Dialog dialog;
    private final LinearLayout results;
    private final Handler handler = new Handler();
    private String[] words = new String[0];

    private Search(MainActivity activity) {
        a = activity;
        dialog = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Util.color(a, R.color.bg));

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Util.color(a, R.color.card));
        bar.setElevation(Util.dp(a, 4));
        bar.setPadding(Util.dp(a, 4), Util.dp(a, 8), Util.dp(a, 12), Util.dp(a, 8));
        TextView back = new TextView(a);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(Util.color(a, R.color.text));
        back.setPadding(Util.dp(a, 12), 0, Util.dp(a, 12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); }
        });
        bar.addView(back);
        final EditText field = new EditText(a);
        field.setHint("Search everything");
        field.setSingleLine(true);
        field.setBackgroundResource(R.drawable.input_bg);
        field.setPadding(Util.dp(a, 16), Util.dp(a, 10), Util.dp(a, 16), Util.dp(a, 10));
        field.setTextColor(Util.color(a, R.color.text));
        field.setHintTextColor(Util.color(a, R.color.muted));
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        ScrollView sv = new ScrollView(a);
        results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(Util.dp(a, 8), Util.dp(a, 8), Util.dp(a, 8), Util.dp(a, 24));
        sv.addView(results);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final Runnable run = new Runnable() {
            public void run() { search(field.getText().toString()); }
        };
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            public void onTextChanged(CharSequence s, int st, int b, int c) {}

            public void afterTextChanged(Editable e) {
                handler.removeCallbacks(run);
                handler.postDelayed(run, 200);
            }
        });
        dialog.setContentView(root);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        hint("Tasks, notes, diary, trips & expenses, groceries, habits, dates, checklists, wallet and the activity log.\n\n"
                + "Credentials are searched inside Credentials, after unlocking.");
        field.requestFocus();
    }

    static void open(MainActivity a) {
        new Search(a).dialog.show();
    }

    private void hint(String text) {
        results.removeAllViews();
        TextView t = new TextView(a);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Util.color(a, R.color.muted));
        t.setPadding(Util.dp(a, 24), Util.dp(a, 40), Util.dp(a, 24), 0);
        results.addView(t);
    }

    private void search(String q) {
        q = q.trim().toLowerCase(Locale.getDefault());
        if (q.length() < 2) {
            hint("Type at least 2 letters.");
            return;
        }
        words = q.split("\\s+");
        List<Hit> hits = find();
        if (hits.isEmpty()) {
            hint("Nothing found.");
            return;
        }
        results.removeAllViews();
        String module = null;
        for (final Hit h : hits) {
            if (!h.module.equals(module)) {
                module = h.module;
                TextView head = new TextView(a);
                head.setText(a.moduleName(h.module).toUpperCase(Locale.getDefault()));
                head.setTextSize(12);
                head.setTextColor(Util.color(a, R.color.muted));
                head.setPadding(Util.dp(a, 8), Util.dp(a, 14), 0, Util.dp(a, 4));
                results.addView(head);
            }
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.card_bg);
            row.setPadding(Util.dp(a, 12), Util.dp(a, 10), Util.dp(a, 12), Util.dp(a, 10));
            TextView icon = new TextView(a);
            icon.setText(h.icon);
            icon.setTextSize(18);
            row.addView(icon, new LinearLayout.LayoutParams(Util.dp(a, 34), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(a);
            t.setText(h.title);
            t.setTextSize(16);
            t.setMaxLines(2);
            t.setTextColor(Util.color(a, R.color.text));
            texts.addView(t);
            if (!h.sub.isEmpty()) {
                TextView s = new TextView(a);
                s.setText(h.sub);
                s.setTextSize(13);
                s.setMaxLines(2);
                s.setTextColor(Util.color(a, R.color.muted));
                texts.addView(s);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Util.dp(a, 4);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Util.hideKeyboard(v);
                    dialog.dismiss();
                    a.go(h.module, h.id, null);
                }
            });
            results.addView(row, lp);
        }
    }

    private boolean match(String... texts) {
        StringBuilder sb = new StringBuilder();
        for (String t : texts) if (t != null) sb.append(t).append('\n');
        String all = sb.toString().toLowerCase(Locale.getDefault());
        for (String w : words) if (!all.contains(w)) return false;
        return true;
    }

    /** A short excerpt around the first word found. */
    private String snippet(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').trim();
        int at = flat.toLowerCase(Locale.getDefault()).indexOf(words[0]);
        if (at < 0) return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
        int from = Math.max(0, at - 30);
        int to = Math.min(flat.length(), at + 60);
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }

    private List<Hit> find() {
        Db db = a.db;
        Store st = a.st;
        List<Hit> out = new ArrayList<Hit>();
        if (a.isModuleVisible("tasks")) {
            for (Db.Task t : db.tasks()) {
                if (match(t.title, t.notes)) out.add(new Hit("tasks", t.id, t.done ? "✓" : "○", t.title,
                        (t.done ? "Done" : t.due != 0 ? Util.dueLabel(t.due) : "") + (t.notes.isEmpty() ? "" : " · " + snippet(t.notes))));
            }
        }
        if (a.isModuleVisible("notes")) {
            for (Db.Note n : db.notes()) {
                if (n.deleted != 0) continue;
                String title = n.body.split("\n", 2)[0];
                if (n.locked ? match(title) : match(n.body)) {
                    out.add(new Hit("notes", n.id, n.locked ? "🔒" : "📝", title.isEmpty() ? "Untitled" : title,
                            n.locked ? "Locked note" : snippet(n.body.length() > title.length() ? n.body.substring(title.length()) : "")));
                }
            }
        }
        if (a.isModuleVisible("diary")) {
            for (Db.Diary d : db.diary()) {
                if (match(d.text, d.highs, d.lows, d.place, d.weather)) {
                    out.add(new Hit("diary", d.day, d.mood > 0 ? DiaryScreen.MOODS[d.mood] : "📖", Util.dayLabel(d.day),
                            snippet(d.text + " " + d.highs + " " + d.lows + " " + d.place)));
                }
            }
        }
        if (a.isModuleVisible("trips")) {
            for (Db.Trip t : db.trips()) {
                if (match(t.name, t.destination)) out.add(new Hit("trips", t.id, t.kind == Db.BUSINESS ? "💼" : "🧳", t.name, t.destination));
                for (Db.Expense e : db.expenses(t.id)) {
                    if (match(e.merchant, e.note, e.category, e.attendees)) {
                        out.add(new Hit("trips", t.id, "🧾", (e.merchant.isEmpty() ? e.category : e.merchant) + " · "
                                + TripsScreen.money(e.cents, e.currency), t.name + " · " + Util.shortDate(e.day)));
                    }
                }
                for (String kind : new String[]{"booking", "place", "journal"}) {
                    for (Db.TripItem i : db.tripItems(t.id, kind)) {
                        if (match(i.title, i.body)) out.add(new Hit("trips", t.id, "📌", i.title.isEmpty() ? snippet(i.body) : i.title, t.name));
                    }
                }
            }
        }
        if (a.isModuleVisible("grocery")) {
            for (Db.Item it : db.items()) {
                if (match(it.name)) out.add(new Hit("grocery", 0, "🛒", (it.qty.isEmpty() ? "" : it.qty + " ") + it.name, "On the shopping list"));
            }
        }
        if (a.isModuleVisible("habits")) {
            for (Store.Habit h : st.habits(false)) if (match(h.name)) out.add(new Hit("habits", h.id, h.emoji.isEmpty() ? "✔" : h.emoji, h.name, ""));
        }
        if (a.isModuleVisible("dates")) {
            long today = Util.startOfDay(System.currentTimeMillis());
            for (Store.DateItem d : st.dates()) {
                if (!d.fromWallet && match(d.title, d.kind, d.notes)) {
                    out.add(new Hit("dates", d.id, DatesScreen.emoji(d), d.title, DatesScreen.when(d, Store.nextOccurrence(d, today))));
                }
            }
        }
        if (a.isModuleVisible("checklists")) {
            for (boolean tpl : new boolean[]{false, true}) {
                for (Store.Checklist l : st.checklists(tpl)) {
                    boolean found = match(l.title);
                    String sub = tpl ? "Template" : l.done + " of " + l.total + " done";
                    if (!found) {
                        for (Store.CheckItem i : st.checkItems(l.id)) {
                            if (match(i.text)) {
                                found = true;
                                sub = (i.done ? "☑ " : "☐ ") + i.text;
                                break;
                            }
                        }
                    }
                    if (found) out.add(new Hit("checklists", l.id, "☑", l.title, sub));
                }
            }
        }
        if (a.isModuleVisible("wallet")) {
            for (Store.WalletItem w : st.wallet()) if (match(w.title, w.type)) out.add(new Hit("wallet", w.id, WalletScreen.emoji(w.type), w.title, w.type));
        }
        if (a.isModuleVisible("log")) {
            for (Db.Entry e : db.entries()) if (match(e.text)) out.add(new Hit("log", 0, "🕘", snippet(e.text), Util.fullTimestamp(a, e.ts)));
        }
        return out.size() > MAX ? out.subList(0, MAX) : out;
    }
}
