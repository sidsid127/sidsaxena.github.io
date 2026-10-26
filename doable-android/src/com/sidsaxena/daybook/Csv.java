package com.sidsaxena.daybook;

import java.util.ArrayList;
import java.util.List;

/** Minimal CSV reading and writing (quotes, embedded separators and line breaks). Pure Java. */
final class Csv {
    private Csv() {}

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<List<String>>();
        if (text.startsWith("﻿")) text = text.substring(1);
        char sep = guessSeparator(text);
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<String>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static char guessSeparator(String text) {
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        int commas = first.length() - first.replace(",", "").length();
        int semis = first.length() - first.replace(";", "").length();
        return semis > commas ? ';' : ',';
    }

    static String cell(String s, char sep) {
        if (s == null) return "";
        if (s.indexOf(sep) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    static String row(char sep, String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(cell(cells[i], sep));
        }
        return sb.append("\r\n").toString();
    }
}
