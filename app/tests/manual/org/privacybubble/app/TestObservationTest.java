package org.privacybubble.app;

public final class TestObservationTest {
    private static void invalid(boolean hasObservation, Object observation, boolean hasLabel, Object label) {
        try { TestObservation.bootstrapPresent(hasObservation, observation, hasLabel, label); throw new AssertionError("invalid observation accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        if (!TestObservation.bootstrapPresent(true, "unobserved", false, null)) throw new AssertionError("legacy label lost");
        if (!TestObservation.bootstrapPresent(true, "unknown", true, true)) throw new AssertionError("new label lost");
        if (TestObservation.bootstrapPresent(true, "unknown", true, false)) throw new AssertionError("false label changed");
        if (TestObservation.bootstrapPresent(false, null, false, null)) throw new AssertionError("missing label inferred");
        invalid(true, 1, false, null);
        invalid(true, "observed", false, null);
        invalid(true, null, false, null);
        invalid(true, "unknown", true, "true");
        invalid(true, "unknown", true, null);
        invalid(true, "unobserved", true, false);
        System.out.println("PASS: bootstrap label is distinct from observation and malformed data rejected");
    }
}
