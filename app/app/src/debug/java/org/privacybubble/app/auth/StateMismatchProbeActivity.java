package org.privacybubble.app.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationManagementActivity;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;

/** Synthetic device regression: actual AppAuth response parser, no browser or network. */
public final class StateMismatchProbeActivity extends Activity {
    private static final String EXPECTED = "SYNTH_REQUEST_STATE_0F45D9ABC1";
    private static final String WRONG = "SYNTH_RESPONSE_STATE_514AF01B3";
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView result = new TextView(this);
        result.setTextSize(18); result.setPadding(24, 24, 24, 24);
        setContentView(result);
        try {
            Uri redirect = Uri.parse("org.privacybubble.app:/oauth2redirect");
            AuthorizationServiceConfiguration config = new AuthorizationServiceConfiguration(Uri.parse("https://issuer.example.test/authorize"), Uri.parse("https://issuer.example.test/token"));
            AuthorizationRequest request = new AuthorizationRequest.Builder(config, "synthetic-client", ResponseTypeValues.CODE, redirect).setState(EXPECTED).build();
            AuthorizationManagementActivity target = new AuthorizationManagementActivity();
            Field field = AuthorizationManagementActivity.class.getDeclaredField("mAuthRequest");
            field.setAccessible(true); field.set(target, request);
            Method extract = AuthorizationManagementActivity.class.getDeclaredMethod("extractResponseData", Uri.class);
            extract.setAccessible(true);
            Uri bad = redirect.buildUpon().appendQueryParameter("code", "synthetic-code").appendQueryParameter("state", WRONG).build();
            Intent rejected = (Intent) extract.invoke(target, bad);
            if (!AuthorizationException.AuthorizationRequestErrors.STATE_MISMATCH.equals(AuthorizationException.fromIntent(rejected))) throw new IllegalStateException("mismatch accepted");
            Uri good = redirect.buildUpon().appendQueryParameter("code", "synthetic-code").appendQueryParameter("state", EXPECTED).build();
            Intent accepted = (Intent) extract.invoke(target, good);
            AuthorizationResponse response = AuthorizationResponse.fromIntent(accepted);
            if (response == null || !"synthetic-code".equals(response.authorizationCode)) throw new IllegalStateException("valid response rejected");
            Uri cancelled = redirect.buildUpon().appendQueryParameter("error", "access_denied").build();
            Intent denied = (Intent) extract.invoke(target, cancelled);
            AuthorizationException exception = AuthorizationException.fromIntent(denied);
            if (exception == null || !"access_denied".equals(exception.error)) throw new IllegalStateException("cancellation accepted");
            result.setText("PASS: synthetic state mismatch rejected, valid response accepted, cancellation rejected. No browser or network.");
        } catch (Exception error) {
            result.setText("FAIL: AppAuth synthetic state regression");
        }
    }
}
