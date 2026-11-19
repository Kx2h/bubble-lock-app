package org.privacybubble.app.portal;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ServerPortalPolicyTest {
    private static final String ORIGIN = "https://bubblelock.iptime.org";
    private final ServerPortalPolicy policy = new ServerPortalPolicy(ORIGIN);

    @Test public void profileNormalizesDnsCaseAndDefaultHttpsPortOnly() {
        ServerPortalPolicy normalized = new ServerPortalPolicy("HTTPS://BubbleLock.IPTime.ORG:443/");
        assertEquals(ORIGIN, normalized.origin());
        assertEquals(ORIGIN + "/#/user/overview", normalized.homeUrl());
        assertEquals("https://bubblelock.iptime.org:8443", new ServerPortalPolicy(ORIGIN + ":8443").origin());
    }

    @Test public void emptyLocalAmbiguousAndNonOriginProfilesAreRejected() {
        for (String value : new String[]{null, "", " ", "http://bubblelock.iptime.org", "https:bundle", "https://localhost", "https://a.localhost",
                "https://a.local", "https://a.internal", "https://a.home.arpa", "https://127.0.0.1", "https://2130706433", "https://0x7f000001",
                "https://0177.0.0.1", "https://[::1]", "https://bubblelock.iptime.org.", "https://bubblelock.iptime.org:",
                "https://bubblelock.iptime.org:0", "https://bubblelock.iptime.org:65536", "https://bubblelock.iptime.org:0443", "https://bubblelock.iptime.org:+443",
                "https://user@bubblelock.iptime.org", "https://bubblelock.iptime.org/user", ORIGIN + "?", ORIGIN + "#", ORIGIN + "/#/user/map",
                "https://-bad.example.org", "https://bad-.example.org", "https://a..example.org", "https://" + "a".repeat(64) + ".example.org"}) {
            assertThrows(IllegalArgumentException.class, () -> new ServerPortalPolicy(value));
        }
    }

    @Test public void sameOriginBffAndIdentityNavigationRemainInsideTheWebSession() {
        for (String value : new String[]{ORIGIN, ORIGIN + "/", ORIGIN + "/#/user/map", "HTTPS://BUBBLELOCK.IPTIME.ORG:443/identity/realms/bubble/protocol/openid-connect/auth?client_id=bubble-lock-dashboard",
                ORIGIN + "/api/auth/login?returnTo=%2F%23%2Fuser%2Fmap", ORIGIN + "/api/auth/callback?code=fixture-code&state=fixture-state",
                ORIGIN + "/api/portal/locations?role=guardian", ORIGIN + "/identity/resources/theme.css"}) {
            assertTrue(policy.isInternal(value));
            assertFalse(policy.isExternalBrowserCandidate(value));
        }
    }

    @Test public void foreignOriginAndNonDefaultPortNeverCountAsInternal() {
        for (String value : new String[]{"https://example.org/path", "https://bubblelock.iptime.org.evil.example/", "https://other.iptime.org/", ORIGIN + ":8443/"}) {
            assertFalse(policy.isInternal(value));
            assertTrue(policy.isExternalBrowserCandidate(value));
        }
        ServerPortalPolicy alternatePort = new ServerPortalPolicy(ORIGIN + ":8443");
        assertTrue(alternatePort.isInternal(ORIGIN + ":8443/identity/realms/bubble"));
        assertFalse(alternatePort.isInternal(ORIGIN + "/"));
    }

    @Test public void dangerousSchemesAndParserConfusionsNeverEscapeToAnotherHandler() {
        for (String value : new String[]{null, "", "//example.org", "/api/auth/login", "javascript:alert(1)", "data:text/html,fixture", "file:///etc/hosts",
                "content://fixture", "intent://example.org/#Intent;scheme=https;end", "http://example.org", "https://user:password@example.org/",
                "https://bubblelock.iptime.org@evil.example/", "https://evil.example@bubblelock.iptime.org/", "https://bubblelock.iptime.org%40evil.example/",
                "https://bubblelock%2eiptime.org/", "https://bubblelock.iptime.org%2f.evil.example/", "https://bubblelock.iptime.org\\@evil.example/",
                "https://bubblelock.iptime.org/%5c@evil.example", "https://bubblelock.iptime.org/%0d%0aHost:evil.example", ORIGIN + "/%00",
                "https://bubbl\u0435lock.iptime.org/", "https://bubblelock.iptime.org\u3002evil.example/", "https://bubblelock.iptime.org\n.evil.example/",
                "https://bubblelock.iptime.org:0443/", "https://bubblelock.iptime.org:/", "https://example.org/%", "https://example.org/a b"}) {
            assertFalse(policy.isInternal(value));
            assertFalse(policy.isExternalBrowserCandidate(value));
        }
    }

    @Test public void logoutMatchesOnlyTheExactSameOriginEndpoint() {
        assertTrue(policy.isLogout(ORIGIN + "/api/auth/logout"));
        assertTrue(policy.isLogout("https://BUBBLELOCK.IPTIME.ORG:443/api/auth/logout"));
        for (String value : new String[]{null, ORIGIN + "/api/auth/logout/", ORIGIN + "/api/auth/logout?", ORIGIN + "/api/auth/logout#",
                ORIGIN + "/api/auth/%6cogout", ORIGIN + ":8443/api/auth/logout", "https://example.org/api/auth/logout", "http://bubblelock.iptime.org/api/auth/logout"}) {
            assertFalse(policy.isLogout(value));
        }
    }

    @Test public void oversizedNavigationFailsBeforeUriParsing() {
        String oversized = ORIGIN + "/" + "a".repeat(16_384);
        assertFalse(policy.isInternal(oversized));
        assertFalse(policy.isExternalBrowserCandidate(oversized));
    }

    @Test public void packagedPresentationMatchesOnlyOneExactSameOriginAsset() {
        assertTrue(policy.isAppStylesheet(ORIGIN + "/__bubble_android__/portal-mobile.css"));
        for (String url : new String[]{null, "", "https://example.org/__bubble_android__/portal-mobile.css",
                ORIGIN + ":8443/__bubble_android__/portal-mobile.css", ORIGIN + "/api/portal/locations",
                ORIGIN + "/identity/theme.css", ORIGIN + "/__bubble_android__/portal-mobile.css?",
                ORIGIN + "/__bubble_android__/portal-mobile.css#", ORIGIN + "/__bubble_android__/%70ortal-mobile.css",
                ORIGIN + "/__bubble_android__/portal-mobile.css/", "file:///__bubble_android__/portal-mobile.css"}) {
            assertFalse(policy.isAppStylesheet(url));
        }
    }

    @Test public void knownProductPagesResumeWithOneCanonicalOriginAndRootPath() {
        String[][] pages = {
                {"/", "BubbleLock"},
                {"/user/overview", "내 대시보드"}, {"/user/map", "키링 위치"},
                {"/user/devices", "가족과 키링"}, {"/user/privacy", "공유 권한"},
                {"/user/activity", "알림과 요청"},
                {"/operator/overview", "한눈에 보기"}, {"/operator/map", "위치 지도"},
                {"/operator/devices", "장치와 자원"}, {"/operator/commands", "명령 처리"},
                {"/operator/audit", "활동 기록"}, {"/operator/services", "연결 서비스"},
                {"/operator/evidence", "개발 현황"}
        };
        for (String[] page : pages) {
            String url = "HTTPS://BUBBLELOCK.IPTIME.ORG:443/index.html#" + page[0];
            assertEquals(ORIGIN + "/#" + page[0], policy.restoreUrl(url));
            assertEquals(page[1], policy.navigationTitle(url));
        }
        assertEquals(ORIGIN + "/#/", policy.restoreUrl(ORIGIN));
        assertEquals(ORIGIN + "/#/", policy.restoreUrl(ORIGIN + "/"));
        assertEquals(ORIGIN + "/#/", policy.restoreUrl(ORIGIN + "/index.html"));
        ServerPortalPolicy alternate = new ServerPortalPolicy(ORIGIN + ":8443");
        assertEquals(ORIGIN + ":8443/#/user/map", alternate.restoreUrl(ORIGIN + ":8443/index.html#/user/map"));
    }

    @Test public void authCredentialsLocationParametersAndUnknownRoutesAreNeverResumeHints() {
        for (String url : new String[]{null, "", "about:blank", "/#/user/map",
                "https://example.org/#/user/map", ORIGIN + ":8443/#/user/map",
                ORIGIN + "/identity/realms/bubble/protocol/openid-connect/auth?state=fixture",
                ORIGIN + "/api/auth/callback?code=fixture-code&state=fixture-state",
                ORIGIN + "/api/auth/login?returnTo=%2F%23%2Fuser%2Fmap",
                ORIGIN + "/?code=fixture-code#/user/map", ORIGIN + "/?#/user/map",
                ORIGIN + "/index.html?token=fixture#/user/map",
                ORIGIN + "/index.html#/user/map?lat=37.5&lon=127.0",
                ORIGIN + "/#/user/map/device-1", ORIGIN + "/#/user/map/",
                ORIGIN + "/#/user/%6dap", ORIGIN + "/#/user/map%3Flat=37.5",
                ORIGIN + "/#/user/map#fixture", ORIGIN + "/#/user/../map",
                ORIGIN + "/#/operator/unknown", ORIGIN + "/#/main",
                ORIGIN + "/other#/user/map", ORIGIN + "/%69ndex.html#/user/map",
                ORIGIN + "/index.html/../#/user/map", ORIGIN + "/INDEX.HTML#/user/map"}) {
            assertNull(url, policy.restoreUrl(url));
        }
        // These redirects still work inside the WebView; only their retention is forbidden.
        assertTrue(policy.isInternal(ORIGIN + "/api/auth/callback?code=fixture-code&state=fixture-state"));
        assertTrue(policy.isInternal(ORIGIN + "/index.html#/user/map?lat=37.5&lon=127.0"));
    }

    @Test public void navigationLabelsDoNotReflectRemoteTitlesOrUnknownUrlContent() {
        assertEquals("로그인", policy.navigationTitle(ORIGIN + "/identity/realms/bubble/login-actions/authenticate?session_code=fixture"));
        assertEquals("로그인", policy.navigationTitle(ORIGIN + "/api/auth/login?returnTo=fixture"));
        assertEquals("로그인", policy.navigationTitle(ORIGIN + "/api/auth/callback?code=fixture-code"));
        for (String url : new String[]{null, "", ORIGIN + "/#/unknown", ORIGIN + "/#/user/map?token=fixture",
                ORIGIN + "/?token=fixture#/user/map", "https://example.org/identity/realms/bubble",
                "https://user:fixture@bubblelock.iptime.org/#/user/map"}) {
            assertEquals("BubbleLock", policy.navigationTitle(url));
        }
    }

    @Test public void webTypeTracksSystemFontScaleIncludingLargeAccessibleText() {
        assertEquals(100, ServerPortalPolicy.webTextZoom(1.0f));
        assertEquals(130, ServerPortalPolicy.webTextZoom(1.3f));
        assertEquals(200, ServerPortalPolicy.webTextZoom(2.0f));
        assertEquals(250, ServerPortalPolicy.webTextZoom(2.5f));
        assertEquals(80, ServerPortalPolicy.webTextZoom(0.8f));
        assertEquals(50, ServerPortalPolicy.webTextZoom(0.1f));
        assertEquals(300, ServerPortalPolicy.webTextZoom(10.0f));
        for (float value : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(100, ServerPortalPolicy.webTextZoom(value));
        }
    }
}
