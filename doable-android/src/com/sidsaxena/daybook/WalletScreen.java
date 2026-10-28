package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Cards and documents: loyalty cards with a barcode to show at the till, ID, passport, driving licence,
 * insurance cards… with front/back photos or scans. Expiry dates show up in Important dates with a
 * reminder a month before. Protected by fingerprint unless turned off.
 */
class WalletScreen extends Page {

    private static final int MENU_LOCK = 200;
    private static final String[] TYPE_EMOJI = {"🏷", "💳", "🪪", "🛂", "🚗", "🏥", "📄", "🎫", "🎟", "🚙", "🧾", "📁", "💳"};

    private boolean unlocked, prompted;

    WalletScreen(MainActivity a) {
        super(a);
        setFab(new Runnable() {
            public void run() { edit(new Store.WalletItem(), null); }
        });
    }

    @Override
    String title() {
        return a.getString(R.string.module_wallet);
    }

    private boolean lockOn() {
        return a.prefs.getBoolean("walletLock", true);
    }

    @Override
    void onShow() {
        a.setSecure(true);
        render();
        if (!unlocked && lockOn() && !prompted) {
            prompted = true;  // once per visit; "Unlock" asks again
            unlock(null);
        }
    }

    @Override
    void onHide() {
        a.setSecure(false);
        prompted = false;
    }

    @Override
    void onStop() {
        if (a.isAway()) return;  // camera / file picker: keep the wallet open
        unlocked = false;
        prompted = false;
    }

    private long pendingOpen;

    @Override
    void open(long id) {
        if (!unlocked && lockOn()) {
            pendingOpen = id;
            return;  // shown after unlocking
        }
        for (Store.WalletItem i : st.wallet()) if (i.id == id) details(i);
    }

    private void unlock(final Runnable then) {
        a.confirmUser("Open Wallet", false, new MainActivity.AuthResult() {
            public void done(boolean ok, int error) {
                if (ok || error == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL
                        || error == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT) {
                    unlocked = true;
                    render();
                    if (then != null) then.run();
                    if (pendingOpen != 0) {
                        long id = pendingOpen;
                        pendingOpen = 0;
                        open(id);
                    }
                }
            }
        });
    }

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_LOCK, 0, "Require fingerprint").setCheckable(true);
    }

    @Override
    void onPrepareMenu(Menu menu) {
        menu.findItem(MENU_LOCK).setChecked(lockOn());
    }

    @Override
    boolean onMenu(int id) {
        if (id != MENU_LOCK) return false;
        final boolean on = !lockOn();
        if (on) {
            a.prefs.edit().putBoolean("walletLock", true).apply();
        } else {
            a.confirmUser("Turn off Wallet fingerprint", false, new MainActivity.AuthResult() {
                public void done(boolean ok, int error) {
                    if (ok) a.prefs.edit().putBoolean("walletLock", false).apply();
                }
            });
        }
        return true;
    }

    static String emoji(String type) {
        int i = java.util.Arrays.asList(Store.WALLET_TYPES).indexOf(type);
        return i >= 0 && i < TYPE_EMOJI.length ? TYPE_EMOJI[i] : "💳";
    }

    @Override
    void render() {
        body.removeAllViews();
        if (!unlocked && lockOn()) {
            fab.setVisibility(View.GONE);
            empty("🔒\nYour cards and documents are protected.");
            LinearLayout c = card();
            link(c, "Unlock", new Runnable() {
                public void run() { unlock(null); }
            }).setGravity(Gravity.CENTER);
            return;
        }
        fab.setVisibility(View.VISIBLE);
        List<Store.WalletItem> items = st.wallet();
        if (items.isEmpty()) {
            empty("Keep loyalty cards, ID, passport, driving licence and insurance cards here.\n"
                    + "Tap + and scan the front and back. Expiry dates get a reminder.");
            return;
        }
        long today = Util.startOfDay(System.currentTimeMillis());
        String type = null;
        LinearLayout card = null;
        for (final Store.WalletItem i : items) {
            if (!i.type.equals(type)) {
                type = i.type;
                header(type);
                card = card();
            }
            String sub = mask(i);
            if (i.expiry != 0) {
                int days = Util.daysFromToday(i.expiry);
                String exp = days < 0 ? "Expired " + date(i.type, i.expiry) : days <= 60 ? "Expires in " + days + " days" : "Valid until " + date(i.type, i.expiry);
                sub = sub.isEmpty() ? exp : sub + " · " + exp;
            }
            int pics = db.attachmentCount("wallet", i.id);
            if (pics > 0) sub += " · 📷" + (pics > 1 ? " " + pics : "");
            LinearLayout r = row(card, emoji(i.type), SpacesUi.badge(a, "wallet", i.id) + i.title, sub, null, new Runnable() {
                public void run() { details(i); }
            }, new Runnable() {
                public void run() { edit(i, null); }
            });
            if (i.expiry != 0 && i.expiry < today + 60L * 86400000L) {
                TextView s = (TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(1);
                s.setTextColor(Util.color(a, R.color.overdue));
            }
        }
    }

    private static String date(long d) {
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(d));
    }

    static final String CARD = "Credit / debit card";

    /** Cards only show month and year ("03/28"), like printed on them. */
    private static String date(String type, long d) {
        return CARD.equals(type) ? new SimpleDateFormat("MM/yy", Locale.getDefault()).format(new Date(d)) : date(d);
    }

    /** Month + year picker for cards; the expiry is the last day of that month. */
    private void pickMonth(long current, final Runnable cleared, final DayPicked picked) {
        Calendar c = Calendar.getInstance();
        if (current != 0) c.setTimeInMillis(current);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER);
        final android.widget.NumberPicker month = new android.widget.NumberPicker(a), year = new android.widget.NumberPicker(a);
        month.setMinValue(1);
        month.setMaxValue(12);
        month.setDisplayedValues(new String[]{"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"});
        month.setValue(c.get(Calendar.MONTH) + 1);
        int now = Calendar.getInstance().get(Calendar.YEAR);
        year.setMinValue(now - 5);
        year.setMaxValue(now + 15);
        year.setValue(Math.max(now - 5, Math.min(now + 15, c.get(Calendar.YEAR))));
        box.addView(month);
        TextView slash = new TextView(a);
        slash.setText("  /  ");
        box.addView(slash);
        box.addView(year);
        new android.app.AlertDialog.Builder(a).setTitle("Valid until (month / year)").setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Calendar e = Calendar.getInstance();
                        e.clear();
                        e.set(year.getValue(), month.getValue() - 1, 1);
                        picked.on(Store.dayOf(year.getValue(), month.getValue(), e.getActualMaximum(Calendar.DAY_OF_MONTH)));
                    }
                })
                .setNeutralButton("No expiry", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { cleared.run(); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    interface DayPicked {
        void on(long day);
    }

    /** Card number with all but the last 4 characters hidden in the list. */
    private static String mask(Store.WalletItem i) {
        String n = i.number.replace(" ", "");
        if (n.length() <= 4) return n;
        return "•••• " + n.substring(n.length() - 4);
    }

    // ---------------------------------------------------------------- details

    private void details(final Store.WalletItem i) {
        LinearLayout box = Util.dialogBox(a);
        final List<Db.Attachment> pics = db.attachments("wallet", i.id);
        if (!pics.isEmpty()) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
            LinearLayout strip = new LinearLayout(a);
            for (final Db.Attachment at : pics) {
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Bitmap b = Attachments.thumb(a, at.file, dp(320));
                if (b != null) iv.setImageBitmap(b);
                else iv.setImageResource(R.drawable.ic_note);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(126));
                lp.rightMargin = dp(8);
                iv.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { AttachmentStrip.open(a, at.file); }
                });
                strip.addView(iv, lp);
            }
            hs.addView(strip);
            box.addView(hs);
        }
        field(box, "Number", i.number);
        field(box, "Name on card", i.holder);
        if (i.expiry != 0) field(box, "Valid until", date(i.type, i.expiry));
        field(box, "Notes", i.notes);

        final String code = i.barcode.isEmpty() ? "" : i.barcode;
        if (!code.isEmpty()) {
            Bitmap bc = barcode(code, dp(300), dp(90));
            if (bc != null) {
                box.addView(Util.label(a, "Barcode · tap to enlarge"));
                ImageView iv = new ImageView(a);
                iv.setImageBitmap(bc);
                iv.setBackgroundColor(0xFFFFFFFF);
                iv.setPadding(dp(8), dp(8), dp(8), dp(8));
                iv.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { showBarcode(i.title, code); }
                });
                box.addView(iv);
            }
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle(emoji(i.type) + " " + i.title)
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Edit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { edit(i, null); }
                })
                .show();
    }

    private void field(LinearLayout box, String label, final String value) {
        if (value == null || value.isEmpty()) return;
        box.addView(Util.label(a, label));
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(17);
        t.setTextIsSelectable(true);
        t.setTextColor(Util.color(a, R.color.text));
        t.setPadding(dp(4), dp(2), 0, dp(2));
        t.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Doable", value));
                Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        box.addView(t);
    }

    /** EAN-13 for 12/13-digit codes with a valid check digit, otherwise Code 128. Null if not encodable. */
    static Bitmap barcode(String code, int width, int height) {
        boolean[] m = Barcode.ean13(code);
        if (m == null) m = Barcode.code128(code);
        if (m == null) return null;
        int quiet = 10;
        int modules = m.length + quiet * 2;
        int scale = Math.max(1, width / modules);
        int w = modules * scale;
        int[] px = new int[w * height];
        for (int x = 0; x < w; x++) {
            int mi = x / scale - quiet;
            int c = mi >= 0 && mi < m.length && m[mi] ? 0xFF000000 : 0xFFFFFFFF;
            for (int y = 0; y < height; y++) px[y * w + x] = c;
        }
        return Bitmap.createBitmap(px, w, height, Bitmap.Config.ARGB_8888);
    }

    /** Full-screen barcode with the screen at full brightness, for scanners at the till. */
    private void showBarcode(String title, String code) {
        int width = a.getResources().getDisplayMetrics().widthPixels;
        Bitmap bc = barcode(code, width - dp(32), dp(160));
        if (bc == null) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_Light_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFFFFFFF);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(20);
        t.setTextColor(0xFF000000);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(bc);
        iv.setPadding(0, dp(24), 0, dp(12));
        root.addView(iv);
        TextView n = new TextView(a);
        n.setText(code);
        n.setTextSize(18);
        n.setTextColor(0xFF000000);
        n.setGravity(Gravity.CENTER);
        root.addView(n);
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        d.setContentView(root);
        WindowManager.LayoutParams lp = d.getWindow().getAttributes();
        lp.screenBrightness = 1f;
        d.getWindow().setAttributes(lp);
        d.show();
    }

    // ---------------------------------------------------------------- editor

    /** Edits a card; {@code files} are photos to attach straight away (shared from another app). */
    void edit(final Store.WalletItem i, List<String> files) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Type"));
        final Spinner type = new Spinner(a);
        type.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, Store.WALLET_TYPES));
        type.setSelection(Math.max(0, java.util.Arrays.asList(Store.WALLET_TYPES).indexOf(i.type)));
        box.addView(type);

        box.addView(Util.label(a, "Name"));
        final EditText title = Util.field(a, i.title);
        title.setHint("e.g. Payback, Passport Sid");
        box.addView(title);

        box.addView(Util.label(a, "Front & back (photo or scan)"));
        final AttachmentStrip pics = new AttachmentStrip(a, "Front / back");
        pics.load("wallet", i.id);
        if (files != null) for (String f : files) pics.addPending(f);
        box.addView(pics.view);

        box.addView(Util.label(a, "Card / document number"));
        final EditText number = Util.field(a, i.number);
        number.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(number);

        box.addView(Util.label(a, "Name on card"));
        final EditText holder = Util.field(a, i.holder);
        holder.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(holder);

        box.addView(Util.label(a, "Valid until"));
        final long[] expiry = {i.expiry};
        final Button exp = new Button(a, null, android.R.attr.borderlessButtonStyle);
        exp.setText(i.expiry == 0 ? "No expiry date" : date(i.type, i.expiry));
        exp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (CARD.equals(Store.WALLET_TYPES[type.getSelectedItemPosition()])) {
                    pickMonth(expiry[0], new Runnable() {
                        public void run() {
                            expiry[0] = 0;
                            exp.setText("No expiry date");
                        }
                    }, new DayPicked() {
                        public void on(long day) {
                            expiry[0] = day;
                            exp.setText(date(CARD, day));
                        }
                    });
                    return;
                }
                Calendar c = Calendar.getInstance();
                if (expiry[0] != 0) c.setTimeInMillis(expiry[0]);
                DatePickerDialog dp = new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(DatePicker p, int y, int m, int d) {
                        expiry[0] = Store.dayOf(y, m + 1, d);
                        exp.setText(date(expiry[0]));
                    }
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
                dp.setButton(DialogInterface.BUTTON_NEUTRAL, "No expiry", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        expiry[0] = 0;
                        exp.setText("No expiry date");
                    }
                });
                dp.show();
            }
        });
        box.addView(exp);

        box.addView(Util.label(a, "Barcode (number printed under it)"));
        final EditText code = Util.field(a, i.barcode);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setHint("Leave empty if none");
        box.addView(code);

        if (i.id != 0 && Account.signedIn(a)) box.addView(SpacesUi.shareRow(a, "wallet", "wallet", i.id, new Runnable() {
            public void run() { render(); }
        }));
        box.addView(Util.label(a, "Notes"));
        final EditText notes = Util.field(a, i.notes);
        notes.setHint("Issuing authority, customer service number…");
        box.addView(notes);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(i.id == 0 ? "New card or document" : "Edit")
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        i.type = Store.WALLET_TYPES[type.getSelectedItemPosition()];
                        String t = title.getText().toString().trim();
                        i.title = t.isEmpty() ? i.type : t;
                        i.number = number.getText().toString().trim();
                        i.holder = holder.getText().toString().trim();
                        i.expiry = expiry[0];
                        i.barcode = code.getText().toString().trim();
                        i.notes = notes.getText().toString().trim();
                        if (!i.barcode.isEmpty() && Barcode.ean13(i.barcode) == null && Barcode.code128(i.barcode) == null) {
                            Toast.makeText(a, "This barcode can't be drawn (only letters, digits and symbols)", Toast.LENGTH_LONG).show();
                        }
                        st.saveWallet(i);
                        pics.commit("wallet", i.id);
                        if (i.expiry != 0) a.ensureNotifications();
                        Reminders.refresh(a);
                        render();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { pics.discard(); }
                });
        if (i.id != 0) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    Util.confirm(a, "Delete \"" + i.title + "\"?", "Its photos and scans are deleted too.", "Delete", new Runnable() {
                        public void run() {
                            Attachments.delete(a, st.deleteWallet(i.id));
                            Reminders.refresh(a);
                            render();
                        }
                    });
                }
            });
        }
        AlertDialog dlg = b.create();
        dlg.setCancelable(false);  // don't lose new scans by tapping outside
        dlg.show();
    }

    /** From share-in: a new document with the shared files, after unlocking. */
    void addShared(final List<String> files) {
        Runnable go = new Runnable() {
            public void run() {
                Store.WalletItem i = new Store.WalletItem();
                i.type = "Document";
                edit(i, files);
            }
        };
        if (!unlocked && lockOn()) unlock(go);
        else go.run();
    }
}
