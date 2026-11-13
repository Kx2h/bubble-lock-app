package org.privacybubble.app.security;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.maplibre.android.MapLibre;
import org.maplibre.android.net.ConnectivityReceiver;
import org.privacybubble.app.BuildConfig;
import org.privacybubble.app.location.KeyringLocation;
import org.privacybubble.app.session.MobileSessionStore;

/**
 * Privileged, isolated instrumentation fixture for native paging and view semantics.
 * It writes only an encrypted local test credential and injects synthetic screen
 * models while location requests and MapLibre network access are paused. It is
 * not evidence of server authentication, physical keyring positions, or TalkBack
 * certification. This helper belongs only in src/androidTest of a debug build.
 */
public final class LocationPagingAssertions {
    private static final String ACCESS = "isolated-paging-fixture-access";
    private static final String SUBJECT = "isolated-paging-fixture-subject";
    private static final long[] renderTimingsMillis = new long[3];
    private LocationPagingAssertions() { }

    public static long[] lastRenderTimingsMillis() { return renderTimingsMillis.clone(); }

    public static int run(Instrumentation instrumentation) throws Exception {
        Counter assertions = new Counter();
        assertions.check(BuildConfig.DEBUG, "debug-build-required");
        SharedPreferences prefs = instrumentation.getTargetContext().getSharedPreferences("mobile_session", 0);
        assertions.check(!prefs.contains("credentials"), "empty-credential-precondition");
        final String originalBase = prefs.getString("api_base", null);
        MobileSessionStore store = new MobileSessionStore(instrumentation.getTargetContext());
        AtomicReference<Activity> activity = new AtomicReference<>();
        AtomicReference<Object> state = new AtomicReference<>();
        AtomicReference<Boolean> originalConnectivityOverride = new AtomicReference<>();
        boolean[] offlineOwned = { false }, sessionOwned = { false };
        java.util.Arrays.fill(renderTimingsMillis, 0);
        try {
            onMain(instrumentation, () -> {
                MapLibre.getInstance(instrumentation.getTargetContext());
                originalConnectivityOverride.set((Boolean)get(ConnectivityReceiver.instance(instrumentation.getTargetContext()), "connected"));
                offlineOwned[0] = true;
                MapLibre.setConnected(false);
                assertions.check(Boolean.FALSE.equals(MapLibre.isConnected()), "map-network-offline");
            });
            Intent launch = new Intent().setClassName(instrumentation.getTargetContext(), "org.privacybubble.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.set(instrumentation.startActivitySync(launch));
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                Activity screen = activity.get();
                pauseRequests(screen);
                state.set(get(screen, "state"));
                invoke(state.get(), "cancel");
                assertions.check(get(state.get(), "request") == null && !(Boolean)get(state.get(), "loading"), "no-initial-location-request");
                invoke(screen, "selectTab", new Class<?>[]{int.class, boolean.class}, 1, false);
            });
            sessionOwned[0] = true;
            store.saveConnection("https://127.0.0.1:9443", store.getIssuer(), store.getClientId());
            store.saveTokens(ACCESS, "", SUBJECT, System.currentTimeMillis() / 1000 + 600, "isolated-paging-nonce");
            assertions.check(!prefs.getString("credentials", "").isEmpty()
                    && !prefs.getString("credentials", "").contains(ACCESS), "test-credential-is-encrypted");
            assertions.check(ACCESS.equals(store.getAccessToken()) && SUBJECT.equals(store.getSubject()), "isolated-test-session-restored");
            List<KeyringLocation> fixture = fixture();
            long[] networkGeneration = { 0 };
            onMain(instrumentation, () -> {
                Object model = state.get(); Activity screen = activity.get();
                invoke(model, "clear");
                networkGeneration[0] = (Long)get(model, "generation");
                set(model, "locations", Collections.unmodifiableList(fixture));
                set(model, "selectedId", id(0)); set(model, "loaded", true); set(model, "lastSuccess", System.currentTimeMillis());
                long started = SystemClock.elapsedRealtime(); invoke(screen, "renderState");
                renderTimingsMillis[0] = SystemClock.elapsedRealtime() - started;
                assertions.check(((List<?>)get(model, "locations")).size() == 1000, "thousand-models-retained");
                checkPage(screen, 0, assertions);
                assertions.check(text(screen, "keyringPageStatus").equals("1 / 40 페이지"), "first-page-status");
                assertions.check(!view(screen, "keyringPrevious").isEnabled() && view(screen, "keyringNext").isEnabled(), "first-page-controls");
                assertions.check(text(screen, "listCaption").contains("1000") && text(screen, "listCaption").contains("1–25"), "first-page-range-label");
                Object map = get(screen, "map");
                assertions.check(((List<?>)get(map, "locations")).size() == 1000, "map-retains-thousand-models");
                ViewGroup rows = (ViewGroup)get(screen, "keyringRows");
                assertions.check(visibleText(rows).contains("모의 · 격리 시험 키링 0001"), "simulation-label-retained");
                assertions.check(visibleText(rows).contains("모의 · -67 dBm"), "ble-simulation-and-rssi-retained");
                assertions.check(visibleText(rows).contains("릴레이 기준 위치"), "relay-source-label-retained");
                assertions.check(visibleText(rows).contains("게이트웨이의 근접 신호 기록"), "ble-only-meaning-is-explicit");
                Object bleCard = cards(screen).get(id(0)), gpsCard = cards(screen).get(id(1));
                assertions.check(((View)get(get(bleCard, "coordinate"), "row")).getVisibility() == View.GONE, "ble-only-has-no-coordinate-row");
                assertions.check(((View)get(get(gpsCard, "battery"), "row")).getVisibility() == View.GONE
                        && ((View)get(get(gpsCard, "connection"), "row")).getVisibility() == View.GONE, "missing-telemetry-stays-hidden");
                assertions.check(!visibleText((ViewGroup)get(screen, "root")).contains(ACCESS), "test-credential-not-in-ui");
                assertNoRequests(screen, networkGeneration[0], assertions);
            });
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                Activity screen = activity.get();
                long started = SystemClock.elapsedRealtime();
                assertions.check(view(screen, "keyringNext").performClick(), "actual-next-button-handler");
                renderTimingsMillis[1] = SystemClock.elapsedRealtime() - started;
                checkPage(screen, 1, assertions);
                assertions.check(text(screen, "keyringPageStatus").equals("2 / 40 페이지") && view(screen, "keyringPrevious").isEnabled(), "next-page-status-and-controls");
                assertNoRequests(screen, networkGeneration[0], assertions);
            });
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                Activity screen = activity.get();
                long started = SystemClock.elapsedRealtime();
                invoke(screen, "selectKeyring", new Class<?>[]{String.class, boolean.class}, id(999), false);
                renderTimingsMillis[2] = SystemClock.elapsedRealtime() - started;
                checkPage(screen, 39, assertions);
                assertions.check(id(999).equals(get(state.get(), "selectedId")), "last-selection-retained");
                assertions.check(((View)get(cards(screen).get(id(999)), "selection")).getVisibility() == View.VISIBLE, "last-selection-indicator");
                assertions.check(text(screen, "keyringPageStatus").equals("40 / 40 페이지") && !view(screen, "keyringNext").isEnabled(), "last-page-status-and-controls");
                assertions.check(visibleText((ViewGroup)get(screen, "keyringRows")).contains("모의 · 격리 시험 키링 0999"), "last-page-provenance-retained");
                assertions.check(id(999).equals(get(get(screen, "map"), "selectedId")), "map-selection-follows-last-device");
                assertNoRequests(screen, networkGeneration[0], assertions);
                invoke(state.get(), "clear"); invoke(screen, "renderState");
                assertions.check(((List<?>)get(state.get(), "locations")).isEmpty(), "clear-removes-models");
                assertions.check(get(state.get(), "selectedId") == null && (Integer)get(state.get(), "listPage") == 0, "clear-removes-selection-and-page");
                assertions.check(cards(screen).isEmpty(), "clear-removes-card-cache");
                Object map = get(screen, "map");
                assertions.check(((List<?>)get(map, "locations")).isEmpty() && get(map, "selectedId") == null, "clear-removes-map-coordinates");
                assertions.check(get(map, "pendingCamera") == null && !(Boolean)get(map, "cameraPositioned"), "clear-removes-private-camera-state");
                assertions.check(get(screen, "selectedCard") == null, "clear-removes-selected-detail");
                assertions.check(!visibleText((ViewGroup)get(screen, "keyringRows")).contains("격리 시험 키링")
                        && !visibleText((ViewGroup)get(screen, "mapDetails")).contains("격리 시험 키링"), "clear-removes-fixture-text");
                assertions.check(view(screen, "keyringPager").getVisibility() == View.GONE
                        && !view(screen, "keyringPrevious").isEnabled() && !view(screen, "keyringNext").isEnabled(), "clear-hides-empty-pagination");
            });
        } finally {
            Throwable cleanupFailure = null;
            try {
                if (activity.get() != null) {
                    onMain(instrumentation, () -> {
                        Activity screen = activity.get();
                        try {
                            pauseRequests(screen);
                            Object model = get(screen, "state"); invoke(model, "clear"); invoke(screen, "renderState");
                        } finally { screen.finish(); }
                    });
                    instrumentation.waitForIdleSync();
                    assertions.check(activity.get().isFinishing() || activity.get().isDestroyed(), "fixture-activity-finished");
                }
            } catch (Throwable failure) { cleanupFailure = failure; }
            try {
                if (sessionOwned[0]) {
                    store.clearAll();
                    if (originalBase != null && !prefs.edit().putString("api_base", originalBase).commit()) throw new AssertionError("paging-restore-connection");
                    assertions.check(!prefs.contains("credentials") && !prefs.contains("iv") && store.getSubject().isEmpty(), "fixture-credential-removed");
                }
            } catch (Throwable failure) { if (cleanupFailure == null) cleanupFailure = failure; }
            try {
                if (offlineOwned[0]) onMain(instrumentation, () -> {
                    MapLibre.setConnected(originalConnectivityOverride.get());
                    Boolean restored = (Boolean)get(ConnectivityReceiver.instance(instrumentation.getTargetContext()), "connected");
                    assertions.check(java.util.Objects.equals(originalConnectivityOverride.get(), restored), "sdk-connectivity-override-restored");
                });
            } catch (Throwable failure) { if (cleanupFailure == null) cleanupFailure = failure; }
            if (cleanupFailure != null) throw new AssertionError("paging-fixture-cleanup", cleanupFailure);
        }
        return assertions.count.get();
    }

    private static void pauseRequests(Activity activity) throws Exception {
        set(activity, "foreground", false);
        ((Handler)get(activity, "main")).removeCallbacks((Runnable)get(activity, "refresh"));
    }

    private static void assertNoRequests(Activity activity, long generation, Counter assertions) throws Exception {
        Object model = get(activity, "state");
        assertions.check(!(Boolean)get(activity, "foreground") && get(model, "request") == null
                && !(Boolean)get(model, "loading") && (Long)get(model, "generation") == generation, "fixture-did-not-start-location-request");
    }

    private static void checkPage(Activity screen, int page, Counter assertions) throws Exception {
        assertions.check((Integer)get(get(screen, "state"), "listPage") == page, "page-index");
        assertions.check(((LinearLayout)get(screen, "keyringRows")).getChildCount() == 25 && cards(screen).size() == 25, "only-twenty-five-native-cards");
        Set<String> expected = new LinkedHashSet<>();
        for (int index = page * 25; index < page * 25 + 25; index++) expected.add(id(index));
        assertions.check(cards(screen).keySet().equals(expected), "page-card-identities");
    }

    private static List<KeyringLocation> fixture() {
        List<KeyringLocation> result = new ArrayList<>(); long now = System.currentTimeMillis();
        String observed = Instant.ofEpochMilli(now - 1000).toString(), expires = Instant.ofEpochMilli(now + 60_000).toString();
        for (int index = 0; index < 1000; index++) {
            boolean bleOnly = index == 0, relay = index == 2;
            result.add(new KeyringLocation(id(index), String.format(Locale.US, "격리 시험 키링 %04d", index), bleOnly ? "unavailable" : "available",
                    bleOnly ? null : 37.0 + index / 1_000_000.0, bleOnly ? null : 127.0 + index / 1_000_000.0,
                    bleOnly ? null : 5.0, bleOnly ? null : observed, null, bleOnly ? null : expires,
                    bleOnly ? "unknown" : relay ? "relay" : "gps", null, null, !bleOnly,
                    bleOnly ? "portal-ble" : relay ? "BLE_RELAY_OBSERVATION" : "portal-gps",
                    bleOnly ? "paging-fixture-gateway" : null, bleOnly ? -67 : null, bleOnly ? observed : null, bleOnly,
                    relay ? "paging-fixture-association" : null));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,?> cards(Activity screen) throws Exception { return (Map<String,?>)get(screen, "keyringCards"); }
    private static View view(Activity screen, String field) throws Exception { return (View)get(screen, field); }
    private static String text(Activity screen, String field) throws Exception { return ((TextView)get(screen, field)).getText().toString(); }
    private static String id(int index) { return String.format(Locale.US, "paging-fixture-%04d", index); }
    private static String visibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return "";
        StringBuilder value = new StringBuilder();
        if (view instanceof TextView) value.append(((TextView)view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup)view).getChildCount(); index++) value.append(visibleText(((ViewGroup)view).getChildAt(index)));
        return value.toString();
    }
    private static Object get(Object target, String field) throws Exception { Field found = target.getClass().getDeclaredField(field); found.setAccessible(true); return found.get(target); }
    private static void set(Object target, String field, Object value) throws Exception { Field found = target.getClass().getDeclaredField(field); found.setAccessible(true); found.set(target, value); }
    private static void invoke(Object target, String name) throws Exception { invoke(target, name, new Class<?>[0]); }
    private static void invoke(Object target, String name, Class<?>[] signature, Object... values) throws Exception { Method method = target.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); method.invoke(target, values); }
    private interface CheckedAction { void run() throws Exception; }
    private static void onMain(Instrumentation instrumentation, CheckedAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } });
        Throwable problem = failure.get();
        if (problem instanceof Exception) throw (Exception)problem;
        if (problem instanceof Error) throw (Error)problem;
        if (problem != null) throw new AssertionError("paging-main-action", problem);
    }
    private static final class Counter {
        final AtomicInteger count = new AtomicInteger();
        void check(boolean value, String name) { if (!value) throw new AssertionError("paging-" + name); count.incrementAndGet(); }
    }
}
