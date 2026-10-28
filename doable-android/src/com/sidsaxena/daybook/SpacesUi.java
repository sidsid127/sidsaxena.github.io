package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Household & sharing on Android: the household page (members, invitations, shared modules, leave), answering
 * invitations, invite links opened on the phone, and the "Share…" choices the modules offer.
 */
final class SpacesUi {
    private SpacesUi() {}

    interface Job {
        void run() throws Exception;
    }

    /** Runs network work off the UI thread, then `then` on it (or a short message on failure). */
    static void background(final MainActivity a, final Job job, final Runnable then) {
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    job.run();
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final String error = err;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        if (error != null) Toast.makeText(a, "Couldn't do that: " + error, Toast.LENGTH_LONG).show();
                        else if (then != null) then.run();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- household page

    private static Dialog page;
    private static String openId;

    static void show(final MainActivity a, String spaceId) {
        openId = spaceId;
        if (page == null || !page.isShowing()) {
            page = new Dialog(a, R.style.AppTheme);
            page.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) { page = null; }
            });
            page.show();
        }
        render(a);
        background(a, new Job() {
            public void run() throws Exception { Spaces.refresh(a); }
        }, new Runnable() {
            public void run() { if (page != null) render(a); }
        });
    }

    private static void render(final MainActivity a) {
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Util.color(a, R.color.bg));
        final JSONObject sp = openId == null ? null : Spaces.get(a, openId);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Util.color(a, R.color.primary));
        bar.setPadding(Util.dp(a, 4), 0, Util.dp(a, 16), 0);
        TextView back = AccountUi.text(a, "←", 24, R.color.text, false);
        back.setTextColor(0xFFFFFFFF);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (sp != null) {
                    openId = null;
                    render(a);
                } else page.dismiss();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Util.dp(a, 52), Util.dp(a, 56)));
        TextView title = AccountUi.text(a, sp == null ? "Household & sharing" : label(a, sp), 20, R.color.text, true);
        title.setTextColor(0xFFFFFFFF);
        bar.addView(title);
        outer.addView(bar);
        ScrollView scroll = new ScrollView(a);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Util.dp(a, 12), Util.dp(a, 8), Util.dp(a, 12), Util.dp(a, 32));
        scroll.addView(body);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (sp == null) overview(a, body);
        else detail(a, body, sp);
        page.setContentView(outer);
    }

    private static String label(Context c, JSONObject sp) {
        if ("household".equals(sp.optString("kind"))) return sp.optString("name");
        String me = Account.user(c).optString("id");
        StringBuilder s = new StringBuilder();
        JSONArray m = sp.optJSONArray("members");
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject x = m.optJSONObject(i);
            if (x.optString("user_id").equals(me)) continue;
            if (s.length() > 0) s.append(", ");
            String n = x.optString("name");
            s.append(n.isEmpty() ? x.optString("email") : n.split(" ")[0]);
        }
        return s.length() == 0 ? "Shared" : s.toString();
    }

    private static void overview(final MainActivity a, LinearLayout body) {
        List<JSONObject> inc = Spaces.incoming(a);
        if (!inc.isEmpty()) {
            AccountUi.section(a, body, "Invitations for you");
            LinearLayout c = AccountUi.card(a);
            for (final JSONObject i : inc) {
                JSONObject from = i.optJSONObject("from");
                AccountUi.row(a, c, "household".equals(i.optString("kind")) ? "🏠" : "👥",
                        "household".equals(i.optString("kind")) ? i.optString("name") : "Shared with you",
                        "From " + (from == null ? "" : from.optString("name", from.optString("email")))
                                + (i.optString("wrapped_key").isEmpty() ? " · waiting for their Doable — or use their link" : " · tap to join"), new Runnable() {
                            public void run() { answer(a, i); }
                        });
            }
            body.addView(c);
        }
        AccountUi.section(a, body, "Households");
        LinearLayout homes = AccountUi.card(a);
        for (final JSONObject s : Spaces.households(a)) {
            JSONArray m = s.optJSONArray("members");
            int n = m == null ? 0 : m.length();
            AccountUi.row(a, homes, "🏠", s.optString("name"), n + (n == 1 ? " member" : " members") + " · " + modules(s)
                    + (Spaces.ready(s.optString("id")) ? "" : " · ⏳ unlocking…"), new Runnable() {
                public void run() {
                    openId = s.optString("id");
                    render(a);
                }
            });
        }
        AccountUi.row(a, homes, "➕", Spaces.households(a).isEmpty() ? "Create a household" : "Create another household",
                "Share groceries, to-dos, checklists and more with your family", new Runnable() {
                    public void run() { createHousehold(a); }
                });
        AccountUi.row(a, homes, "🔗", "Join with an invitation link", "Paste the link someone sent you", new Runnable() {
            public void run() { pasteLink(a, null); }
        });
        body.addView(homes);
        TextView note = AccountUi.text(a, "Everything you share is end-to-end encrypted: only the members can read it — not Doable, not Google.", 13, R.color.muted, false);
        note.setPadding(Util.dp(a, 6), Util.dp(a, 6), Util.dp(a, 6), 0);
        body.addView(note);
        List<JSONObject> pairs = new ArrayList<JSONObject>();
        for (JSONObject s : Spaces.list(a)) if ("share".equals(s.optString("kind"))) pairs.add(s);
        if (!pairs.isEmpty()) {
            AccountUi.section(a, body, "Shared with people");
            LinearLayout c = AccountUi.card(a);
            for (final JSONObject s : pairs) {
                AccountUi.row(a, c, "👥", label(a, s), "Tap to see or stop", new Runnable() {
                    public void run() {
                        openId = s.optString("id");
                        render(a);
                    }
                });
            }
            body.addView(c);
        }
    }

    private static String modules(JSONObject s) {
        JSONArray m = s.optJSONArray("modules");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; m != null && i < m.length(); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Spaces.moduleName(m.optString(i)).replaceFirst("^\\S+ ", ""));
        }
        return sb.length() == 0 ? "nothing shared yet" : sb.toString();
    }

    private static void detail(final MainActivity a, LinearLayout body, final JSONObject sp) {
        final String id = sp.optString("id");
        final boolean owner = "owner".equals(sp.optString("role"));
        final boolean household = "household".equals(sp.optString("kind"));
        final String me = Account.user(a).optString("id");
        if (!Spaces.ready(id)) {
            TextView t = AccountUi.text(a, "⏳ This phone is still getting the key for this space. Another member's Doable needs to be open once; nothing to do on your side.", 13, R.color.muted, false);
            t.setPadding(Util.dp(a, 6), Util.dp(a, 8), Util.dp(a, 6), Util.dp(a, 4));
            body.addView(t);
        }
        AccountUi.section(a, body, "Members");
        LinearLayout people = AccountUi.card(a);
        JSONArray m = sp.optJSONArray("members");
        for (int i = 0; m != null && i < m.length(); i++) {
            final JSONObject x = m.optJSONObject(i);
            final boolean self = x.optString("user_id").equals(me);
            String n = x.optString("name");
            AccountUi.row(a, people, "owner".equals(x.optString("role")) ? "👑" : "👤", (n.isEmpty() ? x.optString("email") : n) + (self ? " (you)" : ""),
                    x.optString("email") + ("owner".equals(x.optString("role")) ? " · owner" : ""), owner && !self ? new Runnable() {
                        public void run() {
                            Util.confirm(a, "Remove " + x.optString("email") + "?", "They lose access to everything shared here.", "Remove", new Runnable() {
                                public void run() {
                                    background(a, new Job() {
                                        public void run() throws Exception {
                                            Spaces.api(a, "remove_member", new JSONObject().put("id", id).put("user_id", x.optString("user_id")));
                                            Spaces.refresh(a);
                                        }
                                    }, new Runnable() {
                                        public void run() { render(a); }
                                    });
                                }
                            });
                        }
                    } : null);
        }
        JSONArray inv = sp.optJSONArray("invites");
        for (int i = 0; inv != null && i < inv.length(); i++) {
            final JSONObject x = inv.optJSONObject(i);
            AccountUi.row(a, people, "✉️", x.optString("email").isEmpty() ? "Invitation link" : x.optString("email"), "Invited · not joined yet · tap to cancel", new Runnable() {
                public void run() {
                    Util.confirm(a, "Cancel this invitation?", null, "Cancel invitation", new Runnable() {
                        public void run() {
                            background(a, new Job() {
                                public void run() throws Exception {
                                    Spaces.api(a, "invite_cancel", new JSONObject().put("invite_id", x.optString("id")));
                                    Spaces.refresh(a);
                                }
                            }, new Runnable() {
                                public void run() { render(a); }
                            });
                        }
                    });
                }
            });
        }
        AccountUi.row(a, people, "➕", "Invite someone", "By link — WhatsApp, SMS, email…", new Runnable() {
            public void run() { invite(a, sp); }
        });
        body.addView(people);

        if (household) {
            AccountUi.section(a, body, "Shared in this household");
            LinearLayout mods = AccountUi.card(a);
            for (final String[] mod : Spaces.MODULES) {
                final boolean on = Spaces.shares(sp, mod[0]);
                AccountUi.row(a, mods, mod[1].split(" ")[0], mod[1].replaceFirst("^\\S+ ", "") + (on ? "  ✓" : ""),
                        on ? "Shared — use \"Share…\" on a list, category, note…" : "Not shared", new Runnable() {
                            public void run() {
                                if (!owner) {
                                    Toast.makeText(a, "Only the owner can change this", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                background(a, new Job() {
                                    public void run() throws Exception {
                                        JSONArray now = new JSONArray();
                                        for (String[] x : Spaces.MODULES) {
                                            boolean was = Spaces.shares(sp, x[0]);
                                            if (x[0].equals(mod[0]) ? !was : was) now.put(x[0]);
                                        }
                                        Spaces.api(a, "update", new JSONObject().put("id", id).put("modules", now));
                                        Spaces.refresh(a);
                                    }
                                }, new Runnable() {
                                    public void run() { render(a); }
                                });
                            }
                        });
            }
            body.addView(mods);
        }

        AccountUi.section(a, body, "");
        LinearLayout end = AccountUi.card(a);
        if (owner && household) {
            AccountUi.row(a, end, "✏️", "Rename", sp.optString("name"), new Runnable() {
                public void run() {
                    Util.prompt(a, "Household name", sp.optString("name"), "", "Save", new Util.TextEntered() {
                        public void onText(final String n) {
                            background(a, new Job() {
                                public void run() throws Exception {
                                    Spaces.api(a, "update", new JSONObject().put("id", id).put("name", n));
                                    Spaces.refresh(a);
                                }
                            }, new Runnable() {
                                public void run() { render(a); }
                            });
                        }
                    });
                }
            });
        }
        TextView leave = AccountUi.row(a, end, "🚪", "Leave", "Shared things are removed from your devices", new Runnable() {
            public void run() {
                Util.confirm(a, "Leave " + (household ? sp.optString("name") : "this sharing") + "?", "Everything shared here is removed from your devices.", "Leave", new Runnable() {
                    public void run() {
                        background(a, new Job() {
                            public void run() throws Exception {
                                Spaces.api(a, "leave", new JSONObject().put("id", id));
                                Spaces.refresh(a);
                            }
                        }, new Runnable() {
                            public void run() {
                                openId = null;
                                render(a);
                                a.reloadCurrent();
                            }
                        });
                    }
                });
            }
        });
        leave.setTextColor(Util.color(a, R.color.overdue));
        body.addView(end);
    }

    private static void createHousehold(final MainActivity a) {
        String last = Account.displayName(a).trim();
        String[] w = last.split(" ");
        Util.prompt(a, "Name your household", (w.length > 1 ? w[w.length - 1] : last) + " Home", "e.g. Saxena Home", "Create", new Util.TextEntered() {
            public void onText(final String name) {
                final String[] made = new String[1];
                background(a, new Job() {
                    public void run() throws Exception {
                        made[0] = Spaces.create(a, name, new String[]{"grocery", "tasks", "checklists", "dates"}, "household");
                    }
                }, new Runnable() {
                    public void run() {
                        openId = made[0];
                        render(a);
                        invite(a, Spaces.get(a, made[0]));
                    }
                });
            }
        });
    }

    static void invite(final MainActivity a, final JSONObject sp) {
        if (sp == null) return;
        LinearLayout box = Util.dialogBox(a);
        box.addView(AccountUi.text(a, "You'll get a link to send by WhatsApp, SMS or email. If they already use Doable with this email, they also see the invitation in the app.\n\nWithout an email, whoever opens the link first can join.", 14, R.color.text, false));
        final EditText email = Util.field(a, "");
        email.setHint("Their email (optional)");
        email.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Util.dp(a, 12), 0, 0);
        box.addView(email, lp);
        new AlertDialog.Builder(a).setTitle("Invite to " + label(a, sp)).setView(Util.wrap(a, box))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create link", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String e = email.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                        final String[] link = new String[1];
                        background(a, new Job() {
                            public void run() throws Exception { link[0] = Spaces.invite(a, sp.optString("id"), e); }
                        }, new Runnable() {
                            public void run() {
                                if (page != null) render(a);
                                send(a, sp, link[0]);
                            }
                        });
                    }
                }).show();
    }

    /** Android's share sheet with the invitation text (WhatsApp, Messages, email…). */
    static void send(MainActivity a, JSONObject sp, String link) {
        String who = Account.displayName(a);
        String mods = modules(sp);
        String text = who + " invited you to " + ("household".equals(sp.optString("kind")) ? "\"" + sp.optString("name") + "\"" : "share")
                + " on Doable" + (mods.startsWith("nothing") ? "" : " to share " + mods) + ".\n\nJoin here: " + link
                + "\n\nDoable for Android: " + Account.SERVER + "android/Doable.apk\niPhone and Mac: open the link in Safari or Chrome.";
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, "Join " + sp.optString("name") + " on Doable");
        a.startExternal(Intent.createChooser(i, "Send the invitation"));
    }

    // ---------------------------------------------------------------- answering invitations

    private static final java.util.Set<String> shown = new java.util.HashSet<String>();

    /** Invitations to my email: asked once per start. */
    static void promptInvites(MainActivity a) {
        for (JSONObject i : Spaces.incoming(a)) {
            if (shown.add(i.optString("id"))) answer(a, i);
        }
    }

    /**
     * An invitation to my email. It can be opened here once a member's device wrapped the household key for my
     * current key; until then (invited before having Doable, or a new key since) the invitation link works.
     */
    static void answer(final MainActivity a, final JSONObject i) {
        JSONObject from = i.optJSONObject("from");
        final String who = from == null ? "Someone" : from.optString("name", from.optString("email"));
        final boolean[] ready = new boolean[1];
        background(a, new Job() {
            public void run() throws Exception {
                ready[0] = !i.optString("wrapped_key").isEmpty();
                if (ready[0] && !i.optString("key_id").isEmpty()) {
                    try {
                        if (!E2e.keyId(Spaces.identity(a)[0]).equals(i.optString("key_id"))) {
                            Spaces.api(a, "invite_reset", new JSONObject().put("invite_id", i.optString("id")));
                            ready[0] = false;
                        }
                    } catch (Exception ignored) {
                        // Google not reachable: try anyway
                    }
                }
            }
        }, new Runnable() {
            public void run() {
                ask(a, i.optString("kind"), i.optString("name"), who, i.optJSONArray("modules"), ready[0] ? new Job() {
                    public void run() throws Exception {
                        Spaces.join(a, i.optString("id"), Spaces.unwrapForMe(a, i.optString("wrapped_key")), null);
                    }
                } : null, new Job() {
                    public void run() throws Exception {
                        Spaces.api(a, "invite_answer", new JSONObject().put("invite_id", i.optString("id")).put("accept", false));
                        Spaces.refresh(a);
                    }
                });
            }
        });
    }

    /** "…?join=ID#TOKEN" → {id, token}, or null. */
    static String[] parseLink(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]join=([0-9a-f]+)#([\\w-]{16,})").matcher(text == null ? "" : text.trim());
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    /** Paste an invitation link (it opened somewhere else, or the in-app invitation isn't ready yet). */
    static void pasteLink(final MainActivity a, String why) {
        LinearLayout box = Util.dialogBox(a);
        if (why != null) box.addView(AccountUi.text(a, why, 15, R.color.text, false));
        box.addView(AccountUi.text(a, "\nPaste the invitation link you were sent (WhatsApp, SMS, email…):", 14, R.color.muted, false));
        final EditText field = Util.field(a, "");
        field.setHint("https://doable-sid.vercel.app/?join=…");
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(a);
            if (parseLink(String.valueOf(clip)) != null) field.setText(clip); // a copied link is filled in already
        }
        box.addView(field);
        new AlertDialog.Builder(a).setTitle(why != null ? "Almost there" : "Join with an invitation link").setView(Util.wrap(a, box))
                .setNegativeButton("Close", null)
                .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String[] l = parseLink(field.getText().toString());
                        if (l == null) Toast.makeText(a, "That isn't a Doable invitation link", Toast.LENGTH_LONG).show();
                        else openLink(a, l[0], l[1]);
                    }
                }).show();
    }

    /** An invite link opened on this phone (https://…/?join=ID#TOKEN). */
    static void openLink(final MainActivity a, final String inviteId, final String token) {
        shown.add(inviteId);
        final JSONObject[] inv = new JSONObject[1];
        background(a, new Job() {
            public void run() throws Exception {
                inv[0] = Spaces.api(a, "invite_open", new JSONObject().put("invite_id", inviteId).put("token_hash", E2e.linkHash(token)));
            }
        }, new Runnable() {
            public void run() {
                final JSONObject x = inv[0];
                if (x.optBoolean("already")) {
                    Toast.makeText(a, "You're already in " + x.optString("name"), Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONObject from = x.optJSONObject("from");
                ask(a, x.optString("kind"), x.optString("name"), from == null ? "Someone" : from.optString("name", from.optString("email")), x.optJSONArray("modules"), new Job() {
                    public void run() throws Exception {
                        Spaces.join(a, inviteId, E2e.linkUnlock(token, x.optString("enc_key")), E2e.linkHash(token));
                    }
                }, new Job() {
                    public void run() throws Exception {
                        Spaces.api(a, "invite_answer", new JSONObject().put("invite_id", inviteId).put("accept", false).put("token_hash", E2e.linkHash(token)));
                    }
                });
            }
        });
    }

    private static void ask(final MainActivity a, final String kind, final String name, final String who, JSONArray modules, final Job join, final Job reject) {
        StringBuilder mods = new StringBuilder();
        for (int i = 0; modules != null && i < modules.length(); i++) mods.append(i == 0 ? "" : ", ").append(Spaces.moduleName(modules.optString(i)));
        final boolean household = "household".equals(kind);
        new AlertDialog.Builder(a)
                .setTitle(household ? "🏠 " + name : "👥 Shared with you")
                .setMessage(who + " invited you to join " + (household ? "the household \"" + name + "\"" : "sharing in Doable") + "."
                        + (mods.length() > 0 ? "\n\nShared: " + mods : "") + "\n\nEverything shared stays end-to-end encrypted — only the members can read it.")
                .setNeutralButton("Not now", null)
                .setNegativeButton("Reject", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        background(a, reject, new Runnable() {
                            public void run() {
                                Toast.makeText(a, "Invitation rejected", Toast.LENGTH_SHORT).show();
                                if (page != null) render(a);
                            }
                        });
                    }
                })
                .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (join == null) {
                            pasteLink(a, who + " invited you before you had Doable, so their Doable has to finish the invitation — it does that by itself "
                                    + "the next time it is open (on Android also in the background, within about 15 minutes). To join right away, use the invitation link "
                                    + who + " sent you.");
                            return;
                        }
                        Toast.makeText(a, "Joining…", Toast.LENGTH_SHORT).show();
                        background(a, join, new Runnable() {
                            public void run() {
                                Toast.makeText(a, household ? "You joined " + name : "Shared with you by " + who, Toast.LENGTH_LONG).show();
                                a.reloadCurrent();
                                a.accountChanged();
                                if (page != null) render(a);
                            }
                        });
                    }
                }).show();
    }

    // ---------------------------------------------------------------- sharing things

    /** "🏠" / "👥" for shared rows (with a space after), "" for private ones. */
    static String badge(Context c, String table, long id) {
        String s = Spaces.spaceOfRow(c, table, id);
        if (s.isEmpty()) return "";
        JSONObject sp = Spaces.get(c, s);
        return (sp == null || "household".equals(sp.optString("kind")) ? "🏠" : "👥") + " ";
    }

    /** The "Share…" choices for one row of a module; `after` runs once something changed. */
    static void shareMenu(final MainActivity a, final String module, final String table, final long rowId, final Runnable after) {
        if (!Account.signedIn(a)) {
            Toast.makeText(a, "Sign in first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String cur = Util.queryString(Db.get(a).getReadableDatabase(), "SELECT space FROM " + table + " WHERE _id = ?", String.valueOf(rowId));
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        for (final JSONObject sp : Spaces.households(a)) {
            if (!Spaces.shares(sp, module)) continue;
            final String id = sp.optString("id");
            if (id.equals(cur)) {
                labels.add("🏠 Shared with " + sp.optString("name") + " — make private");
                actions.add(new Runnable() {
                    public void run() { setSpace(a, table, rowId, "", after); }
                });
            } else {
                labels.add("🏠 Share with " + sp.optString("name"));
                actions.add(new Runnable() {
                    public void run() { setSpace(a, table, rowId, id, after); }
                });
            }
        }
        JSONObject curSp = cur == null || cur.isEmpty() ? null : Spaces.get(a, cur);
        if (curSp != null && "share".equals(curSp.optString("kind"))) {
            labels.add("👥 Shared with " + label(a, curSp) + " — stop sharing");
            actions.add(new Runnable() {
                public void run() { setSpace(a, table, rowId, "", after); }
            });
        }
        labels.add("👤 Share with a person…");
        actions.add(new Runnable() {
            public void run() { sharePerson(a, table, rowId, after); }
        });
        if (Spaces.households(a).isEmpty()) {
            labels.add("🏠 Create a household…");
            actions.add(new Runnable() {
                public void run() { show(a, null); }
            });
        }
        new AlertDialog.Builder(a).setTitle("Share").setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { actions.get(which).run(); }
        }).show();
    }

    /** A small "Share…" link for edit forms (existing rows only). */
    static View shareRow(final MainActivity a, final String module, final String table, final long rowId, final Runnable after) {
        String b = badge(a, table, rowId);
        TextView t = AccountUi.text(a, b.isEmpty() ? "👥  Share…" : b + " Shared · change…", 15, R.color.primary_text, true);
        t.setPadding(0, Util.dp(a, 10), 0, Util.dp(a, 6));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shareMenu(a, module, table, rowId, after); }
        });
        return t;
    }

    /**
     * Puts a row into a space ('' = private). A built-in starting row (same uid on every phone, e.g. "Groceries") is
     * copied to a new row first, so sharing your list doesn't merge with everyone's own one.
     */
    static void setSpace(final MainActivity a, String table, long rowId, final String space, final Runnable after) {
        SQLiteDatabase db = Db.get(a).getWritableDatabase();
        String uid = Util.queryString(db, "SELECT uid FROM " + table + " WHERE _id = ?", String.valueOf(rowId));
        long id = rowId;
        if (!space.isEmpty() && uid != null && uid.startsWith("seed-")) {
            SyncSchema.Table t = SyncSchema.table(table);
            StringBuilder cols = new StringBuilder();
            for (String col : t.cols) cols.append(cols.length() == 0 ? "" : ", ").append(col);
            // executeInsert: the new row's id from the same connection (a separate query may use another one)
            id = db.compileStatement("INSERT INTO " + table + " (" + cols + ") SELECT " + cols + " FROM " + table + " WHERE _id = " + rowId).executeInsert();
            String[][] kids = "grocery_lists".equals(table) ? new String[][]{{"grocery", "list"}, {"grocery_history", "list"}}
                    : "categories".equals(table) ? new String[][]{{"tasks", "category"}, {"categories", "parent"}}
                    : "checklists".equals(table) ? new String[][]{{"checklist_items", "list"}} : new String[0][];
            for (String[] k : kids) db.execSQL("UPDATE " + k[0] + " SET " + k[1] + " = ? WHERE " + k[1] + " = ?", new Object[]{id, rowId});
            if ("grocery_lists".equals(table) && a.prefs.getLong("selList", 1) == rowId) a.prefs.edit().putLong("selList", id).apply();
        }
        ContentValues v = new ContentValues();
        v.put("space", space);
        db.update(table, v, "_id = ?", new String[]{String.valueOf(id)});
        JSONObject sp = space.isEmpty() ? null : Spaces.get(a, space);
        Toast.makeText(a, sp == null ? "Private again" : "Shared with " + label(a, sp), Toast.LENGTH_SHORT).show();
        a.syncSpacesSoon();
        if (after != null) after.run();
    }

    private static void sharePerson(final MainActivity a, final String table, final long rowId, final Runnable after) {
        final List<String> emails = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        String me = Account.user(a).optString("email");
        for (JSONObject sp : Spaces.list(a)) {
            JSONArray m = sp.optJSONArray("members");
            for (int i = 0; m != null && i < m.length(); i++) {
                JSONObject x = m.optJSONObject(i);
                String e = x.optString("email");
                if (e.equals(me) || emails.contains(e)) continue;
                emails.add(e);
                labels.add(x.optString("name").isEmpty() ? e : x.optString("name") + " · " + e);
            }
        }
        labels.add("✉️ Someone else (by email)…");
        new AlertDialog.Builder(a).setTitle("Share with").setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which < emails.size()) shareWith(a, emails.get(which), table, rowId, after);
                else Util.prompt(a, "Their email address", "", "name@gmail.com", "Share", new Util.TextEntered() {
                    public void onText(String e) {
                        if (e.contains("@")) shareWith(a, e.trim().toLowerCase(java.util.Locale.ROOT), table, rowId, after);
                    }
                });
            }
        }).show();
    }

    private static void shareWith(final MainActivity a, final String email, final String table, final long rowId, final Runnable after) {
        final String[] out = new String[2]; // space id, link (when they still have to join)
        background(a, new Job() {
            public void run() throws Exception {
                JSONObject found = null;
                for (JSONObject sp : Spaces.list(a)) {
                    if (!"share".equals(sp.optString("kind"))) continue;
                    if (sp.toString().contains("\"" + email + "\"")) {
                        found = sp;
                        break;
                    }
                }
                if (found == null) {
                    String id = Spaces.create(a, "Shared", new String[0], "share");
                    out[0] = id;
                    out[1] = Spaces.invite(a, id, email);
                } else {
                    out[0] = found.optString("id");
                    JSONArray m = found.optJSONArray("members");
                    boolean joined = false;
                    for (int i = 0; m != null && i < m.length(); i++) joined |= email.equals(m.optJSONObject(i).optString("email"));
                    if (!joined) out[1] = Spaces.invite(a, out[0], email);
                }
            }
        }, new Runnable() {
            public void run() {
                setSpace(a, table, rowId, out[0], after);
                if (out[1] != null) send(a, Spaces.get(a, out[0]), out[1]);
            }
        });
    }

    static void openWeb(MainActivity a, String path) {
        a.startExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(Account.SERVER + path)));
    }
}
