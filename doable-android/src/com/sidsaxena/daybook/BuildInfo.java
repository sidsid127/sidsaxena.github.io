package com.sidsaxena.daybook;

import android.content.Context;

/** This copy's version name, e.g. "7.1". */
final class BuildInfo {
    private BuildInfo() {}

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
