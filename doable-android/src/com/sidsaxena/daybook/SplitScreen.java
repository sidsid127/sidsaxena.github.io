package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared expenses, like the web app's m-split.js: who paid what in a household (or with a person you share with),
 * balances, and the fewest payments that settle up. Rows live in the space, so they're end-to-end encrypted and on every
 * member's devices.
 */
class SplitScreen extends Page {
    private static final String[] CATEGORIES = {"🛒 Groceries", "🏠 Rent", "💡 Utilities", "🍽 Eating out", "🚗 Transport", "✈️ Travel",
            "🎉 Fun", "🧸 Kids", "🩺 Health", "📦 Other"};
    private String sel = "";

    static final class Expense {
        long id;
        String space = "", title = "", currency = "EUR", paidBy = "", shares = "", category = "", note = "", kind = "expense", toUser = "";
        long cents, day, created;

        SplitMath.Row math() {
            SplitMath.Row r = new SplitMath.Row();
            r.paidBy = paidBy;
            r.toUser = toUser;
            r.currency = currency;
            r.kind = kind;
            r.shares = shares;
            r.cents = cents;
            return r;
        }
    }

    SplitScreen(MainActivity a) {
        super(a);
        setFab(new Runnable() {
            public void run() {
                if (current() != null) edit(new Expense());
            }
        });
    }

    @Override
    String title() {
        return a.getString(R.string.module_split);
    }

    // ---------------------------------------------------------------- data

    private SQLiteDatabase db() {
        return Db.get(a).getWritableDatabase();
    }

    List<Expense> rows(String space) {
        List<Expense> out = new ArrayList<Expense>();
        Cursor c = db().rawQuery("SELECT _id, space, title, cents, currency, paid_by, shares, day, category, note, kind, to_user, created"
                + " FROM shared_expenses WHERE space = ? ORDER BY day DESC, created DESC", new String[]{space});
        try {
            while (c.moveToNext()) {
                Expense e = new Expense();
                e.id = c.getLong(0);
                e.space = c.getString(1);
                e.title = c.getString(2);
                e.cents = c.getLong(3);
                e.currency = c.getString(4);
                e.paidBy = c.getString(5);
                e.shares = c.getString(6);
                e.day = c.getLong(7);
                e.category = c.getString(8);
                e.note = c.getString(9);
                e.kind = c.getString(10);
                e.toUser = c.getString(11);
                e.created = c.getLong(12);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void save(Expense e) {
        ContentValues v = new ContentValues();
        v.put("space", e.space);
        v.put("title", e.title);
        v.put("cents", e.cents);
        v.put("currency", e.currency);
        v.put("paid_by", e.paidBy);
        v.put("shares", e.shares);
        v.put("day", e.day);
        v.put("category", e.category);
        v.put("note", e.note);
        v.put("kind", e.kind);
        v.put("to_user", e.toUser);
        if (e.id == 0) {
            v.put("created", e.created = System.currentTimeMillis());
            e.id = db().insert("shared_expenses", null, v);
        } else {
            db().update("shared_expenses", v, "_id = ?", new String[]{String.valueOf(e.id)});
        }
        a.syncSpacesSoon();
    }

    void delete(Expense e) {
        db().delete("shared_expenses", "_id = ?", new String[]{String.valueOf(e.id)});
        a.syncSpacesSoon();
    }

    static Map<String, Map<String, Long>> balances(List<Expense> list) {
        List<SplitMath.Row> m = new ArrayList<SplitMath.Row>();
        for (Expense e : list) m.add(e.math());
        return SplitMath.balances(m);
    }

    // ---------------------------------------------------------------- page

    private List<JSONObject> spaces() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject s : Spaces.list(a)) {
            if (("household".equals(s.optString("kind")) && Spaces.shares(s, "split")) || "share".equals(s.optString("kind"))) out.add(s);
        }
        return out;
    }

    private JSONObject current() {
        return Spaces.get(a, sel);
    }

    private String me() {
        return Account.user(a).optString("id");
    }

    private String nameIn(String userId) {
        if (userId.equals(me())) return "You";
        for (JSONObject m : Spaces.members(a, sel)) {
            if (userId.equals(m.optString("user_id"))) {
                String n = m.optString("name");
                return (n.isEmpty() ? m.optString("email") : n).split(" ")[0];
            }
        }
        return "Someone";
    }

    private static String money(long cents, String currency) {
        return TripsScreen.money(cents, currency);
    }

    private String currency() {
        return a.prefs.getString("currency", "EUR");
    }

    private String spaceLabel(JSONObject sp) {
        if ("household".equals(sp.optString("kind"))) return "🏠 " + sp.optString("name");
        StringBuilder s = new StringBuilder();
        for (JSONObject m : Spaces.members(a, sp.optString("id"))) {
            if (m.optString("user_id").equals(me())) continue;
            if (s.length() > 0) s.append(", ");
            String n = m.optString("name");
            s.append((n.isEmpty() ? m.optString("email") : n).split(" ")[0]);
        }
        return "👥 " + (s.length() == 0 ? "Shared" : s.toString());
    }

    @Override
    void render() {
        body.removeAllViews();
        List<JSONObject> list = spaces();
        if (!Account.signedIn(a) || list.isEmpty()) {
            fab.setVisibility(View.GONE);
            empty("Split costs with your household: who paid what, who owes whom, and the fewest payments to settle up.\n\n"
                    + (Spaces.households(a).isEmpty() ? "Create a household first." : "Turn on \"Shared expenses\" for your household (Household & sharing → your household)."));
            LinearLayout c = card();
            link(c, "Household & sharing", new Runnable() {
                public void run() { SpacesUi.show(a, null); }
            });
            return;
        }
        fab.setVisibility(View.VISIBLE);
        boolean found = false;
        for (JSONObject s : list) found |= s.optString("id").equals(sel);
        if (!found) sel = list.get(0).optString("id");
        if (list.size() > 1) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(a);
            for (final JSONObject s : list) {
                TextView ch = Util.chip(a, spaceLabel(s), Util.color(a, R.color.primary), s.optString("id").equals(sel));
                ch.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        sel = s.optString("id");
                        render();
                    }
                });
                chips.addView(ch);
            }
            hs.addView(chips);
            body.addView(hs);
        }
        if (!Spaces.ready(sel)) {
            TextView t = AccountUi.text(a, "⏳ Still getting the key for this household on this phone…", 13, R.color.muted, false);
            t.setPadding(dp(6), dp(6), dp(6), dp(4));
            body.addView(t);
        }

        final List<Expense> all = rows(sel);
        final Map<String, Map<String, Long>> bal = balances(all);
        LinearLayout sum = card();
        sum.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (bal.isEmpty()) sum.addView(AccountUi.text(a, "All settled", 22, R.color.text, true));
        for (Map.Entry<String, Map<String, Long>> cur : bal.entrySet()) {
            Long mineObj = cur.getValue().get(me());
            long mine = mineObj == null ? 0 : mineObj;
            TextView big = AccountUi.text(a, mine > 0 ? "You get back " + money(mine, cur.getKey()) : mine < 0 ? "You owe " + money(-mine, cur.getKey())
                    : "You're settled" + (bal.size() > 1 ? " (" + cur.getKey() + ")" : ""), 22, mine < 0 ? R.color.overdue : R.color.text, true);
            big.setPadding(0, dp(4), 0, dp(6));
            sum.addView(big);
            List<Map.Entry<String, Long>> others = new ArrayList<Map.Entry<String, Long>>(cur.getValue().entrySet());
            java.util.Collections.sort(others, new java.util.Comparator<Map.Entry<String, Long>>() {
                public int compare(Map.Entry<String, Long> x, Map.Entry<String, Long> y) { return Long.compare(y.getValue(), x.getValue()); }
            });
            for (Map.Entry<String, Long> e : others) {
                if (e.getKey().equals(me())) continue;
                sum.addView(AccountUi.text(a, nameIn(e.getKey()) + (e.getValue() > 0 ? " gets back " : " owes ") + money(Math.abs(e.getValue()), cur.getKey()),
                        15, R.color.muted, false));
            }
        }
        LinearLayout buttons = new LinearLayout(a);
        buttons.setPadding(0, dp(10), 0, 0);
        Button add = new Button(a);
        add.setText("+ Add expense");
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { edit(new Expense()); }
        });
        buttons.addView(add);
        Button settle = new Button(a);
        settle.setText("Settle up");
        settle.setEnabled(!bal.isEmpty());
        settle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { settle(bal); }
        });
        buttons.addView(settle);
        sum.addView(buttons);

        header("History");
        LinearLayout hist = card();
        if (all.isEmpty()) row(hist, "🧾", "No expenses yet", "Add what you paid for the household — everyone sees it and the balances update.", null, null, null);
        int members = Spaces.members(a, sel).size();
        for (final Expense e : all) {
            TextView amt = AccountUi.text(a, money(e.cents, e.currency), 15, R.color.text, true);
            Runnable open = new Runnable() {
                public void run() { edit(e); }
            };
            if ("settle".equals(e.kind)) {
                row(hist, "🤝", nameIn(e.paidBy) + " paid " + nameIn(e.toUser).replaceFirst("^You$", "you"),
                        dayText(e.day) + (e.note.isEmpty() ? "" : " · " + e.note), amt, open, null);
                continue;
            }
            int n = SplitMath.shares(e.shares).size();
            Expense part = new Expense();
            part.cents = e.cents;
            part.currency = e.currency;
            part.shares = e.shares;
            part.paidBy = "__";
            List<Expense> one = new ArrayList<Expense>();
            one.add(part);
            Map<String, Long> b = balances(one).get(e.currency == null || e.currency.isEmpty() ? "EUR" : e.currency);
            Long myPart = b == null ? null : b.get(me());
            long mine = myPart == null ? 0 : -myPart;
            row(hist, e.category.isEmpty() ? "📦" : e.category.split(" ")[0], e.title.isEmpty() ? "Expense" : e.title,
                    nameIn(e.paidBy) + " paid · " + (n == members ? "split equally" : "split between " + n) + " · " + dayText(e.day)
                            + (mine != 0 ? " · your part " + money(mine, e.currency) : ""), amt, open, null);
        }
    }

    private static String dayText(long day) {
        if (day == 0) return "";
        int d = Util.daysFromToday(day);
        if (d == 0) return "Today";
        if (d == -1) return "Yesterday";
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(day));
    }

    // ---------------------------------------------------------------- editing

    private Button dayButton(final long[] day) {
        final Button b = new Button(a, null, android.R.attr.borderlessButtonStyle);
        b.setText(dayText(day[0]));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(day[0]);
                new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(DatePicker p, int y, int m, int d) {
                        Calendar x = Calendar.getInstance();
                        x.set(y, m, d, 0, 0, 0);
                        day[0] = Util.startOfDay(x.getTimeInMillis());
                        b.setText(dayText(day[0]));
                    }
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        return b;
    }

    private EditText amountField(long cents) {
        EditText f = new EditText(a);
        f.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        f.setHint("0.00");
        if (cents > 0) f.setText(String.format(Locale.US, "%.2f", cents / 100.0));
        return f;
    }

    private EditText currencyField(String cur) {
        EditText f = new EditText(a);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        f.setText(cur);
        return f;
    }

    private Spinner people(List<String> ids, String selected) {
        List<String> names = new ArrayList<String>();
        for (String id : ids) names.add(id.equals(me()) ? "Me" : nameFull(id));
        Spinner s = new Spinner(a);
        s.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, names));
        s.setSelection(Math.max(0, ids.indexOf(selected)));
        return s;
    }

    private String nameFull(String id) {
        for (JSONObject m : Spaces.members(a, sel)) {
            if (id.equals(m.optString("user_id"))) return m.optString("name").isEmpty() ? m.optString("email") : m.optString("name");
        }
        return "Someone";
    }

    private List<String> memberIds() {
        List<String> ids = new ArrayList<String>();
        for (JSONObject m : Spaces.members(a, sel)) ids.add(m.optString("user_id"));
        return ids;
    }

    private void showDialog(String title, LinearLayout box, String ok, final Runnable save, final Runnable delete) {
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(title).setView(scroll).setPositiveButton(ok, null).setNegativeButton("Cancel", null);
        if (delete != null) b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { delete.run(); }
        });
        final AlertDialog d = b.create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface x) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        save.run();
                        if (closeOk) d.dismiss();
                    }
                });
            }
        });
        d.show();
    }

    private boolean closeOk;

    void edit(final Expense e) {
        if (current() == null) return;
        if ("settle".equals(e.kind) && e.id != 0) {
            editPayment(e);
            return;
        }
        final boolean isNew = e.id == 0;
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "What"));
        final EditText title = Util.field(a, e.title);
        title.setHint("e.g. Weekly shop, Electricity");
        box.addView(title);
        box.addView(Util.label(a, "Amount"));
        LinearLayout amountRow = new LinearLayout(a);
        final EditText amount = amountField(e.cents);
        final EditText cur = currencyField(e.currency.isEmpty() || isNew ? currency() : e.currency);
        amountRow.addView(amount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        amountRow.addView(cur, new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(amountRow);
        final List<String> ids = memberIds();
        box.addView(Util.label(a, "Paid by"));
        final Spinner paidBy = people(ids, e.paidBy.isEmpty() ? me() : e.paidBy);
        box.addView(paidBy);
        box.addView(Util.label(a, "Split equally between"));
        TreeMap<String, Double> sh = SplitMath.shares(e.shares);
        final List<CheckBox> boxes = new ArrayList<CheckBox>();
        for (String id : ids) {
            CheckBox cb = new CheckBox(a);
            cb.setText(id.equals(me()) ? "Me" : nameFull(id));
            cb.setChecked(isNew || sh.containsKey(id));
            boxes.add(cb);
            box.addView(cb);
        }
        box.addView(Util.label(a, "Category"));
        final Spinner category = new Spinner(a);
        category.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, CATEGORIES));
        category.setSelection(Math.max(0, java.util.Arrays.asList(CATEGORIES).indexOf(e.category)));
        box.addView(category);
        box.addView(Util.label(a, "Date"));
        final long[] day = {e.day == 0 ? Util.startOfDay(System.currentTimeMillis()) : e.day};
        box.addView(dayButton(day));
        box.addView(Util.label(a, "Note"));
        final EditText note = Util.field(a, e.note);
        note.setHint("Optional");
        box.addView(note);
        showDialog(isNew ? "Add expense" : "Edit expense", box, "Save", new Runnable() {
            public void run() {
                closeOk = false;
                long cents = TripsScreen.parseAmount(amount.getText().toString());
                JSONObject shares = new JSONObject();
                for (int i = 0; i < ids.size(); i++) {
                    if (boxes.get(i).isChecked()) {
                        try {
                            shares.put(ids.get(i), 1);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (cents <= 0) {
                    Toast.makeText(a, "Enter the amount", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (shares.length() == 0) {
                    Toast.makeText(a, "Choose who shares it", Toast.LENGTH_SHORT).show();
                    return;
                }
                String catName = CATEGORIES[category.getSelectedItemPosition()];
                e.space = sel;
                e.title = title.getText().toString().trim().isEmpty() ? catName.replaceFirst("^\\S+ ", "") : title.getText().toString().trim();
                e.cents = cents;
                e.currency = cleanCurrency(cur.getText().toString());
                e.paidBy = ids.get(paidBy.getSelectedItemPosition());
                e.shares = shares.toString();
                e.category = catName;
                e.day = day[0];
                e.note = note.getText().toString().trim();
                e.kind = "expense";
                e.toUser = "";
                save(e);
                closeOk = true;
                render();
            }
        }, isNew ? null : new Runnable() {
            public void run() {
                Util.confirm(a, "Delete this expense?", e.title, "Delete", new Runnable() {
                    public void run() {
                        delete(e);
                        render();
                    }
                });
            }
        });
    }

    private String cleanCurrency(String s) {
        s = s.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) s = currency();
        return s.length() > 3 ? s.substring(0, 3) : s;
    }

    /** Settle up: the fewest payments that clear the balances; tap one to record it. */
    private void settle(Map<String, Map<String, Long>> bal) {
        Menus.Builder m = Menus.of(a, "Settle up");
        for (final Map.Entry<String, Map<String, Long>> cur : bal.entrySet()) {
            for (final SplitMath.Payment p : SplitMath.settleUp(cur.getValue())) {
                m.add("🤝", nameIn(p.from) + " → " + nameIn(p.to), money(p.cents, cur.getKey()) + " · tap when it's paid", new Runnable() {
                    public void run() {
                        Expense e = new Expense();
                        e.kind = "settle";
                        e.paidBy = p.from;
                        e.toUser = p.to;
                        e.cents = p.cents;
                        e.currency = cur.getKey();
                        editPayment(e);
                    }
                });
            }
        }
        m.divider().add("✍️", "Record another payment", new Runnable() {
            public void run() {
                Expense e = new Expense();
                e.kind = "settle";
                editPayment(e);
            }
        }).show();
    }

    void editPayment(final Expense e) {
        final boolean isNew = e.id == 0;
        final List<String> ids = memberIds();
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Who paid"));
        final Spinner from = people(ids, e.paidBy.isEmpty() ? me() : e.paidBy);
        box.addView(from);
        box.addView(Util.label(a, "To"));
        String other = "";
        for (String id : ids) if (!id.equals(me())) {
            other = id;
            break;
        }
        final Spinner to = people(ids, e.toUser.isEmpty() ? other : e.toUser);
        box.addView(to);
        box.addView(Util.label(a, "Amount"));
        LinearLayout amountRow = new LinearLayout(a);
        final EditText amount = amountField(e.cents);
        final EditText cur = currencyField(e.currency.isEmpty() ? currency() : e.currency);
        amountRow.addView(amount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        amountRow.addView(cur, new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(amountRow);
        box.addView(Util.label(a, "Date"));
        final long[] day = {e.day == 0 ? Util.startOfDay(System.currentTimeMillis()) : e.day};
        box.addView(dayButton(day));
        box.addView(Util.label(a, "Note"));
        final EditText note = Util.field(a, e.note);
        note.setHint("e.g. bank transfer, cash");
        box.addView(note);
        showDialog(isNew ? "Record a payment" : "Edit payment", box, isNew ? "Record payment" : "Save", new Runnable() {
            public void run() {
                closeOk = false;
                long cents = TripsScreen.parseAmount(amount.getText().toString());
                String f = ids.get(from.getSelectedItemPosition()), t = ids.get(to.getSelectedItemPosition());
                if (f.equals(t)) {
                    Toast.makeText(a, "Choose two different people", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (cents <= 0) {
                    Toast.makeText(a, "Enter the amount", Toast.LENGTH_SHORT).show();
                    return;
                }
                e.space = sel;
                e.title = "Payment";
                e.cents = cents;
                e.currency = cleanCurrency(cur.getText().toString());
                e.paidBy = f;
                e.toUser = t;
                e.day = day[0];
                e.note = note.getText().toString().trim();
                e.kind = "settle";
                e.shares = "";
                e.category = "";
                save(e);
                String otherOne = !f.equals(me()) ? f : t;
                if (isNew && !otherOne.equals(me())) Spaces.tell(a.getApplicationContext(), sel, otherOne, "settle");
                Toast.makeText(a, "Payment recorded", Toast.LENGTH_SHORT).show();
                closeOk = true;
                render();
            }
        }, isNew ? null : new Runnable() {
            public void run() {
                Util.confirm(a, "Delete this payment?", null, "Delete", new Runnable() {
                    public void run() {
                        delete(e);
                        render();
                    }
                });
            }
        });
    }
}
