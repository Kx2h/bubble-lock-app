package org.privacybubble.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, read-only view of the fixed isolated test fixture; never operational data. */
public final class SyntheticObservationActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button load;
    private boolean stopped;
    private int generation;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        TextView heading = new TextView(this); heading.setTextSize(22);
        heading.setText("합성 관측 조회 · 테스트 전용"); body.addView(heading);
        TextView info = new TextView(this); info.setTextSize(16);
        info.setText("격리 시험 CSE에 마지막으로 저장된 합성 관측입니다. 실제 기기·위치 또는 현재 장치 상태가 아닙니다. RSSI는 거리가 아닙니다."); body.addView(info);
        status = new TextView(this); status.setTextSize(16); status.setPadding(0, padding, 0, padding); status.setText("조회 전"); body.addView(status);
        load = new Button(this); load.setText("합성 관측 조회"); load.setAllCaps(false); load.setOnClickListener(v -> fetch()); body.addView(load);
        scroll.addView(body); setContentView(scroll);
    }
    @Override protected void onStart() { super.onStart(); stopped = false; }
    @Override protected void onStop() { stopped = true; generation++; load.setEnabled(true); status.setText("화면 이탈 · 다시 조회하세요"); super.onStop(); }
    @Override protected void onDestroy() { stopped = true; generation++; worker.shutdownNow(); super.onDestroy(); }
    private void fetch() {
        if (stopped || !load.isEnabled()) return;
        int ticket = ++generation; load.setEnabled(false); status.setText("합성 관측 조회 중…");
        worker.execute(() -> {
            String text;
            try {
                String body = new TestStateClient(new URL("http://127.0.0.1:8787/api/test/observation"), 250).read();
                JSONObject o = new JSONObject(body);
                if (!"synthetic".equals(o.opt("mode")) || !"app-isolated".equals(o.opt("gatewayId"))
                        || !"synthetic-keyring".equals(o.opt("deviceId")) || !"gateway-received".equals(o.opt("timestampSource"))
                        || !(o.opt("state") instanceof Boolean) || !(o.opt("sequence") instanceof Number)) throw new IllegalStateException();
                long sequence = o.getLong("sequence");
                if (sequence < 0 || sequence > 4294967295L || ((Number) o.get("sequence")).doubleValue() != sequence) throw new IllegalStateException();
                String observed = o.getString("observedAt"), received = o.getString("receivedAt");
                if (!"2026-01-01T00:00:00Z".equals(observed) || !"2026-01-01T00:00:00Z".equals(received)) throw new IllegalStateException();
                Object battery = o.get("battery"), rssi = o.get("rssi");
                if (!(battery == JSONObject.NULL || battery instanceof Number) || !(rssi == JSONObject.NULL || rssi instanceof Number)) throw new IllegalStateException();
                double b = battery == JSONObject.NULL ? 0 : ((Number) battery).doubleValue();
                double r = rssi == JSONObject.NULL ? 0 : ((Number) rssi).doubleValue();
                if ((battery != JSONObject.NULL && (!Double.isFinite(b) || b < 0 || b > 100)) || (rssi != JSONObject.NULL && (!Double.isFinite(r) || r < -127 || r > 20 || r != Math.rint(r)))) throw new IllegalStateException();
                text = "CSE에 마지막 저장된 합성 관측 · 현재 상태 아님\n합성 기기: synthetic-keyring\n순서: " + sequence
                        + "\n합성 값: " + (o.getBoolean("state") ? "켜짐" : "꺼짐")
                        + "\nGateway 수신 기반 관측 시각: " + observed + "\nGateway 수신 기록: " + received
                        + "\n배터리: " + (battery == JSONObject.NULL ? "미확인" : battery + "%")
                        + "\nRSSI: " + (rssi == JSONObject.NULL ? "미확인" : rssi + " dBm") + " (거리 아님)"
                        + "\n조회 시각: " + DateFormat.getDateTimeInstance().format(new Date()) + " (기기 시간)";
            } catch (Exception failure) { text = "합성 관측을 확인할 수 없습니다 · 시험 중계와 CSE를 확인하세요"; }
            final String result = text;
            runOnUiThread(() -> { if (!stopped && ticket == generation && !isFinishing()) { status.setText(result); load.setEnabled(true); } });
        });
    }
}
