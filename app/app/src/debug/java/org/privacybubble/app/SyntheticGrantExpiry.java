package org.privacybubble.app;

import java.time.Instant;

/** The device clock can require a recheck; it cannot establish server grant expiry. */
final class SyntheticGrantExpiry {
    private SyntheticGrantExpiry() { }
    static long parse(String expires) { return Instant.parse(expires).toEpochMilli(); }
    static boolean needsServerCheck(long deviceMillis, long expiryMillis) { return deviceMillis >= expiryMillis; }
}
