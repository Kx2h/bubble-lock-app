package org.privacybubble.app.auth;

import android.os.Looper;
import org.json.JSONObject;
import org.privacybubble.app.session.MobileSessionStore;
import org.privacybubble.app.location.LocationRepository.ApiException;
import java.io.IOException;
import java.net.URLEncoder;

/** One refresh attempt at a time; a lost response requires sign-in instead of replay. */
public final class MobileTokenManager {
    private MobileTokenManager() { }
    public static synchronized String accessToken(MobileSessionStore store) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Refresh requires a worker thread");
        String existing = store.getAccessToken();
        if (!existing.isEmpty()) return existing;
        MobileSessionStore.RefreshTicket ticket = store.takeRefreshTicket();
        if (ticket == null) throw new ApiException(401, "로그인이 필요합니다.");
        try {
            if (ticket.issuer.isEmpty() || ticket.clientId.isEmpty()) throw new Exception();
            OidcProvider provider = OidcProvider.discover(ticket.issuer);
            long started = System.currentTimeMillis() / 1000;
            JSONObject response = new JSONObject(OidcTokenTransport.postForm(provider.tokenEndpoint,
                    "grant_type=refresh_token&client_id=" + enc(ticket.clientId) + "&refresh_token=" + enc(ticket.refreshToken)));
            TokenValues tokens = TokenValues.parse(response, ticket.refreshToken, false);
            if (response.has("id_token")) provider.verifyMobileRefresh(response.getString("id_token"), ticket.clientId,
                    ticket.identityContext, started, tokens.access);
            if (!store.saveTokensIfCurrent(tokens.access, tokens.refresh, ticket.subject, started + tokens.seconds,
                    ticket.nonce, ticket.epoch, ticket.baseUrl, ticket.identityContext)) throw new Exception();
            return store.getAccessToken();
        } catch (Exception failure) { throw new ApiException(401, "로그인이 필요합니다."); }
    }

    static final class TokenValues {
        final String access, refresh; final long seconds;
        private TokenValues(String access, String refresh, long seconds) { this.access=access; this.refresh=refresh; this.seconds=seconds; }
        static TokenValues parse(JSONObject response, String oldRefresh, boolean initial) throws Exception {
            Object access = response.opt("access_token"), type = response.opt("token_type"), expiry = response.opt("expires_in");
            if (!(access instanceof String) || !(type instanceof String) || !"bearer".equalsIgnoreCase((String) type)
                    || !(expiry instanceof Number) || !printable((String) access)) throw new Exception();
            double seconds = ((Number) expiry).doubleValue();
            if (!Double.isFinite(seconds) || seconds != Math.rint(seconds) || seconds < 30 || seconds > 604800) throw new Exception();
            String refresh = oldRefresh;
            if (response.has("refresh_token")) {
                Object next = response.opt("refresh_token");
                if (!(next instanceof String) || !printable((String) next)) throw new Exception();
                refresh = (String) next;
            }
            if (response.has("scope")) {
                Object scope = response.opt("scope");
                if (!(scope instanceof String) || !java.util.Arrays.asList(((String) scope).split(" ")).contains("location:read")) throw new Exception();
            }
            if (initial && (!(response.opt("id_token") instanceof String) || response.getString("id_token").isEmpty())) throw new Exception();
            return new TokenValues((String) access, refresh == null ? "" : refresh, (long) seconds);
        }
        private static boolean printable(String token) {
            if (token.isEmpty() || token.length() > 16384) return false;
            for (int i=0;i<token.length();i++) if (token.charAt(i)<33 || token.charAt(i)>126) return false;
            return true;
        }
    }
    static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
}
