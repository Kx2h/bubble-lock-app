package org.privacybubble.app.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.Set;

/** Validates an ID token against an already authenticated issuer and JWKS document. */
public final class IdTokenVerifier {
    private static final int MAX_TOKEN_LENGTH = 16384;
    private static final int MAX_JWKS_LENGTH = 65536;
    private static final long FUTURE_IAT_SKEW_SECONDS = 60;

    private IdTokenVerifier() { }

    /**
     * Returns only the verified subject. The caller must obtain jwksJson over verified TLS from
     * the configured issuer's validated discovery document and bind nonce to this transaction.
     * A multiple-audience token is rejected because no other audiences are configured as trusted.
     * Never log the token, nonce, or returned subject.
     */
    public static String verify(String jwt, String jwksJson, String expectedIssuer,
                                String clientId, String nonce, long nowEpochSeconds) {
        try {
            requireText(jwt, MAX_TOKEN_LENGTH);
            requireText(jwksJson, MAX_JWKS_LENGTH);
            requireText(expectedIssuer, 2048);
            requireText(clientId, 512);
            requireText(nonce, 512);
            if (nowEpochSeconds <= 0 || nowEpochSeconds > Long.MAX_VALUE / 1000 - 60) fail();
            URI issuer = new URI(expectedIssuer);
            if (!"https".equalsIgnoreCase(issuer.getScheme()) || issuer.getHost() == null
                    || issuer.getUserInfo() != null || issuer.getQuery() != null || issuer.getFragment() != null) fail();

            SignedJWT token = SignedJWT.parse(jwt);
            JWSHeader header = token.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) fail();
            String kid = header.getKeyID();
            requireText(kid, 512);
            if (header.getJWK() != null || header.getJWKURL() != null || header.getX509CertURL() != null
                    || (header.getCriticalParams() != null && !header.getCriticalParams().isEmpty())) fail();

            List<JWK> keys = JWKSet.parse(jwksJson).getKeys();
            JWK selected = null;
            for (JWK key : keys) {
                if (kid.equals(key.getKeyID())) {
                    if (selected != null) fail(); // ambiguous key IDs are never accepted
                    selected = key;
                }
            }
            if (!(selected instanceof RSAKey) || selected.isPrivate()) fail();
            RSAKey rsa = (RSAKey) selected;
            if (rsa.size() < 2048 || (rsa.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(rsa.getAlgorithm()))
                    || (rsa.getKeyUse() != null && !KeyUse.SIGNATURE.equals(rsa.getKeyUse()))) fail();
            Set<KeyOperation> operations = rsa.getKeyOperations();
            if (operations != null && !operations.contains(KeyOperation.VERIFY)) fail();
            if (!token.verify(new RSASSAVerifier(rsa))) fail();

            JWTClaimsSet claims = token.getJWTClaimsSet();
            if (!expectedIssuer.equals(claims.getIssuer())) fail();
            List<String> audience = claims.getAudience();
            if (audience == null || audience.size() != 1 || !clientId.equals(audience.get(0))) fail();
            Object azp = claims.getClaim("azp");
            if (azp != null && (!(azp instanceof String) || !clientId.equals(azp))) fail();
            String subject = claims.getSubject();
            requireText(subject, 512);
            Date exp = claims.getExpirationTime();
            Date issued = claims.getIssueTime();
            Date notBefore = claims.getNotBeforeTime();
            long nowMillis = nowEpochSeconds * 1000;
            if (exp == null || issued == null || exp.getTime() <= nowMillis
                    || issued.getTime() > (nowEpochSeconds + FUTURE_IAT_SKEW_SECONDS) * 1000
                    || issued.getTime() > exp.getTime()
                    || (notBefore != null && notBefore.getTime() > nowMillis)) fail();
            Object suppliedNonce = claims.getClaim("nonce");
            if (!(suppliedNonce instanceof String) || !MessageDigest.isEqual(
                    nonce.getBytes(StandardCharsets.UTF_8), ((String) suppliedNonce).getBytes(StandardCharsets.UTF_8))) fail();
            return subject;
        } catch (Exception e) {
            // No token contents or library parsing details in user-facing errors or logs.
            throw new IllegalArgumentException("ID token validation failed");
        }
    }

    private static void requireText(String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength || !value.equals(value.trim())) fail();
    }

    private static void fail() { throw new IllegalArgumentException("ID token validation failed"); }
}
