package org.privacybubble.app;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Strict validation for the fixed synthetic fixture; no coercion of JSON values. */
final class SyntheticConsentValidator {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static boolean equalsString(Object value, String expected) { return value instanceof String && expected.equals(value); }
    private static boolean equalsInt(Object value, int expected) { return value instanceof Integer && ((Integer) value) == expected; }
    static void request(Object mode, Object id, Object principal, Object purpose, Object scopes, Object revision, Object ttl) {
        if (!equalsString(mode, "synthetic") || !equalsString(id, "synthetic-request-1")
                || !equalsString(principal, "synthetic-visitor") || !equalsString(purpose, "synthetic-location-check")
                || !equalsString(scopes, "test.location.read") || !equalsInt(revision, 1) || !equalsInt(ttl, 120)) throw new IllegalStateException();
    }
    static void grant(Object mode, Object status, boolean grantNull, Object id, Object consentRevision, Object grantRevision, Object expires, Object receipt) {
        if (!equalsString(mode, "synthetic") || !(status instanceof String)) throw new IllegalStateException();
        String s = (String) status;
        if (s.equals("none")) { if (!grantNull) throw new IllegalStateException(); return; }
        if (!(s.equals("active") || s.equals("revoked") || s.equals("expired")) || grantNull
                || !equalsString(id, "synthetic-request-1") || !equalsInt(consentRevision, 1)
                || !(grantRevision instanceof Integer) || ((Integer) grantRevision) < 1
                || !(expires instanceof String) || !(receipt instanceof String)
                || !((String) receipt).matches("synthetic-[A-Za-z0-9-]{16,64}")) throw new IllegalStateException();
        LocalDateTime.parse((String) expires, UTC);
    }
}
