package com.sidsaxena.daybook;

import android.util.Base64;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption for households and sharing — the same format as the web app's e2e.js (doable/SHARING.md):
 * ECDH P-256 key pairs (public key = 65-byte raw point, base64), space keys wrapped with an ephemeral ECDH + HKDF-SHA256
 * + AES-256-GCM, invite-link secrets, and rows sealed with AES-GCM bound to "space/table/uid".
 */
final class E2e {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static ECParameterSpec curve;

    private E2e() {}

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }

    static byte[] unb64(String s) { return Base64.decode(s, Base64.DEFAULT); }

    static String b64url(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }

    static byte[] unb64url(String s) { return Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }

    static String sha256hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** Short fingerprint of a public key (tells whether a wrap was made for the key a member has now). */
    static String keyId(String pub) throws Exception { return sha256hex("doable-key:" + pub).substring(0, 16); }

    // ---------------------------------------------------------------- key pairs

    /** {public key (base64 raw), private key (base64 PKCS#8)}. */
    static String[] generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair kp = g.generateKeyPair();
        return new String[]{b64(raw((ECPublicKey) kp.getPublic())), b64(kp.getPrivate().getEncoded())};
    }

    private static synchronized ECParameterSpec curve() throws Exception {
        if (curve == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            curve = ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
        }
        return curve;
    }

    static byte[] raw(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 4;
        put32(k.getW().getAffineX(), out, 1);
        put32(k.getW().getAffineY(), out, 33);
        return out;
    }

    private static void put32(BigInteger v, byte[] out, int at) {
        byte[] b = v.toByteArray();
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, out, at + 32 - n, n);
    }

    static PublicKey publicKey(byte[] raw) throws Exception {
        if (raw.length != 65 || raw[0] != 4) throw new IllegalArgumentException("not a P-256 key");
        ECPoint p = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(p, curve()));
    }

    static PrivateKey privateKey(String pkcs8) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(unb64(pkcs8)));
    }

    // ---------------------------------------------------------------- primitives

    static byte[] hkdf(byte[] ikm, byte[] salt, String info) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info.getBytes("UTF-8"));
        mac.update((byte) 1);
        return mac.doFinal(); // 32 bytes = one block
    }

    static byte[] gcm(boolean encrypt, byte[] key, byte[] iv, byte[] data, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(data);
    }

    private static byte[] ecdh(PrivateKey mine, PublicKey theirs) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        return ka.generateSecret();
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    // ---------------------------------------------------------------- space keys

    /** The space key, so that only the owner of `pub` can open it. */
    static String wrapFor(String pub, byte[] spaceKey) throws Exception {
        byte[] their = unb64(pub);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair eph = g.generateKeyPair();
        byte[] ephPub = raw((ECPublicKey) eph.getPublic());
        byte[] key = hkdf(ecdh(eph.getPrivate(), publicKey(their)), ephPub, "doable-wrap-v1");
        byte[] iv = random(12);
        return b64(cat(ephPub, iv, gcm(true, key, iv, spaceKey, their)));
    }

    static byte[] unwrap(String myPub, String myPriv, String blob) throws Exception {
        byte[] b = unb64(blob);
        byte[] ephPub = Arrays.copyOfRange(b, 0, 65);
        byte[] key = hkdf(ecdh(privateKey(myPriv), publicKey(ephPub)), ephPub, "doable-wrap-v1");
        return gcm(false, key, Arrays.copyOfRange(b, 65, 77), Arrays.copyOfRange(b, 77, b.length), unb64(myPub));
    }

    /** {token for the link, hash for the server, the space key locked with the token}. */
    static String[] linkLock(byte[] spaceKey) throws Exception {
        byte[] secret = random(16);
        String token = b64url(secret);
        byte[] key = hkdf(secret, new byte[32], "doable-invite-v1");
        byte[] iv = random(12);
        return new String[]{token, linkHash(token), b64(cat(iv, gcm(true, key, iv, spaceKey, "doable-invite".getBytes("UTF-8"))))};
    }

    static String linkHash(String token) throws Exception { return sha256hex("doable-invite:" + token); }

    static byte[] linkUnlock(String token, String encKey) throws Exception {
        byte[] key = hkdf(unb64url(token), new byte[32], "doable-invite-v1");
        byte[] b = unb64(encKey);
        return gcm(false, key, Arrays.copyOfRange(b, 0, 12), Arrays.copyOfRange(b, 12, b.length), "doable-invite".getBytes("UTF-8"));
    }

    // ---------------------------------------------------------------- rows

    private static byte[] aad(String space, String table, String uid) throws Exception {
        return (space + "/" + table + "/" + uid).getBytes("UTF-8");
    }

    static String sealRow(byte[] key, String space, String table, String uid, String json) throws Exception {
        byte[] iv = random(12);
        return b64(cat(iv, gcm(true, key, iv, json.getBytes("UTF-8"), aad(space, table, uid))));
    }

    static String openRow(byte[] key, String space, String table, String uid, String data) throws Exception {
        byte[] b = unb64(data);
        return new String(gcm(false, key, Arrays.copyOfRange(b, 0, 12), Arrays.copyOfRange(b, 12, b.length), aad(space, table, uid)), "UTF-8");
    }
}
