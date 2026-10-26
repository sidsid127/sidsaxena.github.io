package com.sidsaxena.daybook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local SQLite storage for tasks and activity-log entries. Nothing leaves the phone. */
public class Db extends SQLiteOpenHelper {

    public static class Task {
        public long id;
        public String title;
        public boolean done;
        public long due;      // millis at local midnight, 0 = no due date
        public long created;
        public long doneAt;
    }

    public static class Entry {
        public long id;
        public String text;
        public long ts;
    }

    public Db(Context context) {
        super(context, "daybook.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,"
                + " done INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0,"
                + " created INTEGER NOT NULL, done_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE log (_id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL,"
                + " ts INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // ---- tasks ----

    /** Open tasks first (soonest due, then newest), then completed ones (most recently done first). */
    public List<Task> tasks() {
        List<Task> out = new ArrayList<Task>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id, title, done, due, created, done_at FROM tasks"
                + " ORDER BY done, CASE WHEN done = 0 AND due > 0 THEN due ELSE 9e18 END,"
                + " done_at DESC, created DESC", null);
        try {
            while (c.moveToNext()) {
                Task t = new Task();
                t.id = c.getLong(0);
                t.title = c.getString(1);
                t.done = c.getInt(2) != 0;
                t.due = c.getLong(3);
                t.created = c.getLong(4);
                t.doneAt = c.getLong(5);
                out.add(t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void addTask(String title, long due) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("due", due);
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insert("tasks", null, v);
    }

    public void updateTask(long id, String title, long due) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("due", due);
        getWritableDatabase().update("tasks", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void setDone(long id, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        v.put("done_at", done ? System.currentTimeMillis() : 0);
        getWritableDatabase().update("tasks", v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteTask(long id) {
        getWritableDatabase().delete("tasks", "_id = ?", new String[]{String.valueOf(id)});
    }

    public int clearDone() {
        return getWritableDatabase().delete("tasks", "done = 1", null);
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
