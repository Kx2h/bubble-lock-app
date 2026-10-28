package org.privacybubble.app.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Strict, one-transaction redirect parser; consumes neither network nor device state. */
final class OidcRedirect {
    static final String REDIRECT = "org.privacybubble.app:/oauth2redirect";
    private OidcRedirect() { }

    static String code(String value, String expectedState, String expectedIssuer) {
        try {
            if (value == null || value.length() > 8192 || expectedState == null || expectedState.isEmpty()) fail();
            URI uri = new URI(value);
            if (!"org.privacybubble.app".equals(uri.getScheme()) || uri.getRawAuthority() != null
                    || !"/oauth2redirect".equals(uri.getRawPath()) || uri.getRawFragment() != null || uri.getRawQuery() == null) fail();
            Map<String, String> parameters = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                int separator = part.indexOf('=');
                if (separator <= 0) fail();
                String key = URLDecoder.decode(part.substring(0, separator), "UTF-8");
                String val = URLDecoder.decode(part.substring(separator + 1), "UTF-8");
                if (parameters.put(key, val) != null) fail();
            }
            String state = parameters.get("state");
            String code = parameters.get("code");
            if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), expectedState.getBytes(StandardCharsets.UTF_8))) fail();
            if (parameters.containsKey("error") || parameters.containsKey("access_token") || parameters.containsKey("id_token") || parameters.containsKey("token_type")) fail();
            if (expectedIssuer == null || !expectedIssuer.equals(parameters.get("iss"))) fail();
            if (code == null || code.isEmpty() || code.length() > 4096) fail();
            for (int i = 0; i < code.length(); i++) if (code.charAt(i) < 33 || code.charAt(i) > 126) fail();
            return code;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid authorization response"); }
    }
    private static void fail() { throw new IllegalArgumentException("Invalid authorization response"); }
}
