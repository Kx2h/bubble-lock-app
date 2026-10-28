package org.privacybubble.oidcsurrogate;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Separate, synthetic-only external agent; not a general browser or real login. */
public final class SyntheticAgentActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean submitted = new AtomicBoolean();
    private TextView status;
    private Button continueButton;
    private URI authorization;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(30, 30, 30, 30);
        status = new TextView(this); status.setTextSize(18);
        status.setText("Synthetic local authentication test only. No real account or password. Connecting…");
        continueButton = new Button(this); continueButton.setText("Continue with synthetic test identity");
        continueButton.setEnabled(false);
        layout.addView(status); layout.addView(continueButton); setContentView(layout);
        try {
            Uri incoming = getIntent().getData();
            if (!Intent.ACTION_VIEW.equals(getIntent().getAction()) || incoming == null) throw new IllegalArgumentException();
            authorization = SyntheticRequest.validate(incoming.toString());
            continueButton.setOnClickListener(v -> submit());
            worker.execute(() -> {
                try {
                    HttpsURLConnection connection = open(authorization, "GET");
                    try {
                        if (connection.getResponseCode() != 200) throw new IllegalStateException();
                        discardBounded(connection.getInputStream());
                    } finally { connection.disconnect(); }
                    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { status.setText("Synthetic local test only. No real account or password will be requested."); continueButton.setEnabled(true); } });
                } catch (Exception failure) { failed(); }
            });
        } catch (Exception failure) { status.setText("Only the configured local synthetic test issuer is allowed."); }
    }
    private void submit() {
        if (!submitted.compareAndSet(false, true)) return;
        continueButton.setEnabled(false);
        worker.execute(() -> {
            HttpsURLConnection connection = null;
            try {
                URI endpoint = new URI("https://localhost:18443/authorize");
                connection = open(endpoint, "POST");
                byte[] body = authorization.getRawQuery().getBytes(StandardCharsets.UTF_8);
                if (body.length > 8192) throw new IllegalArgumentException();
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                if (connection.getResponseCode() != 303) throw new IllegalStateException();
                String location = connection.getHeaderField("Location");
                if (location == null || location.length() > 8192) throw new IllegalArgumentException();
                URI redirect = new URI(location);
                if (!"org.privacybubble.app".equals(redirect.getScheme()) || redirect.getRawAuthority() != null || !"/oauth2redirect".equals(redirect.getRawPath()) || redirect.getRawQuery() == null || redirect.getRawFragment() != null) throw new IllegalArgumentException();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        try {
                            Intent callback = new Intent(Intent.ACTION_VIEW, Uri.parse(location));
                            callback.addCategory(Intent.CATEGORY_BROWSABLE);
                            callback.setPackage("org.privacybubble.app");
                            startActivity(callback);
                            finish();
                        } catch (RuntimeException failure) { status.setText("Synthetic callback unavailable."); }
                    }
                });
            } catch (Exception failure) { failed(); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }
    private static HttpsURLConnection open(URI uri, String method) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setRequestMethod(method); connection.setUseCaches(false);
        return connection;
    }
    private static void discardBounded(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > 65536) throw new IllegalArgumentException(); out.write(buffer, 0, n); }
        }
    }
    private void failed() { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) status.setText("Synthetic test request failed. No credentials were requested."); }); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
