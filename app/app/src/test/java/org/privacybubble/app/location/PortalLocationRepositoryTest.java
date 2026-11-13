package org.privacybubble.app.location;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class PortalLocationRepositoryTest {
    private static final String TOKEN = "isolated-test-access";

    private static PortalLocationRepository.WireResponse response(int status, byte[] body, String type, String cache, String date, String contract) {
        return new PortalLocationRepository.WireResponse(status, body, type, cache, date, contract);
    }
    private static PortalLocationRepository.WireResponse valid() {
        return response(200, PortalLocationParserTest.bytes(PortalLocationParserTest.central()), "application/json; charset=utf-8", "private, no-store", PortalLocationParserTest.DATE, "privacy-bubble.integration.v1");
    }
    private static PortalLocationRepository repository(PortalLocationRepository.Endpoint endpoint, PortalLocationRepository.Exchange exchange) {
        AtomicLong elapsed = new AtomicLong();
        return new PortalLocationRepository(endpoint, exchange, () -> PortalLocationParserTest.NOW, () -> elapsed.getAndAdd(100_000_000));
    }

    @Test public void centralUsesFixedPathBearerAndNoStoreWithinOneBoundedExchange() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PortalLocationRepository client = repository(PortalLocationRepository.Endpoint.CENTRAL, (origin, path, headers) -> {
            calls.incrementAndGet(); assertEquals("https://api.example.test", origin.toString());
            assertEquals("/v1/portal/locations", path); assertEquals("Bearer " + TOKEN, headers.get("Authorization"));
            assertEquals("no-store", headers.get("Cache-Control")); assertEquals(3, headers.size());
            return valid();
        });
        LocationSnapshot result = client.load("https://api.example.test", TOKEN);
        assertEquals(1, calls.get()); assertEquals(2, result.keyrings.size());
        assertEquals(PortalLocationParserTest.NOW + 3900, result.readAuthorizedUntilMillis);
    }

    @Test public void webContractIsChosenExplicitlyAndCannotBeGuessedFromPayload() throws Exception {
        PortalLocationRepository web = repository(PortalLocationRepository.Endpoint.WEB, (origin, path, headers) -> {
            assertEquals("/api/portal/locations", path);
            return response(200, PortalLocationParserTest.bytes(PortalLocationParserTest.web()), "application/json", "no-store", PortalLocationParserTest.DATE, null);
        });
        assertEquals("내 키링", web.load("https://api.example.test", TOKEN).keyrings.get(0).name);
        PortalLocationRepository mismatch = repository(PortalLocationRepository.Endpoint.WEB, (origin, path, headers) -> valid());
        assertThrows(LocationRepository.ApiException.class, () -> mismatch.load("https://api.example.test", TOKEN));
    }

    @Test public void invalidCredentialsAndOriginsNeverReachNetworkOrBecomeErrorText() {
        AtomicInteger calls = new AtomicInteger();
        PortalLocationRepository client = repository(PortalLocationRepository.Endpoint.CENTRAL, (origin, path, headers) -> { calls.incrementAndGet(); return valid(); });
        for (String base : new String[]{"http://127.0.0.1:8787", "https://u:p@api.example.test", "https://api.example.test/path", "https://api.example.test?query", "https://api.example.test#fragment", "https://api.example.test:0", "https://api.example.test:65536", "https://api.example.test:", " https://api.example.test", "https://api.example.test\\@other"}) {
            LocationRepository.ApiException failure = assertThrows(LocationRepository.ApiException.class, () -> client.load(base, TOKEN));
            assertFalse(failure.getMessage().contains(TOKEN)); assertFalse(failure.getMessage().contains("api.example"));
        }
        for (String token : new String[]{null, "", "contains space", "secret\r\nHeader: injected"}) {
            LocationRepository.ApiException failure = assertThrows(LocationRepository.ApiException.class, () -> client.load("https://api.example.test", token));
            assertEquals(401, failure.statusCode);
        }
        assertEquals(0, calls.get());
    }

    @Test public void redirectsUnauthorizedAndDeniedNeverTriggerFollowUpOrSchemaFallback() {
        for (int status : new int[]{301, 302, 303, 307, 308, 401, 403, 404, 503}) {
            AtomicInteger calls = new AtomicInteger();
            PortalLocationRepository client = repository(PortalLocationRepository.Endpoint.CENTRAL, (origin, path, headers) -> {
                calls.incrementAndGet(); return response(status, PortalLocationParserTest.bytes("{}"), null, null, null, null);
            });
            LocationRepository.ApiException failure = assertThrows(LocationRepository.ApiException.class, () -> client.load("https://api.example.test", TOKEN));
            assertEquals(status, failure.statusCode); assertEquals(1, calls.get());
        }
    }

    @Test public void missingOrWrongSecurityMetadataAndOverlongBodyFailClosed() {
        for (PortalLocationRepository.WireResponse wire : new PortalLocationRepository.WireResponse[]{
                response(200, valid().body, "text/html", "no-store", PortalLocationParserTest.DATE, "privacy-bubble.integration.v1"),
                response(200, valid().body, "application/json; charset=iso-8859-1", "no-store", PortalLocationParserTest.DATE, "privacy-bubble.integration.v1"),
                response(200, valid().body, "application/json", null, PortalLocationParserTest.DATE, "privacy-bubble.integration.v1"),
                response(200, valid().body, "application/json", "public, max-age=3600", PortalLocationParserTest.DATE, "privacy-bubble.integration.v1"),
                response(200, valid().body, "application/json", "no-store", null, "privacy-bubble.integration.v1"),
                response(200, valid().body, "application/json", "no-store", PortalLocationParserTest.DATE, "privacy-bubble.mobile.v1"),
                response(200, new byte[PortalLocationParser.MAX_BODY_BYTES + 1], "application/json", "no-store", PortalLocationParserTest.DATE, "privacy-bubble.integration.v1")}) {
            PortalLocationRepository client = repository(PortalLocationRepository.Endpoint.CENTRAL, (origin, path, headers) -> wire);
            assertThrows(LocationRepository.ApiException.class, () -> client.load("https://api.example.test", TOKEN));
        }
    }

    @Test public void totalRequestBudgetCannotBeReplayedAndIoErrorsDoNotLeakCredentials() {
        AtomicInteger calls = new AtomicInteger(); AtomicLong elapsed = new AtomicLong();
        PortalLocationRepository client = new PortalLocationRepository(PortalLocationRepository.Endpoint.CENTRAL,
                (origin, path, headers) -> { calls.incrementAndGet(); return valid(); }, () -> PortalLocationParserTest.NOW,
                () -> elapsed.getAndAdd(10_000_000_001L));
        assertThrows(LocationRepository.ApiException.class, () -> client.load("https://api.example.test", TOKEN)); assertEquals(1, calls.get());
        PortalLocationRepository broken = repository(PortalLocationRepository.Endpoint.CENTRAL, (origin, path, headers) -> { throw new IOException(TOKEN); });
        LocationRepository.ApiException failure = assertThrows(LocationRepository.ApiException.class, () -> broken.load("https://api.example.test", TOKEN));
        assertFalse(failure.getMessage().contains(TOKEN)); assertNull(failure.getCause());
    }
}
