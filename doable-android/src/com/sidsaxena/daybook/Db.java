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
    }

    /** A previously bought grocery item, offered under "Buy again". */
    public static class Usual {
        public String name;
        public long shop;
    }

    public static class Entry {
        public long id;
        public String text;
        public long ts;
    }

    public Db(Context context) {
        super(context, "daybook.db", null, 3);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN category INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0");
            createCategories(db);
        }
        if (oldVersion < 3) createV3(db);
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
                + " category, priority FROM tasks", null);
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
                out.add(t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void addTask(String title, long due, long category, int priority) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("due", due);
        v.put("category", category);
        v.put("priority", priority);
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insert("tasks", null, v);
    }

    public void updateTask(long id, String title, long due, long category, int priority) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("due", due);
        v.put("category", category);
        v.put("priority", priority);
        updateTask(id, v);
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
                "SELECT _id, body, pinned, modified FROM notes ORDER BY pinned DESC, modified DESC", null);
        try {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(0);
                n.body = c.getString(1);
                n.pinned = c.getInt(2) != 0;
                n.modified = c.getLong(3);
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
                "SELECT _id, name, shop, done, created FROM grocery ORDER BY done, created", null);
        try {
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.name = c.getString(1);
                it.shop = c.getLong(2);
                it.done = c.getInt(3) != 0;
                it.created = c.getLong(4);
                out.add(it);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Adds an item to the list and remembers it (and its shop) for "Buy again". */
    public void addItem(String name, long shop) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("shop", shop);
        v.put("created", now);
        db.insert("grocery", null, v);
        // (No UPSERT: SQLite on older Android versions doesn't support it.)
        db.execSQL("UPDATE grocery_history SET shop = ?, uses = uses + 1, last_used = ? WHERE name = ?",
                new Object[]{shop, now, name});
        if (android.database.DatabaseUtils.longForQuery(db, "SELECT changes()", null) == 0) {
            ContentValues h = new ContentValues();
            h.put("name", name);
            h.put("shop", shop);
            h.put("uses", 1);
            h.put("last_used", now);
            db.insert("grocery_history", null, h);
        }
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
                "SELECT name, shop FROM grocery_history ORDER BY uses DESC, last_used DESC LIMIT 80", null);
        try {
            while (c.moveToNext()) {
                Usual u = new Usual();
                u.name = c.getString(0);
                u.shop = c.getLong(1);
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
