package com.sidsaxena.daybook;

import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * Streaks for habits. "Build" habits succeed on a scheduled day when the count reaches the target;
 * "quit" habits (e.g. no sweets) succeed every day that has no slip logged. Pure Java.
 */
final class HabitLogic {
    private HabitLogic() {}

    /** Days of week bit mask: bit 0 = Sunday … bit 6 = Saturday. 127 = every day. */
    static boolean scheduled(int mask, Calendar day) {
        return (mask & (1 << (day.get(Calendar.DAY_OF_WEEK) - 1))) != 0;
    }

    static boolean success(boolean quit, int target, Integer count) {
        int n = count == null ? 0 : count;
        return quit ? n == 0 : n >= Math.max(1, target);
    }

    /**
     * {current, best} streak in scheduled days. Today counts if already done (for build habits, an unfinished
     * today doesn't break the streak). Quit habits count from {@code since} (local midnight of creation).
     */
    static int[] streaks(boolean quit, int target, int mask, Map<Long, Integer> log, long today, long since) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(today);
        int current = 0, best = 0, run = 0;
        boolean currentDone = false;
        boolean first = true;
        for (int i = 0; i < 3660; i++) {
            long day = c.getTimeInMillis();
            if (day < since) break;
            if (scheduled(mask, c)) {
                boolean ok = success(quit, target, log.get(day));
                if (first && day == today && !ok && !quit) {
                    // today is still open
                } else if (ok) {
                    run++;
                } else {
                    if (!currentDone) {
                        current = run;
                        currentDone = true;
                    }
                    best = Math.max(best, run);
                    run = 0;
                }
                first = false;
            }
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        if (!currentDone) current = run;
        best = Math.max(best, Math.max(run, current));
        return new int[]{current, best};
    }

    /** Builds the stored reminder spec, or "" if nothing valid was entered. */
    static String reminderSpec(int mode, String times, String hours, String from, String to) {
        try {
            if (mode == 1) {
                StringBuilder sb = new StringBuilder();
                for (String t : times.split("[,; ]+")) {
                    if (t.trim().isEmpty()) continue;
                    int m = parseTime(t);
                    if (m < 0) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60));
                }
                return sb.toString();
            }
            if (mode == 2) {
                int step = (int) Math.round(Double.parseDouble(hours.trim().replace(',', '.')) * 60);
                int a = parseTime(from), b = parseTime(to);
                if (step < 15 || a < 0 || b < a) return "";
                return String.format(Locale.ROOT, "every:%d:%02d:%02d-%02d:%02d", step, a / 60, a % 60, b / 60, b % 60);
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    /** "8", "8:30", "08.30", "20:00" → minutes after midnight, or -1. */
    static int parseTime(String s) {
        s = s.trim().replace('.', ':');
        try {
            String[] p = s.split(":");
            int h = Integer.parseInt(p[0]);
            int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
