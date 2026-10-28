package org.privacybubble.app.auth;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.privacybubble.app.net.Tls13SocketFactory;

/** Single, bounded token endpoint attempt; caller must not replay an uncertain exchange. */
final class OidcTokenTransport {
    private static final int MAX_BODY = 65536;
    private OidcTokenTransport() { }

    static String postForm(URI endpoint, String form) throws Exception {
        byte[] body = form.getBytes(StandardCharsets.UTF_8);
        final long deadline = System.nanoTime() + 10_000_000_000L;
        HttpsURLConnection conn = (HttpsURLConnection) endpoint.toURL().openConnection();
        Tls13SocketFactory.configure(conn);
        conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
        conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json"); conn.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            if (conn.getResponseCode() != 200 || System.nanoTime() - deadline >= 0) throw new IllegalArgumentException("Token exchange failed");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (System.nanoTime() - deadline >= 0 || out.size() + n > MAX_BODY) throw new IllegalArgumentException("Invalid token response");
                    out.write(buffer, 0, n);
                }
                if (System.nanoTime() - deadline >= 0) throw new IllegalArgumentException("Token exchange timed out");
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { conn.disconnect(); }
    }
}
