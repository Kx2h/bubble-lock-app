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
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
        return verifyInitial(jwt, jwksJson, expectedIssuer, clientId, nonce, nowEpochSeconds).subject();
    }

    /** Created only after validation, or restored from the app's authenticated encrypted session store. Never log. */
    static final class Identity {
        private final String issuer, clientId, subject, nonce, azp;
        private final Long authTimeMillis;
        private final long issuedMillis;
        private final byte[] initialTokenDigest;
        private Identity(String issuer, String clientId, String subject, String nonce, String azp,
                         Long authTimeMillis, long issuedMillis, byte[] initialTokenDigest) {
            this.issuer = issuer; this.clientId = clientId; this.subject = subject;
            this.nonce = nonce; this.azp = azp; this.authTimeMillis = authTimeMillis;
            this.issuedMillis = issuedMillis; this.initialTokenDigest = initialTokenDigest;
        }
        String subject() { return subject; }
        boolean matches(String issuer, String clientId) { return this.issuer.equals(issuer) && this.clientId.equals(clientId); }
        String exportForEncryptedStorage() {
            java.util.Map<String,Object> context = new java.util.HashMap<>();
            context.put("issuer",issuer); context.put("client",clientId); context.put("subject",subject); context.put("nonce",nonce);
            context.put("azp",azp); context.put("authTime",authTimeMillis); context.put("issued",issuedMillis);
            context.put("digest",Base64.getEncoder().encodeToString(initialTokenDigest));
            return com.nimbusds.jose.util.JSONObjectUtils.toJSONString(context);
        }
        @Override public String toString() { return "VerifiedIdentity[redacted]"; }
    }

    static Identity verifyInitial(String jwt, String jwksJson, String expectedIssuer,
                                  String clientId, String nonce, long nowEpochSeconds) {
        return verifyInitial(jwt, jwksJson, expectedIssuer, clientId, nonce, nowEpochSeconds, null);
    }

    static Identity verifyInitial(String jwt, String jwksJson, String expectedIssuer,
                                  String clientId, String nonce, long nowEpochSeconds, String accessToken) {
        try {
            requireText(nonce, 512);
            JWTClaimsSet claims = validateSigned(jwt, jwksJson, expectedIssuer, clientId, nowEpochSeconds);
            verifyAccessTokenHash(claims, accessToken);
            Object suppliedNonce = claims.getClaim("nonce");
            if (!(suppliedNonce instanceof String) || !sameNonce(nonce, (String) suppliedNonce)) fail();
            Object azp = claims.getClaim("azp");
            if (claims.getClaims().containsKey("azp") && !(azp instanceof String)) fail();
            Date authTime = authTime(claims);
            Date issued = claims.getIssueTime();
            if (authTime != null && authTime.getTime() > issued.getTime() + FUTURE_IAT_SKEW_SECONDS * 1000) fail();
            return new Identity(expectedIssuer, clientId, claims.getSubject(), nonce, (String) azp,
                    authTime == null ? null : authTime.getTime(), issued.getTime(), digest(jwt));
        } catch (Exception e) { throw new IllegalArgumentException("ID token validation failed"); }
    }

    /** Refresh tokens may omit nonce and auth_time; if supplied they must bind to the initial token. */
    static void verifyRefresh(String jwt, String jwksJson, Identity initial,
                              long requestStartedEpochSeconds, long nowEpochSeconds) {
        verifyRefresh(jwt, jwksJson, initial, requestStartedEpochSeconds, nowEpochSeconds, null);
    }

    static void verifyRefresh(String jwt, String jwksJson, Identity initial,
                              long requestStartedEpochSeconds, long nowEpochSeconds, String accessToken) {
        try {
            if (initial == null || requestStartedEpochSeconds <= 0 || requestStartedEpochSeconds > nowEpochSeconds
                    || nowEpochSeconds > Long.MAX_VALUE / 1000 - 60) fail();
            JWTClaimsSet claims = validateSigned(jwt, jwksJson, initial.issuer, initial.clientId, nowEpochSeconds);
            verifyAccessTokenHash(claims, accessToken);
            if (MessageDigest.isEqual(initial.initialTokenDigest, digest(jwt)) || !initial.subject.equals(claims.getSubject())) fail();
            Object nonce = claims.getClaim("nonce");
            if (claims.getClaims().containsKey("nonce") && (!(nonce instanceof String) || !sameNonce(initial.nonce, (String) nonce))) fail();
            Object azp = claims.getClaim("azp");
            if (claims.getClaims().containsKey("azp") && !(azp instanceof String)) fail();
            if (!Objects.equals(initial.azp, azp)) fail();
            Date originalAuth = authTime(claims);
            if (originalAuth != null && (initial.authTimeMillis == null || originalAuth.getTime() != initial.authTimeMillis)) fail();
            long issued = claims.getIssueTime().getTime();
            if (issued < initial.issuedMillis || issued < (requestStartedEpochSeconds - FUTURE_IAT_SKEW_SECONDS) * 1000) fail();
        } catch (Exception e) { throw new IllegalArgumentException("ID token validation failed"); }
    }

    private static byte[] digest(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    }

    /** RS256 uses the left half of SHA-256, encoded as unpadded base64url. */
    private static void verifyAccessTokenHash(JWTClaimsSet claims, String accessToken) throws Exception {
        if (!claims.getClaims().containsKey("at_hash")) return;
        Object supplied = claims.getClaim("at_hash");
        if (!(supplied instanceof String) || accessToken == null || accessToken.isEmpty() || accessToken.length() > MAX_TOKEN_LENGTH) fail();
        for (int i = 0; i < accessToken.length(); i++) if (accessToken.charAt(i) < 33 || accessToken.charAt(i) > 126) fail();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, hash.length / 2));
        if (!sameNonce(expected, (String) supplied)) fail();
    }

    private static boolean sameNonce(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /** Only call with a value decrypted and authenticated by MobileSessionStore, never an Intent or response field. */
    static Identity restoreFromEncryptedStorage(String value, String issuer, String clientId) {
        try {
            requireText(value,4096);
            java.util.Map<String,Object> context=com.nimbusds.jose.util.JSONObjectUtils.parse(value);
            if (!issuer.equals(context.get("issuer")) || !clientId.equals(context.get("client"))) fail();
            String subject=com.nimbusds.jose.util.JSONObjectUtils.getString(context,"subject"), nonce=com.nimbusds.jose.util.JSONObjectUtils.getString(context,"nonce");
            requireSubject(subject); requireText(nonce,512);
            Object azp=context.get("azp"); if (azp!=null && (!(azp instanceof String) || !clientId.equals(azp))) fail();
            long issued=com.nimbusds.jose.util.JSONObjectUtils.getLong(context,"issued"); if (issued<=0) fail();
            Long auth=context.get("authTime")==null ? null : com.nimbusds.jose.util.JSONObjectUtils.getLong(context,"authTime");
            if (auth!=null && (auth<0 || auth>issued+60_000)) fail();
            byte[] fingerprint=Base64.getDecoder().decode(com.nimbusds.jose.util.JSONObjectUtils.getString(context,"digest"));
            if (fingerprint.length!=32) fail();
            return new Identity(issuer,clientId,subject,nonce,(String)azp,auth,issued,fingerprint);
        } catch (Exception failure) { throw new IllegalArgumentException("Session context unavailable"); }
    }

    private static Date authTime(JWTClaimsSet claims) throws Exception {
        if (!claims.getClaims().containsKey("auth_time")) return null;
        Date value = claims.getDateClaim("auth_time");
        if (value == null) fail();
        return value;
    }

    private static JWTClaimsSet validateSigned(String jwt, String jwksJson, String expectedIssuer,
                                                String clientId, long nowEpochSeconds) throws Exception {
        requireText(jwt, MAX_TOKEN_LENGTH);
        requireText(jwksJson, MAX_JWKS_LENGTH);
        requireText(expectedIssuer, 2048);
        requireText(clientId, 512);
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
                if (selected != null) fail();
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
        requireSubject(claims.getSubject());
        Date exp = claims.getExpirationTime(), issued = claims.getIssueTime(), notBefore = claims.getNotBeforeTime();
        long nowMillis = nowEpochSeconds * 1000;
        if (exp == null || issued == null || exp.getTime() <= nowMillis
                || (claims.getClaims().containsKey("nbf") && notBefore == null)
                || issued.getTime() > (nowEpochSeconds + FUTURE_IAT_SKEW_SECONDS) * 1000
                || issued.getTime() > exp.getTime()
                || (notBefore != null && notBefore.getTime() > nowMillis)) fail();
        return claims;
    }

    private static void requireText(String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength || !value.equals(value.trim())) fail();
    }

    /** OpenID Connect limits the case-sensitive subject to 255 ASCII characters. */
    private static void requireSubject(String value) {
        requireText(value, 255);
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7f) fail();
    }

    private static void fail() { throw new IllegalArgumentException("ID token validation failed"); }
}
