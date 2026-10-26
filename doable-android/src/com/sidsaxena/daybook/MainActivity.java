package com.sidsaxena.daybook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Hosts the modules (to-do, notes, groceries, passwords, activity log), the side menu, the optional
 * fingerprint app lock and the light/dark theme setting.
 */
public class MainActivity extends Activity {

    private static final int MENU_LOCK = 1;
    private static final int MENU_THEME = 2;

    private static final int REQ_AUTH = 1;

    private static final int[] MODULE_NAMES = {
            R.string.module_tasks, R.string.module_notes, R.string.module_grocery, R.string.module_vault, R.string.module_log};
    private static final int[] MODULE_ICONS = {
            R.drawable.ic_todo, R.drawable.ic_note, R.drawable.ic_cart, R.drawable.ic_key, R.drawable.ic_history};

    interface AuthResult {
        void done(boolean ok, int errorCode);
    }

    Db db;
    SharedPreferences prefs;
    FrameLayout content;

    private Screen[] screens;
    private Screen current;
    private int currentIndex;
    private View drawer, scrim, lockView;
    private LinearLayout drawerItems;

    // App lock state
    private boolean locked;
    private boolean authInProgress;
    private boolean leavingForExternal; // launched the share sheet or the system lock screen
    private AuthResult pendingAuth;     // for the pre-Android-9 lock screen activity

    // ---------------------------------------------------------------- theme

    /** Applies the Light/Dark choice (0 = follow the phone) by overriding the night-mode configuration. */
    @Override
    protected void attachBaseContext(Context base) {
        int theme = base.getSharedPreferences("daybook", MODE_PRIVATE).getInt("theme", 0);
        if (theme != 0) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (theme == 2 ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    private void chooseTheme() {
        int current = prefs.getInt("theme", 0);
        new AlertDialog.Builder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(new String[]{"Same as phone", "Light", "Dark"}, current,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                if (which == prefs.getInt("theme", 0)) return;
                                prefs.edit().putInt("theme", which).commit();
                                recreate();
                            }
                        })
                .show();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new Db(this);
        prefs = getSharedPreferences("daybook", MODE_PRIVATE);

        content = (FrameLayout) findViewById(R.id.content);
        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.scrim);
        lockView = findViewById(R.id.lock_view);
        drawerItems = (LinearLayout) findViewById(R.id.drawer_items);

        screens = new Screen[]{
                new TasksScreen(this), new NotesScreen(this), new GroceryScreen(this),
                new VaultScreen(this), new LogScreen(this)};
        for (Screen s : screens) {
            s.root.setVisibility(View.GONE);
            content.addView(s.root);
        }

        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        findViewById(R.id.unlock_btn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { appUnlockPrompt(); }
        });
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        locked = lockEnabled() && (savedInstanceState == null || savedInstanceState.getBoolean("locked", true));
        int module = prefs.getInt("module", 0);
        show(module >= 0 && module < screens.length ? module : 0, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("locked", locked);
    }

    @Override
    protected void onStart() {
        super.onStart();
        leavingForExternal = false;
        showLockScreen(locked);
        if (locked && !authInProgress) {
            lockView.post(new Runnable() {
                public void run() { if (locked && !authInProgress) appUnlockPrompt(); }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!locked) current.onShow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (authInProgress || isChangingConfigurations()) return;
        current.onStop();
        if (lockEnabled() && !leavingForExternal) {
            locked = true;
            showLockScreen(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (current.onBack()) return;
        else if (currentIndex != 0) show(0, true);
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- modules & side menu

    private void show(int index, boolean announce) {
        if (current != null) {
            current.onHide();
            current.root.setVisibility(View.GONE);
        }
        currentIndex = index;
        current = screens[index];
        current.root.setVisibility(View.VISIBLE);
        prefs.edit().putInt("module", index).apply();
        buildDrawer();
        screenChanged();
        if (announce && !locked) current.onShow();
    }

    /** Refreshes title, home button and menu after a screen changed its state (e.g. opened a note). */
    void screenChanged() {
        if (getActionBar() != null) {
            getActionBar().setTitle(current.title());
            getActionBar().setHomeAsUpIndicator(current.homeIsBack() ? 0 : R.drawable.ic_menu);
        }
        invalidateOptionsMenu();
    }

    private void buildDrawer() {
        drawerItems.removeAllViews();
        for (int i = 0; i < screens.length; i++) {
            final int index = i;
            boolean on = i == currentIndex;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Util.dp(this, 20), 0, Util.dp(this, 20), 0);
            row.setMinimumHeight(Util.dp(this, 52));
            if (on) row.setBackgroundColor(Util.color(this, R.color.selected));
            else row.setBackgroundResource(android.R.drawable.list_selector_background);
            int color = Util.color(this, on ? R.color.primary_text : R.color.muted);

            ImageView icon = new ImageView(this);
            icon.setImageResource(MODULE_ICONS[i]);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            row.addView(icon, new LinearLayout.LayoutParams(Util.dp(this, 24), Util.dp(this, 24)));

            TextView label = new TextView(this);
            label.setText(MODULE_NAMES[i]);
            label.setTextSize(16);
            label.setTextColor(on ? color : Util.color(this, R.color.text));
            if (on) label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            label.setPadding(Util.dp(this, 24), 0, 0, 0);
            row.addView(label);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closeDrawer();
                    if (index != currentIndex) show(index, true);
                }
            });
            drawerItems.addView(row);
            if (i == 3) { // divider before the less-used activity log
                View line = new View(this);
                line.setBackgroundColor(Util.color(this, R.color.divider));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(0, Util.dp(this, 8), 0, Util.dp(this, 8));
                drawerItems.addView(line, lp);
            }
        }
    }

    private void openDrawer() {
        Util.hideKeyboard(content);
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        drawer.setTranslationX(-Util.dp(this, 288));
        drawer.animate().translationX(0).setDuration(200).start();
    }

    private void closeDrawer() {
        scrim.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            public void run() { scrim.setVisibility(View.GONE); }
        }).start();
        drawer.animate().translationX(-Util.dp(this, 288)).setDuration(180).withEndAction(new Runnable() {
            public void run() { drawer.setVisibility(View.GONE); }
        }).start();
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        current.onCreateMenu(menu);
        menu.add(0, MENU_LOCK, 50, "Fingerprint app lock").setCheckable(true);
        menu.add(0, MENU_THEME, 51, "Theme (light / dark)");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        onCreateOptionsMenu(menu);
        current.onPrepareMenu(menu);
        menu.findItem(MENU_LOCK).setChecked(lockEnabled());
        return !locked;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (locked) return true;
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (current.homeIsBack()) current.onBack();
            else if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
            else openDrawer();
            return true;
        }
        if (current.onMenu(id)) {
            invalidateOptionsMenu();
            return true;
        }
        if (id == MENU_LOCK) {
            toggleAppLock();
            return true;
        }
        if (id == MENU_THEME) {
            chooseTheme();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- helpers for screens

    void shareText(String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, text);
        leavingForExternal = true;
        startActivity(Intent.createChooser(send, "Share"));
    }

    /** Blocks screenshots and blanks the recent-apps preview (used while passwords are shown). */
    void setSecure(boolean secure) {
        if (secure) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    /**
     * Asks for fingerprint / face / phone screen lock. {@code strong} limits biometrics to the "strong"
     * class, which is what the Keystore accepts for unlocking the password vault.
     */
    void confirmUser(String title, boolean strong, final AuthResult result) {
        if (Build.VERSION.SDK_INT >= 28) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                    .setTitle(title)
                    .setSubtitle("Use your fingerprint, face or phone screen lock");
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators((strong ? BiometricManager.Authenticators.BIOMETRIC_STRONG
                        : BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            authInProgress = true;
            try {
                b.build().authenticate(new CancellationSignal(), getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                                authInProgress = false;
                                result.done(true, 0);
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                authInProgress = false;
                                result.done(false, code);
                            }
                        });
            } catch (RuntimeException e) {
                authInProgress = false;
                result.done(false, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT);
            }
        } else {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            Intent i = km.createConfirmDeviceCredentialIntent(title, "Use your fingerprint or phone screen lock");
            if (i == null) { // no screen lock set on the phone
                result.done(false, BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL);
                return;
            }
            pendingAuth = result;
            authInProgress = true;
            leavingForExternal = true;
            startActivityForResult(i, REQ_AUTH);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_AUTH && pendingAuth != null) {
            authInProgress = false;
            AuthResult r = pendingAuth;
            pendingAuth = null;
            r.done(resultCode == RESULT_OK, 0);
        }
    }

    // ---------------------------------------------------------------- app lock

    private boolean lockEnabled() {
        return prefs.getBoolean("lock", false);
    }

    private void appUnlockPrompt() {
        confirmUser("Unlock " + getString(R.string.app_name), false, new AuthResult() {
            public void done(boolean ok, int error) {
                if (ok || error == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                        || error == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                    // Unlock on success, or when the phone no longer has any screen lock to check against.
                    locked = false;
                    showLockScreen(false);
                    current.onShow();
                }
                // Otherwise stay on the lock screen; "Unlock" retries.
            }
        });
    }

    /** The lock screen also hides the action bar so its menu (e.g. turning the lock off) is unreachable. */
    private void showLockScreen(boolean show) {
        lockView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (getActionBar() != null) {
            if (show) getActionBar().hide();
            else getActionBar().show();
        }
        if (show) {
            if (drawer.getVisibility() == View.VISIBLE) {
                drawer.setVisibility(View.GONE);
                scrim.setVisibility(View.GONE);
            }
            Util.hideKeyboard(content);
        }
    }

    private void toggleAppLock() {
        if (lockEnabled()) {
            prefs.edit().putBoolean("lock", false).apply();
            Toast.makeText(this, "App lock turned off", Toast.LENGTH_SHORT).show();
            return;
        }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!km.isDeviceSecure()) {
            new AlertDialog.Builder(this)
                    .setTitle("Set up a screen lock first")
                    .setMessage("To lock this app, add a fingerprint and a PIN/pattern in your phone's "
                            + "Settings → Security. Then turn this on again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        prefs.edit().putBoolean("lock", true).apply();
        Toast.makeText(this, "Locked whenever you leave the app. Unlock with your fingerprint.",
                Toast.LENGTH_LONG).show();
    }
}
