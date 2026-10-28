package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates for copies installed from an APK file (testers): checks the Doable website for a newer version and installs
 * it with one tap through Android's own installer (Android only accepts it if it is signed like this copy). Switched off
 * when Doable comes from Google Play, which updates apps itself.
 */
final class Updater {
    static final String INFO = Account.SERVER + "android/version.json";
    static final String ACTION_STATUS = "com.sidsaxena.daybook.UPDATE_STATUS";
    private static final long EVERY_MS = 6 * 3600 * 1000L;
    private static final int FLAG_MUTABLE = 0x02000000; // PendingIntent.FLAG_MUTABLE (Android 12+)
    private static boolean busy;

    private Updater() {}

    static boolean fromPlayStore(Context c) {
        try {
            return "com.android.vending".equals(c.getPackageManager().getInstallerPackageName(c.getPackageName()));
        } catch (Exception e) {
            return false;
        }
    }

    static int currentVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** At start: looks for a newer version every few hours (or now, when the person asks). */
    static void check(final MainActivity a, final boolean asked) {
        if (fromPlayStore(a) || busy) {
            if (asked) Toast.makeText(a, "Google Play keeps Doable up to date", Toast.LENGTH_SHORT).show();
            return;
        }
        final long now = System.currentTimeMillis();
        if (!asked && now - a.prefs.getLong("updateChecked", 0) < EVERY_MS) return;
        if (!AccountUi.online(a)) {
            if (asked) Toast.makeText(a, "You're offline", Toast.LENGTH_SHORT).show();
            return;
        }
        a.prefs.edit().putLong("updateChecked", now).apply();
        new Thread(new Runnable() {
            public void run() {
                JSONObject info = null;
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL(INFO + "?t=" + now).openConnection();
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(20000);
                    InputStream in = h.getInputStream();
                    info = new JSONObject(new String(readAll(in), "UTF-8"));
                } catch (Exception ignored) {
                }
                final JSONObject latest = info;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        if (latest == null) {
                            if (asked) Toast.makeText(a, "Couldn't check for updates", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (latest.optInt("versionCode") <= currentVersion(a)) {
                            if (asked) Toast.makeText(a, "Doable is up to date (" + latest.optString("versionName") + ")", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!asked && latest.optInt("versionCode") == a.prefs.getInt("updateLater", 0)) return; // "Later" for this one
                        offer(a, latest);
                    }
                });
            }
        }).start();
    }

    private static void offer(final MainActivity a, final JSONObject latest) {
        String notes = latest.optString("notes");
        new AlertDialog.Builder(a)
                .setTitle("Doable " + latest.optString("versionName") + " is available")
                .setMessage((notes.isEmpty() ? "" : "What's new:\n" + notes + "\n\n") + "Your data stays as it is.")
                .setNegativeButton("Later", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { a.prefs.edit().putInt("updateLater", latest.optInt("versionCode")).apply(); }
                })
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { download(a, Account.SERVER + latest.optString("apk", "android/Doable.apk")); }
                })
                .show();
    }

    private static void download(final MainActivity a, final String url) {
        busy = true;
        Toast.makeText(a, "Downloading the update…", Toast.LENGTH_SHORT).show();
        final Context c = a.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(60000);
                    if (h.getResponseCode() != 200) throw new Exception("download failed (" + h.getResponseCode() + ")");
                    PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(c.getPackageName());
                    int id = pi.createSession(params);
                    PackageInstaller.Session s = pi.openSession(id);
                    InputStream in = h.getInputStream();
                    OutputStream out = s.openWrite("doable.apk", 0, -1);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                    out.close();
                    in.close();
                    Intent back = new Intent(c, MainActivity.class).setAction(ACTION_STATUS);
                    PendingIntent pending = PendingIntent.getActivity(c, 7, back, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_MUTABLE);
                    s.commit(pending.getIntentSender());
                    s.close();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String error = err;
                if (error != null) {
                    busy = false;
                    a.runOnUiThread(new Runnable() {
                        public void run() { Toast.makeText(a, "Update didn't work: " + error, Toast.LENGTH_LONG).show(); }
                    });
                }
            }
        }).start();
    }

    /** Android's installer reports back: ask the person to confirm, or say what went wrong. */
    static boolean handle(MainActivity a, Intent i) {
        if (i == null || !ACTION_STATUS.equals(i.getAction())) return false;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) a.startExternal(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        busy = false;
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                new AlertDialog.Builder(a).setTitle("Update not installed")
                        .setMessage((msg == null ? "Android refused the update." : msg)
                                + "\n\nIf Android asked about installing apps from Doable, allow it and try again (Profile → Check for updates).")
                        .setPositiveButton("OK", null).show();
            }
        }
        return true;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
