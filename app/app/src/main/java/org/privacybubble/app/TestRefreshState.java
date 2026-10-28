package org.privacybubble.app;

/** Main-thread policy for foreground-only, user-enabled test polling. */
final class TestRefreshState {
    private boolean foreground;
    private boolean automatic;
    private boolean inFlight;
    private long generation;

    void foreground() { foreground = true; }
    void background() { foreground = false; automatic = false; generation++; }
    boolean isForeground() { return foreground; }
    boolean isAutomatic() { return automatic; }
    boolean isInFlight() { return inFlight; }
    void setAutomatic(boolean value) { automatic = foreground && value; }

    long begin() {
        if (!foreground || inFlight) return -1;
        inFlight = true;
        return generation;
    }

    boolean finish(long ticket) {
        if (!inFlight) throw new IllegalStateException("No test request in flight");
        inFlight = false;
        return foreground && ticket == generation;
    }
}
