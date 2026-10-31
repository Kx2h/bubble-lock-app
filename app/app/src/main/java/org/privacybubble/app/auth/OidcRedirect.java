package org.privacybubble.app.auth;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.privacybubble.app.net.StrictJson;

final class OidcRedirect {
    static final String REDIRECT = "org.privacybubble.app:/oauth2redirect";
    private OidcRedirect() { }

    static String code(String value, String expectedState, String expectedIssuer) {
        try {
            if (value == null || value.length() > 8192 || !value.startsWith(REDIRECT + "?") || expectedState == null || expectedState.isEmpty()
                    || expectedState.length() > 512 || expectedIssuer == null || expectedIssuer.isEmpty() || expectedIssuer.length() > 2048) fail();
            URI uri = new URI(value);
            if (!"org.privacybubble.app".equals(uri.getScheme()) || uri.getRawAuthority() != null
                    || !"/oauth2redirect".equals(uri.getRawPath()) || uri.getRawFragment() != null || uri.getRawQuery() == null) fail();
            Map<String, String> parameters = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                int separator = part.indexOf('=');
                if (separator <= 0) fail();
                String key = decode(part.substring(0, separator));
                String val = decode(part.substring(separator + 1));
                if (!key.matches("[A-Za-z0-9_]{1,128}") || parameters.size() >= 64) fail();
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

    private static String decode(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < encoded.length(); index++) {
            char c = encoded.charAt(index);
            if (c == '%') {
                if (index + 2 >= encoded.length()) fail();
                int high = Character.digit(encoded.charAt(++index), 16), low = Character.digit(encoded.charAt(++index), 16);
                if (high < 0 || low < 0) fail();
                bytes.write(high * 16 + low);
            } else if (c == '+') bytes.write(' ');
            else {
                String unit;
                if (Character.isHighSurrogate(c)) {
                    if (++index >= encoded.length() || !Character.isLowSurrogate(encoded.charAt(index))) { fail(); }
                    unit = new String(new char[]{c, encoded.charAt(index)});
                } else {
                    if (Character.isLowSurrogate(c)) fail();
                    unit = String.valueOf(c);
                }
                byte[] utf8 = unit.getBytes(StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
            }
        }
        return StrictJson.utf8(bytes.toByteArray(), 8192);
    }
    private static void fail() { throw new IllegalArgumentException("Invalid authorization response"); }
}
