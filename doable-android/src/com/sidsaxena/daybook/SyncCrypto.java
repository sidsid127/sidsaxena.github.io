package com.sidsaxena.daybook;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of the sync document and attachment files (format in doable/SYNC.md), byte-compatible with the
 * web app. PBKDF2-HMAC-SHA256 is computed here because Android before 8.0 only offers the SHA-1 variant.
 * Pure Java.
 */
final class SyncCrypto {
    private SyncCrypto() {}

    static final Charset UTF8 = Charset.forName("UTF-8");
    static final byte[] DOC_MAGIC = "DOABLESYNC1".getBytes(UTF8);
    static final byte[] FILE_MAGIC = "DOABLEFILE1".getBytes(UTF8);
    static final int ROUNDS = 210000;

    static final class Header {
        final byte[] salt;
        final int iterations;

        Header(byte[] salt, int iterations) {
            this.salt = salt;
            this.iterations = iterations;
        }
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** PBKDF2-HMAC-SHA256, 32-byte output. */
    static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        byte[] pw = new String(password).getBytes(UTF8);
        if (pw.length == 0) throw new GeneralSecurityException("empty passphrase");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pw, "HmacSHA256"));
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);
        block[salt.length + 3] = 1; // block index 1 (one 32-byte block is all AES-256 needs)
        byte[] u = mac.doFinal(block);
        byte[] out = u.clone();
        for (int i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int k = 0; k < out.length; k++) out[k] ^= u[k];
        }
        return out;
    }

    /** Header of a sync document, or null if the bytes aren't one. */
    static Header header(byte[] doc) {
        if (doc.length < 11 + 16 + 4 + 12 + 16 || !Arrays.equals(Arrays.copyOf(doc, 11), DOC_MAGIC)) return null;
        int it = ((doc[27] & 255) << 24) | ((doc[28] & 255) << 16) | ((doc[29] & 255) << 8) | (doc[30] & 255);
        return new Header(Arrays.copyOfRange(doc, 11, 27), it);
    }

    static byte[] encryptDoc(byte[] key, byte[] salt, int iterations, String json) throws GeneralSecurityException {
        byte[] iv = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(DOC_MAGIC);
        byte[] ct = c.doFinal(json.getBytes(UTF8));
        ByteArrayOutputStream out = new ByteArrayOutputStream(43 + ct.length);
        out.write(DOC_MAGIC, 0, 11);
        out.write(salt, 0, 16);
        out.write(iterations >>> 24);
        out.write(iterations >>> 16);
        out.write(iterations >>> 8);
        out.write(iterations);
        out.write(iv, 0, 12);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    /** Throws (AEADBadTagException) on a wrong key. */
    static String decryptDoc(byte[] key, byte[] doc) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, doc, 31, 12));
        c.updateAAD(DOC_MAGIC);
        return new String(c.doFinal(doc, 43, doc.length - 43), UTF8);
    }

    static byte[] encryptFile(byte[] key, String name, byte[] data) throws GeneralSecurityException {
        byte[] iv = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(FILE_MAGIC);
        c.updateAAD(name.getBytes(UTF8));
        byte[] ct = c.doFinal(data);
        byte[] out = new byte[23 + ct.length];
        System.arraycopy(FILE_MAGIC, 0, out, 0, 11);
        System.arraycopy(iv, 0, out, 11, 12);
        System.arraycopy(ct, 0, out, 23, ct.length);
        return out;
    }

    static byte[] decryptFile(byte[] key, String name, byte[] data) throws GeneralSecurityException {
        if (data.length < 23 + 16 || !Arrays.equals(Arrays.copyOf(data, 11), FILE_MAGIC)) throw new GeneralSecurityException("not a Doable file");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, data, 11, 12));
        c.updateAAD(FILE_MAGIC);
        c.updateAAD(name.getBytes(UTF8));
        return c.doFinal(data, 23, data.length - 23);
    }

    /** Small local secrets (locked notes, password copy) sealed with the sync key: iv | ciphertext. */
    static byte[] seal(byte[] key, byte[] plain) throws GeneralSecurityException {
        byte[] iv = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    static byte[] open(byte[] key, byte[] sealed) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }
}
