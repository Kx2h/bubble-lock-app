package org.privacybubble.app;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestStateClientTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(503);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/test/bubble-state", exchange -> {
            int call = calls.incrementAndGet();
            int code = ((status.get() == 503 || status.get() == 504) && call > 1) ? 200 : status.get();
            if (code == 506) code = 503;
            byte[] body = (code == 200 ? "ok" : "failure").getBytes(StandardCharsets.UTF_8);
            if (code == 413) { code = 200; body = new byte[16385]; }
            exchange.sendResponseHeaders(code, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URL url = new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/api/test/bubble-state");
            TestStateClient client = new TestStateClient(url, 0);
            if (!"ok".equals(client.read()) || calls.get() != 2) throw new AssertionError("503 should retry exactly once");
            calls.set(0); status.set(504);
            if (!"ok".equals(client.read()) || calls.get() != 2) throw new AssertionError("504 should retry exactly once");
            calls.set(0); status.set(506);
            try { client.read(); throw new AssertionError("repeated 503 must fail"); } catch (TestStateClient.HttpFailure expected) { }
            if (calls.get() != 2) throw new AssertionError("retry must be bounded");
            calls.set(0); status.set(403);
            try { client.read(); throw new AssertionError("403 must fail"); } catch (TestStateClient.HttpFailure expected) { }
            if (calls.get() != 1) throw new AssertionError("403 must not retry");
            calls.set(0); status.set(502);
            try { client.read(); throw new AssertionError("502 must fail"); } catch (TestStateClient.HttpFailure expected) { }
            if (calls.get() != 1) throw new AssertionError("502 may contain contract failures, do not retry");
            calls.set(0); status.set(413);
            try { client.read(); throw new AssertionError("oversize must fail"); } catch (TestStateClient.InvalidResponse expected) { }
            if (calls.get() != 1) throw new AssertionError("oversize must not retry");
            try { new TestStateClient(new URL("http://example.org/api/test/bubble-state"), 0); throw new AssertionError("non-loopback must fail"); } catch (IllegalArgumentException expected) { }
            Thread.currentThread().interrupt();
            try { client.read(); throw new AssertionError("interrupted call must stop"); } catch (InterruptedException expected) { } finally { Thread.interrupted(); }
            System.out.println("PASS: bounded GET retry, non-retry errors, response limit, loopback and cancellation");
        } finally { server.stop(0); }
    }
}
