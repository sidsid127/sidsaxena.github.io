package com.sidsaxena.daybook;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The tables and columns that sync (doable/SYNC.md), in an order where referenced rows come first, plus the
 * row-level merge. Column kinds: T text, I integer, R real, D day ("YYYY-MM-DD" in the document, local
 * midnight in SQLite), F:table reference (uid in the document, _id in SQLite), O attachment owner.
 * Pure Java apart from org.json.
 */
final class SyncSchema {
    private SyncSchema() {}

    static final class Table {
        final String name;
        final String[] cols, kinds;
        final String keyedUid; // SQL for a uid derived from the row's content (NEW.* columns), or null

        Table(String name, String keyedUid, String... spec) {
            this.name = name;
            this.keyedUid = keyedUid;
            cols = new String[spec.length];
            kinds = new String[spec.length];
            for (int i = 0; i < spec.length; i++) {
                int at = spec[i].indexOf(' ');
                cols[i] = spec[i].substring(0, at);
                kinds[i] = spec[i].substring(at + 1);
            }
        }
    }

    private static final String DAY_SQL = "strftime('%Y-%m-%d', NEW.day / 1000, 'unixepoch', 'localtime')";

    static final Table[] TABLES = {
            new Table("categories", null, "name T", "parent F:categories", "color I"),
            new Table("shops", null, "name T", "color I"),
            new Table("grocery_lists", null, "name T"),
            new Table("trips", null, "name T", "kind I", "destination T", "start D", "finish D", "currency T", "created I", "budget I",
                    "rates T", "pd_full I", "pd_partial I", "km_rate I", "per_diem I"),
            new Table("habits", null, "name T", "emoji T", "quit I", "target I", "days I", "remind T", "color I", "archived I", "created D"),
            new Table("checklists", null, "title T", "trip F:trips", "template I", "created I"),
            new Table("notes", null, "body T", "pinned I", "created I", "modified I", "color I", "folder T", "locked I", "deleted I"),
            new Table("diary", "'diary:' || " + DAY_SQL, "day D", "mood I", "text T", "highs T", "lows T", "modified I", "place T",
                    "lat R", "lon R", "weather T", "temp T"),
            new Table("wallet", null, "type T", "title T", "number T", "holder T", "expiry D", "barcode T", "notes T", "created I"),
            new Table("dates", null, "title T", "kind T", "month I", "day I", "year I", "yearly I", "remind_days I", "notes T", "created I"),
            new Table("log", null, "text T", "ts I"),
            new Table("tasks", null, "title T", "done I", "due D", "created I", "done_at I", "category F:categories", "priority I",
                    "position R", "time I", "repeat T", "notes T", "snooze I"),
            new Table("subtasks", null, "task F:tasks", "text T", "done I", "position I"),
            new Table("grocery", null, "name T", "shop F:shops", "done I", "created I", "qty T", "list F:grocery_lists", "aisle T"),
            new Table("grocery_history", "'gh:' || lower(NEW.name)", "name T", "shop F:shops", "uses I", "last_used I", "list F:grocery_lists"),
            new Table("prices", null, "name T", "shop F:shops", "cents I", "qty T", "day D"),
            new Table("expenses", null, "trip F:trips", "day D", "category T", "cents I", "currency T", "merchant T", "attendees T",
                    "payment T", "note T", "submitted I", "created I", "km R"),
            new Table("trip_items", null, "trip F:trips", "kind T", "subtype T", "title T", "body T", "day D", "done I", "created I"),
            new Table("habit_log", "'hl:' || (SELECT uid FROM habits WHERE _id = NEW.habit) || ':' || " + DAY_SQL,
                    "habit F:habits", "day D", "count I"),
            new Table("checklist_items", null, "list F:checklists", "text T", "done I", "position I"),
            new Table("attachments", null, "owner T", "owner_id O", "file T", "name T", "created I"),
    };

    /** attachments.owner → table. */
    static String ownerTable(String owner) {
        if ("note".equals(owner)) return "notes";
        if ("diary".equals(owner)) return "diary";
        if ("expense".equals(owner)) return "expenses";
        if ("trip_item".equals(owner)) return "trip_items";
        if ("wallet".equals(owner)) return "wallet";
        return null;
    }

    static Table table(String name) {
        for (Table t : TABLES) if (t.name.equals(name)) return t;
        return null;
    }

    static final long TOMBSTONE_MS = 180L * 86400000L;

    /** Result of merging: the new document tables and the rows that must be written locally. */
    static final class Merged {
        final JSONObject tables = new JSONObject();
        final List<Object[]> toApply = new ArrayList<Object[]>(); // {table, uid, entry}
    }

    /**
     * Per row the higher m wins; on a tie the document's (remote) version. Tombstones older than 180 days are
     * dropped. Same rules as the web app (sync.js mergeTables).
     */
    static Merged merge(JSONObject local, JSONObject remote, long now) throws org.json.JSONException {
        Merged out = new Merged();
        long cutoff = now - TOMBSTONE_MS;
        List<String> names = new ArrayList<String>();
        for (Iterator<String> it = local.keys(); it.hasNext(); ) names.add(it.next());
        for (Iterator<String> it = remote.keys(); it.hasNext(); ) {
            String n = it.next();
            if (!names.contains(n)) names.add(n);
        }
        for (String table : names) {
            JSONObject l = local.optJSONObject(table), r = remote.optJSONObject(table);
            if (l == null) l = new JSONObject();
            if (r == null) r = new JSONObject();
            JSONObject merged = new JSONObject();
            List<String> uids = new ArrayList<String>();
            for (Iterator<String> it = l.keys(); it.hasNext(); ) uids.add(it.next());
            for (Iterator<String> it = r.keys(); it.hasNext(); ) {
                String u = it.next();
                if (!l.has(u)) uids.add(u);
            }
            for (String uid : uids) {
                JSONObject le = l.optJSONObject(uid), re = r.optJSONObject(uid);
                JSONObject win = re == null ? le : le == null ? re : le.optLong("m") > re.optLong("m") ? le : re;
                boolean dead = win.optInt("d") == 1;
                if (dead && win.optLong("m") < cutoff) continue;
                merged.put(uid, win);
                if (win != le && (le == null || le.optLong("m") != win.optLong("m") || (le.optInt("d") == 1) != dead)) {
                    out.toApply.add(new Object[]{table, uid, win});
                }
            }
            out.tables.put(table, merged);
        }
        return out;
    }
}
