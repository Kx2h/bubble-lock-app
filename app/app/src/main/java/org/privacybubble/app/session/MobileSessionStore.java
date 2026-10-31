package org.privacybubble.app.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import org.privacybubble.app.BuildConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials are encrypted with a non-exportable Android Keystore key, never in saved UI state. */
public final class MobileSessionStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "privacy-bubble.mobile.session.v1";
    private static final byte[] AAD = "org.privacybubble.app/mobile-session/v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences preferences;

    public MobileSessionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("mobile_session", Context.MODE_PRIVATE);
    }

    public String getApiBaseUrl() { return preferences.getString("api_base", BuildConfig.MOBILE_API_BASE_URL); }
    public String getIssuer() { return BuildConfig.OIDC_ISSUER; }
    public String getClientId() { return BuildConfig.OIDC_CLIENT_ID; }
    public boolean isSignInConfigured() {
        return !getApiBaseUrl().isEmpty() && !getIssuer().isEmpty() && !getClientId().isEmpty();
    }

    public void saveConnection(String baseUrl, String issuer, String clientId) {
        String normalized = validateBase(baseUrl);
        if (!getIssuer().equals(issuer == null ? "" : issuer) || !getClientId().equals(clientId == null ? "" : clientId))
            throw new IllegalArgumentException("로그인 서비스 설정을 확인해 주세요.");
        if (!BuildConfig.DEBUG && !BuildConfig.MOBILE_API_BASE_URL.isEmpty()
                && !validateBase(BuildConfig.MOBILE_API_BASE_URL).equals(normalized))
            throw new IllegalArgumentException("등록된 서비스 주소를 사용해 주세요.");
        synchronized (LOCK) {
            if (!getApiBaseUrl().equals(normalized)) clearSession();
            if (!preferences.edit().putString("api_base", normalized).commit()) throw new IllegalStateException("설정을 저장하지 못했습니다.");
        }
    }

    private static String validateBase(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 2048 || !value.equals(value.trim())) throw new Exception();
            URI uri = new URI(value);
            boolean loopback = BuildConfig.DEBUG && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 8787;
            if ((!"https".equals(uri.getScheme()) && !loopback) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().matches("/?"))) throw new Exception();
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (Exception error) { throw new IllegalArgumentException("HTTPS 서비스 주소를 확인해 주세요."); }
    }

    public String getAccessToken() {
        synchronized (LOCK) {
            JSONObject value = read();
            if (value == null || value.optLong("expires", 0) <= System.currentTimeMillis() / 1000 + 15) return "";
            return value.optString("access", "");
        }
    }

    public String getRefreshToken() { synchronized (LOCK) { JSONObject value = read(); return value == null ? "" : value.optString("refresh", ""); } }
    public String getSubject() { synchronized (LOCK) { JSONObject value = read(); return value == null ? "" : value.optString("subject", ""); } }
    public String getNonce() { synchronized (LOCK) { JSONObject value = read(); return value == null ? "" : value.optString("nonce", ""); } }
    public long epoch() { synchronized (LOCK) { return preferences.getLong("epoch", 0); } }

    /** Consume the refresh credential durably before its single network attempt. */
    public RefreshTicket takeRefreshTicket() {
        synchronized (LOCK) {
            JSONObject value = read();
            if (value == null || value.optString("refresh", "").isEmpty()) return null;
            clearSession();
            return new RefreshTicket(value.optString("refresh"), value.optString("subject"), value.optString("nonce"), value.optString("identity", ""),
                    getApiBaseUrl(), getIssuer(), getClientId(), epoch());
        }
    }
    public static final class RefreshTicket {
        public final String refreshToken, subject, nonce, identityContext, baseUrl, issuer, clientId;
        public final long epoch;
        private RefreshTicket(String refresh, String subject, String nonce, String identity, String base, String issuer, String client, long epoch) {
            this.refreshToken=refresh; this.subject=subject; this.nonce=nonce; this.identityContext=identity; this.baseUrl=base; this.issuer=issuer; this.clientId=client; this.epoch=epoch;
        }
        @Override public String toString() { return "RefreshTicket[redacted]"; }
    }
    public boolean saveTokensIfCurrent(String access, String refresh, String subject, long expires, String nonce, long expectedEpoch, String expectedBase) {
        return saveTokensIfCurrent(access,refresh,subject,expires,nonce,expectedEpoch,expectedBase,"");
    }
    public boolean saveTokensIfCurrent(String access, String refresh, String subject, long expires, String nonce, long expectedEpoch, String expectedBase, String identityContext) {
        synchronized (LOCK) {
            if (epoch() != expectedEpoch || !getApiBaseUrl().equals(expectedBase)) return false;
            saveTokens(access, refresh, subject, expires, nonce, identityContext); return true;
        }
    }
    public void clearSessionIfCurrent(long expectedEpoch,String expectedBase) {
        synchronized (LOCK) { if (epoch()==expectedEpoch && getApiBaseUrl().equals(expectedBase)) clearSession(); }
    }

    public void saveTokens(String access, String refresh, String subject, long expiresAtEpochSeconds) {
        saveTokens(access, refresh, subject, expiresAtEpochSeconds, getNonce());
    }
    public void saveTokens(String access, String refresh, String subject, long expiresAtEpochSeconds, String nonce) {
        saveTokens(access,refresh,subject,expiresAtEpochSeconds,nonce,"");
    }
    private void saveTokens(String access, String refresh, String subject, long expiresAtEpochSeconds, String nonce, String identityContext) {
        synchronized (LOCK) {
            if (!tokenText(access) || (refresh != null && !refresh.isEmpty() && !tokenText(refresh)) || subject == null || subject.isEmpty()
                    || expiresAtEpochSeconds <= System.currentTimeMillis() / 1000 || expiresAtEpochSeconds > System.currentTimeMillis() / 1000 + 604800)
                throw new IllegalArgumentException("로그인 응답을 확인하지 못했습니다.");
            try {
                JSONObject value = new JSONObject().put("access", access).put("refresh", refresh == null ? "" : refresh)
                        .put("subject", subject).put("expires", expiresAtEpochSeconds).put("nonce", nonce == null ? "" : nonce)
                        .put("identity", identityContext == null ? "" : identityContext)
                        .put("origin", getApiBaseUrl()).put("issuer", getIssuer()).put("client", getClientId());
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(AAD);
                byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
                if (!preferences.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                        .putString("credentials", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) throw new Exception();
            } catch (Exception failure) { clearSession(); throw new IllegalStateException("안전한 로그인 정보를 저장하지 못했습니다."); }
        }
    }

    /** Workspace device QA only; a release package cannot provision a bearer this way. */
    public void setDebugAccessToken(String token) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("개발자 연결을 사용할 수 없습니다.");
        saveTokens(token, "", "debug-device-session", System.currentTimeMillis() / 1000 + 1800, "");
    }

    public void clearSession() {
        synchronized (LOCK) {
            if (!preferences.edit().remove("credentials").remove("iv").putLong("epoch", epoch() + 1).commit()) throw new IllegalStateException("로그인 정보를 지우지 못했습니다.");
        }
    }
    public void clearAll() { synchronized (LOCK) { clearSession(); long value=epoch(); if (!preferences.edit().clear().putLong("epoch",value).commit()) throw new IllegalStateException("설정을 지우지 못했습니다."); } }

    private JSONObject read() {
        String encrypted = preferences.getString("credentials", "");
        if (encrypted.isEmpty()) return null;
        try {
            if (encrypted.length() > 70000) throw new Exception();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)));
            cipher.updateAAD(AAD);
            JSONObject value = new JSONObject(new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8));
            if (!getApiBaseUrl().equals(value.optString("origin")) || !getIssuer().equals(value.optString("issuer")) || !getClientId().equals(value.optString("client"))) throw new Exception();
            return value;
        } catch (Exception invalid) { preferences.edit().remove("credentials").remove("iv").putLong("epoch",epoch()+1).commit(); return null; }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private static boolean tokenText(String value) {
        if (value == null || value.isEmpty() || value.length() > 16384) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 33 || value.charAt(i) > 126) return false;
        return true;
    }
}
