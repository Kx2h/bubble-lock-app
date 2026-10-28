package org.privacybubble.oidcsurrogate;

/** Small host test; no Android framework or network access. */
public final class SyntheticRequestTest {
    public static void main(String[] args) {
        String query = "response_type=code&client_id=privacy-bubble-android-test&redirect_uri=org.privacybubble.app%3A%2Foauth2redirect&scope=openid&state=ABCDEFGHIJKLMNOP&nonce=ABCDEFGHIJKLMNOP&code_challenge=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq&code_challenge_method=S256";
        String valid = "https://localhost:18443/authorize?" + query;
        SyntheticRequest.validate(valid);
        String refresh = valid.replace("scope=openid", "scope=openid+offline_access") + "&prompt=consent";
        SyntheticRequest.validate(refresh);
        String consent = valid.replace("scope=openid", "scope=openid+offline_access+test.consent.read+test.consent.write") + "&prompt=consent";
        SyntheticRequest.validate(consent);
        for (String bad : new String[] {
                valid.replace("localhost", "example.org"),
                valid.replace("https:", "http:"),
                valid.replace("18443", "18444"),
                valid + "&login_hint=someone", valid + "&state=extra",
                valid.replace("scope=openid", "scope=openid+email"),
                valid.replace("scope=openid", "scope=openid+offline_access"),
                valid + "&prompt=consent",
                refresh.replace("prompt=consent", "prompt=none"),
                refresh.replace("offline_access", "email"),
                refresh + "&prompt=consent", refresh + "&login_hint=someone",
                consent.replace("prompt=consent", "prompt=none"), consent.replace("test.consent.write", "test.admin"),
                refresh.replace("privacy-bubble-android-test", "another-client"),
                refresh.replace("org.privacybubble.app%3A%2Foauth2redirect", "https%3A%2F%2Fevil.example%2Fcallback"),
                valid.replace("code_challenge_method=S256", "code_challenge_method=plain"),
                valid.replace("state=ABCDEFGHIJKLMNOP", "state=short"),
                valid + "#fragment" }) {
            try { SyntheticRequest.validate(bad); throw new AssertionError("Accepted unsafe request"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS: synthetic issuer, PKCE, constrained offline access, fixed client, and no personal-data fields");
    }
}
