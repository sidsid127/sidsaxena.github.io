package com.sidsaxena.daybook;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

/**
 * Screens for the Doable account: the sign-in screen shown until you're signed in, the profile (name, plan, ads or Pro,
 * help, sign out, delete the account) and the account row, ad strip and announcement in the main window.
 */
final class AccountUi {
    private static View welcome;
    private static TextView welcomeMsg;

    private AccountUi() {}

    static boolean online(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    // ---------------------------------------------------------------- sign-in screen

    /** At start: covers the app with the sign-in screen unless this device is signed in. */
    static void gate(final MainActivity a) {
        if (Account.signedIn(a)) {
            refreshSoon(a, 0);
            return;
        }
        showWelcome(a);
        if (GoogleDrive.signedIn(a)) finishSignIn(a, null); // Google already knows this device (sync on): no tap needed
    }

    static boolean waiting() {
        return welcome != null && welcome.getParent() != null;
    }

    private static void showWelcome(final MainActivity a) {
        if (waiting() && welcome.getContext() == a) return; // (a screen of an earlier, closed window is dropped)
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Util.color(a, R.color.bg));
        scroll.setElevation(Util.dp(a, 30));
        scroll.setClickable(true);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = Util.dp(a, 28);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.ic_launcher);
        box.addView(logo, new LinearLayout.LayoutParams(Util.dp(a, 96), Util.dp(a, 96)));
        TextView title = text(a, "Doable", 32, R.color.text, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Util.dp(a, 12), 0, Util.dp(a, 4));
        box.addView(title);
        TextView tag = text(a, "Your to-dos, notes, groceries, credentials and more — private, and in sync on all your devices.", 17, R.color.muted, false);
        tag.setGravity(Gravity.CENTER);
        box.addView(tag);

        LinearLayout points = card(a);
        points.setPadding(Util.dp(a, 18), Util.dp(a, 12), Util.dp(a, 18), Util.dp(a, 12));
        for (String p : new String[]{"🔒  Your notes, tasks and credentials are encrypted — only you can read them",
                "🏠  Share lists with your family or household", "📱  Works on Android, iPhone and Mac, also offline"}) {
            TextView t = text(a, p, 15, R.color.text, false);
            t.setPadding(0, Util.dp(a, 6), 0, Util.dp(a, 6));
            points.addView(t);
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, Util.dp(a, 22), 0, Util.dp(a, 24));
        box.addView(points, plp);

        Button google = new Button(a);
        google.setText("Continue with Google");
        google.setAllCaps(false);
        google.setTextSize(16);
        google.setTypeface(google.getTypeface(), Typeface.BOLD);
        google.setTextColor(0xFF1F1F1F);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(0xFFFFFFFF);
        gbg.setStroke(Util.dp(a, 1), 0xFF747775);
        gbg.setCornerRadius(Util.dp(a, 26));
        google.setBackground(gbg);
        google.setStateListAnimator(null);
        box.addView(google, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(a, 52)));

        welcomeMsg = text(a, "", 14, R.color.muted, false);
        welcomeMsg.setGravity(Gravity.CENTER);
        welcomeMsg.setPadding(0, Util.dp(a, 12), 0, 0);
        box.addView(welcomeMsg);

        TextView legal = text(a, "By continuing you agree to the Terms and the Privacy policy.", 12, R.color.muted, false);
        legal.setGravity(Gravity.CENTER);
        legal.setPadding(0, Util.dp(a, 18), 0, 0);
        legal.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(a, Account.SERVER + "privacy.html"); }
        });
        box.addView(legal);

        google.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!online(a)) {
                    welcomeMsg.setText("You're offline. Connect to the internet to sign in.");
                    return;
                }
                welcomeMsg.setText("Opening Google…");
                GoogleDrive.signIn(a, new GoogleDrive.SignedIn() {
                    public void done(String error) { googleDone(a, error); }
                });
            }
        });
        if (!online(a)) {
            // Can't sign in now: let the app be used and ask again next time.
            Button later = new Button(a);
            later.setText("Continue offline for now");
            later.setAllCaps(false);
            later.setBackground(null);
            later.setTextColor(Util.color(a, R.color.primary_text));
            later.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { hideWelcome(); }
            });
            box.addView(later);
            welcomeMsg.setText("You're offline — Doable asks you to sign in when you're online.");
        }
        welcome = scroll;
        ((FrameLayout) a.findViewById(android.R.id.content)).addView(scroll,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (a.getActionBar() != null) a.getActionBar().hide();
    }

    private static void hideWelcome() {
        if (welcome != null && welcome.getParent() != null) {
            MainActivity a = (MainActivity) welcome.getContext();
            ((ViewGroup) welcome.getParent()).removeView(welcome);
            if (a.getActionBar() != null) a.getActionBar().show();
        }
        welcome = null;
    }

    /** Google answered (also when the app was restarted while the browser was open). */
    static void googleDone(MainActivity a, String error) {
        if (error != null) {
            if (welcomeMsg != null) welcomeMsg.setText(error.contains("access_denied") ? "" : "Google sign-in didn't work: " + error);
            return;
        }
        finishSignIn(a, welcomeMsg);
    }

    private static void finishSignIn(final MainActivity a, final TextView msg) {
        if (welcomeMsg != null) welcomeMsg.setText("Signing in…");
        final Context c = a.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                boolean isNew = false;
                try {
                    isNew = Account.signIn(c, GoogleDrive.token(c));
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String error = err;
                final boolean fresh = isNew;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        if (error != null) {
                            if (welcomeMsg != null) welcomeMsg.setText(online(a) ? "Sign-in didn't work (" + error + "). Please try again." : "");
                            return;
                        }
                        hideWelcome();
                        Toast.makeText(a, (fresh ? "Welcome to Doable, " : "Welcome back, ") + Account.displayName(a).split(" ")[0], Toast.LENGTH_SHORT).show();
                        a.accountChanged();
                        if (SyncEngine.enabled(a)) SyncUi.syncNow(a, false);
                    }
                });
            }
        }).start();
    }

    /** Fetches the latest profile and switches (at most every 15 minutes unless forced). */
    static void refreshSoon(final MainActivity a, long minAgeMs) {
        if (!Account.signedIn(a) || !online(a)) return;
        if (System.currentTimeMillis() - Account.prefs(a).getLong("checked", 0) < minAgeMs) return;
        final Context c = a.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                boolean ended = false;
                try {
                    Account.refresh(c);
                } catch (Account.SignedOutException e) {
                    ended = true;
                } catch (Exception e) {
                    return; // offline or server down: keep what we have
                }
                final boolean signedOut = ended;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        if (signedOut) {
                            Account.forget(a);
                            showWelcome(a);
                        }
                        a.accountChanged();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- main window bits

    /** The account row at the top of the side menu. */
    static View drawerRow(final MainActivity a, final Runnable openProfile) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Util.dp(a, 16), Util.dp(a, 10), Util.dp(a, 16), Util.dp(a, 10));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.addView(avatar(a, 40), new LinearLayout.LayoutParams(Util.dp(a, 40), Util.dp(a, 40)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Util.dp(a, 14), 0, 0, 0);
        TextView name = text(a, Account.displayName(a), 16, R.color.text, true);
        name.setSingleLine(true);
        TextView mail = text(a, Account.user(a).optString("email"), 12, R.color.muted, false);
        mail.setSingleLine(true);
        texts.addView(name);
        texts.addView(mail);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openProfile.run(); }
        });
        return row;
    }

    /** Ad strip at the bottom and the announcement at the top of the main window. */
    static void applyBars(final MainActivity a) {
        TextView ad = (TextView) a.findViewById(R.id.ad_slot);
        final TextView note = (TextView) a.findViewById(R.id.announce);
        if (ad == null || note == null) return;
        JSONObject cfg = Account.config(a);
        ad.setVisibility(Account.showAds(a) ? View.VISIBLE : View.GONE);
        String adText = cfg.optString("ad_text");
        ad.setText("AD  ·  " + (adText.isEmpty() ? "Doable is free thanks to ads like this one." : adText)
                + (cfg.optBoolean("pro_enabled") ? "   Remove ads ›" : ""));
        ad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showProfile(a); }
        });
        final String text = cfg.optString("announcement");
        boolean seen = text.equals(a.prefs.getString("announceSeen", ""));
        note.setVisibility(text.isEmpty() || seen ? View.GONE : View.VISIBLE);
        note.setText(text + "   ✕");
        note.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.prefs.edit().putString("announceSeen", text).apply();
                note.setVisibility(View.GONE);
            }
        });
    }

    // ---------------------------------------------------------------- profile

    static void showProfile(final MainActivity a) {
        if (!Account.signedIn(a)) {
            showWelcome(a);
            return;
        }
        final Dialog d = new Dialog(a, R.style.AppTheme);
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Util.color(a, R.color.bg));

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Util.color(a, R.color.primary));
        bar.setPadding(Util.dp(a, 4), 0, Util.dp(a, 16), 0);
        TextView close = text(a, "←", 24, R.color.text, false);
        close.setTextColor(0xFFFFFFFF);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        bar.addView(close, new LinearLayout.LayoutParams(Util.dp(a, 52), Util.dp(a, 56)));
        TextView title = text(a, "Profile", 20, R.color.text, true);
        title.setTextColor(0xFFFFFFFF);
        bar.addView(title);
        outer.addView(bar);

        ScrollView scroll = new ScrollView(a);
        final LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Util.dp(a, 12), Util.dp(a, 8), Util.dp(a, 12), Util.dp(a, 32));
        scroll.addView(page);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        d.setContentView(outer);
        fillProfile(a, d, page);
        d.show();
    }

    private static void fillProfile(final MainActivity a, final Dialog d, final LinearLayout page) {
        page.removeAllViews();
        JSONObject u = Account.user(a);
        JSONObject cfg = Account.config(a);

        LinearLayout head = card(a);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(Util.dp(a, 16), Util.dp(a, 22), Util.dp(a, 16), Util.dp(a, 18));
        head.addView(avatar(a, 76), new LinearLayout.LayoutParams(Util.dp(a, 76), Util.dp(a, 76)));
        TextView name = text(a, Account.displayName(a), 22, R.color.text, true);
        name.setPadding(0, Util.dp(a, 10), 0, Util.dp(a, 2));
        head.addView(name);
        head.addView(text(a, u.optString("email"), 14, R.color.muted, false));
        TextView badge = text(a, Account.isPro(a) ? "⭐ Pro" : "Free plan", 13, R.color.primary_text, true);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(Account.isPro(a) ? 0xFFF2A33A : Util.color(a, R.color.selected));
        bbg.setCornerRadius(Util.dp(a, 12));
        badge.setBackground(bbg);
        badge.setPadding(Util.dp(a, 10), Util.dp(a, 3), Util.dp(a, 10), Util.dp(a, 3));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, Util.dp(a, 10), 0, 0);
        head.addView(badge, blp);
        page.addView(head);

        section(a, page, "Account");
        LinearLayout acc = card(a);
        row(a, acc, "✏️", "Name", u.optString("name", "Not set"), new Runnable() {
            public void run() {
                Util.prompt(a, "Your name", Account.user(a).optString("name"), "Name", "Save", new Util.TextEntered() {
                    public void onText(final String n) {
                        background(a, new Job() {
                            public void run() throws Exception { Account.rename(a, n); }
                        }, new Runnable() {
                            public void run() { fillProfile(a, d, page); a.accountChanged(); }
                        });
                    }
                });
            }
        });
        row(a, acc, "🔄", "Sync with Google", SyncUi.status(a), new Runnable() {
            public void run() { d.dismiss(); SyncUi.show(a); }
        });
        page.addView(acc);

        if (cfg.optBoolean("ads_enabled") || cfg.optBoolean("pro_enabled")) {
            section(a, page, "Ads or Pro");
            LinearLayout ads = card(a);
            if (Account.isPro(a)) {
                row(a, ads, "⭐", "You have Doable Pro", "No ads, forever. Thank you for supporting Doable!", null);
            } else {
                row(a, ads, "📰", "Free, with a small ad", "ads".equals(u.optString("ads_choice")) ? "✓ Your choice" : "A small banner at the bottom of the screen", new Runnable() {
                    public void run() {
                        background(a, new Job() {
                            public void run() throws Exception { Account.choose(a, "ads"); }
                        }, new Runnable() {
                            public void run() { fillProfile(a, d, page); }
                        });
                    }
                });
                if (cfg.optBoolean("pro_enabled")) {
                    row(a, ads, "⭐", "Doable Pro — " + cfg.optString("pro_price"), "No ads, forever, on all your devices", new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(a).setTitle("⭐ Doable Pro")
                                    .setMessage("Pay once, use Doable without ads forever — on all your devices.\n\nBuying opens soon. Tap \"Notify me\" and we'll keep Doable ad-free for you as soon as it does.")
                                    .setNegativeButton("Not now", null)
                                    .setPositiveButton("Notify me", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface x, int w) {
                                            background(a, new Job() {
                                                public void run() throws Exception { Account.choose(a, "pro"); }
                                            }, new Runnable() {
                                                public void run() { Toast.makeText(a, "Noted — thank you!", Toast.LENGTH_SHORT).show(); fillProfile(a, d, page); }
                                            });
                                        }
                                    }).show();
                        }
                    });
                }
            }
            page.addView(ads);
        }

        section(a, page, "Help");
        LinearLayout help = card(a);
        String version = "Version " + BuildInfo.versionName(a);
        row(a, help, "⬆️", "Check for updates", Updater.fromPlayStore(a) ? version + " · updated by Google Play" : version, new Runnable() {
            public void run() { Updater.check(a, true); }
        });
        row(a, help, "✉️", "Contact", Account.CONTACT, new Runnable() {
            public void run() {
                a.startExternal(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Account.CONTACT + "?subject=Doable")));
            }
        });
        row(a, help, "🔏", "Privacy policy", null, new Runnable() {
            public void run() { open(a, Account.SERVER + "privacy.html"); }
        });
        row(a, help, "📄", "Terms", null, new Runnable() {
            public void run() { open(a, Account.SERVER + "terms.html"); }
        });
        page.addView(help);

        if (Account.isAdmin(a)) {
            section(a, page, "Owner");
            LinearLayout own = card(a);
            row(a, own, "📊", "Admin page", "Opens in the browser: users, sign-ups, ads and Pro switches", new Runnable() {
                public void run() { open(a, Account.SERVER + "?module=admin"); }
            });
            page.addView(own);
        }

        section(a, page, "");
        LinearLayout danger = card(a);
        row(a, danger, "🚪", "Sign out", "On this device", new Runnable() {
            public void run() { signOut(a); }
        });
        TextView del = row(a, danger, "🗑", "Delete account", "Your account and all your Doable data", new Runnable() {
            public void run() { deleteAccount(a); }
        });
        del.setTextColor(Util.color(a, R.color.overdue));
        page.addView(danger);
    }

    // ---------------------------------------------------------------- sign out / delete

    private static void signOut(final MainActivity a) {
        boolean synced = SyncEngine.enabled(a);
        Util.confirm(a, "Sign out of Doable?", synced
                        ? "Your Doable data is removed from this phone. It stays in your Google Drive sync and comes back when you sign in again."
                        : "⚠ Sync is off, so your data exists only on this phone and will be removed. Make a backup first (Backup & restore) if you want to keep it.",
                "Sign out", new Runnable() {
                    public void run() {
                        final Context c = a.getApplicationContext();
                        Toast.makeText(a, "Signing out…", Toast.LENGTH_SHORT).show();
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    if (SyncEngine.enabled(c)) SyncEngine.run(c);
                                } catch (Exception ignored) {
                                }
                                try {
                                    Account.signOut(c);
                                } catch (Exception ignored) {
                                }
                                wipe(a, "Signed out. Open Doable again to sign in.");
                            }
                        }).start();
                    }
                });
    }

    private static void deleteAccount(final MainActivity a) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(text(a, "This permanently deletes:\n• your Doable account and profile\n• your synced data in your Google Drive (Doable's hidden folder)\n"
                + "• all Doable data on this phone\n\nDoable on your other devices keeps what is stored there until you sign out there. This can't be undone.", 15, R.color.text, false));
        final EditText typed = Util.field(a, "");
        typed.setHint("Type DELETE");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Util.dp(a, 14), 0, 0);
        box.addView(typed, lp);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Delete your Doable account?").setView(Util.wrap(a, box))
                .setNegativeButton("Cancel", null).setPositiveButton("Delete forever", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Util.color(a, R.color.overdue));
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!typed.getText().toString().trim().equalsIgnoreCase("DELETE")) {
                    Toast.makeText(a, "Type DELETE to confirm", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!online(a)) {
                    Toast.makeText(a, "Connect to the internet first", Toast.LENGTH_SHORT).show();
                    return;
                }
                dlg.dismiss();
                Toast.makeText(a, "Deleting…", Toast.LENGTH_LONG).show();
                final Context c = a.getApplicationContext();
                new Thread(new Runnable() {
                    public void run() {
                        boolean driveOk = true;
                        if (GoogleDrive.signedIn(c)) {
                            try {
                                GoogleDrive g = new GoogleDrive(c);
                                g.list();
                                for (String name : new java.util.ArrayList<String>(g.ids.keySet())) g.delete(name);
                            } catch (Exception e) {
                                driveOk = false;
                            }
                        }
                        try {
                            Account.delete(c);
                        } catch (Account.SignedOutException ignored) {
                        } catch (final Exception e) {
                            a.runOnUiThread(new Runnable() {
                                public void run() { Toast.makeText(a, "Couldn't reach Doable: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                            });
                            return;
                        }
                        wipe(a, driveOk ? "Your Doable account and data are deleted. Thank you for using Doable."
                                : "Your Doable account is deleted. Google Drive couldn't be reached, so remove the synced copy yourself: "
                                + "drive.google.com → Settings → Manage apps → Doable → Delete hidden app data.");
                    }
                }).start();
            }
        });
    }

    /** Tells the person, then erases all of Doable's data on this phone (Android closes the app). */
    private static void wipe(final MainActivity a, final String message) {
        a.runOnUiThread(new Runnable() {
            public void run() {
                new AlertDialog.Builder(a).setMessage(message).setCancelable(false)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                ((ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE)).clearApplicationUserData();
                            }
                        }).show();
            }
        });
    }

    // ---------------------------------------------------------------- small helpers

    interface Job {
        void run() throws Exception;
    }

    private static void background(final MainActivity a, final Job job, final Runnable then) {
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    job.run();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String error = err;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (error != null) Toast.makeText(a, "Couldn't save — are you online?", Toast.LENGTH_SHORT).show();
                        else then.run();
                    }
                });
            }
        }).start();
    }

    static void open(MainActivity a, String url) {
        a.startExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Util.color(c, color));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Util.dp(c, 4), 0, Util.dp(c, 4));
        l.setLayoutParams(lp);
        return l;
    }

    private static void section(Context c, LinearLayout page, String title) {
        TextView t = text(c, title.toUpperCase(), 12, R.color.muted, true);
        t.setPadding(Util.dp(c, 6), Util.dp(c, 16), 0, Util.dp(c, 6));
        page.addView(t);
    }

    /** A tappable row with an emoji, a title and a muted line under it; returns the title view. */
    private static TextView row(Context c, LinearLayout card, String emoji, String title, String sub, final Runnable onClick) {
        if (card.getChildCount() > 0) {
            View line = new View(c);
            line.setBackgroundColor(Util.color(c, R.color.divider));
            card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Util.dp(c, 56));
        r.setPadding(Util.dp(c, 14), Util.dp(c, 8), Util.dp(c, 14), Util.dp(c, 8));
        TextView e = text(c, emoji, 20, R.color.text, false);
        e.setGravity(Gravity.CENTER);
        r.addView(e, new LinearLayout.LayoutParams(Util.dp(c, 36), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Util.dp(c, 8), 0, 0, 0);
        TextView t = text(c, title, 16, R.color.text, false);
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) texts.addView(text(c, sub, 13, R.color.muted, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onClick != null) {
            r.setBackgroundResource(android.R.drawable.list_selector_background);
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { onClick.run(); }
            });
        }
        card.addView(r);
        return t;
    }

    /** Round profile photo (downloaded once) or initials. */
    static View avatar(final Context c, int sizeDp) {
        final FrameLayout box = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Util.color(c, R.color.selected));
        box.setBackground(bg);
        TextView initials = text(c, initials(Account.displayName(c)), Math.max(12, sizeDp * 2 / 5), R.color.primary_text, true);
        initials.setGravity(Gravity.CENTER);
        box.addView(initials, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final ImageView photo = new ImageView(c);
        box.addView(photo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final String url = Account.user(c).optString("picture");
        if (url.isEmpty()) return box;
        final File f = new File(c.getCacheDir(), "avatar_" + Integer.toHexString(url.hashCode()) + ".png");
        if (f.exists()) {
            photo.setImageBitmap(round(BitmapFactory.decodeFile(f.getPath())));
            return box;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = new URL(url).openStream();
                    final Bitmap b = BitmapFactory.decodeStream(in);
                    in.close();
                    if (b == null) return;
                    FileOutputStream out = new FileOutputStream(f);
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                    out.close();
                    box.post(new Runnable() {
                        public void run() { photo.setImageBitmap(round(b)); }
                    });
                } catch (Exception ignored) {
                    // initials stay
                }
            }
        }).start();
        return box;
    }

    static String initials(String name) {
        StringBuilder s = new StringBuilder();
        for (String w : name.split("[ @.]+")) {
            if (!w.isEmpty() && s.length() < 2) s.append(Character.toUpperCase(w.charAt(0)));
        }
        return s.length() == 0 ? "?" : s.toString();
    }

    private static Bitmap round(Bitmap b) {
        if (b == null) return null;
        int n = Math.min(b.getWidth(), b.getHeight());
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(n / 2f, n / 2f, n / 2f, p);
        return out;
    }
}
