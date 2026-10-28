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
    private final URL endpoint;
    private final long retryDelayMillis;

    TestStateClient(URL endpoint, long retryDelayMillis) {
        if (!"http".equals(endpoint.getProtocol()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getRef() != null || !"/api/test/bubble-state".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Only the local test adapter is supported");
        }
        if (retryDelayMillis < 0 || retryDelayMillis > 1000) throw new IllegalArgumentException("Invalid retry delay");
        this.endpoint = endpoint;
        this.retryDelayMillis = retryDelayMillis;
    }

    String read() throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                int status = connection.getResponseCode();
                if (status != 200) {
                    if (attempt == 0 && (status == 503 || status == 504)) {
                        // Only GET is retried; no approval or other writes pass through this client.
                    } else throw new HttpFailure(status);
                } else {
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (data.size() + n > LIMIT) throw new InvalidResponse();
                            data.write(buffer, 0, n);
                        }
                    }
                    return new String(data.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (HttpFailure | InvalidResponse error) {
                throw error;
            } catch (IOException error) {
                if (attempt != 0 || Thread.currentThread().isInterrupted()) throw error;
            } finally {
                if (connection != null) connection.disconnect();
            }
            Thread.sleep(retryDelayMillis);
        }
        throw new IOException("Request failed");
    }

    static final class HttpFailure extends IOException {
        HttpFailure(int status) { super("HTTP " + status); }
    }
    static final class InvalidResponse extends IOException {
        InvalidResponse() { super("Response too large"); }
    }
}
