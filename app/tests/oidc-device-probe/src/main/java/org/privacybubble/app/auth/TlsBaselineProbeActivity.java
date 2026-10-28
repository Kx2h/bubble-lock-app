package org.privacybubble.app.auth;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.net.URI;
import javax.net.ssl.SSLException;

/** Local synthetic endpoints only; no real user, browser, token or permission. */
public final class TlsBaselineProbeActivity extends Activity {
    interface Check { void run() throws Exception; }
    private static void reject(Check check) throws Exception {
        try { check.run(); throw new IllegalStateException("TLS failure accepted"); }
        catch (SSLException expected) { }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this); view.setTextSize(17); view.setPadding(30, 30, 30, 30);
        view.setText("Synthetic OIDC TLS baseline probe running"); setContentView(view);
        new Thread(() -> {
            String result;
            try {
                OidcProvider.discover("https://localhost:18791");
                if (!"{}".equals(OidcTokenTransport.postForm(URI.create("https://localhost:18791/token-baseline"), "synthetic=test"))) throw new IllegalStateException();
                reject(() -> OidcProvider.discover("https://localhost:18792"));
                reject(() -> OidcTokenTransport.postForm(URI.create("https://localhost:18792/token-baseline"), "synthetic=test"));
                reject(() -> OidcProvider.discover("https://localhost:18793"));
                reject(() -> OidcTokenTransport.postForm(URI.create("https://localhost:18793/token-baseline"), "synthetic=test"));
                result = "PASS: OIDC discovery and token endpoints accept TLS 1.3; TLS 1.2 and wrong hostname rejected. Synthetic only.";
            } catch (Exception error) { result = "FAIL: OIDC TLS baseline (" + error.getClass().getSimpleName() + ")"; }
            String message = result; runOnUiThread(() -> view.setText(message));
        }).start();
    }
}
