package com.sidsaxena.daybook;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption for locked notes: a random data key, itself protected by an Android Keystore key that only
 * works for 30 seconds after fingerprint / screen-lock confirmation. While open, text is encrypted and
 * decrypted with the in-memory data key.
 */
final class SecureBox {
    private static final String ALIAS = "doable_notes_auth";
    private final SharedPreferences prefs;
    private SecretKey key;

    SecureBox(Context c) {
        prefs = c.getSharedPreferences("notelock", Context.MODE_PRIVATE);
    }

    boolean isOpen() { return key != null; }

    void close() { key = null; }

    /** Opens (creating on first use). Throws UserNotAuthenticatedException until the user has confirmed. */
    void open() throws GeneralSecurityException {
        SecretKey kek = keystoreKey();
        if (!prefs.contains("key")) {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            SecretKey k = kg.generateKey();
            prefs.edit().putString("key", Base64.encodeToString(enc(kek, k.getEncoded(), true), Base64.NO_WRAP)).commit();
            key = k;
            return;
        }
        byte[] raw = dec(kek, Base64.decode(prefs.getString("key", ""), Base64.NO_WRAP));
        key = new SecretKeySpec(raw, "AES");
    }

    boolean exists() {
        return prefs.contains("key");
    }

    /** The data key (box must be open), stored in the password-encrypted backup. */
    byte[] rawKey() {
        return key.getEncoded();
    }

    /** Restore: protects the backup's key with this phone's Keystore. Needs a recent fingerprint / PIN. */
    void adoptKey(byte[] raw) throws GeneralSecurityException {
        byte[] wrapped = enc(keystoreKey(), raw, true);
        prefs.edit().putString("key", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit();
        key = null;
    }

    /** Decrypts with a raw key (restore on a phone without a screen lock). */
    static String decryptWith(byte[] raw, String b64) throws GeneralSecurityException {
        try {
            return new String(dec(new SecretKeySpec(raw, "AES"), Base64.decode(b64, Base64.NO_WRAP)), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    String encrypt(String text) throws GeneralSecurityException {
        try {
            return Base64.encodeToString(enc(key, text.getBytes("UTF-8"), false), Base64.NO_WRAP);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    String decrypt(String b64) throws GeneralSecurityException {
        try {
            return new String(dec(key, Base64.decode(b64, Base64.NO_WRAP)), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static SecretKey keystoreKey() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        try {
            ks.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(30);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private static byte[] enc(SecretKey k, byte[] plain, boolean keystore) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        if (keystore) {
            c.init(Cipher.ENCRYPT_MODE, k);
        } else {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
        }
        byte[] iv = c.getIV(), ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] dec(SecretKey k, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, data, 0, 12));
        return c.doFinal(data, 12, data.length - 12);
    }
}
