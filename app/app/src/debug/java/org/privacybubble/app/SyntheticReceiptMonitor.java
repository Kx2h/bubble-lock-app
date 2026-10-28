package org.privacybubble.app;

/** In-memory comparison of displayed synthetic content, never event identity or device state. */
final class SyntheticReceiptMonitor {
    enum Result { FIRST, SAME, CHANGED, RECONFIRMED }
    private String previous;
    private boolean gap;

    Result accept(String content) {
        if (content == null || content.isEmpty()) throw new IllegalArgumentException("Missing receipt content");
        Result result = gap ? Result.RECONFIRMED : previous == null ? Result.FIRST
                : previous.equals(content) ? Result.SAME : Result.CHANGED;
        previous = content;
        gap = false;
        return result;
    }
    void failed() { previous = null; gap = true; }
    void reset() { previous = null; gap = false; }
}
