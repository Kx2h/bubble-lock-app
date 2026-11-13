package org.privacybubble.app.location;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.privacybubble.app.api.HttpsApiTransport;
import org.privacybubble.app.net.BearerToken;

public final class PortalLocationRepository {
    public enum Endpoint {
        CENTRAL("/v1/portal/locations"), WEB("/api/portal/locations");
        final String path;
        Endpoint(String path) { this.path = path; }
    }

    interface Exchange { WireResponse get(URI origin, String path, Map<String,String> headers) throws IOException; }
    static final class WireResponse {
        final int status; final byte[] body;
        final String contentType, cacheControl, date, contract;
        WireResponse(int status, byte[] body, String contentType, String cacheControl, String date, String contract) {
            this.status = status; this.body = body == null ? null : body.clone(); this.contentType = contentType;
            this.cacheControl = cacheControl; this.date = date; this.contract = contract;
        }
    }
    private final Endpoint endpoint;
    private final Exchange exchange;
    private final LongSupplier clock, nanoClock;

    public PortalLocationRepository(Endpoint endpoint) {
        this(endpoint, (origin, path, headers) -> {
            HttpsApiTransport.Response result = new HttpsApiTransport(origin, PortalLocationParser.MAX_BODY_BYTES).request("GET", path, headers, null);
            return new WireResponse(result.status(), result.body(), result.header("content-type"), result.header("cache-control"), result.header("date"), result.header("x-privacy-bubble-contract"));
        }, System::currentTimeMillis, System::nanoTime);
    }

    PortalLocationRepository(Endpoint endpoint, Exchange exchange, LongSupplier clock, LongSupplier nanoClock) {
        if (endpoint == null || exchange == null || clock == null || nanoClock == null) throw new IllegalArgumentException("Portal endpoint is required");
        this.endpoint = endpoint; this.exchange = exchange; this.clock = clock; this.nanoClock = nanoClock;
    }

    public LocationSnapshot load(String apiBaseUrl, String accessToken) throws LocationRepository.ApiException {
        if (!BearerToken.isValid(accessToken)) throw new LocationRepository.ApiException(401, "로그인이 필요합니다.");
        try {
            URI origin = new URI(apiBaseUrl == null ? "" : apiBaseUrl);
            if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null || origin.getPort() == 0 || origin.getPort() > 65535
                    || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                    || !origin.getRawAuthority().matches("(?i)" + java.util.regex.Pattern.quote(origin.getHost()) + "(?::[0-9]{1,5})?")) throw new IllegalArgumentException();
            Map<String,String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + accessToken); headers.put("Accept", "application/json"); headers.put("Cache-Control", "no-store");
            long started = nanoClock.getAsLong();
            WireResponse response = exchange.get(origin, endpoint.path, headers);
            long elapsed = nanoClock.getAsLong() - started;
            if (elapsed < 0 || elapsed > 10_000_000_000L) throw new IOException("Portal request budget exceeded");
            if (response.status == 401) throw new LocationRepository.ApiException(401, "로그인이 만료되었습니다. 다시 로그인해 주세요.");
            if (response.status == 403) throw new LocationRepository.ApiException(403, "위치를 확인할 권한이 없습니다.");
            if (response.status != 200) throw new LocationRepository.ApiException(response.status, "위치를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
            if (response.contentType == null || !response.contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?")
                    || response.cacheControl == null || !java.util.Arrays.asList(response.cacheControl.trim().toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("no-store")
                    || endpoint == Endpoint.CENTRAL && !"privacy-bubble.integration.v1".equals(response.contract)) throw new IllegalArgumentException();
            long elapsedMillis = (elapsed + 999_999) / 1_000_000;
            return endpoint == Endpoint.CENTRAL
                    ? PortalLocationParser.parseCentral(response.body, clock.getAsLong(), response.date, elapsedMillis)
                    : PortalLocationParser.parseWeb(response.body, clock.getAsLong(), response.date, elapsedMillis);
        } catch (LocationRepository.ApiException failure) { throw failure; }
        catch (Exception failure) { throw new LocationRepository.ApiException(0, "안전한 위치 연결을 확인할 수 없습니다. 서비스와 휴대폰 시각을 확인해 주세요."); }
    }
}
