package org.privacybubble.app.portal;

import java.net.URI;
import java.util.Locale;

/** Navigation boundary for the configured first-party BFF web portal. */
public final class ServerPortalPolicy {
    private static final int MAX_URL_LENGTH = 16_384;
    private final String origin;

    public ServerPortalPolicy(String configuredOrigin) {
        URI address = address(configuredOrigin);
        if (address == null || !(address.getRawPath().isEmpty() || "/".equals(address.getRawPath()))
                || address.getRawQuery() != null || address.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid server portal origin");
        }
        origin = origin(address);
    }

    public String origin() { return origin; }
    public String homeUrl() { return origin + "/#/user/overview"; }

    /**
     * A small, non-sensitive route hint for in-memory resume only. Never retain
     * an auth redirect, query, arbitrary fragment, document state, or location.
     * Route membership is not an authorization decision; the BFF owns access.
     */
    public String restoreUrl(String url) {
        URI address = address(url);
        if (address == null || !origin.equals(origin(address))) return null;
        String route = pageRoute(address);
        return route == null ? null : origin + "/#" + route;
    }

    /** A fixed product label, never the document title or an untrusted URL. */
    public String navigationTitle(String url) {
        URI address = address(url);
        if (address == null || !origin.equals(origin(address))) return "BubbleLock";
        String path = address.getRawPath();
        if (path.startsWith("/identity/") || "/api/auth/login".equals(path)
                || "/api/auth/callback".equals(path)) return "로그인";
        String route = pageRoute(address);
        String title = route == null ? null : routeTitle(route);
        return title == null ? "BubbleLock" : title;
    }

    /** Follow the system's text preference without using a CSS viewport zoom. */
    public static int webTextZoom(float fontScale) {
        if (!Float.isFinite(fontScale) || fontScale <= 0) return 100;
        return Math.round(Math.max(0.5f, Math.min(3.0f, fontScale)) * 100);
    }

    public boolean isInternal(String url) {
        URI address = address(url);
        return address != null && origin.equals(origin(address));
    }

    /** The caller must additionally require a main-frame user gesture. */
    public boolean isExternalBrowserCandidate(String url) {
        URI address = address(url);
        return address != null && !origin.equals(origin(address));
    }

    public boolean isLogout(String url) {
        URI address = address(url);
        return address != null && origin.equals(origin(address))
                && "/api/auth/logout".equals(address.getRawPath())
                && address.getRawQuery() == null && address.getRawFragment() == null;
    }

    /** One inert packaged stylesheet; never intercept API or identity resources. */
    public boolean isAppStylesheet(String url) {
        URI address = address(url);
        return address != null && origin.equals(origin(address))
                && "/__bubble_android__/portal-mobile.css".equals(address.getRawPath())
                && address.getRawQuery() == null && address.getRawFragment() == null;
    }

    private static String pageRoute(URI address) {
        String path = address.getRawPath();
        if (!(path.isEmpty() || "/".equals(path) || "/index.html".equals(path))
                || address.getRawQuery() != null) return null;
        String route = address.getRawFragment();
        if (route == null || route.isEmpty()) route = "/";
        return routeTitle(route) == null ? null : route;
    }

    private static String routeTitle(String route) {
        switch (route) {
            case "/": return "BubbleLock";
            case "/user/overview": return "내 대시보드";
            case "/user/map": return "키링 위치";
            case "/user/devices": return "가족과 키링";
            case "/user/privacy": return "공유 권한";
            case "/user/activity": return "알림과 요청";
            case "/operator/overview": return "한눈에 보기";
            case "/operator/map": return "위치 지도";
            case "/operator/devices": return "장치와 자원";
            case "/operator/commands": return "명령 처리";
            case "/operator/audit": return "활동 기록";
            case "/operator/services": return "연결 서비스";
            case "/operator/evidence": return "개발 현황";
            default: return null;
        }
    }

    private static String origin(URI address) {
        int port = address.getPort();
        return "https://" + address.getHost().toLowerCase(Locale.ROOT)
                + (port == -1 || port == 443 ? "" : ":" + port);
    }

    private static URI address(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH) return null;
        for (int index = 0; index < value.length(); index++) {
            char letter = value.charAt(index);
            if (letter <= 32 || letter == 127 || letter == '\\') return null;
            if (letter == '%' && index + 2 < value.length()) {
                int first = Character.digit(value.charAt(index + 1), 16);
                int second = Character.digit(value.charAt(index + 2), 16);
                if (first >= 0 && second >= 0) {
                    int decoded = first * 16 + second;
                    if (decoded < 32 || decoded == 127 || decoded == '\\') return null;
                }
            }
        }
        try {
            URI address = new URI(value);
            String host = address.getHost();
            int port = address.getPort();
            if (!"https".equalsIgnoreCase(address.getScheme()) || address.isOpaque() || host == null
                    || address.getRawUserInfo() != null || address.getRawAuthority() == null || address.getRawPath() == null
                    || port == 0 || port > 65535 || !publicDnsName(host)
                    || !address.getRawAuthority().equalsIgnoreCase(host + (port == -1 ? "" : ":" + port))) return null;
            return address;
        } catch (Exception failure) { return null; }
    }

    private static boolean publicDnsName(String value) {
        String host = value.toLowerCase(Locale.ROOT);
        if (host.length() > 253 || !host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
                || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".localdomain")
                || host.endsWith(".internal") || host.endsWith(".home.arpa")) return false;
        String[] labels = host.split("\\.");
        for (String label : labels) {
            if (label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return false;
        }
        return labels[labels.length - 1].matches("[a-z][a-z0-9-]*");
    }
}
