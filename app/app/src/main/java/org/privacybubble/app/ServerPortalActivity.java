package org.privacybubble.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.text.TextUtils;
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
import android.widget.FrameLayout;
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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.privacybubble.app.portal.ServerPortalPolicy;
import org.privacybubble.app.ui.AppTheme;

/** First-party BFF web session; native OAuth credentials and cookies are not exchanged. */
public final class ServerPortalActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerPortalPolicy policy;
    private WebView portal;
    private AppTheme theme;
    private LinearProgressIndicator progress;
    private TextView notice, toolbarTitle, stateTitle, stateMessage;
    private LinearLayout statePanel;
    private MaterialButton back, more, retry;
    // Only a fixed route survives configuration changes in RAM. No WebView,
    // location, query string, credentials, or document state is serialized.
    private String resumeUrl;
    private String mobileCss = "";
    private boolean resumed, needsReload = true, failed, destroyed, logoutBusy;
    private long logoutDeadline;
    private final Runnable logoutPoll = this::pollLogout;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        try { policy = new ServerPortalPolicy(BuildConfig.SERVER_PORTAL_ORIGIN); }
        catch (IllegalArgumentException invalidProfile) { finish(); return; }
        theme = new AppTheme(this);
        Object retained = getLastCustomNonConfigurationInstance();
        String previous = retained instanceof String ? policy.restoreUrl((String) retained) : null;
        resumeUrl = previous == null ? policy.homeUrl() : previous;
        try (InputStream source = getAssets().open("portal-mobile.css")) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while ((count = source.read(buffer)) != -1 && text.size() < 32_768) text.write(buffer, 0, count);
            mobileCss = text.toString(StandardCharsets.UTF_8.name());
        } catch (IOException unavailable) { /* The server's responsive presentation remains usable. */ }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!dark);
        getWindow().setStatusBarColor(theme.surface);
        getWindow().setNavigationBarColor(theme.surface);
        LinearLayout screen = theme.column(); screen.setBackgroundColor(theme.background); setContentView(screen);
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom)); return insets;
        });
        LinearLayout heading = new LinearLayout(this); heading.setTag("portal-toolbar");
        heading.setGravity(Gravity.CENTER_VERTICAL); heading.setMinimumHeight(theme.dp(56));
        heading.setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4)); heading.setBackgroundColor(theme.surface);
        back = iconButton("back", R.string.portal_back, "portal-back", view -> goBack());
        heading.addView(back, new LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)));
        toolbarTitle = theme.heading(policy.navigationTitle(resumeUrl), 17); toolbarTitle.setTag("portal-title");
        toolbarTitle.setMaxLines(2); toolbarTitle.setEllipsize(TextUtils.TruncateAt.END);
        toolbarTitle.setPadding(theme.dp(8), 0, theme.dp(8), 0);
        heading.addView(toolbarTitle, new LinearLayout.LayoutParams(0, -2, 1));
        more = iconButton("more", R.string.portal_more, "portal-menu", view -> openMenu());
        heading.addView(more, new LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)));
        screen.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        progress = new LinearProgressIndicator(this); progress.setIndeterminate(true); progress.setIndicatorColor(theme.accent);
        progress.setTrackColor(theme.subtle); screen.addView(progress, new LinearLayout.LayoutParams(-1, theme.dp(2)));
        notice = theme.text("", 15, theme.warning); notice.setPadding(theme.dp(16), theme.dp(10), theme.dp(16), theme.dp(10));
        notice.setBackgroundColor(theme.subtle);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); notice.setVisibility(View.GONE); screen.addView(notice);
        FrameLayout content = new FrameLayout(this); screen.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        portal = new WebView(this); portal.setBackgroundColor(theme.background); configureWebView();
        content.addView(portal, new FrameLayout.LayoutParams(-1, -1));
        android.widget.ScrollView stateScroll = new android.widget.ScrollView(this);
        stateScroll.setTag("portal-state"); stateScroll.setFillViewport(true); stateScroll.setBackgroundColor(theme.background);
        statePanel = theme.column(); statePanel.setGravity(Gravity.CENTER);
        statePanel.setPadding(theme.dp(24), theme.dp(24), theme.dp(24), theme.dp(24));
        stateTitle = theme.heading(getString(R.string.portal_loading_title), 24); stateTitle.setGravity(Gravity.CENTER);
        statePanel.addView(stateTitle, new LinearLayout.LayoutParams(-1, -2));
        stateMessage = theme.text(getString(R.string.portal_loading_message), 17, theme.muted); stateMessage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2); messageParams.topMargin = theme.dp(12);
        statePanel.addView(stateMessage, messageParams);
        retry = theme.button(getString(R.string.portal_retry), true, view -> load(resumeUrl)); retry.setTag("portal-retry");
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, -2); retryParams.topMargin = theme.dp(24);
        statePanel.addView(retry, retryParams); retry.setVisibility(View.GONE);
        stateScroll.addView(statePanel, new android.widget.ScrollView.LayoutParams(-1, -1));
        content.addView(stateScroll, new FrameLayout.LayoutParams(-1, -1));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        ViewCompat.requestApplyInsets(screen);
    }

    private MaterialButton iconButton(String icon, int label, String tag, View.OnClickListener action) {
        MaterialButton button = theme.button("", false, action);
        button.setTag(tag); button.setContentDescription(getString(label)); button.setTooltipText(getString(label));
        button.setIcon(theme.icon(icon, theme.text)); button.setIconTint(ColorStateList.valueOf(theme.text));
        button.setIconSize(theme.dp(24)); button.setIconPadding(0); button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setPadding(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(theme.dp(48)); button.setMinimumHeight(theme.dp(48));
        button.setStrokeWidth(0); button.setBackgroundTintList(ColorStateList.valueOf(theme.surface));
        return button;
    }

    private void openMenu() {
        if (destroyed || logoutBusy) return;
        String[] actions = {getString(R.string.portal_reload), getString(R.string.portal_dashboard),
                getString(R.string.portal_start), getString(R.string.portal_service_info),
                getString(R.string.portal_logout), getString(R.string.portal_close)};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.portal_more).setItems(actions, (dialog, index) -> {
            if (index == 0) reloadPage();
            else if (index == 1) load(policy.homeUrl());
            else if (index == 2) load(policy.origin() + "/#/");
            else if (index == 3) new MaterialAlertDialogBuilder(this).setTitle(R.string.portal_service_info)
                    .setMessage("BubbleLock " + BuildConfig.VERSION_NAME + "\n" + Uri.parse(policy.origin()).getAuthority())
                    .setPositiveButton(android.R.string.ok, null).show();
            else if (index == 4) confirmLogout();
            else if (index == 5) finish();
        }).show();
    }

    private void reloadPage() {
        if (destroyed || logoutBusy) return;
        if (failed || !policy.isInternal(portal.getUrl())) load(resumeUrl);
        else { rememberRoute(portal.getUrl()); portal.reload(); }
    }

    private void rememberRoute(String url) {
        String route = policy.restoreUrl(url);
        if (route != null) resumeUrl = route;
        toolbarTitle.setText(policy.navigationTitle(url));
    }

    private void setStateVisible(boolean visible) {
        if (statePanel != null && statePanel.getParent() instanceof View)
            ((View) statePanel.getParent()).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        portal.setSaveEnabled(false); portal.setSaveFromParentEnabled(false);
        portal.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings = portal.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(ServerPortalPolicy.webTextZoom(getResources().getConfiguration().fontScale));
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
                if (!request.isForMainFrame() && policy.isAppStylesheet(url))
                    return new WebResourceResponse("text/css", "UTF-8", 200, "OK",
                            java.util.Map.of("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff"),
                            new ByteArrayInputStream(mobileCss.getBytes(StandardCharsets.UTF_8)));
                if (request.isForMainFrame() && !policy.isInternal(url)) return blocked();
                if ("file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) return blocked();
                return null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (destroyed || "about:blank".equals(url)) return;
                if (!policy.isInternal(url)) { view.stopLoading(); fail(R.string.portal_navigation_blocked); return; }
                failed = false; rememberRoute(url); notice.setVisibility(View.GONE); progress.setVisibility(View.VISIBLE); updateActions();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (destroyed || "about:blank".equals(url)) return;
                rememberRoute(url); progress.setVisibility(logoutBusy ? View.VISIBLE : View.INVISIBLE);
                if (!failed && !logoutBusy) setStateVisible(false);
                applyMobilePresentation();
                updateActions();
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (!destroyed && policy.isInternal(url)) rememberRoute(url);
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
    private void applyMobilePresentation() {
        if (destroyed || failed || mobileCss.isEmpty() || !isPortalDocument()) return;
        String script = "(function(){if(location.origin!==" + org.json.JSONObject.quote(policy.origin())
                + "||!['/','/index.html'].includes(location.pathname))return false;"
                + "document.documentElement.classList.add('bubble-android-app');"
                + "let s=document.getElementById('bubble-android-presentation');"
                + "if(!s){s=document.createElement('link');s.id='bubble-android-presentation';s.rel='stylesheet';"
                + "s.href=" + org.json.JSONObject.quote(policy.origin() + "/__bubble_android__/portal-mobile.css")
                + ";document.head.appendChild(s);}return true;})()";
        portal.evaluateJavascript(script, null);
    }
    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (android.content.ActivityNotFoundException unavailable) { showNotice(R.string.portal_external_unavailable); }
    }
    private void load(String url) {
        if (destroyed || logoutBusy || !policy.isInternal(url)) return;
        failed = false; notice.setVisibility(View.GONE); stateTitle.setText(R.string.portal_loading_title);
        stateMessage.setText(R.string.portal_loading_message); retry.setVisibility(View.GONE); setStateVisible(true);
        portal.setVisibility(resumed ? View.VISIBLE : View.INVISIBLE); portal.loadUrl(url); updateActions();
    }
    private void goBack() {
        if (logoutBusy) return;
        if (portal != null && portal.canGoBack()) portal.goBack(); else finish();
    }
    private void updateActions() {
        back.setEnabled(!logoutBusy); more.setEnabled(!logoutBusy);
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
        failed = true; portal.setVisibility(View.INVISIBLE); progress.setVisibility(View.INVISIBLE); notice.setVisibility(View.GONE);
        stateTitle.setText(R.string.portal_error_title); stateMessage.setText(message); retry.setVisibility(View.VISIBLE); setStateVisible(true);
        stateMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
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
            resumeUrl = policy.homeUrl();
            if (resumed) load(policy.homeUrl()); else needsReload = true;
            showNotice(R.string.portal_signed_out); updateActions();
        });
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (portal != null) { portal.onResume(); if (needsReload) { needsReload = false; load(resumeUrl); } updateActions(); }
    }
    @Override protected void onPause() {
        resumed = false;
        if (portal != null) {
            CookieManager.getInstance().flush();
            portal.setVisibility(View.INVISIBLE); portal.onPause();
        }
        super.onPause();
    }
    @Override protected void onStop() {
        if (portal != null) {
            rememberRoute(portal.getUrl());
            main.removeCallbacks(logoutPoll); logoutBusy = false; needsReload = true;
            portal.stopLoading(); portal.loadUrl("about:blank"); portal.clearHistory();
        }
        super.onStop();
    }
    @Override public Object onRetainCustomNonConfigurationInstance() {
        if (portal != null && !destroyed) rememberRoute(portal.getUrl());
        return resumeUrl;
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
