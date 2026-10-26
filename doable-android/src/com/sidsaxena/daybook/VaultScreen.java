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
            if (q.isEmpty() || (l.title + " " + l.user + " " + l.notes).toLowerCase(Locale.getDefault()).contains(q)) {
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

    /** Details: username and password with copy / show buttons. */
    private void show(final Vault.Login l) {
        LinearLayout box = Util.dialogBox(a);
        if (!l.user.isEmpty()) {
            box.addView(Util.label(a, "Username / ID"));
            box.addView(valueRow(l.user, false, "Username"));
        }
        box.addView(Util.label(a, "Password"));
        box.addView(valueRow(l.password, true, "Password"));
        if (!l.notes.isEmpty()) {
            box.addView(Util.label(a, "Notes"));
            TextView notes = new TextView(a);
            notes.setText(l.notes);
            notes.setTextIsSelectable(true);
            notes.setTextColor(Util.color(a, R.color.text));
            notes.setTextSize(16);
            notes.setPadding(Util.dp(a, 4), Util.dp(a, 4), 0, 0);
            box.addView(notes);
        }
        new AlertDialog.Builder(a)
                .setTitle(l.title)
                .setView(box)
                .setPositiveButton("Close", null)
                .setNeutralButton("Edit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { edit(l); }
                })
                .show();
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

    private void edit(final Vault.Login l) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Website or service"));
        final EditText title = Util.field(a, l.title);
        title.setHint("e.g. Facebook, Marriott Bonvoy");
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(title);
        box.addView(Util.label(a, "Username / email / membership ID"));
        final EditText user = Util.field(a, l.user);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        box.addView(user);
        box.addView(Util.label(a, "Password"));
        final EditText pass = Util.field(a, l.password);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pass.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(pass);
        Button gen = new Button(a, null, android.R.attr.borderlessButtonStyle);
        gen.setText("Generate a strong password");
        gen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pass.setText(Vault.generatePassword(16)); }
        });
        box.addView(gen);
        box.addView(Util.label(a, "Notes"));
        final EditText notes = Util.field(a, l.notes);
        box.addView(notes);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(l.id == null ? "New password" : "Edit password")
                .setView(wrapScroll(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (!vault.isOpen()) return;
                        String t = title.getText().toString().trim();
                        l.title = t.isEmpty() ? (l.title.isEmpty() ? "Untitled" : l.title) : t;
                        l.user = user.getText().toString().trim();
                        l.password = pass.getText().toString();
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
                    Util.confirm(a, "Delete \"" + l.title + "\"?", "This password will be gone for good.", "Delete",
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
        menu.add(0, MENU_EXPORT, 2, "Export passwords (unencrypted)");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        boolean open = vault.isOpen();
        menu.findItem(MENU_LOCK).setVisible(open);
        menu.findItem(MENU_REQUIRE_AUTH).setVisible(open).setChecked(vault.exists() && vault.requiresAuth());
        menu.findItem(MENU_EXPORT).setVisible(open);
    }

    @Override
    boolean onMenu(int id) {
        if (!vault.isOpen()) return false;
        switch (id) {
            case MENU_LOCK:
                lock();
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

    // ---------------------------------------------------------------- adapter

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }

        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_login, parent, false);
            final Vault.Login l = rows.get(position);
            TextView initial = (TextView) view.findViewById(R.id.initial);
            initial.setText(l.title.isEmpty() ? "?" : l.title.substring(0, 1).toUpperCase(Locale.getDefault()));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Db.PALETTE[Math.abs(l.title.toLowerCase(Locale.getDefault()).hashCode()) % Db.PALETTE.length]);
            initial.setBackground(circle);
            ((TextView) view.findViewById(R.id.title)).setText(l.title);
            ((TextView) view.findViewById(R.id.user)).setText(l.user.isEmpty() ? "No username" : l.user);
            view.findViewById(R.id.copy).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { copy("Password", l.password, true); }
            });
            return view;
        }
    }
}
