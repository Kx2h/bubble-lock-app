package org.privacybubble.app.auth;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import org.json.JSONObject;
import org.privacybubble.app.session.MobileSessionStore;
import org.privacybubble.app.session.SessionProfile;

public final class SessionContractAssertions {
    private static int assertions;
    private SessionContractAssertions() { }

    public static int run(Instrumentation instrumentation) throws Exception {
        assertions=0;
        MobileSessionStore store=new MobileSessionStore(instrumentation.getTargetContext());
        String current=store.getApiContract();
        String other=current.equals("portal-central-v1") ? "portal-web-v1" : "portal-central-v1";
        String base=store.getApiBaseUrl();
        store.saveTokens("isolated-contract-access","isolated-contract-refresh","isolated-contract-subject",System.currentTimeMillis()/1000+300,"");
        long epoch=store.epoch();
        check(!store.saveTokensIfCurrent("wrong-contract-access","","isolated-contract-subject",System.currentTimeMillis()/1000+300,"",epoch,base,"",other),"late-token-contract-mismatch");
        store.clearSessionIfCurrent(epoch,base,other);
        check("isolated-contract-access".equals(store.getAccessToken()),"different-contract-cannot-clear-current-session");
        MobileSessionStore.RefreshTicket ticket=store.takeRefreshTicket();
        check(ticket!=null && current.equals(ticket.apiContract),"refresh-ticket-retains-contract");
        check(store.saveTokensIfCurrent("isolated-contract-access","","isolated-contract-subject",System.currentTimeMillis()/1000+300,"",ticket.epoch,ticket.baseUrl,"",current),"same-contract-save");
        overwrite(instrumentation,store,current,other,false);
        check(store.getAccessToken().isEmpty(),"authenticated-embedded-contract-mismatch");
        overwrite(instrumentation,store,other,other,false);
        check(store.getAccessToken().isEmpty(),"different-contract-aad-mismatch");
        overwrite(instrumentation,store,current,null,false);
        check(store.getAccessToken().isEmpty(),"missing-embedded-contract");
        overwrite(instrumentation,store,current,null,true);
        check(store.getAccessToken().isEmpty(),"legacy-storage-format-requires-signin");
        return assertions;
    }

    private static void overwrite(Instrumentation instrumentation,MobileSessionStore store,String aadContract,String embeddedContract,boolean legacy) throws Exception {
        JSONObject value=new JSONObject().put("access","isolated-contract-access").put("refresh","").put("subject","isolated-contract-subject")
                .put("expires",System.currentTimeMillis()/1000+300).put("nonce","").put("identity","")
                .put("origin",store.getApiBaseUrl()).put("issuer",store.getIssuer()).put("client",store.getClientId());
        if (embeddedContract!=null) value.put("apiContract",embeddedContract);
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        SecretKey key=(SecretKey)keys.getKey("privacy-bubble.mobile.session.v1",null);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key);
        cipher.updateAAD(legacy?"org.privacybubble.app/mobile-session/v1".getBytes(StandardCharsets.UTF_8):SessionProfile.associatedData(aadContract));
        byte[] encrypted=cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        SharedPreferences prefs=instrumentation.getTargetContext().getSharedPreferences("mobile_session",0);
        if (!prefs.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("credentials",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) throw new IllegalStateException("Fixture storage unavailable");
    }

    private static void check(boolean value,String name) { if (!value) throw new AssertionError(name); assertions++; }
}
