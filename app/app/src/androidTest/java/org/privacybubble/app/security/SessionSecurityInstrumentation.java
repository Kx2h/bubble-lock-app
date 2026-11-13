package org.privacybubble.app.security;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.privacybubble.app.session.MobileSessionStore;
import org.privacybubble.app.session.ServiceOrigin;

public final class SessionSecurityInstrumentation extends Instrumentation {
    private int assertions;
    private boolean portalNetwork;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        portalNetwork=arguments!=null && "portal-network".equals(arguments.getString("mode"));
        start();
    }
    @Override public void onStart() {
        if (portalNetwork) { runPortalNetwork(); return; }
        Bundle result=new Bundle();
        MobileSessionStore store=new MobileSessionStore(getTargetContext());
        SharedPreferences prefs=getTargetContext().getSharedPreferences("mobile_session",0);
        try {
            store.clearAll();
            store.saveConnection("http://127.0.0.1:8787",store.getIssuer(),store.getClientId());
            long before=store.epoch();
            String access="qa-device-access", refresh="qa-device-refresh";
            store.saveTokens(access,refresh,"qa-device-subject",System.currentTimeMillis()/1000+900,"qa-nonce");
            String cipher=prefs.getString("credentials","");
            check(!cipher.isEmpty() && !cipher.contains(access) && !cipher.contains(refresh),"ciphertext");
            check(!prefs.contains("access") && !prefs.contains("refresh"),"no-plaintext-keys");
            check(access.equals(new MobileSessionStore(getTargetContext()).getAccessToken()),"keystore-restore");
            MobileSessionStore.RefreshTicket ticket=store.takeRefreshTicket();
            check(ticket!=null && refresh.equals(ticket.refreshToken),"refresh-ticket");
            check(store.getAccessToken().isEmpty() && store.getRefreshToken().isEmpty() && !prefs.contains("credentials"),"durable-consumption");
            check(store.epoch()>before,"epoch-advanced");
            check(!store.saveTokensIfCurrent(access,"","qa-device-subject",System.currentTimeMillis()/1000+900,"",before,store.getApiBaseUrl()),"reject-old-write");
            check(store.saveTokensIfCurrent(access,refresh,"qa-device-subject",System.currentTimeMillis()/1000+900,"qa-nonce",ticket.epoch,ticket.baseUrl),"accept-current-write");
            store.clearSession();
            check(!store.saveTokensIfCurrent(access,"","qa-device-subject",System.currentTimeMillis()/1000+900,"",ticket.epoch,ticket.baseUrl),"logout-rejects-late-write");
            store.saveTokens(access,refresh,"qa-device-subject",System.currentTimeMillis()/1000+900,"");
            check(prefs.edit().putString("api_base","https://127.0.0.1:9443").commit(),"change-origin");
            check(store.getAccessToken().isEmpty() && !prefs.contains("credentials"),"ciphertext-origin-binding");
            store.saveTokens(access,refresh,"qa-device-subject",System.currentTimeMillis()/1000+900,"");
            check(prefs.edit().putString("credentials","tampered").commit(),"tamper-fixture");
            check(store.getAccessToken().isEmpty() && !prefs.contains("credentials"),"reject-corrupt-ciphertext");
            check("https://new.example".equals(ServiceOrigin.effective("https://new.example","https://retired.example",false)),"release-origin-migration");
            assertions+=org.privacybubble.app.auth.AuthSecurityAssertions.run(this);
            assertions+=LocationLeaseAssertions.run(this);
            int pagingAssertions=LocationPagingAssertions.run(this);
            assertions+=pagingAssertions;
            store.clearAll();
            check(store.getApiBaseUrl().isEmpty() && store.getSubject().isEmpty() && !prefs.contains("iv"),"cleanup");
            result.putString("stream","PASS: "+assertions+" Keystore/session boundary assertions\nPaging assertions: "+pagingAssertions+"; render ms: "+java.util.Arrays.toString(LocationPagingAssertions.lastRenderTimingsMillis())+"\n");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable failure) {
            try { store.clearAll(); } catch (Exception ignored) { }
            result.putString("stream","FAIL after "+assertions+" assertions: "+failure.getClass().getSimpleName()+(failure instanceof AssertionError?" "+failure.getMessage():"")+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void runPortalNetwork() {
        Bundle result=new Bundle();
        try {
            assertions=PortalNetworkAssertions.run(this);
            result.putString("stream","PASS: "+assertions+" isolated HTTPS/Main lease assertions\n"
                    +"Expiry/denial/total ms: "+java.util.Arrays.toString(PortalNetworkAssertions.lastTimingsMillis())+"\n"
                    +"Synthetic JWT; OIDC login and visible UI are outside this fixture.\n");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable failure) {
            result.putString("stream","FAIL portal-network: "+failure.getClass().getSimpleName()
                    +(failure instanceof AssertionError?" "+failure.getMessage():"")+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void check(boolean value,String name) { if (!value) throw new AssertionError(name); assertions++; }
}
