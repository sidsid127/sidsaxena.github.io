package com.sidsaxena.daybook;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Google sign-in and the few Drive calls sync needs, with plain HttpURLConnection (no Google libraries).
 * Sign-in follows Google's flow for installed apps: the browser opens Google's page, Google redirects to
 * Doable's own address (com.googleusercontent.apps.…:/oauth2redirect) with a code, which is exchanged for
 * tokens with PKCE. The client has no secret, so nothing secret is built into the app.
 * Only the hidden app folder of Drive is accessible (scope drive.appdata).
 */
final class GoogleDrive {

    /** OAuth client of type "iOS" from Google Cloud (it has no secret; see doable/GOOGLE-SETUP.md). */
    static final String DEFAULT_CLIENT_ID = "";

    private static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata email";
    private static final String API = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3";
    private static final String ALIAS = "doable_sync";

    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    interface SignedIn {
        void done(String error); // null = success
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    static String clientId(Context c) {
        return prefs(c).getString("clientId", DEFAULT_CLIENT_ID);
    }


    // ---------------------------------------------------------------- sign-in

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static SignedIn pending; // callback of the sign-in in progress (null if the app was restarted meanwhile)

    /** The redirect Google sends the browser back to; Android opens Doable for it (see the manifest). */
    static String redirectUri(Context c) {
        return "com.googleusercontent.apps." + clientId(c).replace(".apps.googleusercontent.com", "") + ":/oauth2redirect";
    }

    /** Opens Google sign-in in the browser; Doable gets the answer back through {@link #handleRedirect}. */
    static void signIn(MainActivity a, SignedIn cb) {
        String verifier = b64url(SyncCrypto.random(48));
        String state = b64url(SyncCrypto.random(12));
        String challenge;
        try {
            challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")));
        } catch (Exception e) {
            cb.done("Could not start sign-in: " + e.getMessage());
            return;
        }
        pending = cb;
        prefs(a).edit().putString("pkce", verifier).putString("state", state).commit();
        String url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + enc(clientId(a)) + "&redirect_uri=" + enc(redirectUri(a))
                + "&response_type=code&scope=" + enc(SCOPE) + "&code_challenge=" + challenge + "&code_challenge_method=S256"
                + "&state=" + state + "&access_type=offline&prompt=consent";
        a.startExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    /** True if the intent was Google's answer; finishes sign-in in the background and reports on the UI thread. */
    static boolean handleRedirect(final MainActivity a, Uri data) {
        if (data == null || data.getScheme() == null || !data.getScheme().startsWith("com.googleusercontent.apps.")) return false;
        final Context app = a.getApplicationContext();
        final String code = data.getQueryParameter("code"), state = data.getQueryParameter("state"), error = data.getQueryParameter("error");
        final SignedIn cb = pending;
        pending = null;
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    SharedPreferences p = prefs(app);
                    if (code == null || !p.getString("state", "-").equals(state)) {
                        throw new IOException(error != null ? "Google sign-in: " + error : "Sign-in was not completed");
                    }
                    exchange(app, "code=" + enc(code) + "&code_verifier=" + p.getString("pkce", "") + "&redirect_uri=" + enc(redirectUri(app))
                            + "&grant_type=authorization_code");
                    p.edit().remove("pkce").remove("state").apply();
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final String result = err;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (cb != null) cb.done(result);
                        else SyncUi.signedIn(a, result);
                    }
                });
            }
        }).start();
        return true;
    }

    /** Token endpoint: stores the refresh token (wrapped) and caches the access token. */
    private static void exchange(Context c, String form) throws Exception {
        JSONObject j = new JSONObject(post("https://oauth2.googleapis.com/token", "client_id=" + enc(clientId(c)) + "&" + form));
        if (j.has("error")) throw new AuthException("Google: " + j.optString("error_description", j.optString("error")));
        SharedPreferences.Editor e = prefs(c).edit();
        if (j.has("refresh_token")) e.putString("refresh", Secrets.wrap(ALIAS, j.getString("refresh_token")));
        String idToken = j.optString("id_token", "");
        if (!idToken.isEmpty()) {
            try {
                String payload = new String(Base64.decode(idToken.split("\\.")[1], Base64.URL_SAFE), "UTF-8");
                e.putString("email", new JSONObject(payload).optString("email"));
            } catch (Exception ignored) {
            }
        }
        e.commit();
        accessToken = j.getString("access_token");
        accessExpires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
    }

    static boolean signedIn(Context c) {
        return prefs(c).contains("refresh");
    }

    static void signOut(Context c) {
        prefs(c).edit().remove("refresh").remove("email").commit();
        accessToken = null;
    }

    private static String accessToken;
    private static long accessExpires;

    static synchronized String token(Context c) throws Exception {
        if (accessToken != null && accessExpires - 60000 > System.currentTimeMillis()) return accessToken;
        String refresh = prefs(c).getString("refresh", null);
        if (refresh == null) throw new AuthException("Not signed in to Google");
        try {
            exchange(c, "refresh_token=" + enc(Secrets.unwrapString(ALIAS, refresh)) + "&grant_type=refresh_token");
        } catch (AuthException e) {
            signOut(c); // revoked or expired: sign in again
            throw new AuthException("Sign in to Google again");
        }
        return accessToken;
    }

    // ---------------------------------------------------------------- HTTP

    private static String post(String url, String form) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(20000);
        h.setReadTimeout(30000);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream o = h.getOutputStream();
        o.write(form.getBytes("UTF-8"));
        o.close();
        int code = h.getResponseCode();
        byte[] body = readAll(code >= 400 ? h.getErrorStream() : h.getInputStream());
        return new String(body, "UTF-8");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private final Context c;
    final Map<String, String> ids = new HashMap<String, String>(); // file name → Drive id

    GoogleDrive(Context c) {
        this.c = c;
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + token(c));
        return h;
    }

    private byte[] call(HttpURLConnection h) throws IOException {
        int code = h.getResponseCode();
        if (code == 401) {
            accessToken = null;
            throw new AuthException("Sign in to Google again");
        }
        byte[] body = readAll(code >= 400 ? h.getErrorStream() : h.getInputStream());
        if (code >= 400) throw new IOException("Google Drive " + code + ": " + new String(body, "UTF-8").replaceAll("\\s+", " ").trim());
        return body;
    }

    void list() throws Exception {
        ids.clear();
        String page = "";
        do {
            HttpURLConnection h = open(API + "/files?spaces=appDataFolder&pageSize=1000&fields=" + enc("nextPageToken,files(id,name)")
                    + (page.isEmpty() ? "" : "&pageToken=" + enc(page)), "GET");
            JSONObject j = new JSONObject(new String(call(h), "UTF-8"));
            JSONArray files = j.optJSONArray("files");
            for (int i = 0; files != null && i < files.length(); i++) ids.put(files.getJSONObject(i).getString("name"), files.getJSONObject(i).getString("id"));
            page = j.optString("nextPageToken", "");
        } while (!page.isEmpty());
    }

    byte[] download(String name) throws Exception {
        String id = ids.get(name);
        if (id == null) return null;
        return call(open(API + "/files/" + id + "?alt=media", "GET"));
    }

    void upload(String name, byte[] data) throws Exception {
        String id = ids.get(name);
        if (id != null) {
            HttpURLConnection h = open(UPLOAD + "/files/" + id + "?uploadType=media", "POST");
            h.setRequestProperty("X-HTTP-Method-Override", "PATCH"); // HttpURLConnection has no PATCH
            h.setRequestProperty("Content-Type", "application/octet-stream");
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(data.length);
            OutputStream o = h.getOutputStream();
            o.write(data);
            o.close();
            call(h);
            return;
        }
        String boundary = "doable" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + new JSONObject().put("name", name).put("parents", new JSONArray().put("appDataFolder")) + "\r\n--" + boundary
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + boundary + "--").getBytes("UTF-8");
        HttpURLConnection h = open(UPLOAD + "/files?uploadType=multipart&fields=id", "POST");
        h.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
        h.setDoOutput(true);
        h.setFixedLengthStreamingMode(head.length + data.length + tail.length);
        OutputStream o = h.getOutputStream();
        o.write(head);
        o.write(data);
        o.write(tail);
        o.close();
        ids.put(name, new JSONObject(new String(call(h), "UTF-8")).getString("id"));
    }

    void delete(String name) throws Exception {
        String id = ids.get(name);
        if (id == null) return;
        call(open(API + "/files/" + id, "DELETE"));
        ids.remove(name);
    }
}
