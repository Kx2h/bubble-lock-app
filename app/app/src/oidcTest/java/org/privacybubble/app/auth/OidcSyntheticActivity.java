package org.privacybubble.app.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit-only debug test for the local synthetic provider; never a real user login. */
public final class OidcSyntheticActivity extends Activity {
    private static final String ISSUER = "https://localhost:18443";
    private static final String CLIENT_ID = "privacy-bubble-android-test";
    private static final int AUTH_REQUEST = 3270;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private OidcBrowserFlow flow;
    private TextView status;
    private Button startButton;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setTextSize(19);
        title.setText("합성 OIDC 시험 · 실제 로그인이나 권한 부여가 아닙니다.");
        layout.addView(title);
        status = new TextView(this);
        status.setTextSize(17);
        status.setText("시험 준비");
        layout.addView(status);
        startButton = new Button(this);
        startButton.setText("합성 인증 시험 시작");
        startButton.setOnClickListener(view -> begin());
        layout.addView(startButton);
        setContentView(layout);
    }

    private void begin() {
        startButton.setEnabled(false);
        status.setText("합성 서버 연결 중…");
        worker.execute(() -> {
            try {
                OidcProvider provider = OidcProvider.discover(ISSUER);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    try {
                        flow = OidcBrowserFlow.forSyntheticTest(this, provider, CLIENT_ID, new OidcBrowserFlow.Callback() {
                            @Override public void onAuthenticated(OidcBrowserFlow.Session session) {
                                try {
                                    boolean expected = "synthetic-test-user".equals(session.subject());
                                    status.setText(expected ? "PASS: 합성 계정 검증 완료 · 실제 권한 없음" : "FAIL: 예상하지 못한 합성 계정");
                                } catch (RuntimeException error) {
                                    status.setText("FAIL: 합성 세션 만료");
                                } finally {
                                    session.close();
                                    startButton.setEnabled(true);
                                }
                            }
                            @Override public void onFailure() {
                                status.setText("FAIL: 합성 인증이 취소되었거나 검증에 실패했습니다.");
                                startButton.setEnabled(true);
                            }
                        });
                        flow.start(AUTH_REQUEST);
                    } catch (RuntimeException error) {
                        status.setText("FAIL: 합성 인증을 시작할 수 없습니다.");
                        startButton.setEnabled(true);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    status.setText("FAIL: 합성 서버 또는 TLS 연결 오류");
                    startButton.setEnabled(true);
                });
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != AUTH_REQUEST) return;
        if (flow == null || !flow.onActivityResult(requestCode, resultCode, data)) {
            status.setText("FAIL: 합성 인증 거래가 취소되었습니다.");
            startButton.setEnabled(true);
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (flow != null) flow.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
