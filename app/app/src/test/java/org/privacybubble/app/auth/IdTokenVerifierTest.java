package org.privacybubble.app.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Date;

public class IdTokenVerifierTest {
    private static final String ISSUER = "https://identity.example.test/realm";
    private static final String CLIENT = "mobile-client";
    private static final String NONCE = "random-transaction-nonce";
    private static final long NOW = 1800000000L;
    private static RSAKey key;
    private static RSAKey other;

    @BeforeClass public static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("key-1").generate();
        other = new RSAKeyGenerator(2048).keyID("key-1").generate();
    }
    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("person-1").audience(CLIENT)
                .issueTime(new Date((NOW - 10) * 1000)).expirationTime(new Date((NOW + 120) * 1000))
                .claim("nonce", NONCE);
    }
    private String sign(JWTClaimsSet claims, RSAKey signer, String kid) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }
    private String jwks(RSAKey value) { return new JWKSet(value).toString(); }
    private String verify(String jwt, String jwks) {
        return IdTokenVerifier.verify(jwt, jwks, ISSUER, CLIENT, NONCE, NOW);
    }
    private void reject(JWTClaimsSet claims) throws Exception {
        String jwt = sign(claims, key, "key-1");
        assertThrows(IllegalArgumentException.class, () -> verify(jwt, jwks(key.toPublicJWK())));
    }

    @Test public void acceptsVerifiedTokenAndOptionalMatchingAzp() throws Exception {
        String jwt = sign(claims().claim("azp", CLIENT).build(), key, "key-1");
        assertEquals("person-1", verify(jwt, jwks(key.toPublicJWK())));
    }
    @Test public void rejectsWrongIssuerAudienceAndAzp() throws Exception {
        reject(claims().issuer("https://attacker.example.test").build());
        reject(claims().audience("other").claim("azp", CLIENT).build());
        reject(claims().audience(Arrays.asList(CLIENT, "untrusted")).claim("azp", CLIENT).build());
        reject(claims().claim("azp", "other").build());
        reject(claims().subject("").build());
    }
    @Test public void rejectsMissingWrongOrExpiredClaims() throws Exception {
        reject(claims().claim("nonce", "bad").build());
        reject(claims().claim("nonce", null).build());
        reject(claims().expirationTime(new Date(NOW * 1000)).build());
        reject(claims().expirationTime(null).build());
        reject(claims().issueTime(null).build());
        reject(claims().issueTime(new Date((NOW + 61) * 1000)).build());
        reject(claims().notBeforeTime(new Date((NOW + 1) * 1000)).build());
    }
    @Test public void rejectsWrongSignatureMissingOrDuplicateKeys() throws Exception {
        String jwt = sign(claims().build(), key, "key-1");
        assertThrows(IllegalArgumentException.class, () -> verify(jwt, jwks(other.toPublicJWK())));
        assertThrows(IllegalArgumentException.class, () -> verify(jwt, "{\"keys\":[]}"));
        String duplicate = new JWKSet(Arrays.asList(key.toPublicJWK(), other.toPublicJWK())).toString();
        assertThrows(IllegalArgumentException.class, () -> verify(jwt, duplicate));
        assertThrows(IllegalArgumentException.class, () -> verify(jwt, "bad jwks"));
        assertThrows(IllegalArgumentException.class, () -> verify("not.a.jwt", jwks(key.toPublicJWK())));
    }
    @Test public void rejectsWrongUseAndUntrustedConfiguration() throws Exception {
        RSAKey encryptionOnly = new RSAKey.Builder(key.toPublicJWK()).keyUse(KeyUse.ENCRYPTION).build();
        String jwt = sign(claims().build(), key, "key-1");
        assertThrows(IllegalArgumentException.class, () -> verify(jwt, jwks(encryptionOnly)));
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verify(jwt, jwks(key.toPublicJWK()), "http://identity.example.test", CLIENT, NONCE, NOW));
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verify(jwt, jwks(key.toPublicJWK()), ISSUER, CLIENT, "", NOW));
    }
    @Test public void rejectsAlgorithmConfusionMissingKidAndUntrustedKeyUrl() throws Exception {
        SignedJWT symmetric = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(), claims().build());
        symmetric.sign(new MACSigner(new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> verify(symmetric.serialize(), jwks(key.toPublicJWK())));
        String withoutKid = sign(claims().build(), key, null);
        assertThrows(IllegalArgumentException.class, () -> verify(withoutKid, jwks(key.toPublicJWK())));
        SignedJWT keyUrl = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key-1")
                .jwkURL(new URI("https://attacker.example.test/jwks")).build(), claims().build());
        keyUrl.sign(new RSASSASigner(key));
        assertThrows(IllegalArgumentException.class, () -> verify(keyUrl.serialize(), jwks(key.toPublicJWK())));
    }

}
