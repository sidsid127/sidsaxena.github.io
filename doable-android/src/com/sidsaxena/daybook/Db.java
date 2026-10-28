package com.sidsaxena.daybook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local SQLite storage for tasks, categories and activity-log entries. Nothing leaves the phone. */
public class Db extends SQLiteOpenHelper {

    /** Colours handed out to new categories, in order. */
    public static final int[] PALETTE = {
            0xFF3F2CB8, 0xFF1E9C9A, 0xFFE5484D, 0xFFF2A33A,
            0xFF8E4EC6, 0xFF3D8BFD, 0xFF2F9E44, 0xFFD6409F,
    };

    public static class Task {
        public long id;
        public String title;
        public boolean done;
        public long due;      // millis at local midnight, 0 = no due date
        public long created;
        public long doneAt;
        public long category; // 0 = none
        public int priority;  // 0 none, 1 low, 2 medium, 3 high
        public double position; // manual order within the same priority (0 = never dragged)
        public int time = -1;   // reminder time, minutes after midnight on the due day; -1 = none
        public String repeat = ""; // Recurrence rule
        public String notes = "";
        public long snooze;     // reminder moved to this moment (0 = use due + time)

        /** When the reminder should fire, or 0. */
        public long remindAt() {
            if (done) return 0;
            if (snooze > 0) return snooze;
            if (due == 0 || time < 0) return 0;
            return due + time * 60000L + dstFix(due, time);
        }
    }

    /** Daylight-saving safe "midnight + minutes". */
    static long dstFix(long midnight, int minutes) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(midnight);
        c.set(java.util.Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(java.util.Calendar.MINUTE, minutes % 60);
        return c.getTimeInMillis() - (midnight + minutes * 60000L);
    }

    public static class Category {
        public long id;
        public String name;
        public long parent;   // 0 = top level
        public int color;
    }

    public static class Note {
        public long id;
        public String body;
        public boolean pinned;
        public long modified;
        public int color;        // 0 = none, else an ARGB colour
        public String folder = "";
        public boolean locked;   // body is encrypted
        public long deleted;     // in "Recently deleted" since then; 0 = not deleted
    }

    public static class Shop {
        public long id;
        public String name;
        public int color;
    }

    public static class Item {
        public long id;
        public String name;
        public long shop;     // 0 = any shop
        public boolean done;
        public long created;
        public String qty = "";
        public long list = 1;
        public String aisle = "";
        public boolean urgent;  // needed right away
    }

    /** A previously bought grocery item, offered under "Buy again". */
    public static class Usual {
        public String name;
        public long shop;
        public long list = 1;
        public String qty = ""; // quantity used last time
    }

    public static class Entry {
        public long id;
        public String text;
        public long ts;
    }

    private static Db instance;

    /**
     * The one database helper for the whole app. Screens, sync, reminders and widgets all share it, so the
     * database is created once and different threads never trip over each other ("database is locked").
     */
    public static synchronized Db get(Context context) {
        if (instance == null) instance = new Db(context.getApplicationContext());
        return instance;
    }

    private Db(Context context) {
        super(context, "daybook.db", null, 8);
        setWriteAheadLoggingEnabled(true); // readers (screens) keep working while sync writes
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,"
                + " done INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0,"
                + " created INTEGER NOT NULL, done_at INTEGER NOT NULL DEFAULT 0,"
                + " category INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE log (_id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL,"
                + " ts INTEGER NOT NULL)");
        createCategories(db);
        createV3(db);
        createV4(db);
        createV5(db);
        createV6(db);
        createV7(db);
        createV8(db);
    }

    /** A crash during a sync could leave the "applying" flag set; clear it. */
    @Override
    public void onOpen(SQLiteDatabase db) {
        if (!db.isReadOnly()) {
            try {
                db.execSQL("UPDATE sync_state SET applying = 0");
            } catch (RuntimeException ignored) {
                // table missing in a half-upgraded database
            }
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN category INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0");
            createCategories(db);
        }
        if (oldVersion < 3) createV3(db);
        if (oldVersion < 4) {
            createV4(db);
            // Ticked grocery items now leave the list straight away.
            db.delete("grocery", "done = 1", null);
        }
        if (oldVersion < 5) createV5(db);
        if (oldVersion < 6) createV6(db);
        if (oldVersion < 7) createV7(db);
        if (oldVersion < 8) createV8(db);
    }

    /** Tables whose rows can be shared with a household or a person ("space" = the space's id, '' = private). */
    static final String[] SHAREABLE = {"grocery_lists", "categories", "tasks", "notes", "checklists", "dates", "wallet"};

    /** Version 8: households and sharing. */
    static void createV8(SQLiteDatabase db) {
        for (String t : SHAREABLE) db.execSQL("ALTER TABLE " + t + " ADD COLUMN space TEXT NOT NULL DEFAULT ''");
    }

    /** Version 7: urgent grocery items, and the last quantity used for each product. */
    static void createV7(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE grocery ADD COLUMN urgent INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE grocery_history ADD COLUMN qty TEXT NOT NULL DEFAULT ''");
    }

    static final String NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)";
    private static final String RANDOM_UID = "lower(hex(randomblob(16)))";

    /**
     * Sync (version 6): every synced table gets a stable uid and a modification time, and deletions leave a
     * tombstone. Triggers keep them up to date, so ordinary app code doesn't have to know about sync; while a
     * sync writes rows, sync_state.applying = 1 and the triggers keep the incoming times.
     */
    static void createV6(SQLiteDatabase db) {
        for (String sql : v6Sql()) db.execSQL(sql);
        seedUids(db);
        for (SyncSchema.Table t : SyncSchema.TABLES) {
            String expr = t.keyedUid == null ? RANDOM_UID : t.keyedUid.replace("NEW.", "");
            db.execSQL("UPDATE " + t.name + " SET uid = " + expr + " WHERE uid IS NULL");
            db.execSQL("UPDATE " + t.name + " SET mtime = 2"); // older than any real edit, newer than other devices' fresh starting data
            db.execSQL("CREATE UNIQUE INDEX " + t.name + "_uid ON " + t.name + " (uid)");
            for (String sql : triggers(t)) db.execSQL(sql);
        }
    }

    static String[] v6Sql() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add("CREATE TABLE tombstones (tbl TEXT NOT NULL, uid TEXT NOT NULL, mtime INTEGER NOT NULL, PRIMARY KEY (tbl, uid))");
        out.add("CREATE TABLE sync_state (applying INTEGER NOT NULL)");
        out.add("INSERT INTO sync_state VALUES (0)");
        for (SyncSchema.Table t : SyncSchema.TABLES) {
            out.add("ALTER TABLE " + t.name + " ADD COLUMN uid TEXT");
            out.add("ALTER TABLE " + t.name + " ADD COLUMN mtime INTEGER NOT NULL DEFAULT 0");
        }
        return out.toArray(new String[0]);
    }

    static String[] triggers(SyncSchema.Table t) {
        String n = t.name;
        String expr = t.keyedUid == null ? RANDOM_UID : t.keyedUid;
        String applying = "(SELECT applying FROM sync_state)";
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add("CREATE TRIGGER " + n + "_sync_ins AFTER INSERT ON " + n + " BEGIN"
                + " UPDATE " + n + " SET uid = COALESCE(NEW.uid, " + expr + "), mtime = CASE WHEN " + applying + " = 1 THEN NEW.mtime ELSE " + NOW_MS + " END"
                + " WHERE rowid = NEW.rowid;"
                + " DELETE FROM tombstones WHERE tbl = '" + n + "' AND uid = (SELECT uid FROM " + n + " WHERE rowid = NEW.rowid); END");
        if (t.keyedUid == null) {
            out.add("CREATE TRIGGER " + n + "_sync_upd AFTER UPDATE ON " + n + " WHEN " + applying + " = 0 BEGIN"
                    + " UPDATE " + n + " SET mtime = " + NOW_MS + " WHERE rowid = NEW.rowid; END");
        } else {
            // The uid follows the content (e.g. a renamed history item): the old uid becomes a tombstone.
            out.add("CREATE TRIGGER " + n + "_sync_upd AFTER UPDATE ON " + n + " WHEN " + applying + " = 0 BEGIN"
                    + " INSERT OR REPLACE INTO tombstones (tbl, uid, mtime) SELECT '" + n + "', OLD.uid, " + NOW_MS
                    + " WHERE OLD.uid IS NOT NULL AND OLD.uid <> " + expr + ";"
                    + " UPDATE " + n + " SET mtime = " + NOW_MS + ", uid = " + expr + " WHERE rowid = NEW.rowid; END");
        }
        out.add("CREATE TRIGGER " + n + "_sync_del AFTER DELETE ON " + n + " WHEN " + applying + " = 0 AND OLD.uid IS NOT NULL BEGIN"
                + " INSERT OR REPLACE INTO tombstones (tbl, uid, mtime) VALUES ('" + n + "', OLD.uid, " + NOW_MS + "); END");
        return out.toArray(new String[0]);
    }

    /** Built-in starting data gets the same fixed uids on every device (doable/SYNC.md), so it merges instead of doubling. */
    static void seedUids(SQLiteDatabase db) {
        String[][] byName = {
                {"categories", "name = 'Private' AND parent = 0", "seed-cat-private"},
                {"categories", "name = 'Work' AND parent = 0", "seed-cat-work"},
                {"categories", "name = 'Accenture' AND parent = (SELECT _id FROM categories WHERE uid = 'seed-cat-work')", "seed-cat-accenture"},
                {"shops", "name = 'Rewe'", "seed-shop-rewe"},
                {"shops", "name = 'Lidl'", "seed-shop-lidl"},
                {"shops", "name = 'Aldi'", "seed-shop-aldi"},
                {"grocery_lists", "name = 'Groceries'", "seed-list-groceries"},
                {"grocery_lists", "name = 'Drugstore'", "seed-list-drugstore"},
                {"grocery_lists", "name = 'Hardware store'", "seed-list-hardware"},
                {"checklists", "template = 1 AND title = 'Business trip packing'", "seed-tpl-business"},
                {"checklists", "template = 1 AND title = 'Holiday packing'", "seed-tpl-holiday"},
                {"checklists", "template = 1 AND title = 'Weekend away'", "seed-tpl-weekend"},
                {"checklists", "template = 1 AND title = 'Before leaving home'", "seed-tpl-leaving"},
        };
        for (String[] s : byName) {
            db.execSQL("UPDATE " + s[0] + " SET uid = '" + s[2] + "' WHERE uid IS NULL AND _id = (SELECT MIN(_id) FROM " + s[0] + " WHERE " + s[1] + ")"
                    + " AND NOT EXISTS (SELECT 1 FROM " + s[0] + " WHERE uid = '" + s[2] + "')");
        }
        // Template items: seed-tpl-<name>-1, -2, … in list order.
        for (String k : new String[]{"business", "holiday", "weekend", "leaving"}) {
            Cursor c = db.rawQuery("SELECT i._id FROM checklist_items i JOIN checklists l ON l._id = i.list WHERE l.uid = ? AND i.uid IS NULL"
                    + " ORDER BY i.position, i._id", new String[]{"seed-tpl-" + k});
            try {
                int n = 0;
                while (c.moveToNext()) {
                    n++;
                    db.execSQL("UPDATE checklist_items SET uid = ? WHERE _id = ? AND NOT EXISTS (SELECT 1 FROM checklist_items WHERE uid = ?)",
                            new Object[]{"seed-tpl-" + k + "-" + n, c.getLong(0), "seed-tpl-" + k + "-" + n});
                }
            } finally {
                c.close();
            }
        }
    }

    /** Reminders, repeats, subtasks, note extras, grocery lists & prices, new modules. Version 5. */
    private static void createV5(SQLiteDatabase db) {
        String[] alters = {
                "tasks ADD COLUMN time INTEGER NOT NULL DEFAULT -1",
                "tasks ADD COLUMN repeat TEXT NOT NULL DEFAULT ''",
                "tasks ADD COLUMN notes TEXT NOT NULL DEFAULT ''",
                "tasks ADD COLUMN snooze INTEGER NOT NULL DEFAULT 0",
                "notes ADD COLUMN color INTEGER NOT NULL DEFAULT 0",
                "notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''",
                "notes ADD COLUMN locked INTEGER NOT NULL DEFAULT 0",
                "notes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
                "grocery ADD COLUMN qty TEXT NOT NULL DEFAULT ''",
                "grocery ADD COLUMN list INTEGER NOT NULL DEFAULT 1",
                "grocery ADD COLUMN aisle TEXT NOT NULL DEFAULT ''",
                "grocery_history ADD COLUMN list INTEGER NOT NULL DEFAULT 1",
                "diary ADD COLUMN place TEXT NOT NULL DEFAULT ''",
                "diary ADD COLUMN lat REAL NOT NULL DEFAULT 0",
                "diary ADD COLUMN lon REAL NOT NULL DEFAULT 0",
                "diary ADD COLUMN weather TEXT NOT NULL DEFAULT ''",
                "diary ADD COLUMN temp TEXT NOT NULL DEFAULT ''",
                "trips ADD COLUMN budget INTEGER NOT NULL DEFAULT 0",
                "trips ADD COLUMN rates TEXT NOT NULL DEFAULT ''",
                "trips ADD COLUMN pd_full INTEGER NOT NULL DEFAULT 2800",
                "trips ADD COLUMN pd_partial INTEGER NOT NULL DEFAULT 1400",
                "trips ADD COLUMN km_rate INTEGER NOT NULL DEFAULT 30",
                "trips ADD COLUMN per_diem INTEGER NOT NULL DEFAULT 0",
                "expenses ADD COLUMN km REAL NOT NULL DEFAULT 0",
        };
        for (String a : alters) db.execSQL("ALTER TABLE " + a);
        db.execSQL("CREATE TABLE subtasks (_id INTEGER PRIMARY KEY AUTOINCREMENT, task INTEGER NOT NULL, text TEXT NOT NULL,"
                + " done INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE grocery_lists (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)");
        for (String n : new String[]{"Groceries", "Drugstore", "Hardware store"}) {
            ContentValues v = new ContentValues();
            v.put("name", n);
            db.insert("grocery_lists", null, v);
        }
        db.execSQL("CREATE TABLE prices (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE,"
                + " shop INTEGER NOT NULL, cents INTEGER NOT NULL, qty TEXT NOT NULL DEFAULT '', day INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE habits (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL DEFAULT '',"
                + " quit INTEGER NOT NULL DEFAULT 0, target INTEGER NOT NULL DEFAULT 1, days INTEGER NOT NULL DEFAULT 127,"
                + " remind TEXT NOT NULL DEFAULT '', color INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0,"
                + " created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE habit_log (habit INTEGER NOT NULL, day INTEGER NOT NULL, count INTEGER NOT NULL,"
                + " PRIMARY KEY (habit, day))");
        db.execSQL("CREATE TABLE wallet (_id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, title TEXT NOT NULL,"
                + " number TEXT NOT NULL DEFAULT '', holder TEXT NOT NULL DEFAULT '', expiry INTEGER NOT NULL DEFAULT 0,"
                + " barcode TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE dates (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL,"
                + " month INTEGER NOT NULL, day INTEGER NOT NULL, year INTEGER NOT NULL DEFAULT 0, yearly INTEGER NOT NULL DEFAULT 1,"
                + " remind_days INTEGER NOT NULL DEFAULT 1, notes TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE checklists (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,"
                + " trip INTEGER NOT NULL DEFAULT 0, template INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE checklist_items (_id INTEGER PRIMARY KEY AUTOINCREMENT, list INTEGER NOT NULL, text TEXT NOT NULL,"
                + " done INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)");
        Store.seedTemplates(db);
    }

    /** Diary, trips, attachments and manual task order, added in version 4. */
    private static void createV4(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN position REAL NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE diary (_id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL UNIQUE,"
                + " mood INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL DEFAULT '', highs TEXT NOT NULL DEFAULT '',"
                + " lows TEXT NOT NULL DEFAULT '', modified INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE trips (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind INTEGER NOT NULL,"
                + " destination TEXT NOT NULL DEFAULT '', start INTEGER NOT NULL DEFAULT 0, finish INTEGER NOT NULL DEFAULT 0,"
                + " currency TEXT NOT NULL DEFAULT 'EUR', created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE expenses (_id INTEGER PRIMARY KEY AUTOINCREMENT, trip INTEGER NOT NULL, day INTEGER NOT NULL,"
                + " category TEXT NOT NULL, cents INTEGER NOT NULL, currency TEXT NOT NULL, merchant TEXT NOT NULL DEFAULT '',"
                + " attendees TEXT NOT NULL DEFAULT '', payment TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',"
                + " submitted INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE trip_items (_id INTEGER PRIMARY KEY AUTOINCREMENT, trip INTEGER NOT NULL, kind TEXT NOT NULL,"
                + " subtype TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',"
                + " day INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE attachments (_id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL,"
                + " owner_id INTEGER NOT NULL, file TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)");
    }

    /** Notes and groceries, added in version 3. */
    private static void createV3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (_id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL,"
                + " pinned INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, modified INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE shops (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, color INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE grocery (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,"
                + " shop INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE grocery_history (name TEXT PRIMARY KEY COLLATE NOCASE, shop INTEGER NOT NULL,"
                + " uses INTEGER NOT NULL, last_used INTEGER NOT NULL)");
        insertShop(db, "Rewe", 0xFFCC071E);
        insertShop(db, "Lidl", 0xFF0050AA);
        insertShop(db, "Aldi", 0xFFF28C00);
    }

    private static long insertShop(SQLiteDatabase db, String name, int color) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("color", color);
        return db.insert("shops", null, v);
    }

    private static void createCategories(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,"
                + " parent INTEGER NOT NULL DEFAULT 0, color INTEGER NOT NULL)");
        insertCategory(db, "Private", 0, PALETTE[1]);
        long work = insertCategory(db, "Work", 0, PALETTE[0]);
        insertCategory(db, "Accenture", work, PALETTE[4]);
    }

    private static long insertCategory(SQLiteDatabase db, String name, long parent, int color) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("parent", parent);
        v.put("color", color);
        return db.insert("categories", null, v);
    }

    // ---- tasks ----

    public List<Task> tasks() {
        List<Task> out = new ArrayList<Task>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id, title, done, due, created, done_at,"
                + " category, priority, position, time, repeat, notes, snooze FROM tasks", null);
        try {
            while (c.moveToNext()) {
                Task t = new Task();
                t.id = c.getLong(0);
                t.title = c.getString(1);
                t.done = c.getInt(2) != 0;
                t.due = c.getLong(3);
                t.created = c.getLong(4);
                t.doneAt = c.getLong(5);
                t.category = c.getLong(6);
                t.priority = c.getInt(7);
                t.position = c.getDouble(8);
                t.time = c.getInt(9);
                t.repeat = c.getString(10);
                t.notes = c.getString(11);
                t.snooze = c.getLong(12);
                out.add(t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void addTask(String title, long due, long category, int priority) {
        Task t = new Task();
        t.title = title;
        t.due = due;
        t.category = category;
        t.priority = priority;
        saveTask(t);
    }

    public Task task(long id) {
        for (Task t : tasks()) if (t.id == id) return t;
        return null;
    }

    /** Inserts (id 0) or updates every field of a task. */
    public void saveTask(Task t) {
        ContentValues v = new ContentValues();
        v.put("title", t.title);
        v.put("due", t.due);
        v.put("category", t.category);
        v.put("priority", t.priority);
        v.put("time", t.time);
        v.put("repeat", t.repeat);
        v.put("notes", t.notes);
        v.put("snooze", t.snooze);
        v.put("done", t.done ? 1 : 0);
        v.put("done_at", t.doneAt);
        v.put("position", t.position);
        if (t.id == 0) {
            v.put("created", t.created = System.currentTimeMillis());
            t.id = getWritableDatabase().insert("tasks", null, v);
        } else {
            updateTask(t.id, v);
        }
    }

    public void updateTask(long id, String title, long due, long category, int priority) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("due", due);
        v.put("category", category);
        v.put("priority", priority);
        updateTask(id, v);
    }

    /** Stores a manual order (1, 2, 3 …) for tasks dragged into a new order. */
    public void setPositions(List<Task> ordered) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ordered.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("position", i + 1);
                db.update("tasks", v, "_id = ?", new String[]{String.valueOf(ordered.get(i).id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void setDue(long id, long due) {
        ContentValues v = new ContentValues();
        v.put("due", due);
        updateTask(id, v);
    }

    public void setPriority(long id, int priority) {
        ContentValues v = new ContentValues();
        v.put("priority", priority);
        updateTask(id, v);
    }

    public void setDone(long id, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        v.put("done_at", done ? System.currentTimeMillis() : 0);
        updateTask(id, v);
    }

    private void updateTask(long id, ContentValues v) {
        getWritableDatabase().update("tasks", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteTask(long id) {
        getWritableDatabase().delete("tasks", "_id = ?", new String[]{String.valueOf(id)});
    }

    public int clearDone() {
        return getWritableDatabase().delete("tasks", "done = 1", null);
    }

    // ---- categories ----

    /** Top-level categories in creation order, each followed by its sub-categories. */
    public List<Category> categories() {
        List<Category> all = new ArrayList<Category>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id, name, parent, color FROM categories ORDER BY _id", null);
        try {
            while (c.moveToNext()) {
                Category k = new Category();
                k.id = c.getLong(0);
                k.name = c.getString(1);
                k.parent = c.getLong(2);
                k.color = c.getInt(3);
                all.add(k);
            }
        } finally {
            c.close();
        }
        List<Category> out = new ArrayList<Category>();
        for (Category top : all) {
            if (top.parent != 0) continue;
            out.add(top);
            for (Category sub : all) if (sub.parent == top.id) out.add(sub);
        }
        return out;
    }

    public long addCategory(String name, long parent) {
        long count = android.database.DatabaseUtils.queryNumEntries(getReadableDatabase(), "categories");
        return insertCategory(getWritableDatabase(), name, parent, PALETTE[(int) (count % PALETTE.length)]);
    }

    public void renameCategory(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("categories", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Deletes a category. Its tasks are kept: a sub-category's tasks move up to the parent,
     * a top-level category's tasks (and those of its sub-categories) become uncategorised.
     */
    public void deleteCategory(Category k) {
        SQLiteDatabase db = getWritableDatabase();
        String id = String.valueOf(k.id);
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("category", k.parent);
            db.update("tasks", v, "category = ? OR category IN (SELECT _id FROM categories WHERE parent = ?)",
                    new String[]{id, id});
            db.delete("categories", "_id = ? OR parent = ?", new String[]{id, id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- notes ----

    /** Pinned notes first, then most recently edited. */
    public List<Note> notes() {
        List<Note> out = new ArrayList<Note>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id, body, pinned, modified, color, folder, locked, deleted FROM notes"
                        + " ORDER BY pinned DESC, modified DESC", null);
        try {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(0);
                n.body = c.getString(1);
                n.pinned = c.getInt(2) != 0;
                n.modified = c.getLong(3);
                n.color = c.getInt(4);
                n.folder = c.getString(5);
                n.locked = c.getInt(6) != 0;
                n.deleted = c.getLong(7);
                out.add(n);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public long addNote(String body) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("body", body);
        v.put("created", now);
        v.put("modified", now);
        return getWritableDatabase().insert("notes", null, v);
    }

    public void updateNote(long id, String body) {
        ContentValues v = new ContentValues();
        v.put("body", body);
        v.put("modified", System.currentTimeMillis());
        getWritableDatabase().update("notes", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    /** Colour, folder, lock state and "recently deleted" time. */
    public void saveNoteMeta(Note n) {
        ContentValues v = new ContentValues();
        v.put("color", n.color);
        v.put("folder", n.folder);
        v.put("locked", n.locked ? 1 : 0);
        v.put("deleted", n.deleted);
        v.put("pinned", n.pinned ? 1 : 0);
        getWritableDatabase().update("notes", v, "_id = ?", new String[]{String.valueOf(n.id)});
    }

    /** Removes notes that have been in "Recently deleted" for 30 days; returns their attachment files. */
    public List<String> purgeDeletedNotes() {
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        List<String> files = new ArrayList<String>();
        for (Note n : notes()) {
            if (n.deleted > 0 && n.deleted < cutoff) {
                files.addAll(attachmentFiles("note", n.id));
                deleteAttachmentsOf("note", n.id);
                deleteNote(n.id);
            }
        }
        return files;
    }

    public void setPinned(long id, boolean pinned) {
        ContentValues v = new ContentValues();
        v.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("notes", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteNote(long id) {
        getWritableDatabase().delete("notes", "_id = ?", new String[]{String.valueOf(id)});
    }

    // ---- groceries ----

    public List<Shop> shops() {
        List<Shop> out = new ArrayList<Shop>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id, name, color FROM shops ORDER BY _id", null);
        try {
            while (c.moveToNext()) {
                Shop k = new Shop();
                k.id = c.getLong(0);
                k.name = c.getString(1);
                k.color = c.getInt(2);
                out.add(k);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public long addShop(String name) {
        long count = android.database.DatabaseUtils.queryNumEntries(getReadableDatabase(), "shops");
        return insertShop(getWritableDatabase(), name, PALETTE[(int) ((count + 2) % PALETTE.length)]);
    }

    public void renameShop(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("shops", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    /** Deletes a shop; its items (and history) fall back to "any shop". */
    public void deleteShop(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] arg = {String.valueOf(id)};
        ContentValues v = new ContentValues();
        v.put("shop", 0);
        db.update("grocery", v, "shop = ?", arg);
        db.update("grocery_history", v, "shop = ?", arg);
        db.delete("shops", "_id = ?", arg);
    }

    public List<Item> items() {
        List<Item> out = new ArrayList<Item>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id, name, shop, done, created, qty, list, aisle, urgent FROM grocery ORDER BY done, created", null);
        try {
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.name = c.getString(1);
                it.shop = c.getLong(2);
                it.done = c.getInt(3) != 0;
                it.created = c.getLong(4);
                it.qty = c.getString(5);
                it.list = c.getLong(6);
                it.aisle = c.getString(7);
                it.urgent = c.getInt(8) != 0;
                out.add(it);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Adds an item to the list and remembers it (and its shop) for "Buy again". */
    public void addItem(String name, long shop) {
        addItem(name, "", shop, 1);
    }

    /** Adds an item to a list and remembers it (with its shop and list) for "Buy again". */
    public void addItem(String name, String qty, long shop, long list) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("qty", qty);
        v.put("shop", shop);
        v.put("list", list);
        v.put("aisle", GroceryText.aisle(name));
        v.put("created", now);
        db.insert("grocery", null, v);
        // (No UPSERT: SQLite on older Android versions doesn't support it.)
        db.execSQL("UPDATE grocery_history SET shop = ?, list = ?, uses = uses + 1, last_used = ?, qty = ? WHERE name = ?",
                new Object[]{shop, list, now, qty, name});
        if (android.database.DatabaseUtils.longForQuery(db, "SELECT changes()", null) == 0) {
            ContentValues h = new ContentValues();
            h.put("name", name);
            h.put("shop", shop);
            h.put("list", list);
            h.put("uses", 1);
            h.put("last_used", now);
            h.put("qty", qty);
            db.insert("grocery_history", null, h);
        }
    }

    public void updateItemFull(Item it) {
        ContentValues v = new ContentValues();
        v.put("name", it.name);
        v.put("qty", it.qty);
        v.put("shop", it.shop);
        v.put("list", it.list);
        v.put("aisle", it.aisle);
        v.put("urgent", it.urgent ? 1 : 0);
        getWritableDatabase().update("grocery", v, "_id = ?", new String[]{String.valueOf(it.id)});
        ContentValues h = new ContentValues();
        h.put("shop", it.shop);
        h.put("list", it.list);
        h.put("qty", it.qty);
        getWritableDatabase().update("grocery_history", h, "name = ?", new String[]{it.name});
    }

    /** New quantity from the +/− buttons or the unit picker; remembered for next time. */
    public void setItemQty(Item it, String qty) {
        it.qty = qty;
        ContentValues v = new ContentValues();
        v.put("qty", qty);
        getWritableDatabase().update("grocery", v, "_id = ?", new String[]{String.valueOf(it.id)});
        getWritableDatabase().update("grocery_history", v, "name = ?", new String[]{it.name});
    }

    public void setItemUrgent(long id, boolean urgent) {
        ContentValues v = new ContentValues();
        v.put("urgent", urgent ? 1 : 0);
        getWritableDatabase().update("grocery", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void updateItem(long id, String name, long shop) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("shop", shop);
        getWritableDatabase().update("grocery", v, "_id = ?", new String[]{String.valueOf(id)});
        ContentValues h = new ContentValues();
        h.put("shop", shop);
        getWritableDatabase().update("grocery_history", h, "name = ?", new String[]{name});
    }

    public void setItemDone(long id, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        getWritableDatabase().update("grocery", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteItem(long id) {
        getWritableDatabase().delete("grocery", "_id = ?", new String[]{String.valueOf(id)});
    }

    public int clearBoughtItems() {
        return getWritableDatabase().delete("grocery", "done = 1", null);
    }

    /** Most often and most recently bought first. */
    public List<Usual> usuals() {
        List<Usual> out = new ArrayList<Usual>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name, shop, list, qty FROM grocery_history ORDER BY uses DESC, last_used DESC LIMIT 150", null);
        try {
            while (c.moveToNext()) {
                Usual u = new Usual();
                u.name = c.getString(0);
                u.shop = c.getLong(1);
                u.list = c.getLong(2);
                u.qty = c.getString(3);
                out.add(u);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void forgetUsual(String name) {
        getWritableDatabase().delete("grocery_history", "name = ?", new String[]{name});
    }

    /** Puts a ticked-off item back (Undo), without counting it again in the history. */
    public void restoreItem(Item it) {
        ContentValues v = new ContentValues();
        v.put("name", it.name);
        v.put("shop", it.shop);
        v.put("created", it.created);
        v.put("qty", it.qty);
        v.put("list", it.list);
        v.put("aisle", it.aisle);
        v.put("urgent", it.urgent ? 1 : 0);
        getWritableDatabase().insert("grocery", null, v);
    }

    // ---- diary ----

    public static class Diary {
        public long id;
        public long day;      // local midnight
        public int mood;      // 0 = not set, 1 (bad) … 5 (great)
        public String text = "";
        public String highs = "";
        public String lows = "";
        public long modified;
        public String place = "";
        public double lat, lon;
        public String weather = "";
        public String temp = "";

        public boolean isEmpty() {
            return mood == 0 && text.trim().isEmpty() && highs.trim().isEmpty() && lows.trim().isEmpty()
                    && place.isEmpty() && weather.isEmpty() && temp.isEmpty();
        }
    }

    /** Newest day first. */
    public List<Diary> diary() {
        List<Diary> out = new ArrayList<Diary>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id, day, mood, text, highs, lows, modified, place, lat, lon, weather, temp FROM diary ORDER BY day DESC", null);
        try {
            while (c.moveToNext()) {
                Diary d = new Diary();
                d.id = c.getLong(0);
                d.day = c.getLong(1);
                d.mood = c.getInt(2);
                d.text = c.getString(3);
                d.highs = c.getString(4);
                d.lows = c.getString(5);
                d.modified = c.getLong(6);
                d.place = c.getString(7);
                d.lat = c.getDouble(8);
                d.lon = c.getDouble(9);
                d.weather = c.getString(10);
                d.temp = c.getString(11);
                out.add(d);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Diary diaryFor(long day) {
        for (Diary d : diary()) if (d.day == day) return d;
        return null;
    }

    public void saveDiary(Diary d) {
        ContentValues v = new ContentValues();
        v.put("day", d.day);
        v.put("mood", d.mood);
        v.put("text", d.text);
        v.put("highs", d.highs);
        v.put("lows", d.lows);
        v.put("place", d.place);
        v.put("lat", d.lat);
        v.put("lon", d.lon);
        v.put("weather", d.weather);
        v.put("temp", d.temp);
        v.put("modified", d.modified = System.currentTimeMillis());
        if (d.id == 0) d.id = getWritableDatabase().insert("diary", null, v);
        else getWritableDatabase().update("diary", v, "_id = ?", new String[]{String.valueOf(d.id)});
    }

    public void deleteDiary(long id) {
        getWritableDatabase().delete("diary", "_id = ?", new String[]{String.valueOf(id)});
    }

    // ---- trips ----

    public static final int BUSINESS = 0, PRIVATE = 1;

    public static class Trip {
        public long id;
        public String name = "";
        public int kind;
        public String destination = "";
        public long start, finish;   // local midnight, 0 = not set
        public String currency = "EUR"; // home currency (totals and reports)
        public long budget;          // private trips, in home currency cents; 0 = none
        public String rates = "";    // "INR=0.0108;USD=0.92": value of 1 unit in the home currency
        public long pdFull = 2800, pdPartial = 1400; // per-diem rates in cents (German defaults)
        public long kmRate = 30;     // mileage allowance, cents per km
        public boolean perDiem;      // claim per-diem allowances on this trip
    }

    public static class Expense {
        public long id, trip, day;
        public String category = "", currency = "EUR", merchant = "", attendees = "", payment = "", note = "";
        public long cents;
        public boolean submitted;
        public double km;       // mileage claims
        public String tripName; // filled by monthly queries
        public String tripHome, tripRates; // filled by monthly queries (for conversion)
    }

    /** Booking/pass ("booking"), place to go ("place") or journal/notes entry ("journal"). */
    public static class TripItem {
        public long id, trip, day, created;
        public String kind = "", subtype = "", title = "", body = "";
        public boolean done;
    }

    /** Upcoming and current trips first, then past ones (newest first). */
    public List<Trip> trips() {
        List<Trip> out = new ArrayList<Trip>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id, name, kind, destination, start, finish, currency,"
                + " budget, rates, pd_full, pd_partial, km_rate, per_diem FROM trips ORDER BY CASE WHEN finish = 0 OR finish >= ? THEN 0 ELSE 1 END, start DESC, _id DESC",
                new String[]{String.valueOf(Util.startOfDay(System.currentTimeMillis()))});
        try {
            while (c.moveToNext()) {
                Trip t = new Trip();
                t.id = c.getLong(0);
                t.name = c.getString(1);
                t.kind = c.getInt(2);
                t.destination = c.getString(3);
                t.start = c.getLong(4);
                t.finish = c.getLong(5);
                t.currency = c.getString(6);
                t.budget = c.getLong(7);
                t.rates = c.getString(8);
                t.pdFull = c.getLong(9);
                t.pdPartial = c.getLong(10);
                t.kmRate = c.getLong(11);
                t.perDiem = c.getInt(12) != 0;
                out.add(t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void saveTrip(Trip t) {
        ContentValues v = new ContentValues();
        v.put("name", t.name);
        v.put("kind", t.kind);
        v.put("destination", t.destination);
        v.put("start", t.start);
        v.put("finish", t.finish);
        v.put("currency", t.currency);
        v.put("budget", t.budget);
        v.put("rates", t.rates);
        v.put("pd_full", t.pdFull);
        v.put("pd_partial", t.pdPartial);
        v.put("km_rate", t.kmRate);
        v.put("per_diem", t.perDiem ? 1 : 0);
        if (t.id == 0) {
            v.put("created", System.currentTimeMillis());
            t.id = getWritableDatabase().insert("trips", null, v);
        } else {
            getWritableDatabase().update("trips", v, "_id = ?", new String[]{String.valueOf(t.id)});
        }
    }

    /** Deletes a trip with everything in it; returns the attachment files to delete. */
    public List<String> deleteTrip(long id) {
        List<String> files = new ArrayList<String>();
        for (Expense e : expenses(id)) files.addAll(attachmentFiles("expense", e.id));
        for (TripItem i : tripItems(id, null)) files.addAll(attachmentFiles("trip_item", i.id));
        SQLiteDatabase db = getWritableDatabase();
        String[] arg = {String.valueOf(id)};
        db.execSQL("DELETE FROM attachments WHERE (owner = 'expense' AND owner_id IN (SELECT _id FROM expenses WHERE trip = ?))"
                + " OR (owner = 'trip_item' AND owner_id IN (SELECT _id FROM trip_items WHERE trip = ?))", new Object[]{id, id});
        db.delete("expenses", "trip = ?", arg);
        db.delete("trip_items", "trip = ?", arg);
        db.delete("trips", "_id = ?", arg);
        return files;
    }

    private Expense readExpense(Cursor c) {
        Expense e = new Expense();
        e.id = c.getLong(0);
        e.trip = c.getLong(1);
        e.day = c.getLong(2);
        e.category = c.getString(3);
        e.cents = c.getLong(4);
        e.currency = c.getString(5);
        e.merchant = c.getString(6);
        e.attendees = c.getString(7);
        e.payment = c.getString(8);
        e.note = c.getString(9);
        e.submitted = c.getInt(10) != 0;
        e.km = c.getDouble(11);
        if (c.getColumnCount() > 12) {
            e.tripName = c.getString(12);
            e.tripHome = c.getString(13);
            e.tripRates = c.getString(14);
        }
        return e;
    }

    private static final String EXPENSE_COLS = "e._id, e.trip, e.day, e.category, e.cents, e.currency, e.merchant,"
            + " e.attendees, e.payment, e.note, e.submitted, e.km";

    /** A trip's expenses, newest day first. */
    public List<Expense> expenses(long trip) {
        List<Expense> out = new ArrayList<Expense>();
        Cursor c = getReadableDatabase().rawQuery("SELECT " + EXPENSE_COLS + " FROM expenses e WHERE e.trip = ?"
                + " ORDER BY e.day DESC, e.created DESC", new String[]{String.valueOf(trip)});
        try {
            while (c.moveToNext()) out.add(readExpense(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Business-trip expenses with from <= day < to, oldest first (for the monthly report). */
    public List<Expense> businessExpenses(long from, long to) {
        List<Expense> out = new ArrayList<Expense>();
        Cursor c = getReadableDatabase().rawQuery("SELECT " + EXPENSE_COLS + ", t.name, t.currency, t.rates FROM expenses e"
                + " JOIN trips t ON t._id = e.trip WHERE t.kind = 0 AND e.day >= ? AND e.day < ?"
                + " ORDER BY e.day, e.created", new String[]{String.valueOf(from), String.valueOf(to)});
        try {
            while (c.moveToNext()) out.add(readExpense(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Days (local midnight) of all business expenses, for picking a report month. */
    public List<Long> businessExpenseDays() {
        List<Long> out = new ArrayList<Long>();
        Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT e.day FROM expenses e JOIN trips t ON t._id = e.trip"
                + " WHERE t.kind = 0 ORDER BY e.day DESC", null);
        try {
            while (c.moveToNext()) out.add(c.getLong(0));
        } finally {
            c.close();
        }
        return out;
    }

    public void saveExpense(Expense e) {
        ContentValues v = new ContentValues();
        v.put("trip", e.trip);
        v.put("day", e.day);
        v.put("category", e.category);
        v.put("cents", e.cents);
        v.put("currency", e.currency);
        v.put("merchant", e.merchant);
        v.put("attendees", e.attendees);
        v.put("payment", e.payment);
        v.put("note", e.note);
        v.put("submitted", e.submitted ? 1 : 0);
        v.put("km", e.km);
        if (e.id == 0) {
            v.put("created", System.currentTimeMillis());
            e.id = getWritableDatabase().insert("expenses", null, v);
        } else {
            getWritableDatabase().update("expenses", v, "_id = ?", new String[]{String.valueOf(e.id)});
        }
    }

    public void setSubmitted(List<Expense> list, boolean submitted) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Expense e : list) {
                ContentValues v = new ContentValues();
                v.put("submitted", submitted ? 1 : 0);
                db.update("expenses", v, "_id = ?", new String[]{String.valueOf(e.id)});
                e.submitted = submitted;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<String> deleteExpense(long id) {
        List<String> files = attachmentFiles("expense", id);
        deleteAttachmentsOf("expense", id);
        getWritableDatabase().delete("expenses", "_id = ?", new String[]{String.valueOf(id)});
        return files;
    }

    /** kind null = all kinds. Dated items in day order, undated ones after, then by creation. */
    public List<TripItem> tripItems(long trip, String kind) {
        List<TripItem> out = new ArrayList<TripItem>();
        String sql = "SELECT _id, trip, kind, subtype, title, body, day, done, created FROM trip_items WHERE trip = ?"
                + (kind != null ? " AND kind = ?" : "") + " ORDER BY CASE WHEN day = 0 THEN 1 ELSE 0 END, day, created";
        Cursor c = getReadableDatabase().rawQuery(sql, kind != null
                ? new String[]{String.valueOf(trip), kind} : new String[]{String.valueOf(trip)});
        try {
            while (c.moveToNext()) {
                TripItem i = new TripItem();
                i.id = c.getLong(0);
                i.trip = c.getLong(1);
                i.kind = c.getString(2);
                i.subtype = c.getString(3);
                i.title = c.getString(4);
                i.body = c.getString(5);
                i.day = c.getLong(6);
                i.done = c.getInt(7) != 0;
                i.created = c.getLong(8);
                out.add(i);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void saveTripItem(TripItem i) {
        ContentValues v = new ContentValues();
        v.put("trip", i.trip);
        v.put("kind", i.kind);
        v.put("subtype", i.subtype);
        v.put("title", i.title);
        v.put("body", i.body);
        v.put("day", i.day);
        v.put("done", i.done ? 1 : 0);
        if (i.id == 0) {
            v.put("created", i.created = System.currentTimeMillis());
            i.id = getWritableDatabase().insert("trip_items", null, v);
        } else {
            getWritableDatabase().update("trip_items", v, "_id = ?", new String[]{String.valueOf(i.id)});
        }
    }

    public List<String> deleteTripItem(long id) {
        List<String> files = attachmentFiles("trip_item", id);
        deleteAttachmentsOf("trip_item", id);
        getWritableDatabase().delete("trip_items", "_id = ?", new String[]{String.valueOf(id)});
        return files;
    }

    // ---- attachments (photos, screenshots, receipts) ----

    public static class Attachment {
        public long id;
        public String file;   // name inside the app's private attachment folder
        public String name;   // original name, for display
    }

    public List<Attachment> attachments(String owner, long ownerId) {
        List<Attachment> out = new ArrayList<Attachment>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id, file, name FROM attachments WHERE owner = ? AND owner_id = ?"
                + " ORDER BY created, _id", new String[]{owner, String.valueOf(ownerId)});
        try {
            while (c.moveToNext()) {
                Attachment a = new Attachment();
                a.id = c.getLong(0);
                a.file = c.getString(1);
                a.name = c.getString(2);
                out.add(a);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int attachmentCount(String owner, long ownerId) {
        return (int) android.database.DatabaseUtils.queryNumEntries(getReadableDatabase(), "attachments",
                "owner = ? AND owner_id = ?", new String[]{owner, String.valueOf(ownerId)});
    }

    private List<String> attachmentFiles(String owner, long ownerId) {
        List<String> files = new ArrayList<String>();
        for (Attachment a : attachments(owner, ownerId)) files.add(a.file);
        return files;
    }

    public void addAttachment(String owner, long ownerId, String file, String name) {
        ContentValues v = new ContentValues();
        v.put("owner", owner);
        v.put("owner_id", ownerId);
        v.put("file", file);
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insert("attachments", null, v);
    }

    public void deleteAttachment(long id) {
        getWritableDatabase().delete("attachments", "_id = ?", new String[]{String.valueOf(id)});
    }

    private void deleteAttachmentsOf(String owner, long ownerId) {
        getWritableDatabase().delete("attachments", "owner = ? AND owner_id = ?", new String[]{owner, String.valueOf(ownerId)});
    }

    public List<String> deleteDiaryWithFiles(long id) {
        List<String> files = attachmentFiles("diary", id);
        deleteAttachmentsOf("diary", id);
        deleteDiary(id);
        return files;
    }

    // ---- activity log ----

    public List<Entry> entries() {
        List<Entry> out = new ArrayList<Entry>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id, text, ts FROM log ORDER BY ts DESC", null);
        try {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(0);
                e.text = c.getString(1);
                e.ts = c.getLong(2);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void addEntry(String text, long ts) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("ts", ts);
        getWritableDatabase().insert("log", null, v);
    }

    public void updateEntry(long id, String text, long ts) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("ts", ts);
        getWritableDatabase().update("log", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteEntry(long id) {
        getWritableDatabase().delete("log", "_id = ?", new String[]{String.valueOf(id)});
    }
}
