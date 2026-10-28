package org.privacybubble.oidcsurrogate;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Restricts the surrogate to the local synthetic OIDC fixture and no account data. */
final class SyntheticRequest {
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
        "response_type", "client_id", "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method"));
    private static final Set<String> FIELDS = new HashSet<>(REQUIRED);
    static { FIELDS.add("prompt"); }
    private SyntheticRequest() { }
    private static boolean validScopeAndPrompt(Map<String,String> values) {
        String scope = values.get("scope");
        String prompt = values.get("prompt");
        return ("openid".equals(scope) && prompt == null)
                || ("openid offline_access".equals(scope) && "consent".equals(prompt))
                || ("openid offline_access test.consent.read test.consent.write".equals(scope) && "consent".equals(prompt));
    }
    static URI validate(String value) {
        try {
            if (value == null || value.length() > 8192) throw new IllegalArgumentException();
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || !"localhost".equals(uri.getHost()) || uri.getPort() != 18443 || uri.getUserInfo() != null || !"/authorize".equals(uri.getRawPath()) || uri.getRawQuery() == null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            Map<String,String> values = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                int separator = part.indexOf('=');
                if (separator <= 0) throw new IllegalArgumentException();
                String key = URLDecoder.decode(part.substring(0, separator), "UTF-8");
                String item = URLDecoder.decode(part.substring(separator + 1), "UTF-8");
                if (!FIELDS.contains(key) || values.put(key, item) != null) throw new IllegalArgumentException();
            }
            if (!values.keySet().containsAll(REQUIRED) || !"code".equals(values.get("response_type"))
                    || !"privacy-bubble-android-test".equals(values.get("client_id"))
                    || !"org.privacybubble.app:/oauth2redirect".equals(values.get("redirect_uri"))
                    || !validScopeAndPrompt(values) || !"S256".equals(values.get("code_challenge_method"))
                    || !values.get("state").matches("[A-Za-z0-9_-]{16,512}")
                    || !values.get("nonce").matches("[A-Za-z0-9_-]{16,512}")
                    || !values.get("code_challenge").matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            return uri;
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid synthetic test request"); }
    }
}
