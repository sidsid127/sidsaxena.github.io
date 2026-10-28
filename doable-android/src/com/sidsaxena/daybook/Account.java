package com.sidsaxena.daybook;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Doable account (same server as the web app): signing in with a Google access token, the profile, the ads / Pro
 * switches, signing out and deleting the account. The session is kept wrapped by the Android Keystore. Network calls
 * block: call them off the UI thread.
 */
final class Account {
    static final String SERVER = "https://doable-sid.vercel.app/";
    static final String CONTACT = "doable.by.sid@gmail.com";
    private static final String ALIAS = "doable_account";

    static final class SignedOutException extends IOException {
        SignedOutException() { super("Signed out"); }
    }

    private Account() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("account", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return prefs(c).contains("session");
    }

    static JSONObject user(Context c) {
        try {
            return new JSONObject(prefs(c).getString("user", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject config(Context c) {
        try {
            return new JSONObject(prefs(c).getString("config", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean isAdmin(Context c) { return user(c).optBoolean("admin"); }

    static boolean isPro(Context c) { return "pro".equals(user(c).optString("plan")); }

    /** Ads only when the owner switched them on and this person has no Pro. */
    static boolean showAds(Context c) { return signedIn(c) && config(c).optBoolean("ads_enabled") && !isPro(c); }

    static String displayName(Context c) {
        JSONObject u = user(c);
        String n = u.optString("name");
        return n.isEmpty() ? u.optString("email").split("@")[0] : n;
    }

    /** Signs in with a Google access token issued to Doable; returns true for a new account. */
    static boolean signIn(Context c, String googleToken) throws Exception {
        JSONObject body = new JSONObject().put("access_token", googleToken).put("platform", "android")
                .put("device", Build.MANUFACTURER + " " + Build.MODEL);
        JSONObject j = call(c, "signin", body, null);
        prefs(c).edit().putString("session", Secrets.wrap(ALIAS, j.getString("session")))
                .putString("user", j.getJSONObject("user").toString())
                .putString("config", j.getJSONObject("config").toString())
                .putLong("checked", System.currentTimeMillis()).commit();
        return j.optBoolean("new_account");
    }

    /** Latest profile and switches. Throws SignedOutException when the session ended (signed out or deleted elsewhere). */
    static void refresh(Context c) throws Exception {
        JSONObject j = call(c, "me", new JSONObject(), session(c));
        prefs(c).edit().putString("user", j.getJSONObject("user").toString()).putString("config", j.getJSONObject("config").toString())
                .putLong("checked", System.currentTimeMillis()).commit();
    }

    static void rename(Context c, String name) throws Exception {
        JSONObject j = call(c, "profile", new JSONObject().put("name", name), session(c));
        prefs(c).edit().putString("user", j.getJSONObject("user").toString()).commit();
    }

    static void choose(Context c, String choice) throws Exception {
        JSONObject j = call(c, "choose", new JSONObject().put("choice", choice), session(c));
        prefs(c).edit().putString("user", j.getJSONObject("user").toString()).commit();
    }

    static void signOut(Context c) throws Exception {
        call(c, "signout", new JSONObject(), session(c));
    }

    static void delete(Context c) throws Exception {
        call(c, "delete", new JSONObject(), session(c));
    }

    /** Forgets the session on this device only. */
    static void forget(Context c) {
        prefs(c).edit().clear().commit();
    }

    private static String session(Context c) throws Exception {
        String s = prefs(c).getString("session", null);
        if (s == null) throw new SignedOutException();
        return Secrets.unwrapString(ALIAS, s);
    }

    static JSONObject call(Context c, String action, JSONObject body, String session) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(SERVER + "api/account").openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("Content-Type", "application/json");
        if (session != null) h.setRequestProperty("Authorization", "Bearer " + session);
        OutputStream o = h.getOutputStream();
        o.write(body.put("action", action).toString().getBytes("UTF-8"));
        o.close();
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        JSONObject j;
        try {
            j = new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            j = new JSONObject();
        }
        if (code == 401 && "signed_out".equals(j.optString("error"))) throw new SignedOutException();
        if (code >= 400) throw new IOException(j.optString("error", "Doable server " + code));
        return j;
    }
}
