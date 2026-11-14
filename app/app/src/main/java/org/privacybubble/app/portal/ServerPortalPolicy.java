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
    public String homeUrl() { return origin + "/#/user/map"; }

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
