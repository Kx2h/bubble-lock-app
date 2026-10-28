package org.privacybubble.app.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class OidcProviderTest {
    private static final String ISSUER = "https://issuer.example/realm";
    private String metadata(String issuer, String authorization, String token, String jwks, String pkce) {
        return "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + authorization + "\",\"token_endpoint\":\"" + token + "\",\"jwks_uri\":\"" + jwks + "\",\"response_types_supported\":[\"code\"],\"id_token_signing_alg_values_supported\":[\"RS256\"],\"code_challenge_methods_supported\":[\"" + pkce + "\"]}";
    }
    private String valid() { return metadata(ISSUER, "https://issuer.example/auth", "https://issuer.example/token", "https://issuer.example/keys", "S256"); }
    @Test public void acceptsVerifiedDiscoveryCapabilities() throws Exception {
        OidcProvider p = OidcProvider.parse(ISSUER, valid());
        assertEquals(ISSUER, p.issuer);
        assertEquals("https://issuer.example/keys", p.jwksUri.toString());
    }
    @Test public void rejectsIssuerSubstitutionAndNonHttpsEndpoints() {
        assertThrows(Exception.class, () -> OidcProvider.parse(ISSUER, metadata("https://evil.example", "https://issuer.example/auth", "https://issuer.example/token", "https://issuer.example/keys", "S256")));
        assertThrows(Exception.class, () -> OidcProvider.parse(ISSUER, metadata(ISSUER, "http://issuer.example/auth", "https://issuer.example/token", "https://issuer.example/keys", "S256")));
        assertThrows(Exception.class, () -> OidcProvider.parse(ISSUER, metadata(ISSUER, "https://issuer.example/auth", "https://issuer.example/token", "https://issuer.example/keys?x=1", "S256")));
    }
    @Test public void refusesDowngradedProviderAndMalformedDocument() {
        assertThrows(Exception.class, () -> OidcProvider.parse(ISSUER, metadata(ISSUER, "https://issuer.example/auth", "https://issuer.example/token", "https://issuer.example/keys", "plain")));
        assertThrows(Exception.class, () -> OidcProvider.parse("http://issuer.example", valid()));
        assertThrows(Exception.class, () -> OidcProvider.parse(ISSUER, "{}"));
    }
}
