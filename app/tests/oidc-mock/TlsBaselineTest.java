package org.privacybubble.app.auth;

import java.net.URI;
import javax.net.ssl.SSLException;

/** Host test against separately configured TLS 1.2-only and TLS 1.3-only servers. */
public final class TlsBaselineTest {
    interface Check { void run() throws Exception; }
    private static void rejectsTls12(Check check) throws Exception {
        try { check.run(); throw new AssertionError("TLS 1.2 accepted"); }
        catch (SSLException expected) { }
    }
    public static void main(String[] args) throws Exception {
        rejectsTls12(() -> OidcProvider.discover(args[0]));
        rejectsTls12(() -> OidcTokenTransport.postForm(URI.create(args[0] + "/token"), "synthetic=test"));
        String response = OidcTokenTransport.postForm(URI.create(args[1] + "/token"), "synthetic=test");
        if (!"{}".equals(response)) throw new AssertionError("TLS 1.3 token endpoint failed");
        System.out.println("OIDC TLS baseline passed");
    }
}
