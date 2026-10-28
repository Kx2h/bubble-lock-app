package org.privacybubble.app.auth;

/** Local prompt to recheck a synthetic grant; device time is not an authority. */
final class ConsentGrantExpiry {
    private ConsentGrantExpiry() { }
    static boolean needsServerCheck(long deviceTimeMillis, long serverExpiryMillis) {
        return deviceTimeMillis >= serverExpiryMillis;
    }
}
