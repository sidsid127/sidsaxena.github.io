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

    public static class Login {
        public String id;
        public String title = "";
        public String user = "";
        public String password = "";
        public String notes = "";
        public long modified;
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_AUTH = "doable_vault_auth";
    private static final String ALIAS_OPEN = "doable_vault_open";
    private static final int AUTH_SECONDS = 30;

    private final SharedPreferences prefs;
    private final File file;
    private SecretKey dataKey;          // null while locked
    private List<Login> logins;

    public Vault(Context context) {
        prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE);
        file = new File(context.getFilesDir(), "vault.bin");
    }

    public boolean exists() { return prefs.contains("key"); }
    public boolean requiresAuth() { return prefs.getBoolean("auth", true); }
    public boolean isOpen() { return dataKey != null; }

    /** First use: makes a new random data key and protects it with a Keystore key. */
    public void create(boolean auth) throws GeneralSecurityException, IOException {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey key = kg.generateKey();
        wrapKey(key, auth);
        dataKey = key;
        logins = new ArrayList<Login>();
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
                logins.add(l);
            }
        }
    }

    public void close() {
        dataKey = null;
        logins = null;
    }

    /** Switches fingerprint protection on or off. The vault must be open. */
    public void setRequiresAuth(boolean auth) throws GeneralSecurityException {
        wrapKey(dataKey, auth);
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
                arr.put(new JSONObject().put("id", l.id).put("title", l.title).put("user", l.user)
                        .put("password", l.password).put("notes", l.notes).put("modified", l.modified));
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
