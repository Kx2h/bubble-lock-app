package org.privacybubble.app.auth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Opt-in-only synthetic authenticated consent test. Not an operating authorization UI. */
public final class AuthenticatedConsentActivity extends Activity {
    private static final String ISSUER = "https://localhost:18443";
    private static final String CLIENT = "privacy-bubble-android-test";
    private static final String API = "https://localhost:18788/api/mock-consent/v1/";
    private static final String MARKER = "unresolved_mutation";
    private static final int AUTH_CODE = 4811;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private OidcBrowserFlow flow;
    private OidcBrowserFlow.Session session;
    private TextView status;
    private CheckBox choice;
    private Button login, logout, load, approve, revoke, retry, discard;
    private volatile boolean destroyed;
    private boolean busy, known, active, uncertain, storageFailure;
    private int revision;
    private String instance;
    private Pending pending;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("synthetic_authenticated_consent_marker", MODE_PRIVATE);
        try { uncertain = preferences.getBoolean(MARKER, false); } catch (RuntimeException failure) { storageFailure = true; }
        ScrollView scroll = new ScrollView(this); LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24, 24, 24, 24);
        TextView heading = new TextView(this); heading.setTextSize(20);
        heading.setText("합성 인증 동의 시험 · 실제 사용자 또는 권한이 아닙니다"); layout.addView(heading);
        TextView detail = new TextView(this); detail.setText("대상: synthetic-visitor\n목적: synthetic-location-check\n범위: test.location.read\n유효기간: 120초\n합성 서버만 사용합니다."); layout.addView(detail);
        status = new TextView(this); status.setTextSize(16); layout.addView(status);
        login = button(layout, "합성 인증 시작", v -> begin());
        logout = button(layout, "합성 세션 종료", v -> endSession());
        load = button(layout, "합성 상태 조회", v -> load());
        choice = new CheckBox(this); choice.setText("이 합성 동작을 직접 선택합니다"); layout.addView(choice);
        approve = button(layout, "합성 승인", v -> { if (choice.isChecked()) { choice.setChecked(false); mutate(true); } });
        revoke = button(layout, "합성 회수", v -> { if (choice.isChecked()) { choice.setChecked(false); mutate(false); } });
        retry = button(layout, "미확정 합성 요청 재전송", v -> { if (choice.isChecked()) { choice.setChecked(false); resend(); } });
        discard = button(layout, "합성 미확정 표시 삭제", v -> new AlertDialog.Builder(this)
                .setTitle("합성 기록 삭제")
                .setMessage("표시만 삭제합니다. 이전 요청 결과는 확인할 수 없으며 실제 권한에는 영향이 없습니다.")
                .setNegativeButton("취소", null).setPositiveButton("삭제", (dialog, which) -> discardMarker()).show());
        choice.setOnCheckedChangeListener((button, checked) -> render());
        scroll.addView(layout); setContentView(scroll);
        status.setText(storageFailure ? "합성 기록을 읽을 수 없습니다." : uncertain ? "이전 합성 요청 결과 미확정 · 새 변경 차단" : "인증 전 · 실제 권한 없음");
        render();
    }
    private Button button(LinearLayout layout, String text, android.view.View.OnClickListener listener) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(listener); layout.addView(b); return b;
    }
    private void render() {
        boolean loggedIn = session != null && !destroyed;
        login.setEnabled(!busy && !loggedIn);
        logout.setEnabled(!busy && loggedIn);
        load.setEnabled(!busy && loggedIn && !storageFailure);
        choice.setEnabled(!busy && loggedIn && !storageFailure && (known || pending != null));
        approve.setEnabled(!busy && loggedIn && known && !active && !uncertain && choice.isChecked());
        revoke.setEnabled(!busy && loggedIn && known && active && !uncertain && choice.isChecked());
        retry.setEnabled(!busy && loggedIn && uncertain && pending != null && choice.isChecked());
        discard.setEnabled(!busy && (uncertain || storageFailure));
    }
    private void begin() {
        if (busy || session != null) return; busy = true; status.setText("합성 인증 서버 연결 중…"); render();
        worker.execute(() -> {
            try {
                OidcProvider provider = OidcProvider.discover(ISSUER);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    try {
                        flow = OidcBrowserFlow.forSyntheticConsentTest(this, provider, CLIENT, new OidcBrowserFlow.Callback() {
                            @Override public void onAuthenticated(OidcBrowserFlow.Session received) {
                                try {
                                    if (!"synthetic-test-user".equals(received.subject())) throw new IllegalStateException();
                                    session = received; busy = false;
                                    status.setText(uncertain ? "합성 인증 완료 · 이전 요청 결과 미확정, 새 변경 차단" : "합성 인증 완료 · 서버 상태를 조회하세요");
                                } catch (RuntimeException failure) { received.close(); session = null; busy = false; status.setText("합성 인증 실패"); }
                                render();
                            }
                            @Override public void onFailure() { busy = false; status.setText("합성 인증 취소 또는 실패"); render(); }
                        });
                        flow.start(AUTH_CODE);
                    } catch (RuntimeException failure) { busy = false; status.setText("합성 인증을 시작할 수 없습니다"); render(); }
                });
            } catch (Exception failure) { uiFailure("합성 인증 서버 또는 TLS 연결 실패"); }
        });
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AUTH_CODE && (flow == null || !flow.onActivityResult(requestCode, resultCode, data))) {
            busy = false; status.setText("합성 인증 거래 취소"); render();
        }
    }
    private void load() {
        if (busy || session == null || storageFailure) return;
        busy = true; known = false; status.setText("합성 서버 상태 확인 중…"); render();
        worker.execute(() -> {
            try {
                JSONObject request = call("request", null, null, session.accessToken());
                String expectedInstance = validateRequest(request);
                Snapshot snapshot = parseGrant(call("grant", null, null, session.accessToken()));
                if (!expectedInstance.equals(snapshot.instance)) throw new IllegalStateException();
                runOnUiThread(() -> { if (!live()) return; busy = false; apply(snapshot); render(); });
            } catch (Exception failure) { uiFailure("합성 상태를 확인할 수 없습니다 · 변경 차단"); }
        });
    }
    private void mutate(boolean approving) {
        if (busy || session == null || !known || uncertain || storageFailure || approving == active) return;
        try {
            JSONObject body = new JSONObject(); body.put("requestId", "synthetic-request-1");
            if (approving) { body.put("revision", 1); body.put("purpose", "synthetic-location-check"); body.put("scopes", new JSONArray().put("test.location.read")); body.put("ttlSeconds", 120); }
            else body.put("grantRevision", revision);
            Pending next = new Pending(approving ? "approve" : "revoke", body.toString(), instance, approving);
            // Persist only a non-identifying synthetic marker, before any network mutation.
            if (!preferences.edit().putBoolean(MARKER, true).commit()) { storageFailure = true; throw new IllegalStateException(); }
            uncertain = true; pending = next; known = false; send(next);
        } catch (Exception failure) { known = false; status.setText("합성 요청 보관 실패 · 변경 차단"); render(); }
    }
    private void resend() { if (!busy && uncertain && pending != null && session != null) send(pending); }
    private void send(Pending op) {
        busy = true; status.setText("합성 요청 처리 중…"); render();
        worker.execute(() -> {
            try {
                String token = session.accessToken();
                Snapshot before = parseGrant(call("grant", null, null, token));
                if (!op.instance.equals(before.instance) || destroyed || Thread.currentThread().isInterrupted()) throw new IllegalStateException();
                // Recheck that closing the Activity did not invalidate the session during preflight.
                token = session.accessToken();
                JSONObject ack = call(op.endpoint, op.body, op, token);
                Snapshot accepted = parseGrant(ack);
                if (!op.instance.equals(accepted.instance) || !op.correlation.equals(ack.opt("correlationId"))
                        || !(op.approving ? "active" : "revoked").equals(accepted.status)) throw new IllegalStateException();
                Snapshot latest = parseGrant(call("grant", null, null, session.accessToken()));
                if (!op.instance.equals(latest.instance)) throw new IllegalStateException();
                runOnUiThread(() -> {
                    if (!live()) return; busy = false;
                    if (!preferences.edit().remove(MARKER).commit()) { storageFailure = true; known = false; status.setText("합성 기록 정리 실패 · 변경 차단"); }
                    else { uncertain = false; pending = null; apply(latest); }
                    render();
                });
            } catch (Exception failure) { uiFailure("합성 처리 결과 미확정 · 자동 재시도 없음"); }
        });
    }
    private void endSession() {
        if (busy || session == null) return;
        session.close(); session = null; known = false; choice.setChecked(false);
        status.setText(uncertain ? "합성 세션 종료 · 이전 요청 결과 미확정" : "합성 세션 종료 · 실제 권한 없음"); render();
    }
    private void discardMarker() {
        if (busy || !(uncertain || storageFailure)) return;
        if (!preferences.edit().remove(MARKER).commit()) { storageFailure = true; status.setText("합성 표시 삭제 실패 · 변경 차단"); }
        else { storageFailure = false; uncertain = false; pending = null; known = false; status.setText("합성 표시만 삭제됨 · 이전 결과 확인 불가; 상태를 다시 조회하세요"); }
        render();
    }
    private boolean live() { return !destroyed && !isFinishing(); }
    private void uiFailure(String message) { runOnUiThread(() -> { if (!live()) return; busy = false; known = false; status.setText(message); render(); }); }
    private void apply(Snapshot s) {
        known = true; active = "active".equals(s.status); revision = s.revision; instance = s.instance;
        status.setText("합성 서버 상태: " + s.status + (active ? "\n합성 만료 시각: " + s.expires : "")
                + (uncertain ? "\n이전 합성 처리 결과 미확정 · 새 변경 차단" : "") + "\n실제 권한은 없습니다.");
    }
    private static JSONObject call(String endpoint, String body, Pending op, String accessToken) throws Exception {
        if (!(endpoint.equals("request") || endpoint.equals("grant") || endpoint.equals("approve") || endpoint.equals("revoke"))) throw new IllegalArgumentException();
        if (accessToken == null || accessToken.length() > 16384 || accessToken.isEmpty()) throw new IllegalArgumentException();
        ResponseDeadline deadline = new ResponseDeadline(System::nanoTime, 10_000_000_000L);
        HttpsURLConnection conn = (HttpsURLConnection) new URL(API + endpoint).openConnection();
        conn.setConnectTimeout(5000); conn.setReadTimeout(5000); conn.setInstanceFollowRedirects(false); conn.setUseCaches(false);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken); conn.setRequestProperty("Accept", "application/json");
        try {
            if (op != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setFixedLengthStreamingMode(payload.length);
                conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Idempotency-Key", op.key);
                conn.setRequestProperty("X-Correlation-ID", op.correlation); conn.setRequestProperty("X-Mock-Instance-ID", op.instance);
                try (OutputStream out = conn.getOutputStream()) { out.write(payload); }
            } else conn.setRequestMethod("GET");
            deadline.check();
            if (conn.getResponseCode() != 200) throw new IllegalStateException();
            deadline.check();
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(deadline.read(in, 16384));
            }
        } finally { conn.disconnect(); }
    }
    private static String string(JSONObject o, String key) throws Exception { Object value = o.get(key); if (!(value instanceof String)) throw new IllegalStateException(); return (String) value; }
    private static int integer(JSONObject o, String key) throws Exception { Object value = o.get(key); if (!(value instanceof Integer)) throw new IllegalStateException(); return (Integer) value; }
    private static String instance(JSONObject obj) throws Exception {
        String value = string(obj, "serverInstanceId"); UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value) || uuid.version() != 4 || uuid.variant() != 2) throw new IllegalStateException(); return value;
    }
    private static String validateRequest(JSONObject json) throws Exception {
        if (!"synthetic".equals(string(json,"mode"))) throw new IllegalStateException(); String id = instance(json);
        JSONObject req = json.getJSONObject("request"); JSONArray scopes = req.getJSONArray("scopes");
        if (!"synthetic-request-1".equals(string(req,"requestId")) || !"synthetic-visitor".equals(string(req,"principal"))
                || !"synthetic-location-check".equals(string(req,"purpose")) || integer(req,"revision") != 1 || integer(req,"ttlSeconds") != 120
                || scopes.length() != 1 || !(scopes.get(0) instanceof String) || !"test.location.read".equals(scopes.getString(0))) throw new IllegalStateException();
        return id;
    }
    private static Snapshot parseGrant(JSONObject json) throws Exception {
        if (!"synthetic".equals(string(json,"mode"))) throw new IllegalStateException(); String id = instance(json), state = string(json,"status");
        if (!(state.equals("none") || state.equals("active") || state.equals("revoked") || state.equals("expired")) || !json.has("grant")) throw new IllegalStateException();
        if (state.equals("none")) { if (!json.isNull("grant")) throw new IllegalStateException(); return new Snapshot(id, state, 0, ""); }
        JSONObject grant = json.getJSONObject("grant"); int rev = integer(grant,"grantRevision"); String expires = string(grant,"expiresAt");
        if (!"synthetic-request-1".equals(string(grant,"requestId")) || integer(grant,"consentRevision") != 1 || rev < 1
                || !string(grant,"receiptId").matches("synthetic-[A-Za-z0-9-]{16,64}") || !expires.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")) throw new IllegalStateException();
        Instant.parse(expires); return new Snapshot(id, state, rev, expires);
    }
    private static final class Snapshot { final String instance,status,expires; final int revision; Snapshot(String i,String s,int r,String e) { instance=i;status=s;revision=r;expires=e; } }
    private static final class Pending { final String endpoint,body,instance,key,correlation; final boolean approving; Pending(String e,String b,String i,boolean a) { endpoint=e;body=b;instance=i;approving=a;key=UUID.randomUUID().toString();correlation=UUID.randomUUID().toString(); } }
    @Override protected void onDestroy() {
        destroyed = true; if (flow != null) flow.close(); if (session != null) session.close(); worker.shutdownNow(); super.onDestroy();
    }
}
