package org.privacybubble.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Read-only client for the loopback development adapter, not the production API. */
final class TestStateClient {
    private static final int LIMIT = 16384;
    private static final long DEFAULT_BUDGET_MILLIS = 10_000;
    private final URL endpoint;
    private final long retryDelayMillis;
    private final long budgetMillis;

    TestStateClient(URL endpoint, long retryDelayMillis) {
        this(endpoint, retryDelayMillis, DEFAULT_BUDGET_MILLIS);
    }

    TestStateClient(URL endpoint, long retryDelayMillis, long budgetMillis) {
        if (!"http".equals(endpoint.getProtocol()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getRef() != null || !("/api/test/bubble-state".equals(endpoint.getPath()) || "/api/test/observation".equals(endpoint.getPath()) || "/api/test/receipt".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("Only the local test adapter is supported");
        }
        if (retryDelayMillis < 0 || retryDelayMillis > 1000) throw new IllegalArgumentException("Invalid retry delay");
        if (budgetMillis < 1 || budgetMillis > 30_000) throw new IllegalArgumentException("Invalid request budget");
        this.endpoint = endpoint;
        this.retryDelayMillis = retryDelayMillis;
        this.budgetMillis = budgetMillis;
    }

    String read() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000;
        for (int attempt = 0; attempt < 2; attempt++) {
            remainingMillis(deadline);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setConnectTimeout(Math.min(5000, remainingMillis(deadline)));
                connection.setReadTimeout(Math.min(8000, remainingMillis(deadline)));
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                int status = connection.getResponseCode();
                remainingMillis(deadline);
                if (status != 200) {
                    if (attempt == 0 && (status == 503 || status == 504)) {
                        // Only GET is retried; no approval or other writes pass through this client.
                    } else throw new HttpFailure(status);
                } else {
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int n;
                        while (true) {
                            connection.setReadTimeout(Math.min(8000, remainingMillis(deadline)));
                            n = input.read(buffer);
                            remainingMillis(deadline);
                            if (n == -1) break;
                            if (data.size() + n > LIMIT) throw new InvalidResponse();
                            data.write(buffer, 0, n);
                        }
                    }
                    return new String(data.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (HttpFailure | InvalidResponse | DeadlineExceeded error) {
                throw error;
            } catch (IOException error) {
                if (attempt != 0 || Thread.currentThread().isInterrupted()) throw error;
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (retryDelayMillis >= remainingMillis(deadline)) throw new DeadlineExceeded();
            Thread.sleep(retryDelayMillis);
        }
        throw new IOException("Request failed");
    }

    private static int remainingMillis(long deadline) throws InterruptedException, DeadlineExceeded {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new DeadlineExceeded();
        return (int) Math.max(1, (nanos + 999_999) / 1_000_000);
    }

    static final class HttpFailure extends IOException {
        HttpFailure(int status) { super("HTTP " + status); }
    }
    static final class InvalidResponse extends IOException {
        InvalidResponse() { super("Response too large"); }
    }
    static final class DeadlineExceeded extends IOException {
        DeadlineExceeded() { super("Request time budget exceeded"); }
    }
}
