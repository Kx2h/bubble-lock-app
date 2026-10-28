package org.privacybubble.app.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.privacybubble.app.BuildConfig;

import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** One in-memory browser transaction. No caller is configured until the team provisions an IdP. */
public final class OidcBrowserFlow implements AutoCloseable {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final int MAX_BODY = 65536;
    private final Activity activity;
    private final OidcProvider provider;
    private final String clientId;
    private final Callback callback;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AuthorizationService service;
    private AuthorizationRequest request;
    private boolean started;
    private boolean consumed;
    private volatile boolean closed;
    private int requestCode;

    public interface Callback {
        /** An in-memory session only; do not log, serialize or put it into an Intent. */
        void onAuthenticated(Session session);
        /** Covers cancellation and validation or connection failures without token details. */
        void onFailure();
    }
    public static final class Session implements AutoCloseable {
        private String subject;
        private String accessToken;
        private final long expiresAtElapsed;
        private Session(String subject, String accessToken, long expiresAtElapsed) { this.subject = subject; this.accessToken = accessToken; this.expiresAtElapsed = expiresAtElapsed; }
        public synchronized String subject() {
            if (accessToken == null || SystemClock.elapsedRealtime() >= expiresAtElapsed) throw new IllegalStateException("Session expired");
            return subject;
        }
        public synchronized String accessToken() {
            if (accessToken == null || SystemClock.elapsedRealtime() >= expiresAtElapsed) throw new IllegalStateException("Session expired");
            return accessToken;
        }
        @Override public synchronized void close() { subject = null; accessToken = null; }
        @Override public String toString() { return "OidcSession[redacted]"; }
    }

    /** provider MUST originate from pre-provisioned trusted issuer discovery, never a redirect. */
    public OidcBrowserFlow(Activity activity, OidcProvider provider, String clientId, Callback callback) {
        if (activity == null || provider == null || callback == null || clientId == null || clientId.isEmpty() || clientId.length() > 512 || !clientId.equals(clientId.trim())) throw new IllegalArgumentException("Invalid configuration");
        this.activity = activity; this.provider = provider; this.clientId = clientId; this.callback = callback;
    }

    /** Called on the main thread. The caller must forward onActivityResult and call close on destroy. */
    @SuppressWarnings("deprecation")
    public void start(int resultCode) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("OIDC test flow unavailable in release");
        if (closed || started || !ACTIVE.compareAndSet(false, true)) throw new IllegalStateException("Authorization already active");
        started = true; requestCode = resultCode;
        try {
            AuthorizationServiceConfiguration config = new AuthorizationServiceConfiguration(
                    Uri.parse(provider.authorizationEndpoint.toString()), Uri.parse(provider.tokenEndpoint.toString()));
            request = new AuthorizationRequest.Builder(config, clientId, ResponseTypeValues.CODE, Uri.parse(OidcRedirect.REDIRECT))
                    .setScope("openid").build();
            if (request.state == null || request.nonce == null || request.codeVerifier == null
                    || !"S256".equals(request.codeVerifierChallengeMethod)) throw new IllegalStateException("PKCE unavailable");
            service = new AuthorizationService(activity);
            activity.startActivityForResult(service.getAuthorizationRequestIntent(request), resultCode);
        } catch (RuntimeException e) { fail(); }
    }

    /** Returns false for unrelated results. Process recreation requires a new transaction. */
    public boolean onActivityResult(int resultCode, int result, Intent data) {
        if (!started || resultCode != requestCode || closed) return false;
        if (consumed) return true;
        consumed = true;
        final String code;
        try {
            if (result != Activity.RESULT_OK || data == null || data.getData() == null) throw new IllegalArgumentException();
            code = OidcRedirect.code(data.getData().toString(), request.state, provider.issuer);
        } catch (RuntimeException e) { fail(); return true; }
        worker.execute(() -> {
            try {
                long exchangeStarted = SystemClock.elapsedRealtime();
                JSONObject token = exchange(code);
                Object accessValue = token.opt("access_token");
                Object idValue = token.opt("id_token");
                Object typeValue = token.opt("token_type");
                Object lifetime = token.opt("expires_in");
                if (!(accessValue instanceof String) || !(idValue instanceof String) || !(typeValue instanceof String)
                        || !(lifetime instanceof Number)) throw new IllegalArgumentException();
                String access = (String) accessValue;
                String idToken = (String) idValue;
                double seconds = ((Number) lifetime).doubleValue();
                if (access.isEmpty() || access.length() > 16384 || idToken.isEmpty() || idToken.length() > 16384
                        || !"bearer".equalsIgnoreCase((String) typeValue) || seconds < 1 || seconds > 604800 || seconds != Math.rint(seconds)) throw new IllegalArgumentException();
                for (int i = 0; i < access.length(); i++) if (access.charAt(i) < 33 || access.charAt(i) > 126) throw new IllegalArgumentException();
                Object scope = token.opt("scope");
                if (scope != null && (!(scope instanceof String) || !java.util.Arrays.asList(((String) scope).split(" +")).contains("openid"))) throw new IllegalArgumentException();
                // Conservatively begin the lifetime before JWKS fetching, which may take time.
                long expiresAtElapsed = exchangeStarted + (long) seconds * 1000;
                String subject = provider.verifyIdToken(idToken, clientId, request.nonce, System.currentTimeMillis() / 1000);
                if (SystemClock.elapsedRealtime() >= expiresAtElapsed) throw new IllegalArgumentException();
                main.post(() -> { if (!closed) { close(); callback.onAuthenticated(new Session(subject, access, expiresAtElapsed)); } });
            } catch (Exception e) { main.post(() -> { if (!closed) fail(); }); }
        });
        return true;
    }

    private JSONObject exchange(String code) throws Exception {
        String form = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(OidcRedirect.REDIRECT) + "&code_verifier=" + enc(request.codeVerifier);
        byte[] body = form.getBytes(StandardCharsets.UTF_8);
        final long deadline = System.nanoTime() + 10_000_000_000L;
        HttpsURLConnection conn = (HttpsURLConnection) provider.tokenEndpoint.toURL().openConnection();
        conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
        conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json"); conn.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            if (conn.getResponseCode() != 200 || System.nanoTime() - deadline >= 0) throw new IllegalArgumentException("Token exchange failed");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (System.nanoTime() - deadline >= 0 || out.size() + n > MAX_BODY) throw new IllegalArgumentException("Invalid token response");
                    out.write(buffer, 0, n);
                }
                if (System.nanoTime() - deadline >= 0) throw new IllegalArgumentException("Token exchange timed out");
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { conn.disconnect(); }
    }
    private static String enc(String input) throws Exception { return URLEncoder.encode(input, "UTF-8"); }
    private void fail() { if (!closed) { close(); callback.onFailure(); } }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (started) ACTIVE.set(false);
        worker.shutdownNow();
        if (service != null) service.dispose();
        request = null;
    }
}
