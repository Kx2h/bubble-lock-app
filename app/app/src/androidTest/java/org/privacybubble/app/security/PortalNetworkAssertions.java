package org.privacybubble.app.security;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.maplibre.android.MapLibre;
import org.maplibre.android.net.ConnectivityReceiver;
import org.privacybubble.app.BuildConfig;
import org.privacybubble.app.api.HttpsApiTransport;
import org.privacybubble.app.location.KeyringLocation;
import org.privacybubble.app.net.StrictJson;
import org.privacybubble.app.session.MobileSessionStore;
import org.privacybubble.app.ui.LocationReadLease;

/**
 * Privileged debug-only network fixture: real Android HTTPS/TLS and MainActivity
 * state, using one synthetic RSA JWT and an isolated localhost server. It bypasses
 * OIDC login and drives the foreground state while the screen may remain locked.
 * The camera cache is injected from synthetic coordinates with map networking
 * paused. This is not evidence of visible UI, a production IdP, or physical data.
 */
public final class PortalNetworkAssertions {
    private static final String ORIGIN = "https://localhost:9443";
    private static final String ISSUER = ORIGIN + "/fixture-idp";
    private static final String AUDIENCE = "portal-network-native";
    private static final String SUBJECT = "isolated-native-portal";
    private static final String KEYRING = "portal-network-fixture-keyring";
    private static volatile long expiryDuringRenewalMillis, lateDenialMillis, totalMillis;
    private PortalNetworkAssertions() { }

    public static long[] lastTimingsMillis() { return new long[]{expiryDuringRenewalMillis, lateDenialMillis, totalMillis}; }

    public static int run(Instrumentation instrumentation) throws Exception {
        long started = SystemClock.elapsedRealtime();
        expiryDuringRenewalMillis = lateDenialMillis = totalMillis = 0;
        Counter assertions = new Counter();
        SharedPreferences prefs = instrumentation.getTargetContext().getSharedPreferences("mobile_session", 0);
        File file = new File(instrumentation.getTargetContext().getFilesDir(), "portal-network-token");
        boolean tokenOwned = file.isFile() && file.getCanonicalFile().getParentFile().equals(instrumentation.getTargetContext().getFilesDir().getCanonicalFile());
        String originalBase = prefs.getString("api_base", null);
        MobileSessionStore store = new MobileSessionStore(instrumentation.getTargetContext());
        AtomicReference<Activity> activity = new AtomicReference<>();
        AtomicReference<Object> state = new AtomicReference<>();
        AtomicReference<Boolean> originalOverride = new AtomicReference<>();
        boolean[] offlineOwned = {false}, sessionOwned = {false};
        try {
            assertions.check(BuildConfig.DEBUG && BuildConfig.OIDC_TEST_ENABLED, "explicit-debug-test-ca-profile");
            assertions.check("portal-central-v1".equals(BuildConfig.MOBILE_API_CONTRACT)
                    && ORIGIN.equals(BuildConfig.MOBILE_API_BASE_URL), "isolated-loopback-portal-profile");
            assertions.check(ISSUER.equals(BuildConfig.OIDC_ISSUER) && AUDIENCE.equals(BuildConfig.OIDC_CLIENT_ID), "isolated-client-profile");
            assertions.check(!prefs.contains("credentials"), "empty-credential-precondition");
            assertions.check(tokenOwned, "owned-isolated-jwt-file");
            String token = readToken(file); long expires = fixtureExpiry(token);
            assertions.check(expires > System.currentTimeMillis() / 1000 + 30, "isolated-jwt-still-valid");
            onMain(instrumentation, () -> {
                MapLibre.getInstance(instrumentation.getTargetContext());
                originalOverride.set((Boolean)get(ConnectivityReceiver.instance(instrumentation.getTargetContext()), "connected"));
                offlineOwned[0] = true; MapLibre.setConnected(false);
                assertions.check(Boolean.FALSE.equals(MapLibre.isConnected()), "map-network-paused");
            });
            sessionOwned[0] = true;
            store.clearSession(); store.saveConnection(ORIGIN, store.getIssuer(), store.getClientId());
            store.saveTokens(token, "", SUBJECT, expires, "isolated-portal-network-nonce");
            assertions.check(prefs.contains("credentials") && !prefs.getString("credentials", "").contains(token), "fixture-session-encrypted");
            Intent launch = new Intent().setClassName(instrumentation.getTargetContext(), "org.privacybubble.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.set(instrumentation.startActivitySync(launch));
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                Activity screen = activity.get(); Object model = get(screen, "state"); state.set(model);
                set(screen, "foreground", true);
                set(model, "listener", (Runnable)() -> {
                    try { invoke(screen, "renderState"); } catch (Exception failure) { throw new AssertionError("network-fixture-render", failure); }
                });
                invoke(screen, "fetchLocations");
            });
            await(instrumentation, () -> {
                Object model = state.get();
                return !(Boolean)get(model, "loading") && ((List<?>)get(model, "locations")).size() == 1;
            }, 4000, "first-real-https-response");
            long[] beforeGeneration = {0};
            onMain(instrumentation, () -> {
                Activity screen = activity.get(); Object model = state.get();
                @SuppressWarnings("unchecked") List<KeyringLocation> points = (List<KeyringLocation>)get(model, "locations");
                KeyringLocation point = points.get(0);
                assertions.check(KEYRING.equals(point.id) && point.hasCoordinates() && point.simulation, "real-https-mock-position-and-provenance");
                assertions.check(KEYRING.equals(get(model, "selectedId")), "real-response-selected-device");
                assertions.check(((LocationReadLease)get(model, "readLease")).active(), "real-response-installs-hard-lease");
                assertions.check((screen.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "protected-portal-window");
                assertions.check(visibleText((ViewGroup)get(screen, "root")).contains("모의"), "mock-provenance-reaches-native-views");
                Handler handler = (Handler)get(screen, "main"); handler.removeCallbacks((Runnable)get(screen, "refresh"));
                invoke(model, "cancel");
                Object map = get(screen, "map");
                Bundle pending = new Bundle(); pending.putString("keyringId", KEYRING);
                pending.putDouble("lat", point.latitude); pending.putDouble("lon", point.longitude); pending.putDouble("zoom", 15); pending.putDouble("bearing", 0);
                set(map, "pendingCamera", pending); set(map, "cameraPositioned", true);
                assertions.check(get(map, "pendingCamera") != null && (Boolean)get(map, "cameraPositioned"), "synthetic-camera-cache-precondition");
                beforeGeneration[0] = (Long)get(model, "generation");
            });
            Map<String,String> headers = new HashMap<>(); headers.put("Authorization", "Bearer " + token);
            headers.put("Accept", "application/json"); headers.put("Cache-Control", "no-store");
            HttpsApiTransport.Response control = new HttpsApiTransport(URI.create(ORIGIN)).request("POST", "/_fixture/slow-deny", headers, null);
            assertions.check(control.status() == 200, "authenticated-isolated-fixture-control");
            long[] renewalGeneration = {0}, renewalStarted = {0}, remaining = {0};
            onMain(instrumentation, () -> {
                Activity screen = activity.get(); Object model = state.get();
                assertions.check(!((List<?>)get(model, "locations")).isEmpty(), "coordinates-present-before-slow-renewal");
                remaining[0] = ((LocationReadLease)get(model, "readLease")).remaining(System.currentTimeMillis(), SystemClock.elapsedRealtime());
                assertions.check(remaining[0] > 500 && remaining[0] <= 5000, "existing-lease-remains-bounded");
                renewalStarted[0] = SystemClock.elapsedRealtime(); invoke(screen, "fetchLocations");
                renewalGeneration[0] = (Long)get(model, "generation");
                assertions.check((Boolean)get(model, "loading") && get(model, "request") != null
                        && renewalGeneration[0] > beforeGeneration[0], "real-slow-renewal-pending");
            });
            await(instrumentation, () -> (Boolean)get(state.get(), "readLeaseExpired")
                    && ((List<?>)get(state.get(), "locations")).isEmpty(), Math.min(5500, remaining[0] + 800), "hard-expiry-before-network-reply");
            expiryDuringRenewalMillis = SystemClock.elapsedRealtime() - renewalStarted[0];
            onMain(instrumentation, () -> {
                Object model = state.get(); Object map = get(activity.get(), "map");
                assertions.check((Boolean)get(model, "loading") && get(model, "request") != null, "expiry-keeps-real-request-pending");
                assertions.check((Long)get(model, "generation") == renewalGeneration[0], "expiry-independent-of-network-generation");
                assertions.check(get(model, "selectedId") == null, "expiry-clears-selection");
                assertions.check(((List<?>)get(map, "locations")).isEmpty() && get(map, "selectedId") == null, "expiry-clears-map-coordinates");
                assertions.check(get(map, "pendingCamera") == null && !(Boolean)get(map, "cameraPositioned"), "expiry-clears-camera-cache");
                assertions.check(expiryDuringRenewalMillis <= remaining[0] + 800 && expiryDuringRenewalMillis < 6000, "expiry-meets-original-deadline");
            });
            await(instrumentation, () -> (Integer)get(state.get(), "errorCode") == 403, Math.max(1000, 11_500 - (SystemClock.elapsedRealtime() - renewalStarted[0])), "late-denial");
            lateDenialMillis = SystemClock.elapsedRealtime() - renewalStarted[0];
            onMain(instrumentation, () -> {
                Object model = state.get(); Object map = get(activity.get(), "map");
                assertions.check(lateDenialMillis >= 6500 && lateDenialMillis < 10_500, "delayed-denial-observed");
                assertions.check(((List<?>)get(model, "locations")).isEmpty() && get(model, "selectedId") == null, "late-denial-does-not-restore-state");
                assertions.check(((List<?>)get(map, "locations")).isEmpty() && get(map, "pendingCamera") == null
                        && !(Boolean)get(map, "cameraPositioned"), "late-denial-does-not-restore-map-or-camera");
            });
        } finally {
            Throwable cleanupFailure = null;
            try {
                if (activity.get() != null) {
                    onMain(instrumentation, () -> {
                        Activity screen = activity.get();
                        try {
                            set(screen, "foreground", false);
                            ((Handler)get(screen, "main")).removeCallbacks((Runnable)get(screen, "refresh"));
                            Object model = get(screen, "state"); invoke(model, "clear"); set(model, "listener", null); invoke(screen, "renderState");
                        } finally { screen.finish(); }
                    });
                    instrumentation.waitForIdleSync();
                    assertions.check(activity.get().isFinishing() || activity.get().isDestroyed(), "network-fixture-activity-finished");
                }
            } catch (Throwable failure) { cleanupFailure = failure; }
            try {
                if (sessionOwned[0]) {
                    store.clearAll();
                    if (originalBase != null && !prefs.edit().putString("api_base", originalBase).commit()) throw new AssertionError("network-fixture-connection-restore");
                    assertions.check(!prefs.contains("credentials") && !prefs.contains("iv"), "network-fixture-credentials-cleared");
                }
            } catch (Throwable failure) { if (cleanupFailure == null) cleanupFailure = failure; }
            try {
                if (tokenOwned) assertions.check(!file.exists() || file.delete(), "network-fixture-jwt-file-removed");
            } catch (Throwable failure) { if (cleanupFailure == null) cleanupFailure = failure; }
            try {
                if (offlineOwned[0]) onMain(instrumentation, () -> {
                    MapLibre.setConnected(originalOverride.get());
                    assertions.check(java.util.Objects.equals(originalOverride.get(), get(ConnectivityReceiver.instance(instrumentation.getTargetContext()), "connected")), "map-connectivity-override-restored");
                });
            } catch (Throwable failure) { if (cleanupFailure == null) cleanupFailure = failure; }
            totalMillis = SystemClock.elapsedRealtime() - started;
            if (cleanupFailure != null) throw new AssertionError("network-fixture-cleanup", cleanupFailure);
        }
        assertions.check(totalMillis < 15_000, "bounded-network-fixture-duration");
        return assertions.count.get();
    }

    private static String readToken(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) { if (output.size() + count > 8192) throw new AssertionError("network-fixture-token-bound"); output.write(buffer, 0, count); }
            return StrictJson.utf8(output.toByteArray(), 8192).trim();
        }
    }
    private static long fixtureExpiry(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new AssertionError("network-fixture-jwt-shape");
        for (String part : parts) if (!part.matches("[A-Za-z0-9_-]+")) throw new AssertionError("network-fixture-jwt-shape");
        Map<String,Object> header = StrictJson.object(StrictJson.utf8(Base64.getUrlDecoder().decode(parts[0]), 4096), 4096);
        Map<String,Object> claims = StrictJson.object(StrictJson.utf8(Base64.getUrlDecoder().decode(parts[1]), 4096), 4096);
        if (!"RS256".equals(header.get("alg")) || !"isolated-portal-network-key".equals(header.get("kid"))
                || !ISSUER.equals(claims.get("iss")) || !AUDIENCE.equals(claims.get("aud")) || !SUBJECT.equals(claims.get("sub"))
                || !"portal:read".equals(claims.get("scope")) || !(claims.get("exp") instanceof BigDecimal)) throw new AssertionError("network-fixture-identity-required");
        long exp = ((BigDecimal)claims.get("exp")).longValueExact();
        if (exp > System.currentTimeMillis() / 1000 + 610) throw new AssertionError("network-fixture-token-lifetime");
        return exp;
    }
    private interface CheckedAction { void run() throws Exception; }
    private interface CheckedCondition { boolean get() throws Exception; }
    private static void onMain(Instrumentation instrumentation, CheckedAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } });
        Throwable problem = failure.get();
        if (problem instanceof Exception) throw (Exception)problem;
        if (problem instanceof Error) throw (Error)problem;
        if (problem != null) throw new AssertionError("network-main-action", problem);
    }
    private static void await(Instrumentation instrumentation, CheckedCondition condition, long timeout, String name) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            boolean[] ready = {false}; onMain(instrumentation, () -> ready[0] = condition.get());
            if (ready[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("network-" + name + "-timeout");
    }
    private static Object get(Object target, String field) throws Exception { Field found = target.getClass().getDeclaredField(field); found.setAccessible(true); return found.get(target); }
    private static void set(Object target, String field, Object value) throws Exception { Field found = target.getClass().getDeclaredField(field); found.setAccessible(true); found.set(target, value); }
    private static void invoke(Object target, String method) throws Exception { Method found = target.getClass().getDeclaredMethod(method); found.setAccessible(true); found.invoke(target); }
    private static String visibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return "";
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView)view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup)view).getChildCount(); index++) result.append(visibleText(((ViewGroup)view).getChildAt(index)));
        return result.toString();
    }
    private static final class Counter {
        final AtomicInteger count = new AtomicInteger();
        void check(boolean value, String label) { if (!value) throw new AssertionError("network-" + label); count.incrementAndGet(); }
    }
}
