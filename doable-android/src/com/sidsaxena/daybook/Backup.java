package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * One encrypted file with everything: the database, photos/scans/voice notes, settings, and the keys of
 * the password vault and locked notes (so they can be opened on a new phone). The file is a zip inside
 * {@link BackupCrypto}; nothing ever leaves the phone unless the user saves the file somewhere.
 */
final class Backup {
    private Backup() {}

    private static final String DB = "daybook.db";

    // ---------------------------------------------------------------- UI

    static void show(final MainActivity a) {
        long last = a.prefs.getLong("lastBackup", 0);
        LinearLayout box = Util.dialogBox(a);
        TextView intro = new TextView(a);
        intro.setText("A backup is one encrypted file with all your data: tasks, notes, diary, trips, receipts and photos, "
                + "passwords, wallet and settings. Doable never uploads anything — you choose where the file goes. "
                + "Keep a copy somewhere other than this phone (PC, USB stick or a folder your cloud app syncs).\n\n"
                + "New phone? Install Doable there, open Backup & restore → Restore, and pick the file.");
        intro.setTextColor(Util.color(a, R.color.text));
        box.addView(intro);
        TextView status = new TextView(a);
        String auto = AutoBackup.status(a);
        status.setText(auto + (last == 0 ? "" : "\nLast backup in a file you chose: " + Util.fullTimestamp(a, last)));
        status.setTextColor(Util.color(a, AutoBackup.hasProblem(a) ? R.color.overdue : R.color.muted));
        status.setPadding(0, Util.dp(a, 12), 0, Util.dp(a, 4));
        box.addView(status);
        final AlertDialog[] dlg = new AlertDialog[1];
        action(a, box, "⏱  Automatic backup…", new Runnable() {
            public void run() { dlg[0].dismiss(); AutoBackup.setup(a); }
        });
        action(a, box, "💾  Back up now", new Runnable() {
            public void run() { dlg[0].dismiss(); AutoBackup.backupNow(a); }
        });
        action(a, box, AutoBackup.folderLabel(a), new Runnable() {
            public void run() { dlg[0].dismiss(); AutoBackup.changeFolder(a); }
        });
        action(a, box, "↩  Restore from a backup", new Runnable() {
            public void run() { dlg[0].dismiss(); pickRestore(a); }
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(a)
                .setTitle("Backup & restore")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();
    }

    private static void action(Context c, LinearLayout box, String text, final Runnable run) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(Util.color(c, R.color.primary_text));
        t.setPadding(Util.dp(c, 4), Util.dp(c, 14), Util.dp(c, 4), Util.dp(c, 14));
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        t.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { run.run(); }
        });
        box.addView(t);
    }

    static EditText password(Context c, String hint) {
        EditText f = new EditText(c);
        f.setHint(hint);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return f;
    }

    interface Secrets {
        /** Keys are null when there are no passwords / locked notes. */
        void got(byte[] vaultKey, boolean vaultAuth, byte[] notesKey);
    }

    /** Passwords and locked notes are protected by the phone's Keystore; their keys need a fingerprint first. */
    static void collectSecrets(final MainActivity a, final Secrets result) {
        final Vault vault = new Vault(a);
        final SecureBox box = new SecureBox(a);
        boolean needAuth = (vault.exists() && vault.requiresAuth()) || box.exists();
        final Runnable go = new Runnable() {
            public void run() {
                byte[] vk = null, nk = null;
                boolean vAuth = false;
                try {
                    if (vault.exists()) {
                        vault.open();
                        vk = vault.rawKey();
                        vAuth = vault.requiresAuth();
                        vault.close();
                    }
                    if (box.exists()) {
                        box.open();
                        nk = box.rawKey();
                        box.close();
                    }
                } catch (Exception e) {
                    Toast.makeText(a, "Couldn't unlock passwords / locked notes for the backup. Please try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                result.got(vk, vAuth, nk);
            }
        };
        if (!needAuth) {
            go.run();
            return;
        }
        a.confirmUser("Include passwords & locked notes", true, new MainActivity.AuthResult() {
            public void done(boolean ok, int error) {
                if (ok) go.run();
            }
        });
    }

    /** A consistent copy of the database, taken on the UI thread so nothing changes meanwhile. */
    static File snapshotDb(MainActivity a, String name) throws IOException {
        SQLiteDatabase db = a.db.getWritableDatabase();
        Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null);
        c.moveToFirst();
        c.close();
        File out = new File(a.getCacheDir(), name);
        copy(new FileInputStream(a.getDatabasePath(DB)), new FileOutputStream(out));
        return out;
    }

    static void write(Context c, OutputStream raw, char[] pw, File dbCopy, byte[] vaultKey, boolean vaultAuth, byte[] notesKey)
            throws Exception {
        OutputStream enc = BackupCrypto.encrypt(raw, pw);
        ZipOutputStream zip = new ZipOutputStream(enc);
        try {
            JSONObject meta = new JSONObject();
            meta.put("app", "Doable");
            meta.put("format", 1);
            meta.put("created", System.currentTimeMillis());
            if (vaultKey != null) {
                meta.put("vaultKey", Base64.encodeToString(vaultKey, Base64.NO_WRAP));
                meta.put("vaultAuth", vaultAuth);
            }
            if (notesKey != null) meta.put("notesKey", Base64.encodeToString(notesKey, Base64.NO_WRAP));
            putBytes(zip, "meta.json", meta.toString().getBytes("UTF-8"));
            putBytes(zip, "prefs.json", prefsJson(c.getSharedPreferences("daybook", Context.MODE_PRIVATE)).getBytes("UTF-8"));
            putFile(zip, DB, dbCopy);
            File vault = new File(c.getFilesDir(), "vault.bin");
            if (vaultKey != null && vault.exists()) putFile(zip, "vault.bin", vault);
            File[] att = Attachments.dir(c).listFiles();
            if (att != null) for (File f : att) if (f.isFile()) putFile(zip, "att/" + f.getName(), f);
        } finally {
            zip.close();  // also finishes the encrypted stream
        }
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        } finally {
            in.close();
        }
        zip.closeEntry();
    }

    private static String prefsJson(SharedPreferences p) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Object v = e.getValue();
            String type = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : v instanceof String ? "s" : null;
            if (type == null) continue;  // string sets are not used
            o.put(type + ":" + e.getKey(), v);
        }
        return o.toString();
    }

    // ---------------------------------------------------------------- restore

    private static void pickRestore(final MainActivity a) {
        a.openDocument(new String[]{"*/*"}, new MainActivity.UriPicked() {
            public void onUri(final Uri uri) {
                final EditText pw = password(a, "Backup password");
                new AlertDialog.Builder(a)
                        .setTitle("Password of this backup")
                        .setView(Util.wrap(a, pw))
                        .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                unpack(a, uri, pw.getText().toString().toCharArray());
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                Util.showKeyboard(pw);
            }
        });
    }

    private static File staging(Context c) {
        return new File(c.getFilesDir(), "restore");
    }

    private static void unpack(final MainActivity a, final Uri uri, final char[] pw) {
        Toast.makeText(a, "Checking backup…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                JSONObject meta = null;
                File dir = staging(a);
                deleteTree(dir);
                try {
                    InputStream raw = a.getContentResolver().openInputStream(uri);
                    if (raw == null) throw new IOException("Cannot open the file");
                    meta = read(raw, pw, dir);
                } catch (BackupCrypto.WrongPassword e) {
                    error = "Wrong password, or the file is damaged.";
                } catch (Exception e) {
                    error = "This is not a Doable backup or it is damaged (" + e.getClass().getSimpleName() + ").";
                }
                if (error != null) deleteTree(dir);
                final String err = error;
                final JSONObject m = meta;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (err != null) Toast.makeText(a, err, Toast.LENGTH_LONG).show();
                        else confirmRestore(a, m);
                    }
                });
            }
        }).start();
    }

    /** Decrypts and unzips into {@code dir}; returns the backup's meta data. Reads to the end so a cut-off file fails. */
    static JSONObject read(InputStream raw, char[] pw, File dir) throws Exception {
        InputStream in = BackupCrypto.decrypt(raw, pw);
        JSONObject meta = null;
        boolean hasDb = false;
        try {
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.matches("(meta\\.json|prefs\\.json|daybook\\.db|vault\\.bin|att/[A-Za-z0-9._-]+)") || name.contains("..")) continue;
                File out = new File(dir, name);
                out.getParentFile().mkdirs();
                OutputStream os = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = zip.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    os.close();
                }
                if (name.equals(DB)) hasDb = true;
            }
            while (in.read(buf) >= 0) {
                // drain: authenticates the final chunk
            }
            meta = new JSONObject(new String(readAll(new File(dir, "meta.json")), "UTF-8"));
        } finally {
            in.close();
        }
        if (!"Doable".equals(meta.optString("app")) || !hasDb) throw new IOException("not a Doable backup");
        return meta;
    }

    private static void confirmRestore(final MainActivity a, final JSONObject meta) {
        long created = meta.optLong("created");
        new AlertDialog.Builder(a)
                .setTitle("Restore this backup?")
                .setMessage("Backup from " + Util.fullTimestamp(a, created) + ".\n\nEverything currently in Doable on this phone "
                        + "will be replaced by the backup. This can't be undone.")
                .setPositiveButton("Replace & restore", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        boolean secrets = meta.has("vaultKey") || meta.has("notesKey");
                        boolean secure = ((KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE)).isDeviceSecure();
                        if (secrets && secure) {
                            a.confirmUser("Restore passwords & locked notes", true, new MainActivity.AuthResult() {
                                public void done(boolean ok, int error) { apply(a, meta, ok); }
                            });
                        } else {
                            apply(a, meta, false);
                        }
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { deleteTree(staging(a)); }
                })
                .show();
    }

    /** Swaps in the restored files. {@code authed}: the user just confirmed, so Keystore keys can be used. */
    private static void apply(MainActivity a, JSONObject meta, boolean authed) {
        File dir = staging(a);
        StringBuilder notes = new StringBuilder();
        try {
            // Database: copied next to the old one, then swapped in (a new file, so stray connections can't touch it)
            a.beforeRestore();
            a.db.close();
            File dbFile = a.getDatabasePath(DB);
            File fresh = new File(dbFile.getPath() + ".new");
            copy(new FileInputStream(new File(dir, DB)), new FileOutputStream(fresh));
            for (String suffix : new String[]{"-wal", "-shm", "-journal"}) new File(dbFile.getPath() + suffix).delete();
            if (!fresh.renameTo(dbFile)) throw new IOException("Could not replace the database");

            // Photos, scans, receipts, voice notes
            File att = Attachments.dir(a);
            File[] old = att.listFiles();
            if (old != null) for (File f : old) f.delete();
            File[] restored = new File(dir, "att").listFiles();
            if (restored != null) {
                for (File f : restored) {
                    File to = new File(att, f.getName());
                    if (!f.renameTo(to)) copy(new FileInputStream(f), new FileOutputStream(to));
                }
            }

            // Settings
            File prefs = new File(dir, "prefs.json");
            if (prefs.exists()) restorePrefs(a.prefs, new JSONObject(new String(readAll(prefs), "UTF-8")));

            // Passwords
            if (meta.has("vaultKey") && new File(dir, "vault.bin").exists()) {
                byte[] key = Base64.decode(meta.getString("vaultKey"), Base64.NO_WRAP);
                Vault v = new Vault(a);
                v.reset();
                File vf = new File(a.getFilesDir(), "vault.bin");
                copy(new FileInputStream(new File(dir, "vault.bin")), new FileOutputStream(vf));
                boolean auth = meta.optBoolean("vaultAuth", true);
                try {
                    v.adoptKey(key, auth && authed);
                } catch (Exception e) {
                    v.adoptKey(key, false);
                }
                if (auth && !authed) notes.append("Passwords were restored without fingerprint protection; turn on "
                        + "\"Require fingerprint\" in Passwords.\n");
            }

            // Locked notes
            if (meta.has("notesKey")) {
                byte[] key = Base64.decode(meta.getString("notesKey"), Base64.NO_WRAP);
                boolean adopted = false;
                if (authed) {
                    try {
                        new SecureBox(a).adoptKey(key);
                        adopted = true;
                    } catch (Exception ignored) {
                    }
                }
                if (!adopted) {
                    int n = unlockNotes(a, key);
                    if (n > 0) notes.append(n).append(" locked note(s) were unlocked because they couldn't be "
                            + "re-locked on this phone. Lock them again in Notes.\n");
                }
            }
        } catch (Exception e) {
            notes.append("Something went wrong while restoring: ").append(e.getMessage()).append('\n');
        } finally {
            deleteTree(dir);
        }
        Reminders.refresh(a);
        final String msg = notes.toString().trim();
        a.restarted(msg.isEmpty() ? "Backup restored ✓" : "Backup restored.\n\n" + msg);
    }

    private static void restorePrefs(SharedPreferences p, JSONObject o) throws Exception {
        SharedPreferences.Editor e = p.edit();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k.length() < 3 || k.charAt(1) != ':') continue;
            String name = k.substring(2);
            if (name.equals("lastFired")) continue;
            switch (k.charAt(0)) {
                case 'b': e.putBoolean(name, o.getBoolean(k)); break;
                case 'i': e.putInt(name, o.getInt(k)); break;
                case 'l': e.putLong(name, o.getLong(k)); break;
                case 'f': e.putFloat(name, (float) o.getDouble(k)); break;
                case 's': e.putString(name, o.getString(k)); break;
                default: break;
            }
        }
        e.commit();
    }

    /** Decrypts locked notes in the restored database with the backup's key and stores them as normal notes. */
    private static int unlockNotes(MainActivity a, byte[] key) {
        int n = 0;
        for (Db.Note note : a.db.notes()) {
            int at = note.body.indexOf(NotesScreen.LOCK_MARK);
            if (at < 0) continue;
            try {
                String plain = SecureBox.decryptWith(key, note.body.substring(at + NotesScreen.LOCK_MARK.length()));
                a.db.updateNote(note.id, plain);
                note.locked = false;
                note.body = plain;
                a.db.saveNoteMeta(note);
                n++;
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- files

    static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        copy(new FileInputStream(f), out);
        return out.toByteArray();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
