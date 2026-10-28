package org.privacybubble.app.api;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONObject;

/** Separate APK: local synthetic TLS only. No real user, token or permission. */
public final class TransportProbeActivity extends Activity {
    private static HttpsApiTransport transport(int port) {
        return new HttpsApiTransport(URI.create("https://localhost:" + port));
    }
    private static HttpsApiTransport.Response get(HttpsApiTransport c, String path) throws IOException {
        return c.request("GET", path, Collections.emptyMap(), null);
    }
    private static void require(boolean ok) { if (!ok) throw new IllegalStateException(); }
    private static void rejectPath(HttpsApiTransport c, String path) throws IOException {
        try { get(c, path); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException();
    }
    private static void rejected(HttpsApiTransport c) throws IOException {
        try { get(c, "/ok"); }
        catch (IOException expected) { return; }
        throw new IllegalStateException();
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView view = new TextView(this); view.setTextSize(17); view.setPadding(30, 30, 30, 30);
        view.setText("Synthetic HTTPS transport probe running"); setContentView(view);
        new Thread(() -> {
            String message;
            try {
                HttpsApiTransport tls = transport(18791);
                rejectPath(tls, "/../admin");
                rejectPath(tls, "/..");
                rejectPath(tls, "/../../admin");
                rejectPath(tls, "/a/%252e%252e/b");
                rejectPath(tls, "/a/%252f/b");
                rejectPath(tls, "/a/%255c/b");
                require(get(tls, "/ok").status() == 200);
                require(tls.request("POST", "/write", Collections.emptyMap(), "synthetic".getBytes(StandardCharsets.UTF_8)).status() == 201);
                require(get(tls, "/redirect").status() == 302);
                require(get(tls, "/denied").status() == 403);
                try { tls.request("POST", "/lost", Collections.emptyMap(), "synthetic".getBytes(StandardCharsets.UTF_8)); throw new IllegalStateException(); }
                catch (IOException expected) { /* outcome unknown, no application retry */ }
                JSONObject counts = new JSONObject(new String(get(tls, "/counts").body(), StandardCharsets.UTF_8));
                require(counts.getInt("writes") == 1 && counts.getInt("lost") == 1 && counts.getInt("redirected") == 0);
                rejected(transport(18792)); // TLS 1.2 only
                rejected(transport(18793)); // correct CA, incorrect DNS name
                message = "PASS: root and double-encoded traversal rejected; TLS 1.3, HTTP responses, redirect not followed, lost write response; TLS 1.2 and wrong hostname rejected. Synthetic only.";
            } catch (Exception error) { message = "FAIL: synthetic transport probe (" + error.getClass().getSimpleName() + ")"; }
            String result = message; runOnUiThread(() -> view.setText(result));
        }).start();
    }
}
