package com.sidsaxena.daybook;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted password store.
 *
 * The logins are kept as one AES-256-GCM encrypted file. Its key (the "data key") is itself encrypted by a
 * key that lives in the Android Keystore (secure hardware) and never leaves it. When fingerprint protection
 * is on, the Keystore only lets that key be used for 30 seconds after the user has confirmed with their
 * fingerprint, face or phone screen lock; methods then throw UserNotAuthenticatedException until they have.
 * The decrypted data key is only held in memory while the vault is open.
 */
public class Vault {

    public static final String[] TYPES = {"Login", "Bank card", "Secure note", "Wi-Fi"};

    public static class Login {
        public String id;
        public String type = "Login";
        public String title = "";
        public String url = "";       // website or app, used by autofill
        public String user = "";
        public String password = "";  // also the Wi-Fi password
        public String number = "";    // card number
        public String holder = "";
        public String expiry = "";    // MM/YY
        public String cvv = "";
        public String pin = "";
        public String notes = "";
        public long modified;
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_AUTH = "doable_vault_auth";
    private static final String ALIAS_OPEN = "doable_vault_open";
    private static final int AUTH_SECONDS = 30;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File file;
    private SecretKey dataKey;          // null while locked
    private List<Login> logins;

    public Vault(Context context) {
        ctx = context.getApplicationContext();
        prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE);
        file = new File(context.getFilesDir(), "vault.bin");
    }

    public boolean exists() { return prefs.contains("key"); }
    public boolean requiresAuth() { return prefs.getBoolean("auth", true); }
    public boolean isOpen() { return dataKey != null; }

    /** First use: makes a new random data key and protects it with a Keystore key. */
    public void create(boolean auth) throws GeneralSecurityException, IOException, JSONException {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey key = kg.generateKey();
        wrapKey(key, auth);
        dataKey = key;
        logins = new ArrayList<Login>();
        mergeSyncCopy(); // passwords that arrived through sync before this phone had a vault
        save();
    }

    /** Decrypts the data key and the logins. */
    public void open() throws GeneralSecurityException, IOException, JSONException {
        byte[] wrapped = Base64.decode(prefs.getString("key", ""), Base64.NO_WRAP);
        SecretKey kek = keystoreKey(requiresAuth() ? ALIAS_AUTH : ALIAS_OPEN, requiresAuth());
        dataKey = new SecretKeySpec(decrypt(kek, wrapped), "AES");
        logins = new ArrayList<Login>();
        if (file.exists()) {
            JSONArray arr = new JSONArray(new String(decrypt(dataKey, readFile()), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Login l = new Login();
                l.id = o.getString("id");
                l.title = o.optString("title");
                l.user = o.optString("user");
                l.password = o.optString("password");
                l.notes = o.optString("notes");
                l.modified = o.optLong("modified");
                l.type = o.optString("type", "Login");
                l.url = o.optString("url");
                l.number = o.optString("number");
                l.holder = o.optString("holder");
                l.expiry = o.optString("expiry");
                l.cvv = o.optString("cvv");
                l.pin = o.optString("pin");
                logins.add(l);
            }
        }
        mergeSyncCopy();
    }

    public void close() {
        dataKey = null;
        logins = null;
    }

    /** Switches fingerprint protection on or off. The vault must be open. */
    public void setRequiresAuth(boolean auth) throws GeneralSecurityException {
        wrapKey(dataKey, auth);
    }

    /** The data key (vault must be open), stored in the password-encrypted backup. */
    byte[] rawKey() {
        return dataKey.getEncoded();
    }

    /** Restore: protects the backup's data key with this phone's Keystore (vault.bin is already in place). */
    void adoptKey(byte[] raw, boolean auth) throws GeneralSecurityException {
        close();
        wrapKey(new SecretKeySpec(raw, "AES"), auth);
    }

    /** True if {@code raw} still decrypts the saved passwords (or there are none yet). For automatic backups. */
    static boolean keyOpens(Context c, byte[] raw) {
        Vault v = new Vault(c);
        if (!v.file.exists()) return true;
        try {
            decrypt(new SecretKeySpec(raw, "AES"), v.readFile());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Deletes everything, e.g. after the Keystore key became unusable. */
    public void reset() {
        close();
        file.delete();
        prefs.edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(ALIAS_AUTH);
            ks.deleteEntry(ALIAS_OPEN);
        } catch (Exception ignored) {
        }
    }

    // ---- logins (vault must be open) ----

    public List<Login> logins() {
        List<Login> out = new ArrayList<Login>(logins);
        Collections.sort(out, new Comparator<Login>() {
            public int compare(Login a, Login b) { return a.title.compareToIgnoreCase(b.title); }
        });
        return out;
    }

    public void put(Login l) throws GeneralSecurityException, IOException, JSONException {
        if (l.id == null) {
            l.id = UUID.randomUUID().toString();
            logins.add(l);
        }
        l.modified = System.currentTimeMillis();
        save();
    }

    public void delete(Login l) throws GeneralSecurityException, IOException, JSONException {
        logins.remove(l);
        save();
    }

    private void save() throws GeneralSecurityException, IOException {
        JSONArray arr = new JSONArray();
        try {
            for (Login l : logins) {
                arr.put(toJson(l));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        byte[] data = encrypt(dataKey, arr.toString().getBytes("UTF-8"));
        File tmp = new File(file.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) throw new IOException("Could not save passwords");
        writeSyncCopy();
    }

    // ---- sync: a copy of the logins sealed with the sync key, so sync can run while the vault is locked

    /** True once this phone's own passwords are in the sealed copy (or there is no vault here). */
    static boolean inSync(Context c) {
        return !new Vault(c).exists() || SyncEngine.prefs(c).getBoolean("vaultIn", false);
    }

    /** Counts password changes on this phone, so sync notices them. */
    static long edits(Context c) {
        return SyncEngine.prefs(c).getLong("vaultEdits", 0);
    }

    static File syncCopyFile(Context c) {
        return new File(c.getFilesDir(), "vault_sync.bin");
    }

    /** The logins as sync rows ({uid: {m, r} or {m, d: 1}}), or null if there is no copy. */
    static JSONObject readSyncCopy(Context c, byte[] key) {
        File f = syncCopyFile(c);
        if (key == null || !f.exists()) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
            try {
                in.readFully(data);
            } finally {
                in.close();
            }
            return new JSONObject(new String(SyncCrypto.open(key, data), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static void writeSyncCopy(Context c, byte[] key, JSONObject rows) throws GeneralSecurityException, IOException {
        File tmp = new File(c.getFilesDir(), "vault_sync.tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(SyncCrypto.seal(key, rows.toString().getBytes("UTF-8")));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(syncCopyFile(c))) throw new IOException("Could not save the sync copy");
    }

    private static JSONObject syncRow(Login l) throws JSONException {
        return new JSONObject().put("type", l.type).put("title", l.title).put("url", l.url).put("user", l.user).put("password", l.password)
                .put("number", l.number).put("holder", l.holder).put("expiry", l.expiry).put("cvv", l.cvv).put("pin", l.pin)
                .put("notes", l.notes).put("modified", l.modified);
    }

    /** After a change: rows for all logins; logins that disappeared become tombstones. */
    private void writeSyncCopy() {
        byte[] key = SyncEngine.syncKey(ctx);
        if (key == null || logins == null) return;
        try {
            JSONObject old = readSyncCopy(ctx, key);
            JSONObject rows = new JSONObject();
            for (Login l : logins) rows.put(l.id, new JSONObject().put("m", l.modified).put("r", syncRow(l)));
            if (old != null) {
                for (java.util.Iterator<String> it = old.keys(); it.hasNext(); ) {
                    String uid = it.next();
                    if (rows.has(uid)) continue;
                    JSONObject e = old.getJSONObject(uid);
                    rows.put(uid, e.optInt("d") == 1 ? e : new JSONObject().put("m", System.currentTimeMillis()).put("d", 1));
                }
            }
            writeSyncCopy(ctx, key, rows);
            SharedPreferences sp = SyncEngine.prefs(ctx);
            sp.edit().putBoolean("vaultIn", true).putLong("vaultEdits", sp.getLong("vaultEdits", 0) + 1).apply();
        } catch (Exception ignored) {
            // the next save tries again
        }
    }

    /** On opening: takes in logins that sync received while the vault was locked (newer wins). */
    private void mergeSyncCopy() throws GeneralSecurityException, IOException, JSONException {
        JSONObject rows = readSyncCopy(ctx, SyncEngine.syncKey(ctx));
        if (rows == null) {
            writeSyncCopy();
            return;
        }
        boolean changed = false;
        for (java.util.Iterator<String> it = rows.keys(); it.hasNext(); ) {
            String uid = it.next();
            JSONObject e = rows.getJSONObject(uid);
            long m = e.optLong("m");
            Login local = null;
            for (Login l : logins) if (l.id.equals(uid)) local = l;
            if (e.optInt("d") == 1) {
                if (local != null && local.modified < m) {
                    logins.remove(local);
                    changed = true;
                }
            } else if (local == null || local.modified < m) {
                Login l = fromJson(e.getJSONObject("r"));
                l.id = uid;
                l.modified = m;
                if (local != null) logins.remove(local);
                logins.add(l);
                changed = true;
            }
        }
        if (changed) save();
        else writeSyncCopy();
    }

    static JSONObject toJson(Login l) throws JSONException {
        return new JSONObject().put("id", l.id).put("type", l.type).put("title", l.title).put("url", l.url)
                .put("user", l.user).put("password", l.password).put("number", l.number).put("holder", l.holder)
                .put("expiry", l.expiry).put("cvv", l.cvv).put("pin", l.pin).put("notes", l.notes)
                .put("modified", l.modified);
    }

    static Login fromJson(JSONObject o) {
        Login l = new Login();
        l.id = o.optString("id", null);
        l.type = o.optString("type", "Login");
        l.title = o.optString("title");
        l.url = o.optString("url");
        l.user = o.optString("user");
        l.password = o.optString("password");
        l.number = o.optString("number");
        l.holder = o.optString("holder");
        l.expiry = o.optString("expiry");
        l.cvv = o.optString("cvv");
        l.pin = o.optString("pin");
        l.notes = o.optString("notes");
        l.modified = o.optLong("modified");
        return l;
    }

    /** All logins as JSON (vault must be open), for the encrypted backup. */
    public String exportJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Login l : logins) arr.put(toJson(l));
        return arr.toString();
    }

    /** Replaces / adds logins from a backup (vault must be open). */
    public void importJson(String json, boolean replace) throws JSONException, GeneralSecurityException, IOException {
        JSONArray arr = new JSONArray(json);
        if (replace) logins.clear();
        for (int i = 0; i < arr.length(); i++) {
            Login l = fromJson(arr.getJSONObject(i));
            if (l.id == null || l.id.isEmpty()) l.id = UUID.randomUUID().toString();
            logins.add(l);
        }
        save();
    }

    /**
     * Imports a password CSV from Chrome / Edge / Firefox / Bitwarden / 1Password / Proton Pass.
     * Returns the number of logins added (exact duplicates are skipped).
     */
    public int importCsv(String text) throws GeneralSecurityException, IOException {
        List<List<String>> rows = Csv.parse(text);
        if (rows.size() < 2) return 0;
        List<String> head = new ArrayList<String>();
        for (String h : rows.get(0)) head.add(h.trim().toLowerCase(java.util.Locale.ROOT));
        int name = col(head, "name", "title"), url = col(head, "url", "login_uri", "website", "uri"),
                user = col(head, "username", "login_username", "login", "email"),
                pass = col(head, "password", "login_password"), note = col(head, "note", "notes", "extra", "comment");
        if (pass < 0) return 0;
        int added = 0;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            Login l = new Login();
            l.url = get(row, url);
            l.title = get(row, name).isEmpty() ? host(l.url) : get(row, name);
            if (l.title.isEmpty()) l.title = "Imported login";
            l.user = get(row, user);
            l.password = get(row, pass);
            l.notes = get(row, note);
            if (l.password.isEmpty() && l.user.isEmpty()) continue;
            boolean dup = false;
            for (Login o : logins) dup |= o.title.equals(l.title) && o.user.equals(l.user) && o.password.equals(l.password);
            if (dup) continue;
            l.id = UUID.randomUUID().toString();
            l.modified = System.currentTimeMillis();
            logins.add(l);
            added++;
        }
        save();
        return added;
    }

    private static int col(List<String> head, String... names) {
        for (String n : names) if (head.contains(n)) return head.indexOf(n);
        return -1;
    }

    private static String get(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i).trim() : "";
    }

    /** "https://www.facebook.com/login" → "facebook.com". */
    static String host(String url) {
        String h = url.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^[a-z]+://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    // ---- crypto ----

    private void wrapKey(SecretKey key, boolean auth) throws GeneralSecurityException {
        byte[] wrapped = encrypt(keystoreKey(auth ? ALIAS_AUTH : ALIAS_OPEN, auth), key.getEncoded());
        prefs.edit().putString("key", Base64.encodeToString(wrapped, Base64.NO_WRAP)).putBoolean("auth", auth).commit();
    }

    private static SecretKey keystoreKey(String alias, boolean auth) throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        try {
            ks.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);

        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (auth) {
            b.setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            } else {
                b.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS);
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(b.build());
        return kg.generateKey();
    }

    /** AES-GCM; output is the 12-byte IV followed by the ciphertext. */
    private static byte[] encrypt(SecretKey key, byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        if (key instanceof SecretKeySpec) {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        } else {
            c.init(Cipher.ENCRYPT_MODE, key); // Keystore keys pick their own random IV
        }
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] decrypt(SecretKey key, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
        return c.doFinal(data, 12, data.length - 12);
    }

    private byte[] readFile() throws IOException {
        byte[] buf = new byte[(int) file.length()];
        FileInputStream in = new FileInputStream(file);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        return buf;
    }

    /** Random password: letters, digits and a few symbols, avoiding look-alike characters. */
    public static String generatePassword(int length) {
        String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%&*?-_";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
