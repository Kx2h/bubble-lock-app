package org.privacybubble.app.auth;

public final class ConsentReviewGateTest {
    public static void main(String[] args) {
        ConsentReviewGate gate = new ConsentReviewGate("instance-a", "active", 7, "expiry", 1000);
        if (!gate.matches("instance-a", "active", 7, "expiry", 1001, 100, 200)) throw new AssertionError("valid review rejected");
        if (gate.matches("instance-b", "active", 7, "expiry", 1001, 100, 200)) throw new AssertionError("instance changed");
        if (gate.matches("instance-a", "revoked", 7, "expiry", 1001, 100, 200)) throw new AssertionError("status changed");
        if (gate.matches("instance-a", "active", 8, "expiry", 1001, 100, 200)) throw new AssertionError("revision changed");
        if (gate.matches("instance-a", "active", 7, "other", 1001, 100, 200)) throw new AssertionError("expiry changed");
        if (gate.matches("instance-a", "active", 7, "expiry", 61000, 100, 200)) throw new AssertionError("review timed out");
        if (gate.matches("instance-a", "active", 7, "expiry", 1001, 200, 200)) throw new AssertionError("grant expired");
        if (gate.matches("instance-a", "active", 7, "expiry", 999, 100, 200)) throw new AssertionError("clock moved backwards");
        ConsentReviewGate inactive = new ConsentReviewGate("instance-a", "expired", 7, "expiry", 1000);
        if (!inactive.matches("instance-a", "expired", 7, "expiry", 1001, 200, 200)) throw new AssertionError("expired grant should be reviewable for a new approval");
        System.out.println("Consent review binding tests passed");
    }
}
