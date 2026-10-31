package org.privacybubble.app.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;
import org.json.JSONObject;
import org.privacybubble.app.session.MobileSessionStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MobileSignInActivity extends AppCompatActivity {
    private static final int BROWSER = 7702;
    private MobileSessionStore store;
    private AuthorizationService service;
    private SignInModel model;
    private boolean completed;

    @Override public void onCreate(Bundle state) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(state);
        store = new MobileSessionStore(getApplicationContext());
        setResult(Activity.RESULT_CANCELED);
        render(false);
        model = new ViewModelProvider(this).get(SignInModel.class);
        model.status.observe(this, status -> {
            if (isFinishing()) return;
            if (status == SignInModel.SUCCESS) {
                completed = true;
                model.acceptSuccess();
                setResult(Activity.RESULT_OK);
                finish();
            } else if (status == SignInModel.FAILED) render(true);
            else if (status == SignInModel.READY) {
                try {
                    AuthorizationRequest browserRequest = model.claimBrowserRequest();
                    if (browserRequest == null) return;
                    service = new AuthorizationService(this);
                    startActivityForResult(service.getAuthorizationRequestIntent(browserRequest), BROWSER);
                } catch (Exception failure) { model.cancel(); render(true); }
            }
        });
        model.start(store, state != null);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == BROWSER && model != null) model.consumeResult(result, data == null || data.getData() == null ? null : data.getData().toString());
    }

    private void render(boolean failed) {
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int gap=(int)(24*getResources().getDisplayMetrics().density); layout.setPadding(gap,gap*3,gap,gap);
        TextView title=new TextView(this); title.setText(failed ? "로그인을 완료하지 못했어요" : "안전하게 로그인하고 있어요");
        title.setTextSize(26); title.setTypeface(null,android.graphics.Typeface.BOLD); title.setAccessibilityHeading(true); layout.addView(title);
        TextView description=new TextView(this);
        description.setText(failed ? (store!=null && !store.isSignInConfigured() ? "서비스 연결 정보가 준비되지 않았어요. 서비스 관리자에게 연결 설정을 확인해 주세요." : "로그인이 중단되었거나 연결을 확인할 수 없어요. 돌아간 뒤 다시 로그인해 주세요.") : "브라우저에서 계정을 확인한 후 앱으로 돌아옵니다.");
        description.setTextSize(17); description.setPadding(0,gap,0,gap); description.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); layout.addView(description);
        if (!failed) layout.addView(new ProgressBar(this));
        MaterialButton done=new MaterialButton(this); done.setText(failed ? "돌아가기" : "취소"); done.setMinHeight((int)(48*getResources().getDisplayMetrics().density));
        done.setOnClickListener(view -> finish()); layout.addView(done);
        setContentView(layout);
    }

    @Override protected void onDestroy() {
        if (service != null) service.dispose();
        super.onDestroy();
    }

    @Override public void finish() {
        if (!completed && model != null) model.cancel();
        super.finish();
    }

    static AuthorizationRequest publicBrowserRequest(AuthorizationRequest transaction) {
        return new AuthorizationRequest.Builder(transaction.configuration, transaction.clientId, ResponseTypeValues.CODE, transaction.redirectUri)
                .setScope(transaction.scope).setState(transaction.state).setNonce(transaction.nonce)
                .setCodeVerifier(SignInModel.PUBLIC_TRANSPORT_VERIFIER, transaction.codeVerifierChallenge, transaction.codeVerifierChallengeMethod).build();
    }

    public static final class SignInModel extends ViewModel {
        static final int BUSY = 0, READY = 1, SUCCESS = 2, FAILED = 3;
        private static final String PUBLIC_TRANSPORT_VERIFIER = "public-browser-transport-placeholder-not-the-secret";
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final MutableLiveData<Integer> status = new MutableLiveData<>(BUSY);
        private MobileSessionStore store;
        private AuthorizationRequest request;
        private OidcProvider provider;
        private AuthorizationAttempt attempt;
        private boolean started, browserLaunched, closed, accepted;

        synchronized void start(MobileSessionStore store, boolean restoredByOperatingSystem) {
            if (started) return;
            started = true;
            this.store = store;
            if (restoredByOperatingSystem) {
                try { store.clearSession(); } catch (Exception failure) { }
                closed = true;
                worker.shutdownNow();
                status.setValue(FAILED);
                return;
            }
            if (!store.isSignInConfigured()) { status.setValue(FAILED); return; }
            try {
                store.clearSession();
                attempt = new AuthorizationAttempt(SystemClock.elapsedRealtime(), store.epoch(), store.getApiBaseUrl());
                worker.execute(() -> {
                    try {
                        OidcProvider discovered = OidcProvider.discover(store.getIssuer());
                        AuthorizationServiceConfiguration configuration = new AuthorizationServiceConfiguration(
                                Uri.parse(discovered.authorizationEndpoint.toString()), Uri.parse(discovered.tokenEndpoint.toString()));
                        AuthorizationRequest created = new AuthorizationRequest.Builder(configuration, store.getClientId(), ResponseTypeValues.CODE, Uri.parse(OidcRedirect.REDIRECT))
                                .setScope("openid offline_access location:read").build();
                        if (created.state == null || created.nonce == null || created.codeVerifier == null || !"S256".equals(created.codeVerifierChallengeMethod)) throw new Exception();
                        synchronized (this) {
                            if (!live()) return;
                            provider = discovered; request = created;
                            status.postValue(READY);
                        }
                    } catch (Exception failure) { fail(); }
                });
            } catch (Exception failure) { fail(); }
        }

        synchronized AuthorizationRequest claimBrowserRequest() throws Exception {
            if (closed || browserLaunched || request == null) return null;
            if (!live()) throw new IllegalStateException("Authorization transaction unavailable");
            browserLaunched = true;
            return publicBrowserRequest(request);
        }

        synchronized void consumeResult(int result, String redirect) {
            if (closed) return;
            if (attempt == null) { fail(); return; }
            if (!attempt.claim(SystemClock.elapsedRealtime(), store.epoch(), store.getApiBaseUrl())) {
                if (!live()) fail();
                return;
            }
            final String code;
            final AuthorizationRequest transaction = request;
            final OidcProvider trustedProvider = provider;
            try {
                if (result != Activity.RESULT_OK || redirect == null || transaction == null || trustedProvider == null) throw new Exception();
                code = OidcRedirect.code(redirect, transaction.state, trustedProvider.issuer);
            } catch (Exception invalid) { fail(); return; }
            status.setValue(BUSY);
            worker.execute(() -> {
                try {
                    long exchangeStarted = System.currentTimeMillis()/1000;
                    String form = "grant_type=authorization_code&code=" + MobileTokenManager.enc(code)
                            + "&client_id=" + MobileTokenManager.enc(transaction.clientId) + "&redirect_uri=" + MobileTokenManager.enc(OidcRedirect.REDIRECT)
                            + "&code_verifier=" + MobileTokenManager.enc(transaction.codeVerifier);
                    String responseText = OidcTokenTransport.postForm(trustedProvider.tokenEndpoint, form);
                    MobileTokenManager.TokenValues tokens = MobileTokenManager.TokenValues.parse(responseText, "", true);
                    JSONObject response = new JSONObject(responseText);
                    IdTokenVerifier.Identity identity = trustedProvider.verifyIdentityToken(response.getString("id_token"), transaction.clientId, transaction.nonce, tokens.access);
                    synchronized (this) {
                        if (!live() || !store.saveTokensIfCurrent(tokens.access, tokens.refresh, identity.subject(), exchangeStarted + tokens.seconds,
                                transaction.nonce, attempt.epoch, attempt.base, identity.exportForEncryptedStorage())) throw new Exception();
                        request = null;
                        status.postValue(SUCCESS);
                    }
                } catch (Exception failure) { fail(); }
            });
        }

        private boolean live() {
            return !closed && attempt != null && attempt.isLive(SystemClock.elapsedRealtime(), store.epoch(), store.getApiBaseUrl());
        }

        private synchronized void fail() {
            if (closed) return;
            request = null;
            status.postValue(FAILED);
        }

        synchronized void acceptSuccess() { accepted = true; }

        synchronized void cancel() {
            if (closed) return;
            closed = true;
            request = null;
            status.postValue(FAILED);
            if (attempt != null) {
                attempt.cancel();
                try { store.clearSessionIfCurrent(attempt.epoch, attempt.base); } catch (Exception ignored) { }
            }
            worker.shutdownNow();
        }

        @Override protected void onCleared() {
            if (!accepted) cancel();
            else worker.shutdownNow();
        }
    }
}
