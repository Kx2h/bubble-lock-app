package org.privacybubble.app.auth;

import static org.junit.Assert.*;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.util.*;
import org.junit.Test;

public class OidcProviderFuzzTest {
    private static final String ISSUER = "https://issuer.example.test/realm";
    private Map<String,Object> metadata() {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("issuer", ISSUER); values.put("authorization_endpoint", "https://issuer.example.test/auth");
        values.put("token_endpoint", "https://issuer.example.test/token"); values.put("jwks_uri", "https://issuer.example.test/keys");
        values.put("response_types_supported", Arrays.asList("code")); values.put("id_token_signing_alg_values_supported", Arrays.asList("RS256"));
        values.put("code_challenge_methods_supported", Arrays.asList("S256"));
        return values;
    }

    @Test public void seededEndpointAndCapabilityMutationsCannotChangeTrustOrDowngradePkce() {
        long seed = 0x4F4944434D455441L;
        Random random = new Random(seed);
        String[] endpointFields = {"authorization_endpoint", "token_endpoint", "jwks_uri"};
        String[] badUris = {"http://issuer.example.test/endpoint", "file:///endpoint", "https://user@issuer.example.test/endpoint", "https://issuer.example.test/endpoint?secret=1",
                "https://issuer.example.test/endpoint#fragment", "https://issuer.example.test:0/endpoint", "https://issuer.example.test:65536/endpoint", "//issuer.example.test/endpoint"};
        for (int index=0; index<512; index++) {
            Map<String,Object> values = metadata();
            switch (random.nextInt(5)) {
                case 0: values.put(endpointFields[random.nextInt(endpointFields.length)], badUris[random.nextInt(badUris.length)]); break;
                case 1: values.put("issuer", ISSUER + "/substituted-" + random.nextInt()); break;
                case 2: values.put("code_challenge_methods_supported", Arrays.asList("plain")); break;
                case 3: values.put("id_token_signing_alg_values_supported", Arrays.asList("HS256")); break;
                default: values.put("response_types_supported", Arrays.asList("token", "id_token")); break;
            }
            String document = JSONObjectUtils.toJSONString(values);
            assertThrows(Exception.class, () -> OidcProvider.parse(ISSUER, document));
        }
        System.out.println("OidcProviderFuzz seed=" + seed + " endpoint-and-capability-mutations=512");
    }

    @Test public void duplicateLenientAndOversizedDiscoveryDocumentsFailClosed() throws Exception {
        String valid = JSONObjectUtils.toJSONString(metadata());
        assertEquals(ISSUER, OidcProvider.parse(ISSUER, valid).issuer);
        for (String document : new String[]{valid.substring(0, valid.length()-1) + ",\"issuer\":\"" + ISSUER + "\"}",
                valid.substring(0, valid.length()-1) + ",\"\\u0069ssuer\":\"" + ISSUER + "\"}",
                valid.replace("\"issuer\"", "issuer"), valid.replace("\"issuer\"", "'issuer'"), valid + "{}", " ".repeat(65537),
                valid.substring(0, valid.length()-1) + ",\"extension\":" + "[".repeat(17) + "null" + "]".repeat(17) + "}"}) {
            assertThrows(Exception.class, () -> OidcProvider.parse(ISSUER, document));
        }
    }
}
