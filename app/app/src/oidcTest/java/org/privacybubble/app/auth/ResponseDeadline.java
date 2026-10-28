package org.privacybubble.app.auth;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** Bounds response-body processing even when the peer keeps sending before each read timeout. */
final class ResponseDeadline {
    interface Clock { long nanoTime(); }
    private final Clock clock;
    private final long deadline;

    ResponseDeadline(Clock clock, long budgetNanos) {
        if (clock == null || budgetNanos <= 0) throw new IllegalArgumentException();
        this.clock = clock;
        deadline = clock.nanoTime() + budgetNanos;
    }
    void check() throws SocketTimeoutException {
        if (clock.nanoTime() - deadline >= 0) throw new SocketTimeoutException("Response deadline exceeded");
    }
    String read(InputStream in, int limit) throws Exception {
        if (limit < 1) throw new IllegalArgumentException();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] bytes = new byte[1024];
        while (true) {
            check();
            int count = in.read(bytes);
            check();
            if (count == -1) return new String(data.toByteArray(), StandardCharsets.UTF_8);
            if (count > limit - data.size()) throw new IllegalStateException("Response too large");
            data.write(bytes, 0, count);
        }
    }
}
