package org.privacybubble.app.auth;

import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.privacybubble.app.net.Tls13SocketFactory;
import org.privacybubble.app.net.StrictJson;

public final class OidcProvider {
    private static final int MAX_BODY_BYTES = 65536;
    private static final long TOTAL_TIMEOUT_NANOS = 10_000_000_000L;
    public final String issuer;
    public final URI authorizationEndpoint;
    public final URI tokenEndpoint;
    public final URI jwksUri;

    private OidcProvider(String issuer, URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri) {
        this.issuer = issuer;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.jwksUri = jwksUri;
    }

    public static OidcProvider discover(String trustedIssuer) throws Exception {
        URI issuerUri = secureUri(trustedIssuer);
        String base = issuerUri.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return parse(trustedIssuer, fetch(base + "/.well-known/openid-configuration"));
    }

    static OidcProvider parse(String trustedIssuer, String metadata) throws Exception {
        secureUri(trustedIssuer);
        if (metadata == null || metadata.length() > MAX_BODY_BYTES) throw new IllegalArgumentException("Invalid discovery document");
        Map<String, Object> doc = StrictJson.object(metadata, MAX_BODY_BYTES);
        if (!trustedIssuer.equals(JSONObjectUtils.getString(doc, "issuer"))) throw new IllegalArgumentException("Issuer mismatch");
        requireCapability(doc, "response_types_supported", "code");
        requireCapability(doc, "id_token_signing_alg_values_supported", "RS256");

        requireCapability(doc, "code_challenge_methods_supported", "S256");
        return new OidcProvider(trustedIssuer,
            secureUri(JSONObjectUtils.getString(doc, "authorization_endpoint")),
            secureUri(JSONObjectUtils.getString(doc, "token_endpoint")),
            secureUri(JSONObjectUtils.getString(doc, "jwks_uri")));
    }

    public String verifyIdToken(String jwt, String clientId, String nonce) throws Exception {
        String keys = fetch(jwksUri.toString());
        return IdTokenVerifier.verify(jwt, keys, issuer, clientId, nonce, System.currentTimeMillis() / 1000);
    }

    @Deprecated
    public String verifyIdToken(String jwt, String clientId, String nonce, long ignoredNowEpochSeconds) throws Exception {
        return verifyIdToken(jwt, clientId, nonce);
    }

    IdTokenVerifier.Identity verifyIdentityToken(String jwt, String clientId, String nonce, String accessToken) throws Exception {
        String keys = fetch(jwksUri.toString());
        return IdTokenVerifier.verifyInitial(jwt, keys, issuer, clientId, nonce, System.currentTimeMillis() / 1000, accessToken);
    }

    void verifyRefreshIdToken(String jwt, IdTokenVerifier.Identity initial, String clientId, long started, String accessToken) throws Exception {
        if (initial == null || !initial.matches(issuer, clientId)) throw new IllegalArgumentException("ID token validation failed");
        String keys = fetch(jwksUri.toString());
        IdTokenVerifier.verifyRefresh(jwt, keys, initial, started, System.currentTimeMillis() / 1000, accessToken);
    }

    void verifyMobileRefresh(String jwt, String clientId, String encryptedStoreContext, long requestedAt, String accessToken) throws Exception {
        IdTokenVerifier.Identity initial=IdTokenVerifier.restoreFromEncryptedStorage(encryptedStoreContext,issuer,clientId);
        IdTokenVerifier.verifyRefresh(jwt,fetch(jwksUri.toString()),initial,requestedAt,System.currentTimeMillis()/1000,accessToken);
    }

    private static void requireCapability(Map<String, Object> doc, String key, String expected) throws Exception {
        List<String> values = JSONObjectUtils.getStringList(doc, key);
        if (values == null || !values.contains(expected)) throw new IllegalArgumentException("Unsupported OIDC provider");
    }

    private static URI secureUri(String value) throws Exception {
        if (value == null || value.isEmpty() || value.length() > 2048 || !value.equals(value.trim())) throw new IllegalArgumentException("Invalid HTTPS URI");
        URI uri = new URI(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid HTTPS URI");
        return uri;
    }

    private static String fetch(String url) throws Exception {
        final long deadline = System.nanoTime() + TOTAL_TIMEOUT_NANOS;
        HttpsURLConnection conn = (HttpsURLConnection) secureUri(url).toURL().openConnection();
        Tls13SocketFactory.configure(conn);
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setUseCaches(false);
        try {
            if (conn.getResponseCode() != 200) throw new IllegalArgumentException("OIDC endpoint unavailable");
            requireWithinDeadline(deadline);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    requireWithinDeadline(deadline);
                    if (out.size() + count > MAX_BODY_BYTES) throw new IllegalArgumentException("OIDC response too large");
                    out.write(buffer, 0, count);
                }
                requireWithinDeadline(deadline);
                return StrictJson.utf8(out.toByteArray(), MAX_BODY_BYTES);
            }
        } finally { conn.disconnect(); }
    }

    private static void requireWithinDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new IllegalArgumentException("OIDC endpoint timed out");
    }
}
