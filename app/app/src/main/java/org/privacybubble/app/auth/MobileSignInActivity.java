package org.privacybubble.app.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;
import org.json.JSONObject;
import org.privacybubble.app.session.MobileSessionStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browser-only code + PKCE sign-in against the operator-provisioned issuer. */
public final class MobileSignInActivity extends AppCompatActivity {
    private static final int BROWSER = 7702;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MobileSessionStore store;
    private AuthorizationService service;
    private AuthorizationRequest request;
    private OidcProvider provider;
    private volatile boolean closed;
    private boolean exchanged;
    private boolean completed;
    private Intent pendingResult;
    private int pendingResultCode;
    private long startedAt, expectedEpoch;
    private String expectedBase;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new MobileSessionStore(this);
        setResult(Activity.RESULT_CANCELED);
        render(false);
        if (!store.isSignInConfigured()) { render(true); return; }
        try {
            if (state != null) {
                startedAt=state.getLong("startedAt"); expectedEpoch=state.getLong("epoch"); expectedBase=state.getString("base", "");
                if (state.getBoolean("exchanged") || System.currentTimeMillis()/1000-startedAt > 600
                        || state.getString("request") == null) throw new Exception();
                request=AuthorizationRequest.jsonDeserialize(state.getString("request"));
            } else {
                store.clearSession(); expectedEpoch=store.epoch(); expectedBase=store.getApiBaseUrl(); startedAt=System.currentTimeMillis()/1000;
            }
            worker.execute(() -> {
                try {
                    OidcProvider discovered=OidcProvider.discover(store.getIssuer());
                    main.post(() -> {
                        if (closed) return;
                        provider=discovered;
                        if (request != null) {
                            if (!request.clientId.equals(store.getClientId()) || !request.configuration.authorizationEndpoint.toString().equals(provider.authorizationEndpoint.toString())) { render(true); return; }
                            if (pendingResult != null) consumeResult(pendingResultCode,pendingResult);
                            return;
                        }
                        try {
                            AuthorizationServiceConfiguration config=new AuthorizationServiceConfiguration(Uri.parse(provider.authorizationEndpoint.toString()),Uri.parse(provider.tokenEndpoint.toString()));
                            request=new AuthorizationRequest.Builder(config,store.getClientId(),ResponseTypeValues.CODE,Uri.parse(OidcRedirect.REDIRECT))
                                    .setScope("openid offline_access location:read").build();
                            if (request.state==null || request.nonce==null || request.codeVerifier==null || !"S256".equals(request.codeVerifierChallengeMethod)) throw new Exception();
                            service=new AuthorizationService(this);
                            startActivityForResult(service.getAuthorizationRequestIntent(request),BROWSER);
                        } catch (Exception failure) { render(true); }
                    });
                } catch (Exception failure) { main.post(() -> { if (!closed) render(true); }); }
            });
        } catch (Exception invalid) { render(true); }
    }

    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if (code!=BROWSER || exchanged || closed) return;
        if (provider==null && data!=null) { pendingResult=data; pendingResultCode=result; return; }
        consumeResult(result,data);
    }
    private void consumeResult(int result,Intent data) {
        if (exchanged || closed) return;
        exchanged=true; pendingResult=null;
        final String authorizationCode;
        try {
            if (result!=Activity.RESULT_OK || data==null || data.getData()==null || provider==null || request==null) throw new Exception();
            if (System.currentTimeMillis()/1000-startedAt>600 || store.epoch()!=expectedEpoch || !store.getApiBaseUrl().equals(expectedBase)) throw new Exception();
            authorizationCode=OidcRedirect.code(data.getData().toString(),request.state,provider.issuer);
        } catch (Exception invalid) { render(true); return; }
        render(false);
        worker.execute(() -> {
            try {
                long exchangeStarted=System.currentTimeMillis()/1000;
                String form="grant_type=authorization_code&code="+MobileTokenManager.enc(authorizationCode)
                        +"&client_id="+MobileTokenManager.enc(request.clientId)+"&redirect_uri="+MobileTokenManager.enc(OidcRedirect.REDIRECT)
                        +"&code_verifier="+MobileTokenManager.enc(request.codeVerifier);
                JSONObject response=new JSONObject(OidcTokenTransport.postForm(provider.tokenEndpoint,form));
                MobileTokenManager.TokenValues tokens=MobileTokenManager.TokenValues.parse(response,"",true);
                IdTokenVerifier.Identity identity=provider.verifyIdentityToken(response.getString("id_token"),request.clientId,request.nonce,tokens.access);
                if (closed || !store.saveTokensIfCurrent(tokens.access,tokens.refresh,identity.subject(),exchangeStarted+tokens.seconds,
                        request.nonce,expectedEpoch,expectedBase,identity.exportForEncryptedStorage())) throw new Exception();
                main.post(() -> { if (!closed && !isFinishing()) { completed=true; setResult(Activity.RESULT_OK); finish(); } });
            } catch (Exception failure) { main.post(() -> { if (!closed) render(true); }); }
        });
    }

    private void render(boolean failed) {
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int gap=(int)(24*getResources().getDisplayMetrics().density); layout.setPadding(gap,gap*3,gap,gap);
        TextView title=new TextView(this); title.setText(failed ? "로그인을 완료하지 못했어요" : "안전하게 로그인하고 있어요");
        title.setTextSize(26); title.setTypeface(null,1); title.setAccessibilityHeading(true); layout.addView(title);
        TextView description=new TextView(this);
        description.setText(failed ? (store!=null && !store.isSignInConfigured() ? "서비스 연결 정보가 준비되지 않았어요. 서비스 관리자에게 연결 설정을 확인해 주세요." : "인터넷 연결이나 로그인 상태를 확인한 뒤 다시 시도해 주세요.") : "브라우저에서 계정을 확인한 후 앱으로 돌아옵니다.");
        description.setTextSize(17); description.setPadding(0,gap,0,gap); description.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); layout.addView(description);
        if (!failed) layout.addView(new ProgressBar(this));
        MaterialButton done=new MaterialButton(this); done.setText(failed ? "돌아가기" : "취소"); done.setMinHeight((int)(48*getResources().getDisplayMetrics().density));
        done.setOnClickListener(view -> finish()); layout.addView(done);
        setContentView(layout);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (request!=null) out.putString("request",request.jsonSerializeString());
        out.putLong("startedAt",startedAt); out.putLong("epoch",expectedEpoch); out.putString("base",expectedBase); out.putBoolean("exchanged",exchanged);
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() { closed=true; worker.shutdownNow(); if (service!=null) service.dispose(); super.onDestroy(); }
    @Override public void finish() {
        if (!completed) {
            closed=true;
            if (store!=null && expectedBase!=null) {
                try { store.clearSessionIfCurrent(expectedEpoch,expectedBase); } catch (Exception ignored) { /* Credential reads remain encrypted and bounded. */ }
            }
        }
        super.finish();
    }
}
