package com.sidsaxena.daybook;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;

/** One module of the app (to-do, notes, …). MainActivity shows one screen at a time. */
abstract class Screen {
    final MainActivity a;
    final Db db;
    final Store st;
    final View root;

    Screen(MainActivity a, int layout) {
        this.a = a;
        this.db = a.db;
        this.st = a.st;
        this.root = LayoutInflater.from(a).inflate(layout, a.content, false);
    }

    abstract String title();

    /** Screen became visible, or the app came back to the foreground while it was visible. */
    void onShow() {}

    /** Another screen is about to be shown. */
    void onHide() {}

    /** The app went to the background while this screen was visible. */
    void onStop() {}

    /** Screen-specific menu items; use ids from 100 up. */
    void onCreateMenu(Menu menu) {}

    void onPrepareMenu(Menu menu) {}

    boolean onMenu(int id) { return false; }

    /** True if back was handled inside the screen (e.g. closing an open note). */
    boolean onBack() { return false; }

    /** True when the action-bar home button should act as "back" instead of opening the side menu. */
    boolean homeIsBack() { return false; }

    /** Opens one item (from search, a notification or a widget). */
    void open(long id) {}

    /** A quick action from a widget or app shortcut, e.g. "task" = start typing a new task. */
    void quick(String what) {}
}
