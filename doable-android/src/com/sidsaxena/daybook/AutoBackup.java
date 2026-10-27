package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;


/**
 * Automatic backups: daily, weekly or monthly into a folder the user picked once. Each run writes
 * "Doable backup.doable" there, replacing the previous one (written to a temporary file first, so a failed
 * run never destroys the last good backup). Runs when Doable goes to the background and a backup is due.
 * The backup password (and the keys for passwords / locked notes) are kept wrapped by a Keystore key.
 */
final class AutoBackup {
    private AutoBackup() {}

    static final String FILE = "Doable backup.doable";
    private static final String TMP = "Doable backup.doable.tmp";
    private static final String ALIAS = "doable_autobackup";
    private static final long DAY = 86400000L;
    private static final String[] NAMES = {"Off", "Daily", "Weekly", "Monthly"};
    private static final int[] DAYS = {0, 1, 7, 30};

    private static volatile boolean running;

    static SharedPreferences prefs(Context c) {
        // Separate file: it isn't copied into backups (the folder and wrapped password only work on this phone).
        return c.getSharedPreferences("autobackup", Context.MODE_PRIVATE);
    }

    private static boolean configured(Context c) {
        SharedPreferences p = prefs(c);
        return p.contains("tree") && p.contains("secret");
    }

    static boolean hasProblem(Context c) {
        return prefs(c).getInt("days", 0) > 0 && (prefs(c).contains("error") || prefs(c).contains("note"));
    }

    static String status(Context c) {
        SharedPreferences p = prefs(c);
        int days = p.getInt("days", 0);
        if (days == 0 || !configured(c)) return "Automatic backup: off.";
        String freq = days == 1 ? "daily" : days == 7 ? "weekly" : "monthly";
        long last = p.getLong("last", 0);
        StringBuilder sb = new StringBuilder("Automatic backup: " + freq + " to the folder \"" + p.getString("folder", "?") + "\""
                + " (file \"" + FILE + "\").\nLast automatic backup: " + (last == 0 ? "not yet" : Util.fullTimestamp(c, last)) + ".");
        if (p.contains("error")) sb.append("\n⚠ Last attempt failed: ").append(p.getString("error", ""));
        if (p.contains("note")) sb.append("\n⚠ ").append(p.getString("note", ""));
        return sb.toString();
    }

    // ---------------------------------------------------------------- setup

    static void setup(final MainActivity a) {
        final SharedPreferences p = prefs(a);
        int days = configured(a) ? p.getInt("days", 0) : 0;
        int checked = 0;
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i] == days) checked = i;
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Back up automatically")
                .setSingleChoiceItems(NAMES, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (DAYS[which] == 0) {
                            p.edit().putInt("days", 0).apply();
                            Toast.makeText(a, "Automatic backup turned off", Toast.LENGTH_SHORT).show();
                        } else if (configured(a)) {
                            p.edit().putInt("days", DAYS[which]).apply();
                            Toast.makeText(a, "Backing up " + NAMES[which].toLowerCase() + " to \"" + p.getString("folder", "") + "\"",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            fullSetup(a, DAYS[which]);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        if (configured(a)) {
            b.setNeutralButton("Change folder / password", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    fullSetup(a, Math.max(1, p.getInt("days", 7)));
                }
            });
        }
        b.show();
    }

    /** Password → fingerprint (to include passwords & locked notes) → folder → first backup right away. */
    private static void fullSetup(final MainActivity a, final int days) {
        LinearLayout box = Util.dialogBox(a);
        TextView info = new TextView(a);
        info.setText("Choose a password for your automatic backups (at least 8 characters). Doable remembers it, "
                + "locked in this phone's secure hardware, so it can back up without asking. You'll need it to restore — "
                + "write it down somewhere safe.");
        info.setTextColor(Util.color(a, R.color.muted));
        box.addView(info);
        final EditText p1 = Backup.password(a, "Backup password");
        final EditText p2 = Backup.password(a, "Repeat password");
        box.addView(p1);
        box.addView(p2);
        new AlertDialog.Builder(a)
                .setTitle("Automatic backup")
                .setView(box)
                .setPositiveButton("Next: choose folder", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final String pw = p1.getText().toString();
                        if (pw.length() < 8) {
                            Toast.makeText(a, "Password too short (min. 8 characters)", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!pw.equals(p2.getText().toString())) {
                            Toast.makeText(a, "The passwords don't match", Toast.LENGTH_LONG).show();
                            return;
                        }
                        Backup.collectSecrets(a, new Backup.Secrets() {
                            public void got(final byte[] vaultKey, final boolean vaultAuth, final byte[] notesKey) {
                                Toast.makeText(a, "Pick or create a folder for the backups, e.g. \"Documents/Doable\"", Toast.LENGTH_LONG).show();
                                a.openFolder(new MainActivity.UriPicked() {
                                    public void onUri(Uri tree) { save(a, days, tree, pw, vaultKey, vaultAuth, notesKey); }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        Util.showKeyboard(p1);
    }

    private static void save(MainActivity a, int days, Uri tree, String pw, byte[] vk, boolean vAuth, byte[] nk) {
        try {
            JSONObject s = new JSONObject();
            s.put("pw", pw);
            if (vk != null) {
                s.put("vk", Base64.encodeToString(vk, Base64.NO_WRAP));
                s.put("vAuth", vAuth);
            }
            if (nk != null) s.put("nk", Base64.encodeToString(nk, Base64.NO_WRAP));
            prefs(a).edit()
                    .putString("tree", tree.toString())
                    .putString("folder", folderName(a.getContentResolver(), tree))
                    .putString("secret", wrap(s.toString()))
                    .putInt("days", days)
                    .remove("error").remove("note").remove("last")
                    .commit();
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't set up automatic backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        start(a, true);
    }

    // ---------------------------------------------------------------- running

    /** Called when the app goes to the background. */
    static void runIfDue(MainActivity a) {
        SharedPreferences p = prefs(a);
        int days = p.getInt("days", 0);
        if (days == 0 || !configured(a)) return;
        // A little slack, so "daily" doesn't slip a day when the app is opened at a slightly earlier time.
        if (System.currentTimeMillis() - p.getLong("last", 0) < days * DAY - 3 * 3600000L) return;
        start(a, false);
    }

    private static void start(final MainActivity a, final boolean tell) {
        if (running) return;
        final File snapshot;
        try {
            snapshot = Backup.snapshotDb(a, "autobackup.db");
        } catch (Exception e) {
            prefs(a).edit().putString("error", "database copy failed").apply();
            return;
        }
        running = true;
        if (tell) Toast.makeText(a, "Making the first backup…", Toast.LENGTH_SHORT).show();
        final Context c = a.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                try {
                    perform(c, snapshot);
                } catch (Exception e) {
                    error = e instanceof SecurityException ? "no access to the folder any more — choose it again"
                            : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                } finally {
                    snapshot.delete();
                    running = false;
                }
                SharedPreferences.Editor ed = prefs(c).edit();
                if (error == null) ed.putLong("last", System.currentTimeMillis()).remove("error");
                else ed.putString("error", error);
                ed.apply();
                if (!tell) return;
                final String err = error;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        new AlertDialog.Builder(a)
                                .setTitle(err == null ? "Automatic backup is on ✓" : "Backup failed")
                                .setMessage(err == null ? status(a) : err)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private static void perform(Context c, File snapshot) throws Exception {
        SharedPreferences p = prefs(c);
        JSONObject s = new JSONObject(unwrap(p.getString("secret", "")));
        char[] pw = s.getString("pw").toCharArray();
        String note = null;

        byte[] vk = s.has("vk") ? Base64.decode(s.getString("vk"), Base64.NO_WRAP) : null;
        boolean hasVault = new Vault(c).exists();
        if (vk != null && !Vault.keyOpens(c, vk)) vk = null;
        if (hasVault && vk == null) {
            note = "Passwords aren't included — choose \"Change folder / password\" to add them.";
        }
        byte[] nk = s.has("nk") ? Base64.decode(s.getString("nk"), Base64.NO_WRAP) : null;
        if (nk == null && new SecureBox(c).exists()) {
            note = (note == null ? "" : note + " ") + "Locked notes aren't included — choose \"Change folder / password\".";
        }

        ContentResolver cr = c.getContentResolver();
        Uri tree = Uri.parse(p.getString("tree", ""));
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        Map<String, Uri> kids = children(cr, tree, rootId);
        if (kids.containsKey(TMP)) DocumentsContract.deleteDocument(cr, kids.get(TMP));

        // 1. Write the new backup next to the old one.
        Uri tmp = DocumentsContract.createDocument(cr, dir, "application/octet-stream", TMP);
        if (tmp == null) throw new java.io.IOException("can't create a file in the folder");
        java.io.OutputStream out = cr.openOutputStream(tmp, "w");
        if (out == null) throw new java.io.IOException("can't write to the folder");
        try {
            Backup.write(c, out, pw, snapshot, vk, s.optBoolean("vAuth", true), nk);
        } catch (Exception e) {
            DocumentsContract.deleteDocument(cr, tmp);
            throw e;
        }

        // 2. Only now replace the previous backup.
        Uri old = kids.get(FILE);
        boolean renamed = false;
        try {
            if (old != null) DocumentsContract.deleteDocument(cr, old);
            renamed = DocumentsContract.renameDocument(cr, tmp, FILE) != null;
        } catch (Exception ignored) {
            // some folders can't rename files: copy instead
        }
        if (!renamed) {
            Uri target = children(cr, tree, rootId).get(FILE);
            if (target == null) target = DocumentsContract.createDocument(cr, dir, "application/octet-stream", FILE);
            if (target == null) throw new java.io.IOException("can't create the backup file");
            Backup.copy(cr.openInputStream(tmp), cr.openOutputStream(target, "wt"));
            DocumentsContract.deleteDocument(cr, tmp);
        }
        if (note == null) p.edit().remove("note").apply();
        else p.edit().putString("note", note).apply();
    }

    private static Map<String, Uri> children(ContentResolver cr, Uri tree, String parentId) {
        Map<String, Uri> out = new HashMap<String, Uri>();
        Uri q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Cursor cur = cr.query(q, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (cur == null) return out;
        try {
            while (cur.moveToNext()) out.put(cur.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)));
        } finally {
            cur.close();
        }
        return out;
    }

    private static String folderName(ContentResolver cr, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Cursor cur = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) return cur.getString(0);
                } finally {
                    cur.close();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "chosen folder";
    }

    // ---------------------------------------------------------------- keystore wrapping (no fingerprint needed)

    private static String wrap(String plain) throws Exception {
        return Secrets.wrap(ALIAS, plain);
    }

    private static String unwrap(String b64) throws Exception {
        return Secrets.unwrapString(ALIAS, b64);
    }
}
