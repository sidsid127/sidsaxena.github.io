package com.sidsaxena.daybook;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps small secrets (backup and sync passwords, the sync key, Google tokens) wrapped by an Android
 * Keystore key that needs no fingerprint, so background work can use them. The Keystore key never leaves
 * the phone's secure hardware.
 */
final class Secrets {
    private Secrets() {}

    private static SecretKey key(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    static String wrap(String alias, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(alias));
        byte[] iv = c.getIV(), ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    static byte[] unwrap(String alias, String b64) throws Exception {
        byte[] data = Base64.decode(b64, Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(alias), new GCMParameterSpec(128, data, 0, 12));
        return c.doFinal(data, 12, data.length - 12);
    }

    static String wrap(String alias, String plain) throws Exception {
        return wrap(alias, plain.getBytes("UTF-8"));
    }

    static String unwrapString(String alias, String b64) throws Exception {
        return new String(unwrap(alias, b64), "UTF-8");
    }
}
