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
        section("Mobius4 테스트", "테스트 전용 상태 조회입니다. adb reverse와 PC 테스트 프록시가 필요합니다. 실제 권한이나 승인 상태가 아닙니다.");
        testStatus = text("조회 전", 16, Color.DKGRAY); body.addView(testStatus);
        testButton = button("테스트 상태 조회", v -> fetchTestState()); body.addView(testButton);
        renderTestState();
    }
    private void renderTestState() {
        if (testStatus != null && testButton != null) {
            testStatus.setText(testState.message);
            testButton.setEnabled(!testState.inFlight);
        }
    }
    private void fetchTestState() {
        testState.fetch();
    }

    /** Retains request state across configuration changes; the Activity detaches on destroy. */
    private static final class TestState {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private String message = "조회 전";
        private boolean inFlight;
        private boolean closed;
        private Runnable listener;

        private void notifyListener() { if (listener != null) listener.run(); }
        private void close() { closed = true; listener = null; worker.shutdownNow(); }
        private void fetch() {
            if (closed || inFlight) return;
            inFlight = true; message = "조회 중…"; notifyListener();
            worker.execute(() -> {
                String result;
                try {
                    String body = new TestStateClient(new URL("http://127.0.0.1:8787/api/test/bubble-state"), 250).read();
                    JSONObject json = new JSONObject(body);
                    if (!"test".equals(json.optString("mode")) || !(json.opt("state") instanceof Boolean)) throw new IllegalStateException("잘못된 테스트 응답");
                    String observation = json.optString("observation", "unknown");
                    String source = "unobserved".equals(observation) ? "초기값 · 장치 관측 전" : "관측 여부 미확인";
                    String checkedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date());
                    result = "테스트 상태: " + (json.getBoolean("state") ? "켜짐" : "꺼짐") + "\n" + source
                        + "\n조회 시각: " + checkedAt + " (기기 시간)\n자동 갱신되지 않습니다."
                        + "\n요청 ID: " + json.optString("requestId", "없음");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = "조회가 취소되었습니다.";
                } catch (Exception e) { result = "조회 실패 · 테스트 프록시와 adb reverse 연결을 확인하세요."; }
                final String completed = result;
                main.post(() -> {
                    if (closed) return;
                    message = completed; inFlight = false; notifyListener();
                });
            });
        }
    }
}
