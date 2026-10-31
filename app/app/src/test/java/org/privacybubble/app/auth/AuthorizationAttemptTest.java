package org.privacybubble.app.auth;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;

public class AuthorizationAttemptTest {
    @Test public void singleUseCallbackAndLateResponseCannotSurviveCancellation() {
        AuthorizationAttempt attempt = new AuthorizationAttempt(100, 3, "https://api.example.test");
        assertTrue(attempt.claim(100, 3, "https://api.example.test"));
        assertFalse(attempt.claim(100, 3, "https://api.example.test"));
        assertTrue(attempt.isLive(101, 3, "https://api.example.test"));
        attempt.cancel();
        assertFalse(attempt.isLive(102, 3, "https://api.example.test"));
        assertFalse(attempt.claim(102, 3, "https://api.example.test"));
    }

    @Test public void monotonicTimeoutRollbackAndEpochOriginMutationsFailClosed() {
        long seed = 0x504B434553544154L;
        Random random = new Random(seed);
        for (int index=0; index<1024; index++) {
            long start = 1_000_000 + random.nextInt(1_000_000);
            AuthorizationAttempt attempt = new AuthorizationAttempt(start, index, "https://api.example.test");
            assertTrue(attempt.isLive(start + 599_999, index, "https://api.example.test"));
            assertFalse(attempt.claim(start + 600_000, index, "https://api.example.test"));
            assertFalse(attempt.claim(start - 1, index, "https://api.example.test"));
            assertFalse(attempt.claim(start, index + 1, "https://api.example.test"));
            assertFalse(attempt.claim(start, index, "https://other.example.test"));
            assertTrue(attempt.claim(start, index, "https://api.example.test"));
        }
        System.out.println("AuthorizationAttemptFuzz seed=" + seed + " attempts=1024 time-and-binding-rejections=4096");
    }
}
