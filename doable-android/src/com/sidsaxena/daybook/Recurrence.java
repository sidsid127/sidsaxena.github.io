package com.sidsaxena.daybook;

import java.util.Calendar;

/**
 * Repeat rules for tasks: "D1" daily, "D3" every 3 days, "WD" weekdays, "W1" weekly, "W2" every 2 weeks,
 * "M1" monthly, "Y1" yearly, "" none.
 */
final class Recurrence {
    private Recurrence() {}

    static final String[] RULES = {"", "D1", "WD", "W1", "W2", "M1", "Y1"};
    static final String[] LABELS = {"Does not repeat", "Every day", "Every weekday (Mon–Fri)", "Every week",
            "Every 2 weeks", "Every month", "Every year"};

    static String label(String rule) {
        if (rule == null || rule.isEmpty()) return "";
        if (rule.equals("WD")) return "Every weekday";
        int n = interval(rule);
        String unit = rule.charAt(0) == 'D' ? "day" : rule.charAt(0) == 'W' ? "week" : rule.charAt(0) == 'M' ? "month" : "year";
        return n == 1 ? "Every " + unit : "Every " + n + " " + unit + "s";
    }

    private static int interval(String rule) {
        try {
            return Math.max(1, Integer.parseInt(rule.substring(1)));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** The next date after {@code due} (local midnight) for the rule; monthly keeps the day where possible. */
    static long next(long due, String rule) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(due);
        if (rule.equals("WD")) {
            do {
                c.add(Calendar.DAY_OF_MONTH, 1);
            } while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY);
            return c.getTimeInMillis();
        }
        int n = interval(rule);
        switch (rule.charAt(0)) {
            case 'D': c.add(Calendar.DAY_OF_MONTH, n); break;
            case 'W': c.add(Calendar.WEEK_OF_YEAR, n); break;
            case 'M': c.add(Calendar.MONTH, n); break;
            default: c.add(Calendar.YEAR, n); break;
        }
        return c.getTimeInMillis();
    }

    /** Next occurrence that is on or after {@code notBefore} (used when a repeating task was overdue). */
    static long nextOnOrAfter(long due, String rule, long notBefore) {
        long d = next(due, rule);
        for (int guard = 0; d < notBefore && guard < 4000; guard++) d = next(d, rule);
        return d;
    }
}
