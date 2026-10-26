package com.sidsaxena.daybook;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Currency conversion and per-diem allowances for trips. Pure Java. */
final class TripMath {
    private TripMath() {}

    /** "INR=0.0108;USD=0.92" → map of currency → value of 1 unit in the home currency. */
    static Map<String, Double> rates(String s) {
        Map<String, Double> out = new LinkedHashMap<String, Double>();
        if (s == null) return out;
        for (String part : s.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            try {
                out.put(kv[0].trim().toUpperCase(Locale.ROOT), Double.parseDouble(kv[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static String ratesString(Map<String, Double> rates) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : rates.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Amount in the home currency, or -1 when no rate is known. */
    static long toHome(long cents, String currency, String home, Map<String, Double> rates) {
        if (currency.equalsIgnoreCase(home)) return cents;
        Double r = rates.get(currency.toUpperCase(Locale.ROOT));
        if (r == null || r <= 0) return -1;
        return Math.round(cents * r);
    }

    /**
     * Per-diem days for a trip from {@code start} to {@code finish} (local midnights, inclusive), German style:
     * a one-day trip counts as one partial day; otherwise arrival and departure days are partial and the days in
     * between are full. Returns {fullDays, partialDays}.
     */
    static int[] perDiemDays(long start, long finish) {
        if (start == 0) return new int[]{0, 0};
        if (finish < start) finish = start;
        int days = 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(start);
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(finish);
        while (!c.after(end) && days < 4000) {
            days++;
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        if (days == 1) return new int[]{0, 1};
        return new int[]{days - 2, 2};
    }
}
