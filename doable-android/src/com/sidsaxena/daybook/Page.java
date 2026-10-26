package com.sidsaxena.daybook;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A screen built from code on a scrolling page of cards (Today, Habits, Wallet, Dates, Checklists),
 * so the newer modules need no layout files of their own.
 */
abstract class Page extends Screen {
    final LinearLayout body;
    final ScrollView scroll;
    final View fab;

    Page(MainActivity a) {
        super(a, R.layout.screen_page);
        body = (LinearLayout) root.findViewById(R.id.body);
        scroll = (ScrollView) root.findViewById(R.id.scroll);
        fab = root.findViewById(R.id.add);
    }

    /** Rebuilds the page from the database. */
    abstract void render();

    @Override
    void onShow() {
        render();
    }

    void setFab(final Runnable onClick) {
        fab.setVisibility(onClick == null ? View.GONE : View.VISIBLE);
        fab.setOnClickListener(onClick == null ? null : new View.OnClickListener() {
            public void onClick(View v) { onClick.run(); }
        });
    }

    int dp(int v) {
        return Util.dp(a, v);
    }

    TextView header(String text) {
        TextView t = new TextView(a);
        t.setText(text.toUpperCase(java.util.Locale.getDefault()));
        t.setTextSize(12);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setTextColor(Util.color(a, R.color.muted));
        t.setPadding(dp(6), dp(16), dp(6), dp(6));
        body.addView(t);
        return t;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(0, dp(4), 0, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        body.addView(c, lp);
        return c;
    }

    TextView empty(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setTextColor(Util.color(a, R.color.muted));
        t.setPadding(dp(24), dp(48), dp(24), dp(24));
        body.addView(t);
        return t;
    }

    /** A list row: emoji/icon, title, optional subtitle and an optional view at the end. */
    LinearLayout row(LinearLayout parent, String icon, CharSequence title, CharSequence sub, View end,
                     final Runnable click, final Runnable longClick) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(52));
        r.setPadding(dp(12), dp(6), dp(12), dp(6));
        r.setBackgroundResource(android.R.drawable.list_selector_background);
        if (icon != null) {
            TextView i = new TextView(a);
            i.setText(icon);
            i.setTextSize(20);
            i.setGravity(Gravity.CENTER);
            r.addView(i, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, dp(8), 0);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(Util.color(a, R.color.text));
        texts.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextSize(13);
            s.setTextColor(Util.color(a, R.color.muted));
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (end != null) r.addView(end);
        if (click != null) {
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { click.run(); }
            });
        }
        if (longClick != null) {
            r.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    longClick.run();
                    return true;
                }
            });
        }
        parent.addView(r);
        return r;
    }

    /** A small round button with a text/emoji label (used at the end of rows). */
    TextView pill(String text, boolean filled, final Runnable click) {
        TextView t = Util.chip(a, text, Util.color(a, R.color.primary), filled);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(48));
        if (click != null) {
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { click.run(); }
            });
        }
        return t;
    }

    /** A text link-style button. */
    TextView link(LinearLayout parent, String text, final Runnable click) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setTextColor(Util.color(a, R.color.primary_text));
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { click.run(); }
        });
        parent.addView(t);
        return t;
    }
}
