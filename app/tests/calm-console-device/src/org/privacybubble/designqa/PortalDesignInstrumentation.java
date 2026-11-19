package org.privacybubble.designqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.webkit.CookieManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in actual installed app QA. No domain mutations beyond optional exact-origin login. */
public final class PortalDesignInstrumentation extends Instrumentation {
    private static final String ORIGIN="https://bubblelock.iptime.org";
    private Activity activity;
    private WebView web;
    private Bundle args;
    private JSONObject report=new JSONObject();
    private JSONObject input;
    private int checks;
    private int injectedTouches;
    private boolean startedAuthenticated,loginPerformed,activityFinished;
    private String phase;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments==null?new Bundle():arguments;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            phase=args.getString("phase","portrait");check(List.of("portrait","navigation","recovery","layout").contains(phase),"bounded-phase");
            check(!((KeyguardManager)getTargetContext().getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked(),"visible-unlocked-device-required");
            boolean debug=(getTargetContext().getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
            check(!"yes".equals(args.getString("requireRelease","yes"))||!debug,"actual-nondebug-target");
            input=new JSONObject(privateCall("input",null,null).getString("value"));
            check(input.optString("runId").matches("[A-Za-z0-9._-]{1,60}"),"private-run-binding");
            activity=startActivitySync(new Intent().setClassName(getTargetContext(),"org.privacybubble.app.ServerPortalActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();onMain(()->web=findWeb(activity.getWindow().getDecorView()));check(web!=null,"actual-app-webview");
            awaitHome();
            onMain(()->{
                check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"flag-secure-retained");
                check(!web.isSaveEnabled()&&!web.isSaveFromParentEnabled(),"no-webview-saved-state");
                check(!web.getSettings().getAllowFileAccess()&&!web.getSettings().getAllowContentAccess(),"local-file-and-content-access-disabled");
            });
            JSONObject session=session();startedAuthenticated=session.optBoolean("authenticated");
            if(!startedAuthenticated&&!input.optString("password").isEmpty())login();
            input.put("password","");
            JSONObject now=session();check(now.optInt("status")==200&&now.optBoolean("live"),"read-real-live-session");
            report.put("authenticated",now.optBoolean("authenticated"));report.put("loginPerformed",loginPerformed);report.put("preexistingSessionPreserved",startedAuthenticated&&!loginPerformed);
            geometry();
            if(phase.equals("portrait")||phase.equals("layout")){capturePublicLanding();geometry();}
            if(phase.equals("navigation")){check(now.optBoolean("authenticated"),"authenticated-navigation-required");tabs();overflow();backgroundRoute();}
            if(phase.equals("recovery")){recovery();}
            report.put("status","PASS");report.put("checks",checks);report.put("phase",phase);report.put("phoneLocked",false);report.put("domainMutationCount",0);report.put("logoutPerformed",false);report.put("injectedTouchCount",injectedTouches);
            finishOwnedActivity();saveReport();result.putString("stream","PASS: design-"+phase+"; checks="+checks+"; "+report.toString()+"\nActual installed app; read-only product navigation and optional BFF login.\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){
            try{report.put("status","FAIL");report.put("phase",phase);report.put("checks",checks);report.put("errorType",failure.getClass().getSimpleName());if(failure instanceof AssertionError)report.put("errorCode",failure.getMessage());finishOwnedActivity();saveReport();}catch(Exception ignored){}
            result.putString("stream","FAIL: design-"+phase+"; checks="+checks+"; "+failure.getClass().getSimpleName()+(failure instanceof AssertionError?" "+failure.getMessage():"")+"\nNo target clear, logout or domain mutation cleanup performed.\n");finish(Activity.RESULT_CANCELED,result);
        }finally{
            if(input!=null)try{input.put("password","");}catch(Exception ignored){}
            if(activity!=null&&!activityFinished)try{finishOwnedActivity();}catch(Exception ignored){}
        }
    }
    private void geometry()throws Exception{
        onMain(()->{
            View toolbar=tag("portal-toolbar"),back=tag("portal-back"),menu=tag("portal-menu"),title=tag("portal-title");
            check(toolbar!=null&&back!=null&&menu!=null&&title!=null,"stable-native-toolbar-controls");
            float d=activity.getResources().getDisplayMetrics().density,font=activity.getResources().getConfiguration().fontScale;
            check(toolbar.getHeight()/d>=55,"toolbar-minimum-height");
            check(toolbar.getHeight()/d<=(font>=1.5?128:80),"compact-toolbar-retains-content-space");
            for(View button:List.of(back,menu)){
                check(button.getWidth()/d>=47.5&&button.getHeight()/d>=47.5,"native-touch-target-48dp");
                check(button.getContentDescription()!=null&&button.getContentDescription().length()>0,"native-icon-accessible-name");
            }
            int[]b=new int[2],t=new int[2],m=new int[2];back.getLocationOnScreen(b);title.getLocationOnScreen(t);menu.getLocationOnScreen(m);
            check(b[0]+back.getWidth()<=t[0]+1&&t[0]+title.getWidth()<=m[0]+1,"toolbar-controls-do-not-overlap");
            check(web.getWidth()>0&&web.getHeight()>0,"web-content-has-positive-viewport");
            check(web.getSettings().getTextZoom()>=Math.min(200,Math.round(font*100))-1,"web-text-follows-system-font-scale");
            report.put("fontScale",font);report.put("webTextZoom",web.getSettings().getTextZoom());report.put("toolbarHeightDp",Math.round(toolbar.getHeight()/d));report.put("webViewportWidthDp",Math.round(web.getWidth()/d));report.put("webViewportHeightDp",Math.round(web.getHeight()/d));report.put("nightMode",activity.getResources().getConfiguration().uiMode&0x30);
        });
        JSONObject doc=object("JSON.stringify({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,rootFont:getComputedStyle(document.body).fontSize,canvas:getComputedStyle(document.body).backgroundColor})");
        check(doc.getInt("scrollWidth")<=doc.getInt("width")+2,"no-document-horizontal-overflow");report.put("webLayout",doc);presentationHeaders();
    }
    private void capturePublicLanding()throws Exception{
        String previous=currentUrl();onMain(()->web.loadUrl(ORIGIN+"/#/"));await(()->"true".equals(evaluate("Boolean(location.origin==='"+ORIGIN+"'&&document.querySelector('.welcome-page')&&!document.querySelector('.location-detail')&&!document.querySelector('input[type=password]'))")),30000,"nonsensitive-public-landing");
        SystemClock.sleep(600);
        presentationHeaders();
        JSONObject brand=object("JSON.stringify((function(){const brands=[...document.querySelectorAll('.portal-brand')].filter(el=>el.getBoundingClientRect().width>0),words=[];for(const el of brands){const walker=document.createTreeWalker(el,NodeFilter.SHOW_TEXT);let n;while(n=walker.nextNode()){const start=n.textContent.indexOf('BubbleLock');if(start>=0){const r=document.createRange();r.setStart(n,start);r.setEnd(n,start+10);const boxes=[...r.getClientRects()].filter(b=>b.width>0.25&&b.height>0),centers=[];for(const b of boxes){const y=b.top+b.height/2;if(!centers.some(c=>Math.abs(c-y)<=Math.max(2,b.height*0.45)))centers.push(y);}words.push({lines:centers.length,whiteSpace:getComputedStyle(n.parentElement).whiteSpace,boxes:boxes.map(b=>({x:b.x,y:b.y,width:b.width,height:b.height}))});break;}}}return{visibleBrands:brands.length,words}})())");
        report.put("brandDiagnostics",brand);
        onMain(()->{
            View root=activity.getWindow().getDecorView();int width=root.getWidth(),height=root.getHeight();check(width>0&&height>0,"capture-positive-size");
            float scale=Math.min(1f,720f/width);Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);canvas.scale(scale,scale);root.draw(canvas);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();check(bytes.size()<650000,"bounded-private-nonsensitive-capture");
            Bundle bundle=new Bundle();bundle.putByteArray("value",bytes.toByteArray());privateCall("shot",phase+"-public-landing.png",bundle);report.put("capture","public-landing-no-real-location-or-account-details");
        });
        check(brand.getInt("visibleBrands")>0&&brand.getJSONArray("words").length()>0,"public-brand-present");
        for(int i=0;i<brand.getJSONArray("words").length();i++){
            JSONObject word=brand.getJSONArray("words").getJSONObject(i);
            check(word.getInt("lines")==1,"brand-word-not-broken-across-lines");
            check(word.getString("whiteSpace").equals("nowrap"),"app-brand-nowrap-style-applied");
        }
        report.put("brandWordNotBroken",true);
        if(previous!=null&&previous.startsWith(ORIGIN+"/")){onMain(()->web.loadUrl(previous));awaitHome();}
    }
    private void tabs()throws Exception{
        String[]routes={"overview","map","devices","privacy","activity"};
        for(String route:routes){
            String hash="#/user/"+route;tapWebRoute(hash);
            await(()->"true".equals(evaluate("Boolean(location.hash==="+JSONObject.quote(hash)+"&&document.querySelector('.user-portal')&&document.querySelector('h1')&&[...document.querySelectorAll((innerWidth<=760?'.tab-bar':'.portal-sidebar nav')+' a')].some(a=>a.getAttribute('href')==="+JSONObject.quote(hash)+"&&a.getAttribute('aria-current')==='page'))")),25000,"family-page-"+route);
            JSONObject nav=object("JSON.stringify((function(){const phone=innerWidth<=760,n=document.querySelector(phone?'.tab-bar':'.portal-sidebar nav'),links=n?[...n.querySelectorAll('a')]:[];return{phone,count:links.length,selected:links.filter(a=>a.getAttribute('aria-current')==='page').map(a=>a.getAttribute('href')),minHeight:links.length?Math.min(...links.map(a=>a.getBoundingClientRect().height)):0,overflow:document.documentElement.scrollWidth>innerWidth+2}})())");
            check(nav.getInt("count")==5,"five-family-navigation-items");JSONArray selected=nav.getJSONArray("selected");boolean active=false;for(int i=0;i<selected.length();i++)if(hash.equals(selected.getString(i)))active=true;check(active,"selected-family-route-matches");check(nav.getDouble("minHeight")>=43.5,"web-navigation-touch-targets");check(!nav.getBoolean("overflow"),"family-page-fits-width");
        }
        report.put("familyRoutesChecked",new JSONArray(routes));report.put("hitTargetNavigation",true);
    }
    private void overflow()throws Exception{
        tapNative("portal-menu");SystemClock.sleep(250);AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();check(root!=null,"native-overflow-accessible-window");
        for(String label:List.of("새로고침","가족 대시보드","시작 화면","서비스 정보","로그아웃","닫기"))check(!root.findAccessibilityNodeInfosByText(label).isEmpty(),"overflow-"+label);
        root.recycle();sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);SystemClock.sleep(150);report.put("overflowActionsVerified",true);
    }
    private void backgroundRoute()throws Exception{
        tapWebRoute("#/user/privacy");await(()->"true".equals(evaluate("location.hash==='#/user/privacy'")),12000,"route-before-background");
        onMain(()->activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        await(()->"about:blank".equals(currentUrl()),8000,"background-blanks-sensitive-web-document");
        onMain(()->getTargetContext().startActivity(new Intent().setClassName(getTargetContext(),"org.privacybubble.app.ServerPortalActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        awaitHome();await(()->"true".equals(evaluate("location.hash==='#/user/privacy'")),20000,"ram-route-restored-after-background");report.put("backgroundDocumentCleared",true);report.put("ramRouteRestored",true);
    }
    private void recovery()throws Exception{
        onMain(()->web.getWebViewClient().onReceivedHttpError(web,
            new android.webkit.WebResourceRequest(){
                public Uri getUrl(){return Uri.parse(ORIGIN+"/#/user/overview");}
                public boolean isForMainFrame(){return true;}
                public boolean isRedirect(){return false;}
                public boolean hasGesture(){return false;}
                public String getMethod(){return "GET";}
                public java.util.Map<String,String> getRequestHeaders(){return java.util.Collections.emptyMap();}
            },new android.webkit.WebResourceResponse("text/plain","UTF-8",503,"Service Unavailable",java.util.Collections.singletonMap("Cache-Control","no-store"),new java.io.ByteArrayInputStream(new byte[0]))));
        await(()->{AtomicReference<Boolean>ready=new AtomicReference<>(false);onMain(()->{View state=tag("portal-state"),retry=tag("portal-retry");ready.set(state!=null&&state.isShown()&&retry!=null&&retry.isShown()&&retry.getHeight()>0);});return ready.get();},5000,"injected-connection-error-visible");
        onMain(()->{View state=tag("portal-state"),retry=tag("portal-retry");check(state!=null&&state.getVisibility()==View.VISIBLE&&retry!=null&&retry.getVisibility()==View.VISIBLE,"failure-has-visible-recovery-state");check(web.getVisibility()!=View.VISIBLE,"failure-hides-sensitive-web-document");check(retry.getHeight()/activity.getResources().getDisplayMetrics().density>=47.5,"recovery-touch-target-48dp");});tapNative("portal-retry");
        awaitHome();await(()->tag("portal-state").getVisibility()!=View.VISIBLE,20000,"retry-restores-real-portal");report.put("explicitErrorAndRetryVerified",true);report.put("failureMechanism","controlled WebViewClient HTTP 503 callback; normal real portal retry");
    }
    private void login()throws Exception{
        check(input.optString("username","guardian").equals("guardian"),"explicit-guardian-only-login");String password=input.getString("password");check(!password.isEmpty()&&password.length()<=1024,"private-login-input-present");
        onMain(()->web.loadUrl(ORIGIN+"/api/auth/login?returnTo=%2F%23%2Fuser%2Foverview"));
        await(()->"true".equals(evaluate("Boolean(location.origin==='"+ORIGIN+"'&&document.querySelector('form#kc-form-login input[name=password]'))")),45000,"exact-first-party-login-form");
        String script="(function(){if(location.origin!=="+JSONObject.quote(ORIGIN)+"||window!==window.top)return false;const f=document.querySelector('form#kc-form-login');if(!f||f.method.toLowerCase()!=='post')return false;const a=new URL(f.action,location.href);if(a.origin!==location.origin||a.pathname!=='/identity/realms/bubble/login-actions/authenticate'||a.searchParams.get('client_id')!=='bubble-lock-dashboard')return false;const u=f.querySelector('input[name=username]'),p=f.querySelector('input[name=password]');if(!u||!p||p.type!=='password')return false;u.value='guardian';p.value="+JSONObject.quote(password)+";u.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('input',{bubbles:true}));f.requestSubmit(f.querySelector('#kc-login')||undefined);return true;})()";
        check("true".equals(evaluate(script)),"credential-submitted-on-exact-real-idp-form");password="";input.put("password","");awaitHome();await(()->session().optBoolean("authenticated"),25000,"real-bff-login-succeeded");onMain(()->CookieManager.getInstance().flush());loginPerformed=true;
    }
    private JSONObject session()throws Exception{
        String id=Long.toString(SystemClock.elapsedRealtimeNanos());
        check("true".equals(evaluate("(function(){if(location.origin!=="+JSONObject.quote(ORIGIN)+")return false;window.__calmQaSession={id:"+JSONObject.quote(id)+",ready:false};fetch('/api/auth/session',{credentials:'same-origin',cache:'no-store',redirect:'error'}).then(async r=>{const d=await r.json();window.__calmQaSession={id:"+JSONObject.quote(id)+",ready:true,status:r.status,live:d.mode==='live',authenticated:d.authenticated===true};}).catch(()=>window.__calmQaSession={id:"+JSONObject.quote(id)+",ready:true,status:0});return true;})()")),"same-origin-session-read-started");
        await(()->"true".equals(evaluate("Boolean(window.__calmQaSession?.id==="+JSONObject.quote(id)+"&&window.__calmQaSession.ready)")),18000,"bounded-session-metadata-response");return object("JSON.stringify(window.__calmQaSession)");
    }
    private void awaitHome()throws Exception{await(()->"true".equals(evaluate("location.origin==='"+ORIGIN+"'&&['/','/index.html'].includes(location.pathname)&&document.readyState==='complete'")),45000,"real-first-party-portal-document");}
    private void saveReport()throws Exception{Bundle b=new Bundle();b.putString("value",report.toString());privateCall("report",(phase==null?"unknown":phase)+"-report.json",b);}
    private void tapWebRoute(String hash)throws Exception{
        await(()->"true".equals(evaluate("Boolean(document.querySelector('.user-portal')&&document.querySelector(innerWidth<=760?'.tab-bar':'.portal-sidebar nav'))")),20000,"family-navigation-ready-before-touch");
        JSONObject point=object("JSON.stringify((function(){const n=document.querySelector(innerWidth<=760?'.tab-bar':'.portal-sidebar nav'),a=n?[...n.querySelectorAll('a')].find(a=>a.getAttribute('href')==="+JSONObject.quote(hash)+"):null;if(!a)return{found:false};const r=a.getBoundingClientRect();return{found:true,x:r.left+r.width/2,y:r.top+r.height/2,width:r.width,height:r.height,viewportWidth:innerWidth,viewportHeight:innerHeight,inside:r.left>=0&&r.right<=innerWidth+1&&r.top>=0&&r.bottom<=innerHeight+1}})())");
        check(point.optBoolean("found")&&point.optBoolean("inside"),"visible-web-navigation-hit-target");check(point.getDouble("height")>=43.5,"web-navigation-hit-height");
        float[]screen=new float[2];onMain(()->{int[]p=new int[2];web.getLocationOnScreen(p);float scale=web.getWidth()/(float)point.getDouble("viewportWidth");screen[0]=p[0]+(float)point.getDouble("x")*scale;screen[1]=p[1]+(float)point.getDouble("y")*scale;});tap(screen[0],screen[1]);
        await(()->"true".equals(evaluate("location.hash==="+JSONObject.quote(hash))),15000,"touch-opens-family-route");
    }
    private void tapNative(String name)throws Exception{
        float[]point=new float[2];onMain(()->{View v=tag(name);check(v!=null&&v.isShown()&&v.isEnabled(),"native-touch-control-visible-enabled");int[]p=new int[2];v.getLocationOnScreen(p);point[0]=p[0]+v.getWidth()/2f;point[1]=p[1]+v.getHeight()/2f;});tap(point[0],point[1]);
    }
    private void tap(float x,float y){
        check(!((KeyguardManager)getTargetContext().getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked(),"touch-device-still-unlocked");long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,x,y,0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{check(getUiAutomation().injectInputEvent(down,true),"real-touch-down-injected");SystemClock.sleep(60);check(getUiAutomation().injectInputEvent(up,true),"real-touch-up-injected");injectedTouches++;waitForIdleSync();}finally{down.recycle();up.recycle();}
    }
    private void presentationHeaders()throws Exception{
        await(()->"true".equals(evaluate("Boolean(document.getElementById('bubble-android-presentation')&&document.documentElement.classList.contains('bubble-android-app'))")),10000,"app-scoped-first-party-presentation-present");
        JSONObject layout=object("JSON.stringify((function(){const headers=[...document.querySelectorAll('.welcome-header,.portal-topbar,.page-heading')].filter(h=>h.getBoundingClientRect().width>0);return{stylePresent:true,headers:headers.map(h=>({className:h.className,children:[...h.children].filter(c=>c.getBoundingClientRect().width>0).map(c=>{const r=c.getBoundingClientRect();return{left:r.left,right:r.right,fits:r.left>=-1&&r.right<=innerWidth+1}})}))}})())");
        JSONArray headers=layout.getJSONArray("headers");for(int i=0;i<headers.length();i++){JSONArray children=headers.getJSONObject(i).getJSONArray("children");for(int n=0;n<children.length();n++)check(children.getJSONObject(n).getBoolean("fits"),"header-child-remains-within-viewport");}
        report.put("appScopedPresentation",layout);
    }
    private void finishOwnedActivity()throws Exception{
        if(activity==null||activityFinished)return;
        onMain(()->{CookieManager.getInstance().flush();activity.finish();});waitForIdleSync();SystemClock.sleep(250);onMain(()->CookieManager.getInstance().flush());activityFinished=true;report.put("cookieJarFlushedWithoutExtraction",true);report.put("activityFinishedBeforeInstrumentation",true);
    }
    private Bundle privateCall(String method,String arg,Bundle extras){Bundle b=getTargetContext().getContentResolver().call(Uri.parse("content://org.privacybubble.designqa.private"),method,arg,extras);if(b==null)throw new AssertionError("private-qa-provider");return b;}
    private View tag(String name){return activity.getWindow().getDecorView().findViewWithTag(name);}
    private static WebView findWeb(View root){if(root instanceof WebView)return(WebView)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){WebView w=findWeb(g.getChildAt(i));if(w!=null)return w;}}return null;}
    private JSONObject object(String script)throws Exception{return new JSONObject(evaluate(script));}
    private String currentUrl()throws Exception{AtomicReference<String>value=new AtomicReference<>();onMain(()->value.set(web.getUrl()));return value.get();}
    private String evaluate(String script)throws Exception{AtomicReference<String>r=new AtomicReference<>();CountDownLatch d=new CountDownLatch(1);onMain(()->web.evaluateJavascript(script,v->{r.set(v);d.countDown();}));if(!d.await(5000,TimeUnit.MILLISECONDS))throw new AssertionError("bounded-webview-evaluation");String v=r.get();if(v==null||v.equals("null"))return null;Object result=new JSONArray("["+v+"]").get(0);return result==JSONObject.NULL?null:String.valueOf(result);}
    private interface Action{void run()throws Exception;}private interface Condition{boolean get()throws Exception;}
    private void onMain(Action a)throws Exception{AtomicReference<Throwable>e=new AtomicReference<>();runOnMainSync(()->{try{a.run();}catch(Throwable t){e.set(t);}});if(e.get() instanceof Exception)throw(Exception)e.get();if(e.get() instanceof Error)throw(Error)e.get();if(e.get()!=null)throw new AssertionError("main-thread-operation",e.get());}
    private void await(Condition c,long ms,String label)throws Exception{long deadline=SystemClock.elapsedRealtime()+ms;do{if(c.get())return;SystemClock.sleep(120);}while(SystemClock.elapsedRealtime()<deadline);throw new AssertionError(label);}
    private void check(boolean c,String label){if(!c)throw new AssertionError(label);checks++;}
}
