package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Sync with Google": turn on (sign in → passphrase → include passwords & locked notes → first sync),
 * sync now, turn off. Sync itself runs in the background when Doable opens and closes.
 */
final class SyncUi {
    private SyncUi() {}

    static String status(MainActivity a) {
        SharedPreferences p = SyncEngine.prefs(a);
        if (!SyncEngine.enabled(a)) return "Off — data stays on this phone";
        String err = p.getString("error", "");
        long last = p.getLong("last", 0);
        return (err.isEmpty() ? "" : "⚠ " + err + "\n") + p.getString("email", "") + (last == 0 ? " · not synced yet" : " · synced " + Util.fullTimestamp(a, last));
    }

    static void show(final MainActivity a) {
        boolean on = SyncEngine.enabled(a);
        String msg = "Keep your Android phone, iPhone and Mac in sync through your own Google Drive.\n\n"
                + "Doable keeps one file in a hidden folder of your Drive that only Doable can see. It's encrypted on the phone with "
                + "your sync passphrase, so Google can't read it. Doable still works offline; it syncs when it opens and closes.\n\n"
                + "Status: " + status(a);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Sync with Google").setMessage(msg).setNegativeButton("Close", null);
        if (on) {
            b.setPositiveButton("Sync now", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { syncNow(a, true); }
            });
            b.setNeutralButton("Turn off…", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    Util.confirm(a, "Turn off sync on this phone?", "Your data stays here; your other devices keep syncing.", "Turn off", new Runnable() {
                        public void run() {
                            SyncEngine.turnOff(a);
                            SyncJob.cancel(a);
                            Toast.makeText(a, "Sync turned off", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } else {
            b.setPositiveButton("Turn on", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { turnOn(a); }
            });
        }
        b.show();
    }

    private static void turnOn(final MainActivity a) {
        if (GoogleDrive.clientId(a).isEmpty()) {
            new AlertDialog.Builder(a).setTitle("Sync with Google")
                    .setMessage("Sync isn't switched on in this version of Doable yet. It will work in the next update — no setup needed on your side.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (GoogleDrive.signedIn(a)) {
            afterSignIn(a);
            return;
        }
        Toast.makeText(a, "Sign in with Google in the browser, then come back", Toast.LENGTH_LONG).show();
        GoogleDrive.signIn(a, new GoogleDrive.SignedIn() {
            public void done(String error) {
                if (error != null) {
                    new AlertDialog.Builder(a).setTitle("Google sign-in").setMessage(error).setPositiveButton("OK", null).show();
                    return;
                }
                afterSignIn(a);
            }
        });
    }

    /** Signed in: make sure passwords & locked notes can be included, then the first sync (which asks for the passphrase). */
    private static void afterSignIn(final MainActivity a) {
        SyncEngine.prefs(a).edit().putBoolean("enabled", true).apply();
        SyncJob.schedule(a);
        syncNow(a, true);
    }

    /** Includes passwords (sealed copy) and converts locked notes; needs the fingerprint once. */
    private static void includeSecrets(final MainActivity a, final Runnable then) {
        final Vault vault = new Vault(a);
        final Db db = new Db(a);
        final boolean notes = SyncEngine.hasUnconvertedLockedNotes(db);
        final boolean passwords = vault.exists() && !Vault.syncCopyFile(a).exists();
        if (!notes && !passwords) {
            then.run();
            return;
        }
        a.confirmUser("Include passwords & locked notes in sync", true, new MainActivity.AuthResult() {
            public void done(boolean ok, int error) {
                if (ok) {
                    try {
                        if (passwords) {
                            vault.open(); // writes the sealed copy
                            vault.close();
                        }
                        if (notes) {
                            SecureBox box = new SecureBox(a);
                            box.open();
                            SyncEngine.convertLockedNotes(a, db, box);
                            box.close();
                        }
                    } catch (Exception e) {
                        Toast.makeText(a, "Passwords / locked notes not included: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(a, "Passwords and locked notes will sync after you allow it here", Toast.LENGTH_LONG).show();
                }
                then.run();
            }
        });
    }

    /** Runs a sync; with {@code interactive} it shows progress and asks for whatever is missing. */
    static void syncNow(final MainActivity a, final boolean interactive) {
        if (!SyncEngine.enabled(a)) return;
        final ProgressDialog pd = interactive ? ProgressDialog.show(a, null, "Syncing…", true) : null;
        new Thread(new Runnable() {
            public void run() {
                Exception err = null;
                boolean changed = false;
                try {
                    changed = SyncEngine.run(a);
                } catch (Exception e) {
                    err = e;
                }
                final Exception error = err;
                final boolean ch = changed;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (pd != null) pd.dismiss();
                        if (ch) a.syncChanged();
                        if (error == null) {
                            if (interactive) {
                                if (SyncEngine.syncKey(a) != null && (new Vault(a).exists() && !Vault.syncCopyFile(a).exists()
                                        || SyncEngine.hasUnconvertedLockedNotes(new Db(a)))) {
                                    includeSecrets(a, new Runnable() {
                                        public void run() { syncNow(a, false); }
                                    });
                                }
                                Toast.makeText(a, "Synced ✓", Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        if (error instanceof SyncEngine.NeedPassphrase) {
                            if (interactive) passphrase(a, ((SyncEngine.NeedPassphrase) error).header);
                        } else if (error instanceof GoogleDrive.AuthException) {
                            if (interactive) turnOn(a);
                        } else if (interactive) {
                            new AlertDialog.Builder(a).setTitle("Sync failed").setMessage(error.getMessage()).setPositiveButton("OK", null).show();
                        }
                    }
                });
            }
        }).start();
    }

    private static void passphrase(final MainActivity a, final SyncCrypto.Header header) {
        LinearLayout box = Util.dialogBox(a);
        TextView t = new TextView(a);
        t.setText(header == null
                ? "Choose a sync passphrase (8+ characters). Everything Doable puts on your Google Drive is encrypted with it. "
                + "You'll type it once on each device — write it down; without it the synced data can't be opened."
                : "Enter the sync passphrase you chose on your first device.");
        t.setTextColor(Util.color(a, R.color.muted));
        box.addView(t);
        final EditText p1 = new EditText(a);
        p1.setHint("Sync passphrase");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p1);
        final EditText p2 = new EditText(a);
        p2.setHint("Repeat passphrase");
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (header == null) box.addView(p2);
        new AlertDialog.Builder(a).setTitle(header == null ? "Choose a sync passphrase" : "Sync passphrase").setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String pw = p1.getText().toString();
                        if (pw.length() < 8) {
                            Toast.makeText(a, "Use at least 8 characters", Toast.LENGTH_LONG).show();
                            passphrase(a, header);
                            return;
                        }
                        if (header == null && !pw.equals(p2.getText().toString())) {
                            Toast.makeText(a, "The passphrases don't match", Toast.LENGTH_LONG).show();
                            passphrase(a, header);
                            return;
                        }
                        final ProgressDialog pd = ProgressDialog.show(a, null, "Checking…", true);
                        new Thread(new Runnable() {
                            public void run() {
                                String err = null;
                                try {
                                    SyncEngine.setPassphrase(a, pw.toCharArray(), header);
                                } catch (java.security.GeneralSecurityException e) {
                                    err = "Wrong passphrase";
                                } catch (Exception e) {
                                    err = e.getMessage();
                                }
                                final String error = err;
                                a.runOnUiThread(new Runnable() {
                                    public void run() {
                                        pd.dismiss();
                                        if (error != null) {
                                            Toast.makeText(a, error, Toast.LENGTH_LONG).show();
                                            passphrase(a, header);
                                        } else {
                                            syncNow(a, true);
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }
}
