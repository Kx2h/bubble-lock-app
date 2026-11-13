package org.privacybubble.app.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MobileApiAuthorizationTest {
    private static final String[] CONTRACTS = {"keyrings-v1", "portal-central-v1", "portal-web-v1"};

    @Test public void requestsOnlyTheSelectedApiScope() {
        assertEquals("openid offline_access location:read", MobileApiAuthorization.forContract(CONTRACTS[0]).requestScope());
        assertEquals("openid portal:read", MobileApiAuthorization.forContract(CONTRACTS[1]).requestScope());
        assertEquals("openid portal:read", MobileApiAuthorization.forContract(CONTRACTS[2]).requestScope());
        for (String contract : new String[]{null,"","portal","portal-central-v2","KEYRINGS-v1","keyrings-v1 ","portal-web-v1\u0000"})
            assertThrows(IllegalArgumentException.class, () -> MobileApiAuthorization.forContract(contract));
    }

    @Test public void acceptsOnlyDistinctRequestedScopesWithRequiredApiPermission() {
        for (String contract : CONTRACTS) {
            MobileApiAuthorization policy = MobileApiAuthorization.forContract(contract);
            for (String valid : new String[]{policy.apiScope,"openid " + policy.apiScope,
                    policy.requestScope(),policy.apiScope + " openid"}) policy.requireResponseScope(valid);
            if (contract.equals("keyrings-v1")) {
                policy.requireResponseScope("offline_access " + policy.apiScope);
                policy.requireResponseScope(policy.apiScope + " offline_access openid");
            } else {
                assertThrows(IllegalArgumentException.class, () -> policy.requireResponseScope("offline_access " + policy.apiScope));
                assertThrows(IllegalArgumentException.class, () -> policy.requireResponseScope(policy.requestScope() + " offline_access"));
                assertFalse(policy.allowedScopes().contains("offline_access"));
            }
            String other = contract.equals("keyrings-v1") ? "portal:read" : "location:read";
            for (String invalid : new String[]{null,"","openid","openid offline_access",other,"openid " + other,
                    policy.apiScope + " " + other,policy.apiScope + " dashboard:read",policy.apiScope + " admin",
                    policy.apiScope + " household:write",policy.apiScope + " bubble:command",policy.apiScope + " " + policy.apiScope,
                    "openid openid " + policy.apiScope," " + policy.apiScope,policy.apiScope + " ","openid  " + policy.apiScope,
                    "openid\t" + policy.apiScope,"openid\n" + policy.apiScope,policy.apiScope + "\u0000"})
                assertThrows(IllegalArgumentException.class, () -> policy.requireResponseScope(invalid));
        }
    }

    @Test public void malformedOrDuplicateScopeCannotExpandAProfile() {
        java.util.Random random = new java.util.Random(0x53434f5045L);
        for (String contract : CONTRACTS) {
            MobileApiAuthorization policy = MobileApiAuthorization.forContract(contract);
            for (int index=0;index<512;index++) {
                String extra = "scope" + Long.toUnsignedString(random.nextLong(),36);
                assertThrows(IllegalArgumentException.class, () -> policy.requireResponseScope(policy.requestScope() + " " + extra));
            }
        }
    }
}
