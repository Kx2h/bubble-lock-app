package org.privacybubble.app;

public final class SyntheticReceiptMonitorTest {
    private static void check(SyntheticReceiptMonitor.Result actual, SyntheticReceiptMonitor.Result expected) {
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        SyntheticReceiptMonitor monitor = new SyntheticReceiptMonitor();
        check(monitor.accept("INDETERMINATE|unknown"), SyntheticReceiptMonitor.Result.FIRST);
        check(monitor.accept("INDETERMINATE|unknown"), SyntheticReceiptMonitor.Result.SAME);
        check(monitor.accept("APPLIED|gateway-estimated"), SyntheticReceiptMonitor.Result.CHANGED);
        check(monitor.accept("APPLIED|gateway-estimated"), SyntheticReceiptMonitor.Result.SAME);
        monitor.failed();
        // A gap cannot establish a new event or a transition, even if the next
        // stored content differs from the last successful response.
        check(monitor.accept("REJECTED|unknown"), SyntheticReceiptMonitor.Result.RECONFIRMED);
        check(monitor.accept("REJECTED|unknown"), SyntheticReceiptMonitor.Result.SAME);
        monitor.reset();
        check(monitor.accept("TIMEOUT|unknown"), SyntheticReceiptMonitor.Result.FIRST);
        monitor.failed(); monitor.reset();
        check(monitor.accept("INDETERMINATE|unknown"), SyntheticReceiptMonitor.Result.FIRST);
        // A rapid leave/return cannot queue a second network operation or use
        // the old screen's late response as its comparison baseline.
        TestRefreshState refresh = new TestRefreshState();
        refresh.foreground(); refresh.setAutomatic(true);
        long old = refresh.begin();
        refresh.background(); monitor.reset();
        refresh.foreground(); refresh.setAutomatic(true);
        if (refresh.begin() != -1 || refresh.finish(old)) throw new AssertionError("stale request accepted or overlap started");
        long current = refresh.begin();
        if (current < 0 || !refresh.finish(current)) throw new AssertionError("new foreground request failed");
        check(monitor.accept("APPLIED|new-baseline"), SyntheticReceiptMonitor.Result.FIRST);
        refresh.setAutomatic(false);
        if (refresh.isAutomatic()) throw new AssertionError("explicit stop ignored");
        System.out.println("PASS: synthetic receipt first read, repeat, change, gap and reset");
    }
}
