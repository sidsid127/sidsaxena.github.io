package com.sidsaxena.daybook;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One sync round with Google Drive (format and rules: doable/SYNC.md): export the local rows, merge with the
 * document on Drive, write the winners into SQLite, move attachment files, upload the merged document.
 * Everything on Drive is end-to-end encrypted with the key derived from the sync passphrase.
 */
final class SyncEngine {
    private SyncEngine() {}

    static final String DOC = "doable.sync";
    static final String SEALED = "S:";
    private static final String ALIAS = "doable_sync";

    static final class NeedPassphrase extends Exception {
        final SyncCrypto.Header header; // null = the first device: choose one

        NeedPassphrase(SyncCrypto.Header header) {
            super(header == null ? "Choose a sync passphrase" : "Enter your sync passphrase");
            this.header = header;
        }
    }

    static SharedPreferences prefs(Context c) {
        return GoogleDrive.prefs(c);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    /** The sync key (32 bytes), or null when sync isn't set up on this phone. */
    static byte[] syncKey(Context c) {
        String k = prefs(c).getString("key", null);
        if (k == null) return null;
        try {
            return Secrets.unwrap(ALIAS, k);
        } catch (Exception e) {
            return null;
        }
    }

    /** Stores a (new) sync key. Locked notes and the password copy sealed with an old key are re-sealed first. */
    static void storeKey(Context c, byte[] key, byte[] salt, int iterations) throws Exception {
        byte[] old = syncKey(c);
        if (old != null && !java.util.Arrays.equals(old, key)) {
            Db db = new Db(c);
            for (Db.Note note : db.notes()) {
                int at = note.body.indexOf(NotesScreen.LOCK_MARK);
                if (at < 0 || !note.body.startsWith(SEALED, at + NotesScreen.LOCK_MARK.length())) continue;
                byte[] plain = SyncCrypto.open(old, Base64.decode(note.body.substring(at + NotesScreen.LOCK_MARK.length() + SEALED.length()), Base64.NO_WRAP));
                String body = new String(plain, "UTF-8");
                db.updateNote(note.id, NotesScreen.titleOf(body) + "\n" + NotesScreen.LOCK_MARK + SEALED
                        + Base64.encodeToString(SyncCrypto.seal(key, plain), Base64.NO_WRAP));
            }
            JSONObject logins = Vault.readSyncCopy(c, old);
            if (logins != null) Vault.writeSyncCopy(c, key, logins);
        }
        prefs(c).edit().putString("key", Secrets.wrap(ALIAS, key)).putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putInt("iterations", iterations).commit();
    }

    // ---------------------------------------------------------------- locked notes

    /** "S:" + note sealed with the sync key, or null when sync isn't set up (then the note key is used). */
    static String seal(Context c, String body) throws Exception {
        byte[] key = syncKey(c);
        if (key == null) return null;
        return SEALED + Base64.encodeToString(SyncCrypto.seal(key, body.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    static String openSealed(Context c, String sealed) throws Exception {
        byte[] key = syncKey(c);
        if (key == null) throw new IllegalStateException("Sync key missing");
        return new String(SyncCrypto.open(key, Base64.decode(sealed.substring(SEALED.length()), Base64.NO_WRAP)), "UTF-8");
    }

    // ---------------------------------------------------------------- days

    static String dayString(long millis) {
        if (millis == 0) return "";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static long dayMillis(String day) {
        if (day == null || day.length() < 10) return 0;
        try {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(day.substring(0, 4)), Integer.parseInt(day.substring(5, 7)) - 1, Integer.parseInt(day.substring(8, 10)));
            return c.getTimeInMillis();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- export

    private static Map<Long, String> uidsOf(SQLiteDatabase db, String table, Map<String, Map<Long, String>> cache) {
        Map<Long, String> m = cache.get(table);
        if (m != null) return m;
        m = new HashMap<Long, String>();
        Cursor c = db.rawQuery("SELECT _id, uid FROM " + table, null);
        try {
            while (c.moveToNext()) m.put(c.getLong(0), c.getString(1));
        } finally {
            c.close();
        }
        cache.put(table, m);
        return m;
    }

    /** All local rows and tombstones as document tables (without passwords). Locked notes that can't be read are skipped. */
    static JSONObject export(Context ctx, SQLiteDatabase db, List<String> warnings) throws JSONException {
        JSONObject tables = new JSONObject();
        Map<String, Map<Long, String>> cache = new HashMap<String, Map<Long, String>>();
        for (SyncSchema.Table t : SyncSchema.TABLES) {
            JSONObject rows = new JSONObject();
            StringBuilder cols = new StringBuilder("uid, mtime");
            for (String c : t.cols) cols.append(", ").append(c);
            Cursor c = db.rawQuery("SELECT " + cols + " FROM " + t.name + " WHERE uid IS NOT NULL", null);
            try {
                while (c.moveToNext()) {
                    JSONObject r = new JSONObject();
                    boolean skip = false;
                    for (int i = 0; i < t.cols.length; i++) {
                        String col = t.cols[i], kind = t.kinds[i];
                        int at = i + 2;
                        if (kind.equals("T")) r.put(col, c.isNull(at) ? "" : c.getString(at));
                        else if (kind.equals("I")) r.put(col, c.getLong(at));
                        else if (kind.equals("R")) r.put(col, c.getDouble(at));
                        else if (kind.equals("D")) r.put(col, dayString(c.getLong(at)));
                        else if (kind.startsWith("F:")) {
                            long id = c.getLong(at);
                            String uid = id == 0 ? null : uidsOf(db, kind.substring(2), cache).get(id);
                            r.put(col, uid == null ? "" : uid);
                        } else if (kind.equals("O")) {
                            String owner = SyncSchema.ownerTable(r.optString("owner"));
                            String uid = owner == null ? null : uidsOf(db, owner, cache).get(c.getLong(at));
                            r.put(col, uid == null ? "" : uid);
                        }
                    }
                    if (t.name.equals("notes") && r.optLong("locked") == 1) {
                        String body = r.optString("body");
                        int mark = body.indexOf(NotesScreen.LOCK_MARK);
                        String sealed = mark < 0 ? null : body.substring(mark + NotesScreen.LOCK_MARK.length());
                        try {
                            if (sealed == null) skip = false;
                            else if (sealed.startsWith(SEALED)) r.put("body", openSealed(ctx, sealed));
                            else skip = true; // locked before sync was turned on and not converted yet
                        } catch (Exception e) {
                            skip = true;
                        }
                        if (skip) warnings.add("A locked note couldn't be synced");
                    }
                    if (!skip) rows.put(c.getString(0), new JSONObject().put("m", c.getLong(1)).put("r", r));
                }
            } finally {
                c.close();
            }
            tables.put(t.name, rows);
        }
        Cursor c = db.rawQuery("SELECT tbl, uid, mtime FROM tombstones", null);
        try {
            while (c.moveToNext()) {
                JSONObject rows = tables.optJSONObject(c.getString(0));
                if (rows != null && !rows.has(c.getString(1))) rows.put(c.getString(1), new JSONObject().put("m", c.getLong(2)).put("d", 1));
            }
        } finally {
            c.close();
        }
        return tables;
    }

    // ---------------------------------------------------------------- apply

    private static long idOf(SQLiteDatabase db, String table, String uid) {
        if (uid == null || uid.isEmpty()) return 0;
        Cursor c = db.rawQuery("SELECT _id FROM " + table + " WHERE uid = ?", new String[]{uid});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double asDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return o == null ? 0 : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Writes merged rows into SQLite (references by uid → local _id). Triggers keep the incoming times while
     * sync_state.applying = 1. Returns attachment files whose rows were deleted.
     */
    static List<String> apply(Context ctx, SQLiteDatabase db, List<Object[]> toApply) throws Exception {
        List<String> goneFiles = new ArrayList<String>();
        if (toApply.isEmpty()) return goneFiles;
        Map<String, List<Object[]>> byTable = new HashMap<String, List<Object[]>>();
        for (Object[] a : toApply) {
            List<Object[]> l = byTable.get(a[0]);
            if (l == null) byTable.put((String) a[0], l = new ArrayList<Object[]>());
            l.add(a);
        }
        long groceries = idOf(db, "grocery_lists", "seed-list-groceries");
        db.beginTransaction();
        try {
            db.execSQL("UPDATE sync_state SET applying = 1");
            for (SyncSchema.Table t : SyncSchema.TABLES) {
                List<Object[]> list = byTable.get(t.name);
                if (list == null) continue;
                for (Object[] a : list) {
                    String uid = (String) a[1];
                    JSONObject e = (JSONObject) a[2];
                    long m = e.optLong("m");
                    long id = idOf(db, t.name, uid);
                    if (e.optInt("d") == 1) {
                        if (id != 0) {
                            if (t.name.equals("attachments")) {
                                Cursor c = db.rawQuery("SELECT file FROM attachments WHERE _id = ?", new String[]{String.valueOf(id)});
                                try {
                                    if (c.moveToFirst()) goneFiles.add(c.getString(0));
                                } finally {
                                    c.close();
                                }
                            }
                            db.delete(t.name, "_id = ?", new String[]{String.valueOf(id)});
                        }
                        db.execSQL("INSERT OR REPLACE INTO tombstones (tbl, uid, mtime) VALUES (?, ?, ?)", new Object[]{t.name, uid, m});
                        continue;
                    }
                    JSONObject r = e.optJSONObject("r");
                    if (r == null) continue;
                    ContentValues v = new ContentValues();
                    for (int i = 0; i < t.cols.length; i++) {
                        String col = t.cols[i], kind = t.kinds[i];
                        Object val = r.opt(col);
                        if (kind.equals("T")) v.put(col, val == null || val == JSONObject.NULL ? "" : String.valueOf(val));
                        else if (kind.equals("I")) v.put(col, asLong(val));
                        else if (kind.equals("R")) v.put(col, asDouble(val));
                        else if (kind.equals("D")) v.put(col, dayMillis(r.optString(col)));
                        else if (kind.startsWith("F:")) {
                            long ref = idOf(db, kind.substring(2), r.optString(col));
                            if (ref == 0 && col.equals("list") && t.name.startsWith("grocery")) ref = groceries > 0 ? groceries : 1;
                            v.put(col, ref);
                        } else if (kind.equals("O")) {
                            String owner = SyncSchema.ownerTable(r.optString("owner"));
                            v.put(col, owner == null ? 0 : idOf(db, owner, r.optString(col)));
                        }
                    }
                    if (t.name.equals("notes") && r.optLong("locked") == 1) {
                        String body = r.optString("body");
                        String sealed = seal(ctx, body);
                        v.put("body", NotesScreen.titleOf(body) + "\n" + NotesScreen.LOCK_MARK + sealed);
                    }
                    v.put("mtime", m);
                    if (id != 0) {
                        db.update(t.name, v, "_id = ?", new String[]{String.valueOf(id)});
                    } else {
                        v.put("uid", uid);
                        // Rows unique by content (diary day, history name, habit day) replace a local twin.
                        db.insertWithOnConflict(t.name, null, v, t.keyedUid != null ? SQLiteDatabase.CONFLICT_REPLACE : SQLiteDatabase.CONFLICT_ABORT);
                    }
                    db.execSQL("DELETE FROM tombstones WHERE tbl = ? AND uid = ?", new Object[]{t.name, uid});
                }
            }
            // Sub-categories whose parent arrived in the same round.
            List<Object[]> cats = byTable.get("categories");
            if (cats != null) {
                for (Object[] a : cats) {
                    JSONObject r = ((JSONObject) a[2]).optJSONObject("r");
                    if (r == null || r.optString("parent").isEmpty()) continue;
                    db.execSQL("UPDATE categories SET parent = ? WHERE uid = ?", new Object[]{idOf(db, "categories", r.optString("parent")), a[1]});
                }
            }
            db.execSQL("UPDATE sync_state SET applying = 0");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return goneFiles;
    }

    // ---------------------------------------------------------------- a round

    /** Comparable summary of a document: uid → m (and d) per table, plus the file list. */
    private static String fingerprint(JSONObject tables, JSONArray files) {
        StringBuilder sb = new StringBuilder();
        TreeSet<String> names = new TreeSet<String>();
        for (Iterator<String> it = tables.keys(); it.hasNext(); ) names.add(it.next());
        for (String n : names) {
            JSONObject rows = tables.optJSONObject(n);
            if (rows == null) continue;
            TreeSet<String> uids = new TreeSet<String>();
            for (Iterator<String> it = rows.keys(); it.hasNext(); ) uids.add(it.next());
            if (uids.isEmpty()) continue;
            sb.append(n).append('{');
            for (String u : uids) {
                JSONObject e = rows.optJSONObject(u);
                sb.append(u).append(':').append(e.optLong("m")).append(e.optInt("d") == 1 ? "d" : "").append(',');
            }
            sb.append('}');
        }
        TreeSet<String> f = new TreeSet<String>();
        for (int i = 0; files != null && i < files.length(); i++) f.add(files.optString(i));
        return sb.append(f).toString();
    }

    private static byte[] readFile(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
        try {
            in.readFully(data);
        } finally {
            in.close();
        }
        return data;
    }

    private static boolean running;

    /**
     * Runs one sync round (background thread). Returns true if local data changed. Throws NeedPassphrase when the
     * passphrase must be entered, GoogleDrive.AuthException when Google sign-in is needed.
     */
    static boolean run(Context ctx) throws Exception {
        synchronized (SyncEngine.class) {
            if (running) return false;
            running = true;
        }
        try {
            return round(ctx.getApplicationContext());
        } catch (Exception e) {
            if (!(e instanceof NeedPassphrase)) prefs(ctx).edit().putString("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).apply();
            throw e;
        } finally {
            synchronized (SyncEngine.class) {
                running = false;
            }
        }
    }

    private static boolean round(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        GoogleDrive drive = new GoogleDrive(ctx);
        drive.list();
        byte[] bytes = drive.download(DOC);
        byte[] key = syncKey(ctx);
        JSONObject remote = new JSONObject();
        if (bytes != null) {
            SyncCrypto.Header h = SyncCrypto.header(bytes);
            if (h == null) throw new IOException("The sync file on Google Drive is damaged");
            String salt = Base64.encodeToString(h.salt, Base64.NO_WRAP);
            if (key == null || !salt.equals(p.getString("salt", ""))) throw new NeedPassphrase(h);
            try {
                remote = new JSONObject(SyncCrypto.decryptDoc(key, bytes));
            } catch (java.security.GeneralSecurityException e) {
                throw new NeedPassphrase(h);
            }
        } else if (key == null) {
            throw new NeedPassphrase(null);
        }
        JSONObject remoteTables = remote.optJSONObject("tables");
        if (remoteTables == null) remoteTables = new JSONObject();
        JSONObject remoteLogins = remoteTables.optJSONObject("logins");
        remoteTables.remove("logins");

        Db helper = new Db(ctx);
        SQLiteDatabase db = helper.getWritableDatabase();
        List<String> warnings = new ArrayList<String>();
        SyncSchema.Merged merged = SyncSchema.merge(export(ctx, db, warnings), remoteTables, System.currentTimeMillis());
        List<String> gone = apply(ctx, db, merged.toApply);
        boolean changed = !merged.toApply.isEmpty();

        // Passwords travel through the sealed copy, so this works while the vault is locked.
        JSONObject localLogins = Vault.readSyncCopy(ctx, key);
        JSONObject lin = new JSONObject().put("logins", localLogins == null ? new JSONObject() : localLogins);
        JSONObject rin = new JSONObject().put("logins", remoteLogins == null ? new JSONObject() : remoteLogins);
        JSONObject logins = SyncSchema.merge(lin, rin, System.currentTimeMillis()).tables.getJSONObject("logins");
        Vault.writeSyncCopy(ctx, key, logins);
        merged.tables.put("logins", logins);

        // Attachment files.
        Set<String> used = new HashSet<String>();
        JSONObject atts = merged.tables.optJSONObject("attachments");
        for (Iterator<String> it = atts == null ? new ArrayList<String>().iterator() : atts.keys(); it.hasNext(); ) {
            JSONObject e = atts.getJSONObject(it.next());
            if (e.optInt("d") != 1 && e.optJSONObject("r") != null) used.add(e.getJSONObject("r").optString("file"));
        }
        Set<String> files = new TreeSet<String>();
        JSONArray rf = remote.optJSONArray("files");
        for (int i = 0; rf != null && i < rf.length(); i++) files.add(rf.getString(i));
        for (String f : used) {
            File local = Attachments.file(ctx, f);
            boolean onDrive = drive.ids.containsKey("att-" + f);
            if (!onDrive && local.exists()) {
                drive.upload("att-" + f, SyncCrypto.encryptFile(key, f, readFile(local)));
                files.add(f);
            } else if (onDrive) {
                files.add(f);
                if (local.exists()) continue;
                byte[] enc = drive.download("att-" + f);
                if (enc != null) {
                    File tmp = new File(local.getPath() + ".part");
                    FileOutputStream out = new FileOutputStream(tmp);
                    try {
                        out.write(SyncCrypto.decryptFile(key, f, enc));
                    } finally {
                        out.close();
                    }
                    if (!tmp.renameTo(local)) tmp.delete();
                }
            }
        }
        for (String f : new ArrayList<String>(files)) {
            if (!used.contains(f)) {
                files.remove(f);
                try {
                    drive.delete("att-" + f);
                } catch (Exception ignored) {
                }
            } else if (!drive.ids.containsKey("att-" + f)) {
                files.remove(f);
            }
        }
        Attachments.delete(ctx, gone);

        JSONArray fileList = new JSONArray();
        for (String f : files) fileList.put(f);
        JSONObject remoteAll = remote.optJSONObject("tables");
        if (remoteAll == null) remoteAll = new JSONObject();
        if (remoteLogins != null) remoteAll.put("logins", remoteLogins);
        if (bytes == null || !fingerprint(merged.tables, fileList).equals(fingerprint(remoteAll, rf))) {
            JSONObject doc = new JSONObject().put("format", 1).put("updated", System.currentTimeMillis())
                    .put("by", Build.MODEL).put("tables", merged.tables).put("files", fileList);
            byte[] salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP);
            drive.upload(DOC, SyncCrypto.encryptDoc(key, salt, p.getInt("iterations", SyncCrypto.ROUNDS), doc.toString()));
        }
        p.edit().putLong("last", System.currentTimeMillis()).putString("error", warnings.isEmpty() ? "" : warnings.get(0)).apply();
        if (changed) Reminders.refresh(ctx);
        return changed;
    }

    /** Derives and stores the key; with a header (other devices) it is checked against the document. */
    static void setPassphrase(Context ctx, char[] passphrase, SyncCrypto.Header header) throws Exception {
        byte[] salt = header != null ? header.salt : SyncCrypto.random(16);
        int iterations = header != null ? header.iterations : SyncCrypto.ROUNDS;
        byte[] key = SyncCrypto.pbkdf2(passphrase, salt, iterations);
        if (header != null) {
            GoogleDrive drive = new GoogleDrive(ctx);
            drive.list();
            byte[] doc = drive.download(DOC);
            if (doc != null) SyncCrypto.decryptDoc(key, doc); // throws on a wrong passphrase
        }
        storeKey(ctx, key, salt, iterations);
    }

    /** Re-seals locked notes from the note key to the sync key (needs the note key open, i.e. a fingerprint). */
    static int convertLockedNotes(Context ctx, Db db, SecureBox box) throws Exception {
        int n = 0;
        for (Db.Note note : db.notes()) {
            int at = note.body.indexOf(NotesScreen.LOCK_MARK);
            if (!note.locked || at < 0) continue;
            String sealed = note.body.substring(at + NotesScreen.LOCK_MARK.length());
            if (sealed.startsWith(SEALED)) continue;
            String plain = box.decrypt(sealed);
            db.updateNote(note.id, NotesScreen.titleOf(plain) + "\n" + NotesScreen.LOCK_MARK + seal(ctx, plain));
            n++;
        }
        return n;
    }

    static boolean hasUnconvertedLockedNotes(Db db) {
        for (Db.Note note : db.notes()) {
            int at = note.body.indexOf(NotesScreen.LOCK_MARK);
            if (note.locked && at >= 0 && !note.body.startsWith(SEALED, at + NotesScreen.LOCK_MARK.length())) return true;
        }
        return false;
    }

    /**
     * Turns sync off on this phone (keeps the data; other devices keep syncing). The key stays: locked notes are
     * sealed with it.
     */
    static void turnOff(Context ctx) {
        prefs(ctx).edit().putBoolean("enabled", false).remove("error").commit();
        GoogleDrive.signOut(ctx);
    }
}
