package org.privacybubble.app.auth;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.privacybubble.app.BuildConfig;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.browser.BrowserDescriptor;
import net.openid.appauth.browser.BrowserMatcher;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    private final BrowserMatcher browserMatcher;
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
        private RotatingSession rotating;
        private Session(String subject, String accessToken, long expiresAtElapsed, OidcProvider provider, String clientId, String refreshToken) {
            this.expiresAtElapsed = expiresAtElapsed;
            if (refreshToken == null) { this.subject = subject; this.accessToken = accessToken; }
            else { this.rotating = new RotatingSession(subject, accessToken, refreshToken, expiresAtElapsed,
                    SystemClock::elapsedRealtime, previous -> rotate(provider, clientId, previous)); }
        }
        public synchronized String subject() {
            if (rotating != null) return rotating.subject();
            if (accessToken == null || SystemClock.elapsedRealtime() >= expiresAtElapsed) throw new IllegalStateException("Session expired");
            return subject;
        }
        public synchronized String accessToken() {
            if (rotating != null) return rotating.accessToken();
            if (accessToken == null || SystemClock.elapsedRealtime() >= expiresAtElapsed) throw new IllegalStateException("Session expired");
            return accessToken;
        }
        /** Blocking network operation; synthetic test flow only. Never call on the main thread. */
        public void refresh() throws IOException {
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Refresh on UI thread");
            final RotatingSession current;
            synchronized (this) { current = rotating; }
            if (current == null) throw new IllegalStateException("Refresh unavailable");
            current.refresh();
        }
        @Override public synchronized void close() { if (rotating != null) rotating.close(); rotating = null; subject = null; accessToken = null; }
        @Override public String toString() { return "OidcSession[redacted]"; }
    }

    /** provider MUST originate from pre-provisioned trusted issuer discovery, never a redirect. */
    public OidcBrowserFlow(Activity activity, OidcProvider provider, String clientId, Callback callback) {
        this(activity, provider, clientId, callback, null);
    }

    private OidcBrowserFlow(Activity activity, OidcProvider provider, String clientId, Callback callback, BrowserMatcher browserMatcher) {
        if (activity == null || provider == null || callback == null || clientId == null || clientId.isEmpty() || clientId.length() > 512 || !clientId.equals(clientId.trim())) throw new IllegalArgumentException("Invalid configuration");
        this.activity = activity; this.provider = provider; this.clientId = clientId; this.callback = callback;
        this.browserMatcher = browserMatcher;
    }

    /** Debug opt-in synthetic provider only; never select a surrogate for real credentials. */
    @SuppressWarnings("deprecation")
    public static OidcBrowserFlow forSyntheticTest(Activity activity, OidcProvider provider, String clientId, Callback callback) {
        if (!BuildConfig.DEBUG || !BuildConfig.OIDC_TEST_ENABLED || activity == null || provider == null
                || !"https://localhost:18443".equals(provider.issuer)
                || !"privacy-bubble-android-test".equals(clientId)) throw new IllegalStateException("Synthetic test unavailable");
        try {
            Signature[] signatures = activity.getPackageManager().getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            if (signatures == null || signatures.length != 1) throw new IllegalStateException("Unexpected app signature");
            java.util.Set<String> expected = BrowserDescriptor.generateSignatureHashes(signatures);
            BrowserMatcher matcher = descriptor -> "org.privacybubble.oidcsurrogate".equals(descriptor.packageName)
                    && expected.equals(descriptor.signatureHashes) && !descriptor.useCustomTab;
            return new OidcBrowserFlow(activity, provider, clientId, callback, matcher);
        } catch (PackageManager.NameNotFoundException e) { throw new IllegalStateException("App signature unavailable"); }
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
            AuthorizationRequest.Builder builder = new AuthorizationRequest.Builder(config, clientId, ResponseTypeValues.CODE, Uri.parse(OidcRedirect.REDIRECT));
            if (browserMatcher != null) builder.setScope("openid offline_access").setPrompt("consent");
            else builder.setScope("openid");
            request = builder.build();
            if (request.state == null || request.nonce == null || request.codeVerifier == null
                    || !"S256".equals(request.codeVerifierChallengeMethod)) throw new IllegalStateException("PKCE unavailable");
            service = browserMatcher == null ? new AuthorizationService(activity)
                    : new AuthorizationService(activity, new AppAuthConfiguration.Builder().setBrowserMatcher(browserMatcher).build());
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
                final String refreshToken;
                if (browserMatcher != null) {
                    Object refreshValue = token.opt("refresh_token");
                    if (!(refreshValue instanceof String) || (scope != null && !RotatingSession.syntheticScope((String) scope))) throw new IllegalArgumentException();
                    refreshToken = (String) refreshValue;
                } else refreshToken = null;
                // Conservatively begin the lifetime before JWKS fetching, which may take time.
                long expiresAtElapsed = exchangeStarted + (long) seconds * 1000;
                String subject = provider.verifyIdToken(idToken, clientId, request.nonce, System.currentTimeMillis() / 1000);
                if (SystemClock.elapsedRealtime() >= expiresAtElapsed) throw new IllegalArgumentException();
                Session session = new Session(subject, access, expiresAtElapsed, provider, clientId, refreshToken);
                if (!main.post(() -> {
                    if (closed) { session.close(); return; }
                    // The main queue can be delayed past expiry after verification completed.
                    try { session.subject(); } catch (IllegalStateException expired) { session.close(); fail(); return; }
                    close(); callback.onAuthenticated(session);
                })) session.close();
            } catch (Exception e) { main.post(() -> { if (!closed) fail(); }); }
        });
        return true;
    }

    private JSONObject exchange(String code) throws Exception {
        String form = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(OidcRedirect.REDIRECT) + "&code_verifier=" + enc(request.codeVerifier);
        return postForm(provider.tokenEndpoint, form);
    }
    private static RotatingSession.Response rotate(OidcProvider provider, String clientId, String refreshToken) throws Exception {
        JSONObject token = postForm(provider.tokenEndpoint, "grant_type=refresh_token&client_id=" + enc(clientId) + "&refresh_token=" + enc(refreshToken));
        Object access = token.opt("access_token"), refresh = token.opt("refresh_token"), type = token.opt("token_type"), duration = token.opt("expires_in");
        Object scope = token.opt("scope");
        if (!(access instanceof String) || !(refresh instanceof String) || !(type instanceof String) || !(duration instanceof Number)
                || (token.has("scope") && !(scope instanceof String))) throw new IllegalArgumentException();
        double seconds = ((Number) duration).doubleValue();
        if (!Double.isFinite(seconds) || seconds != Math.rint(seconds) || seconds < 1 || seconds > 604800) throw new IllegalArgumentException();
        return new RotatingSession.Response((String) access, (String) refresh, (String) type, (long) seconds, (String) scope, token.has("id_token"));
    }
    private static JSONObject postForm(java.net.URI endpoint, String form) throws Exception {
        byte[] body = form.getBytes(StandardCharsets.UTF_8);
        final long deadline = System.nanoTime() + 10_000_000_000L;
        HttpsURLConnection conn = (HttpsURLConnection) endpoint.toURL().openConnection();
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
