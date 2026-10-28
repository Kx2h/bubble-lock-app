package org.privacybubble.app.auth;

public final class ConsentGrantExpiryTest {
    public static void main(String[] args) {
        long expiration = 1_800_000_000_000L;
        if (ConsentGrantExpiry.needsServerCheck(expiration - 1, expiration)) throw new AssertionError("early expiry");
        if (!ConsentGrantExpiry.needsServerCheck(expiration, expiration)) throw new AssertionError("expiry boundary missed");
        if (!ConsentGrantExpiry.needsServerCheck(expiration + 1, expiration)) throw new AssertionError("expired status accepted");
        System.out.println("Consent local expiry boundary tests passed");
    }
}
