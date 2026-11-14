package org.privacybubble.app.security;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.privacybubble.app.BuildConfig;

/**
 * Opt-in real-server QA. Credentials exist only in the private input file,
 * temporary test memory and the exact Keycloak login form. Cookie/token values
 * and private response DTOs never cross into Java or instrumentation output.
 * A locked device may require explicit WebView lifecycle control below; this is
 * privileged instrumentation, not evidence of visible UI or manual touch.
 * Only login/logout and GET session/locations/snapshot are performed.
 */
public final class ServerPortalAssertions {
    private static final String ORIGIN = "https://bubblelock.iptime.org";
    private static final String HOME = ORIGIN + "/#/user/map";
    private static final String AUTH = "/identity/realms/bubble/protocol/openid-connect/auth";
    private static final String AUTHENTICATE = "/identity/realms/bubble/login-actions/authenticate";
    private static final String CLIENT = "bubble-lock-dashboard";
    private static final String INPUT = "portal-live-accounts.json";
    private static final String RESULT = "__bubblePortalLiveQaMeta";
    private static volatile String summary = "Not executed";
    private ServerPortalAssertions() { }

    public static String lastSummary() { return summary; }

    public static int run(Instrumentation instrumentation) throws Exception { return run(instrumentation,true); }

    public static int run(Instrumentation instrumentation, boolean expectedDebuggable) throws Exception {
        Counter checks = new Counter();
        AtomicReference<Activity> activity = new AtomicReference<>();
        AtomicReference<WebView> view = new AtomicReference<>();
        File input = new File(instrumentation.getTargetContext().getFilesDir(), INPUT);
        List<Account> accounts = new ArrayList<>();
        boolean[] owned = {false};
        List<String> outcomes = new ArrayList<>();
        summary = "Not completed";
        try {
            checks.check(ORIGIN.equals(BuildConfig.SERVER_PORTAL_ORIGIN), "explicit-live-portal-profile");
            checks.check(((instrumentation.getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0)==expectedDebuggable, "actual-target-build-type");
            accounts.addAll(readAccounts(instrumentation, input, checks));
            Intent launch = new Intent().setClassName(instrumentation.getTargetContext(), "org.privacybubble.app.ServerPortalActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.set(instrumentation.startActivitySync(launch));
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                view.set(findWebView(activity.get().getWindow().getDecorView()));
                checks.check(view.get() != null, "real-portal-webview");
                // The lock screen can stop the Activity and blank its WebView.
                // Only this explicitly invoked test resumes/reloads the container.
                view.get().onResume(); view.get().resumeTimers(); view.get().loadUrl(HOME);
            });
            awaitHome(instrumentation, view.get());
            JSONObject initial = request(instrumentation, view.get(), "session", "");
            checks.check(initial.optInt("status") == 200 && initial.optBoolean("live")
                    && !initial.optBoolean("authenticated"), "no-existing-bff-session");
            owned[0] = true;
            clearQaContainer(instrumentation, view.get(), checks);

            for (Account account : accounts) {
                onMain(instrumentation, () -> view.get().loadUrl(HOME));
                awaitHome(instrumentation, view.get());
                JSONObject before = request(instrumentation, view.get(), "session", "");
                checks.check(before.optInt("status") == 200 && !before.optBoolean("authenticated"), "account-starts-without-bff-session");
                onMain(instrumentation, () -> view.get().loadUrl(ORIGIN + "/api/auth/login?returnTo=%2F%23%2Fuser%2Fmap"));
                awaitForm(instrumentation, view.get());
                String submitted = evaluate(instrumentation, view.get(), formScript(account));
                checks.check("submitted".equals(submitted), "one-credential-submit-on-exact-idp-form");
                awaitHome(instrumentation, view.get());
                JSONObject session = request(instrumentation, view.get(), "session", account.principal);
                checks.check(session.optInt("status") == 200 && session.optBoolean("live")
                        && session.optBoolean("authenticated") && session.optBoolean("principalMatches")
                        && session.optBoolean("portalRead") && session.optBoolean("expiryPresent"), "real-bff-identity-and-read-scope");
                checks.check(session.optBoolean("dashboardRead") == account.user.equals("operator"), "account-scope-separation");
                JSONObject locations = request(instrumentation, view.get(), "locations", "");
                checks.check(locations.optInt("status") == 200 && locations.optBoolean("live")
                        && locations.optBoolean("schema") && locations.optBoolean("normalized")
                        && locations.optBoolean("leaseBounded"), "real-live-normalized-location-read");
                checks.check(locations.optInt("devices", -1) >= 0 && locations.optInt("devices") <= 1000
                        && locations.optInt("gateways", -1) >= 0, "bounded-real-location-counts");
                JSONObject snapshot = request(instrumentation, view.get(), "snapshot", account.principal);
                checks.check(snapshot.optInt("status") == 200 && snapshot.optBoolean("live")
                        && snapshot.optBoolean("schema") && snapshot.optBoolean("principalMatches")
                        && snapshot.optBoolean("arrays"), "real-authorized-portal-snapshot");
                assertDashboard(instrumentation, view.get(), account.user, checks);
                JSONObject logout = request(instrumentation, view.get(), "logout", "");
                checks.check(logout.optInt("status") == 200 && logout.optBoolean("live")
                        && !logout.optBoolean("authenticated"), "same-origin-post-server-logout");
                JSONObject after = request(instrumentation, view.get(), "session", "");
                checks.check(after.optInt("status") == 200 && !after.optBoolean("authenticated"), "server-session-ended");
                outcomes.add(account.user + ": session/locations/snapshot/dashboard-DOM/logout PASS; devices=" + locations.optInt("devices")
                        + ", positions=" + locations.optInt("positions") + ", BLE=" + locations.optInt("ble"));
                clearQaContainer(instrumentation, view.get(), checks);
            }
            summary = String.join("\n", outcomes) + "\nReal server WebView instrumentation; no visible/manual UI claim.";
        } finally {
            Throwable cleanup = null;
            if (owned[0] && view.get() != null) {
                try {
                    onMain(instrumentation, () -> { view.get().onResume(); view.get().resumeTimers(); view.get().loadUrl(HOME); });
                    awaitHome(instrumentation, view.get());
                    JSONObject logout = request(instrumentation, view.get(), "logout", "");
                    checks.check(logout.optInt("status") == 200 && !logout.optBoolean("authenticated"), "owned-server-session-cleanup");
                } catch (Throwable problem) { cleanup = problem; }
                try { clearQaContainer(instrumentation, view.get(), checks); }
                catch (Throwable problem) { if (cleanup == null) cleanup = problem; }
            }
            try {
                if (input.exists()) checks.check(input.delete(), "private-credential-file-deleted");
                checks.check(!input.exists(), "no-credential-file-after-test");
            } catch (Throwable problem) { if (cleanup == null) cleanup = problem; }
            for (Account account : accounts) account.password = "";
            if (activity.get() != null) {
                try { onMain(instrumentation, () -> activity.get().finish()); }
                catch (Throwable problem) { if (cleanup == null) cleanup = problem; }
            }
            if (cleanup != null) throw new AssertionError("portal-live-cleanup", cleanup);
        }
        return checks.count;
    }

    private static List<Account> readAccounts(Instrumentation instrumentation, File file, Counter checks) throws Exception {
        File directory = instrumentation.getTargetContext().getFilesDir().getCanonicalFile();
        StructStat stat = Os.lstat(file.getAbsolutePath());
        checks.check(file.getCanonicalFile().equals(new File(directory, INPUT)) && OsConstants.S_ISREG(stat.st_mode)
                && stat.st_uid == instrumentation.getTargetContext().getApplicationInfo().uid && (stat.st_mode & 0077) == 0, "private-owned-input-file");
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = in.read(buffer)) != -1) { checks.check(out.size() + count <= 8192, "credential-file-size-bound"); out.write(buffer, 0, count); }
            bytes = out.toByteArray();
        }
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        java.util.Arrays.fill(bytes, (byte)0);
        JSONArray values = root.getJSONArray("accounts"); checks.check(values.length() == 2, "two-explicit-test-accounts");
        List<Account> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index); String user = value.getString("user"), password = value.getString("password");
            checks.check((index == 0 ? "guardian" : "operator").equals(user) && !password.isEmpty() && password.length() <= 1024, "account-fixture-shape");
            String principal = value.optString("principalId", user + "-1");
            checks.check(principal.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"), "explicit-expected-principal");
            result.add(new Account(user, password, principal));
        }
        return result;
    }

    private static String formGuard() {
        return "location.origin===" + JSONObject.quote(ORIGIN) + "&&window===window.top&&"
                + "(location.pathname===" + JSONObject.quote(AUTH) + "||location.pathname.startsWith('/identity/realms/bubble/login-actions/'))";
    }
    private static String formScript(Account account) {
        return "(function(){try{if(!(" + formGuard() + "))return 'blocked';const f=document.querySelector('form#kc-form-login');"
                + "if(!f||f.method.toLowerCase()!=='post')return 'blocked';const a=new URL(f.action,location.href);"
                + "if(a.origin!==" + JSONObject.quote(ORIGIN) + "||a.pathname!==" + JSONObject.quote(AUTHENTICATE)
                + "||a.searchParams.get('client_id')!==" + JSONObject.quote(CLIENT) + ")return 'blocked';"
                + "const p=new URL(location.href).searchParams;"
                + "if(p.has('client_id')&&p.get('client_id')!==" + JSONObject.quote(CLIENT) + ")return 'blocked';"
                + "if(p.has('redirect_uri')&&p.get('redirect_uri')!==" + JSONObject.quote(ORIGIN + "/api/auth/callback") + ")return 'blocked';"
                + "const u=f.querySelector('input[name=username]'),s=f.querySelector('input[name=password]');"
                + "if(!u||!s||s.type!=='password'||f.querySelectorAll('input[type=password]').length!==1)return 'blocked';"
                + "u.value=" + JSONObject.quote(account.user) + ";s.value=" + JSONObject.quote(account.password) + ";"
                + "u.dispatchEvent(new Event('input',{bubbles:true}));s.dispatchEvent(new Event('input',{bubbles:true}));"
                + "const b=f.querySelector('#kc-login');if(b&&b.disabled)return 'blocked';f.requestSubmit(b||undefined);return 'submitted';"
                + "}catch(e){return 'blocked';}})()";
    }

    private static JSONObject request(Instrumentation instrumentation, WebView view, String kind, String principal) throws Exception {
        long id = SystemClock.elapsedRealtimeNanos();
        String path = kind.equals("session") ? "/api/auth/session" : kind.equals("locations") ? "/api/portal/locations"
                : kind.equals("snapshot") ? "/api/portal/snapshot" : kind.equals("logout") ? "/api/auth/logout" : "";
        if (path.isEmpty()) throw new AssertionError("whitelisted-read-or-logout-only");
        String script = "(function(){if(location.origin!==" + JSONObject.quote(ORIGIN) + "||window!==window.top)return false;"
                + "const key=" + JSONObject.quote(RESULT) + ",id=" + JSONObject.quote(Long.toString(id)) + ",kind=" + JSONObject.quote(kind)
                + ",expected=" + JSONObject.quote(principal) + ",path=" + JSONObject.quote(path) + ";window[key]={id,ready:false};"
                + "const c=new AbortController(),timer=setTimeout(()=>c.abort(),12000);"
                + "fetch(path,{method:kind==='logout'?'POST':'GET',credentials:'same-origin',redirect:'error',cache:'no-store',signal:c.signal})"
                + ".then(async r=>{const text=await r.text();if(new TextEncoder().encode(text).length>1048576)throw Error();"
                + "const d=JSON.parse(text),url=new URL(r.url),m={id,ready:true,status:r.status,live:d.mode==='live',"
                + "json:/^application\\/json(?:\\s*;\\s*charset=utf-8)?$/i.test(r.headers.get('content-type')||''),"
                + "noStore:(r.headers.get('cache-control')||'').toLowerCase().split(',').some(v=>v.trim()==='no-store'),"
                + "sameOrigin:url.origin===location.origin&&url.pathname===path&&!r.redirected};"
                + "if(kind==='session'||kind==='logout'){m.authenticated=d.authenticated===true;m.principalMatches=!!d.principal&&d.principal.principalId===expected;"
                + "m.portalRead=!!d.principal&&Array.isArray(d.principal.scopes)&&d.principal.scopes.includes('portal:read');"
                + "m.dashboardRead=!!d.principal&&Array.isArray(d.principal.scopes)&&d.principal.scopes.includes('dashboard:read');m.expiryPresent=Number.isFinite(Date.parse(d.expiresAt));}"
                + "if(kind==='locations'){m.schema=d.schemaVersion==='1.0.0';m.devices=Array.isArray(d.devices)?d.devices.length:-1;"
                + "m.gateways=Array.isArray(d.gateways)?d.gateways.length:-1;const devices=Array.isArray(d.devices)?d.devices:[];"
                + "m.positions=devices.filter(v=>v.position!==null).length;m.ble=devices.filter(v=>v.ble!==null).length;"
                + "m.normalized=m.devices>=0&&m.gateways>=0&&new Set(devices.map(v=>v.deviceId)).size===devices.length"
                + "&&devices.every(v=>typeof v.deviceId==='string'&&typeof v.label==='string'&&(v.position!==null||v.ble!==null))"
                + "&&['portal-api','combined','local-jsonl'].includes(d.source)&&typeof d.simulation==='boolean'&&typeof d.caughtUp==='boolean'&&Number.isSafeInteger(d.rejectedRecords);"
                + "const until=Date.parse(d.readExpiresAt),date=Date.parse(r.headers.get('date'));"
                + "m.leaseBounded=Number.isFinite(until)&&Number.isFinite(date)&&until>Date.now()&&until-date>0&&until-date<=6000&&Math.abs(date-Date.now())<=30000;}"
                + "if(kind==='snapshot'){const s=d.snapshot;m.schema=!!s&&s.schemaVersion==='1.0.0';m.principalMatches=!!s&&!!s.principal&&s.principal.principalId===expected;"
                + "m.arrays=!!s&&['households','devices','consents','grants','incidents','commandRequests','notifications'].every(k=>Array.isArray(s[k]));}"
                + "window[key]=m;}).catch(()=>{window[key]={id,ready:true,error:true};}).finally(()=>clearTimeout(timer));return true;})()";
        if (!"true".equals(evaluate(instrumentation, view, script))) throw new AssertionError("trusted-same-origin-request-context");
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) {
            String value = evaluate(instrumentation, view, "(function(){if(location.origin!==" + JSONObject.quote(ORIGIN)
                    + ")return null;return JSON.stringify(window[" + JSONObject.quote(RESULT) + "]||null);})()");
            if (value != null && !value.equals("null")) {
                JSONObject result = new JSONObject(value);
                if (Long.toString(id).equals(result.optString("id")) && result.optBoolean("ready")) {
                    if (result.optBoolean("error")) throw new AssertionError("same-origin-fetch-completed");
                    if (!result.optBoolean("json") || !result.optBoolean("noStore") || !result.optBoolean("sameOrigin"))
                        throw new AssertionError("live-response-transport-metadata");
                    return result;
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("bounded-live-fetch-timeout");
    }

    private static void awaitHome(Instrumentation instrumentation, WebView view) throws Exception {
        await(instrumentation, () -> {
            AtomicReference<Boolean> home = new AtomicReference<>(false);
            onMain(instrumentation, () -> {
                String raw = view.getUrl(); if (raw == null) return;
                URI url = URI.create(raw);
                home.set(ORIGIN.equals(url.getScheme() + "://" + url.getRawAuthority())
                        && (url.getPath().equals("/") || url.getPath().equals("/index.html")) && url.getRawQuery() == null);
            });
            return home.get() && "true".equals(evaluate(instrumentation, view,
                    "location.origin===" + JSONObject.quote(ORIGIN)
                            + "&&['/','/index.html'].includes(location.pathname)&&location.search===''&&document.readyState==='complete'"));
        }, 45000, "trusted-portal-home-load");
    }
    private static void awaitForm(Instrumentation instrumentation, WebView view) throws Exception {
        await(instrumentation, () -> "true".equals(evaluate(instrumentation, view,
                "Boolean((" + formGuard() + ")&&document.querySelector('form#kc-form-login input[name=password]'))")),
                45000, "exact-keycloak-login-form");
    }
    private static void assertDashboard(Instrumentation instrumentation, WebView view, String account, Counter checks) throws Exception {
        String variant = account.equals("operator") ? "operator" : "user";
        String route = "#/" + variant + "/map";
        String installed = evaluate(instrumentation, view,
                "(function(){if(location.origin!==" + JSONObject.quote(ORIGIN) + ")return false;"
                        + "const s={fatal:0};window.__bubblePortalLiveQaErrors=s;"
                        + "window.addEventListener('error',e=>{if(e instanceof ErrorEvent)s.fatal++;});"
                        + "window.addEventListener('unhandledrejection',()=>s.fatal++);"
                        + "location.hash='#/';setTimeout(()=>{location.hash=" + JSONObject.quote(route) + ";},150);return true;})()");
        checks.check("true".equals(installed), "trusted-dashboard-navigation");
        String selector = ".portal-app." + variant + "-portal main#main .map-content .location-experience.location-" + variant + " .location-canvas";
        long deadline = SystemClock.elapsedRealtime() + 25000;
        boolean ready = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            String value = evaluate(instrumentation, view,
                    "(function(){if(location.origin!==" + JSONObject.quote(ORIGIN) + ")return null;const s=window.__bubblePortalLiveQaErrors;"
                            + "return JSON.stringify({route:location.hash===" + JSONObject.quote(route)
                            + ",canvas:!!document.querySelector(" + JSONObject.quote(selector) + "),"
                            + "gate:!!document.querySelector('.auth-gate'),fatal:s?s.fatal:-1});})()");
            if (value != null) {
                JSONObject state = new JSONObject(value);
                if (state.optInt("fatal", -1) != 0) throw new AssertionError("no-observed-dashboard-fatal-javascript");
                if (state.optBoolean("route") && state.optBoolean("canvas") && !state.optBoolean("gate")) { ready = true; break; }
            }
            SystemClock.sleep(150);
        }
        checks.check(ready, "authorized-role-map-dom-initialized");
        SystemClock.sleep(1500);
        String finalState = evaluate(instrumentation, view,
                "Boolean(location.origin===" + JSONObject.quote(ORIGIN) + "&&location.hash===" + JSONObject.quote(route)
                        + "&&document.querySelector(" + JSONObject.quote(selector) + ")&&!document.querySelector('.auth-gate')"
                        + "&&window.__bubblePortalLiveQaErrors&&window.__bubblePortalLiveQaErrors.fatal===0)");
        checks.check("true".equals(finalState), "role-map-dom-stable-without-observed-fatal-javascript");
    }
    private static void clearQaContainer(Instrumentation instrumentation, WebView view, Counter checks) throws Exception {
        CountDownLatch removed = new CountDownLatch(1);
        onMain(instrumentation, () -> CookieManager.getInstance().removeAllCookies(value -> removed.countDown()));
        checks.check(removed.await(8000, TimeUnit.MILLISECONDS), "owned-qa-cookie-removal-completed");
        onMain(instrumentation, () -> {
            CookieManager.getInstance().flush();
            checks.check(!CookieManager.getInstance().hasCookies(), "owned-qa-cookie-state-empty");
            WebStorage.getInstance().deleteAllData(); view.clearHistory();
        });
    }
    private static String evaluate(Instrumentation instrumentation, WebView view, String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>(); CountDownLatch complete = new CountDownLatch(1);
        onMain(instrumentation, () -> view.evaluateJavascript(script, value -> { result.set(value); complete.countDown(); }));
        if (!complete.await(5000, TimeUnit.MILLISECONDS)) throw new AssertionError("bounded-webview-evaluation");
        String value = result.get();
        if (value == null || value.equals("null")) return null;
        Object decoded = new JSONArray("[" + value + "]").get(0);
        return decoded == JSONObject.NULL ? null : String.valueOf(decoded);
    }
    private interface Action { void run() throws Exception; }
    private interface Condition { boolean get() throws Exception; }
    private static void onMain(Instrumentation instrumentation, Action action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } });
        if (failure.get() instanceof Exception) throw (Exception)failure.get();
        if (failure.get() != null) throw new AssertionError("live-main-thread-control", failure.get());
    }
    private static void await(Instrumentation instrumentation, Condition condition, long timeout, String name) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.get()) return; SystemClock.sleep(150); }
        throw new AssertionError(name);
    }
    private static WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView)root;
        if (root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int index=0; index<group.getChildCount(); index++) {
                WebView found=findWebView(group.getChildAt(index));
                if(found!=null) return found;
            }
        }
        return null;
    }

    private static final class Counter { int count; void check(boolean value, String name) { if (!value) throw new AssertionError(name); count++; } }
    private static final class Account {
        final String user, principal; String password;
        Account(String user, String password, String principal) { this.user=user; this.password=password; this.principal=principal; }
    }
}
