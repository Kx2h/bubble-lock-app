package org.privacybubble.app.auth;

final class AuthorizationAttempt {
    final long epoch;
    final String base, apiContract;
    private final long started;
    private boolean consumed, canceled;

    AuthorizationAttempt(long started, long epoch, String base) {
        this(started, epoch, base, "keyrings-v1");
    }

    AuthorizationAttempt(long started, long epoch, String base, String apiContract) {
        if (started < 0 || base == null || base.isEmpty()) throw new IllegalArgumentException("Invalid authorization transaction");
        this.started = started; this.epoch = epoch; this.base = base;
        this.apiContract = MobileApiAuthorization.forContract(apiContract).contract;
    }

    synchronized boolean isLive(long now, long currentEpoch, String currentBase) {
        return isLive(now, currentEpoch, currentBase, apiContract);
    }

    synchronized boolean isLive(long now, long currentEpoch, String currentBase, String currentContract) {
        return !canceled && now >= started && now - started < 600_000 && epoch == currentEpoch && base.equals(currentBase) && apiContract.equals(currentContract);
    }

    synchronized boolean claim(long now, long currentEpoch, String currentBase) {
        return claim(now, currentEpoch, currentBase, apiContract);
    }

    synchronized boolean claim(long now, long currentEpoch, String currentBase, String currentContract) {
        if (consumed || !isLive(now, currentEpoch, currentBase, currentContract)) return false;
        consumed = true;
        return true;
    }

    synchronized void cancel() { canceled = true; }
}
