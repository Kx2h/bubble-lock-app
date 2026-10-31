package org.privacybubble.app.location;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.privacybubble.app.api.HttpsApiTransport;

/** Read-only mobile API caller. It never sends a credential to a redirect or caches location. */
public final class LocationRepository {
    private static final int MAX_BODY = 65_536;
    private static final String CONTRACT = "privacy-bubble.mobile.v1";
    private final boolean allowDebugLoopback;

    public LocationRepository(boolean allowDebugLoopback) { this.allowDebugLoopback = allowDebugLoopback; }

    public LocationSnapshot load(String apiBaseUrl, String accessToken) throws ApiException {
        if (accessToken == null || !accessToken.matches("[A-Za-z0-9_.-]{1,8192}")) throw new ApiException(401, "로그인이 필요합니다.");
        try {
            URI base = new URI(apiBaseUrl == null ? "" : apiBaseUrl.trim());
            String path = base.getRawPath();
            if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || base.getPort() == 0 || base.getPort() > 65535 || !(path.isEmpty() || path.equals("/") || path.equals("/v1") || path.equals("/v1/"))) throw new IllegalArgumentException();
            URI origin = new URI(base.getScheme().toLowerCase(Locale.ROOT), null, base.getHost(), base.getPort(), "", null, null);
            byte[] body;
            if ("https".equalsIgnoreCase(base.getScheme())) {
                Map<String, String> headers = headers(accessToken);
                HttpsApiTransport.Response response = new HttpsApiTransport(origin).request("GET", "/v1/keyrings", headers, null);
                checkStatus(response.status());
                checkHeaders(response.header("content-type"), response.header("x-privacy-bubble-contract"));
                body = response.body();
            } else if (allowDebugLoopback && "http".equalsIgnoreCase(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() == 8787) {
                body = loopback(origin.resolve("/v1/keyrings"), accessToken);
            } else throw new IllegalArgumentException();
            return parse(body, System.currentTimeMillis());
        } catch (ApiException error) { throw error; }
        catch (IllegalArgumentException | java.net.URISyntaxException error) { throw new ApiException(0, "올바른 HTTPS 서비스 주소를 확인해 주세요."); }
        catch (Exception error) { throw new ApiException(0, "위치를 불러오지 못했습니다. 연결을 확인하고 다시 시도해 주세요."); }
    }

    private static Map<String, String> headers(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token); headers.put("Accept", "application/json");
        headers.put("Cache-Control", "no-store");
        return headers;
    }

    private static void checkStatus(int status) throws ApiException {
        if (status == 401) throw new ApiException(401, "로그인이 만료되었습니다. 다시 로그인해 주세요.");
        if (status == 403) throw new ApiException(403, "위치를 확인할 권한이 없습니다.");
        if (status != 200) throw new ApiException(status, "위치를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static void checkHeaders(String contentType, String contract) throws ApiException {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?") || !CONTRACT.equals(contract)) throw new ApiException(0, "서비스 응답 형식을 확인할 수 없습니다.");
    }

    private static byte[] loopback(URI target, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
        connection.setUseCaches(false); connection.setRequestMethod("GET");
        for (Map.Entry<String, String> header : headers(token).entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
        long deadline = System.nanoTime() + 10_000_000_000L;
        try {
            checkStatus(connection.getResponseCode());
            checkHeaders(connection.getHeaderField("content-type"), connection.getHeaderField("x-privacy-bubble-contract"));
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int read;
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("Read budget exceeded");
                    connection.setReadTimeout((int)Math.min(8000, Math.max(1, remaining / 1_000_000)));
                    read = input.read(buffer);
                    if (System.nanoTime() > deadline) throw new IOException("Read budget exceeded");
                    if (read == -1) return output.toByteArray();
                    if (read > MAX_BODY - output.size()) throw new IOException("Response too large");
                    output.write(buffer, 0, read);
                }
            }
        } finally { connection.disconnect(); }
    }

    /** Public to permit deterministic schema tests without network or an Android Activity. */
    public static LocationSnapshot parse(byte[] payload, long nowMillis) throws ApiException {
        try {
            if (payload == null || payload.length > MAX_BODY) throw new IllegalArgumentException();
            String document = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
            checkDepth(document);
            JSONTokener tokener = new JSONTokener(document);
            Object decoded = tokener.nextValue();
            if (!(decoded instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException();
            JSONObject root = (JSONObject)decoded;
            checkKeys(root, "contractId", "docRevision", "generatedAt", "keyrings");
            if (!CONTRACT.equals(string(root, "contractId", 64)) || !"2.0.0".equals(string(root, "docRevision", 16))) throw new IllegalArgumentException();
            String generatedAt = string(root, "generatedAt", 48);
            long generated = timestamp(generatedAt);
            if (generated > nowMillis + 30_000 || generated < nowMillis - 86_400_000) throw new IllegalArgumentException();
            Object rawList = root.opt("keyrings");
            if (!(rawList instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray array = (JSONArray)rawList;
            if (array.length() > 100) throw new IllegalArgumentException();
            List<KeyringLocation> items = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                Object raw = array.get(index);
                if (!(raw instanceof JSONObject)) throw new IllegalArgumentException();
                JSONObject item = (JSONObject)raw;
                checkKeys(item, "id", "name", "status", "latitude", "longitude", "accuracyMeters", "observedAt", "receivedAt", "expiresAt", "source", "connectivity", "batteryPercent");
                String id = string(item, "id", 96), name = string(item, "name", 96), status = string(item, "status", 16);
                String source = string(item, "source", 32), connectivity = string(item, "connectivity", 16);
                if (!ids.add(id) || !(status.equals("available") || status.equals("stale") || status.equals("unavailable") || status.equals("denied"))
                        || !(connectivity.equals("online") || connectivity.equals("offline") || connectivity.equals("unknown"))) throw new IllegalArgumentException();
                Double latitude = number(item, "latitude"), longitude = number(item, "longitude"), accuracy = number(item, "accuracyMeters"), rawBattery = number(item, "batteryPercent");
                Integer battery = null;
                if (rawBattery != null) {
                    if (rawBattery < 0 || rawBattery > 100 || rawBattery != Math.floor(rawBattery)) throw new IllegalArgumentException();
                    battery = rawBattery.intValue();
                }
                String observedAt = nullableString(item, "observedAt"), receivedAt = nullableString(item, "receivedAt"), expiresAt = nullableString(item, "expiresAt");
                if (status.equals("available") || status.equals("stale")) {
                    if (latitude == null || longitude == null || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                            || (accuracy != null && (accuracy < 0 || accuracy > 10_000_000)) || !source.equals("mec_location")) throw new IllegalArgumentException();
                    long observed = timestamp(observedAt), received = timestamp(receivedAt), expires = timestamp(expiresAt);
                    if (observed > generated + 30_000 || received > generated + 30_000 || observed > received + 30_000 || expires < observed
                            || (status.equals("stale") && expires > generated)) throw new IllegalArgumentException();
                    if (expires <= nowMillis) status = "stale";
                } else if (latitude != null || longitude != null || accuracy != null || observedAt != null || receivedAt != null || expiresAt != null || !source.equals("unknown")) throw new IllegalArgumentException();
                items.add(new KeyringLocation(id, name, status, latitude, longitude, accuracy, observedAt, receivedAt, expiresAt, source, connectivity, battery));
            }
            return new LocationSnapshot(items, generatedAt);
        } catch (Exception error) { throw new ApiException(0, "위치 응답을 확인할 수 없습니다."); }
    }

    private static void checkDepth(String text) {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (quoted) {
                if (c < 32) throw new IllegalArgumentException();
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c < 32 && c != '\t' && c != '\n' && c != '\r') throw new IllegalArgumentException();
            else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 8) throw new IllegalArgumentException(); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new IllegalArgumentException(); }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException();
    }

    private static void checkKeys(JSONObject object, String... expected) {
        Set<String> keys = new HashSet<>(Arrays.asList(expected));
        if (object.length() != keys.size()) throw new IllegalArgumentException();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) if (!keys.contains(iterator.next())) throw new IllegalArgumentException();
    }

    private static String string(JSONObject object, String key, int max) {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException();
        String text = (String)value;
        if (text.isEmpty() || text.length() > max || !text.equals(text.trim())) throw new IllegalArgumentException();
        for (int index = 0; index < text.length(); index++) if (text.charAt(index) < 32 || text.charAt(index) == 127) throw new IllegalArgumentException();
        return text;
    }
    private static String nullableString(JSONObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException();
        return object.isNull(key) ? null : string(object, key, 48);
    }
    private static Double number(JSONObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException();
        if (object.isNull(key)) return null;
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException();
        double number = ((Number)value).doubleValue();
        if (!Double.isFinite(number)) throw new IllegalArgumentException();
        return number;
    }
    private static long timestamp(String value) { return Instant.parse(value).toEpochMilli(); }

    public static final class ApiException extends IOException {
        public final int statusCode;
        public ApiException(int statusCode, String message) { super(message); this.statusCode = statusCode; }
    }
}
