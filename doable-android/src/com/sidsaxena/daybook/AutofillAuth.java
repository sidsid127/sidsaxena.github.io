package com.sidsaxena.daybook;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.security.keystore.UserNotAuthenticatedException;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Unlocks the vault for autofill (fingerprint / screen lock) and hands the matching logins to Android. */
@TargetApi(26)
public class AutofillAuth extends Activity {

    private AutofillSvc.Fields fields;
    private Vault vault;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AssistStructure s = getIntent().getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE);
        if (s == null) {
            finish();
            return;
        }
        fields = AutofillSvc.find(s);
        vault = new Vault(this);
        tryOpen(true);
    }

    private void tryOpen(boolean askIfNeeded) {
        try {
            vault.open();
            respond();
        } catch (UserNotAuthenticatedException e) {
            if (askIfNeeded) authenticate();
            else fail("Use your fingerprint or phone PIN to unlock");
        } catch (Exception e) {
            fail("Could not open your passwords");
        }
    }

    private void authenticate() {
        if (Build.VERSION.SDK_INT >= 28) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this).setTitle("Unlock Doable passwords")
                    .setSubtitle("Use your fingerprint, face or phone screen lock");
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            b.build().authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { tryOpen(false); }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) { finish(); }
            });
        } else {
            Intent i = ((KeyguardManager) getSystemService(KEYGUARD_SERVICE))
                    .createConfirmDeviceCredentialIntent("Unlock Doable passwords", null);
            if (i == null) tryOpen(false);
            else startActivityForResult(i, 1);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) tryOpen(false);
        else finish();
    }

    /** Logins for this app / website first; if none match, all logins (so you can still pick one). */
    private void respond() {
        String site = fields.domain.isEmpty() ? fields.pkg : fields.domain;
        List<Vault.Login> matches = new ArrayList<Vault.Login>(), others = new ArrayList<Vault.Login>();
        for (Vault.Login l : vault.logins()) {
            if (!l.type.equals("Login")) continue;
            if (matches(l, fields.domain, fields.pkg)) matches.add(l);
            else others.add(l);
        }
        List<Vault.Login> offer = matches.isEmpty() ? others : matches;
        FillResponse.Builder rb = new FillResponse.Builder();
        int n = 0;
        for (Vault.Login l : offer) {
            if (n++ >= 25) break;
            RemoteViews p = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
            p.setTextViewText(android.R.id.text1, "🔑 " + l.title + (l.user.isEmpty() ? "" : "  ·  " + l.user));
            Dataset.Builder db = new Dataset.Builder(p);
            if (fields.user != null) db.setValue(fields.user, AutofillValue.forText(l.user));
            if (fields.pass != null) db.setValue(fields.pass, AutofillValue.forText(l.password));
            rb.addDataset(db.build());
        }
        vault.close();
        if (n == 0) {
            fail("No saved logins yet");
            return;
        }
        Intent result = new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, rb.build());
        setResult(RESULT_OK, result);
        if (matches.isEmpty()) Toast.makeText(this, "No login saved for " + site + " — showing all", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** facebook.com matches www.facebook.com, m.facebook.com and the app com.facebook.katana. */
    static boolean matches(Vault.Login l, String domain, String pkg) {
        String host = Vault.host(l.url.isEmpty() ? l.title : l.url);
        if (host.isEmpty()) return false;
        String d = domain.toLowerCase(Locale.ROOT);
        if (!d.isEmpty() && (d.equals(host) || d.endsWith("." + host))) return true;
        String[] parts = host.split("\\.");
        String core = parts.length >= 2 ? parts[parts.length - 2] : parts[0];
        if (core.length() < 3) return false;
        return (!d.isEmpty() && d.contains(core)) || pkg.toLowerCase(Locale.ROOT).contains(core);
    }

    private void fail(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finish();
    }
}
