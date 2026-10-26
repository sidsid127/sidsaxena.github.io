package com.sidsaxena.daybook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns pasted text (Notepad, a note, WhatsApp, Doable's own "Share as text") into checklist items: one
 * item per line, list markers removed, "☑ / [x]" kept as ticked. Pure Java.
 */
final class ChecklistText {
    private ChecklistText() {}

    static final class Line {
        final String text;
        final boolean done;

        Line(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    static List<Line> parse(String pasted) {
        List<Line> out = new ArrayList<Line>();
        Set<String> seen = new HashSet<String>();
        for (String raw : pasted.replace('\r', '\n').split("\n")) {
            String l = raw.trim();
            boolean done = false;
            if (l.startsWith("☑") || l.startsWith("✅") || l.startsWith("✔") || l.matches("(?i)^[-*]?\\s*\\[[x✓]\\].*")) done = true;
            // bullets, boxes, "1." / "1)" numbering
            l = l.replaceFirst("(?i)^([-•*·–—>]|☐|☑|✅|✔|□|\\[[ x✓]?\\]|\\d{1,3}[.)])+\\s*", "").trim();
            if (l.startsWith("[ ]") || l.startsWith("[]")) l = l.replaceFirst("^\\[ ?\\]\\s*", "");
            if (l.isEmpty() || !seen.add(key(l))) continue;
            out.add(new Line(l, done));
        }
        return out;
    }

    /** "Packing list:" on the first line is a title, not an item. Returns it, or null. */
    static String heading(String pasted) {
        for (String raw : pasted.replace('\r', '\n').split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            return l.endsWith(":") && l.length() > 1 ? l.substring(0, l.length() - 1).trim() : null;
        }
        return null;
    }

    /** Comparison key: case, spacing and trailing punctuation don't make an item "new". */
    static String key(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("[.,;:!]+$", "").trim();
    }
}
