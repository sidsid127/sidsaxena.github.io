package com.sidsaxena.daybook;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Keeps a note when Doable crashes (what failed, on which phone), so the next start can show it and the user can
 * send it on. Nothing leaves the phone unless the user shares it.
 */
public class App extends Application {
    static File crashFile(Context c) {
        return new File(c.getFilesDir(), "last_crash.txt");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    String version = "?";
                    try {
                        version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Exception ignored) {
                    }
                    pw.println("Doable " + version + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                            + Build.MANUFACTURER + " " + Build.MODEL + " · thread " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream out = new FileOutputStream(crashFile(App.this));
                    out.write(sw.toString().getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                    // never fail while reporting a failure
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }
}
