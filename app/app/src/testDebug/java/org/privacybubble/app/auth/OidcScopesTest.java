package org.privacybubble.app.auth;

import static org.junit.Assert.*;
import org.junit.Test;

public class OidcScopesTest {
    @Test public void initialScopeCannotExpandOrDropRequestedScope() {
        assertTrue(OidcScopes.matchesInitial(null, false, false));
        assertTrue(OidcScopes.matchesInitial("openid", false, false));
        for (Object invalid : new Object[]{"openid admin", "admin openid", "openid openid", " openid", "openid ", "", 1})
            assertFalse(OidcScopes.matchesInitial(invalid, false, false));
    }

    @Test public void syntheticScopesRemainExactRegardlessOfOrder() {
        assertTrue(OidcScopes.matchesInitial(null, true, true));
        assertTrue(OidcScopes.matchesInitial("openid offline_access", true, false));
        assertTrue(OidcScopes.matchesInitial("test.consent.read openid test.consent.write offline_access", true, true));
        assertFalse(OidcScopes.matchesInitial("openid offline_access admin", true, false));
        assertFalse(OidcScopes.matchesInitial("openid offline_access test.consent.read", true, true));
        assertFalse(OidcScopes.matchesInitial("openid offline_access test.consent.read test.consent.write admin", true, true));
    }
}
