package org.privacybubble.app.location;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;
import org.junit.Test;

public class LocationRepositoryFuzzTest {
    private static final long SEED = 0x4C4F434154494F4EL;
    private static final long NOW = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
    private static final String GENERATED = "2026-11-01T00:00:00.000Z";
    private static final String POINT = "{\"id\":\"keyring-1\",\"name\":\"내 열쇠\",\"status\":\"available\",\"latitude\":37.5,\"longitude\":127.0,\"accuracyMeters\":5,"
            + "\"observedAt\":\"2026-10-31T23:59:50.000Z\",\"receivedAt\":\"2026-10-31T23:59:55.000Z\",\"expiresAt\":\"2026-11-01T00:02:00.000Z\","
            + "\"source\":\"mec_location\",\"connectivity\":\"online\",\"batteryPercent\":80}";
    private String document(String points) { return "{\"contractId\":\"privacy-bubble.mobile.v1\",\"docRevision\":\"2.0.0\",\"generatedAt\":\"" + GENERATED + "\",\"keyrings\":[" + points + "]}"; }
    private LocationSnapshot parse(String value) throws Exception { return LocationRepository.parse(value.getBytes(StandardCharsets.UTF_8), NOW); }
    private void reject(String value) {
        LocationRepository.ApiException failure = assertThrows(LocationRepository.ApiException.class, () -> parse(value));
        assertEquals(0, failure.statusCode);
        assertFalse(failure.getMessage().contains("37.5"));
    }

    @Test public void validCoordinatesFreshnessAndEmptyAuthorizedInventory() throws Exception {
        assertEquals(0, parse(document("")).keyrings.size());
        KeyringLocation point = parse(document(POINT)).keyrings.get(0);
        assertEquals("내 열쇠", point.name); assertTrue(point.hasCoordinates()); assertTrue(point.isFresh(NOW));
        KeyringLocation expired = LocationRepository.parse(document(POINT).getBytes(StandardCharsets.UTF_8), NOW + 120_000).keyrings.get(0);
        assertEquals("stale", expired.status); assertTrue(expired.hasCoordinates()); assertFalse(expired.isFresh(NOW + 120_000));
        for (double latitude : new double[]{-90, 0, 90}) for (double longitude : new double[]{-180, 0, 180}) {
            String value = POINT.replace("\"latitude\":37.5", "\"latitude\":" + latitude).replace("\"longitude\":127.0", "\"longitude\":" + longitude);
            assertTrue(parse(document(value)).keyrings.get(0).hasCoordinates());
        }
    }

    @Test public void seededOutOfRangeAndWrongTypeMutationCorpusFailsClosed() {
        Random random = new Random(SEED);
        for (int index=0; index<1024; index++) {
            String changed;
            switch (random.nextInt(8)) {
                case 0: changed = POINT.replace("\"latitude\":37.5", "\"latitude\":" + (90.01 + random.nextDouble()*1000)); break;
                case 1: changed = POINT.replace("\"longitude\":127.0", "\"longitude\":" + (-180.01 - random.nextDouble()*1000)); break;
                case 2: changed = POINT.replace("\"accuracyMeters\":5", "\"accuracyMeters\":" + (-1 - random.nextInt(10000))); break;
                case 3: changed = POINT.replace("\"batteryPercent\":80", "\"batteryPercent\":" + (101 + random.nextInt(1000))); break;
                case 4: changed = POINT.replace("\"latitude\":37.5", "\"latitude\":\"37.5\""); break;
                case 5: changed = POINT.replace("\"latitude\":37.5", "\"latitude\":null"); break;
                case 6: changed = POINT.replace("\"batteryPercent\":80", "\"batteryPercent\":80.5"); break;
                default: changed = POINT.replace("\"accuracyMeters\":5", "\"accuracyMeters\":1e309"); break;
            }
            reject(document(changed));
        }
        System.out.println("LocationRepositoryFuzz seed=" + SEED + " range-and-type-mutations=1024");
    }

    @Test public void forbiddenDataForDeniedAndUnavailableStatesIsNeverExposed() throws Exception {
        for (String status : new String[]{"denied", "unavailable"}) {
            reject(document(POINT.replace("available", status)));
            String hidden = POINT.replace("available", status).replace("\"latitude\":37.5", "\"latitude\":null")
                    .replace("\"longitude\":127.0", "\"longitude\":null").replace("\"accuracyMeters\":5", "\"accuracyMeters\":null")
                    .replace("\"2026-10-31T23:59:50.000Z\"", "null").replace("\"2026-10-31T23:59:55.000Z\"", "null")
                    .replace("\"2026-11-01T00:02:00.000Z\"", "null").replace("mec_location", "unknown");
            KeyringLocation point = parse(document(hidden)).keyrings.get(0);
            assertFalse(point.hasCoordinates()); assertFalse(point.isFresh(NOW)); assertNull(point.observedAt);
        }
        for (String status : new String[]{"fresh", "", "AVAILABLE", "denied\u0000", "stale"}) reject(document(POINT.replace("available", status)));
    }

    @Test public void timestampCalendarNormalizationOffsetsPrecisionAndOrderingAreRejected() {
        for (String timestamp : new String[]{"2026-02-30T00:00:00.000Z", "2026-10-31T24:00:00.000Z", "2026-10-31T23:59:60.000Z",
                "2026-10-31T23:59:50.000+00:00", "2026-10-31T23:59:50.000000001Z", "2026-10-31t23:59:50.000z", "2026-13-01T00:00:00Z", "not-a-date"}) {
            reject(document(POINT.replace("2026-10-31T23:59:50.000Z", timestamp)));
        }
        reject(document(POINT).replace(GENERATED, "2026-11-01T00:00:30.001Z"));
        reject(document(POINT).replace(GENERATED, "2026-10-30T23:59:59.999Z"));
        reject(document(POINT.replace("2026-10-31T23:59:50.000Z", "2026-11-01T00:00:31.000Z")));
        reject(document(POINT.replace("2026-11-01T00:02:00.000Z", "2026-10-31T23:59:49.999Z")));
    }

    @Test public void schemaDuplicatesLenientGrammarUnicodeAndResourceBoundsFailClosed() {
        String valid = document(POINT);
        for (String value : new String[]{valid.replace("\"latitude\":37.5", "\"latitude\":91,\"latitude\":37.5"),
                valid.replace("\"latitude\":37.5", "\"latitude\":91,\"\\u006catitude\":37.5"),
                valid.replace("\"id\":\"keyring-1\"", "\"id\":\"keyring-1\",\"extra\":null"),
                valid.replace("\"batteryPercent\":80", "\"batteryPercent\":true"),
                valid.replace("\"name\":\"내 열쇠\"", "\"name\":\"\\uD800\""),
                valid.replace("\"name\":\"내 열쇠\"", "\"name\":\"\\u0000\""),
                valid.replace("\"latitude\":37.5", "'latitude':37.5"), valid.replace("\"latitude\":37.5", "latitude:37.5"),
                valid.replace("\"latitude\":37.5", "\"latitude\":037.5"), valid + "{}", document(POINT + "," + POINT),
                valid.replace("\"batteryPercent\":80", "\"batteryPercent\":80,"),
                valid.replace("\"latitude\":37.5", "\"latitude\":" + "[".repeat(18) + "0" + "]".repeat(18))}) reject(value);
        assertThrows(LocationRepository.ApiException.class, () -> LocationRepository.parse(new byte[65537], NOW));
        assertThrows(LocationRepository.ApiException.class, () -> LocationRepository.parse(valid.getBytes(StandardCharsets.UTF_8), Long.MAX_VALUE));
        assertThrows(LocationRepository.ApiException.class, () -> LocationRepository.parse(null, NOW));
        byte[] before = valid.getBytes(StandardCharsets.UTF_8);
        byte[] bad = new byte[before.length+2]; System.arraycopy(before, 0, bad, 0, before.length);
        bad[before.length] = (byte)0xC0; bad[before.length+1] = (byte)0xAF;
        assertThrows(LocationRepository.ApiException.class, () -> LocationRepository.parse(bad, NOW));
        StringBuilder crowded = new StringBuilder();
        for (int index=0; index<101; index++) { if (index>0) crowded.append(','); crowded.append(POINT.replace("keyring-1", "keyring-" + index)); }
        reject(document(crowded.toString()));
    }

    @Test public void decimalPrecisionCannotRoundOutOfRangeOrFractionalValuesIntoValidity() {
        for (String value : new String[]{"90.000000000000000000001", "-90.000000000000000000001"}) {
            reject(document(POINT.replace("\"latitude\":37.5", "\"latitude\":" + value)));
        }
        for (String value : new String[]{"180.000000000000000000001", "-180.000000000000000000001"}) {
            reject(document(POINT.replace("\"longitude\":127.0", "\"longitude\":" + value)));
        }
        reject(document(POINT.replace("\"batteryPercent\":80", "\"batteryPercent\":80.000000000000000000001")));
        reject(document(POINT.replace("\"accuracyMeters\":5", "\"accuracyMeters\":10000000.000000000000001")));
        reject(document(POINT.replace("\"accuracyMeters\":5", "\"accuracyMeters\":-1e-400")));
    }
}
