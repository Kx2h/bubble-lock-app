package org.privacybubble.app.ui;

public final class LocationReadLease {
    private long wallDeadline, elapsedDeadline;

    public boolean install(long deadline, long wallNow, long elapsedNow) {
        clear();
        if (wallNow < 0 || elapsedNow < 0 || deadline <= wallNow || deadline - wallNow > 5000
                || elapsedNow > Long.MAX_VALUE - (deadline - wallNow)) return false;
        wallDeadline=deadline;
        elapsedDeadline=elapsedNow+(deadline-wallNow);
        return true;
    }

    public boolean active() { return wallDeadline != 0; }

    public boolean isValid(long wallNow, long elapsedNow) {
        return active() && wallNow >= 0 && elapsedNow >= 0 && wallNow < wallDeadline && elapsedNow < elapsedDeadline;
    }

    public long remaining(long wallNow, long elapsedNow) {
        return isValid(wallNow,elapsedNow) ? Math.min(wallDeadline-wallNow,elapsedDeadline-elapsedNow) : 0;
    }

    public void clear() { wallDeadline=0; elapsedDeadline=0; }
}
