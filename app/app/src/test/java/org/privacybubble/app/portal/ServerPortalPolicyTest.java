package org.privacybubble.app.portal;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ServerPortalPolicyTest {
    private static final String ORIGIN = "https://bubblelock.iptime.org";
    private final ServerPortalPolicy policy = new ServerPortalPolicy(ORIGIN);

    @Test public void profileNormalizesDnsCaseAndDefaultHttpsPortOnly() {
        ServerPortalPolicy normalized = new ServerPortalPolicy("HTTPS://BubbleLock.IPTime.ORG:443/");
        assertEquals(ORIGIN, normalized.origin());
        assertEquals(ORIGIN + "/#/user/map", normalized.homeUrl());
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
}
