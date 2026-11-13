package org.privacybubble.app.location;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;
import org.junit.Test;

public class PortalLocationParserTest {
    static final long NOW = Instant.parse("2026-11-14T00:00:00Z").toEpochMilli();
    static final String DATE = "Sat, 14 Nov 2026 00:00:00 GMT";
    static final String EXPIRY = "2026-11-14T00:00:05.000Z";
    static final String OBSERVED = "2026-11-13T23:59:50.000Z";
    static final String GPS = "{\"deviceId\":\"keyring-A\",\"gatewayId\":\"gateway-1\",\"observedAt\":\"" + OBSERVED + "\",\"simulation\":false,\"location\":{\"lat\":37.5,\"lon\":127,\"source\":\"gps\",\"accuracyM\":8},\"rssiDbm\":-65}";
    static final String BLE = "{\"deviceId\":\"keyring-B\",\"gatewayId\":\"gateway-1\",\"observedAt\":\"" + OBSERVED + "\",\"simulation\":false,\"rssiDbm\":-70}";

    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String central(String ids, String observations) {
        return "{\"deviceIds\":[" + ids + "],\"observations\":[" + observations + "],\"readExpiresAt\":\"" + EXPIRY + "\"}";
    }
    static String central() { return central("\"keyring-A\",\"keyring-B\"", GPS + "," + BLE); }
    static String web() {
        return "{\"schemaVersion\":\"1.0.0\",\"mode\":\"live\",\"simulation\":false,\"source\":\"portal-api\",\"updatedAt\":\"2026-11-14T00:00:00Z\",\"readExpiresAt\":\"" + EXPIRY
                + "\",\"devices\":[{\"deviceId\":\"keyring-A\",\"label\":\"내 키링\",\"position\":{\"coordinates\":{\"lat\":37.5,\"lon\":127},\"source\":\"gps\",\"observedAt\":\"" + OBSERVED
                + "\",\"accuracyM\":8,\"simulation\":false},\"ble\":null},{\"deviceId\":\"keyring-B\",\"label\":\"근접 키링\",\"position\":null,\"ble\":{\"gatewayId\":\"gateway-1\",\"rssiDbm\":-70,\"observedAt\":\"" + OBSERVED
                + "\",\"simulation\":false}}],\"gateways\":[{\"gatewayId\":\"gateway-1\",\"label\":\"수신기\",\"position\":{\"lat\":37.6,\"lon\":127.1},\"simulation\":false,\"referenceRssiDbm\":-59,\"pathLossExponent\":2}],\"rejectedRecords\":0,\"caughtUp\":true}";
    }
    private LocationSnapshot parse(String value) throws Exception { return PortalLocationParser.parseCentral(bytes(value), NOW, DATE); }
    private void reject(String value) { assertThrows(LocationRepository.ApiException.class, () -> parse(value)); }

    @Test public void mapsAuthorizedGpsAndRssiWithoutInventingCoordinatesOrTelemetry() throws Exception {
        LocationSnapshot result = parse(central());
        assertEquals(2, result.keyrings.size()); assertEquals(NOW + 4000, result.readAuthorizedUntilMillis);
        assertTrue(result.isReadAuthorized(NOW + 3999)); assertFalse(result.isReadAuthorized(NOW + 4000));
        KeyringLocation gps = result.keyrings.get(0), signal = result.keyrings.get(1);
        assertEquals("gps", gps.source); assertTrue(gps.hasCoordinates()); assertNull(gps.receivedAt);
        assertNull(gps.batteryPercent); assertNull(gps.connectivity);
        assertEquals(Integer.valueOf(-65), gps.rssiDbm); assertEquals("gateway-1", gps.gatewayId);
        assertFalse(signal.hasCoordinates()); assertNull(signal.latitude); assertNull(signal.longitude); assertNull(signal.accuracyMeters);
        assertEquals(Integer.valueOf(-70), signal.rssiDbm); assertEquals(OBSERVED.replace(".000", ""), signal.bleObservedAt);
        assertEquals("portal-ble", signal.provenance);
    }

    @Test public void relayAndMixedSimulationProvenanceSurviveStaleConversion() throws Exception {
        String relay = "{\"deviceId\":\"keyring-A\",\"observedAt\":\"2026-11-13T23:59:51.000Z\",\"simulation\":true,\"service\":\"Location\",\"sourceType\":\"BLE_RELAY_OBSERVATION\",\"associationId\":\"relay-1\",\"location\":{\"lat\":37.50005,\"lon\":127.00005,\"source\":\"mec\",\"accuracyM\":15}}";
        KeyringLocation point = parse(central("\"keyring-A\"", GPS + "," + relay)).keyrings.get(0);
        assertEquals("relay", point.source); assertTrue(point.simulation); assertFalse(point.bleSimulation);
        assertEquals("BLE_RELAY_OBSERVATION", point.provenance); assertEquals("relay-1", point.relayAssociationId);
        KeyringLocation stale = point.asStale(); assertEquals("stale", stale.status);
        assertTrue(stale.simulation); assertEquals(point.provenance, stale.provenance); assertEquals(point.gatewayId, stale.gatewayId);
        assertEquals(point.rssiDbm, stale.rssiDbm); assertEquals(point.bleObservedAt, stale.bleObservedAt); assertEquals(point.relayAssociationId, stale.relayAssociationId);
        assertSame(stale, stale.asStale());
    }

    @Test public void latestPositionAndSignalAreIndependentAndUnobservedDevicesStayEmpty() throws Exception {
        String older = GPS.replace(OBSERVED, "2026-11-13T23:59:40.000Z").replace("37.5", "38.5");
        String newerSignal = BLE.replace("keyring-B", "keyring-A").replace(OBSERVED, "2026-11-13T23:59:55.000Z");
        LocationSnapshot result = parse(central("\"keyring-A\",\"keyring-unobserved\"", GPS + "," + older + "," + newerSignal));
        assertEquals(37.5, result.keyrings.get(0).latitude, 0); assertEquals(Integer.valueOf(-70), result.keyrings.get(0).rssiDbm);
        assertEquals("unavailable", result.keyrings.get(1).status); assertNull(result.keyrings.get(1).rssiDbm);
        assertFalse(result.keyrings.get(1).hasCoordinates());
        assertTrue(parse(central("", "")).keyrings.isEmpty());
    }

    @Test public void webSnapshotPreservesLabelsAndNeverUsesGatewayAnchorAsKeyringPosition() throws Exception {
        LocationSnapshot result = PortalLocationParser.parseWeb(bytes(web()), NOW, DATE);
        assertEquals("내 키링", result.keyrings.get(0).name); assertTrue(result.keyrings.get(0).hasCoordinates());
        assertFalse(result.keyrings.get(1).hasCoordinates()); assertEquals("gateway-1", result.keyrings.get(1).gatewayId);
        assertEquals("portal-web:portal-api", result.provenance); assertTrue(result.caughtUp); assertEquals(0, result.rejectedRecords);
        String mixed = web().replace("\"accuracyM\":8,\"simulation\":false", "\"accuracyM\":8,\"simulation\":true")
                .replace("\"position\":{\"lat\":37.6,\"lon\":127.1},\"simulation\":false", "\"position\":{\"lat\":37.6,\"lon\":127.1},\"simulation\":true");
        LocationSnapshot simulated = PortalLocationParser.parseWeb(bytes(mixed), NOW, DATE);
        assertTrue(simulated.keyrings.get(0).simulation); assertTrue(simulated.keyrings.get(1).bleSimulation);
        assertFalse(simulated.simulation);
        KeyringLocation relay = PortalLocationParser.parseWeb(bytes(web().replace("\"source\":\"gps\"", "\"source\":\"relay\"")), NOW, DATE).keyrings.get(0);
        assertEquals("relay", relay.source); assertEquals("portal-relay", relay.provenance); assertNull(relay.relayAssociationId);
    }

    @Test public void permissionsExpireIndependentlyFromFreshnessAndNetworkDurationCanOnlyShortenLease() throws Exception {
        LocationSnapshot initial = parse(central());
        assertTrue(initial.keyrings.get(0).isFresh(NOW + 6000)); assertFalse(initial.isReadAuthorized(NOW + 6000));
        assertEquals(NOW + 1500, PortalLocationParser.parseCentral(bytes(central()), NOW, DATE, 2500).readAuthorizedUntilMillis);
        assertEquals(NOW + 4000, PortalLocationParser.parseCentral(bytes(central()), NOW, DATE, 0).readAuthorizedUntilMillis);
        assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseCentral(bytes(central()), NOW, DATE, 4000));
        LocationRepository.ApiException expired = assertThrows(LocationRepository.ApiException.class, () -> parse(central().replace(EXPIRY, "2026-11-14T00:00:00Z")));
        assertEquals(403, expired.statusCode);
        reject(central().replace(EXPIRY, "2026-11-14T00:00:06.000000001Z"));
        reject(central().replace(EXPIRY, "2026-11-14T00:10:00Z"));
    }

    @Test public void invalidHttpDatesClockSkewMissingLeaseAndCalendarNormalizationFailClosed() {
        for (String date : new String[]{null, "", "Sat, 14 Nov 2026 00:01:00 GMT", "Sat, 14 Nov 2026 00:00:00 PST", "Sun, 14 Nov 2026 00:00:00 GMT", "Sat, 14 Nov 2026 00:00:00 GMT\r\nX: y"})
            assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseCentral(bytes(central()), NOW, date));
        assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseCentral(bytes(central()), Long.MAX_VALUE, DATE));
        assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseCentral(bytes(central()), NOW, DATE, -1));
        for (String at : new String[]{"2026-02-29T00:00:00Z", "2026-11-13T24:00:00Z", "2026-11-13T23:59:60Z", "2026-11-14T00:00:05.000000001Z", "2026-11-13T00:00:00Z", "2026-11-12T23:59:59.999Z", "2026-11-13t23:59:50z"})
            reject(central().replace(OBSERVED, at));
        reject(central().replace(",\"readExpiresAt\":\"" + EXPIRY + "\"", ""));
    }

    @Test public void validOffsetNanosecondTimestampsAreNormalizedForNativeViews() throws Exception {
        String offset = "2026-11-14T08:59:50.123456789+09:00";
        KeyringLocation value = parse(central().replace(OBSERVED, offset)).keyrings.get(0);
        assertEquals("2026-11-13T23:59:50.123456789Z", value.observedAt);
        assertTrue(value.isFresh(NOW));
        assertEquals("2026-11-13T23:59:50Z", parse(central().replace(OBSERVED, "2026-11-14T23:58:50+23:59")).keyrings.get(0).observedAt);
    }

    @Test public void exactDecimalBoundsAndSeededMutationCorpusCannotRoundIntoValidity() {
        Random random = new Random(0x504F5254414CL);
        for (int index = 0; index < 512; index++) {
            String changed;
            switch (random.nextInt(8)) {
                case 0: changed = central().replace("\"lat\":37.5", "\"lat\":90.000000000000000000001"); break;
                case 1: changed = central().replace("\"lon\":127", "\"lon\":180.000000000000000000001"); break;
                case 2: changed = central().replace("\"accuracyM\":8", "\"accuracyM\":-1e-400"); break;
                case 3: changed = central().replace("\"accuracyM\":8", "\"accuracyM\":100000.000000000000001"); break;
                case 4: changed = central().replace("\"rssiDbm\":-65", "\"rssiDbm\":-65.000000000000000000001"); break;
                case 5: changed = central().replace("\"lat\":37.5", "\"lat\":\"37.5\""); break;
                case 6: changed = central().replace("\"rssiDbm\":-65", "\"rssiDbm\":-128"); break;
                default: changed = central().replace("\"lat\":37.5", "\"lat\":1e309"); break;
            }
            reject(changed);
        }
    }

    @Test public void schemaIdentityUtf8DuplicateDepthAndOversizeBoundsFailClosed() {
        for (String bad : new String[]{central().replace("\"keyring-B\"", "\"keyring-A\""), central().replace("\"deviceIds\":", "\"extra\":true,\"deviceIds\":"),
                central().replace("\"deviceId\":\"keyring-A\"", "\"deviceId\":\"foreign\""), central().replace("\"lat\":37.5", "\"lat\":91,\"lat\":37.5"),
                central().replace("\"lat\":37.5", "\"lat\":91,\"\\u006cat\":37.5"), central().replace("\"simulation\":false", "\"simulation\":null"),
                central().replace("\"lat\":37.5", "'lat':37.5"), central().replace("\"lat\":37.5", "\"lat\":" + "[".repeat(18) + "0" + "]".repeat(18)),
                central().replace("\"gatewayId\":\"gateway-1\",", ""), central().replace("\"keyring-A\"", "\"keyring\\u0000-A\""), central() + "{}"}) reject(bad);
        reject(central().replace("\"source\":\"gps\"", "\"source\":\"relay\""));
        assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseCentral(new byte[PortalLocationParser.MAX_BODY_BYTES + 1], NOW, DATE));
        byte[] invalidUtf8 = new byte[]{'{','"','x','"',':','"',(byte)0xC0,(byte)0xAF,'"','}'};
        assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseCentral(invalidUtf8, NOW, DATE));
        assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseCentral(null, NOW, DATE));
    }

    @Test public void thousandDevicesFitBoundedParserAndOverflowIsRejected() throws Exception {
        StringBuilder ids = new StringBuilder(), observations = new StringBuilder();
        for (int index = 0; index < 1000; index++) {
            if (index > 0) { ids.append(','); observations.append(','); }
            ids.append('"').append("keyring-").append(index).append('"');
            observations.append(GPS.replace("keyring-A", "keyring-" + index));
        }
        String value = central(ids.toString(), observations.toString());
        assertTrue(bytes(value).length > 65536); assertEquals(1000, parse(value).keyrings.size());
        reject(central(ids + ",\"extra\"", observations.toString()));
    }

    @Test public void thousandWebDevicesFitTheSameExplicitBudget() throws Exception {
        String sample = web(); int start = sample.indexOf("\"devices\":[") + "\"devices\":[".length();
        int next = sample.indexOf(",{\"deviceId\":\"keyring-B\"", start);
        int end = sample.indexOf("],\"gateways\":", next);
        String point = sample.substring(start, next), prefix = sample.substring(0, start), suffix = sample.substring(end);
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < 1000; index++) { if (index > 0) rows.append(','); rows.append(point.replace("keyring-A", "keyring-" + index)); }
        String payload = prefix + rows + suffix;
        assertTrue(bytes(payload).length > 65536);
        assertEquals(1000, PortalLocationParser.parseWeb(bytes(payload), NOW, DATE).keyrings.size());
        String overflow = prefix + rows + ',' + point.replace("keyring-A", "keyring-1000") + suffix;
        assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseWeb(bytes(overflow), NOW, DATE));
    }

    @Test public void demoAndIndividualProvenanceCannotSilentlyBecomeLive() throws Exception {
        String simulated = web().replace("\"mode\":\"live\"", "\"mode\":\"demo\"").replace("\"source\":\"portal-api\"", "\"source\":\"demo-fixture\"").replace("\"simulation\":false", "\"simulation\":true");
        LocationSnapshot result = PortalLocationParser.parseWeb(bytes(simulated), NOW, DATE);
        assertTrue(result.simulation); assertTrue(result.keyrings.get(0).simulation); assertTrue(result.keyrings.get(1).bleSimulation);
        for (String invalid : new String[]{web().replace("\"simulation\":false", "\"simulation\":true").replace("\"accuracyM\":8,\"simulation\":true", "\"accuracyM\":8,\"simulation\":false"), web().replace("\"mode\":\"live\"", "\"mode\":\"demo\""), web().replace("\"position\":null,\"ble\":{", "\"position\":null,\"ble\":null,\"extra\":{")})
            assertThrows(LocationRepository.ApiException.class, () -> PortalLocationParser.parseWeb(bytes(invalid), NOW, DATE));
    }
}
