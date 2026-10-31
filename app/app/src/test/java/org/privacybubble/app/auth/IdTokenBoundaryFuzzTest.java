package org.privacybubble.app.auth;

import static org.junit.Assert.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.BeforeClass;
import org.junit.Test;

public class IdTokenBoundaryFuzzTest {
    private static final long SEED = 0x425542424C45L;
    private static final long NOW = 1_800_000_000L;
    private static final String ISSUER = "https://identity.example.test/realm";
    private static final String CLIENT = "mobile-client", NONCE = "transaction-nonce";
    private static RSAKey key;
    private static String jwks;
    private static final String HEADER = "{\"alg\":\"RS256\",\"kid\":\"security-key\"}";

    @BeforeClass public static void generateKey() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("security-key").generate();
        jwks = new JWKSet(key.toPublicJWK()).toString();
    }

    private Map<String,Object> claims() {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("iss", ISSUER); values.put("aud", CLIENT); values.put("sub", "person-1");
        values.put("nonce", NONCE); values.put("iat", NOW - 10); values.put("exp", NOW + 120);
        return values;
    }

    private String sign(String header, String payload) throws Exception {
        String input = encode(header.getBytes(StandardCharsets.UTF_8)) + "." + encode(payload.getBytes(StandardCharsets.UTF_8));
        return input + "." + new RSASSASigner(key).sign(new JWSHeader(JWSAlgorithm.RS256), input.getBytes(StandardCharsets.US_ASCII));
    }
    private String sign(Map<String,Object> values) throws Exception { return sign(HEADER, JSONObjectUtils.toJSONString(values)); }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String verify(String value, String keys) { return IdTokenVerifier.verify(value, keys, ISSUER, CLIENT, NONCE, NOW); }
    private void reject(String value) { assertThrows(IllegalArgumentException.class, () -> verify(value, jwks)); }

    @Test public void canonicalSignaturePreventsInitialTokenReplayThroughUnusedBits() throws Exception {
        String original = sign(claims());
        assertEquals("person-1", verify(original, jwks));
        IdTokenVerifier.Identity identity = IdTokenVerifier.verifyInitial(original, jwks, ISSUER, CLIENT, NONCE, NOW);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(original.charAt(original.length()-1));
        for (int extra = 1; extra < 16; extra++) {
            String mutated = original.substring(0, original.length()-1) + alphabet.charAt(last + extra);
            assertArrayEquals(Base64.getUrlDecoder().decode(original.split("\\.")[2]), Base64.getUrlDecoder().decode(mutated.split("\\.")[2]));
            reject(mutated);
            assertThrows(IllegalArgumentException.class, () -> IdTokenVerifier.verifyRefresh(mutated, jwks, identity, NOW, NOW));
        }
    }

    @Test public void duplicateEscapedClaimsHeadersAndJwkMembersAreRejected() throws Exception {
        String payload = JSONObjectUtils.toJSONString(claims());
        for (String member : new String[]{"iss", "aud", "sub", "nonce", "iat", "exp"}) {
            String value = JSONObjectUtils.toJSONString(Collections.singletonMap(member, claims().get(member)));
            reject(sign(HEADER, payload.substring(0, payload.length()-1) + "," + value.substring(1)));
        }
        reject(sign(HEADER, payload.substring(0, payload.length()-1) + ",\"\\u0069ss\":\"" + ISSUER + "\"}"));
        reject(sign("{\"alg\":\"none\",\"alg\":\"RS256\",\"kid\":\"security-key\"}", payload));
        String signed = sign(claims());
        String duplicateKeys = jwks.substring(0, jwks.length()-1) + ",\"keys\":" + jwks.substring(8);
        assertThrows(IllegalArgumentException.class, () -> verify(signed, duplicateKeys));
        String duplicateKid = jwks.replace("\"kid\":\"security-key\"", "\"kid\":\"other\",\"kid\":\"security-key\"");
        assertThrows(IllegalArgumentException.class, () -> verify(signed, duplicateKid));
    }

    @Test public void numericDateTypesFractionsAndOverflowFailClosed() throws Exception {
        Object[] bad = {null, "1800000000", true, new BigDecimal("1800000000.5"), new BigDecimal("1e100"), -1L,
                Long.MAX_VALUE, Arrays.asList(NOW), Collections.singletonMap("time", NOW)};
        for (String name : new String[]{"iat", "exp", "nbf", "auth_time"}) {
            for (Object value : bad) {
                Map<String,Object> values = claims(); values.put(name, value);
                reject(sign(values));
            }
        }
        for (String badSubject : new String[]{"user\u0000name", "user\rname", "user\u007fname"}) {
            Map<String,Object> values = claims(); values.put("sub", badSubject); reject(sign(values));
        }
    }

    @Test public void seededClaimMutationCorpusNeverAuthenticatesWrongBindings() throws Exception {
        Random random = new Random(SEED);
        for (int index = 0; index < 256; index++) {
            Map<String,Object> values = claims();
            switch (random.nextInt(8)) {
                case 0: values.put("iss", ISSUER + "/" + random.nextInt()); break;
                case 1: values.put("aud", Arrays.asList(CLIENT, "other-" + random.nextInt())); break;
                case 2: values.put("nonce", NONCE + random.nextInt()); break;
                case 3: values.put("exp", NOW - random.nextInt(1000)); break;
                case 4: values.put("iat", NOW + 61 + random.nextInt(1000)); break;
                case 5: values.put("nbf", NOW + 1 + random.nextInt(1000)); break;
                case 6: values.put("azp", "other-" + random.nextInt()); break;
                default: values.put("sub", "x".repeat(256 + random.nextInt(64))); break;
            }
            reject(sign(values));
        }
        System.out.println("IdTokenBoundaryFuzz seed=" + SEED + " signed-claim-mutations=256");
    }

    @Test public void seededSignatureAndJwkMutationsRejectWithoutUnexpectedFailures() throws Exception {
        Random random = new Random(SEED ^ 0x4A574B);
        String original = sign(claims()); String[] segments = original.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(segments[2]);
        for (int index = 0; index < 512; index++) {
            byte[] changed = signature.clone(); int offset = random.nextInt(changed.length);
            changed[offset] ^= (byte)(1 << random.nextInt(8));
            reject(segments[0] + "." + segments[1] + "." + encode(changed));
        }
        for (int index = 0; index < 128; index++) {
            Map<String,Object> changed = new HashMap<>(key.toPublicJWK().toJSONObject());
            switch (random.nextInt(5)) {
                case 0: changed.put("kid", "unbound-" + index); break;
                case 1: changed.put("alg", "HS256"); break;
                case 2: changed.put("use", "enc"); break;
                case 3: changed.put("key_ops", Arrays.asList("encrypt")); break;
                default: changed.put("n", "!"); break;
            }
            String keys = JSONObjectUtils.toJSONString(Collections.singletonMap("keys", Arrays.asList(changed)));
            assertThrows(IllegalArgumentException.class, () -> verify(original, keys));
        }
        assertThrows(IllegalArgumentException.class, () -> verify(original,
                JSONObjectUtils.toJSONString(Collections.singletonMap("keys", Collections.nCopies(65, key.toPublicJWK().toJSONObject())))));
        System.out.println("IdTokenBoundaryFuzz seed=" + (SEED ^ 0x4A574B) + " signature-mutations=512 jwk-mutations=128");
    }

    @Test public void arbitraryCompactTokensRemainBoundedAndFailClosed() {
        Random random = new Random(SEED ^ 0x4A5754);
        for (int index = 0; index < 1024; index++) {
            byte[] bytes = new byte[random.nextInt(256)]; random.nextBytes(bytes);
            String value = new String(bytes, StandardCharsets.ISO_8859_1);
            reject(value);
        }
        for (String value : new String[]{null, "", "x".repeat(16385), "e30=.e30=.AA", "e30.e30.A!", "e30.e30.AA\n"}) reject(value);
        System.out.println("IdTokenBoundaryFuzz seed=" + (SEED ^ 0x4A5754) + " malformed-compact-tokens=1024");
    }
}
