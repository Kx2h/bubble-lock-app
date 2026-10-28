package org.privacybubble.app;

public final class TestRefreshStateTest {
    public static void main(String[] args) {
        TestRefreshState state = new TestRefreshState();
        if (state.begin() != -1) throw new AssertionError("background request started");
        state.setAutomatic(true);
        if (state.isAutomatic()) throw new AssertionError("background auto enabled");
        state.foreground();
        state.setAutomatic(true);
        long ticket = state.begin();
        if (!state.isAutomatic() || ticket < 0 || state.begin() != -1) throw new AssertionError("overlapping request");
        state.background();
        if (state.isAutomatic() || state.isForeground()) throw new AssertionError("polling continued in background");
        state.foreground();
        state.setAutomatic(true);
        if (state.begin() != -1 || state.finish(ticket)) throw new AssertionError("stale request accepted");
        long current = state.begin();
        if (current < 0 || !state.finish(current) || state.isInFlight()) throw new AssertionError("current request rejected");
        state.setAutomatic(false);
        if (state.isAutomatic()) throw new AssertionError("auto did not stop");
        System.out.println("PASS: foreground polling, no overlap and stale response discard");
    }
}
