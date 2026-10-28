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
    @Test public void subjectMustBeAtMost255AsciiCharacters() throws Exception {
        String subject = "Aa" + "x".repeat(253);
        assertEquals(subject, verify(sign(claims().subject(subject).build(), key, "key-1"), jwks(key.toPublicJWK())));
        reject(claims().subject(subject + "x").build());
        reject(claims().subject("subject-한글").build());
        reject(claims().subject("subject-\ud83d\ude00").build());
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
    @Test public void rejectsExplicitNullNotBeforeOnInitialAndRefresh() throws Exception {
        IdTokenVerifier.Identity initial = identity(claims().build());
        for (boolean initialToken : new boolean[]{true, false}) {
            String payload = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"person-1\",\"aud\":\"" + CLIENT
                    + "\",\"iat\":" + NOW + ",\"exp\":" + (NOW + 120)
                    + (initialToken ? ",\"nonce\":\"" + NONCE + "\"" : "") + ",\"nbf\":null}";
            com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key-1").build(), new com.nimbusds.jose.Payload(payload));
            jws.sign(new RSASSASigner(key));
            String signed = jws.serialize(), keys = jwks(key.toPublicJWK());
            if (initialToken) assertThrows(IllegalArgumentException.class, () -> verify(signed, keys));
            else assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(signed, keys, initial, NOW, NOW));
        }
        // The optional claim may still be omitted or contain a valid past time.
        assertEquals("person-1", verify(sign(claims().build(), key, "key-1"), jwks(key.toPublicJWK())));
        assertEquals("person-1", verify(sign(claims().notBeforeTime(new Date((NOW - 1) * 1000)).build(), key, "key-1"), jwks(key.toPublicJWK())));
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

    private JWTClaimsSet.Builder refreshed() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("person-1").audience(CLIENT)
                .issueTime(new Date(NOW * 1000)).expirationTime(new Date((NOW + 120) * 1000));
    }
    private IdTokenVerifier.Identity identity(JWTClaimsSet initial) throws Exception {
        return IdTokenVerifier.verifyInitial(sign(initial, key, "key-1"), jwks(key.toPublicJWK()), ISSUER, CLIENT, NONCE, NOW);
    }
    private void verifyRefresh(JWTClaimsSet value, IdTokenVerifier.Identity identity) throws Exception {
        IdTokenVerifier.verifyRefresh(sign(value, key, "key-1"), jwks(key.toPublicJWK()), identity, NOW, NOW);
    }
    private void rejectRefresh(JWTClaimsSet value, IdTokenVerifier.Identity identity) throws Exception {
        String jwt = sign(value, key, "key-1");
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(jwt, jwks(key.toPublicJWK()), identity, NOW, NOW));
    }
    @Test public void accessTokenHashBindsInitialAndRefreshedResponses() throws Exception {
        // Independently calculated SHA-256 left half for ASCII "access-123".
        String hash = "p8W_jVG3TsPtAC2vLCgoSQ";
        String jwks = jwks(key.toPublicJWK());
        String initialJwt = sign(claims().claim("at_hash", hash).build(), key, "key-1");
        IdTokenVerifier.Identity initial = IdTokenVerifier.verifyInitial(initialJwt, jwks, ISSUER, CLIENT, NONCE, NOW, "access-123");
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyInitial(initialJwt, jwks, ISSUER, CLIENT, NONCE, NOW, "other"));
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyInitial(initialJwt, jwks, ISSUER, CLIENT, NONCE, NOW));
        String refreshedJwt = sign(refreshed().claim("at_hash", hash).build(), key, "key-1");
        IdTokenVerifier.verifyRefresh(refreshedJwt, jwks, initial, NOW, NOW, "access-123");
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(refreshedJwt, jwks, initial, NOW, NOW, "other"));
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(refreshedJwt, jwks, initial, NOW, NOW));
        for (Object bad : new Object[]{42, "", hash + "=", "wrong"}) {
            String jwt = sign(claims().claim("at_hash", bad).build(), key, "key-1");
            assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyInitial(jwt, jwks, ISSUER, CLIENT, NONCE, NOW, "access-123"));
        }
        String payload = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"person-1\",\"aud\":\"" + CLIENT
                + "\",\"iat\":" + NOW + ",\"exp\":" + (NOW + 120) + ",\"nonce\":\"" + NONCE + "\",\"at_hash\":null}";
        com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key-1").build(), new com.nimbusds.jose.Payload(payload));
        jws.sign(new RSASSASigner(key));
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyInitial(jws.serialize(), jwks, ISSUER, CLIENT, NONCE, NOW, "access-123"));
    }
    @Test public void refreshIdTokenAcceptsOmittedAndMatchingNonce() throws Exception {
        IdTokenVerifier.Identity initial = identity(claims().build());
        verifyRefresh(refreshed().build(), initial);
        verifyRefresh(refreshed().claim("nonce", NONCE).build(), initial);
        assertEquals("VerifiedIdentity[redacted]", initial.toString());
        String originalJwt = sign(claims().build(), key, "key-1");
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(originalJwt, jwks(key.toPublicJWK()), initial, NOW, NOW));
    }
    @Test public void refreshIdTokenBindsIssuerSubjectAudienceAndNonce() throws Exception {
        IdTokenVerifier.Identity initial = identity(claims().build());
        rejectRefresh(refreshed().issuer("https://other.example.test").build(), initial);
        rejectRefresh(refreshed().subject("other").build(), initial);
        rejectRefresh(refreshed().audience("other").build(), initial);
        rejectRefresh(refreshed().audience(Arrays.asList(CLIENT, "other")).build(), initial);
        rejectRefresh(refreshed().claim("nonce", "wrong").build(), initial);
        rejectRefresh(refreshed().claim("nonce", "").build(), initial);
        rejectRefresh(refreshed().claim("nonce", 42).build(), initial);
        String jwt = sign(refreshed().build(), other, "key-1");
        assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(jwt, jwks(key.toPublicJWK()), initial, NOW, NOW));
    }
    @Test public void refreshIdTokenChecksAuthTimeAzpAndIssuance() throws Exception {
        Date auth = new Date((NOW - 100) * 1000);
        IdTokenVerifier.Identity initial = identity(claims().claim("auth_time", auth).claim("azp", CLIENT).build());
        verifyRefresh(refreshed().claim("auth_time", auth).claim("azp", CLIENT).build(), initial);
        verifyRefresh(refreshed().claim("azp", CLIENT).build(), initial);
        rejectRefresh(refreshed().claim("auth_time", new Date((NOW - 50) * 1000)).claim("azp", CLIENT).build(), initial);
        rejectRefresh(refreshed().claim("azp", "other").build(), initial);
        rejectRefresh(refreshed().build(), initial);
        rejectRefresh(refreshed().claim("azp", CLIENT).issueTime(new Date((NOW - 100) * 1000)).build(), initial);
        rejectRefresh(refreshed().claim("azp", CLIENT).issueTime(new Date((NOW + 61) * 1000)).build(), initial);
        rejectRefresh(refreshed().claim("azp", CLIENT).expirationTime(new Date(NOW * 1000)).build(), initial);
        IdTokenVerifier.Identity noAuthTime = identity(claims().build());
        rejectRefresh(refreshed().claim("auth_time", auth).build(), noAuthTime);
    }

    @Test public void refreshRejectsExplicitNullAndWrongClaimTypes() throws Exception {
        IdTokenVerifier.Identity initial = identity(claims().build());
        for (String claim : new String[]{"\"nonce\":null", "\"auth_time\":null", "\"azp\":null", "\"auth_time\":\"bad\""}) {
            String payload = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"person-1\",\"aud\":\"" + CLIENT
                    + "\",\"iat\":" + NOW + ",\"exp\":" + (NOW + 120) + "," + claim + "}";
            com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key-1").build(), new com.nimbusds.jose.Payload(payload));
            jws.sign(new RSASSASigner(key));
            String signed = jws.serialize();
            assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(signed, jwks(key.toPublicJWK()), initial, NOW, NOW));
        }
    }

}
