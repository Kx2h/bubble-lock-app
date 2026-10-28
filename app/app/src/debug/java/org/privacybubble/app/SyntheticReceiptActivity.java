package org.privacybubble.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.net.URL;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Isolated synthetic receipt only; it never sends or authorizes a command. */
public final class SyntheticReceiptActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button load;
    private boolean stopped;
    private int generation;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setTextSize(22); title.setText("합성 결과 조회 · 테스트 전용"); body.addView(title);
        TextView info = new TextView(this); info.setTextSize(16);
        info.setText("격리 CSE에 저장된 합성 결과입니다. 실제 승인·기기 동작이나 현재 상태가 아닙니다."); body.addView(info);
        status = new TextView(this); status.setTextSize(16); status.setPadding(0, pad, 0, pad); status.setText("조회 전"); body.addView(status);
        load = new Button(this); load.setText("합성 결과 조회"); load.setAllCaps(false); load.setOnClickListener(v -> fetch()); body.addView(load);
        scroll.addView(body); setContentView(scroll);
    }
    @Override protected void onStart() { super.onStart(); stopped = false; }
    @Override protected void onStop() { stopped = true; generation++; load.setEnabled(true); status.setText("화면 이탈 · 다시 조회하세요"); super.onStop(); }
    @Override protected void onDestroy() { stopped = true; generation++; worker.shutdownNow(); super.onDestroy(); }
    private static String time(JSONObject obj, String name) throws Exception {
        Object value = obj.get(name);
        if (value == JSONObject.NULL) return "미확인";
        if (!(value instanceof String)) throw new IllegalStateException();
        Instant.parse((String) value);
        return (String) value;
    }
    private void fetch() {
        if (stopped || !load.isEnabled()) return;
        int ticket = ++generation; load.setEnabled(false); status.setText("합성 결과 조회 중…");
        worker.execute(() -> {
            String text;
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
            } catch (Exception failure) { text = "합성 결과를 확인할 수 없습니다 · 시험 중계와 CSE를 확인하세요"; }
            final String result = text;
            runOnUiThread(() -> { if (!stopped && ticket == generation && !isFinishing()) { status.setText(result); load.setEnabled(true); } });
        });
    }
}
