package org.privacybubble.app;

public final class SyntheticGrantExpiryTest {
    public static void main(String[] args) {
        long expiry = SyntheticGrantExpiry.parse("2026-10-28T12:00:00.000Z");
        if (SyntheticGrantExpiry.needsServerCheck(expiry - 1, expiry)) throw new AssertionError("early expiry");
        if (!SyntheticGrantExpiry.needsServerCheck(expiry, expiry)) throw new AssertionError("deadline ignored");
        if (!SyntheticGrantExpiry.needsServerCheck(expiry + 1, expiry)) throw new AssertionError("past deadline ignored");
        System.out.println("Synthetic local expiry boundary tests passed");
    }
}
