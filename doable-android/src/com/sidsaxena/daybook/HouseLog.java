package com.sidsaxena.daybook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What other members did in shared spaces ("Anna ticked Milk", "Ben assigned you Fix the tap"), like the web app's
 * activity log in spaces.js, and the notifications for it and for the server's notices (invitations, someone joined).
 * Android has no push service here, so these arrive with the background job (about every 15 minutes) or while Doable is open.
 */
final class HouseLog {
    private HouseLog() {}

    static final String CHANNEL = "household";
    private static final List<JSONObject> fresh = new ArrayList<JSONObject>();

    private static SharedPreferences prefs(Context c) { return Spaces.prefs(c); }

    /** [{space, by, name, text, table, uid, at, mine}], newest first, at most 300. */
    static synchronized List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("activity", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    static List<JSONObject> forSpace(Context c, String id) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject e : list(c)) if (id.equals(e.optString("space"))) out.add(e);
        return out;
    }

    private static boolean on(Object v) {
        return v instanceof Boolean ? (Boolean) v : v instanceof Number ? ((Number) v).longValue() != 0 : v != null && "true".equals(v.toString());
    }

    static String title(String t, JSONObject r) {
        if (r == null) return "";
        String s = "notes".equals(t) ? r.optString("body").split("\n")[0].replaceFirst("^#+\\s*", "")
                : first(r.optString("title"), r.optString("name"), r.optString("text"));
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String first(String... s) {
        for (String x : s) if (!x.isEmpty()) return x;
        return "";
    }

    /** "added Milk", "completed Call plumber", "assigned you Fix the tap" … or null for nothing worth telling. */
    static String describe(String t, JSONObject before, JSONObject after, String me) {
        JSONObject r = after.optJSONObject("r");
        JSONObject old = before != null && before.optInt("d") != 1 ? before.optJSONObject("r") : null;
        String what = title(t, r != null ? r : old);
        if (after.optInt("d") == 1) return old != null ? "removed " + what : null;
        if (r == null || "grocery_history".equals(t) || "shops".equals(t) || ("subtasks".equals(t) && old == null)) return null;
        String assignee = r.optString("assignee");
        if (old == null && "tasks".equals(t) && !assignee.isEmpty() && assignee.equals(me)) return "assigned you " + what;
        if (old == null) {
            if ("shared_expenses".equals(t)) return "settle".equals(r.optString("kind")) ? "recorded a payment" : "added the expense " + what;
            String verb = "tasks".equals(t) ? "added the task " : "notes".equals(t) ? "added the note " : "checklists".equals(t) ? "added the checklist "
                    : "dates".equals(t) ? "added the date " : "wallet".equals(t) ? "added the card " : "grocery_lists".equals(t) ? "made the list "
                    : "categories".equals(t) ? "made the category " : "added ";
            return verb + what;
        }
        if (r.has("done") && on(r.opt("done")) != on(old.opt("done"))) return (on(r.opt("done")) ? ("tasks".equals(t) ? "completed " : "ticked ") : "unticked ") + what;
        if ("tasks".equals(t) && !assignee.equals(old.optString("assignee"))) {
            return assignee.equals(me) ? "assigned you " + what : !assignee.isEmpty() ? "assigned " + what : "unassigned " + what;
        }
        return "changed " + what;
    }

    static String nameOf(Context c, String spaceId, String userId) {
        JSONObject sp = Spaces.get(c, spaceId);
        JSONArray m = sp == null ? null : sp.optJSONArray("members");
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject x = m.optJSONObject(i);
            if (userId.equals(x.optString("user_id"))) {
                String n = x.optString("name");
                return (n.isEmpty() ? x.optString("email") : n).split(" ")[0];
            }
        }
        return "Someone";
    }

    /** A row another member changed arrived (called from Spaces.pull, before it is applied). */
    static synchronized void note(Context c, String spaceId, String by, String t, String uid, JSONObject before, JSONObject after) {
        String me = Account.user(c).optString("id");
        String text = describe(t, before, after, me);
        if (text == null) return;
        JSONObject r = after.optJSONObject("r");
        try {
            fresh.add(new JSONObject().put("space", spaceId).put("by", by).put("name", nameOf(c, spaceId, by)).put("text", text)
                    .put("table", t).put("uid", uid).put("at", System.currentTimeMillis())
                    .put("mine", "tasks".equals(t) && r != null && me.equals(r.optString("assignee")) && text.startsWith("assigned you")));
        } catch (Exception ignored) {
        }
    }

    /**
     * Saves this round's entries; one notification for them when Doable isn't in front (background job), or for
     * assignments to me at any time. Returns the summary when it wasn't shown as a notification (for a short message), else null.
     */
    static synchronized String announce(Context c, boolean inFront) {
        if (fresh.isEmpty()) return null;
        List<JSONObject> got = new ArrayList<JSONObject>(fresh);
        fresh.clear();
        JSONArray all = new JSONArray();
        for (int i = got.size() - 1; i >= 0; i--) all.put(got.get(i));
        for (JSONObject e : list(c)) {
            if (all.length() >= 300) break;
            all.put(e);
        }
        prefs(c).edit().putString("activity", all.toString()).apply();
        JSONObject first = null;
        for (JSONObject e : got) if (e.optBoolean("mine")) {
            first = e;
            break;
        }
        boolean notify = !inFront || first != null;
        if (first == null) first = got.get(0);
        int more = got.size() - 1;
        String body = first.optString("name") + " " + first.optString("text") + (more > 0 ? " (+" + more + " more)" : "");
        if (!notify) return body;
        JSONObject sp = Spaces.get(c, first.optString("space"));
        String where = sp == null ? "Household" : "household".equals(sp.optString("kind")) ? sp.optString("name") : "Shared with you";
        show(c, ("activity:" + first.optString("space")).hashCode(), where, body);
        return null;
    }

    /** The server's notices since last time (invitations, someone joined); assignments and payments come with the rows. */
    static void notices(Context c) {
        if (!Account.signedIn(c)) return;
        SharedPreferences p = prefs(c);
        boolean firstTime = !p.contains("notice_id");
        try {
            JSONArray a = Spaces.api(c, "notices", new JSONObject().put("since", p.getLong("notice_id", 0))).optJSONArray("notices");
            long last = p.getLong("notice_id", 0);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject n = a.getJSONObject(i);
                last = Math.max(last, n.optLong("id"));
                String kind = n.optString("kind");
                if (firstTime || !("invite".equals(kind) || "joined".equals(kind))) continue;
                show(c, ("notice:" + n.optLong("id")).hashCode(), n.optString("title"), n.optString("body"));
            }
            p.edit().putLong("notice_id", last).apply();
        } catch (Exception ignored) {
            // offline: next time
        }
    }

    @SuppressWarnings("deprecation")
    static void show(Context c, int nid, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Household & sharing", NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        Intent open = new Intent(c, MainActivity.class).putExtra("household", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(R.drawable.ic_todo)
                .setColor(c.getResources().getColor(R.color.primary))
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT < 26) b.setDefaults(Notification.DEFAULT_ALL);
        try {
            nm.notify(nid, b.build());
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }
}
