package org.privacybubble.app.auth;

import com.nimbusds.jose.util.JSONObjectUtils;
import javax.net.ssl.HttpsURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host integration only. Uses the production discovery and ID-token verification classes. */
public final class HostJavaIntegration {
    private static final String CLIENT = "privacy-bubble-android-test";
    private static final String REDIRECT = "org.privacybubble.app:/oauth2redirect";
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String form(Map<String,String> params) {
        StringBuilder output = new StringBuilder();
        params.forEach((k,v) -> { if (output.length() != 0) output.append('&'); output.append(encode(k)).append('=').append(encode(v)); });
        return output.toString();
    }
    private static HttpsURLConnection connection(URI uri, String method, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) uri.toURL().openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (var out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        return c;
    }
    public static void main(String[] args) throws Exception {
        String issuer = args[0];
        if (args[1].equals("untrusted")) {
            try { OidcProvider.discover(issuer); throw new AssertionError("Untrusted issuer accepted"); }
            catch (javax.net.ssl.SSLHandshakeException expected) { System.out.println("PASS: untrusted TLS rejected"); }
            return;
        }
        OidcProvider provider = OidcProvider.discover(issuer);
        byte[] bytes = new byte[48]; new SecureRandom().nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = "test-state-123456789", nonce = "test-nonce-123456789";
        Map<String,String> auth = new LinkedHashMap<>();
        auth.put("response_type","code"); auth.put("client_id",CLIENT); auth.put("redirect_uri",REDIRECT); auth.put("scope","openid");
        auth.put("state",state); auth.put("nonce",nonce); auth.put("code_challenge",challenge); auth.put("code_challenge_method","S256");
        String authForm = form(auth);
        HttpsURLConnection page = connection(URI.create(provider.authorizationEndpoint + "?" + authForm),"GET",null);
        if (page.getResponseCode() != 200) throw new AssertionError("Mock consent page failed"); page.disconnect();
        HttpsURLConnection consent = connection(provider.authorizationEndpoint,"POST",authForm);
        if (consent.getResponseCode() != 303) throw new AssertionError("Mock consent failed");
        URI callback = URI.create(consent.getHeaderField("Location")); consent.disconnect();
        if (!REDIRECT.equals(callback.getScheme() + ":" + callback.getPath())) throw new AssertionError("Wrong redirect");
        String code = OidcRedirect.code(callback.toString(), state, issuer);
        Map<String,String> exchange = new LinkedHashMap<>();
        exchange.put("grant_type","authorization_code"); exchange.put("client_id",CLIENT); exchange.put("redirect_uri",REDIRECT);
        exchange.put("code",code); exchange.put("code_verifier",verifier);
        HttpsURLConnection tokenResponse = connection(provider.tokenEndpoint,"POST",form(exchange));
        if (tokenResponse.getResponseCode() != 200) throw new AssertionError("Token exchange failed");
        String json;
        try (var stream = tokenResponse.getInputStream()) { json = new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
        finally { tokenResponse.disconnect(); }
        String token = JSONObjectUtils.getString(JSONObjectUtils.parse(json),"id_token");
        long now = System.currentTimeMillis()/1000;
        if (args[1].equals("expired")) {
            try { provider.verifyIdToken(token,CLIENT,nonce,now - 240); throw new AssertionError("Stale caller time accepted expired ID token"); }
            catch (IllegalArgumentException expected) { System.out.println("PASS: stale caller time cannot revive expired ID token"); }
            return;
        }
        if (args[1].equals("future-iat") || args[1].equals("future-nbf")) {
            long future = now + (args[1].equals("future-nbf") ? 90 : 240);
            try { provider.verifyIdToken(token,CLIENT,nonce,future); throw new AssertionError("Future caller time accepted premature ID token"); }
            catch (IllegalArgumentException expected) { System.out.println("PASS: future caller time cannot accept premature ID token"); }
            return;
        }
        if (!"synthetic-test-user".equals(provider.verifyIdToken(token,CLIENT,nonce))) throw new AssertionError("Unexpected subject");
        try { provider.verifyIdToken(token,CLIENT,"incorrect-test-nonce"); throw new AssertionError("Wrong nonce accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS: trusted discovery, mock S256 exchange, Java ID token verification and wrong nonce rejection");
    }
}
