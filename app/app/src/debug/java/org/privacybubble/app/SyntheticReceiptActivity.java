package org.privacybubble.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.net.URL;
import java.time.Instant;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Isolated synthetic receipt only; it never sends or authorizes a command. */
public final class SyntheticReceiptActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TestRefreshState refresh = new TestRefreshState();
    private final SyntheticReceiptMonitor monitor = new SyntheticReceiptMonitor();
    private final Runnable poll = this::fetch;
    private TextView status, notice, refreshStatus;
    private Button load, automatic;
    private boolean destroyed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setTextSize(22); title.setText("합성 결과 조회 · 테스트 전용"); body.addView(title);
        TextView info = new TextView(this); info.setTextSize(16);
        info.setText("격리 CSE에 마지막 저장된 합성 결과입니다. 실제 승인·기기 동작이나 현재 상태가 아닙니다. 자동 조회는 이 화면의 표시 내용 변화만 확인하며 새 사건이나 실행 순서를 뜻하지 않습니다."); body.addView(info);
        notice = new TextView(this); notice.setTextSize(16);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setText("조회 전 · 화면 내 시험 안내만 표시합니다"); body.addView(notice);
        status = new TextView(this); status.setTextSize(16); status.setPadding(0, pad, 0, pad); status.setText("조회 전"); body.addView(status);
        load = new Button(this); load.setText("합성 결과 조회"); load.setAllCaps(false); load.setOnClickListener(v -> fetch()); body.addView(load);
        automatic = new Button(this); automatic.setAllCaps(false); automatic.setOnClickListener(v -> toggleAutomatic()); body.addView(automatic);
        refreshStatus = new TextView(this); refreshStatus.setTextSize(16); body.addView(refreshStatus);
        scroll.addView(body); setContentView(scroll);
        renderControls();
    }
    @Override protected void onStart() { super.onStart(); refresh.foreground(); renderControls(); }
    @Override protected void onStop() {
        refresh.background(); main.removeCallbacks(poll); monitor.reset();
        status.setText("화면 이탈 · 다시 조회하세요"); notice.setText("비교 기준 초기화 · 다시 조회한 결과부터 확인합니다");
        renderControls(); super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacks(poll); worker.shutdownNow(); super.onDestroy();
    }
    private void renderControls() {
        load.setEnabled(refresh.isForeground() && !refresh.isInFlight());
        automatic.setEnabled(refresh.isForeground());
        automatic.setText(refresh.isAutomatic() ? "자동 조회 중지" : "자동 조회 시작");
        refreshStatus.setText(refresh.isAutomatic() ? "전경 자동 조회 중 · 완료 10초 후 재조회" : "자동 조회 꺼짐 · 백그라운드 알림 없음");
    }
    private void toggleAutomatic() {
        if (destroyed || !refresh.isForeground()) return;
        refresh.setAutomatic(!refresh.isAutomatic()); main.removeCallbacks(poll);
        if (refresh.isAutomatic() && !refresh.isInFlight()) fetch();
        else renderControls();
    }
    private static String time(JSONObject obj, String name) throws Exception {
        Object value = obj.get(name);
        if (value == JSONObject.NULL) return "미확인";
        if (!(value instanceof String)) throw new IllegalStateException();
        Instant.parse((String) value);
        return (String) value;
    }
    private void fetch() {
        if (destroyed) return;
        long ticket = refresh.begin();
        if (ticket < 0) return;
        main.removeCallbacks(poll); status.setText("합성 결과 조회 중…"); notice.setText("조회 중 · 결과 확인 대기"); renderControls();
        worker.execute(() -> {
            String text;
            String comparison = null;
            try {
                JSONObject r = new JSONObject(new TestStateClient(new URL("http://127.0.0.1:8787/api/test/receipt"), 250).read());
                if (!"synthetic".equals(r.opt("mode")) || !"app-isolated".equals(r.opt("gatewayId"))
                        || !"synthetic-keyring".equals(r.opt("deviceId")) || !"synthetic-command-1".equals(r.opt("commandId"))) throw new IllegalStateException();
                Object state = r.get("status"), code = r.get("ackCode"), source = r.get("timestampSource");
                if (!(state instanceof String) || !(code instanceof String) || !((String) code).matches("[A-Z][A-Z0-9_]{0,63}")
                        || !(source instanceof String) || !(source.equals("unavailable") || source.equals("gateway-estimated"))) throw new IllegalStateException();
                String label = SyntheticReceiptLabels.label((String) state);
                String accepted = time(r,"acceptedAt"), applied = time(r,"appliedAt"), received = time(r,"receivedAt");
                if (received.equals("미확인") || (source.equals("unavailable") && !(accepted.equals("미확인") && applied.equals("미확인")))
                        || (source.equals("gateway-estimated") && accepted.equals("미확인"))) throw new IllegalStateException();
                Object readback = r.get("readback"); String gpio;
                if (readback == JSONObject.NULL) gpio = "미확인";
                else if (readback instanceof JSONObject && ((JSONObject) readback).length() == 1 && ((JSONObject) readback).opt("state") instanceof Boolean)
                    gpio = ((JSONObject) readback).getBoolean("state") ? "켜짐" : "꺼짐";
                else throw new IllegalStateException();
                text = "합성 결과: " + label + "\n합성 ackCode: " + code
                    + "\n시각 출처: " + (source.equals("gateway-estimated") ? "Gateway 추정" : "미확인")
                    + "\n수락 시각: " + accepted + "\n적용 시각: " + applied + "\nGateway 수신 시각: " + received
                    + "\nGPIO readback: " + gpio + "\nGPIO 값은 물리 진동 확인이 아닙니다.";
                // Compare only validated display content. Per-request IDs and the
                // local query time must never turn an unchanged poll into a change.
                comparison = text;
                text += "\n조회 시각: " + DateFormat.getDateTimeInstance().format(new Date()) + " (기기 시간)";
            } catch (Exception failure) { text = "합성 결과를 확인할 수 없습니다 · 시험 중계와 CSE를 확인하세요"; }
            final String result = text;
            final String content = comparison;
            main.post(() -> {
                if (destroyed) return;
                boolean current = refresh.finish(ticket);
                if (current && !isFinishing()) {
                    status.setText(result);
                    if (content == null) { monitor.failed(); notice.setText("조회 실패 · 이전 결과로 현재 내용을 추정하지 않습니다"); }
                    else {
                        switch (monitor.accept(content)) {
                            case FIRST: notice.setText("첫 조회 완료 · 이후 표시 내용과 비교합니다"); break;
                            case SAME: notice.setText("표시 내용 변화 없음 · 같은 사건이라는 보장은 없습니다"); break;
                            case CHANGED: notice.setText("합성 표시 내용 변화 확인 · 새 사건이나 기기 동작을 뜻하지 않습니다"); break;
                            case RECONFIRMED: notice.setText("조회 복구 · 비교 기준을 다시 설정했습니다"); break;
                        }
                    }
                }
                renderControls();
                if (refresh.isAutomatic() && !isFinishing()) {
                    if (current) main.postDelayed(poll, 10_000);
                    else fetch();
                }
            });
        });
    }
}
