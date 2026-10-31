package org.privacybubble.app.auth;

final class AuthorizationAttempt {
    final long epoch;
    final String base;
    private final long started;
    private boolean consumed, canceled;

    AuthorizationAttempt(long started, long epoch, String base) {
        if (started < 0 || base == null || base.isEmpty()) throw new IllegalArgumentException("Invalid authorization transaction");
        this.started = started; this.epoch = epoch; this.base = base;
    }

    synchronized boolean isLive(long now, long currentEpoch, String currentBase) {
        return !canceled && now >= started && now - started < 600_000 && epoch == currentEpoch && base.equals(currentBase);
    }

    synchronized boolean claim(long now, long currentEpoch, String currentBase) {
        if (consumed || !isLive(now, currentEpoch, currentBase)) return false;
        consumed = true;
        return true;
    }

    synchronized void cancel() { canceled = true; }
}
