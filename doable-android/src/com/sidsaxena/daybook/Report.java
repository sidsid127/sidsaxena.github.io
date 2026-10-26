package com.sidsaxena.daybook;

import java.util.List;

/** Small HTML helpers for the printable (PDF) diary book, travel diary and expense reports. */
final class Report {
    private Report() {}

    static final String HEAD = "<!doctype html><html><head><meta charset='utf-8'><style>"
            + "body{font-family:sans-serif;color:#1C1B29;font-size:12pt;line-height:1.5;margin:0}"
            + ".cover{height:90vh;display:flex;flex-direction:column;justify-content:center;text-align:center;page-break-after:always}"
            + ".cover h1{font-size:40pt;color:#3F2CB8;margin:0}.cover p{color:#7A7891;font-size:14pt;margin:6px}"
            + "h1{color:#3F2CB8;font-size:22pt;margin:0 0 12px}h1.month{page-break-before:always}"
            + "h2{font-size:15pt;margin:22px 0 4px;border-bottom:1px solid #E3E1F0;padding-bottom:4px}"
            + "h3{font-size:11pt;color:#7A7891;text-transform:uppercase;letter-spacing:.5px;margin:12px 0 2px}"
            + ".mood{font-weight:normal;color:#7A7891;font-size:12pt}.entry{page-break-inside:avoid}p{margin:4px 0}"
            + ".photos{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}.photos img{max-width:48%;max-height:300px;border-radius:6px}"
            + "table{border-collapse:collapse;width:100%;font-size:9.5pt}th,td{border-bottom:1px solid #E3E1F0;padding:5px 4px;text-align:left;vertical-align:top}"
            + "th{background:#F4F3FA}td.n,th.n{text-align:right;white-space:nowrap}.muted{color:#7A7891}"
            + ".receipt{page-break-inside:avoid;margin-top:16px}.receipt img{max-width:100%;max-height:900px}"
            + "</style></head><body>";

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Escaped text with line breaks kept. */
    static String para(String s) {
        return esc(s.trim()).replace("\n", "<br>");
    }

    /** Photos as a grid; PDFs are listed by name since they can't be embedded. */
    static String photos(List<Db.Attachment> list) {
        if (list.isEmpty()) return "";
        StringBuilder h = new StringBuilder("<div class='photos'>");
        for (Db.Attachment at : list) {
            if (Attachments.isPdf(at.file)) h.append("<span class='muted'>📄 PDF attached</span>");
            else h.append("<img src='att/").append(at.file).append("'>");
        }
        return h.append("</div>").toString();
    }
}
