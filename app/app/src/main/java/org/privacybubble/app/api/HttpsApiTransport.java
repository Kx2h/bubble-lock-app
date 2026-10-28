package org.privacybubble.app.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** HTTPS transport boundary; the caller owns API semantics and trusted-origin provisioning. */
public final class HttpsApiTransport {
    private static final int MAX_BODY = 65536;
    private static final long BUDGET_NANOS = 10_000_000_000L;
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE"));
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList("host", "connection", "content-length", "transfer-encoding", "proxy-authorization"));
    private final URI origin;

    /** Origin must be provisioned by trusted configuration, not by a redirect or a remote response. */
    public HttpsApiTransport(URI origin) {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || origin.getPort() > 65535 || origin.getPort() == 0
                || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))) {
            throw new IllegalArgumentException("Invalid HTTPS origin");
        }
        this.origin = origin;
    }

    /** Performs one application-level attempt. An IOException after a write can leave its outcome unknown. */
    public Response request(String method, String relative, Map<String, String> headers, byte[] body) throws IOException {
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unsupported method");
        if (body != null && (body.length > MAX_BODY || method.equals("GET"))) throw new IllegalArgumentException("Invalid request body");
        URI target = target(relative);
        Map<String, String> checked = validateHeaders(headers);
        long deadline = System.nanoTime() + BUDGET_NANOS;
        HttpsURLConnection connection = (HttpsURLConnection) target.toURL().openConnection();
        connection.setSSLSocketFactory(new Tls13Factory(HttpsURLConnection.getDefaultSSLSocketFactory()));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        for (Map.Entry<String, String> header : checked.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
        try {
            if (body != null || method.equals("POST") || method.equals("PUT")) {
                byte[] payload = body == null ? new byte[0] : body;
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            }
            remainingMillis(deadline);
            int status = connection.getResponseCode();
            remainingMillis(deadline);
            if (status < 100 || status > 599) throw new IOException("Invalid HTTP response");
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] response = readBody(connection, stream, deadline);
            Map<String, String> responseHeaders = new HashMap<>();
            Set<String> repeated = new HashSet<>();
            for (Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null) continue;
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (repeated.contains(name)) continue;
                if (responseHeaders.containsKey(name) || header.getValue() == null || header.getValue().size() != 1 || header.getValue().get(0) == null) {
                    responseHeaders.remove(name); repeated.add(name);
                } else responseHeaders.put(name, header.getValue().get(0));
            }
            return new Response(status, response, responseHeaders);
        } finally { connection.disconnect(); }
    }

    private URI target(String relative) {
        if (relative == null || !relative.startsWith("/") || relative.startsWith("//") || relative.indexOf('\\') >= 0) throw new IllegalArgumentException("Invalid path");
        URI path;
        try { path = URI.create(relative); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid path"); }
        String raw = path.getRawPath();
        if (path.isAbsolute() || path.getRawAuthority() != null || path.getRawFragment() != null || raw == null
                || !path.normalize().equals(path) || raw.toLowerCase(Locale.ROOT).matches(".*%(25|2e|2f|5c|0a|0d).*")) {
            throw new IllegalArgumentException("Invalid path");
        }
        // URI.normalize() retains leading ".." segments instead of removing them.
        // Reject them explicitly even when they cannot change the target origin.
        for (String segment : raw.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Invalid path");
        }
        return origin.resolve(path);
    }

    private static Map<String, String> validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.size() > 32) throw new IllegalArgumentException("Invalid headers");
        Map<String, String> result = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String key = header.getKey(), value = header.getValue();
            if (key == null || !key.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}") || value == null || value.length() > 8192) throw new IllegalArgumentException("Invalid header");
            String normalized = key.toLowerCase(Locale.ROOT);
            if (RESERVED.contains(normalized) || !names.add(normalized)) throw new IllegalArgumentException("Invalid header");
            for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) throw new IllegalArgumentException("Invalid header");
            result.put(key, value);
        }
        return result;
    }

    private static int remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Request interrupted");
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("Request budget exceeded");
        return (int) Math.max(1, (nanos + 999_999) / 1_000_000);
    }

    private static byte[] readBody(HttpsURLConnection connection, InputStream input, long deadline) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while (true) {
                connection.setReadTimeout(Math.min(8000, remainingMillis(deadline)));
                count = in.read(buffer);
                remainingMillis(deadline);
                if (count == -1) break;
                if (count > MAX_BODY - output.size()) throw new IOException("Response too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public static final class Response {
        private final int status;
        private final byte[] body;
        private final Map<String, String> headers;
        private Response(int status, byte[] body, Map<String, String> headers) {
            this.status = status; this.body = body; this.headers = Collections.unmodifiableMap(headers);
        }
        public int status() { return status; }
        public byte[] body() { return body.clone(); }
        /** Returns null for absent or repeated headers. Do not log sensitive values. */
        public String header(String name) { return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT)); }
        @Override public String toString() { return "HttpsApiResponse[redacted]"; }
    }

    /** Restricts the process-default factory to TLS 1.3 without replacing hostname verification. */
    private static final class Tls13Factory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls13Factory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket restrict(Socket socket) throws IOException {
            if (!(socket instanceof SSLSocket)) { socket.close(); throw new SSLException("TLS unavailable"); }
            try { ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.3"}); return socket; }
            catch (IllegalArgumentException e) { socket.close(); throw new SSLException("TLS 1.3 unavailable"); }
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException { return restrict(delegate.createSocket(socket, host, port, autoClose)); }
        @Override public Socket createSocket(String host, int port) throws IOException { return restrict(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return restrict(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return restrict(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return restrict(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket() throws IOException { return restrict(delegate.createSocket()); }
    }
}
