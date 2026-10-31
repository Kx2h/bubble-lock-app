package org.privacybubble.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.privacybubble.app.auth.MobileSignInActivity;
import org.privacybubble.app.auth.MobileTokenManager;
import org.privacybubble.app.location.KeyringLocation;
import org.privacybubble.app.location.LocationRepository;
import org.privacybubble.app.location.LocationSnapshot;
import org.privacybubble.app.session.MobileSessionStore;
import org.privacybubble.app.ui.AppTheme;
import org.privacybubble.app.ui.KeyringMapView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Product launcher: authorized location map, accessible keyring list, and service settings. */
public final class MainActivity extends AppCompatActivity {
    private static final int SIGN_IN = 7701;
    private static final long REFRESH_MILLIS = 30_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> { if (this.foreground) fetchLocations(); };
    private AppTheme theme;
    private MobileSessionStore session;
    private ScreenState state;
    private KeyringMapView map;
    private LinearLayout root, mapDetails, keyringRows;
    private LocationCard selectedCard;
    private final Map<String, LocationCard> keyringCards = new LinkedHashMap<>();
    private String mapEmptySignature, listEmptySignature;
    private ScrollView mapScroll, keyringScroll, settingsScroll;
    private FrameLayout pages;
    private TextView connectionPill, banner, mapCaption, listCaption, settingsStatus;
    private LinearProgressIndicator progress;
    private MaterialButton refreshButton, accountButton;
    private final List<MaterialButton> navButtons = new ArrayList<>();
    private TextInputLayout addressField;
    private TextInputEditText addressInput;
    private String addressDraft;
    private String addressError, addressHelper;
    private int addressSelectionStart, addressSelectionEnd, tab;
    private boolean foreground, resumed, mapStarted, mapResumed, vaultProblem;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        theme = new AppTheme(this); session = new MobileSessionStore(this);
        Object retained = getLastCustomNonConfigurationInstance();
        state = retained instanceof ScreenState ? (ScreenState)retained : new ScreenState();
        if (saved != null) {
            tab = Math.max(0, Math.min(2, saved.getInt("tab", 0)));
            state.selectedId = saved.getString("selectedKeyring", state.selectedId);
            addressDraft = saved.getString("addressDraft");
            addressError = saved.getString("addressError"); addressHelper = saved.getString("addressHelper");
            addressSelectionStart = saved.getInt("addressSelectionStart", 0);
            addressSelectionEnd = saved.getInt("addressSelectionEnd", 0);
        }
        if (addressDraft == null) addressDraft = safeBase();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!dark);
        map = new KeyringMapView(this, saved);
        String mapStyle = BuildConfig.MAP_STYLE_URL;
        if (dark && KeyringMapView.DEFAULT_STYLE_URL.equals(mapStyle)) mapStyle = "https://tiles.openfreemap.org/styles/dark";
        map.setStyleUrl(mapStyle);
        map.setOnKeyringSelectedListener(id -> selectKeyring(id, false));
        buildShell(); renderState(); selectTab(tab, false);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (tab != 0) selectTab(0, true);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
    }
    @Override public Object onRetainCustomNonConfigurationInstance() { return state; }
    @Override protected void onStart() {
        super.onStart(); foreground = true; state.listener = this::renderState;
        syncMapLifecycle(); renderState(); fetchLocations();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; syncMapLifecycle(); }
    @Override protected void onPause() { resumed = false; syncMapLifecycle(); super.onPause(); }
    @Override protected void onStop() {
        foreground = false; main.removeCallbacks(refresh); state.detach(); syncMapLifecycle(); super.onStop();
    }
    @Override protected void onDestroy() {
        map.onDestroy(); if (!isChangingConfigurations()) state.close(); super.onDestroy();
    }
    @Override public void onLowMemory() { super.onLowMemory(); if (map != null) map.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        captureAddress(); out.putInt("tab", tab); out.putString("selectedKeyring", state.selectedId);
        out.putString("addressDraft", addressDraft); out.putInt("addressSelectionStart", addressSelectionStart);
        out.putString("addressError", addressError); out.putString("addressHelper", addressHelper);
        out.putInt("addressSelectionEnd", addressSelectionEnd); map.onSaveInstanceState(out);
        super.onSaveInstanceState(out);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SIGN_IN) { state.clear(); renderState(); if (result == RESULT_OK) fetchLocations(); }
    }

    private void buildShell() {
        boolean compact = getResources().getConfiguration().screenHeightDp < 480;
        root = theme.column(); root.setBackgroundColor(theme.background); setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom)); return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(theme.dp(22), theme.dp(16), theme.dp(22), theme.dp(16));
        LinearLayout brand = theme.column();
        TextView wordmark = theme.heading("Bubble", 26); wordmark.setTextColor(theme.accent); brand.addView(wordmark);
        brand.addView(theme.text("나와 연결된 소중한 일상", 13, theme.muted));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        connectionPill = theme.text("연결 필요", 13, theme.muted);
        connectionPill.setPadding(theme.dp(12), theme.dp(8), theme.dp(12), theme.dp(8));
        connectionPill.setBackground(theme.shape(theme.subtle, Color.TRANSPARENT, 24)); header.addView(connectionPill); header.setVisibility(compact ? View.GONE : View.VISIBLE); root.addView(header);
        progress = new LinearProgressIndicator(this); progress.setIndeterminate(true); progress.setIndicatorColor(theme.accent);
        progress.setTrackColor(theme.subtle); root.addView(progress, new LinearLayout.LayoutParams(-1, theme.dp(3)));
        banner = theme.text("", 14, theme.warning); banner.setPadding(theme.dp(22), theme.dp(12), theme.dp(22), theme.dp(12));
        banner.setBackgroundColor(theme.subtle); banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(banner);
        pages = new FrameLayout(this); root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        buildMapPage(); buildKeyringsPage(); buildSettingsPage();
        View divider = new View(this); divider.setBackgroundColor(theme.border); root.addView(divider, new LinearLayout.LayoutParams(-1, theme.dp(1)));
        LinearLayout nav = new LinearLayout(this); nav.setPadding(theme.dp(10), theme.dp(8), theme.dp(10), theme.dp(8)); nav.setBackgroundColor(theme.surface);
        String[] names = {getString(R.string.nav_location), getString(R.string.nav_keyrings), getString(R.string.nav_settings)};
        String[] icons = {"map", "keyring", "settings"};
        for (int i=0;i<names.length;i++) {
            final int index=i; MaterialButton item = theme.button(names[i], false, v -> selectTab(index, true));
            item.setIcon(compact ? null : theme.icon(icons[i], theme.accent)); item.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
            item.setIconSize(theme.dp(23)); item.setIconPadding(theme.dp(5)); item.setTextSize(13);
            item.setStrokeWidth(0); item.setPadding(theme.dp(6), theme.dp(compact ? 4 : 8), theme.dp(6), theme.dp(compact ? 4 : 8)); item.setMinHeight(theme.dp(compact ? 48 : 64));
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1)); navButtons.add(item);
        }
        root.addView(nav); ViewCompat.requestApplyInsets(root);
    }
    private ScrollView page(LinearLayout body) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), theme.dp(24)); scroll.addView(body);
        pages.addView(scroll, new FrameLayout.LayoutParams(-1, -1)); return scroll;
    }
    private void buildMapPage() {
        LinearLayout body = theme.column();
        LinearLayout title = new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(theme.heading("키링 위치", 24), new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = theme.button("새로고침", false, v -> fetchLocations());
        refreshButton.setIcon(theme.icon("refresh", theme.accent)); refreshButton.setIconSize(theme.dp(19));
        refreshButton.setContentDescription("키링 위치 새로고침"); title.addView(refreshButton); body.addView(title);
        mapCaption = theme.text("공유된 위치를 한눈에 확인하세요.", 14, theme.muted); add(body, mapCaption, 6, 16);
        FrameLayout mapFrame = new FrameLayout(this); mapFrame.setBackground(theme.shape(theme.subtle, theme.border, 24)); mapFrame.setClipToOutline(true);
        mapFrame.addView(map, new FrameLayout.LayoutParams(-1, -1)); body.addView(mapFrame, new LinearLayout.LayoutParams(-1, theme.dp(300)));
        MaterialButton center = theme.button("선택한 키링 위치로", false, v -> map.focusSelected()); center.setTag("centerKeyring"); add(body, center, 12, 0);
        mapDetails = theme.column(); add(body, mapDetails, 18, 0); mapScroll = page(body); mapScroll.setId(R.id.map_page);
    }
    private void buildKeyringsPage() {
        LinearLayout body = theme.column(); body.addView(theme.heading("내 키링", 26));
        listCaption = theme.text("위치가 공유된 키링을 확인할 수 있어요.", 15, theme.muted); add(body, listCaption, 8, 20);
        keyringRows = theme.column(); body.addView(keyringRows); keyringScroll = page(body); keyringScroll.setId(R.id.keyring_page);
    }
    private void buildSettingsPage() {
        LinearLayout body = theme.column(); body.addView(theme.heading("설정", 26));
        add(body, theme.text("안전하게 연결하고, 필요한 정보만 확인하세요.", 15, theme.muted), 8, 24);
        LinearLayout account = theme.panel(); account.addView(theme.heading("내 계정", 19));
        settingsStatus = theme.text("", 16, theme.muted); add(account, settingsStatus, 10, 16);
        accountButton = theme.button("로그인", true, v -> { if (hasSession()) confirmSignOut(); else signIn(); }); account.addView(accountButton); body.addView(account);
        LinearLayout service = theme.panel(); add(service, theme.heading("서비스 연결", 19), 0, 8);
        add(service, theme.text("안내받은 서비스 주소를 저장한 후 계정으로 로그인하세요.", 15, theme.muted), 0, 16);
        addressField = new TextInputLayout(this); addressField.setHint("서비스 주소"); addressField.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        addressField.setBoxCornerRadii(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12));
        addressInput = new TextInputEditText(addressField.getContext()); addressInput.setSingleLine(true);
        addressInput.setId(R.id.service_address);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setTextSize(16); addressInput.setText(addressDraft); addressInput.setHint("https://service.example.org");
        addressInput.setSelection(clamp(addressSelectionStart, addressDraft.length()), clamp(addressSelectionEnd, addressDraft.length()));
        addressField.addView(addressInput); service.addView(addressField); add(service, theme.button("연결 정보 저장", false, v -> saveConnection()), 16, 0);
        add(service, theme.button("서비스 연결 해제", false, v -> confirmDisconnect()), 10, 0); add(body, service, 18, 0);
        addressField.setHelperText(addressHelper); addressField.setError(addressError);
        LinearLayout privacy = theme.panel(); privacy.addView(theme.heading("위치와 개인정보", 19));
        add(privacy, theme.text("키링이 공유한 위치만 표시하며 휴대폰의 위치 권한은 사용하지 않아요. 위치가 늦게 도착하거나 정확하지 않을 수 있어요.", 15, theme.muted), 10, 0); add(body, privacy, 18, 0);
        if (BuildConfig.DEBUG) {
            LinearLayout developer = theme.panel(); developer.addView(theme.heading("개발 도구", 19));
            add(developer, theme.text("개발 빌드에서만 보이는 메뉴예요.", 14, theme.muted), 8, 14);
            developer.addView(theme.button("개발 인증 연결", false, v -> debugConnection()));
            add(developer, theme.button("시험 도구 열기", false, v -> {
                Intent intent = new Intent(); intent.setClassName(this, "org.privacybubble.app.DeveloperToolsActivity"); startActivity(intent);
            }), 10, 0); add(body, developer, 18, 0);
        }
        TextView version = theme.text("Bubble · " + BuildConfig.VERSION_NAME, 13, theme.muted); version.setGravity(Gravity.CENTER); add(body, version, 26, 0); settingsScroll = page(body); settingsScroll.setId(R.id.settings_page);
    }

    private void selectTab(int selected, boolean user) {
        if (tab == 2) captureAddress(); tab = selected;
        mapScroll.setVisibility(tab == 0 ? View.VISIBLE : View.GONE); keyringScroll.setVisibility(tab == 1 ? View.VISIBLE : View.GONE); settingsScroll.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        for (int i=0;i<navButtons.size();i++) {
            MaterialButton item = navButtons.get(i); boolean active = i == tab; item.setSelected(active);
            item.setBackgroundTintList(ColorStateList.valueOf(active ? theme.subtle : theme.surface)); item.setContentDescription(item.getText() + (active ? ", 선택됨" : ""));
        }
        if (tab != 2 && addressInput != null) {
            addressInput.clearFocus(); InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(addressInput.getWindowToken(), 0);
        }
        syncMapLifecycle(); if (user) pages.announceForAccessibility(tab == 0 ? "키링 위치" : tab == 1 ? "내 키링" : "설정");
    }
    private void syncMapLifecycle() {
        if (map == null) return;
        boolean start = foreground && tab == 0, resume = start && resumed;
        if (!resume && mapResumed) { map.onPause(); mapResumed = false; }
        if (!start && mapStarted) { map.onStop(); mapStarted = false; }
        if (start && !mapStarted) { map.onStart(); mapStarted = true; }
        if (resume && !mapResumed) { map.onResume(); mapResumed = true; }
    }

    private void renderState() {
        if (root == null) return;
        boolean signedIn = hasSession();
        if (!signedIn && !state.locations.isEmpty()) state.clear();
        if (state.errorCode == 401 && signedIn) {
            try { session.clearSession(); } catch (Exception error) { vaultProblem = true; }
            signedIn = false;
        }
        progress.setVisibility(state.loading ? View.VISIBLE : View.INVISIBLE);
        String pill = safeBase().isEmpty() || !signedIn ? "연결 필요" : state.loading ? "확인 중" : state.errorCode == 403 ? "접근 제한" : state.error != null ? "연결 끊김" : state.loaded ? "연결됨" : "연결 확인";
        connectionPill.setText(pill); connectionPill.setTextColor(state.error != null ? theme.warning : signedIn ? theme.accent : theme.muted);
        String notice = vaultProblem ? "연결 정보를 안전하게 불러오지 못했어요. 앱을 다시 열어 주세요."
                : state.errorCode == 401 ? "로그인이 만료됐어요. 다시 로그인해 주세요."
                : state.errorCode == 403 ? "위치를 확인할 권한이 없어요. 계정의 공유 설정을 확인해 주세요."
                : state.error != null ? (state.locations.isEmpty() ? "위치를 불러오지 못했어요. 연결을 확인하고 다시 시도해 주세요." : "연결이 끊겨 마지막 위치를 표시하고 있어요. 현재 위치와 다를 수 있어요.") : "";
        if (!notice.contentEquals(banner.getText())) banner.setText(notice); banner.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        refreshButton.setEnabled(signedIn && !safeBase().isEmpty() && !state.loading);
        mapCaption.setText(state.loading ? "공유된 위치를 확인하고 있어요." : state.lastSuccess > 0 && signedIn
                ? "최근 확인 " + relative(Instant.ofEpochMilli(state.lastSuccess).toString()) + " · 위치 도착이 지연될 수 있어요." : "공유된 위치를 한눈에 확인하세요.");
        settingsStatus.setText(signedIn ? "계정이 연결되어 있어요. 공유가 허용된 키링만 표시해요." : "로그인하면 내게 공유된 키링 위치를 확인할 수 있어요.");
        accountButton.setText(signedIn ? "로그아웃" : "로그인"); accountButton.setEnabled(!vaultProblem);
        List<KeyringLocation> visible = visibleLocations(); map.updateLocations(visible, state.selectedId);
        View center = mapScroll.findViewWithTag("centerKeyring"); KeyringLocation selected = selected(visible);
        if (center != null) {
            center.setEnabled(selected != null && selected.hasCoordinates());
            center.setContentDescription(selected == null ? "선택한 키링 위치로" : selected.name + " 위치로 지도 이동");
        }
        renderMapDetails(selected, signedIn); renderKeyrings(visible, signedIn);
    }
    private List<KeyringLocation> visibleLocations() {
        List<KeyringLocation> result = new ArrayList<>(); long now = System.currentTimeMillis();
        for (KeyringLocation item : state.locations) {
            if (item.hasCoordinates() && (state.error != null || !item.isFresh(now)))
                result.add(new KeyringLocation(item.id, item.name, "stale", item.latitude, item.longitude, item.accuracyMeters,
                        item.observedAt, item.receivedAt, item.expiresAt, item.source, item.connectivity, item.batteryPercent));
            else result.add(item);
        }
        return result;
    }
    private KeyringLocation selected(List<KeyringLocation> locations) {
        for (KeyringLocation item : locations) if (item.id.equals(state.selectedId)) return item; return null;
    }
    private void renderMapDetails(KeyringLocation selected, boolean signedIn) {
        if (selected == null) {
            String signature = emptySignature(signedIn);
            if (selectedCard != null || !signature.equals(mapEmptySignature)) {
                mapDetails.removeAllViews(); mapDetails.addView(emptyPanel(signedIn));
                selectedCard = null; mapEmptySignature = signature;
            }
            return;
        }
        if (selectedCard == null) {
            mapDetails.removeAllViews(); selectedCard = new LocationCard(true);
            mapDetails.addView(selectedCard.view); mapEmptySignature = null;
        }
        selectedCard.bind(selected, true);
    }
    private void renderKeyrings(List<KeyringLocation> locations, boolean signedIn) {
        setText(listCaption, locations.isEmpty() ? "위치가 공유된 키링을 확인할 수 있어요." : "총 " + locations.size() + "개 · 위치와 최근 상태");
        if (locations.isEmpty()) {
            String signature = emptySignature(signedIn);
            if (!keyringCards.isEmpty() || !signature.equals(listEmptySignature)) {
                keyringCards.clear(); keyringRows.removeAllViews(); keyringRows.addView(emptyPanel(signedIn)); listEmptySignature = signature;
            }
            return;
        }
        if (keyringCards.isEmpty()) keyringRows.removeAllViews();
        listEmptySignature = null;
        Set<String> ids = new HashSet<>(); for (KeyringLocation item : locations) ids.add(item.id);
        Iterator<Map.Entry<String, LocationCard>> obsolete = keyringCards.entrySet().iterator();
        while (obsolete.hasNext()) {
            Map.Entry<String, LocationCard> row = obsolete.next();
            if (!ids.contains(row.getKey())) { keyringRows.removeView(row.getValue().view); obsolete.remove(); }
        }
        for (int index = 0; index < locations.size(); index++) {
            KeyringLocation item = locations.get(index); LocationCard card = keyringCards.get(item.id);
            if (card == null) { card = new LocationCard(false); keyringCards.put(item.id, card); }
            if (keyringRows.getChildAt(index) != card.view) {
                keyringRows.removeView(card.view);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = theme.dp(16);
                keyringRows.addView(card.view, index, params);
            }
            card.bind(item, item.id.equals(state.selectedId));
        }
    }
    private String emptySignature(boolean signedIn) {
        return safeBase().isEmpty() + ":" + signedIn + ":" + state.loading + ":" + state.errorCode + ":" + (state.error != null);
    }
    private static void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    /** Stable native views preserve reading position and TalkBack focus during polling. */
    private final class LocationCard {
        final LinearLayout view = theme.panel();
        final TextView name = theme.heading("", 22), status = badge("", theme.muted);
        final TextView selection = theme.text("선택한 키링", 13, theme.accent);
        final TextView summary = theme.text("", 16, theme.text);
        final TextView stale = theme.text("이전 위치 정보예요. 현재 위치와 다를 수 있어요.", 15, theme.warning);
        final InfoField time, accuracy, provider, coordinate, battery, connection;
        final MaterialButton action;
        final boolean detail;
        String currentId;
        LocationCard(boolean detail) {
            this.detail = detail; view.addView(name); add(view, selection, 4, 0); add(view, status, 10, 12); view.addView(summary); add(view, stale, 8, 8);
            time = new InfoField(view, "정보 시각"); accuracy = new InfoField(view, "위치 정확도");
            provider = new InfoField(view, "위치 출처"); coordinate = new InfoField(view, "좌표");
            battery = new InfoField(view, "배터리"); connection = new InfoField(view, "키링 연결");
            action = theme.button(detail ? "키링 목록 보기" : "지도에서 보기", false, v -> {
                if (this.detail) selectTab(1, true); else selectKeyring(currentId, true);
            });
            add(view, action, 16, 0);
        }
        void bind(KeyringLocation item, boolean selected) {
            currentId = item.id; boolean coordinates = item.hasCoordinates(), fresh = item.isFresh(System.currentTimeMillis());
            boolean denied = "denied".equals(item.status);
            setText(name, item.name); setText(status, status(item)); status.setTextColor(fresh ? theme.success : theme.warning);
            selection.setVisibility(!detail && selected ? View.VISIBLE : View.GONE);
            setText(summary, coordinates ? "마지막 위치 정보 " + relative(item.observedAt) : noLocation(item));
            stale.setVisibility(coordinates && !fresh ? View.VISIBLE : View.GONE);
            time.bind(coordinates, absolute(item.observedAt));
            accuracy.bind(coordinates, item.accuracyMeters == null ? "제공 정보 없음" : "약 " + distance(item.accuracyMeters) + " 반경");
            provider.bind(coordinates, source(item.source));
            coordinate.bind(coordinates, coordinates ? String.format(Locale.US, "%.5f, %.5f", item.latitude, item.longitude) : "");
            battery.bind(!denied && item.batteryPercent != null, item.batteryPercent == null ? "" : item.batteryPercent + "%");
            connection.bind(!denied, connectivity(item.connectivity));
            action.setVisibility(detail || coordinates ? View.VISIBLE : View.GONE);
            action.setContentDescription(detail ? "키링 목록 보기" : item.name + ", 지도에서 보기");
            view.setBackground(theme.shape(!detail && selected ? theme.subtle : theme.surface, !detail && selected ? theme.accent : theme.border, 22));
        }
    }
    private final class InfoField {
        final LinearLayout row = theme.column(); final TextView value = theme.text("", 15, theme.text);
        InfoField(LinearLayout parent, String label) {
            row.setPadding(0, theme.dp(7), 0, theme.dp(7)); row.addView(theme.text(label, 13, theme.muted)); add(row, value, 2, 0); parent.addView(row);
        }
        void bind(boolean visible, String text) {
            setText(value, visible ? text : ""); row.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    private LinearLayout emptyPanel(boolean signedIn) {
        LinearLayout empty = theme.panel(); String title, message, action;
        if (safeBase().isEmpty()) { title = "키링과 연결해 볼까요?"; message = "서비스를 연결하면 내게 공유된 키링 위치를 여기서 확인할 수 있어요."; action = "서비스 연결하기"; }
        else if (!signedIn) { title = "내 키링을 만나보세요"; message = "계정으로 로그인하면 공유가 허용된 위치만 안전하게 표시해요."; action = "로그인"; }
        else if (state.loading) { title = "키링을 확인하고 있어요"; message = "잠시만 기다려 주세요. 공유된 위치와 최근 상태를 불러오고 있어요."; action = null; }
        else if (state.errorCode == 403) { title = "위치 공유 권한이 필요해요"; message = "현재 계정으로 확인할 수 있는 위치가 없어요. 서비스의 공유 설정을 확인해 주세요."; action = "다시 확인"; }
        else if (state.error != null) { title = "지금은 연결할 수 없어요"; message = "인터넷 연결과 서비스 주소를 확인한 뒤 다시 시도해 주세요."; action = "다시 시도"; }
        else { title = "공유된 키링이 아직 없어요"; message = "위치 공유가 허용된 키링이 연결되면 여기에 나타나요."; action = "다시 확인"; }
        TextView symbol = theme.text("", 32, theme.accent); symbol.setCompoundDrawablesWithIntrinsicBounds(theme.icon("keyring", theme.accent), null, null, null);
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); empty.addView(symbol);
        add(empty, theme.heading(title, 22), 16, 10); add(empty, theme.text(message, 16, theme.muted), 0, 4);
        if (action != null) add(empty, theme.button(action, true, v -> {
            if (safeBase().isEmpty()) selectTab(2, true); else if (!hasSession()) signIn(); else fetchLocations();
        }), 18, 0); return empty;
    }

    private void selectKeyring(String id, boolean switchToMap) {
        state.selectedId = id; renderState(); if (switchToMap) { selectTab(0, true); map.focusSelected(); }
    }
    private void fetchLocations() {
        main.removeCallbacks(refresh); if (!foreground) return;
        if (safeBase().isEmpty()) { state.clear(); renderState(); return; }
        if (!hasSession()) {
            state.clear(); renderState(); main.postDelayed(refresh, REFRESH_MILLIS); return;
        }
        if (!state.loading) state.load(safeBase(), session); main.postDelayed(refresh, REFRESH_MILLIS);
    }
    private boolean hasSession() {
        // The refresh credential is deliberately consumed before network I/O. Keep the
        // current authenticated loading state while that one attempt is in progress.
        try { return !session.getAccessToken().isEmpty() || !session.getRefreshToken().isEmpty() || (state != null && state.loading); }
        catch (Exception error) { vaultProblem = true; return false; }
    }
    private String safeBase() { try { return session.getApiBaseUrl(); } catch (Exception error) { vaultProblem = true; return ""; } }
    private void signIn() {
        if (safeBase().isEmpty()) { selectTab(2, true); addressInput.requestFocus(); return; }
        try {
            if (session.getIssuer().isEmpty() || session.getClientId().isEmpty()) {
                showMessage("로그인 서비스 준비 중", "이 앱의 로그인 서비스가 아직 설정되지 않았어요. 서비스 제공자에게 연결 정보를 확인해 주세요."); return;
            }
            startActivityForResult(new Intent(this, MobileSignInActivity.class), SIGN_IN);
        } catch (Exception error) { showMessage("로그인할 수 없어요", "안전한 연결을 시작하지 못했어요. 잠시 후 다시 시도해 주세요."); }
    }
    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this).setTitle("로그아웃할까요?").setMessage("이 기기에서 로그인 정보와 표시된 키링 위치가 지워져요.")
                .setNegativeButton("취소", null).setPositiveButton("로그아웃", (dialog, which) -> {
                    state.clear(); main.removeCallbacks(refresh); try { session.clearSession(); } catch (Exception error) { vaultProblem = true; } renderState();
                }).show();
    }
    private void confirmDisconnect() {
        new MaterialAlertDialogBuilder(this).setTitle("서비스 연결을 해제할까요?")
                .setMessage("이 기기에 입력한 서비스 주소, 로그인 정보와 표시된 키링 위치가 지워져요.")
                .setNegativeButton("취소", null).setPositiveButton("연결 해제", (dialog, which) -> {
                    state.clear(); main.removeCallbacks(refresh);
                    try {
                        session.clearAll(); addressDraft=session.getApiBaseUrl(); addressSelectionStart=addressSelectionEnd=addressDraft.length();
                        addressInput.setText(addressDraft); addressField.setError(null); addressField.setHelperText("연결을 해제했어요.");
                    } catch (Exception error) { vaultProblem=true; }
                    renderState();
                }).show();
    }
    private void saveConnection() {
        captureAddress(); String value = addressDraft.trim();
        try {
            String previous = safeBase(); session.saveConnection(value, session.getIssuer(), session.getClientId());
            if (!previous.equals(safeBase())) { state.clear(); session.clearSession(); }
            addressField.setError(null); addressField.setHelperText("연결 정보를 저장했어요.");
            InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(addressInput.getWindowToken(), 0); renderState(); fetchLocations();
        } catch (Exception error) { addressField.setError("안내받은 HTTPS 서비스 주소를 확인해 주세요."); }
    }
    private void captureAddress() {
        if (addressInput == null || addressInput.getText() == null) return;
        addressDraft = addressInput.getText().toString(); addressSelectionStart = addressInput.getSelectionStart(); addressSelectionEnd = addressInput.getSelectionEnd();
        addressError = addressField.getError() == null ? null : addressField.getError().toString();
        addressHelper = addressField.getHelperText() == null ? null : addressField.getHelperText().toString();
    }
    private void debugConnection() {
        if (!BuildConfig.DEBUG) return;
        LinearLayout content = theme.column(); content.setPadding(theme.dp(24), theme.dp(12), theme.dp(24), 0);
        add(content, theme.text("개발 서버에서 발급한 임시 인증으로 연결합니다. 먼저 서비스 주소를 저장해 주세요.", 15, theme.muted), 0, 16);
        TextInputLayout field = new TextInputLayout(this); field.setHint("개발 인증 값"); field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(field.getContext()); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); field.addView(input); content.addView(field);
        new MaterialAlertDialogBuilder(this).setTitle("개발 연결").setView(content).setNegativeButton("취소", (d,w) -> { if (input.getText()!=null) input.getText().clear(); })
                .setPositiveButton("연결", (d,w) -> {
                    try {
                        if (safeBase().isEmpty()) throw new IllegalStateException();
                        session.setDebugAccessToken(input.getText() == null ? "" : input.getText().toString().trim()); state.clear(); renderState(); fetchLocations();
                    } catch (Exception error) { showMessage("개발 연결 실패", "서비스 주소와 개발 인증 값을 확인해 주세요."); }
                    finally { if (input.getText()!=null) input.getText().clear(); }
                }).show();
    }
    private void showMessage(String title, String message) { new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("확인", null).show(); }
    private void add(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin=theme.dp(top); params.bottomMargin=theme.dp(bottom); parent.addView(child, params);
    }
    private TextView badge(String text, int color) {
        TextView badge = theme.text(text, 13, color); badge.setPadding(theme.dp(10), theme.dp(5), theme.dp(10), theme.dp(5)); badge.setBackground(theme.shape(theme.subtle, Color.TRANSPARENT, 20)); return badge;
    }
    private static int clamp(int value, int length) { return Math.max(0, Math.min(value, length)); }
    private static String status(KeyringLocation item) {
        if ("denied".equals(item.status)) return "접근 제한"; if (!item.hasCoordinates()) return "위치 확인 전";
        return item.isFresh(System.currentTimeMillis()) ? "최근 위치 정보" : "이전 위치 정보";
    }
    private static String noLocation(KeyringLocation item) { return "denied".equals(item.status) ? "이 키링의 위치를 볼 권한이 없어요." : "아직 공유된 위치가 없어요."; }
    private static String distance(double meters) { return meters < 1 ? "1 m 미만" : meters >= 1000 ? String.format(Locale.KOREAN, "%.1f km", meters / 1000) : String.format(Locale.KOREAN, "%.0f m", meters); }
    private static String source(String value) {
        if (value == null || value.isEmpty()) return "제공 정보 없음"; String source=value.toLowerCase(Locale.ROOT);
        if (source.contains("gps") || source.contains("gnss")) return "위성 위치";
        if (source.contains("mec") || source.contains("network")) return "MEC 위치 서비스";
        if (source.contains("ble")) return "근접 신호"; if (source.contains("gateway")) return "연결된 기기"; return "위치 제공 서비스";
    }
    private static String connectivity(String value) {
        if (value == null) return "확인 필요";
        switch(value.toLowerCase(Locale.ROOT)) {
            case "online": case "connected": case "healthy": return "연결됨";
            case "offline": case "disconnected": return "연결 끊김"; default: return "확인 필요";
        }
    }
    private static String relative(String timestamp) {
        if (timestamp == null) return "시각 정보 없음";
        try {
            long elapsed = Math.max(0, System.currentTimeMillis()-Instant.parse(timestamp).toEpochMilli());
            if (elapsed<60_000) return "방금 기록"; if (elapsed<3_600_000) return elapsed/60_000+"분 전 기록";
            if (elapsed<86_400_000) return elapsed/3_600_000+"시간 전 기록"; return absolute(timestamp);
        } catch (RuntimeException error) { return "시각 정보 없음"; }
    }
    private static String absolute(String timestamp) {
        if (timestamp == null) return "제공 정보 없음";
        try { return DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm z", Locale.KOREAN).withZone(ZoneId.systemDefault()).format(Instant.parse(timestamp)); }
        catch (RuntimeException error) { return "제공 정보 없음"; }
    }

    /** Retains in-memory state without an Activity or a repeating background request. */
    private static final class ScreenState {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private Future<?> request; private long generation; private List<KeyringLocation> locations = Collections.emptyList();
        private String selectedId, error; private int errorCode; private boolean loading, loaded, closed; private long lastSuccess;
        private Runnable listener;
        private void load(String base, MobileSessionStore store) {
            if (closed || loading) return; final long ticket = ++generation; loading = true; notifyListener();
            request = worker.submit(() -> {
                LocationSnapshot response = null; String failure = null; int code = 0;
                try { response = new LocationRepository(BuildConfig.DEBUG).load(base, MobileTokenManager.accessToken(store)); }
                catch (LocationRepository.ApiException problem) { failure = problem.getMessage(); code = problem.statusCode; }
                catch (Exception problem) { failure = "연결을 확인해 주세요."; }
                final LocationSnapshot result = response; final String message = failure; final int status = code;
                main.post(() -> {
                    if (closed || ticket != generation) return; loading = false; request = null; error = message; errorCode = status;
                    if (result != null) {
                        locations = result.keyrings; loaded = true; lastSuccess = System.currentTimeMillis(); boolean selectionPresent = false;
                        for (KeyringLocation item : locations) if (item.id.equals(selectedId)) selectionPresent = true;
                        if (!selectionPresent) selectedId = locations.isEmpty() ? null : locations.get(0).id;
                    } else if (status == 401 || status == 403) { locations = Collections.emptyList(); selectedId = null; lastSuccess = 0; }
                    notifyListener();
                });
            });
        }
        private void notifyListener() { if (listener != null) listener.run(); }
        private void cancel() { ++generation; if (request != null) request.cancel(true); request = null; loading = false; }
        private void detach() { listener = null; cancel(); }
        private void clear() { cancel(); locations = Collections.emptyList(); selectedId = null; error = null; errorCode = 0; loaded = false; lastSuccess = 0; }
        private void close() { closed = true; detach(); worker.shutdownNow(); }
    }
}
