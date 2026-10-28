package org.privacybubble.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit debug-only simulator. This screen does not grant real access. */
public final class SyntheticConsentActivity extends Activity {
    private State model;
    private TextView status;
    private CheckBox choice;
    private Button refresh, approve, revoke, retry, discard;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Object retained = getLastNonConfigurationInstance();
        model = retained instanceof State ? (State) retained : new State(getSharedPreferences("synthetic_consent_pending", MODE_PRIVATE));
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        scroll.addView(layout); setContentView(scroll);
        TextView title = new TextView(this);
        title.setTextSize(23); title.setTextColor(Color.rgb(25, 48, 75));
        title.setText("합성 동의 시험 · 실제 권한 없음"); layout.addView(title);
        TextView details = new TextView(this);
        details.setTextSize(16);
        details.setText("모의 요청자: synthetic-visitor\n목적: synthetic-location-check\n범위: test.location.read\n유효기간: 120초\n\n합성 서버와 adb reverse가 필요합니다. 실제 사용자, 위치 또는 권한을 사용하지 않습니다.");
        layout.addView(details);
        status = new TextView(this); status.setTextSize(16); status.setPadding(0, pad, 0, pad); layout.addView(status);
        choice = new CheckBox(this); choice.setText("이 합성 동작을 직접 선택합니다"); layout.addView(choice);
        refresh = new Button(this); refresh.setText("합성 상태 조회"); refresh.setAllCaps(false); layout.addView(refresh);
        approve = new Button(this); approve.setText("합성 승인"); approve.setAllCaps(false); layout.addView(approve);
        revoke = new Button(this); revoke.setText("합성 회수"); revoke.setAllCaps(false); layout.addView(revoke);
        retry = new Button(this); retry.setText("미확정 합성 요청 재전송"); retry.setAllCaps(false); layout.addView(retry);
        discard = new Button(this); discard.setText("차단된 합성 보류 기록 삭제"); discard.setAllCaps(false); layout.addView(discard);
        discard.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("합성 보류 기록 삭제")
                .setMessage("시험용 보류 기록만 지웁니다. 이전 요청 결과는 확인할 수 없으며 실제 권한에는 영향을 주지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("기록 삭제", (dialog, which) -> model.discardBlockedRecord())
                .show());
        refresh.setOnClickListener(v -> { choice.setChecked(false); model.refresh(); });
        approve.setOnClickListener(v -> { if (choice.isChecked()) { choice.setChecked(false); model.mutate(true); } });
        revoke.setOnClickListener(v -> { if (choice.isChecked()) { choice.setChecked(false); model.mutate(false); } });
        retry.setOnClickListener(v -> { if (choice.isChecked()) { choice.setChecked(false); model.retry(); } });
        choice.setOnCheckedChangeListener((button, checked) -> render());
        model.listener = this::render; render();
    }
    @Override public Object onRetainNonConfigurationInstance() { return model; }
    @Override protected void onDestroy() {
        model.listener = null;
        if (!isChangingConfigurations()) model.close();
        super.onDestroy();
    }
    private void render() {
        status.setText(model.message);
        refresh.setEnabled(!model.busy && !model.storageFault && model.pending == null);
        choice.setEnabled(!model.busy && !model.storageFault && !model.epochMismatch && (model.known || model.pending != null));
        approve.setEnabled(!model.busy && model.known && !model.active && choice.isChecked());
        revoke.setEnabled(!model.busy && model.known && model.active && choice.isChecked());
        retry.setEnabled(!model.busy && !model.storageFault && !model.epochMismatch && !model.known && model.pending != null && choice.isChecked());
        discard.setEnabled(!model.busy && (model.storageFault || model.epochMismatch));
    }

    private static final class State {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private static final String PENDING_KEY = "pending_v1";
        private final SharedPreferences preferences;
        private boolean busy, closed, known, active, storageFault, epochMismatch;
        private int grantRevision;
        private String serverInstance;
        private SyntheticPending pending;
        private String message = "합성 상태 미조회 · 먼저 조회하세요.";
        private Runnable listener;
        State(SharedPreferences preferences) {
            this.preferences = preferences;
            try {
                if (preferences.contains(PENDING_KEY)) {
                    pending = SyntheticPending.decode(preferences.getString(PENDING_KEY, null));
                    message = "저장된 합성 요청이 미확정입니다. 선택 후 같은 요청을 재전송하세요.";
                }
            } catch (Exception failure) {
                storageFault = true;
                message = "합성 보류 기록을 확인할 수 없어 변경을 차단했습니다. 차단된 합성 보류 기록만 삭제할 수 있습니다.";
            }
        }
        private void notifyUi() { if (listener != null) listener.run(); }
        private void close() { closed = true; listener = null; worker.shutdownNow(); }
        private void discardBlockedRecord() {
            if (closed || busy || !(storageFault || epochMismatch)) return;
            if (!preferences.edit().remove(PENDING_KEY).commit()) {
                storageFault = true;
                message = "합성 보류 기록을 삭제하지 못해 변경을 차단했습니다.";
            } else {
                pending = null; storageFault = false; epochMismatch = false; known = false; active = false; serverInstance = null;
                message = "합성 보류 기록을 삭제했습니다. 이전 요청 결과는 확인할 수 없습니다. 현재 상태를 다시 조회하세요.";
            }
            notifyUi();
        }
        private void refresh() {
            if (closed || busy || storageFault || pending != null) return;
            busy = true; known = false; message = "합성 상태 조회 중…"; notifyUi();
            worker.execute(() -> {
                Snapshot snapshot = null;
                try {
                    JSONObject request = call("request", null);
                    validateRequest(request);
                    String instance = instance(request);
                    Snapshot found = parseGrant(call("grant", null));
                    if (!instance.equals(found.instance)) throw new EpochMismatch();
                    snapshot = found;
                }
                catch (Exception ignored) { }
                final Snapshot result = snapshot;
                main.post(() -> {
                    if (closed) return;
                    busy = false;
                    if (result == null) { known = false; message = "합성 상태 미확정 · 연결 확인 후 다시 조회하세요."; }
                    else apply(result);
                    notifyUi();
                });
            });
        }
        private void mutate(boolean approving) {
            if (closed || busy || storageFault || !known || pending != null || approving == active) return;
            try {
                String body = approving ? SyntheticPending.APPROVE_BODY
                        : "{\"requestId\":\"synthetic-request-1\",\"grantRevision\":" + grantRevision + "}";
                SyntheticPending next = new SyntheticPending(serverInstance, approving ? "approve" : "revoke", body,
                        UUID.randomUUID().toString(), UUID.randomUUID().toString());
                // Synchronous persistence precedes every network mutation.
                if (!preferences.edit().putString(PENDING_KEY, next.encode()).commit()) throw new IllegalStateException();
                pending = next;
                sendPending();
            } catch (Exception failure) {
                storageFault = true; known = false;
                message = "합성 요청 보관에 실패해 전송을 차단했습니다. 차단된 합성 보류 기록만 삭제할 수 있습니다."; notifyUi();
            }
        }
        private void retry() {
            if (closed || busy || storageFault || epochMismatch || known || pending == null) return;
            sendPending();
        }
        private void sendPending() {
            final SyntheticPending operation = pending;
            busy = true; known = false; message = "합성 요청 처리 중…"; notifyUi();
            worker.execute(() -> {
                Snapshot snapshot = null;
                boolean differentInstance = false;
                try {
                    Snapshot before = parseGrant(call("grant", null));
                    if (!operation.instance.equals(before.instance)) throw new EpochMismatch();
                    JSONObject reply = call(operation.endpoint, operation);
                    Snapshot acknowledged = parseGrant(reply);
                    if (!operation.instance.equals(acknowledged.instance)) throw new EpochMismatch();
                    if (!(reply.opt("correlationId") instanceof String) || !operation.correlation.equals(reply.opt("correlationId"))
                            || acknowledged.active != operation.approving
                            || !(operation.approving ? "active" : "revoked").equals(acknowledged.status)) throw new IllegalStateException();
                    // A repeated receipt can be stale; only a fresh read describes current server state.
                    snapshot = parseGrant(call("grant", null));
                    if (!operation.instance.equals(snapshot.instance)) throw new EpochMismatch();
                } catch (EpochMismatch mismatch) { differentInstance = true; snapshot = null; }
                catch (Exception ignored) { }
                final Snapshot result = snapshot;
                final boolean mismatch = differentInstance;
                main.post(() -> {
                    if (closed) return;
                    busy = false;
                    if (mismatch) {
                        epochMismatch = true; known = false;
                        message = "합성 서버가 재시작되어 보류 요청을 재전송할 수 없습니다. 차단된 합성 보류 기록만 삭제할 수 있습니다.";
                    } else if (result == null) {
                        known = false; message = "합성 처리 결과 미확정 · 선택 후 저장된 같은 요청을 직접 재전송하세요.";
                    } else if (!preferences.edit().remove(PENDING_KEY).commit()) {
                        storageFault = true; known = false;
                        message = "합성 보류 기록을 정리하지 못해 변경을 차단했습니다.";
                    } else { pending = null; apply(result); }
                    notifyUi();
                });
            });
        }
        private void apply(Snapshot result) {
            known = true; active = result.active; grantRevision = result.revision; serverInstance = result.instance;
            message = "합성 서버 상태: " + result.status + (result.active ? "\n합성 만료 시각: " + result.expires : "")
                    + "\n서버 조회 시점의 상태입니다. 실제 권한은 없습니다.";
        }
        private static JSONObject call(String endpoint, SyntheticPending operation) throws Exception {
            if (!(endpoint.equals("request") || endpoint.equals("grant") || endpoint.equals("approve") || endpoint.equals("revoke"))) throw new IllegalArgumentException();
            byte[] payload = operation == null ? new byte[0] : operation.body.getBytes(StandardCharsets.UTF_8);
            String headers = (operation == null ? "GET " : "POST ") + "/api/mock-consent/v1/" + endpoint + " HTTP/1.0\r\n"
                    + "Host: 127.0.0.1:18787\r\nConnection: close\r\n"
                    + (operation == null ? "" : "Content-Type: application/json\r\nIdempotency-Key: "
                    + operation.key + "\r\nX-Correlation-ID: " + operation.correlation + "\r\nX-Mock-Instance-ID: " + operation.instance + "\r\nContent-Length: " + payload.length + "\r\n")
                    + "\r\n";
            // A single socket and a single write attempt: no client or framework POST retry.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 18787), 5000);
                socket.setSoTimeout(5000);
                java.io.OutputStream out = socket.getOutputStream();
                out.write(headers.getBytes(StandardCharsets.US_ASCII));
                if (payload.length != 0) out.write(payload);
                out.flush();
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                InputStream input = socket.getInputStream();
                byte[] bytes = new byte[1024]; int count;
                while ((count = input.read(bytes)) != -1) {
                    if (data.size() + count > 16384) throw new IllegalStateException();
                    data.write(bytes, 0, count);
                }
                byte[] response = data.toByteArray();
                String text = new String(response, StandardCharsets.UTF_8);
                int separator = text.indexOf("\r\n\r\n");
                if (separator < 0 || separator > 8192 || text.length() - separator - 4 > 8192
                        || !text.startsWith("HTTP/1.1 200 ")
                        || text.substring(0, separator).toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding:")) throw new IllegalStateException();
                return new JSONObject(text.substring(separator + 4));
            }
        }

        private static void validateRequest(JSONObject json) throws Exception {
            JSONObject r = json.getJSONObject("request"); JSONArray scopes = r.getJSONArray("scopes");
            if (scopes.length() != 1) throw new IllegalStateException();
            SyntheticConsentValidator.request(json.opt("mode"), r.opt("requestId"), r.opt("principal"), r.opt("purpose"),
                    scopes.opt(0), r.opt("revision"), r.opt("ttlSeconds"));
        }
        private static String instance(JSONObject json) throws Exception {
            Object id = json.opt("serverInstanceId");
            if (!(id instanceof String) || !SyntheticPending.uuid((String) id)) throw new IllegalStateException();
            return (String) id;
        }
        private static Snapshot parseGrant(JSONObject json) throws Exception {
            String serverInstance = instance(json);
            if (!json.has("grant")) throw new IllegalStateException();
            boolean grantNull = json.isNull("grant");
            JSONObject g = grantNull ? null : json.getJSONObject("grant");
            SyntheticConsentValidator.grant(json.opt("mode"), json.opt("status"), grantNull,
                    g == null ? null : g.opt("requestId"), g == null ? null : g.opt("consentRevision"),
                    g == null ? null : g.opt("grantRevision"), g == null ? null : g.opt("expiresAt"),
                    g == null ? null : g.opt("receiptId"));
            String status = (String) json.get("status");
            return new Snapshot(status, g == null ? 0 : (Integer) g.get("grantRevision"),
                    g == null ? "" : (String) g.get("expiresAt"), serverInstance);
        }

    }
    private static final class EpochMismatch extends Exception { }
    private static final class Snapshot {
        final String status, expires, instance; final boolean active; final int revision;
        Snapshot(String status, int revision, String expires, String instance) {
            this.status = status; this.active = status.equals("active"); this.revision = revision; this.expires = expires; this.instance = instance;
        }
    }
}
