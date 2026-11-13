package org.privacybubble.app.location;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.privacybubble.app.net.StrictJson;

public final class PortalLocationParser {
    public static final int MAX_BODY_BYTES = 1_048_576;
    public static final long MAX_READ_LEASE_MILLIS = 5000;
    private static final long DAY = 86_400_000;
    private static final long FRESH_MILLIS = 60_000;
    private static final Pattern TIMESTAMP = Pattern.compile("([0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?)(Z|([+-])([01][0-9]|2[0-3]):([0-5][0-9]))");

    private PortalLocationParser() { }

    public static LocationSnapshot parseCentral(byte[] body, long nowMillis, String responseDate) throws LocationRepository.ApiException {
        return parseCentral(body, nowMillis, responseDate, 0);
    }

    public static LocationSnapshot parseCentral(byte[] body, long nowMillis, String responseDate, long requestElapsedMillis) throws LocationRepository.ApiException {
        try {
            Clock clock = new Clock(nowMillis, responseDate, requestElapsedMillis);
            Map<String,Object> root = document(body);
            keys(root, new String[]{"deviceIds", "observations", "readExpiresAt"});
            Lease lease = clock.lease(root.get("readExpiresAt"));
            List<?> ids = array(root.get("deviceIds"), 1000);
            Map<String,Device> devices = new LinkedHashMap<>();
            for (Object raw : ids) {
                String id = identifier(raw);
                if (devices.put(id, new Device(id, id)) != null) throw invalid();
            }
            List<?> observations = array(root.get("observations"), 2000);
            for (Object raw : observations) {
                Map<String,Object> observation = object(raw);
                keys(observation, new String[]{"deviceId", "observedAt", "simulation"}, "gatewayId", "location", "rssiDbm", "service", "sourceType", "associationId");
                Device device = devices.get(identifier(observation.get("deviceId")));
                if (device == null) throw invalid();
                Instant observed = clock.observationTime(observation.get("observedAt"));
                boolean simulation = bool(observation.get("simulation"));
                String gatewayId = observation.containsKey("gatewayId") ? identifier(observation.get("gatewayId")) : null;
                boolean relay = "BLE_RELAY_OBSERVATION".equals(observation.get("sourceType"));
                if (observation.containsKey("sourceType") && !relay) throw invalid();
                if (observation.containsKey("service") && !"Location".equals(observation.get("service"))) throw invalid();
                String associationId = observation.containsKey("associationId") ? identifier(observation.get("associationId")) : null;
                if (relay != (associationId != null) || (relay && !"Location".equals(observation.get("service")))) throw invalid();
                boolean signal = observation.containsKey("rssiDbm");
                if (signal) {
                    if (gatewayId == null) throw invalid();
                    device.signal(new Signal(gatewayId, integer(observation.get("rssiDbm"), -127, 20), observed, simulation));
                }
                if (observation.containsKey("location")) {
                    Map<String,Object> point = object(observation.get("location"));
                    keys(point, new String[]{"lat", "lon", "source"}, "accuracyM");
                    String source = text(point.get("source"), 16);
                    if (!source.equals("gps") && !source.equals("mec") && !source.equals("relay")) throw invalid();
                    if (relay && !source.equals("mec") && !source.equals("relay") || !relay && source.equals("relay")) throw invalid();
                    device.position(new Position(decimal(point.get("lat"), -90, 90), decimal(point.get("lon"), -180, 180),
                            point.containsKey("accuracyM") ? decimal(point.get("accuracyM"), 0, 100_000) : null,
                            observed, relay ? "relay" : source, simulation, relay ? "BLE_RELAY_OBSERVATION" : "portal-" + source, associationId));
                } else if (!signal || relay) throw invalid();
            }
            List<KeyringLocation> values = new ArrayList<>();
            boolean allSimulation = !observations.isEmpty();
            for (Device device : devices.values()) {
                values.add(device.location(clock));
                if ((device.position != null && !device.position.simulation) || (device.signal != null && !device.signal.simulation)) allSimulation = false;
            }
            return new LocationSnapshot(values, Instant.ofEpochMilli(clock.serverMillis).toString(), lease.until, lease.serverExpiry,
                    "portal-central", allSimulation, true, 0);
        } catch (LocationRepository.ApiException failure) { throw failure; }
        catch (Exception failure) { throw unreadable(); }
    }

    public static LocationSnapshot parseWeb(byte[] body, long nowMillis, String responseDate) throws LocationRepository.ApiException {
        return parseWeb(body, nowMillis, responseDate, 0);
    }

    public static LocationSnapshot parseWeb(byte[] body, long nowMillis, String responseDate, long requestElapsedMillis) throws LocationRepository.ApiException {
        try {
            Clock clock = new Clock(nowMillis, responseDate, requestElapsedMillis);
            Map<String,Object> root = document(body);
            keys(root, new String[]{"schemaVersion", "mode", "simulation", "source", "updatedAt", "readExpiresAt", "devices", "gateways", "rejectedRecords", "caughtUp"});
            if (!"1.0.0".equals(root.get("schemaVersion"))) throw invalid();
            String mode = text(root.get("mode"), 16), source = text(root.get("source"), 32);
            boolean simulation = bool(root.get("simulation"));
            if (!Arrays.asList("live", "demo").contains(mode) || !Arrays.asList("demo-fixture", "local-jsonl", "portal-api", "combined").contains(source)
                    || mode.equals("demo") != source.equals("demo-fixture") || mode.equals("demo") && !simulation) throw invalid();
            Instant updated = timestamp(root.get("updatedAt"));
            if (updated.isAfter(Instant.ofEpochMilli(clock.now + 5000)) || updated.isBefore(Instant.ofEpochMilli(clock.now - DAY))) throw invalid();
            Lease lease = clock.lease(root.get("readExpiresAt"));
            long rejected = exactLong(root.get("rejectedRecords"), 0, 9_007_199_254_740_991L);
            boolean caughtUp = bool(root.get("caughtUp"));
            Map<String,Boolean> gateways = new LinkedHashMap<>();
            for (Object raw : array(root.get("gateways"), 1000)) {
                Map<String,Object> gateway = object(raw);
                keys(gateway, new String[]{"gatewayId", "label", "position", "simulation", "referenceRssiDbm", "pathLossExponent"});
                String id = identifier(gateway.get("gatewayId")); text(gateway.get("label"), 128);
                Map<String,Object> coordinates = object(gateway.get("position"));
                keys(coordinates, new String[]{"lat", "lon"});
                decimal(coordinates.get("lat"), -90, 90); decimal(coordinates.get("lon"), -180, 180);
                decimal(gateway.get("referenceRssiDbm"), -127, -1); decimal(gateway.get("pathLossExponent"), 1, 6);
                if (gateways.put(id, bool(gateway.get("simulation"))) != null) throw invalid();
            }
            Set<String> ids = new HashSet<>(); List<KeyringLocation> values = new ArrayList<>();
            for (Object raw : array(root.get("devices"), 1000)) {
                Map<String,Object> item = object(raw);
                keys(item, new String[]{"deviceId", "label", "position", "ble"});
                String id = identifier(item.get("deviceId"));
                if (!ids.add(id)) throw invalid();
                Device device = new Device(id, text(item.get("label"), 128));
                if (item.get("position") != null) {
                    Map<String,Object> position = object(item.get("position"));
                    keys(position, new String[]{"coordinates", "source", "observedAt", "accuracyM", "simulation"});
                    Map<String,Object> coordinates = object(position.get("coordinates"));
                    keys(coordinates, new String[]{"lat", "lon"});
                    String positionSource = text(position.get("source"), 16);
                    if (!Arrays.asList("gps", "mec", "relay").contains(positionSource)) throw invalid();
                    boolean positionSimulation = bool(position.get("simulation"));
                    if (simulation && !positionSimulation) throw invalid();
                    device.position(new Position(decimal(coordinates.get("lat"), -90, 90), decimal(coordinates.get("lon"), -180, 180),
                            position.get("accuracyM") == null ? null : decimal(position.get("accuracyM"), 0, 100_000),
                            clock.observationTime(position.get("observedAt")), positionSource, positionSimulation,
                            "portal-" + positionSource, null));
                }
                if (item.get("ble") != null) {
                    Map<String,Object> signal = object(item.get("ble"));
                    keys(signal, new String[]{"gatewayId", "rssiDbm", "observedAt", "simulation"});
                    String gatewayId = identifier(signal.get("gatewayId"));
                    boolean signalSimulation = bool(signal.get("simulation"));
                    if (simulation && !signalSimulation) throw invalid();
                    device.signal(new Signal(gatewayId, integer(signal.get("rssiDbm"), -127, 20),
                            clock.observationTime(signal.get("observedAt")), signalSimulation || Boolean.TRUE.equals(gateways.get(gatewayId))));
                }
                if (device.position == null && device.signal == null) throw invalid();
                values.add(device.location(clock));
            }
            return new LocationSnapshot(values, updated.toString(), lease.until, lease.serverExpiry, "portal-web:" + source,
                    simulation, caughtUp, rejected);
        } catch (LocationRepository.ApiException failure) { throw failure; }
        catch (Exception failure) { throw unreadable(); }
    }

    private static Map<String,Object> document(byte[] body) {
        return StrictJson.object(StrictJson.utf8(body, MAX_BODY_BYTES), MAX_BODY_BYTES, 65_536);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (!(value instanceof Map)) throw invalid();
        return (Map<String,Object>)value;
    }

    private static List<?> array(Object value, int maximum) {
        if (!(value instanceof List) || ((List<?>)value).size() > maximum) throw invalid();
        return (List<?>)value;
    }

    private static void keys(Map<String,Object> value, String[] required, String... optional) {
        Set<String> allowed = new HashSet<>(Arrays.asList(required));
        allowed.addAll(Arrays.asList(optional));
        if (!value.keySet().containsAll(Arrays.asList(required)) || !allowed.containsAll(value.keySet())) throw invalid();
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String)) throw invalid();
        String string = (String)value;
        if (string.isBlank() || string.length() > maximum || !string.equals(string.trim())) throw invalid();
        for (int index = 0; index < string.length(); index++) if (string.charAt(index) < 32 || string.charAt(index) == 127) throw invalid();
        return string;
    }

    private static String identifier(Object value) {
        String id = text(value, 128);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw invalid();
        return id;
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean)) throw invalid();
        return (Boolean)value;
    }

    private static Double decimal(Object value, long minimum, long maximum) {
        if (!(value instanceof BigDecimal)) throw invalid();
        BigDecimal number = (BigDecimal)value;
        if (number.compareTo(BigDecimal.valueOf(minimum)) < 0 || number.compareTo(BigDecimal.valueOf(maximum)) > 0) throw invalid();
        double result = number.doubleValue();
        if (!Double.isFinite(result)) throw invalid();
        return result;
    }

    private static int integer(Object value, int minimum, int maximum) {
        return (int)exactLong(value, minimum, maximum);
    }

    private static long exactLong(Object value, long minimum, long maximum) {
        if (!(value instanceof BigDecimal)) throw invalid();
        long result = ((BigDecimal)value).longValueExact();
        if (result < minimum || result > maximum) throw invalid();
        return result;
    }

    private static Instant timestamp(Object value) {
        String text = text(value, 64);
        Matcher parts = TIMESTAMP.matcher(text);
        if (!parts.matches()) throw invalid();
        LocalDateTime local = LocalDateTime.parse(parts.group(1), DateTimeFormatter.ISO_LOCAL_DATE_TIME.withResolverStyle(ResolverStyle.STRICT));
        Instant instant = local.toInstant(ZoneOffset.UTC);
        if (!parts.group(2).equals("Z")) {
            long offset = (Long.parseLong(parts.group(4)) * 60 + Long.parseLong(parts.group(5))) * 60;
            instant = parts.group(3).equals("+") ? instant.minusSeconds(offset) : instant.plusSeconds(offset);
        }
        return instant;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid portal location response"); }
    private static LocationRepository.ApiException unreadable() { return new LocationRepository.ApiException(0, "위치 응답을 확인할 수 없습니다."); }

    private static final class Clock {
        final long now, serverMillis, requestElapsed;
        Clock(long now, String date, long requestElapsed) throws LocationRepository.ApiException {
            if (now < DAY || now > 253_402_300_799_000L || requestElapsed < 0 || requestElapsed > 10_000
                    || date == null || date.length() > 64 || !date.matches("[A-Z][a-z]{2}, [0-9]{1,2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")) throw invalid();
            this.now = now; this.requestElapsed = requestElapsed;
            serverMillis = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant().toEpochMilli();
            if (Math.abs(serverMillis - now) > 30_000) throw new LocationRepository.ApiException(0, "휴대폰 시각과 서비스 연결을 확인해 주세요.");
        }
        Lease lease(Object value) throws LocationRepository.ApiException {
            Instant expiry = timestamp(value); long until = expiry.toEpochMilli();
            if (until <= now) throw new LocationRepository.ApiException(403, "위치 조회 승인이 만료되었습니다. 다시 확인해 주세요.");
            long advertised = until - serverMillis;
            if (advertised <= 0 || expiry.isAfter(Instant.ofEpochMilli(serverMillis + 6000))) throw invalid();
            long remaining = Math.min(MAX_READ_LEASE_MILLIS, Math.min(until - now, advertised - 1000 - requestElapsed));
            if (remaining <= 0) throw new LocationRepository.ApiException(403, "위치 조회 승인이 만료되었습니다. 다시 확인해 주세요.");
            return new Lease(Math.addExact(now, remaining), expiry.toString());
        }
        Instant observationTime(Object value) {
            Instant observed = timestamp(value);
            if (observed.isAfter(Instant.ofEpochMilli(now + 5000)) || !observed.isAfter(Instant.ofEpochMilli(now - DAY))) throw invalid();
            return observed;
        }
    }

    private static final class Lease {
        final long until; final String serverExpiry;
        Lease(long until, String serverExpiry) { this.until = until; this.serverExpiry = serverExpiry; }
    }

    private static final class Position {
        final double latitude, longitude; final Double accuracy;
        final Instant observed; final String source, provenance, associationId; final boolean simulation;
        Position(double latitude, double longitude, Double accuracy, Instant observed, String source, boolean simulation, String provenance, String associationId) {
            this.latitude = latitude; this.longitude = longitude; this.accuracy = accuracy; this.observed = observed;
            this.source = source; this.simulation = simulation; this.provenance = provenance; this.associationId = associationId;
        }
    }

    private static final class Signal {
        final String gatewayId; final int rssi; final Instant observed; final boolean simulation;
        Signal(String gatewayId, int rssi, Instant observed, boolean simulation) {
            this.gatewayId = gatewayId; this.rssi = rssi; this.observed = observed; this.simulation = simulation;
        }
    }

    private static final class Device {
        final String id, label; Position position; Signal signal;
        Device(String id, String label) { this.id = id; this.label = label; }
        void position(Position next) { if (position == null || !next.observed.isBefore(position.observed)) position = next; }
        void signal(Signal next) { if (signal == null || !next.observed.isBefore(signal.observed)) signal = next; }
        KeyringLocation location(Clock clock) {
            String observed = position == null ? null : position.observed.toString();
            String expiry = position == null ? null : position.observed.plusMillis(FRESH_MILLIS).toString();
            String status = position == null ? "unavailable" : position.observed.toEpochMilli() + FRESH_MILLIS > clock.now ? "available" : "stale";
            return new KeyringLocation(id, label, status, position == null ? null : position.latitude, position == null ? null : position.longitude,
                    position == null ? null : position.accuracy, observed, null, expiry, position == null ? "unknown" : position.source,
                    null, null, position != null && position.simulation, position == null ? signal == null ? "portal-unobserved" : "portal-ble" : position.provenance,
                    signal == null ? null : signal.gatewayId, signal == null ? null : signal.rssi,
                    signal == null ? null : signal.observed.toString(), signal != null && signal.simulation,
                    position == null ? null : position.associationId);
        }
    }
}
