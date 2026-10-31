package org.privacybubble.app.auth;

import static org.junit.Assert.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import org.junit.Test;

public class OidcRedirectFuzzTest {
    private static final long SEED = 0x5245444952454354L;
    private static final String BASE = "org.privacybubble.app:/oauth2redirect?";
    private static final String ISSUER = "https://issuer.example.test/realm";
    private String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private String response(String state) throws Exception { return BASE + "code=code-123&state=" + encode(state) + "&iss=" + encode(ISSUER); }

    @Test public void seededValidTransactionsAndEncodedDuplicateParameters() throws Exception {
        Random random = new Random(SEED);
        for (int index = 0; index < 512; index++) {
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String uri = response(state);
            assertEquals("code-123", OidcRedirect.code(uri, state, ISSUER));
            String key = new String[]{"state", "%73tate", "st%61te", "sta%74e"}[random.nextInt(4)];
            assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(uri + "&" + key + "=" + state, state, ISSUER));
            assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(uri, state + "x", ISSUER));
        }
        System.out.println("OidcRedirectFuzz seed=" + SEED + " valid=512 duplicate-parameters=512 wrong-state=512");
    }

    @Test public void malformedEncodingAndUriConfusionCorpusIsRejected() throws Exception {
        String good = response("state-123");
        for (String suffix : new String[]{"&extra=%C0%AF", "&extra=%ED%A0%80", "&extra=%F4%90%80%80", "&extra=%E2%82", "&extra=%FF", "&extra=%", "&extra=%GG",
                "&%73tate=state-123", "&code=another", "&iss=" + encode(ISSUER), "&error=access_denied", "&id_token=token", "&access_token=token", "&token_type=Bearer", "&=value", "&&extra=x", "#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(good + suffix, "state-123", ISSUER));
        }
        for (String prefix : new String[]{"org.privacybubble.app://host/oauth2redirect?", "org.privacybubble.app:///oauth2redirect?", "ORG.privacybubble.app:/oauth2redirect?", "org.privacybubble.app:/oauth2redirect/?",
                "org.privacybubble.app:/%6Fauth2redirect?", "org.privacybubble.app:oauth2redirect?", "https://issuer.example.test/oauth2redirect?", "org.privacybubble.app:/other?"}) {
            String value = prefix + good.substring(BASE.length());
            assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(value, "state-123", ISSUER));
        }
        for (String code : new String[]{"", "%00", "%20", "%0A", "%7F", "%E2%82%AC", "x".repeat(4097)}) {
            String value = BASE + "state=state-123&iss=" + encode(ISSUER) + "&code=" + code;
            assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(value, "state-123", ISSUER));
        }
    }

    @Test public void parserResourceAndExpectedTransactionBoundsAreEnforced() throws Exception {
        String good = response("state-123");
        assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(good, "x".repeat(513), ISSUER));
        assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(good, "state-123", "x".repeat(2049)));
        assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(good + "&extra=" + "x".repeat(8192), "state-123", ISSUER));
        StringBuilder overloaded = new StringBuilder(good);
        for (int index=0; index<64; index++) overloaded.append("&extra").append(index).append("=x");
        assertThrows(IllegalArgumentException.class, () -> OidcRedirect.code(overloaded.toString(), "state-123", ISSUER));
        assertEquals("code-123", OidcRedirect.code(good + "&session_state=provider-session", "state-123", ISSUER));
    }
}
