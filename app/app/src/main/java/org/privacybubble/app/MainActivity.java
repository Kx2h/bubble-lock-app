package org.privacybubble.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import java.net.URI;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI for the unconnected client and explicitly isolated Mobius4 test read. */
public final class MainActivity extends Activity {
    private TestState testState;
    private LinearLayout body;
    private TextView testStatus;
    private Button testButton;
    private Button testAutoButton;
    private String role = "Guardian";
    private SharedPreferences settings;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        testState = retained instanceof TestState ? (TestState) retained : new TestState();
        settings = getSharedPreferences("connection", MODE_PRIVATE);
        if (state != null) role = state.getString("role", "Guardian");
        render();
        testState.listener = this::renderTestState;
        renderTestState();
    }
    @Override public Object onRetainNonConfigurationInstance() { return testState; }
    @Override protected void onStart() {
        super.onStart();
        testState.foreground();
    }
    @Override protected void onStop() {
        testState.background();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("role", role);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        testState.listener = null;
        if (!isChangingConfigurations()) testState.close();
        super.onDestroy();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }
    private void section(String title, String content) {
        TextView heading = text(title, 19, Color.rgb(26, 52, 79));
        heading.setTypeface(null, 1); body.addView(heading);
        body.addView(text(content, 16, Color.DKGRAY));
    }
    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(listener); return b;
    }
    private void render() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(24), dp(20), dp(32));
        body.setBackgroundColor(Color.rgb(247, 249, 252)); scroll.addView(body); setContentView(scroll);
        TextView title = text("Privacy Bubble", 28, Color.rgb(22, 47, 72)); title.setTypeface(null, 1); body.addView(title);
        body.addView(text("연결 준비 중 · 로그인되지 않음", 16, Color.rgb(138, 77, 0)));
        body.addView(text("역할 화면 선택: " + role + " (인증 또는 권한 변경 아님)", 16, Color.DKGRAY));
        LinearLayout roles = new LinearLayout(this); roles.setGravity(Gravity.CENTER_VERTICAL);
        Button guardian = button("Guardian", v -> { role = "Guardian"; render(); });
        Button visitor = button("Visitor", v -> { role = "Visitor"; render(); });
        guardian.setEnabled(!role.equals("Guardian")); visitor.setEnabled(!role.equals("Visitor"));
        roles.addView(guardian, new LinearLayout.LayoutParams(0, -2, 1)); roles.addView(visitor, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(roles);
        if (role.equals("Guardian")) {
            section("가정과 기기", "가정 연결 및 기기 등록: 서버 계약 대기 중");
            section("동의와 승인", "목적, 범위, 유효기간 확인 및 승인·회수: 서버 계약 대기 중");
            section("사건과 그룹 명령", "요청, 승인, 구성원별 ACK 및 결과: 서버 계약 대기 중");
        } else {
            section("방문자 접근", "초대 및 접근 범위·만료 확인: 서버 계약 대기 중");
            section("동의와 요청", "동의 및 사건 요청: 서버 계약 대기 중");
        }
        section("상태와 알림", "기기 · Gateway · 위치 · handover · FL · 알림: 서버 계약 대기 중");
        section("서버 설정", "추후 사용할 HTTPS API 주소만 저장합니다. 현재 인증이나 API 연결에는 사용되지 않습니다.");
        EditText endpoint = new EditText(this); endpoint.setSingleLine(true); endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint("https://example.org"); endpoint.setText(settings.getString("api_base", "")); body.addView(endpoint);
        TextView feedback = text("", 15, Color.DKGRAY); body.addView(feedback);
        body.addView(button("주소 저장", v -> {
            String value = endpoint.getText().toString().trim();
            if (value.isEmpty()) { settings.edit().remove("api_base").apply(); feedback.setText("주소를 삭제했습니다."); return; }
            try {
                URI uri = new URI(value);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
                settings.edit().putString("api_base", value).apply(); feedback.setText("저장됨 · 아직 연결되지 않음");
            } catch (Exception e) { feedback.setText("사용자 정보, 쿼리, 프래그먼트가 없는 HTTPS 주소를 입력하세요."); }
        }));
        if (BuildConfig.DEBUG) {
            section("합성 동의 시험", "실제 사용자나 권한에 영향을 주지 않는 로컬 모의 시험입니다.");
            body.addView(button("합성 동의 화면 열기", v -> {
                android.content.Intent intent = new android.content.Intent();
                intent.setClassName(this, "org.privacybubble.app.SyntheticConsentActivity");
                startActivity(intent);
            }));
            if (BuildConfig.OIDC_TEST_ENABLED) {
                section("인증된 합성 동의 시험", "별도 합성 인증 서버와 동의 서버를 사용합니다. 실제 로그인이나 권한 부여가 아닙니다.");
                body.addView(button("인증된 합성 동의 화면 열기", v -> {
                    android.content.Intent intent = new android.content.Intent();
                    intent.setClassName(this, "org.privacybubble.app.auth.AuthenticatedConsentActivity");
                    startActivity(intent);
                }));
            }
            section("Mobius4 테스트", "테스트 전용 상태 조회입니다. adb reverse와 PC 테스트 프록시가 필요합니다. 실제 권한이나 승인 상태가 아닙니다.");
            testStatus = text("조회 전", 16, Color.DKGRAY); body.addView(testStatus);
            testButton = button("테스트 상태 조회", v -> fetchTestState()); body.addView(testButton);
            testAutoButton = button("자동 갱신 시작", v -> testState.toggleAutomatic()); body.addView(testAutoButton);
            body.addView(button("합성 관측 조회 화면 열기", v -> {
                android.content.Intent intent = new android.content.Intent();
                intent.setClassName(this, "org.privacybubble.app.SyntheticObservationActivity");
                startActivity(intent);
            }));
            body.addView(button("합성 결과 조회 화면 열기", v -> {
                android.content.Intent intent = new android.content.Intent();
                intent.setClassName(this, "org.privacybubble.app.SyntheticReceiptActivity");
                startActivity(intent);
            }));
            renderTestState();
        }
    }
    private void renderTestState() {
        if (testStatus != null && testButton != null && testAutoButton != null) {
            testStatus.setText(testState.message + (testState.policy.isAutomatic()
                    ? "\n전경 자동 갱신 중 · 조회 완료 10초 후 다시 확인" : "\n자동 갱신 꺼짐"));
            testButton.setEnabled(testState.policy.isForeground() && !testState.policy.isInFlight());
            testAutoButton.setEnabled(testState.policy.isForeground());
            testAutoButton.setText(testState.policy.isAutomatic() ? "자동 갱신 중지" : "자동 갱신 시작");
        }
    }
    private void fetchTestState() {
        testState.fetch();
    }

    /** Retains request state across configuration changes; the Activity detaches on destroy. */
    private static final class TestState {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private final TestRefreshState policy = new TestRefreshState();
        private final Runnable poll = this::fetch;
        private String message = "조회 전";
        private boolean closed;
        private Runnable listener;

        private void notifyListener() { if (listener != null) listener.run(); }
        private void foreground() { if (!closed) { policy.foreground(); notifyListener(); } }
        private void background() {
            if (closed) return;
            policy.background(); main.removeCallbacks(poll);
            message = "화면을 떠나 상태를 확인할 수 없습니다 · 다시 조회하세요.";
            notifyListener();
        }
        private void toggleAutomatic() {
            if (closed || !policy.isForeground()) return;
            policy.setAutomatic(!policy.isAutomatic());
            main.removeCallbacks(poll);
            if (policy.isAutomatic() && !policy.isInFlight()) fetch();
            else notifyListener();
        }
        private void close() { closed = true; main.removeCallbacks(poll); listener = null; worker.shutdownNow(); }
        private void fetch() {
            if (closed) return;
            long ticket = policy.begin();
            if (ticket < 0) return;
            main.removeCallbacks(poll);
            message = "조회 중…"; notifyListener();
            worker.execute(() -> {
                String result;
                try {
                    String body = new TestStateClient(new URL("http://127.0.0.1:8787/api/test/bubble-state"), 250).read();
                    JSONObject json = new JSONObject(body);
                    if (!"test".equals(json.optString("mode")) || !(json.opt("state") instanceof Boolean)) throw new IllegalStateException("잘못된 테스트 응답");
                    boolean bootstrap = TestObservation.bootstrapPresent(json.has("observation"), json.opt("observation"),
                            json.has("bootstrapLabelPresent"), json.opt("bootstrapLabelPresent"));
                    String source = bootstrap ? "bootstrap 표식 존재 · 실제 관측 여부 미확인" : "실제 관측 여부 미확인";
                    String resourceTime = TestResourceTimestamp.rawOrNull(json.opt("resourceLastModifiedRaw"));
                    String checkedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date());
                    result = "테스트 상태: " + (json.getBoolean("state") ? "켜짐" : "꺼짐") + "\n" + source
                        + "\nCSE 자원 변경 시각 원문: " + (resourceTime == null ? "확인 불가" : resourceTime + " (시간대 미표기)")
                        + "\n자원 변경 시각은 기기 관측 시각이 아닙니다."
                        + "\n조회 시각: " + checkedAt + " (기기 시간)"
                        + "\n요청 ID: " + json.optString("requestId", "없음");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = "조회가 취소되었습니다.";
                } catch (Exception e) { result = "조회 실패 · 테스트 프록시와 adb reverse 연결을 확인하세요."; }
                final String completed = result;
                main.post(() -> {
                    if (closed) return;
                    boolean current = policy.finish(ticket);
                    if (current) message = completed;
                    notifyListener();
                    if (policy.isAutomatic()) {
                        if (current) main.postDelayed(poll, 10_000);
                        else fetch();
                    }
                });
            });
        }
    }
}
