package com.sidsaxena.daybook;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.widget.EditText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Light formatting for notes, stored as plain text: **bold**, _italic_, "# " heading, "• " bullet,
 * "☐ / ☑ " checklist items and [[links]] to other notes. Styling is applied live while typing.
 */
final class NoteFormat {
    private NoteFormat() {}

    static final String BOX = "☐ ", TICK = "☑ ", BULLET = "• ";

    // Our own span types, so restyling removes only what we added.
    static final class Bold extends StyleSpan { Bold() { super(Typeface.BOLD); } }
    static final class Italic extends StyleSpan { Italic() { super(Typeface.ITALIC); } }
    static final class Big extends RelativeSizeSpan { Big(float f) { super(f); } }
    static final class Dim extends ForegroundColorSpan { Dim(int c) { super(c); } }
    static final class Link extends ForegroundColorSpan { Link(int c) { super(c); } }
    static final class Under extends UnderlineSpan {}
    static final class Strike extends StrikethroughSpan {}

    private static final Class<?>[] OURS = {Bold.class, Italic.class, Big.class, Dim.class, Link.class, Under.class, Strike.class};
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*_])_([^_\\n]+?)_(?![\\w])");
    static final Pattern LINK = Pattern.compile("\\[\\[([^\\]\\n]+)\\]\\]");

    static void style(Editable e, int muted, int link) {
        for (Class<?> k : OURS) for (Object span : e.getSpans(0, e.length(), k)) e.removeSpan(span);
        String t = e.toString();
        int start = 0;
        boolean first = true;
        while (start <= t.length()) {
            int end = t.indexOf('\n', start);
            if (end < 0) end = t.length();
            String line = t.substring(start, end);
            if (line.startsWith("# ")) {
                span(e, new Big(1.35f), start, end);
                span(e, new Bold(), start, end);
                span(e, new Dim(muted), start, start + 2);
            } else if (line.startsWith("## ")) {
                span(e, new Big(1.15f), start, end);
                span(e, new Bold(), start, end);
                span(e, new Dim(muted), start, start + 3);
            } else if (first && !line.trim().isEmpty()) {
                span(e, new Big(1.3f), start, end);
                span(e, new Bold(), start, end);
            }
            if (line.startsWith(TICK)) {
                span(e, new Strike(), start + 2, end);
                span(e, new Dim(muted), start + 2, end);
            }
            if (!line.trim().isEmpty()) first = false;
            start = end + 1;
        }
        Matcher m = BOLD.matcher(t);
        while (m.find()) {
            span(e, new Bold(), m.start(1), m.end(1));
            span(e, new Dim(muted), m.start(), m.start() + 2);
            span(e, new Dim(muted), m.end() - 2, m.end());
        }
        m = ITALIC.matcher(t);
        while (m.find()) {
            span(e, new Italic(), m.start(1), m.end(1));
            span(e, new Dim(muted), m.start(), m.start() + 1);
            span(e, new Dim(muted), m.end() - 1, m.end());
        }
        m = LINK.matcher(t);
        while (m.find()) {
            span(e, new Link(link), m.start(), m.end());
            span(e, new Under(), m.start(1), m.end(1));
        }
    }

    private static void span(Editable e, Object span, int s, int end) {
        if (s < end && end <= e.length()) e.setSpan(span, s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Wraps the selection in a marker (or inserts an empty pair with the cursor inside). */
    static void wrap(EditText ed, String marker) {
        int s = Math.max(0, ed.getSelectionStart()), e = Math.max(0, ed.getSelectionEnd());
        if (s > e) {
            int x = s;
            s = e;
            e = x;
        }
        Editable t = ed.getText();
        t.insert(e, marker);
        t.insert(s, marker);
        if (s == e) ed.setSelection(s + marker.length());
        else ed.setSelection(e + marker.length() * 2);
    }

    /** Toggles a line prefix (heading, bullet, checkbox) on the line with the cursor. */
    static void togglePrefix(EditText ed, String prefix, String... others) {
        Editable t = ed.getText();
        int cur = Math.max(0, ed.getSelectionStart());
        int lineStart = t.toString().lastIndexOf('\n', cur - 1) + 1;
        String rest = t.toString().substring(lineStart);
        if (rest.startsWith(prefix)) {
            t.delete(lineStart, lineStart + prefix.length());
            return;
        }
        for (String o : others) {
            if (rest.startsWith(o)) {
                t.delete(lineStart, lineStart + o.length());
                break;
            }
        }
        t.insert(lineStart, prefix);
    }

    /** The list prefix a new line should continue with after Enter, or null. */
    static String continuation(String line) {
        if (line.startsWith(BOX) || line.startsWith(TICK)) return BOX;
        if (line.startsWith(BULLET)) return BULLET;
        if (line.startsWith("- ")) return "- ";
        return null;
    }

    /** Text without formatting markers (for sharing). */
    static String plain(String body) {
        return body.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("\\[\\[([^\\]\\n]+)\\]\\]", "$1");
    }
}
