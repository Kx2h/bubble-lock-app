package org.privacybubble.app.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class OidcRedirectTest {
    private static final String BASE = "org.privacybubble.app:/oauth2redirect?";
    private String parse(String uri) { return OidcRedirect.code(uri, "expected-state", "https://issuer.test"); }
    @Test public void acceptsBoundCode() { assertEquals("abc-123", parse(BASE + "state=expected-state&code=abc-123&iss=https%3A%2F%2Fissuer.test")); }
    @Test public void rejectsWrongAndAmbiguousRedirects() {
        for (String value : new String[]{
            "org.privacybubble.app:/other?state=expected-state&code=a",
            "org.privacybubble.app://other/oauth2redirect?state=expected-state&code=a",
            BASE + "state=expected-state&code=a", BASE + "state=wrong&code=a", BASE + "state=expected-state&state=expected-state&code=a",
            BASE + "state=expected-state&code=a&code=b", BASE + "state=expected-state&code=a#error",
            BASE + "state=expected-state&code=a&error=denied", BASE + "state=expected-state&code=a&access_token=secret",
            BASE + "state=expected-state&code=a&iss=https%3A%2F%2Fother.test", BASE + "state=expected-state&code=%0a"
        }) assertThrows(IllegalArgumentException.class, () -> parse(value));
    }
}
