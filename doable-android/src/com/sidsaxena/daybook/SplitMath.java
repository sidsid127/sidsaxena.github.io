package com.sidsaxena.daybook;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared expenses maths, the same as the web app's logic.js (splitShares, splitBalances, settleUp), so every device agrees to the cent. */
final class SplitMath {
    private SplitMath() {}

    static final class Row {
        String paidBy = "", toUser = "", currency = "EUR", kind = "expense", shares = "";
        long cents;
    }

    static final class Payment {
        final String from, to;
        final long cents;

        Payment(String from, String to, long cents) {
            this.from = from;
            this.to = to;
            this.cents = cents;
        }
    }

    /** {"userId": weight} from the stored JSON; only positive weights, sorted by id. */
    static TreeMap<String, Double> shares(String text) {
        TreeMap<String, Double> out = new TreeMap<String, Double>();
        try {
            JSONObject o = new JSONObject(text == null || text.isEmpty() ? "{}" : text);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                double v = o.optDouble(k, 0);
                if (v > 0) out.put(k, v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void add(Map<String, Map<String, Long>> out, String cur, String u, long c) {
        if (u == null || u.isEmpty()) return;
        Map<String, Long> b = out.get(cur);
        if (b == null) out.put(cur, b = new TreeMap<String, Long>());
        Long was = b.get(u);
        b.put(u, (was == null ? 0 : was) + c);
    }

    /** Per currency, per person: positive = gets money back, negative = owes. */
    static Map<String, Map<String, Long>> balances(List<Row> rows) {
        Map<String, Map<String, Long>> out = new TreeMap<String, Map<String, Long>>();
        for (Row r : rows) {
            String cur = r.currency == null || r.currency.isEmpty() ? "EUR" : r.currency;
            long cents = r.cents;
            if (cents == 0 || r.paidBy == null || r.paidBy.isEmpty()) continue;
            if ("settle".equals(r.kind)) {
                add(out, cur, r.paidBy, cents);
                add(out, cur, r.toUser, -cents);
                continue;
            }
            TreeMap<String, Double> sh = shares(r.shares);
            if (sh.isEmpty()) continue;
            List<String> ids = new ArrayList<String>(sh.keySet()); // TreeMap: same order as JavaScript's sort()
            double total = 0;
            for (double w : sh.values()) total += w;
            long[] parts = new long[ids.size()];
            long sum = 0;
            for (int i = 0; i < ids.size(); i++) {
                parts[i] = (long) Math.floor(cents * sh.get(ids.get(i)) / total);
                sum += parts[i];
            }
            for (int i = 0, left = (int) (cents - sum); left > 0; i = (i + 1) % ids.size(), left--) parts[i]++;
            add(out, cur, r.paidBy, cents);
            for (int i = 0; i < ids.size(); i++) add(out, cur, ids.get(i), -parts[i]);
        }
        for (Map<String, Long> b : out.values()) b.values().removeAll(Collections.singleton(0L));
        List<String> empty = new ArrayList<String>();
        for (Map.Entry<String, Map<String, Long>> e : out.entrySet()) if (e.getValue().isEmpty()) empty.add(e.getKey());
        for (String k : empty) out.remove(k);
        return out;
    }

    /** The fewest payments that settle one currency's balances, biggest first. */
    static List<Payment> settleUp(Map<String, Long> balance) {
        List<Object[]> pos = new ArrayList<Object[]>(), neg = new ArrayList<Object[]>();
        for (Map.Entry<String, Long> e : balance.entrySet()) {
            if (e.getValue() > 0) pos.add(new Object[]{e.getKey(), e.getValue()});
            else if (e.getValue() < 0) neg.add(new Object[]{e.getKey(), -e.getValue()});
        }
        Comparator<Object[]> big = new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                int c = Long.compare((Long) b[1], (Long) a[1]);
                return c != 0 ? c : ((String) a[0]).compareTo((String) b[0]);
            }
        };
        Collections.sort(pos, big);
        Collections.sort(neg, big);
        List<Payment> out = new ArrayList<Payment>();
        int i = 0, j = 0;
        while (i < pos.size() && j < neg.size()) {
            long c = Math.min((Long) pos.get(i)[1], (Long) neg.get(j)[1]);
            out.add(new Payment((String) neg.get(j)[0], (String) pos.get(i)[0], c));
            pos.get(i)[1] = (Long) pos.get(i)[1] - c;
            neg.get(j)[1] = (Long) neg.get(j)[1] - c;
            if ((Long) pos.get(i)[1] == 0) i++;
            if ((Long) neg.get(j)[1] == 0) j++;
        }
        return out;
    }
}
