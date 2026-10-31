package org.privacybubble.app.session;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ServiceOrigin {
    private ServiceOrigin() { }

    public static String normalize(String value, boolean debug) {
        try {
            if (value == null || value.isBlank() || value.length() > 2048 || !value.equals(value.trim())) throw new Exception();
            URI uri = new URI(value);
            boolean loopback = debug && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 8787;
            if ((!"https".equals(uri.getScheme()) && !loopback) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getRawPath() != null && !uri.getRawPath().matches("/?"))
                    || !uri.getRawAuthority().matches("(?i)" + Pattern.quote(uri.getHost()) + "(?::[0-9]{1,5})?")) throw new Exception();
            int port = "https".equals(uri.getScheme()) && uri.getPort() == 443 ? -1 : uri.getPort();
            return new URI(uri.getScheme(), null, uri.getHost().toLowerCase(Locale.ROOT), port, null, null, null).toString();
        } catch (Exception invalid) { throw new IllegalArgumentException("HTTPS 서비스 주소를 확인해 주세요."); }
    }

    public static String effective(String provisioned, String saved, boolean debug) {
        String selected = debug && saved != null ? saved : provisioned;
        return selected == null || selected.isEmpty() ? "" : normalize(selected, debug);
    }
}
