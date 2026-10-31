package org.privacybubble.app.auth;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.privacybubble.app.net.BearerToken;

public class MobileTokenBoundaryFuzzTest {
    private JSONObject response() throws Exception {
        return new JSONObject().put("access_token", "access.token-123").put("token_type", "Bearer").put("expires_in", 300)
                .put("refresh_token", "rotated-refresh").put("scope", "openid location:read").put("id_token", "signed.id.token");
    }

    @Test public void bearerGrammarIsSharedWithApiAndRefreshRemainsOpaque() throws Exception {
        for (String access : new String[]{"access.token-123", "a+b/c~d_ef==", "x".repeat(8192)}) {
            assertTrue(BearerToken.isValid(access));
            MobileTokenManager.TokenValues values = MobileTokenManager.TokenValues.parse(response().put("access_token", access), "previous", true);
            assertEquals(access, values.access); assertEquals("rotated-refresh", values.refresh); assertEquals(300, values.seconds);
        }
        for (String access : new String[]{"", "a b", "\r\nHeader:value", "a:b", "a=b=c", "=only-padding", "키링", "x".repeat(8193)}) {
            assertFalse(BearerToken.isValid(access));
            assertThrows(Exception.class, () -> MobileTokenManager.TokenValues.parse(response().put("access_token", access), "previous", false));
        }
    }

    @Test public void typeExpiryScopeAndIdentityBoundaryCorpusIsRejected() throws Exception {
        for (Object expiry : new Object[]{JSONObject.NULL, "300", true, 29, 604801, 300.5, new java.math.BigDecimal("1e100")}) {
            assertThrows(Exception.class, () -> MobileTokenManager.TokenValues.parse(response().put("expires_in", expiry), "previous", false));
        }
        for (String scope : new String[]{"", "openid", " location:read", "location:read ", "openid  location:read", "openid\t location:read", "openid\u0000 location:read", "키링 location:read", "x\\y location:read", "x\"y location:read"}) {
            assertThrows(Exception.class, () -> MobileTokenManager.TokenValues.parse(response().put("scope", scope), "previous", false));
        }
        for (String name : new String[]{"access_token", "token_type", "refresh_token", "scope", "id_token"}) {
            for (Object value : new Object[]{JSONObject.NULL, true, 42, new JSONObject()}) {
                assertThrows(Exception.class, () -> MobileTokenManager.TokenValues.parse(response().put(name, value), "previous", false));
            }
        }
        JSONObject noRotation = response(); noRotation.remove("refresh_token"); noRotation.remove("id_token");
        assertEquals("previous", MobileTokenManager.TokenValues.parse(noRotation, "previous", false).refresh);
    }

    @Test public void rawDecimalExpiryIsValidatedBeforeAndroidCanRoundItToDouble() throws Exception {
        String prefix = "{\"access_token\":\"access.token\",\"token_type\":\"Bearer\",\"expires_in\":";
        for (String expiry : new String[]{"300.000000000000000000001", "29.999999999999999999999", "604800.000000000000000000001", "1e100", "-1e-400"}) {
            assertThrows(Exception.class, () -> MobileTokenManager.TokenValues.parse(prefix + expiry + "}", "previous", false));
        }
        assertEquals(300, MobileTokenManager.TokenValues.parse(prefix + "3e2}", "previous", false).seconds);
        assertEquals(300, MobileTokenManager.TokenValues.parse(prefix + "300.000000000000000000000}", "previous", false).seconds);
    }
}
