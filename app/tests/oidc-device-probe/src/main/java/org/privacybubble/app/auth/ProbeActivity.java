package org.privacybubble.app.auth;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import android.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Separate synthetic probe. Its in-process requests are not a browser login. */
public final class ProbeActivity extends Activity {
    private static final String ISSUER = "https://localhost:18443";
    private static final String CLIENT = "privacy-bubble-android-test";
    private static final String REDIRECT = "org.privacybubble.app:/oauth2redirect";
    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static String form(Map<String,String> values) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String,String> item : values.entrySet()) {
            if (result.length() != 0) result.append('&');
            result.append(enc(item.getKey())).append('=').append(enc(item.getValue()));
        }
        return result.toString();
    }
    private static HttpsURLConnection open(URI uri, String method, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) uri.toURL().openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (java.io.OutputStream output = c.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        return c;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this); view.setTextSize(18); view.setPadding(30, 30, 30, 30);
        view.setText("Synthetic OIDC TLS probe running. Not a browser login."); setContentView(view);
        new Thread(() -> {
            String result;
            try {
                OidcProvider provider = OidcProvider.discover(ISSUER);
                byte[] random = new byte[48]; new SecureRandom().nextBytes(random);
                String verifier = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                String stateValue = "probe-state-123456789", nonce = "probe-nonce-123456789";
                Map<String,String> auth = new LinkedHashMap<>();
                auth.put("response_type", "code"); auth.put("client_id", CLIENT); auth.put("redirect_uri", REDIRECT); auth.put("scope", "openid");
                auth.put("state", stateValue); auth.put("nonce", nonce); auth.put("code_challenge", challenge); auth.put("code_challenge_method", "S256");
                HttpsURLConnection consent = open(provider.authorizationEndpoint, "POST", form(auth));
                if (consent.getResponseCode() != 303) throw new IllegalStateException("mock authorization");
                String callback = consent.getHeaderField("Location"); consent.disconnect();
                String code = OidcRedirect.code(callback, stateValue, ISSUER);
                Map<String,String> token = new LinkedHashMap<>();
                token.put("grant_type", "authorization_code"); token.put("client_id", CLIENT); token.put("redirect_uri", REDIRECT);
                token.put("code", code); token.put("code_verifier", verifier);
                HttpsURLConnection response = open(provider.tokenEndpoint, "POST", form(token));
                if (response.getResponseCode() != 200) throw new IllegalStateException("mock token endpoint");
                byte[] bytes = new byte[65537]; int total = 0;
                try (InputStream input = response.getInputStream()) {
                    int count; while (total < bytes.length && (count = input.read(bytes, total, bytes.length - total)) != -1) total += count;
                } finally { response.disconnect(); }
                if (total > 65536) throw new IllegalStateException("oversize");
                String json = new String(bytes, 0, total, StandardCharsets.UTF_8);
                String idToken = JSONObjectUtils.getString(JSONObjectUtils.parse(json), "id_token");
                if (!"synthetic-test-user".equals(provider.verifyIdToken(idToken, CLIENT, nonce))) throw new IllegalStateException("subject");
                try { provider.verifyIdToken(idToken, CLIENT, "wrong-probe-nonce"); throw new IllegalStateException("wrong nonce accepted"); }
                catch (IllegalArgumentException expected) { /* expected */ }
                result = "PASS: Android TLS, mock PKCE exchange, ID token and wrong nonce rejection. Synthetic account. No browser login.";
            } catch (Exception failure) { result = "FAIL: local TLS/OIDC probe (" + failure.getClass().getSimpleName() + ")"; }
            final String message = result;
            runOnUiThread(() -> view.setText(message));
        }).start();
    }
}
