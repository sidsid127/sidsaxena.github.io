package com.sidsaxena.daybook;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Credentials search (same rules as the web app's searchCredentials): every word must be found in the name, folder,
 * username / email, website, card holder, type or notes — never in the secrets. Best matches first: name, then folder,
 * then username / website.
 */
final class CredentialSearch {
    private CredentialSearch() {}

    static String fold(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    static List<Vault.Login> search(List<Vault.Login> list, String query) {
        final String[] words = fold(query).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return list;
        final List<Object[]> hits = new ArrayList<Object[]>();
        for (Vault.Login l : list) {
            String title = fold(l.title), folder = fold(l.folder), user = fold(l.user), url = fold(l.url), holder = fold(l.holder),
                    type = fold(l.type), notes = fold(l.notes);
            int score = 0;
            for (String w : words) {
                int s = title.startsWith(w) ? 7 : title.contains(w) ? 6 : folder.contains(w) ? 5 : user.contains(w) || url.contains(w) ? 4
                        : holder.contains(w) || type.contains(w) ? 2 : notes.contains(w) ? 1 : 0;
                if (s == 0) {
                    score = -1;
                    break;
                }
                score += s;
            }
            if (score > 0) hits.add(new Object[]{score, l});
        }
        Collections.sort(hits, new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                int d = (Integer) b[0] - (Integer) a[0];
                return d != 0 ? d : ((Vault.Login) a[1]).title.compareToIgnoreCase(((Vault.Login) b[1]).title);
            }
        });
        List<Vault.Login> out = new ArrayList<Vault.Login>();
        for (Object[] h : hits) out.add((Vault.Login) h[1]);
        return out;
    }

    /** Folder names in use, sorted. */
    static List<String> folders(List<Vault.Login> list) {
        TreeSet<String> set = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (Vault.Login l : list) if (!l.folder.trim().isEmpty()) set.add(l.folder.trim());
        return new ArrayList<String>(set);
    }
}
