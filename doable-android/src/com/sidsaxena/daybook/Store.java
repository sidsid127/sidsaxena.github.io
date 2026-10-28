package com.sidsaxena.daybook;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Storage for subtasks, grocery lists and prices, habits, wallet, important dates and checklists. */
final class Store {

    private final Db db;

    Store(Db db) {
        this.db = db;
    }

    private SQLiteDatabase w() { return db.getWritableDatabase(); }
    private SQLiteDatabase r() { return db.getReadableDatabase(); }
    private static String[] a(Object... v) {
        String[] s = new String[v.length];
        for (int i = 0; i < v.length; i++) s[i] = String.valueOf(v[i]);
        return s;
    }

    // ---------------------------------------------------------------- subtasks

    static final class Subtask {
        long id, task;
        String text = "";
        boolean done;
    }

    List<Subtask> subtasks(long task) {
        List<Subtask> out = new ArrayList<Subtask>();
        Cursor c = r().rawQuery("SELECT _id, task, text, done FROM subtasks WHERE task = ? ORDER BY position, _id", a(task));
        try {
            while (c.moveToNext()) {
                Subtask s = new Subtask();
                s.id = c.getLong(0);
                s.task = c.getLong(1);
                s.text = c.getString(2);
                s.done = c.getInt(3) != 0;
                out.add(s);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** {done, total} per task id, for the "2/5" badge. */
    Map<Long, int[]> subtaskCounts() {
        Map<Long, int[]> out = new HashMap<Long, int[]>();
        Cursor c = r().rawQuery("SELECT task, SUM(done), COUNT(*) FROM subtasks GROUP BY task", null);
        try {
            while (c.moveToNext()) out.put(c.getLong(0), new int[]{c.getInt(1), c.getInt(2)});
        } finally {
            c.close();
        }
        return out;
    }

    void replaceSubtasks(long task, List<Subtask> list) {
        w().delete("subtasks", "task = ?", a(task));
        for (int i = 0; i < list.size(); i++) {
            Subtask s = list.get(i);
            if (s.text.trim().isEmpty()) continue;
            ContentValues v = new ContentValues();
            v.put("task", task);
            v.put("text", s.text.trim());
            v.put("done", s.done ? 1 : 0);
            v.put("position", i);
            w().insert("subtasks", null, v);
        }
    }

    void setSubtaskDone(long id, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        w().update("subtasks", v, "_id = ?", a(id));
    }

    void deleteSubtasks(long task) {
        w().delete("subtasks", "task = ?", a(task));
    }

    // ---------------------------------------------------------------- grocery lists & prices

    static final class GList {
        long id;
        String name;
    }

    List<GList> groceryLists() {
        List<GList> out = new ArrayList<GList>();
        Cursor c = r().rawQuery("SELECT _id, name FROM grocery_lists ORDER BY _id", null);
        try {
            while (c.moveToNext()) {
                GList l = new GList();
                l.id = c.getLong(0);
                l.name = c.getString(1);
                out.add(l);
            }
        } finally {
            c.close();
        }
        return out;
    }

    long addGroceryList(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        return w().insert("grocery_lists", null, v);
    }

    void renameGroceryList(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        w().update("grocery_lists", v, "_id = ?", a(id));
    }

    /** Deletes a list and its items (history stays, moved to the first list). */
    void deleteGroceryList(long id) {
        if (id == 1) return;
        w().delete("grocery", "list = ?", a(id));
        ContentValues v = new ContentValues();
        v.put("list", 1);
        w().update("grocery_history", v, "list = ?", a(id));
        w().delete("grocery_lists", "_id = ?", a(id));
    }

    static final class Price {
        long id;
        String name, qty; // qty "1 kg" = price per kg; older entries: the quantity bought
        long shop, cents, day;
    }

    void addPrice(String name, long shop, long cents, String qty) {
        addPrice(name, shop, cents, qty, Util.startOfDay(System.currentTimeMillis()));
    }

    void addPrice(String name, long shop, long cents, String qty, long day) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("shop", shop);
        v.put("cents", cents);
        v.put("qty", qty);
        v.put("day", day);
        w().insert("prices", null, v);
    }

    void updatePrice(Price p) {
        ContentValues v = new ContentValues();
        v.put("name", p.name);
        v.put("shop", p.shop);
        v.put("cents", p.cents);
        v.put("qty", p.qty);
        v.put("day", p.day);
        w().update("prices", v, "_id = ?", a(p.id));
    }

    void deletePrice(long id) {
        w().delete("prices", "_id = ?", a(id));
    }

    /** Every product with a price: name → its prices, newest first. */
    java.util.TreeMap<String, List<Price>> allPrices() {
        java.util.TreeMap<String, List<Price>> out = new java.util.TreeMap<String, List<Price>>(String.CASE_INSENSITIVE_ORDER);
        Cursor c = r().rawQuery("SELECT _id, name, shop, cents, qty, day FROM prices ORDER BY day DESC, _id DESC", null);
        try {
            while (c.moveToNext()) {
                Price p = price(c);
                if (!out.containsKey(p.name)) out.put(p.name, new ArrayList<Price>());
                out.get(p.name).add(p);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static Price price(Cursor c) {
        Price p = new Price();
        p.id = c.getLong(0);
        p.name = c.getString(1);
        p.shop = c.getLong(2);
        p.cents = c.getLong(3);
        p.qty = c.getString(4);
        p.day = c.getLong(5);
        return p;
    }

    /** Price history of one product, newest first. */
    List<Price> prices(String name) {
        List<Price> out = new ArrayList<Price>();
        Cursor c = r().rawQuery("SELECT _id, name, shop, cents, qty, day FROM prices WHERE name = ? COLLATE NOCASE ORDER BY day DESC, _id DESC LIMIT 60", a(name));
        try {
            while (c.moveToNext()) out.add(price(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Latest price per shop for every product: name(lowercase) → shop → price. */
    Map<String, Map<Long, Price>> latestPrices() {
        Map<String, Map<Long, Price>> out = new HashMap<String, Map<Long, Price>>();
        Cursor c = r().rawQuery("SELECT _id, name, shop, cents, qty, day FROM prices ORDER BY day, _id", null);
        try {
            while (c.moveToNext()) {
                Price p = price(c);
                String key = p.name.toLowerCase(java.util.Locale.getDefault());
                if (!out.containsKey(key)) out.put(key, new HashMap<Long, Price>());
                out.get(key).put(p.shop, p);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Sum of prices entered today ("spent today"). */
    long spentToday() {
        Cursor c = r().rawQuery("SELECT SUM(cents) FROM prices WHERE day = ?", a(Util.startOfDay(System.currentTimeMillis())));
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    // ---------------------------------------------------------------- habits

    static final class Habit {
        long id;
        String name = "", emoji = "";
        boolean quit;
        int target = 1;
        int days = 127;
        String remind = "";   // "08:00,20:00" or "every:120:08:00-20:00"
        int color;
        boolean archived;
        long created;
    }

    List<Habit> habits(boolean archived) {
        List<Habit> out = new ArrayList<Habit>();
        Cursor c = r().rawQuery("SELECT _id, name, emoji, quit, target, days, remind, color, archived, created FROM habits"
                + " WHERE archived = ? ORDER BY _id", a(archived ? 1 : 0));
        try {
            while (c.moveToNext()) {
                Habit h = new Habit();
                h.id = c.getLong(0);
                h.name = c.getString(1);
                h.emoji = c.getString(2);
                h.quit = c.getInt(3) != 0;
                h.target = c.getInt(4);
                h.days = c.getInt(5);
                h.remind = c.getString(6);
                h.color = c.getInt(7);
                h.archived = c.getInt(8) != 0;
                h.created = c.getLong(9);
                out.add(h);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void saveHabit(Habit h) {
        ContentValues v = new ContentValues();
        v.put("name", h.name);
        v.put("emoji", h.emoji);
        v.put("quit", h.quit ? 1 : 0);
        v.put("target", h.target);
        v.put("days", h.days);
        v.put("remind", h.remind);
        v.put("color", h.color);
        v.put("archived", h.archived ? 1 : 0);
        if (h.id == 0) {
            v.put("created", h.created = Util.startOfDay(System.currentTimeMillis()));
            h.id = w().insert("habits", null, v);
        } else {
            w().update("habits", v, "_id = ?", a(h.id));
        }
    }

    void deleteHabit(long id) {
        w().delete("habit_log", "habit = ?", a(id));
        w().delete("habits", "_id = ?", a(id));
    }

    Map<Long, Integer> habitLog(long habit) {
        Map<Long, Integer> out = new HashMap<Long, Integer>();
        Cursor c = r().rawQuery("SELECT day, count FROM habit_log WHERE habit = ?", a(habit));
        try {
            while (c.moveToNext()) out.put(c.getLong(0), c.getInt(1));
        } finally {
            c.close();
        }
        return out;
    }

    int habitCount(long habit, long day) {
        Cursor c = r().rawQuery("SELECT count FROM habit_log WHERE habit = ? AND day = ?", a(habit, day));
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    void setHabitCount(long habit, long day, int count) {
        w().delete("habit_log", "habit = ? AND day = ?", a(habit, day));
        if (count <= 0) return;
        ContentValues v = new ContentValues();
        v.put("habit", habit);
        v.put("day", day);
        v.put("count", count);
        w().insert("habit_log", null, v);
    }

    // ---------------------------------------------------------------- wallet

    static final String[] WALLET_TYPES = {"Loyalty card", "ID card", "Passport", "Driving licence", "Health insurance card",
            "Insurance policy", "Membership", "Ticket / pass", "Vehicle", "Warranty", "Document", "Other"};

    static final class WalletItem {
        long id;
        String type = WALLET_TYPES[0], title = "", number = "", holder = "", barcode = "", notes = "";
        long expiry;  // local midnight, 0 = none
    }

    List<WalletItem> wallet() {
        List<WalletItem> out = new ArrayList<WalletItem>();
        Cursor c = r().rawQuery("SELECT _id, type, title, number, holder, expiry, barcode, notes FROM wallet ORDER BY type, title COLLATE NOCASE", null);
        try {
            while (c.moveToNext()) {
                WalletItem i = new WalletItem();
                i.id = c.getLong(0);
                i.type = c.getString(1);
                i.title = c.getString(2);
                i.number = c.getString(3);
                i.holder = c.getString(4);
                i.expiry = c.getLong(5);
                i.barcode = c.getString(6);
                i.notes = c.getString(7);
                out.add(i);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void saveWallet(WalletItem i) {
        ContentValues v = new ContentValues();
        v.put("type", i.type);
        v.put("title", i.title);
        v.put("number", i.number);
        v.put("holder", i.holder);
        v.put("expiry", i.expiry);
        v.put("barcode", i.barcode);
        v.put("notes", i.notes);
        if (i.id == 0) {
            v.put("created", System.currentTimeMillis());
            i.id = w().insert("wallet", null, v);
        } else {
            w().update("wallet", v, "_id = ?", a(i.id));
        }
    }

    List<String> deleteWallet(long id) {
        List<String> files = new ArrayList<String>();
        for (Db.Attachment at : db.attachments("wallet", id)) {
            files.add(at.file);
            db.deleteAttachment(at.id);
        }
        w().delete("wallet", "_id = ?", a(id));
        return files;
    }

    // ---------------------------------------------------------------- important dates

    static final String[] DATE_KINDS = {"Birthday", "Anniversary", "Name day", "Contract renewal", "Expiry", "Appointment", "Other"};

    static final class DateItem {
        long id;
        String title = "", kind = DATE_KINDS[0], notes = "";
        int month = 1, day = 1, year;   // year 0 = unknown (yearly)
        boolean yearly = true;
        int remindDays = 1;              // -1 = no reminder
        boolean fromWallet;              // generated from a wallet expiry (read-only)
        long walletId;
    }

    List<DateItem> dates() {
        List<DateItem> out = new ArrayList<DateItem>();
        Cursor c = r().rawQuery("SELECT _id, title, kind, month, day, year, yearly, remind_days, notes FROM dates", null);
        try {
            while (c.moveToNext()) {
                DateItem d = new DateItem();
                d.id = c.getLong(0);
                d.title = c.getString(1);
                d.kind = c.getString(2);
                d.month = c.getInt(3);
                d.day = c.getInt(4);
                d.year = c.getInt(5);
                d.yearly = c.getInt(6) != 0;
                d.remindDays = c.getInt(7);
                d.notes = c.getString(8);
                out.add(d);
            }
        } finally {
            c.close();
        }
        // Wallet expiries show up as dates too (reminder 30 days before).
        for (WalletItem wi : wallet()) {
            if (wi.expiry == 0) continue;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(wi.expiry);
            DateItem d = new DateItem();
            d.title = wi.title + " expires";
            d.kind = "Expiry";
            d.year = cal.get(Calendar.YEAR);
            d.month = cal.get(Calendar.MONTH) + 1;
            d.day = cal.get(Calendar.DAY_OF_MONTH);
            d.yearly = false;
            d.remindDays = 30;
            d.fromWallet = true;
            d.walletId = wi.id;
            out.add(d);
        }
        return out;
    }

    void saveDate(DateItem d) {
        ContentValues v = new ContentValues();
        v.put("title", d.title);
        v.put("kind", d.kind);
        v.put("month", d.month);
        v.put("day", d.day);
        v.put("year", d.year);
        v.put("yearly", d.yearly ? 1 : 0);
        v.put("remind_days", d.remindDays);
        v.put("notes", d.notes);
        if (d.id == 0) {
            v.put("created", System.currentTimeMillis());
            d.id = w().insert("dates", null, v);
        } else {
            w().update("dates", v, "_id = ?", a(d.id));
        }
    }

    void deleteDate(long id) {
        w().delete("dates", "_id = ?", a(id));
    }

    /** Next occurrence (local midnight) on or after today; for one-off dates the date itself (may be past). */
    static long nextOccurrence(DateItem d, long today) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(today);
        int year = d.yearly ? c.get(Calendar.YEAR) : (d.year > 0 ? d.year : c.get(Calendar.YEAR));
        long t = dayOf(year, d.month, d.day);
        if (d.yearly && t < today) t = dayOf(year + 1, d.month, d.day);
        return t;
    }

    static long dayOf(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, 1);
        c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        return c.getTimeInMillis();
    }

    // ---------------------------------------------------------------- checklists

    static final class Checklist {
        long id, trip;
        String title = "";
        boolean template;
        int done, total;
    }

    static final class CheckItem {
        long id, list;
        String text = "";
        boolean done;
    }

    List<Checklist> checklists(boolean templates) {
        List<Checklist> out = new ArrayList<Checklist>();
        Cursor c = r().rawQuery("SELECT l._id, l.title, l.trip, l.template,"
                + " (SELECT COUNT(*) FROM checklist_items i WHERE i.list = l._id AND i.done = 1),"
                + " (SELECT COUNT(*) FROM checklist_items i WHERE i.list = l._id)"
                + " FROM checklists l WHERE l.template = ? ORDER BY l._id DESC", a(templates ? 1 : 0));
        try {
            while (c.moveToNext()) {
                Checklist l = new Checklist();
                l.id = c.getLong(0);
                l.title = c.getString(1);
                l.trip = c.getLong(2);
                l.template = c.getInt(3) != 0;
                l.done = c.getInt(4);
                l.total = c.getInt(5);
                out.add(l);
            }
        } finally {
            c.close();
        }
        return out;
    }

    Checklist checklistForTrip(long trip) {
        for (Checklist l : checklists(false)) if (l.trip == trip) return l;
        return null;
    }

    long addChecklist(String title, boolean template, long trip) {
        return addChecklist(w(), title, template, trip);
    }

    private static long addChecklist(SQLiteDatabase db, String title, boolean template, long trip) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("template", template ? 1 : 0);
        v.put("trip", trip);
        v.put("created", System.currentTimeMillis());
        return db.insert("checklists", null, v);
    }

    void renameChecklist(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        w().update("checklists", v, "_id = ?", a(id));
    }

    void deleteChecklist(long id) {
        w().delete("checklist_items", "list = ?", a(id));
        w().delete("checklists", "_id = ?", a(id));
    }

    List<CheckItem> checkItems(long list) {
        List<CheckItem> out = new ArrayList<CheckItem>();
        Cursor c = r().rawQuery("SELECT _id, list, text, done FROM checklist_items WHERE list = ? ORDER BY position, _id", a(list));
        try {
            while (c.moveToNext()) {
                CheckItem i = new CheckItem();
                i.id = c.getLong(0);
                i.list = c.getLong(1);
                i.text = c.getString(2);
                i.done = c.getInt(3) != 0;
                out.add(i);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void addCheckItem(long list, String text) {
        addCheckItem(w(), list, text, 100000);
    }

    private static void addCheckItem(SQLiteDatabase db, long list, String text, int position) {
        ContentValues v = new ContentValues();
        v.put("list", list);
        v.put("text", text);
        v.put("position", position);
        db.insert("checklist_items", null, v);
    }

    void setCheckItem(long id, String text, boolean done) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("done", done ? 1 : 0);
        w().update("checklist_items", v, "_id = ?", a(id));
    }

    /** Adds pasted items that aren't on the list yet; returns {added, skipped}. */
    int[] importCheckItems(long list, List<ChecklistText.Line> lines) {
        java.util.Set<String> have = new java.util.HashSet<String>();
        for (CheckItem i : checkItems(list)) have.add(ChecklistText.key(i.text));
        int added = 0, skipped = 0;
        SQLiteDatabase db = w();
        db.beginTransaction();
        try {
            for (ChecklistText.Line l : lines) {
                if (!have.add(ChecklistText.key(l.text))) {
                    skipped++;
                    continue;
                }
                ContentValues v = new ContentValues();
                v.put("list", list);
                v.put("text", l.text);
                v.put("done", l.done ? 1 : 0);
                v.put("position", 100000);
                db.insert("checklist_items", null, v);
                added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new int[]{added, skipped};
    }

    /** Unticked items first (keeping their order), ticked ones at the bottom. */
    void sortCheckedLast(long list) {
        List<CheckItem> items = checkItems(list);
        SQLiteDatabase db = w();
        db.beginTransaction();
        try {
            int pos = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (CheckItem i : items) {
                    if (i.done != (pass == 1)) continue;
                    ContentValues v = new ContentValues();
                    v.put("position", pos++);
                    db.update("checklist_items", v, "_id = ?", a(i.id));
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void deleteCheckItem(long id) {
        w().delete("checklist_items", "_id = ?", a(id));
    }

    void resetChecklist(long list) {
        ContentValues v = new ContentValues();
        v.put("done", 0);
        w().update("checklist_items", v, "list = ?", a(list));
    }

    /** Copies a checklist (e.g. a template) into a new one. */
    long copyChecklist(long from, String title, boolean template, long trip) {
        long id = addChecklist(title, template, trip);
        int pos = 0;
        for (CheckItem i : checkItems(from)) addCheckItem(w(), id, i.text, pos++);
        return id;
    }

    /** Ready-made templates for a fresh install / upgrade. */
    static void seedTemplates(SQLiteDatabase db) {
        String[][] templates = {
                {"Business trip packing", "Laptop + charger", "Phone charger", "Power bank", "Travel adapter", "Headphones",
                        "Company badge", "ID / passport", "Business cards", "Suit / blazer", "Shirts", "Smart shoes",
                        "Toiletry bag", "Medication", "Notebook + pen", "Hotel booking", "Train / flight tickets",
                        "Corporate credit card"},
                {"Holiday packing", "Passport / ID", "Tickets & bookings", "Credit card + some cash", "Phone + charger",
                        "Travel adapter", "Sunglasses", "Sunscreen", "Swimwear", "T-shirts", "Shorts / trousers",
                        "Underwear & socks", "Comfortable shoes", "Toiletries", "Medication & first aid", "Book / e-reader",
                        "Travel insurance details", "House keys left with someone"},
                {"Weekend away", "Phone + charger", "Toothbrush & toiletries", "Change of clothes", "Pyjamas", "Wallet & ID",
                        "Snacks for the road"},
                {"Before leaving home", "Windows closed", "Stove & oven off", "Heating turned down", "Plants watered",
                        "Bins emptied", "Fridge checked", "Lights off", "Door locked"},
        };
        for (String[] t : templates) {
            long id = addChecklist(db, t[0], true, 0);
            for (int i = 1; i < t.length; i++) addCheckItem(db, id, t[i], i);
        }
    }
}
