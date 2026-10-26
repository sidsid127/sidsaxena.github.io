package com.sidsaxena.daybook;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a task typed in plain language, e.g. "Call bank tomorrow 9am #work !high every monday".
 * Pure Java (no Android), so it can be unit-tested on a computer.
 */
final class QuickAdd {

    static final class Result {
        String title = "";
        long due;            // local midnight, 0 = none
        int time = -1;       // minutes after midnight, -1 = none
        int priority = -1;   // 0..3, -1 = not given
        String category;     // text after '#', null = none
        String repeat = "";  // see Recurrence

        boolean any() {
            return due != 0 || time >= 0 || priority >= 0 || category != null || !repeat.isEmpty();
        }
    }

    private static final String[] DAYS = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final String DAY_RE = "(sun(?:day)?|mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:rs|rsday)?|fri(?:day)?|sat(?:urday)?)";
    private static final String DAY_FULL_RE = "(sunday|monday|tuesday|wednesday|thursday|friday|saturday)";
    private static final String MONTH_RE = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    private QuickAdd() {}

    static Result parse(String input, long now) {
        Result r = new Result();
        String s = " " + input.trim() + " ";
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        zero(today);
        Matcher m;

        // Priority: !high !medium !low, !!!, !3, p1 (highest) … p3
        m = find(s, "(?i)\\s(!{1,3}|!(?:high|h|medium|med|m|low|l|[0-3])|p[1-3])(?=\\s)");
        if (m != null) {
            String p = m.group(1).toLowerCase(Locale.ROOT);
            if (p.matches("!!!|!high|!h|!3|p1")) r.priority = 3;
            else if (p.matches("!!|!medium|!med|!m|!2|p2")) r.priority = 2;
            else if (p.matches("!0")) r.priority = 0;
            else r.priority = 1;
            s = cut(s, m);
        }

        // Category: #work, #customer-a
        m = find(s, "\\s#([\\p{L}\\p{N}_\\-/]+)(?=\\s)");
        if (m != null) {
            r.category = m.group(1).replace('-', ' ').replace('_', ' ');
            s = cut(s, m);
        }

        // Repeat: every day / weekday / week / month / year / N days / monday, daily, weekly, monthly, yearly
        m = find(s, "(?i)\\s(every\\s+(\\d+)\\s+(day|week|month|year)s?|every\\s+(day|weekday|week|month|year)"
                + "|every\\s+" + DAY_RE + "|daily|weekly|monthly|yearly|annually|weekdays)(?=\\s)");
        if (m != null) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            if (m.group(2) != null) {
                r.repeat = m.group(3).substring(0, 1).toUpperCase(Locale.ROOT) + Integer.parseInt(m.group(2));
            } else if (m.group(5) != null) {
                r.repeat = "W1";
                r.due = nextWeekday(today, dayIndex(m.group(5)), true);
            } else if (w.contains("weekday")) {
                r.repeat = "WD";
            } else if (w.contains("day") || w.equals("daily")) {
                r.repeat = "D1";
            } else if (w.contains("week")) {
                r.repeat = "W1";
            } else if (w.contains("month")) {
                r.repeat = "M1";
            } else {
                r.repeat = "Y1";
            }
            s = cut(s, m);
        }

        // Relative dates
        if ((m = find(s, "(?i)\\s(the\\s+)?day\\s+after\\s+tomorrow(?=\\s)")) != null) {
            r.due = plusDays(today, 2);
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\sin\\s+(\\d+|a|an|one|two|three)\\s+(day|week|month)s?(?=\\s)")) != null) {
            int n = number(m.group(1));
            Calendar c = (Calendar) today.clone();
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            c.add(unit.equals("day") ? Calendar.DAY_OF_MONTH : unit.equals("week") ? Calendar.WEEK_OF_YEAR : Calendar.MONTH, n);
            r.due = c.getTimeInMillis();
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\snext\\s+week(?=\\s)")) != null) {
            r.due = nextWeekday(today, Calendar.MONDAY, false);
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\snext\\s+month(?=\\s)")) != null) {
            Calendar c = (Calendar) today.clone();
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.add(Calendar.MONTH, 1);
            r.due = c.getTimeInMillis();
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\s(?:on\\s+|next\\s+)" + DAY_RE + "(?=\\s)")) != null) {
            boolean next = m.group(0).toLowerCase(Locale.ROOT).contains("next");
            r.due = nextWeekday(today, dayIndex(m.group(1)), !next);
            if (next && r.due == today.getTimeInMillis()) r.due = plusDays(today, 7);
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\s" + DAY_FULL_RE + "(?=\\s)")) != null) {
            r.due = nextWeekday(today, dayIndex(m.group(1)), true);
            s = cut(s, m);
        }

        // Absolute dates: 2026-11-05, 5.11.2026, 5.11., 5/11, 5 nov, nov 5, 5th of november
        if ((m = find(s, "\\s(?:on\\s+)?(\\d{4})-(\\d{1,2})-(\\d{1,2})(?=\\s)")) != null) {
            r.due = date(today, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            s = cut(s, m);
        } else if ((m = find(s, "\\s(?:on\\s+)?(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?\\.?(?=\\s)")) != null
                && valid(m.group(1), m.group(2))) {
            int y = m.group(3) == null ? -1 : year(m.group(3));
            r.due = date(today, y, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\s(?:on\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH_RE + "(?:\\s+(\\d{4}))?(?=\\s)")) != null) {
            r.due = date(today, m.group(3) == null ? -1 : Integer.parseInt(m.group(3)), month(m.group(2)), Integer.parseInt(m.group(1)));
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\s(?:on\\s+)?" + MONTH_RE + "\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+(\\d{4}))?(?=\\s)")) != null) {
            r.due = date(today, m.group(3) == null ? -1 : Integer.parseInt(m.group(3)), month(m.group(1)), Integer.parseInt(m.group(2)));
            s = cut(s, m);
        }

        if ((m = find(s, "(?i)\\s(today|tonight|tomorrow|tmrw|tmr)(?=\\s)")) != null) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            r.due = w.startsWith("tod") || w.equals("tonight") ? today.getTimeInMillis() : plusDays(today, 1);
            if (w.equals("tonight")) r.time = 20 * 60;
            s = cut(s, m);
        }

        // Times: 9am, 9 pm, 9:30, 21:00, at 9, at 18.30, noon
        if ((m = find(s, "(?i)\\s(?:at\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s?(am|pm)(?=\\s)")) != null) {
            int h = Integer.parseInt(m.group(1)) % 12, min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (m.group(3).equalsIgnoreCase("pm")) h += 12;
            if (h < 24 && min < 60) {
                r.time = h * 60 + min;
                s = cut(s, m);
            }
        } else if ((m = find(s, "(?i)\\s(?:at\\s+)?([01]?\\d|2[0-3])[:](\\d{2})(?:\\s?(?:h|uhr))?(?=\\s)")) != null) {
            r.time = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\sat\\s+([01]?\\d|2[0-3])(?:[.](\\d{2}))?(?:\\s?(?:h|uhr))?(?=\\s)")) != null) {
            r.time = Integer.parseInt(m.group(1)) * 60 + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
            s = cut(s, m);
        } else if ((m = find(s, "(?i)\\s(?:at\\s+)?noon(?=\\s)")) != null) {
            r.time = 12 * 60;
            s = cut(s, m);
        }

        // A time without a date means today (or tomorrow if that time has passed).
        if (r.time >= 0 && r.due == 0) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            int nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            r.due = r.time > nowMin ? today.getTimeInMillis() : plusDays(today, 1);
        }
        // A repeat without a date starts today.
        if (!r.repeat.isEmpty() && r.due == 0) r.due = today.getTimeInMillis();

        r.title = s.replaceAll("\\s+", " ").trim();
        if (r.title.isEmpty()) r.title = input.trim();
        return r;
    }

    // ---- helpers

    private static Matcher find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m : null;
    }

    private static String cut(String s, Matcher m) {
        return s.substring(0, m.start()) + " " + s.substring(m.end());
    }

    private static void zero(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static long plusDays(Calendar today, int n) {
        Calendar c = (Calendar) today.clone();
        c.add(Calendar.DAY_OF_MONTH, n);
        return c.getTimeInMillis();
    }

    /** Next date that falls on the weekday (Calendar.SUNDAY…); today counts if allowToday. */
    static long nextWeekday(Calendar today, int weekday, boolean allowToday) {
        Calendar c = (Calendar) today.clone();
        if (!allowToday) c.add(Calendar.DAY_OF_MONTH, 1);
        while (c.get(Calendar.DAY_OF_WEEK) != weekday) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static int dayIndex(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 7; i++) if (DAYS[i].startsWith(w.substring(0, 3))) return i + 1; // Calendar.SUNDAY = 1
        return Calendar.MONDAY;
    }

    private static int month(String word) {
        String w = word.toLowerCase(Locale.ROOT).substring(0, 3);
        for (int i = 0; i < 12; i++) if (MONTHS[i].equals(w)) return i + 1;
        return 1;
    }

    private static int number(String w) {
        w = w.toLowerCase(Locale.ROOT);
        if (w.equals("a") || w.equals("an") || w.equals("one")) return 1;
        if (w.equals("two")) return 2;
        if (w.equals("three")) return 3;
        return Integer.parseInt(w);
    }

    private static boolean valid(String day, String month) {
        int d = Integer.parseInt(day), mo = Integer.parseInt(month);
        return d >= 1 && d <= 31 && mo >= 1 && mo <= 12;
    }

    private static int year(String y) {
        int v = Integer.parseInt(y);
        return v < 100 ? 2000 + v : v;
    }

    /** A date; with year -1 the next such date from today on. */
    private static long date(Calendar today, int year, int month, int day) {
        Calendar c = (Calendar) today.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        if (year > 0) c.set(Calendar.YEAR, year);
        else if (c.before(today)) c.add(Calendar.YEAR, 1);
        return c.getTimeInMillis();
    }
}
