package org.privacybubble.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ClientCertRequest;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import org.privacybubble.app.portal.ServerPortalPolicy;
import org.privacybubble.app.ui.AppTheme;

/** First-party BFF web session; native OAuth credentials and cookies are not exchanged. */
public final class ServerPortalActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerPortalPolicy policy;
    private WebView portal;
    private LinearProgressIndicator progress;
    private TextView notice;
    private MaterialButton back, logout;
    private boolean resumed, needsReload = true, failed, destroyed, logoutBusy;
    private long logoutDeadline;
    private final Runnable logoutPoll = this::pollLogout;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        try { policy = new ServerPortalPolicy(BuildConfig.SERVER_PORTAL_ORIGIN); }
        catch (IllegalArgumentException invalidProfile) { finish(); return; }
        AppTheme theme = new AppTheme(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        LinearLayout screen = theme.column(); screen.setBackgroundColor(theme.background); setContentView(screen);
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom)); return insets;
        });
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(theme.dp(16), theme.dp(8), theme.dp(16), theme.dp(8));
        LinearLayout brand = theme.column(); brand.addView(theme.heading("BubbleLock", 22));
        TextView address = theme.text(Uri.parse(policy.origin()).getAuthority(), 13, theme.muted); brand.addView(address);
        heading.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(theme.button(getString(R.string.portal_close), false, view -> { if (!logoutBusy) finish(); })); screen.addView(heading);
        LinearLayout navigation = new LinearLayout(this); navigation.setPadding(theme.dp(8), 0, theme.dp(8), theme.dp(8));
        back = theme.button(getString(R.string.portal_back), false, view -> goBack());
        MaterialButton home = theme.button(getString(R.string.portal_home), false, view -> load(policy.origin() + "/#/"));
        MaterialButton reload = theme.button(getString(R.string.portal_reload), false, view -> {
            if (logoutBusy) return;
            if (failed || !policy.isInternal(portal.getUrl())) load(policy.homeUrl()); else portal.reload();
        });
        logout = theme.button(getString(R.string.portal_logout), false, view -> confirmLogout());
        for (MaterialButton button : new MaterialButton[]{back, home, reload, logout}) {
            button.setTextSize(13); button.setPadding(theme.dp(6), theme.dp(8), theme.dp(6), theme.dp(8)); button.setMinHeight(theme.dp(48));
            navigation.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        screen.addView(navigation);
        progress = new LinearProgressIndicator(this); progress.setIndeterminate(true); progress.setIndicatorColor(theme.accent);
        screen.addView(progress, new LinearLayout.LayoutParams(-1, theme.dp(3)));
        notice = theme.text("", 14, theme.warning); notice.setPadding(theme.dp(16), theme.dp(10), theme.dp(16), theme.dp(10));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); notice.setVisibility(View.GONE); screen.addView(notice);
        portal = new WebView(this); configureWebView(); screen.addView(portal, new LinearLayout.LayoutParams(-1, 0, 1));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        ViewCompat.requestApplyInsets(screen);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        portal.setSaveEnabled(false); portal.setSaveFromParentEnabled(false);
        portal.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings = portal.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false); settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSaveFormData(false); settings.setCacheMode(WebSettings.LOAD_NO_CACHE); settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(portal, false);
        portal.setDownloadListener((url, agent, disposition, type, size) -> showNotice(R.string.portal_download_unavailable));
        portal.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams parameters) { callback.onReceiveValue(null); return true; }
        });
        portal.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (policy.isInternal(url)) return false;
                if (request.hasGesture() && policy.isExternalBrowserCandidate(url)) openExternal(url);
                else showNotice(R.string.portal_navigation_blocked);
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return !policy.isInternal(url); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString(), scheme = request.getUrl().getScheme();
                if (request.isForMainFrame() && !policy.isInternal(url)) return blocked();
                if ("file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) return blocked();
                return null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (destroyed || "about:blank".equals(url)) return;
                if (!policy.isInternal(url)) { view.stopLoading(); fail(R.string.portal_navigation_blocked); return; }
                failed = false; notice.setVisibility(View.GONE); progress.setVisibility(View.VISIBLE); updateActions();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (destroyed || "about:blank".equals(url)) return;
                progress.setVisibility(logoutBusy ? View.VISIBLE : View.INVISIBLE); updateActions();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); fail(R.string.portal_secure_connection_failed); }
            @Override public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) { request.cancel(); }
            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) { handler.cancel(); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) fail(R.string.portal_connection_failed);
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) fail(R.string.portal_connection_failed);
            }
        });
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(new byte[0]));
    }
    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (android.content.ActivityNotFoundException unavailable) { showNotice(R.string.portal_external_unavailable); }
    }
    private void load(String url) {
        if (destroyed || logoutBusy || !policy.isInternal(url)) return;
        failed = false; notice.setVisibility(View.GONE); portal.setVisibility(resumed ? View.VISIBLE : View.INVISIBLE); portal.loadUrl(url); updateActions();
    }
    private void goBack() {
        if (logoutBusy) return;
        if (portal != null && portal.canGoBack()) portal.goBack(); else finish();
    }
    private void updateActions() {
        back.setEnabled(!logoutBusy); logout.setEnabled(!logoutBusy && isPortalDocument());
        if (resumed && !failed && !logoutBusy) portal.setVisibility(View.VISIBLE);
    }
    private boolean isPortalDocument() {
        if (portal == null || !policy.isInternal(portal.getUrl())) return false;
        String path = Uri.parse(portal.getUrl()).getPath();
        return path != null && !path.startsWith("/identity/") && !path.startsWith("/api/auth/");
    }
    private void showNotice(int message) { if (!destroyed) { notice.setText(message); notice.setVisibility(View.VISIBLE); } }
    private void fail(int message) {
        if (destroyed) return;
        failed = true; portal.setVisibility(View.INVISIBLE); progress.setVisibility(View.INVISIBLE); showNotice(message);
    }
    private void confirmLogout() {
        if (!isPortalDocument() || logoutBusy) return;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.portal_logout).setMessage(R.string.portal_logout_confirm)
                .setNegativeButton(R.string.portal_cancel, null).setPositiveButton(R.string.portal_logout, (dialog, which) -> beginLogout()).show();
    }
    private void beginLogout() {
        if (!isPortalDocument() || logoutBusy) return;
        logoutBusy = true; logoutDeadline = SystemClock.elapsedRealtime() + 7000;
        portal.setVisibility(View.INVISIBLE); progress.setVisibility(View.VISIBLE); updateActions(); showNotice(R.string.portal_signing_out);
        String script = "(function(){if(location.origin!=='" + policy.origin() + "')return false;window.__bubblePortalLogoutResult='pending';"
                + "fetch('/api/auth/logout',{method:'POST',credentials:'same-origin',redirect:'error',cache:'no-store'})"
                + ".then(function(r){window.__bubblePortalLogoutResult=r.status===200?'ok':'error';})"
                + ".catch(function(){window.__bubblePortalLogoutResult='error';});return true;})()";
        portal.evaluateJavascript(script, value -> {
            if (!destroyed && logoutBusy) {
                if ("true".equals(value)) main.post(logoutPoll); else finishLogout(false);
            }
        });
    }
    private void pollLogout() {
        if (destroyed || !logoutBusy) return;
        if (!isPortalDocument() || SystemClock.elapsedRealtime() >= logoutDeadline) { finishLogout(false); return; }
        portal.evaluateJavascript("window.__bubblePortalLogoutResult", value -> {
            if (destroyed || !logoutBusy) return;
            if ("\"ok\"".equals(value)) finishLogout(true);
            else if ("\"error\"".equals(value)) finishLogout(false);
            else main.postDelayed(logoutPoll, 100);
        });
    }
    private void finishLogout(boolean completed) {
        main.removeCallbacks(logoutPoll);
        if (!completed) {
            logoutBusy = false; progress.setVisibility(View.INVISIBLE); updateActions(); showNotice(R.string.portal_logout_failed); return;
        }
        CookieManager.getInstance().removeAllCookies(removed -> {
            if (destroyed) return;
            CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData();
            portal.clearCache(true); portal.clearHistory(); portal.clearFormData(); logoutBusy = false;
            if (resumed) load(policy.homeUrl()); else needsReload = true;
            showNotice(R.string.portal_signed_out); updateActions();
        });
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (portal != null) { portal.onResume(); if (needsReload) { needsReload = false; load(policy.homeUrl()); } updateActions(); }
    }
    @Override protected void onPause() {
        resumed = false;
        if (portal != null) { portal.setVisibility(View.INVISIBLE); portal.onPause(); }
        super.onPause();
    }
    @Override protected void onStop() {
        if (portal != null) {
            main.removeCallbacks(logoutPoll); logoutBusy = false; needsReload = true;
            portal.stopLoading(); portal.loadUrl("about:blank"); portal.clearHistory();
        }
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacks(logoutPoll);
        if (portal != null) {
            portal.stopLoading(); portal.setWebChromeClient(null); portal.setWebViewClient(null);
            if (portal.getParent() instanceof ViewGroup) ((ViewGroup)portal.getParent()).removeView(portal);
            portal.clearHistory(); portal.clearFormData(); portal.destroy(); portal = null;
        }
        super.onDestroy();
    }
}
