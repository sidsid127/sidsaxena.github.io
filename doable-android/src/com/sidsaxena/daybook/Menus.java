package com.sidsaxena.daybook;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Action menus as a bottom sheet: an icon, a label and an optional second line per row, dividers between groups, and
 * destructive actions last in red — the same look as the web app's menus.
 */
final class Menus {
    private Menus() {}

    static final class Item {
        final String icon, label, sub;
        final boolean danger, checked;
        final Runnable action;

        Item(String icon, String label, String sub, boolean danger, boolean checked, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.sub = sub;
            this.danger = danger;
            this.checked = checked;
            this.action = action;
        }
    }

    /** Builder: Menus.of(a, "Title").add("✏️", "Rename", run).danger("🗑", "Delete", run).show(). */
    static final class Builder {
        private final MainActivity a;
        private final String title;
        private final List<Item> items = new ArrayList<Item>();

        Builder(MainActivity a, String title) {
            this.a = a;
            this.title = title;
        }

        Builder add(String icon, String label, Runnable action) { return add(icon, label, null, action); }

        Builder add(String icon, String label, String sub, Runnable action) {
            items.add(new Item(icon, label, sub, false, false, action));
            return this;
        }

        Builder check(String icon, String label, boolean checked, Runnable action) {
            items.add(new Item(icon, label, null, false, checked, action));
            return this;
        }

        Builder danger(String icon, String label, Runnable action) {
            items.add(new Item(icon, label, null, true, false, action));
            return this;
        }

        Builder divider() {
            items.add(null);
            return this;
        }

        void show() { Menus.show(a, title, items); }
    }

    static Builder of(MainActivity a, String title) { return new Builder(a, title); }

    static void show(final MainActivity a, String title, List<Item> raw) {
        // Destructive items last, after a divider; no doubled or trailing dividers.
        List<Item> safe = new ArrayList<Item>(), bad = new ArrayList<Item>();
        for (Item i : raw) (i != null && i.danger ? bad : safe).add(i);
        List<Item> items = new ArrayList<Item>(safe);
        if (!bad.isEmpty() && !safe.isEmpty()) items.add(null);
        items.addAll(bad);

        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Util.color(a, R.color.card));
        float r = Util.dp(a, 18);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(0, Util.dp(a, 8), 0, Util.dp(a, 12));
        View grip = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Util.color(a, R.color.divider));
        g.setCornerRadius(Util.dp(a, 2));
        grip.setBackground(g);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Util.dp(a, 36), Util.dp(a, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = Util.dp(a, 6);
        box.addView(grip, glp);
        if (title != null && !title.isEmpty()) {
            TextView t = AccountUi.text(a, title, 13, R.color.muted, true);
            t.setPadding(Util.dp(a, 20), Util.dp(a, 6), Util.dp(a, 20), Util.dp(a, 6));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(t);
        }
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        Item prev = null;
        boolean first = true;
        for (final Item it : items) {
            if (it == null) {
                if (!first && prev != null) {
                    View line = new View(a);
                    line.setBackgroundColor(Util.color(a, R.color.divider));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                    lp.setMargins(0, Util.dp(a, 6), 0, Util.dp(a, 6));
                    list.addView(line, lp);
                }
                prev = null;
                continue;
            }
            first = false;
            prev = it;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Util.dp(a, 52));
            row.setPadding(Util.dp(a, 20), Util.dp(a, 6), Util.dp(a, 20), Util.dp(a, 6));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            int color = it.danger ? R.color.overdue : R.color.text;
            TextView ic = AccountUi.text(a, it.icon == null ? "" : it.icon, 19, color, false);
            ic.setGravity(Gravity.CENTER);
            row.addView(ic, new LinearLayout.LayoutParams(Util.dp(a, 32), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Util.dp(a, 14), 0, 0, 0);
            TextView label = AccountUi.text(a, it.label, 16, color, false);
            texts.addView(label);
            if (it.sub != null && !it.sub.isEmpty()) texts.addView(AccountUi.text(a, it.sub, 13, R.color.muted, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (it.checked) {
                TextView c = AccountUi.text(a, "✓", 18, R.color.primary_text, true);
                c.setTypeface(c.getTypeface(), Typeface.BOLD);
                row.addView(c);
            }
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    if (it.action != null) it.action.run();
                }
            });
            list.addView(row);
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        box.addView(scroll);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        d.show();
    }
}
