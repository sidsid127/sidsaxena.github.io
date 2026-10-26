package com.sidsaxena.daybook;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;

/**
 * Offers Doable's saved logins in other apps and browsers. The vault stays locked until you pick
 * "Unlock Doable" and confirm with your fingerprint (see {@link AutofillAuth}).
 */
@TargetApi(26)
public class AutofillSvc extends AutofillService {

    /** The username and password fields found on a screen, plus where they are. */
    static final class Fields {
        AutofillId user, pass;
        String domain = "", pkg = "";

        AutofillId[] ids() {
            if (user != null && pass != null) return new AutofillId[]{user, pass};
            return new AutofillId[]{user != null ? user : pass};
        }
    }

    static Fields find(AssistStructure s) {
        Fields f = new Fields();
        f.pkg = s.getActivityComponent().getPackageName();
        for (int i = 0; i < s.getWindowNodeCount(); i++) walk(s.getWindowNodeAt(i).getRootViewNode(), f);
        return f;
    }

    private static void walk(AssistStructure.ViewNode n, Fields f) {
        if (n.getWebDomain() != null && f.domain.isEmpty()) f.domain = n.getWebDomain();
        if (n.getAutofillType() == View.AUTOFILL_TYPE_TEXT && n.getAutofillId() != null) {
            String kind = kind(n);
            if ("pass".equals(kind) && f.pass == null) f.pass = n.getAutofillId();
            else if ("user".equals(kind) && f.user == null) f.user = n.getAutofillId();
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i), f);
    }

    private static String kind(AssistStructure.ViewNode n) {
        String[] hints = n.getAutofillHints();
        if (hints != null) {
            for (String h : hints) {
                String x = h.toLowerCase(Locale.ROOT);
                if (x.contains("password")) return "pass";
                if (x.contains("username") || x.contains("email") || x.contains("phone")) return "user";
            }
        }
        int type = n.getInputType();
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return "pass";
        if ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return "pass";
        StringBuilder words = new StringBuilder();
        if (n.getIdEntry() != null) words.append(n.getIdEntry()).append(' ');
        if (n.getHint() != null) words.append(n.getHint()).append(' ');
        if (n.getHtmlInfo() != null && n.getHtmlInfo().getAttributes() != null) {
            for (android.util.Pair<String, String> p : n.getHtmlInfo().getAttributes()) words.append(p.second).append(' ');
        }
        String w = words.toString().toLowerCase(Locale.ROOT);
        if (w.contains("pass") || w.contains("kennwort") || w.contains("passwort")) return "pass";
        if (w.contains("user") || w.contains("email") || w.contains("e-mail") || w.contains("login") || w.contains("benutzer")
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
            return "user";
        }
        return null;
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal signal, FillCallback callback) {
        List<FillContext> contexts = request.getFillContexts();
        AssistStructure s = contexts.get(contexts.size() - 1).getStructure();
        Fields f = find(s);
        if ((f.user == null && f.pass == null) || f.pkg.equals(getPackageName()) || !new Vault(this).exists()) {
            callback.onSuccess(null);
            return;
        }
        RemoteViews unlock = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
        unlock.setTextViewText(android.R.id.text1, "🔒  Unlock Doable passwords");
        Intent i = new Intent(this, AutofillAuth.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1001, i,
                PendingIntent.FLAG_CANCEL_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        callback.onSuccess(new FillResponse.Builder().setAuthentication(f.ids(), pi.getIntentSender(), unlock).build());
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onSuccess(); // saving new logins from other apps is not offered
    }
}
