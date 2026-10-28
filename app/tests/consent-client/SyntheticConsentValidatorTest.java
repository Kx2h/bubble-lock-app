package org.privacybubble.app;

public final class SyntheticConsentValidatorTest {
    private static void rejected(Runnable run) {
        try { run.run(); throw new AssertionError("malformed input accepted"); }
        catch (IllegalStateException | java.time.DateTimeException expected) { }
    }
    private static void grant(Object mode, Object status, boolean missing, Object id, Object consent, Object revision, Object time, Object receipt) {
        SyntheticConsentValidator.grant(mode, status, missing, id, consent, revision, time, receipt);
    }
    public static void main(String[] args) {
        SyntheticConsentValidator.request("synthetic", "synthetic-request-1", "synthetic-visitor", "synthetic-location-check", "test.location.read", 1, 120);
        rejected(() -> SyntheticConsentValidator.request("synthetic", "synthetic-request-1", "synthetic-visitor", "synthetic-location-check", "test.location.read", "1", 120));
        rejected(() -> SyntheticConsentValidator.request("synthetic", "synthetic-request-1", "synthetic-visitor", "synthetic-location-check", "test.location.read", 1, 120.0));
        String receipt = "synthetic-12345678-1234-1234-1234-123456789abc";
        String time = "2026-10-28T11:00:00.123Z";
        grant("synthetic", "none", true, null, null, null, null, null);
        grant("synthetic", "active", false, "synthetic-request-1", 1, 1, time, receipt);
        grant("synthetic", "revoked", false, "synthetic-request-1", 1, 2, time, receipt);
        rejected(() -> grant("synthetic", "none", false, null, null, null, null, null));
        rejected(() -> grant("synthetic", "active", true, null, null, null, null, null));
        rejected(() -> grant("synthetic", "active", false, "synthetic-request-1", "1", 1, time, receipt));
        rejected(() -> grant("synthetic", "active", false, "synthetic-request-1", 1, "1", time, receipt));
        rejected(() -> grant("synthetic", "active", false, "synthetic-request-1", 1, 0, time, receipt));
        rejected(() -> grant("synthetic", "active", false, "synthetic-request-1", 1, 1, "2026-02-30T11:00:00.123Z", receipt));
        rejected(() -> grant("synthetic", "active", false, "synthetic-request-1", 1, 1, "2026-10-28T24:00:00.123Z", receipt));
        rejected(() -> grant("synthetic", "active", false, "synthetic-request-1", 1, 1, "2026-10-28T11:00:00.123+00:00", receipt));
        rejected(() -> grant("synthetic", "active", false, "synthetic-request-1", 1, 1, time, 123));
        rejected(() -> grant("synthetic", "approved", false, "synthetic-request-1", 1, 1, time, receipt));
        System.out.println("PASS: synthetic consent strict validation");
    }
}
