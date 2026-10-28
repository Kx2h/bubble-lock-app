package org.privacybubble.app.auth;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RotatingSessionTest {
    private RotatingSession.Response ok(String access, String refresh) { return new RotatingSession.Response(access, refresh, "Bearer", 60, null, false); }
    @Test public void rotatesAndAllowsRefreshAfterAccessExpiry() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger count = new AtomicInteger();
        RotatingSession s = new RotatingSession("synthetic", "a1", "r1", 2000, now::get, old -> {
            assertEquals(count.get() == 0 ? "r1" : "r2", old);
            return count.incrementAndGet() == 1 ? ok("a2", "r2") : ok("a3", "r3");
        });
        now.set(2000); assertThrows(IllegalStateException.class, s::accessToken);
        s.refresh(); assertEquals("a2", s.accessToken()); assertEquals("synthetic", s.subject());
        s.refresh(); assertEquals("a3", s.accessToken()); assertEquals(2, count.get());
        s.close(); assertThrows(IllegalStateException.class, s::accessToken); assertThrows(IllegalStateException.class, s::refresh);
        assertEquals("OidcSession[redacted]", s.toString());
    }
    @Test public void malformedOrAmbiguousResponsePermanentlyClosesWithoutRetry() {
        for (RotatingSession.Response bad : new RotatingSession.Response[]{
            ok("a2", "r1"), ok("a2", null), ok("r2", "r2"), new RotatingSession.Response("a2", "r2", "basic", 60, null, false),
            new RotatingSession.Response("a2", "r2", "bearer", 0, null, false), new RotatingSession.Response("a2", "r2", "bearer", 60, "openid admin", false),
            new RotatingSession.Response("a2", "r2", "bearer", 60, null, true)
        }) {
            AtomicInteger calls = new AtomicInteger();
            RotatingSession s = new RotatingSession("synthetic", "a1", "r1", 2000, () -> 1000, old -> { calls.incrementAndGet(); return bad; });
            assertThrows(IOException.class, s::refresh); assertThrows(IllegalStateException.class, s::accessToken);
            assertThrows(IllegalStateException.class, s::refresh); assertEquals(1, calls.get());
        }
        AtomicInteger calls = new AtomicInteger();
        RotatingSession s = new RotatingSession("synthetic", "a1", "r1", 2000, () -> 1000, old -> { calls.incrementAndGet(); throw new IOException("ambiguous network failure"); });
        assertThrows(IOException.class, s::refresh); assertThrows(IllegalStateException.class, s::refresh); assertEquals(1, calls.get());
    }
    @Test public void fatalTransportFailureAlsoDropsCredentials() {
        RotatingSession s = new RotatingSession("synthetic", "a1", "r1", 2000, () -> 1000, old -> { throw new AssertionError("fatal"); });
        assertThrows(AssertionError.class, s::refresh);
        assertThrows(IllegalStateException.class, s::refresh);
        assertThrows(IllegalStateException.class, s::accessToken);
    }
    @Test public void simultaneousRefreshAndCloseCannotResurrectSession() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); AtomicReference<Throwable> result = new AtomicReference<>();
        RotatingSession s = new RotatingSession("synthetic", "a1", "r1", 2000, () -> 1000, old -> { calls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); return ok("a2", "r2"); });
        Thread worker = new Thread(() -> { try { s.refresh(); } catch (Throwable e) { result.set(e); } });
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, s::refresh); assertThrows(IllegalStateException.class, s::accessToken);
        s.close(); release.countDown(); worker.join(5000);
        assertFalse(worker.isAlive()); assertTrue(result.get() instanceof IOException);
        assertThrows(IllegalStateException.class, s::accessToken); assertEquals(1, calls.get());
    }
    @Test public void expirationIsMeasuredFromBeforeExchange() {
        AtomicLong now = new AtomicLong(1000);
        RotatingSession s = new RotatingSession("synthetic", "a1", "r1", 2000, now::get, old -> { now.set(62000); return ok("a2", "r2"); });
        assertThrows(IOException.class, s::refresh); assertThrows(IllegalStateException.class, s::accessToken);
    }
}
