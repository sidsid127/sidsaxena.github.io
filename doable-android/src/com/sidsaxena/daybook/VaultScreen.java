package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.PersistableBundle;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.UserNotAuthenticatedException;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Encrypted logins (website, membership numbers, …). Locks whenever you leave it. */
class VaultScreen extends Screen {

    private static final int MENU_REQUIRE_AUTH = 130;
    private static final int MENU_EXPORT = 131;
    private static final int MENU_LOCK = 132;
    private static final int MENU_IMPORT = 133;
    private static final int MENU_AUTOFILL = 134;

    private final Vault vault;
    private final View lockedPanel, openPanel;
    private final TextView lockedText;
    private final EditText search;
    private final List<Vault.Login> rows = new ArrayList<Vault.Login>();
    private final RowAdapter adapter = new RowAdapter();
    private boolean prompting;

    private interface Op {
        void run() throws Exception;
    }

    VaultScreen(MainActivity activity) {
        super(activity, R.layout.screen_vault);
        vault = new Vault(a);
        lockedPanel = root.findViewById(R.id.locked_panel);
        openPanel = root.findViewById(R.id.open_panel);
        lockedText = (TextView) root.findViewById(R.id.locked_text);
        search = (EditText) root.findViewById(R.id.search);
        ListView list = (ListView) root.findViewById(R.id.list);
        TextView empty = (TextView) root.findViewById(R.id.empty);
        empty.setText(R.string.empty_vault);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        root.findViewById(R.id.unlock).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { unlock(); }
        });
        root.findViewById(R.id.add).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { edit(new Vault.Login()); }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                show(rows.get(position));
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { reload(); }
        });
    }

    @Override
    String title() {
        return a.getString(R.string.module_vault);
    }

    @Override
    void onShow() {
        refreshPanels();
        if (!vault.isOpen() && !prompting) unlock();
    }

    @Override
    void onHide() {
        lock();
    }

    @Override
    void onStop() {
        lock();
    }

    // ---------------------------------------------------------------- lock state

    private boolean deviceSecure() {
        return ((KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE)).isDeviceSecure();
    }

    private void unlock() {
        if (!vault.exists()) {
            final boolean auth = deviceSecure();
            run(new Op() {
                public void run() throws Exception {
                    vault.create(auth);
                }
            });
            if (!auth) {
                new AlertDialog.Builder(a)
                        .setTitle("No screen lock on this phone")
                        .setMessage("Your passwords are encrypted, but anyone holding this phone can open them. "
                                + "Add a fingerprint and PIN in the phone's Settings → Security, then turn on "
                                + "\"Require fingerprint\" in this screen's ⋮ menu.")
                        .setPositiveButton("OK", null)
                        .show();
            }
            return;
        }
        run(new Op() {
            public void run() throws Exception {
                vault.open();
            }
        });
    }

    private void lock() {
        vault.close();
        rows.clear();
        search.setText("");
        refreshPanels();
    }

    private void refreshPanels() {
        boolean open = vault.isOpen();
        lockedPanel.setVisibility(open ? View.GONE : View.VISIBLE);
        openPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        lockedText.setText(vault.exists() && vault.requiresAuth()
                ? "Your passwords are encrypted on this phone.\nUnlock with your fingerprint, face or screen lock."
                : a.getString(R.string.vault_locked));
        // Keep passwords out of screenshots and the recent-apps preview.
        a.setSecure(open);
        if (open) reload();
        a.invalidateOptionsMenu();
    }

    /**
     * Runs a vault operation; if the Keystore says the user must authenticate first, asks for fingerprint /
     * screen lock and tries once more.
     */
    private void run(final Op op) {
        try {
            op.run();
            refreshPanels();
        } catch (UserNotAuthenticatedException e) {
            prompting = true;
            a.confirmUser("Unlock passwords", true, new MainActivity.AuthResult() {
                public void done(boolean ok, int error) {
                    prompting = false;
                    if (!ok) return;
                    try {
                        op.run();
                    } catch (Exception again) {
                        failed(again);
                    }
                    refreshPanels();
                }
            });
        } catch (Exception e) {
            failed(e);
        }
    }

    private void failed(Exception e) {
        if (e instanceof KeyPermanentlyInvalidatedException) {
            new AlertDialog.Builder(a)
                    .setTitle("Passwords can't be decrypted")
                    .setMessage("The phone's screen lock was removed or reset, so Android deleted the key that "
                            + "protected your saved passwords. They cannot be recovered. Start a new, empty password list?")
                    .setPositiveButton("Start over", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            vault.reset();
                            unlock();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (e instanceof UserNotAuthenticatedException) {
            Toast.makeText(a, "Please use your fingerprint or phone PIN to unlock passwords", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(a, "Could not open passwords: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- list

    private void reload() {
        rows.clear();
        if (!vault.isOpen()) return;
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        for (Vault.Login l : vault.logins()) {
            if (q.isEmpty() || (l.title + " " + l.user + " " + l.url + " " + l.type + " " + l.notes).toLowerCase(Locale.getDefault()).contains(q)) {
                rows.add(l);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void copy(String label, String value, boolean sensitive) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, value);
        if (sensitive && Build.VERSION.SDK_INT >= 24) {
            // Android 13+ hides sensitive clipboard contents from the copy preview.
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(a, label + " copied", Toast.LENGTH_SHORT).show();
    }

    /** Details with copy / show buttons, depending on the item type. */
    private void show(final Vault.Login l) {
        LinearLayout box = Util.dialogBox(a);
        if (l.type.equals("Bank card")) {
            if (!l.holder.isEmpty()) field(box, "Card holder", l.holder, false);
            if (!l.number.isEmpty()) field(box, "Card number", l.number, true);
            if (!l.expiry.isEmpty()) field(box, "Valid until", l.expiry, false);
            if (!l.cvv.isEmpty()) field(box, "CVV / security code", l.cvv, true);
            if (!l.pin.isEmpty()) field(box, "PIN", l.pin, true);
        } else if (l.type.equals("Wi-Fi")) {
            field(box, "Network password", l.password, true);
        } else if (l.type.equals("Login")) {
            if (!l.url.isEmpty()) field(box, "Website / app", l.url, false);
            if (!l.user.isEmpty()) field(box, "Username / ID", l.user, false);
            field(box, "Password", l.password, true);
        }
        if (!l.notes.isEmpty()) {
            box.addView(Util.label(a, l.type.equals("Secure note") ? "Note" : "Notes"));
            TextView notes = new TextView(a);
            notes.setText(l.notes);
            notes.setTextIsSelectable(true);
            notes.setTextColor(Util.color(a, R.color.text));
            notes.setTextSize(16);
            notes.setPadding(Util.dp(a, 4), Util.dp(a, 4), 0, 0);
            box.addView(notes);
        }
        new AlertDialog.Builder(a)
                .setTitle(icon(l) + "  " + l.title)
                .setView(wrapScroll(box))
                .setPositiveButton("Close", null)
                .setNeutralButton("Edit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { edit(l); }
                })
                .show();
    }

    private void field(LinearLayout box, String label, String value, boolean secret) {
        box.addView(Util.label(a, label));
        box.addView(valueRow(value, secret, label));
    }

    static String icon(Vault.Login l) {
        return l.type.equals("Bank card") ? "💳" : l.type.equals("Secure note") ? "📝" : l.type.equals("Wi-Fi") ? "📶" : "🔑";
    }

    private View valueRow(final String value, final boolean secret, final String label) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView t = new TextView(a);
        t.setText(secret ? "••••••••••" : value);
        t.setTextColor(Util.color(a, R.color.text));
        t.setTextSize(17);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (secret) {
            final Button reveal = new Button(a, null, android.R.attr.borderlessButtonStyle);
            reveal.setText("Show");
            reveal.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean hidden = reveal.getText().equals("Show");
                    t.setText(hidden ? value : "••••••••••");
                    reveal.setText(hidden ? "Hide" : "Show");
                }
            });
            row.addView(reveal);
        }
        Button copy = new Button(a, null, android.R.attr.borderlessButtonStyle);
        copy.setText("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copy(label, value, secret); }
        });
        row.addView(copy);
        return row;
    }

    private EditText input(LinearLayout box, String label, String value, String hint, int type) {
        TextView lbl = Util.label(a, label);
        box.addView(lbl);
        EditText e = Util.field(a, value);
        e.setHint(hint);
        e.setInputType(type);
        box.addView(e);
        e.setTag(lbl);
        return e;
    }

    private static void show(EditText e, boolean visible) {
        e.setVisibility(visible ? View.VISIBLE : View.GONE);
        ((View) e.getTag()).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void edit(final Vault.Login l) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Type"));
        final android.widget.Spinner type = new android.widget.Spinner(a);
        type.setAdapter(new android.widget.ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, Vault.TYPES));
        type.setSelection(Math.max(0, java.util.Arrays.asList(Vault.TYPES).indexOf(l.type)));
        box.addView(type);
        int text = InputType.TYPE_CLASS_TEXT, words = text | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
        int secret = text | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, digits = InputType.TYPE_CLASS_NUMBER;
        final EditText title = input(box, "Name", l.title, "e.g. Facebook, Marriott Bonvoy, Home Wi-Fi", words);
        final EditText url = input(box, "Website or app (for autofill)", l.url, "e.g. facebook.com", text | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user = input(box, "Username / email / membership ID", l.user, "", text | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText pass = input(box, "Password", l.password, "", secret);
        pass.setTypeface(android.graphics.Typeface.MONOSPACE);
        final Button gen = new Button(a, null, android.R.attr.borderlessButtonStyle);
        gen.setText("Generate a strong password");
        gen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pass.setText(Vault.generatePassword(16)); }
        });
        box.addView(gen);
        final EditText holder = input(box, "Card holder", l.holder, "Name on the card", words);
        final EditText number = input(box, "Card number", l.number, "", digits);
        final EditText expiry = input(box, "Valid until", l.expiry, "MM/YY", text);
        final EditText cvv = input(box, "CVV / security code", l.cvv, "", digits | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        final EditText pin = input(box, "PIN", l.pin, "", digits | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        final EditText notes = input(box, "Notes", l.notes, "", text | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                String t = Vault.TYPES[pos];
                boolean login = t.equals("Login"), card = t.equals("Bank card"), wifi = t.equals("Wi-Fi");
                show(url, login);
                show(user, login);
                show(pass, login || wifi);
                gen.setVisibility(login || wifi ? View.VISIBLE : View.GONE);
                show(holder, card);
                show(number, card);
                show(expiry, card);
                show(cvv, card);
                show(pin, card);
                ((TextView) title.getTag()).setText(wifi ? "Network name" : card ? "Card name (bank)" : "Name");
                ((TextView) notes.getTag()).setText(t.equals("Secure note") ? "Note" : "Notes");
                notes.setMinLines(t.equals("Secure note") ? 6 : 1);
            }

            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(l.id == null ? "New item" : "Edit")
                .setView(wrapScroll(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (!vault.isOpen()) return;
                        l.type = Vault.TYPES[type.getSelectedItemPosition()];
                        String t = title.getText().toString().trim();
                        l.title = t.isEmpty() ? (l.title.isEmpty() ? "Untitled" : l.title) : t;
                        l.url = url.getText().toString().trim();
                        l.user = user.getText().toString().trim();
                        l.password = pass.getText().toString();
                        l.holder = holder.getText().toString().trim();
                        l.number = number.getText().toString().replace(" ", "");
                        l.expiry = expiry.getText().toString().trim();
                        l.cvv = cvv.getText().toString().trim();
                        l.pin = pin.getText().toString().trim();
                        l.notes = notes.getText().toString().trim();
                        run(new Op() {
                            public void run() throws Exception { vault.put(l); }
                        });
                    }
                })
                .setNegativeButton("Cancel", null);
        if (l.id != null) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    Util.confirm(a, "Delete \"" + l.title + "\"?", "It will be gone for good.", "Delete",
                            new Runnable() {
                                public void run() {
                                    VaultScreen.this.run(new Op() {
                                        public void run() throws Exception { vault.delete(l); }
                                    });
                                }
                            });
                }
            });
        }
        b.show();
        if (l.id == null) Util.showKeyboard(title);
    }

    private View wrapScroll(View v) {
        android.widget.ScrollView s = new android.widget.ScrollView(a);
        s.addView(v);
        return s;
    }

    // ---------------------------------------------------------------- menu

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_LOCK, 0, "Lock passwords now");
        menu.add(0, MENU_REQUIRE_AUTH, 1, "Require fingerprint").setCheckable(true);
        menu.add(0, MENU_IMPORT, 2, "Import from CSV (Chrome, Bitwarden…)");
        menu.add(0, MENU_AUTOFILL, 3, "Use Doable for autofill…");
        menu.add(0, MENU_EXPORT, 4, "Export passwords (unencrypted)");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        boolean open = vault.isOpen();
        menu.findItem(MENU_LOCK).setVisible(open);
        menu.findItem(MENU_REQUIRE_AUTH).setVisible(open).setChecked(vault.exists() && vault.requiresAuth());
        menu.findItem(MENU_EXPORT).setVisible(open);
        menu.findItem(MENU_IMPORT).setVisible(open);
        menu.findItem(MENU_AUTOFILL).setVisible(android.os.Build.VERSION.SDK_INT >= 26);
    }

    @Override
    boolean onMenu(int id) {
        if (!vault.isOpen()) return false;
        switch (id) {
            case MENU_LOCK:
                lock();
                return true;
            case MENU_IMPORT:
                a.openDocument(new String[]{"text/*", "text/csv", "text/comma-separated-values", "application/vnd.ms-excel"}, new MainActivity.UriPicked() {
                    public void onUri(final android.net.Uri uri) {
                        run(new Op() {
                            public void run() throws Exception {
                                int n = vault.importCsv(a.readText(uri));
                                Toast.makeText(a, n == 0 ? "No new passwords found in that file" : n + " passwords imported", Toast.LENGTH_LONG).show();
                                new AlertDialog.Builder(a).setTitle("Delete the CSV file")
                                        .setMessage("The file you imported contains your passwords in plain text. Delete it from Downloads (or wherever it is) now.")
                                        .setPositiveButton("OK", null).show();
                            }
                        });
                    }
                });
                return true;
            case MENU_AUTOFILL:
                autofillSetup();
                return true;
            case MENU_REQUIRE_AUTH:
                final boolean want = !vault.requiresAuth();
                if (want && !deviceSecure()) {
                    Toast.makeText(a, "Set up a fingerprint and PIN in the phone's Settings first", Toast.LENGTH_LONG).show();
                    return true;
                }
                Runnable apply = new Runnable() {
                    public void run() {
                        VaultScreen.this.run(new Op() {
                            public void run() throws Exception {
                                vault.setRequiresAuth(want);
                                Toast.makeText(a, want ? "Fingerprint required to open passwords"
                                        : "Passwords open without fingerprint", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                };
                if (want) apply.run();
                else Util.confirm(a, "Turn off fingerprint?", "Passwords stay encrypted, but anyone who can open "
                        + "this app will be able to see them.", "Turn off", apply);
                return true;
            case MENU_EXPORT:
                Util.confirm(a, "Export unencrypted?", "This shares all your passwords as plain text. Only send "
                        + "them somewhere safe (e.g. a printout or a password manager import).", "Export", new Runnable() {
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        for (Vault.Login l : vault.logins()) {
                            sb.append(l.title).append('\n');
                            if (!l.user.isEmpty()) sb.append("  User: ").append(l.user).append('\n');
                            sb.append("  Password: ").append(l.password).append('\n');
                            if (!l.notes.isEmpty()) sb.append("  Notes: ").append(l.notes).append('\n');
                            sb.append('\n');
                        }
                        a.shareText("Passwords", sb.toString());
                    }
                });
                return true;
        }
        return false;
    }

    @android.annotation.TargetApi(26)
    private void autofillSetup() {
        android.view.autofill.AutofillManager am = a.getSystemService(android.view.autofill.AutofillManager.class);
        if (am != null && am.hasEnabledAutofillServices()) {
            Toast.makeText(a, "Doable is already your autofill service", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a).setTitle("Autofill with Doable")
                .setMessage("Android will open its autofill setting. Choose \"Doable passwords\". Then, when you log in "
                        + "to an app or website, tap the username field and choose \"Unlock Doable\" to fill it in after "
                        + "your fingerprint. Tip: put the website or app name (e.g. facebook.com) on each login.")
                .setPositiveButton("Open setting", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        try {
                            a.startExternal(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                    .setData(android.net.Uri.parse("package:" + a.getPackageName())));
                        } catch (RuntimeException e) {
                            a.startExternal(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS));
                        }
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- adapter

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }

        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_login, parent, false);
            final Vault.Login l = rows.get(position);
            TextView initial = (TextView) view.findViewById(R.id.initial);
            initial.setText(!l.type.equals("Login") ? icon(l) : l.title.isEmpty() ? "?" : l.title.substring(0, 1).toUpperCase(Locale.getDefault()));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Db.PALETTE[Math.abs(l.title.toLowerCase(Locale.getDefault()).hashCode()) % Db.PALETTE.length]);
            initial.setBackground(circle);
            ((TextView) view.findViewById(R.id.title)).setText(l.title);
            String sub = l.type.equals("Bank card") ? (l.number.length() > 4 ? "•••• " + l.number.substring(l.number.length() - 4) : "Bank card")
                    + (l.expiry.isEmpty() ? "" : " · " + l.expiry)
                    : l.type.equals("Secure note") ? "Secure note" : l.type.equals("Wi-Fi") ? "Wi-Fi password"
                    : (l.user.isEmpty() ? "No username" : l.user) + (l.url.isEmpty() ? "" : " · " + Vault.host(l.url));
            ((TextView) view.findViewById(R.id.user)).setText(sub);
            final boolean copyable = !l.type.equals("Secure note");
            view.findViewById(R.id.copy).setVisibility(copyable ? View.VISIBLE : View.INVISIBLE);
            view.findViewById(R.id.copy).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (l.type.equals("Bank card")) copy("Card number", l.number, true);
                    else copy("Password", l.password, true);
                }
            });
            return view;
        }
    }
}
