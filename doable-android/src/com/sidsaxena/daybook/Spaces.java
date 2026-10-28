package com.sidsaxena.daybook;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Households and sharing, like the web app's spaces.js: which spaces this person is in, keeping their shared rows in
 * step with the server (sealed with each space's key, see E2e and doable/SHARING.md), and invitations. A row belongs to
 * a space through its own "space" column (lists, categories, tasks, notes, checklists, dates, wallet) or through its
 * parent (items of a shared list, subtasks…). Network calls block: run off the UI thread.
 */
final class Spaces {
    static final String[] SYNCED = {"grocery_lists", "grocery", "grocery_history", "categories", "tasks", "subtasks", "notes",
            "checklists", "checklist_items", "dates", "wallet", "shared_expenses"};
    private static final Set<String> OWN = new HashSet<String>(java.util.Arrays.asList(Db.SHAREABLE));
    static {
        OWN.add("shared_expenses");
    }
    private static final Map<String, String[]> PARENT = new HashMap<String, String[]>();
    static {
        PARENT.put("grocery", new String[]{"list", "grocery_lists"});
        PARENT.put("grocery_history", new String[]{"list", "grocery_lists"});
        PARENT.put("subtasks", new String[]{"task", "tasks"});
        PARENT.put("checklist_items", new String[]{"list", "checklists"});
        PARENT.put("tasks", new String[]{"category", "categories"});
        PARENT.put("categories", new String[]{"parent", "categories"});
    }
    static final String[][] MODULES = {{"grocery", "🛒 Groceries"}, {"tasks", "✅ To-do"}, {"checklists", "☑️ Checklists"},
            {"dates", "🎂 Important dates"}, {"notes", "📝 Notes"}, {"wallet", "💳 Wallet"}, {"split", "💶 Shared expenses"}};
    private static final String ID_FILE = "doable.identity";
    private static final String ALIAS = "doable_identity";

    private static final Map<String, byte[]> keys = new HashMap<String, byte[]>(); // space id → raw key
    private static JSONObject state;

    private Spaces() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("spaces", Context.MODE_PRIVATE);
    }

    /** {list, incoming, cursors, pushed, where} — see the web app's spaces.js. */
    static synchronized JSONObject state(Context c) {
        if (state == null) {
            try {
                state = new JSONObject(prefs(c).getString("state", "{}"));
            } catch (Exception e) {
                state = new JSONObject();
            }
            for (String k : new String[]{"cursors", "pushed", "where"}) if (state.optJSONObject(k) == null) put(state, k, new JSONObject());
            if (state.optJSONArray("list") == null) put(state, "list", new JSONArray());
            if (state.optJSONArray("incoming") == null) put(state, "incoming", new JSONArray());
        }
        return state;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    static synchronized void save(Context c) {
        prefs(c).edit().putString("state", state(c).toString()).apply();
    }

    static List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray a = state(c).optJSONArray("list");
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optJSONObject(i));
        return out;
    }

    static List<JSONObject> households(Context c) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject s : list(c)) if ("household".equals(s.optString("kind"))) out.add(s);
        return out;
    }

    static List<JSONObject> incoming(Context c) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray a = state(c).optJSONArray("incoming");
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optJSONObject(i));
        return out;
    }

    static JSONObject get(Context c, String id) {
        for (JSONObject s : list(c)) if (s.optString("id").equals(id)) return s;
        return null;
    }

    static boolean ready(String id) {
        synchronized (keys) {
            return keys.containsKey(id);
        }
    }

    static boolean shares(JSONObject space, String module) {
        JSONArray m = space.optJSONArray("modules");
        for (int i = 0; m != null && i < m.length(); i++) if (module.equals(m.optString(i))) return true;
        return false;
    }

    static List<JSONObject> members(Context c, String spaceId) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONObject sp = spaceId == null || spaceId.isEmpty() ? null : get(c, spaceId);
        JSONArray m = sp == null ? null : sp.optJSONArray("members");
        for (int i = 0; m != null && i < m.length(); i++) out.add(m.optJSONObject(i));
        return out;
    }

    /** First name (or "Me") of every member of every space, by user id. */
    static Map<String, String> names(Context c) {
        Map<String, String> out = new HashMap<String, String>();
        String me = Account.user(c).optString("id");
        for (JSONObject sp : list(c)) {
            for (JSONObject m : members(c, sp.optString("id"))) {
                String n = m.optString("name");
                out.put(m.optString("user_id"), m.optString("user_id").equals(me) ? "Me" : (n.isEmpty() ? m.optString("email") : n).split(" ")[0]);
            }
        }
        return out;
    }

    /** Tells a member about something only devices know (assigned a task, recorded a payment); best effort, off the UI thread. */
    static void tell(final Context c, final String spaceId, final String userId, final String kind) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    api(c, "notify", new JSONObject().put("id", spaceId).put("user_id", userId).put("kind", kind));
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    static String moduleName(String id) {
        for (String[] m : MODULES) if (m[0].equals(id)) return m[1];
        return id;
    }

    static JSONObject api(Context c, String action, JSONObject body) throws Exception {
        return Account.call("api/spaces", action, body, Account.session(c));
    }

    // ---------------------------------------------------------------- my key pair

    /** {public, private (PKCS#8)}: from this phone, else from my Google Drive, else new (saved to Drive for my other devices). */
    static synchronized String[] identity(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String pub = p.getString("pub", null), priv = p.getString("priv", null);
        if (pub != null && priv != null) return new String[]{pub, Secrets.unwrapString(ALIAS, priv)};
        GoogleDrive g = new GoogleDrive(c);
        g.list();
        byte[] b = g.download(ID_FILE);
        String[] id;
        if (b != null) {
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            id = new String[]{o.getString("pub"), o.getString("priv")};
        } else {
            id = E2e.generate();
            g.upload(ID_FILE, new JSONObject().put("v", 1).put("pub", id[0]).put("priv", id[1]).toString().getBytes("UTF-8"));
        }
        p.edit().putString("pub", id[0]).putString("priv", Secrets.wrap(ALIAS, id[1])).commit();
        return id;
    }

    // ---------------------------------------------------------------- keeping in step

    /** Spaces, members, invitations; opens space keys; helps finish invitations and new keys of other members. */
    static void refresh(Context c) throws Exception {
        if (!Account.signedIn(c)) return;
        JSONObject j = api(c, "list", new JSONObject());
        String[] me = null;
        try {
            me = identity(c);
        } catch (Exception e) {
            // Google Drive not reachable yet: keys come later
        }
        if (me != null && !me[0].equals(j.optString("public_key"))) {
            try {
                api(c, "key_set", new JSONObject().put("public_key", me[0]));
            } catch (Exception ignored) {
            }
        }
        Set<String> before = new HashSet<String>();
        for (JSONObject s : list(c)) before.add(s.optString("id"));
        synchronized (Spaces.class) {
            put(state(c), "list", j.optJSONArray("spaces"));
            put(state(c), "incoming", j.optJSONArray("incoming"));
        }
        if (me != null) {
            String myId = E2e.keyId(me[0]);
            for (JSONObject sp : list(c)) {
                String id = sp.optString("id");
                if (!ready(id) && !sp.optString("wrapped_key").isEmpty() && myId.equals(sp.optString("key_id"))) {
                    try {
                        byte[] raw = E2e.unwrap(me[0], me[1], sp.optString("wrapped_key"));
                        synchronized (keys) {
                            keys.put(id, raw);
                        }
                    } catch (Exception ignored) {
                        // wrapped for an older key: another member's device fixes it
                    }
                }
                byte[] raw;
                synchronized (keys) {
                    raw = keys.get(id);
                }
                if (raw == null) continue;
                JSONArray members = sp.optJSONArray("members");
                for (int i = 0; members != null && i < members.length(); i++) {
                    JSONObject m = members.getJSONObject(i);
                    String pk = m.optString("public_key");
                    if (!pk.isEmpty() && !E2e.keyId(pk).equals(m.optString("key_id"))) {
                        try {
                            api(c, "rewrap", new JSONObject().put("id", id).put("user_id", m.optString("user_id"))
                                    .put("wrapped_key", E2e.wrapFor(pk, raw)).put("key_id", E2e.keyId(pk)));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            try {
                JSONArray tw = api(c, "invites_to_wrap", new JSONObject()).optJSONArray("invites");
                for (int i = 0; tw != null && i < tw.length(); i++) {
                    JSONObject x = tw.getJSONObject(i);
                    byte[] raw;
                    synchronized (keys) {
                        raw = keys.get(x.optString("space_id"));
                    }
                    if (raw != null) api(c, "invite_wrap", new JSONObject().put("invite_id", x.optString("id"))
                            .put("wrapped_key", E2e.wrapFor(x.optString("public_key"), raw)).put("key_id", E2e.keyId(x.optString("public_key"))));
                }
            } catch (Exception ignored) {
            }
        }
        for (String id : before) if (get(c, id) == null) forget(c, id);
        save(c);
    }

    /** Push this phone's changes to every space, then fetch the others'. Returns true when something arrived. */
    static synchronized boolean round(Context c) throws Exception {
        if (!Account.signedIn(c) || list(c).isEmpty()) return false;
        SQLiteDatabase db = Db.get(c).getWritableDatabase();
        JSONObject tables = SyncEngine.export(c, db, new ArrayList<String>());
        boolean got = false;
        for (JSONObject sp : list(c)) {
            String id = sp.optString("id");
            byte[] key;
            synchronized (keys) {
                key = keys.get(id);
            }
            if (key == null) continue;
            push(c, id, key, tables);
            got |= pull(c, id, key, tables, db);
        }
        save(c);
        return got;
    }

    /** The space a row belongs to ("" = private), from exported document tables. */
    static String spaceOf(JSONObject tables, String table, String uid, int depth) {
        JSONObject rows = tables.optJSONObject(table);
        JSONObject e = rows == null ? null : rows.optJSONObject(uid);
        if (e == null || e.optInt("d") == 1) return "";
        JSONObject r = e.optJSONObject("r");
        if (r == null) return "";
        if (OWN.contains(table) && !r.optString("space").isEmpty()) return r.optString("space");
        String[] p = PARENT.get(table);
        if (p != null && !r.optString(p[0]).isEmpty() && depth < 4) return spaceOf(tables, p[1], r.optString(p[0]), depth + 1);
        return "";
    }

    private static JSONObject obj(JSONObject parent, String k) {
        JSONObject o = parent.optJSONObject(k);
        if (o == null) put(parent, k, o = new JSONObject());
        return o;
    }

    private static void push(Context c, String id, byte[] key, JSONObject tables) throws Exception {
        JSONObject pushed = obj(obj(state(c), "pushed"), id);
        JSONObject where = obj(state(c), "where");
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> shops = new HashSet<String>();
        for (String t : SYNCED) {
            JSONObject rows = tables.optJSONObject(t);
            if (rows == null) continue;
            for (Iterator<String> it = rows.keys(); it.hasNext(); ) {
                String uid = it.next();
                JSONObject e = rows.getJSONObject(uid);
                String k = t + "/" + uid;
                long m = e.optLong("m");
                if (pushed.optLong(k) >= m) continue;
                String now = e.optInt("d") == 1 ? "" : spaceOf(tables, t, uid, 0);
                if (now.equals(id)) {
                    out.add(new Object[]{t, uid, e});
                    JSONObject r = e.optJSONObject("r");
                    if (r != null && !r.optString("shop").isEmpty()) shops.add(r.optString("shop"));
                } else if (id.equals(where.optString(k))) {
                    out.add(new Object[]{t, uid, new JSONObject().put("m", m).put("d", 1)}); // deleted or moved out
                }
            }
        }
        JSONObject shopRows = tables.optJSONObject("shops");
        for (String uid : shops) {
            JSONObject e = shopRows == null ? null : shopRows.optJSONObject(uid);
            if (e != null && e.optInt("d") != 1 && pushed.optLong("shops/" + uid) < e.optLong("m")) out.add(new Object[]{"shops", uid, e});
        }
        for (int i = 0; i < out.size(); i += 200) {
            List<Object[]> part = out.subList(i, Math.min(out.size(), i + 200));
            JSONArray rows = new JSONArray();
            for (Object[] x : part) {
                JSONObject e = (JSONObject) x[2];
                rows.put(new JSONObject().put("tbl", x[0]).put("uid", x[1]).put("m", e.optLong("m"))
                        .put("data", E2e.sealRow(key, id, (String) x[0], (String) x[1], e.toString())));
            }
            api(c, "push", new JSONObject().put("id", id).put("rows", rows));
            for (Object[] x : part) {
                String k = x[0] + "/" + x[1];
                JSONObject e = (JSONObject) x[2];
                pushed.put(k, e.optLong("m"));
                if (e.optInt("d") == 1) {
                    if (id.equals(where.optString(k))) where.remove(k);
                } else {
                    where.put(k, id);
                }
            }
        }
    }

    private static boolean pull(Context c, String id, byte[] key, JSONObject tables, SQLiteDatabase db) throws Exception {
        JSONObject pushed = obj(obj(state(c), "pushed"), id);
        JSONObject where = obj(state(c), "where");
        JSONObject cursors = obj(state(c), "cursors");
        long since = cursors.optLong(id);
        List<Object[]> toApply = new ArrayList<Object[]>();
        String me = Account.user(c).optString("id");
        for (int n = 0; n < 50; n++) {
            JSONObject j = api(c, "pull", new JSONObject().put("id", id).put("since", since));
            JSONArray rows = j.optJSONArray("rows");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String t = row.optString("tbl"), uid = row.optString("uid");
                if (SyncSchema.table(t) == null) continue;
                JSONObject entry;
                try {
                    entry = new JSONObject(E2e.openRow(key, id, t, uid, row.optString("data")));
                } catch (Exception e) {
                    continue;
                }
                String k = t + "/" + uid;
                JSONObject local = tables.optJSONObject(t) == null ? null : tables.optJSONObject(t).optJSONObject(uid);
                long m = entry.optLong("m");
                String by = row.optString("by");
                if (!by.isEmpty() && !by.equals(me) && (local == null || m > local.optLong("m"))) HouseLog.note(c, id, by, t, uid, local, entry);
                if (entry.optInt("d") == 1) {
                    if (local != null && local.optInt("d") != 1 && m > local.optLong("m") && id.equals(spaceOf(tables, t, uid, 0))) toApply.add(new Object[]{t, uid, entry});
                    if (id.equals(where.optString(k))) where.remove(k);
                } else {
                    if (local == null || m > local.optLong("m")) toApply.add(new Object[]{t, uid, entry});
                    where.put(k, id);
                }
                pushed.put(k, Math.max(pushed.optLong(k), m));
            }
            since = j.optLong("since", since);
            cursors.put(id, since);
            if (!j.optBoolean("more")) break;
        }
        if (toApply.isEmpty()) return false;
        SyncEngine.apply(c, db, toApply);
        return true;
    }

    /** A space this person left (or was removed from): its rows leave this phone. */
    static void forget(Context c, String id) throws Exception {
        SQLiteDatabase db = Db.get(c).getWritableDatabase();
        JSONObject tables = SyncEngine.export(c, db, new ArrayList<String>());
        List<Object[]> gone = new ArrayList<Object[]>();
        for (String t : SYNCED) {
            JSONObject rows = tables.optJSONObject(t);
            if (rows == null) continue;
            for (Iterator<String> it = rows.keys(); it.hasNext(); ) {
                String uid = it.next();
                JSONObject e = rows.getJSONObject(uid);
                if (e.optInt("d") != 1 && id.equals(spaceOf(tables, t, uid, 0))) gone.add(new Object[]{t, uid, new JSONObject().put("m", e.optLong("m") + 1).put("d", 1)});
            }
        }
        SyncEngine.apply(c, db, gone);
        obj(state(c), "cursors").remove(id);
        obj(state(c), "pushed").remove(id);
        JSONObject where = obj(state(c), "where");
        List<String> drop = new ArrayList<String>();
        for (Iterator<String> it = where.keys(); it.hasNext(); ) {
            String k = it.next();
            if (id.equals(where.optString(k))) drop.add(k);
        }
        for (String k : drop) where.remove(k);
        synchronized (keys) {
            keys.remove(id);
        }
    }

    // ---------------------------------------------------------------- households & invitations

    static String create(Context c, String name, String[] modules, String kind) throws Exception {
        String[] me = identity(c);
        byte[] raw = E2e.random(32);
        JSONArray mods = new JSONArray();
        for (String m : modules) mods.put(m);
        JSONObject j = api(c, "create", new JSONObject().put("name", name).put("kind", kind).put("modules", mods)
                .put("wrapped_key", E2e.wrapFor(me[0], raw)).put("key_id", E2e.keyId(me[0])));
        synchronized (keys) {
            keys.put(j.getString("id"), raw);
        }
        refresh(c);
        return j.getString("id");
    }

    /** Creates an invitation and returns the link to send. */
    static String invite(Context c, String id, String email) throws Exception {
        byte[] raw;
        synchronized (keys) {
            raw = keys.get(id);
        }
        if (raw == null) throw new Exception("This phone can't open this household yet");
        String[] lock = E2e.linkLock(raw);
        JSONObject body = new JSONObject().put("id", id).put("email", email).put("token_hash", lock[1]).put("enc_key", lock[2]);
        if (!email.isEmpty()) {
            JSONObject u = api(c, "user_key", new JSONObject().put("email", email));
            if (u.optBoolean("found") && !u.optString("public_key").isEmpty()) {
                body.put("wrapped_key", E2e.wrapFor(u.optString("public_key"), raw)).put("key_id", E2e.keyId(u.optString("public_key")));
            }
        }
        JSONObject j = api(c, "invite", body);
        refresh(c);
        return Account.SERVER + "?join=" + j.getString("id") + "#" + lock[0];
    }

    /** Joins with the space key (from a link, or wrapped for me in an email invitation). */
    static void join(Context c, String inviteId, byte[] raw, String tokenHash) throws Exception {
        String[] me = identity(c);
        JSONObject body = new JSONObject().put("invite_id", inviteId).put("accept", true)
                .put("wrapped_key", E2e.wrapFor(me[0], raw)).put("key_id", E2e.keyId(me[0]));
        if (tokenHash != null) body.put("token_hash", tokenHash);
        api(c, "invite_answer", body);
        refresh(c);
        round(c);
    }

    static byte[] unwrapForMe(Context c, String wrapped) throws Exception {
        String[] me = identity(c);
        return E2e.unwrap(me[0], me[1], wrapped);
    }

    // ---------------------------------------------------------------- local rows

    /** The space of one local row ("" = private): own column, else its parent's. */
    static String spaceOfRow(Context c, String table, long id) {
        SQLiteDatabase db = Db.get(c).getReadableDatabase();
        for (int depth = 0; depth < 4 && id != 0; depth++) {
            if (OWN.contains(table)) {
                String s = Util.queryString(db, "SELECT space FROM " + table + " WHERE _id = ?", String.valueOf(id));
                if (s != null && !s.isEmpty()) return s;
            }
            String[] p = PARENT.get(table);
            if (p == null) return "";
            Long parent = Util.queryLong(db, "SELECT " + p[0] + " FROM " + table + " WHERE _id = ?", String.valueOf(id));
            table = p[1];
            id = parent == null ? 0 : parent;
        }
        return "";
    }
}
