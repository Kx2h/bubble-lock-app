package org.privacybubble.app.session;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ServiceOriginTest {
    @Test public void pinsCurrentBuildAfterUpgradeAndIgnoresSavedOriginInRelease() {
        assertEquals("https://new.example", ServiceOrigin.effective("https://new.example/", "https://retired.example", false));
        assertEquals("", ServiceOrigin.effective("", "https://retired.example", false));
        assertEquals("https://retired.example", ServiceOrigin.effective("https://new.example", "https://retired.example", true));
    }
    @Test public void normalizesOnlySafeOriginsAndRejectsAmbiguousPorts() {
        assertEquals("https://example.org", ServiceOrigin.normalize("https://EXAMPLE.org:443/", false));
        assertEquals("https://[::1]:8443", ServiceOrigin.normalize("https://[::1]:8443", false));
        assertEquals("http://127.0.0.1:8787", ServiceOrigin.normalize("http://127.0.0.1:8787/", true));
        for (String invalid : new String[]{"https://x:0", "https://x:65536", "https://x:", "https://x:9999999999", "https://x:abc", "https://x/path", "https://u@x", "https://x?", "https://x#", " https://x", "https://x/..", "https://x/%2f", "https://x\\@evil", "http://127.0.0.1:8787", "https://x\n"})
            assertThrows(invalid, IllegalArgumentException.class, () -> ServiceOrigin.normalize(invalid, false));
    }
    @Test public void checksAllOutOfRangePortsDeterministically() {
        for (int port=65536; port<66560; port++) {
            final String invalid="https://service.example:"+port;
            assertThrows(IllegalArgumentException.class, () -> ServiceOrigin.normalize(invalid, false));
        }
    }
}
