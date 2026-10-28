package org.privacybubble.app.auth;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** In-memory synthetic public-client session; a failed rotation invalidates all local credentials. */
final class RotatingSession implements AutoCloseable {
    interface Clock { long elapsedMillis(); }
    interface Exchange { Response rotate(String token) throws Exception; }
    static final class Response {
        final String access, refresh, type, scope;
        final long seconds;
        final boolean idTokenPresent;
        Response(String access, String refresh, String type, long seconds, String scope, boolean idTokenPresent) {
            this.access = access; this.refresh = refresh; this.type = type; this.seconds = seconds; this.scope = scope; this.idTokenPresent = idTokenPresent;
        }
    }
    private final Clock clock;
    private final Exchange exchange;
    private final boolean consentScope;
    private String subject, access, refresh;
    private long expires;
    private boolean closed, inFlight;

    RotatingSession(String subject, String access, String refresh, long expires, Clock clock, Exchange exchange) {
        this(subject, access, refresh, expires, clock, exchange, false);
    }
    RotatingSession(String subject, String access, String refresh, long expires, Clock clock, Exchange exchange, boolean consentScope) {
        if (!printable(subject, 512) || !printable(access, 16384) || !printable(refresh, 16384)
                || access.equals(refresh) || clock == null || exchange == null || expires <= clock.elapsedMillis()) throw new IllegalArgumentException("Invalid session");
        this.subject = subject; this.access = access; this.refresh = refresh; this.expires = expires;
        this.clock = clock; this.exchange = exchange; this.consentScope = consentScope;
    }
    synchronized String subject() { requireAccess(); return subject; }
    synchronized String accessToken() { requireAccess(); return access; }
    private void requireAccess() {
        if (closed || inFlight || clock.elapsedMillis() >= expires) throw new IllegalStateException("Session unavailable");
    }
    /** Blocking; never call on the UI thread. A refresh is never retried. */
    void refresh() throws IOException {
        final String previous;
        final long started;
        synchronized (this) {
            if (closed || inFlight) throw new IllegalStateException("Session unavailable");
            inFlight = true;
            previous = refresh;
            started = clock.elapsedMillis();
        }
        try {
            Response value = exchange.rotate(previous);
            if (value == null || !printable(value.access, 16384) || !printable(value.refresh, 16384)
                    || value.refresh.equals(previous) || value.refresh.equals(value.access)
                    || !"bearer".equalsIgnoreCase(value.type) || value.seconds < 1 || value.seconds > 604800
                    || value.idTokenPresent || (value.scope != null && !syntheticScope(value.scope, consentScope))) throw new IllegalArgumentException();
            long deadline = Math.addExact(started, Math.multiplyExact(value.seconds, 1000));
            synchronized (this) {
                if (closed || clock.elapsedMillis() >= deadline) throw new IllegalStateException();
                access = value.access; refresh = value.refresh; expires = deadline; inFlight = false;
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            close();
            // In particular, do not replay the old refresh token after a timeout or malformed response.
            throw new IOException("Session refresh failed");
        } catch (Error fatal) {
            // Never retain a potentially consumed credential if the transport fails fatally.
            close();
            throw fatal;
        }
    }
    @Override public synchronized void close() { closed = true; inFlight = false; subject = null; access = null; refresh = null; }
    @Override public String toString() { return "OidcSession[redacted]"; }
    private static boolean printable(String s, int max) {
        if (s == null || s.isEmpty() || s.length() > max) return false;
        for (int i=0; i<s.length(); i++) if (s.charAt(i) < 33 || s.charAt(i) > 126) return false;
        return true;
    }
    static boolean syntheticScope(String value) { return syntheticScope(value, false); }
    static boolean syntheticScope(String value, boolean consentScope) {
        if (value.isEmpty() || !value.equals(value.trim()) || value.contains("  ")) return false;
        String[] parts = value.split(" ");
        Set<String> scopes = new HashSet<>(Arrays.asList(parts));
        return scopes.size() == parts.length && scopes.contains("openid") && scopes.contains("offline_access")
                && (consentScope ? scopes.size() == 4 && scopes.contains("test.consent.read") && scopes.contains("test.consent.write") : scopes.size() == 2);
    }
}
