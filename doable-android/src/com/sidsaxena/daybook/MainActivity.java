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
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.List;

/**
 * Hosts the modules (to-do, notes, groceries, passwords, activity log), the side menu, the optional
 * fingerprint app lock and the light/dark theme setting.
 */
public class MainActivity extends Activity {

    private static final int MENU_LOCK = 1;
    private static final int MENU_THEME = 2;
    private static final int MENU_SEARCH = 3;
    private static final int MENU_BACKUP = 4;
    private static final int MENU_MODULES = 5;

    private static final int REQ_AUTH = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_PICK = 3;
    private static final int REQ_SCAN = 4;
    private static final int REQ_OPEN_DOC = 5;
    private static final int REQ_CREATE_DOC = 6;
    private static final int REQ_TREE = 9;

    interface UriPicked {
        void onUri(android.net.Uri uri);
    }

    private UriPicked pendingUri;

    /** Modules in side-menu order; the last one (activity log) sits below a divider. */
    private static final String[] MODULE_IDS = {"today", "tasks", "notes", "diary", "trips", "grocery", "habits",
            "dates", "checklists", "wallet", "vault", "log"};
    private static final int[] MODULE_NAMES = {
            R.string.module_today, R.string.module_tasks, R.string.module_notes, R.string.module_diary, R.string.module_trips,
            R.string.module_grocery, R.string.module_habits, R.string.module_dates, R.string.module_checklists,
            R.string.module_wallet, R.string.module_vault, R.string.module_log};
    private static final int[] MODULE_ICONS = {
            R.drawable.ic_today, R.drawable.ic_todo, R.drawable.ic_note, R.drawable.ic_diary, R.drawable.ic_trip,
            R.drawable.ic_cart, R.drawable.ic_habit, R.drawable.ic_cake, R.drawable.ic_checklist,
            R.drawable.ic_wallet, R.drawable.ic_key, R.drawable.ic_history};

    interface AuthResult {
        void done(boolean ok, int errorCode);
    }

    interface FilesPicked {
        void onFiles(List<String> files);
    }

    Db db;
    Store st;
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
    private FilesPicked pendingFiles;   // waiting for the camera / file picker
    private WebView printView;          // kept alive while a PDF is being prepared

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
        st = new Store(db);
        prefs = getSharedPreferences("daybook", MODE_PRIVATE);

        content = (FrameLayout) findViewById(R.id.content);
        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.scrim);
        lockView = findViewById(R.id.lock_view);
        drawerItems = (LinearLayout) findViewById(R.id.drawer_items);

        screens = new Screen[]{
                new TodayScreen(this), new TasksScreen(this), new NotesScreen(this), new DiaryScreen(this),
                new TripsScreen(this), new GroceryScreen(this), new HabitsScreen(this), new DatesScreen(this),
                new ChecklistsScreen(this), new WalletScreen(this), new VaultScreen(this), new LogScreen(this)};
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
        int module = java.util.Arrays.asList(MODULE_IDS).indexOf(prefs.getString("module_id", "today"));
        if (module < 0 || !isModuleVisible(MODULE_IDS[module])) module = firstVisible();
        show(module, false);

        new Thread(new Runnable() {
            public void run() {
                Reminders.createChannels(MainActivity.this);
                Reminders.schedule(MainActivity.this);
            }
        }).start();
        setupShortcuts();
        if (SyncEngine.enabled(this)) SyncJob.schedule(this); // replaces the slower schedule of older versions
        String restored = prefs.getString("restoreMsg", null);
        if (restored != null) {
            prefs.edit().remove("restoreMsg").apply();
            new AlertDialog.Builder(this).setMessage(restored).setPositiveButton("OK", null).show();
        }
        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private Intent pendingIntent;  // waits until the app is unlocked

    /** Opens what a notification, widget, shortcut or share asked for. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        if (GoogleDrive.handleRedirect(this, i.getData())) {
            setIntent(new Intent(this, MainActivity.class));
            return;
        }
        boolean share = ShareIn.isShare(i);
        if (!share && i.getStringExtra("module") == null) return;
        if (locked) {
            pendingIntent = i;
            return;
        }
        if (share) {
            ShareIn.handle(this, i);
        } else {
            go(i.getStringExtra("module"), i.getLongExtra("item", 0), i.getStringExtra("quick"));
        }
        setIntent(new Intent(this, MainActivity.class));  // don't repeat it after rotation / recreation
    }

    private void runPendingIntent() {
        Intent i = pendingIntent;
        pendingIntent = null;
        if (i != null) handleIntent(i);
    }

    /** Opens a module, then optionally one item in it or a quick action ("task", "note", "expense"…). */
    void go(String module, long item, String quick) {
        int index = java.util.Arrays.asList(MODULE_IDS).indexOf(module);
        if (index < 0) return;
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        if (index != currentIndex) show(index, true);
        if (item != 0) current.open(item);
        if (quick != null) current.quick(quick);
    }

    Screen screen(String module) {
        return screens[java.util.Arrays.asList(MODULE_IDS).indexOf(module)];
    }

    String moduleName(String module) {
        int i = java.util.Arrays.asList(MODULE_IDS).indexOf(module);
        return i < 0 ? module : getString(MODULE_NAMES[i]);
    }

    /** True while another app (camera, file picker, share sheet, system lock screen) is in front. */
    boolean isAway() {
        return leavingForExternal;
    }

    // ---------------------------------------------------------------- module visibility

    boolean isModuleVisible(String id) {
        return !("," + prefs.getString("hiddenModules", "") + ",").contains("," + id + ",");
    }

    private int firstVisible() {
        for (int i = 0; i < MODULE_IDS.length; i++) if (isModuleVisible(MODULE_IDS[i])) return i;
        return 0;
    }

    private void chooseModules() {
        final String[] names = new String[MODULE_IDS.length];
        final boolean[] on = new boolean[MODULE_IDS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = getString(MODULE_NAMES[i]);
            on[i] = isModuleVisible(MODULE_IDS[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Show in the menu")
                .setMultiChoiceItems(names, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean checked) { on[which] = checked; }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        StringBuilder hidden = new StringBuilder();
                        int shown = 0;
                        for (int i = 0; i < on.length; i++) {
                            if (on[i]) shown++;
                            else hidden.append(hidden.length() == 0 ? "" : ",").append(MODULE_IDS[i]);
                        }
                        if (shown == 0) {
                            Toast.makeText(MainActivity.this, "Keep at least one module", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        prefs.edit().putString("hiddenModules", hidden.toString()).apply();
                        if (!isModuleVisible(MODULE_IDS[currentIndex])) show(firstVisible(), true);
                        else buildDrawer();
                        Toast.makeText(MainActivity.this, "Hidden modules keep their data", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- app-icon shortcuts

    /** Long-press the app icon: new task, new note, shopping list, search. */
    private void setupShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            String[][] defs = {
                    {"task", "New task", "tasks", "task"},
                    {"note", "New note", "notes", "note"},
                    {"grocery", "Shopping list", "grocery", "grocery"},
                    {"today", "Today", "today", null},
            };
            int[] icons = {R.drawable.ic_todo, R.drawable.ic_note, R.drawable.ic_cart, R.drawable.ic_today};
            List<android.content.pm.ShortcutInfo> list = new java.util.ArrayList<android.content.pm.ShortcutInfo>();
            for (int k = 0; k < defs.length; k++) {
                Intent i = new Intent(Intent.ACTION_VIEW, null, this, MainActivity.class).putExtra("module", defs[k][2])
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (defs[k][3] != null) i.putExtra("quick", defs[k][3]);
                android.graphics.drawable.Icon icon = android.graphics.drawable.Icon.createWithResource(this, icons[k])
                        .setTint(Util.color(this, R.color.primary));
                list.add(new android.content.pm.ShortcutInfo.Builder(this, defs[k][0])
                        .setShortLabel(defs[k][1]).setIcon(icon).setIntent(i).build());
            }
            sm.setDynamicShortcuts(list);
        } catch (RuntimeException ignored) {
            // shortcuts are a nice-to-have
        }
    }

    /** Sync wrote new data: refresh what's on screen (not while an editor is open, to keep typing undisturbed). */
    void syncChanged() {
        if (locked || isFinishing()) return;
        if (!current.homeIsBack()) current.onShow();
    }

    /** Closes the open note / diary entry (saving it) before the database is replaced. */
    void beforeRestore() {
        current.onHide();
    }

    /** After a restore: reopen everything from the restored data. */
    void restarted(String message) {
        prefs.edit().putString("restoreMsg", message).commit();
        recreate();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("locked", locked);
    }

    // While Doable is on screen it checks every few seconds whether this phone or Drive has something new
    // (one small request), so changes from other devices show up almost right away.
    private final android.os.Handler syncHandler = new android.os.Handler();
    private final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable syncTick = new Runnable() {
        public void run() {
            syncHandler.postDelayed(this, 10000);
            if (!SyncEngine.enabled(MainActivity.this) || SyncEngine.syncKey(MainActivity.this) == null || !checking.compareAndSet(false, true)) return;
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                public void run() {
                    boolean need;
                    try {
                        need = SyncEngine.needsRound(app);
                    } catch (Exception e) {
                        need = false; // offline or signed out: the status shows it after the next full round
                    }
                    checking.set(false);
                    if (need) runOnUiThread(new Runnable() {
                        public void run() { if (!isFinishing()) SyncUi.syncNow(MainActivity.this, false); }
                    });
                }
            }).start();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        leavingForExternal = false;
        syncHandler.removeCallbacks(syncTick);
        syncHandler.post(syncTick);
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
        syncHandler.removeCallbacks(syncTick);
        if (authInProgress || isChangingConfigurations()) return;
        current.onStop();
        AutoBackup.runIfDue(this);
        if (!leavingForExternal && SyncEngine.enabled(this) && SyncEngine.syncKey(this) != null) SyncUi.syncNow(this, false);
        new Thread(new Runnable() {
            public void run() { Reminders.refresh(getApplicationContext()); }
        }).start();
        if (lockEnabled() && !leavingForExternal) {
            locked = true;
            showLockScreen(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (current.onBack()) return;
        else if (currentIndex != firstVisible()) show(firstVisible(), true);
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
        prefs.edit().putString("module_id", MODULE_IDS[index]).apply();
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
            if (!isModuleVisible(MODULE_IDS[i])) continue;
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
            if (MODULE_IDS[i].equals("log")) divider(); // the less-used activity log sits below a divider
            drawerItems.addView(row);
        }
        divider();
        drawerAction(R.drawable.ic_sync, "Sync with Google", new Runnable() {
            public void run() { SyncUi.show(MainActivity.this); }
        });
        drawerAction(R.drawable.ic_backup, "Backup & restore", new Runnable() {
            public void run() { Backup.show(MainActivity.this); }
        });
        drawerAction(R.drawable.ic_tune, "Show / hide modules", new Runnable() {
            public void run() { chooseModules(); }
        });
    }

    private void divider() {
        View line = new View(this);
        line.setBackgroundColor(Util.color(this, R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, Util.dp(this, 8), 0, Util.dp(this, 8));
        drawerItems.addView(line, lp);
    }

    private void drawerAction(int iconRes, String text, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Util.dp(this, 20), 0, Util.dp(this, 20), 0);
        row.setMinimumHeight(Util.dp(this, 48));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Util.color(this, R.color.muted), PorterDuff.Mode.SRC_IN);
        row.addView(icon, new LinearLayout.LayoutParams(Util.dp(this, 22), Util.dp(this, 22)));
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setTextColor(Util.color(this, R.color.text));
        label.setPadding(Util.dp(this, 24), 0, 0, 0);
        row.addView(label);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                action.run();
            }
        });
        drawerItems.addView(row);
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
        MenuItem search = menu.add(0, MENU_SEARCH, 40, "Search everything");
        search.setIcon(R.drawable.ic_search);
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_BACKUP, 49, "Backup & restore…");
        menu.add(0, MENU_LOCK, 50, "Fingerprint app lock").setCheckable(true);
        menu.add(0, MENU_THEME, 51, "Theme (light / dark)");
        menu.add(0, MENU_MODULES, 52, "Show / hide modules…");
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
        if (id == MENU_SEARCH) {
            Search.open(this);
            return true;
        }
        if (id == MENU_BACKUP) {
            Backup.show(this);
            return true;
        }
        if (id == MENU_MODULES) {
            chooseModules();
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
        startExternal(Intent.createChooser(send, "Share"));
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

    private Runnable pendingPermission;

    /** Runs {@code granted} once the runtime permission is given (asks first if needed). */
    void withPermission(String permission, String why, Runnable granted) {
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            granted.run();
            return;
        }
        pendingPermission = granted;
        leavingForExternal = true;
        requestPermissions(new String[]{permission}, 8);
        Toast.makeText(this, why, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == 8) {
            Runnable r = pendingPermission;
            pendingPermission = null;
            if (r != null && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) r.run();
            else if (r != null) Toast.makeText(this, "Permission not given", Toast.LENGTH_SHORT).show();
        }
    }

    /** Asks for permission to show notifications (Android 13+), once a reminder is first set. */
    void ensureNotifications() {
        Reminders.createChannels(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            leavingForExternal = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

        /** Lets the user pick a file to read (e.g. a backup or a CSV). */
    void openDocument(String[] mimeTypes, UriPicked cb) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        pendingUri = cb;
        leavingForExternal = true;
        try {
            startActivityForResult(i, REQ_OPEN_DOC);
        } catch (RuntimeException e) {
            pendingUri = null;
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    /** Lets the user choose where to save a new file (Downloads, Google Drive, USB stick…). */
    void createDocument(String fileName, String mimeType, UriPicked cb) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, fileName);
        pendingUri = cb;
        leavingForExternal = true;
        try {
            startActivityForResult(i, REQ_CREATE_DOC);
        } catch (RuntimeException e) {
            pendingUri = null;
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    /** Lets the user pick a folder Doable may keep writing to (automatic backups). */
    void openFolder(UriPicked cb) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pendingUri = cb;
        leavingForExternal = true;
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (RuntimeException e) {
            pendingUri = null;
            Toast.makeText(this, "No folder picker on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    String readText(android.net.Uri uri) throws java.io.IOException {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("Cannot open file");
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /** Starts another app (viewer, share sheet, …) without the app lock kicking in on return. */
    void startExternal(Intent i) {
        leavingForExternal = true;
        startActivity(i);
    }

    // ---------------------------------------------------------------- photos & files

    void pickAttachments(final FilesPicked result) {
        pickAttachments(result, false);
    }

    /** Photo, document scan, gallery, file or (optionally) a voice note; delivers stored attachment file names. */
    void pickAttachments(final FilesPicked result, final boolean voice) {
        String[] items = voice
                ? new String[]{"Take a photo", "Scan a document", "Choose photos or screenshots", "Choose a file (PDF or image)", "Record a voice note"}
                : new String[]{"Take a photo", "Scan a document", "Choose photos or screenshots", "Choose a file (PDF or image)"};
        new AlertDialog.Builder(this)
                .setItems(items,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int pick) {
                                if (pick == 4) {
                                    withPermission(android.Manifest.permission.RECORD_AUDIO, "Doable needs the microphone to record voice notes.", new Runnable() {
                                        public void run() {
                                            Voice.record(MainActivity.this, new Voice.Done() {
                                                public void onRecorded(String file) {
                                                    result.onFiles(java.util.Collections.singletonList(file));
                                                }
                                            });
                                        }
                                    });
                                    return;
                                }
                                int which = pick == 0 ? 0 : pick == 1 ? -1 : pick - 1;
                                Intent i;
                                int req = REQ_PICK;
                                if (which <= 0) {
                                    new java.io.File(getFilesDir(), "capture.jpg").delete();
                                    i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                                    i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Files.CAPTURE);
                                    i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    req = which < 0 ? REQ_SCAN : REQ_CAMERA;
                                } else if (which == 1 && Build.VERSION.SDK_INT >= 33) {
                                    i = new Intent("android.provider.action.PICK_IMAGES");
                                    i.putExtra("android.provider.extra.PICK_IMAGES_MAX", 20);
                                } else if (which == 1) {
                                    i = new Intent(Intent.ACTION_GET_CONTENT);
                                    i.setType("image/*");
                                    i.addCategory(Intent.CATEGORY_OPENABLE);
                                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                                } else {
                                    i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                    i.setType("*/*");
                                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                                    i.addCategory(Intent.CATEGORY_OPENABLE);
                                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                                }
                                pendingFiles = result;
                                leavingForExternal = true;
                                try {
                                    startActivityForResult(i, req);
                                } catch (RuntimeException e) {
                                    pendingFiles = null;
                                    Toast.makeText(MainActivity.this, "No app found for this on your phone", Toast.LENGTH_LONG).show();
                                }
                            }
                        })
                .show();
    }

    /** Copies files shared from other apps into Doable, then reports their stored names. */
    void importUris(List<android.net.Uri> uris, FilesPicked result) {
        importPicked(result, uris, false);
    }

    /** Copies the picked/captured files in the background, then reports them. */
    private void importPicked(final FilesPicked result, final List<android.net.Uri> uris, final boolean camera) {
        Toast.makeText(this, "Adding…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final List<String> files = new java.util.ArrayList<String>();
                int failed = 0;
                if (camera) {
                    java.io.File capture = new java.io.File(getFilesDir(), "capture.jpg");
                    try {
                        files.add(Attachments.importImage(MainActivity.this, capture));
                    } catch (Exception e) {
                        failed++;
                    }
                    capture.delete();
                }
                for (android.net.Uri u : uris) {
                    try {
                        files.add(Attachments.importUri(MainActivity.this, u));
                    } catch (Exception e) {
                        failed++;
                    }
                }
                final int f = failed;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (f > 0) Toast.makeText(MainActivity.this, f + " file(s) could not be added", Toast.LENGTH_LONG).show();
                        if (!files.isEmpty()) result.onFiles(files);
                    }
                });
            }
        }).start();
    }

    /** Renders HTML (images may use "att/<file>") and opens Android's print dialog, where "Save as PDF" is offered. */
    void printHtml(final String title, String html) {
        final WebView w = new WebView(this);
        w.getSettings().setAllowFileAccess(true);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (printView != view) return;
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                leavingForExternal = true;
                pm.print(title, view.createPrintDocumentAdapter(title),
                        new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
            }
        });
        printView = w;
        w.loadDataWithBaseURL("file://" + getFilesDir().getAbsolutePath() + "/", html, "text/html", "UTF-8", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_TREE && pendingUri != null) {
            UriPicked cb = pendingUri;
            pendingUri = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    getContentResolver().takePersistableUriPermission(data.getData(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // still usable until the app restarts
                }
                cb.onUri(data.getData());
            }
            return;
        }
        if ((requestCode == REQ_OPEN_DOC || requestCode == REQ_CREATE_DOC) && pendingUri != null) {
            UriPicked cb = pendingUri;
            pendingUri = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) cb.onUri(data.getData());
            return;
        }
        if (requestCode == REQ_SCAN && pendingFiles != null) {
            final FilesPicked r = pendingFiles;
            pendingFiles = null;
            if (resultCode != RESULT_OK) return;
            final java.io.File capture = new java.io.File(getFilesDir(), "capture.jpg");
            DocScan.crop(this, capture, new DocScan.Done() {
                public void onScanned(String file) {
                    capture.delete();
                    r.onFiles(java.util.Collections.singletonList(file));
                }
            });
            return;
        }
        if ((requestCode == REQ_CAMERA || requestCode == REQ_PICK) && pendingFiles != null) {
            FilesPicked r = pendingFiles;
            pendingFiles = null;
            if (resultCode != RESULT_OK) return;
            List<android.net.Uri> uris = new java.util.ArrayList<android.net.Uri>();
            if (requestCode == REQ_PICK && data != null) {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            importPicked(r, uris, requestCode == REQ_CAMERA);
            return;
        }
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
                    runPendingIntent();
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
