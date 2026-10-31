package org.privacybubble.app.auth;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;
import org.privacybubble.app.session.MobileSessionStore;

public final class AuthSecurityAssertions {
    private static int assertions;
    private AuthSecurityAssertions() { }

    public static int run(Instrumentation instrumentation) throws Exception {
        assertions = 0;
        AuthorizationRequest original = new AuthorizationRequest.Builder(
                new AuthorizationServiceConfiguration(Uri.parse("https://issuer.example.test/auth"), Uri.parse("https://issuer.example.test/token")),
                "isolated-mobile-client", ResponseTypeValues.CODE, Uri.parse(OidcRedirect.REDIRECT)).setScope("openid offline_access location:read").build();
        AuthorizationRequest transport = MobileSignInActivity.publicBrowserRequest(original);
        check(original != transport && !original.codeVerifier.equals(transport.codeVerifier), "separate-transport-request");
        check(original.state.equals(transport.state) && original.nonce.equals(transport.nonce), "public-transaction-binding");
        check(original.codeVerifierChallenge.equals(transport.codeVerifierChallenge) && "S256".equals(transport.codeVerifierChallengeMethod), "original-s256-challenge");
        check(original.codeVerifierChallenge.equals(transport.toUri().getQueryParameter("code_challenge")), "browser-challenge");
        check(transport.toUri().getQueryParameter("code_verifier") == null, "no-verifier-in-browser-uri");
        String placeholderHash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(transport.codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        check(!placeholderHash.equals(original.codeVerifierChallenge), "transport-value-cannot-redeem-code");
        AuthorizationService service = new AuthorizationService(instrumentation.getTargetContext());
        try {
            Intent browser = service.getAuthorizationRequestIntent(transport);
            String serialized = browser.getStringExtra("authRequest");
            check(serialized != null && !serialized.contains(original.codeVerifier), "no-original-verifier-in-appauth-intent");
            AuthorizationRequest recovered = AuthorizationRequest.jsonDeserialize(serialized);
            check(recovered.codeVerifierChallenge.equals(original.codeVerifierChallenge) && !recovered.codeVerifier.equals(original.codeVerifier), "serialized-public-request-only");
        } finally { service.dispose(); }
        MobileSessionStore store = new MobileSessionStore(instrumentation.getTargetContext());
        store.saveTokens("isolated-process-loss-access", "isolated-process-loss-refresh", "isolated-process-loss-subject", System.currentTimeMillis()/1000+300, "isolated-nonce");
        long before = store.epoch();
        instrumentation.runOnMainSync(() -> {
            MobileSignInActivity.SignInModel recreated = new MobileSignInActivity.SignInModel();
            recreated.start(store, true);
            recreated.onCleared();
        });
        check(store.getAccessToken().isEmpty() && store.getRefreshToken().isEmpty(), "process-loss-requires-new-signin");
        check(store.epoch() > before, "process-loss-invalidates-old-epoch");
        MobileSignInActivity.SignInModel canceled = new MobileSignInActivity.SignInModel();
        Field field = MobileSignInActivity.SignInModel.class.getDeclaredField("status");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") MutableLiveData<Integer> status = (MutableLiveData<Integer>)field.get(canceled);
        instrumentation.runOnMainSync(() -> {
            status.setValue(MobileSignInActivity.SignInModel.READY);
            canceled.cancel();
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            check(Integer.valueOf(MobileSignInActivity.SignInModel.FAILED).equals(status.getValue()), "browser-launch-cancel-terminal-state");
            int[] restored = {-1};
            Observer<Integer> observer = value -> restored[0] = value;
            status.observeForever(observer);
            check(restored[0] == MobileSignInActivity.SignInModel.FAILED, "new-observer-retains-failed-state");
            status.removeObserver(observer);
            canceled.onCleared();
        });
        return assertions;
    }

    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); assertions++; }
}
