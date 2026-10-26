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

    public static class Entry {
        public long id;
        public String text;
        public long ts;
    }

    public Db(Context context) {
        super(context, "daybook.db", null, 2);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN category INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0");
            createCategories(db);
        }
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
