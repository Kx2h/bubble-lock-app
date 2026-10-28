package org.privacybubble.app.auth;

/** In-memory review binding for the fixed synthetic consent fixture, not authorization. */
final class ConsentReviewGate {
    static final long REVIEW_MILLIS = 60_000;
    final String instance, status, expires;
    final int revision;
    final long started;

    ConsentReviewGate(String instance, String status, int revision, String expires, long started) {
        this.instance = instance; this.status = status; this.revision = revision;
        this.expires = expires; this.started = started;
    }

    boolean matches(String instance, String status, int revision, String expires,
                    long elapsed, long wallMillis, long expiryMillis) {
        if (elapsed < started || elapsed - started >= REVIEW_MILLIS) return false;
        if (!this.instance.equals(instance) || !this.status.equals(status)
                || this.revision != revision || !this.expires.equals(expires)) return false;
        // Device wall time is only a conservative UI check; the service remains authoritative.
        return !"active".equals(status) || wallMillis < expiryMillis;
    }
}
