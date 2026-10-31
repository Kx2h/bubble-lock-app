package org.privacybubble.app.ui;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;
import org.privacybubble.app.location.KeyringLocation;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static org.maplibre.android.style.expressions.Expression.color;
import static org.maplibre.android.style.expressions.Expression.eq;
import static org.maplibre.android.style.expressions.Expression.get;
import static org.maplibre.android.style.expressions.Expression.literal;
import static org.maplibre.android.style.expressions.Expression.switchCase;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor;
import static org.maplibre.android.style.layers.PropertyFactory.textColor;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloBlur;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloColor;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloWidth;

/** Native basemap and authorized server locations. Never enables the phone location component. */
public final class KeyringMapView extends FrameLayout {
    public static final String DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    private static final String DEFAULT_DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark";
    private static final String STATE = "privacybubble.map.camera";
    private static final String POINTS = "privacybubble-keyrings";
    private static final String AREA = "privacybubble-accuracy";
    private static final String HALO = "privacybubble-halos";
    private static final String DOTS = "privacybubble-dots";
    private static final String SELECTED = "privacybubble-selected";
    private static final LatLng OVERVIEW = new LatLng(36.3, 127.8);
    private static final int MAX_POINTS = 500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout mapHost;
    private final LinearLayout statusChip;
    private final TextView statusText, errorTitle, errorMessage, legend;
    private final ScrollView errorPanel;
    private final ProgressBar spinner;
    private final ImageButton zoomIn, zoomOut, focus;
    private final int surface, text, muted, green, amber;
    private final Runnable expire = () -> { renderLocations(); scheduleExpiry(); };
    private final Runnable loadTimeout = () -> {
        if (!this.destroyed && !this.fullyRendered && this.resumed && isShown()) showError(false);
    };
    private MapView mapView;
    private MapLibreMap map;
    private GeoJsonSource pointSource, accuracySource;
    private List<KeyringLocation> locations = Collections.emptyList();
    private Consumer<String> onSelected;
    private String selectedId, styleUrl = DEFAULT_STYLE_URL;
    private Bundle pendingCamera;
    private boolean styleReady, fullyRendered, destroyed, started, resumed, cameraPositioned;
    private int styleGeneration;

    public KeyringMapView(Context context, Bundle state) {
        super(context);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? Color.rgb(29, 43, 35) : Color.WHITE;
        text = dark ? Color.rgb(237, 244, 239) : Color.rgb(32, 45, 38);
        muted = dark ? Color.rgb(189, 205, 195) : Color.rgb(82, 103, 91);
        green = dark ? Color.rgb(115, 216, 158) : Color.rgb(25, 105, 67);
        amber = dark ? Color.rgb(244, 189, 102) : Color.rgb(151, 92, 12);
        setBackgroundColor(dark ? Color.rgb(21, 32, 26) : Color.rgb(235, 240, 235));
        if (state != null) pendingCamera = state.getBundle(STATE);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(-1, -1));
        mapHost = new FrameLayout(context);
        column.addView(mapHost, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView credits = label("© OpenStreetMap · OpenMapTiles  ·  지도 정보", 11, muted);
        credits.setGravity(Gravity.CENTER);
        credits.setMinHeight(dp(48));
        credits.setPadding(dp(12), dp(4), dp(12), dp(4));
        credits.setBackgroundColor(surface);
        credits.setContentDescription("지도 출처: OpenStreetMap, OpenMapTiles. 지도 정보 열기");
        credits.setFocusable(true);
        credits.setOnClickListener(v -> openCredits());
        column.addView(credits, new LinearLayout.LayoutParams(-1, -2));

        statusChip = new LinearLayout(context);
        statusChip.setGravity(Gravity.CENTER_VERTICAL);
        statusChip.setPadding(dp(12), dp(9), dp(12), dp(9));
        statusChip.setBackground(round(surface, 14));
        statusChip.setElevation(dp(2));
        spinner = new ProgressBar(context);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(green));
        statusChip.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        statusText = label("지도를 불러오는 중", 12, text);
        statusText.setPadding(dp(8), 0, 0, 0);
        statusText.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        statusChip.addView(statusText, new LinearLayout.LayoutParams(-2, -2));
        LayoutParams statusParams = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        statusParams.setMargins(dp(12), dp(12), dp(80), 0);
        mapHost.addView(statusChip, statusParams);

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(2), dp(2), dp(2), dp(2));
        controls.setBackground(round(surface, 16));
        controls.setElevation(dp(3));
        zoomIn = control(0, "지도 확대");
        zoomOut = control(1, "지도 축소");
        focus = control(2, "선택한 키링 위치로 이동");
        controls.addView(zoomIn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        controls.addView(zoomOut, new LinearLayout.LayoutParams(dp(48), dp(48)));
        controls.addView(focus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        zoomIn.setOnClickListener(v -> changeZoom(1));
        zoomOut.setOnClickListener(v -> changeZoom(-1));
        focus.setOnClickListener(v -> focusSelected());
        LayoutParams controlsParams = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        controlsParams.setMargins(0, dp(12), dp(12), 0);
        mapHost.addView(controls, controlsParams);

        legend = label("● 최근 확인   ○ 이전 기록", 11, text);
        legend.setPadding(dp(10), dp(7), dp(10), dp(7));
        legend.setGravity(Gravity.CENTER);
        legend.setBackgroundColor(surface);
        legend.setVisibility(GONE);
        legend.setContentDescription("채워진 초록 점은 최근 확인 위치, 빈 테두리와 황토색 점은 이전 위치 기록입니다.");
        // Text can grow to 200% without covering the SDK's attribution or map controls.
        column.addView(legend, 1, new LinearLayout.LayoutParams(-1, -2));

        errorPanel = new ScrollView(context);
        errorPanel.setFillViewport(false);
        errorPanel.setBackground(round(surface, 18));
        errorPanel.setElevation(dp(5));
        LinearLayout errorContent = new LinearLayout(context);
        errorContent.setOrientation(LinearLayout.VERTICAL);
        errorContent.setPadding(dp(20), dp(18), dp(20), dp(12));
        errorTitle = label("지도를 불러오지 못했어요", 17, text);
        errorTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) errorTitle.setAccessibilityHeading(true);
        errorTitle.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        errorContent.addView(errorTitle);
        errorMessage = label("인터넷 연결을 확인하고 다시 시도해 주세요. 키링 목록에서도 위치 정보를 확인할 수 있어요.", 14, muted);
        errorMessage.setPadding(0, dp(8), 0, dp(12));
        errorContent.addView(errorMessage);
        Button retry = new Button(context);
        retry.setText("지도 다시 불러오기");
        retry.setTextSize(14);
        retry.setTextColor(green);
        retry.setAllCaps(false);
        retry.setMinHeight(dp(48));
        retry.setOnClickListener(v -> { if (map != null) loadStyle(); else initializeMap(); });
        errorContent.addView(retry, new LinearLayout.LayoutParams(-1, -2));
        errorPanel.addView(errorContent, new ScrollView.LayoutParams(-1, -2));
        LayoutParams errorParams = new LayoutParams(-1, -2, Gravity.CENTER);
        errorParams.setMargins(dp(20), dp(56), dp(76), dp(48));
        mapHost.addView(errorPanel, errorParams);
        errorPanel.setVisibility(GONE);
        updateControlState();
        initializeMap();
    }

    private void initializeMap() {
        if (destroyed || mapView != null) return;
        try {
            MapLibre.getInstance(getContext());
            MapLibreMapOptions options = MapLibreMapOptions.createFromAttributes(getContext())
                    .textureMode(true).attributionEnabled(true).logoEnabled(true)
                    .compassEnabled(true).compassGravity(Gravity.BOTTOM | Gravity.START);
            mapView = new MapView(getContext(), options);
            mapView.setContentDescription("키링 위치 지도. 같은 위치 정보는 키링 목록에서도 확인할 수 있습니다.");
            mapHost.addView(mapView, 0, new LayoutParams(-1, -1));
            // Camera restore is deferred until an authorized location response arrives.
            mapView.onCreate(null);
            mapView.addOnDidFailLoadingMapListener(message -> {
                if (!destroyed) showError(false); // Never expose native error messages or URLs.
            });
            mapView.addOnDidFinishRenderingMapListener(fully -> {
                if (!destroyed && styleReady && fully) {
                    fullyRendered = true;
                    main.removeCallbacks(loadTimeout);
                    errorPanel.setVisibility(GONE);
                    statusChip.setVisibility(GONE);
                    mapView.setContentDescription("키링 위치 지도 · 지도 로드 완료. 같은 위치 정보는 키링 목록에서도 확인할 수 있습니다.");
                }
            });
            mapView.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) view.getParent().requestDisallowInterceptTouchEvent(true);
                else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                return false;
            });
            mapView.getMapAsync(ready -> {
                if (destroyed) return;
                map = ready;
                map.setMinZoomPreference(2);
                map.setMaxZoomPreference(20);
                map.getUiSettings().setAttributionEnabled(true);
                // Retain SDK attribution margins: it reserves space beside its logo.
                map.getUiSettings().setCompassMargins(dp(12), 0, 0, dp(48));
                map.addOnMapClickListener(this::selectAt);
                resetOverview();
                loadStyle();
            });
            if (started) mapView.onStart();
            if (resumed) mapView.onResume();
        } catch (RuntimeException | LinkageError unavailable) {
            if (mapView != null) {
                try { mapView.onDestroy(); } catch (RuntimeException | LinkageError ignored) { }
                mapHost.removeView(mapView);
            }
            mapView = null;
            map = null;
            showError(true);
        }
    }

    public void setStyleUrl(String value) {
        final URI uri;
        try {
            uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || value.length() > 2048 || !value.equals(value.trim())) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalArgumentException("A credential-free HTTPS map style is required"); }
        String next = uri.toASCIIString();
        if (!styleUrl.equals(next)) { styleUrl = next; if (map != null) loadStyle(); }
    }

    private void loadStyle() {
        if (destroyed || map == null) return;
        styleReady = false;
        fullyRendered = false;
        pointSource = null;
        accuracySource = null;
        int generation = ++styleGeneration;
        spinner.setVisibility(VISIBLE);
        statusText.setText("지도를 불러오는 중");
        statusChip.setVisibility(VISIBLE);
        errorPanel.setVisibility(GONE);
        updateControlState();
        main.removeCallbacks(loadTimeout);
        if (resumed && isShown()) main.postDelayed(loadTimeout, 25_000);
        try {
            map.setStyle(new Style.Builder().fromUri(styleUrl), style -> {
                if (destroyed || generation != styleGeneration) return;
                try {
                    improveDefaultDarkLabels(style);
                    installLayers(style);
                    styleReady = true;
                    renderLocations();
                    placeInitialCamera();
                    updateControlState();
                    scheduleExpiry();
                } catch (RuntimeException invalidStyle) {
                    styleReady = false;
                    updateControlState();
                    showError(false);
                }
            });
        } catch (RuntimeException failure) { showError(false); }
    }

    private void improveDefaultDarkLabels(Style style) {
        if (!DEFAULT_DARK_STYLE_URL.equals(styleUrl)) return;
        for (Layer layer : style.getLayers()) {
            String id = layer.getId();
            if (layer instanceof SymbolLayer && (id.startsWith("highway_name_")
                    || id.startsWith("place_") || "water_name".equals(id))) {
                layer.setProperties(textColor(Color.rgb(197, 209, 200)),
                        textHaloColor(Color.rgb(16, 26, 20)), textHaloWidth(1.25f), textHaloBlur(.25f));
            }
        }
    }

    private void installLayers(Style style) {
        accuracySource = new GeoJsonSource(AREA, FeatureCollection.fromFeatures(new Feature[0]));
        pointSource = new GeoJsonSource(POINTS, FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(accuracySource);
        style.addSource(pointSource);
        Expression tint = switchCase(get("fresh"), color(green), color(amber));
        style.addLayer(new FillLayer(AREA + "-fill", AREA).withProperties(
                fillColor(tint), fillOpacity(0.12f), fillOutlineColor(tint)));
        style.addLayer(new CircleLayer(HALO, POINTS).withProperties(
                circleColor(tint), circleOpacity(0.17f),
                circleRadius(switchCase(get("selected"), literal(22), literal(14)))));
        style.addLayer(new CircleLayer(DOTS, POINTS).withProperties(
                circleColor(switchCase(get("fresh"), color(green), color(surface))),
                circleRadius(switchCase(get("selected"), literal(10), literal(7))),
                circleStrokeColor(switchCase(get("fresh"), color(Color.WHITE), color(amber))),
                circleStrokeWidth(3f)));
        CircleLayer selected = new CircleLayer(SELECTED, POINTS).withProperties(
                circleColor(Color.WHITE), circleRadius(3f));
        selected.setFilter(Expression.all(eq(get("selected"), true), eq(get("fresh"), true)));
        style.addLayer(selected);
    }

    public void updateLocations(List<KeyringLocation> items, String selectedId) {
        if (destroyed) return;
        boolean hadCoordinates = hasAnyCoordinates();
        boolean selectionChanged = !Objects.equals(this.selectedId, selectedId);
        Set<String> previouslyAuthorized = new HashSet<>();
        for (KeyringLocation item : locations) if (item.hasCoordinates()) previouslyAuthorized.add(item.id);
        List<KeyringLocation> next = new ArrayList<>();
        if (items != null) for (KeyringLocation item : items) {
            if (item != null && item.id != null && !item.id.isEmpty() && next.size() < MAX_POINTS) next.add(item);
        }
        locations = Collections.unmodifiableList(next);
        this.selectedId = selectedId;
        Set<String> currentlyAuthorized = new HashSet<>();
        for (KeyringLocation item : locations) if (item.hasCoordinates()) currentlyAuthorized.add(item.id);
        if (!currentlyAuthorized.containsAll(previouslyAuthorized)) {
            // Revoked or removed coordinates must not survive indirectly in the last viewport.
            pendingCamera = null;
            cameraPositioned = false;
            resetOverview();
        }
        if (!hasAnyCoordinates()) {
            pendingCamera = null;
            if (hadCoordinates) resetOverview();
            cameraPositioned = false;
        }
        renderLocations();
        if (!cameraPositioned) placeInitialCamera();
        else if (selectionChanged && selectedItem() != null) focusSelected();
        updateControlState();
        scheduleExpiry();
    }

    public void setOnKeyringSelectedListener(Consumer<String> listener) { onSelected = listener; }

    private void renderLocations() {
        if (destroyed || !styleReady || pointSource == null || accuracySource == null) return;
        long now = System.currentTimeMillis();
        List<Feature> points = new ArrayList<>();
        List<Feature> accuracy = new ArrayList<>();
        for (KeyringLocation item : locations) {
            if (!item.hasCoordinates()) continue;
            Feature point = Feature.fromGeometry(Point.fromLngLat(item.longitude, item.latitude));
            point.addStringProperty("keyringId", item.id);
            point.addBooleanProperty("selected", item.id != null && item.id.equals(selectedId));
            point.addBooleanProperty("fresh", item.isFresh(now));
            points.add(point);
            if (item.id != null && item.id.equals(selectedId) && item.accuracyMeters != null
                    && Double.isFinite(item.accuracyMeters) && item.accuracyMeters > 0 && item.accuracyMeters <= 100_000) {
                Feature area = Feature.fromGeometry(accuracyArea(item));
                area.addBooleanProperty("fresh", item.isFresh(now));
                accuracy.add(area);
            }
        }
        pointSource.setGeoJson(FeatureCollection.fromFeatures(points));
        accuracySource.setGeoJson(FeatureCollection.fromFeatures(accuracy));
        legend.setVisibility(points.isEmpty() ? GONE : VISIBLE);
    }

    private static Polygon accuracyArea(KeyringLocation item) {
        List<Point> ring = new ArrayList<>();
        double latitude = Math.toRadians(item.latitude), longitude = Math.toRadians(item.longitude);
        double distance = item.accuracyMeters / 6_371_008.8;
        for (int i = 0; i <= 64; i++) {
            double bearing = 2 * Math.PI * i / 64;
            double lat = Math.asin(Math.sin(latitude) * Math.cos(distance)
                    + Math.cos(latitude) * Math.sin(distance) * Math.cos(bearing));
            double lon = longitude + Math.atan2(Math.sin(bearing) * Math.sin(distance) * Math.cos(latitude),
                    Math.cos(distance) - Math.sin(latitude) * Math.sin(lat));
            ring.add(Point.fromLngLat(Math.toDegrees(lon), Math.toDegrees(lat)));
        }
        return Polygon.fromLngLats(Collections.singletonList(ring));
    }

    private boolean selectAt(LatLng coordinate) {
        if (destroyed || !styleReady || map == null) return false;
        PointF pixel = map.getProjection().toScreenLocation(coordinate);
        float target = dp(24);
        List<Feature> hits = map.queryRenderedFeatures(new RectF(pixel.x - target, pixel.y - target,
                pixel.x + target, pixel.y + target), DOTS);
        for (Feature feature : hits) {
            if (!feature.hasProperty("keyringId")) continue;
            String id = feature.getStringProperty("keyringId");
            if (locations.stream().noneMatch(item -> id.equals(item.id) && item.hasCoordinates())) continue;
            selectedId = id;
            renderLocations();
            updateControlState();
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Consumer<String> callback = onSelected;
            if (callback != null) callback.accept(id);
            return true;
        }
        return false;
    }

    public void focusSelected() {
        KeyringLocation item = selectedItem();
        if (destroyed || map == null || !styleReady || item == null) return;
        double zoom = item.accuracyMeters == null || item.accuracyMeters <= 150 ? 16 :
                Math.max(6, Math.min(16, 17 - Math.log(item.accuracyMeters / 80) / Math.log(2)));
        move(CameraUpdateFactory.newLatLngZoom(new LatLng(item.latitude, item.longitude), zoom), true);
        cameraPositioned = true;
    }

    private void placeInitialCamera() {
        if (destroyed || !styleReady || map == null || cameraPositioned || !hasAnyCoordinates()) return;
        if (pendingCamera != null && Objects.equals(selectedId, pendingCamera.getString("keyringId"))
                && authorizedId(pendingCamera.getString("keyringId"))) {
            double latitude = pendingCamera.getDouble("lat", Double.NaN);
            double longitude = pendingCamera.getDouble("lon", Double.NaN);
            double zoom = pendingCamera.getDouble("zoom", Double.NaN);
            double bearing = pendingCamera.getDouble("bearing", 0);
            if (Double.isFinite(latitude) && Math.abs(latitude) <= 90 && Double.isFinite(longitude)
                    && Math.abs(longitude) <= 180 && Double.isFinite(zoom) && zoom >= 2 && zoom <= 20
                    && Double.isFinite(bearing)) {
                move(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                        .target(new LatLng(latitude, longitude)).zoom(zoom).bearing(bearing).build()), false);
                cameraPositioned = true;
            }
        }
        pendingCamera = null;
        if (cameraPositioned) return;
        if (selectedItem() != null) focusSelected();
        else {
            List<KeyringLocation> valid = new ArrayList<>();
            for (KeyringLocation item : locations) if (item.hasCoordinates()) valid.add(item);
            if (valid.size() == 1) {
                KeyringLocation item = valid.get(0);
                move(CameraUpdateFactory.newLatLngZoom(new LatLng(item.latitude, item.longitude), 15), false);
            } else {
                LatLngBounds.Builder bounds = new LatLngBounds.Builder();
                for (KeyringLocation item : valid) bounds.include(new LatLng(item.latitude, item.longitude));
                try { move(CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(64)), false); }
                catch (IllegalStateException notLaidOut) { return; }
            }
            cameraPositioned = true;
        }
    }

    private boolean authorizedId(String id) {
        if (id == null) return false;
        for (KeyringLocation item : locations) if (id.equals(item.id) && item.hasCoordinates()) return true;
        return false;
    }

    private KeyringLocation selectedItem() {
        for (KeyringLocation item : locations) if (Objects.equals(selectedId, item.id) && item.hasCoordinates()) return item;
        return null;
    }

    private boolean hasAnyCoordinates() {
        for (KeyringLocation item : locations) if (item.hasCoordinates()) return true;
        return false;
    }

    private void resetOverview() {
        if (map != null && !destroyed) map.moveCamera(CameraUpdateFactory.newLatLngZoom(OVERVIEW, 5.4));
    }

    private void move(CameraUpdate update, boolean animate) {
        if (map == null || destroyed) return;
        if (animate && resumed && ValueAnimator.areAnimatorsEnabled()) map.animateCamera(update, 450);
        else map.moveCamera(update);
    }

    private void changeZoom(double amount) {
        if (styleReady && map != null) move(CameraUpdateFactory.zoomBy(amount), true);
    }

    private void updateControlState() {
        boolean ready = !destroyed && map != null && styleReady;
        zoomIn.setEnabled(ready);
        zoomOut.setEnabled(ready);
        zoomIn.setAlpha(ready ? 1f : .4f);
        zoomOut.setAlpha(ready ? 1f : .4f);
        focus.setEnabled(ready && selectedItem() != null);
        focus.setAlpha(focus.isEnabled() ? 1f : .4f);
    }

    private void showError(boolean unsupported) {
        if (destroyed) return;
        main.removeCallbacks(loadTimeout);
        spinner.setVisibility(GONE);
        statusChip.setVisibility(GONE);
        errorTitle.setText(unsupported ? "이 기기에서 지도를 열지 못했어요" : "지도를 불러오지 못했어요");
        errorMessage.setText(unsupported ? "키링 목록에서 위치 정보를 계속 확인할 수 있어요. 앱을 다시 열어 시도해 주세요."
                : "인터넷 연결을 확인하고 다시 시도해 주세요. 키링 목록에서도 위치 정보를 확인할 수 있어요.");
        errorPanel.setVisibility(VISIBLE);
        if (mapView != null) mapView.setContentDescription("키링 위치 지도 · 지도 로드 실패. 키링 목록에서 위치 정보를 확인할 수 있습니다.");
    }

    private void scheduleExpiry() {
        main.removeCallbacks(expire);
        if (!destroyed && resumed && hasAnyCoordinates()) main.postDelayed(expire, 15_000);
    }

    public void onStart() { started = true; if (!destroyed && mapView != null) mapView.onStart(); }
    public void onResume() {
        resumed = true;
        if (!destroyed && mapView != null) mapView.onResume();
        main.removeCallbacks(loadTimeout);
        if (!destroyed && !fullyRendered && isShown()) main.postDelayed(loadTimeout, 25_000);
        renderLocations();
        scheduleExpiry();
    }
    public void onPause() {
        resumed = false;
        main.removeCallbacks(expire);
        main.removeCallbacks(loadTimeout);
        if (!destroyed && mapView != null) mapView.onPause();
    }
    public void onStop() { started = false; if (!destroyed && mapView != null) mapView.onStop(); }
    public void onLowMemory() { if (!destroyed && mapView != null) mapView.onLowMemory(); }
    public void onDestroy() {
        if (destroyed) return;
        destroyed = true;
        main.removeCallbacks(expire);
        main.removeCallbacks(loadTimeout);
        onSelected = null;
        locations = Collections.emptyList();
        pendingCamera = null;
        pointSource = null;
        accuracySource = null;
        map = null;
        if (mapView != null) mapView.onDestroy();
        mapView = null;
    }

    public void onSaveInstanceState(Bundle state) {
        if (destroyed || mapView == null || map == null) return;
        mapView.onSaveInstanceState(new Bundle());
        if (!hasAnyCoordinates() || !authorizedId(selectedId)) { state.remove(STATE); return; }
        CameraPosition camera = map.getCameraPosition();
        if (camera.target == null) { state.remove(STATE); return; }
        Bundle saved = new Bundle();
        saved.putString("keyringId", selectedId);
        saved.putDouble("lat", camera.target.getLatitude());
        saved.putDouble("lon", camera.target.getLongitude());
        saved.putDouble("zoom", camera.zoom);
        saved.putDouble("bearing", camera.bearing);
        state.putBundle(STATE, saved);
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // View construction can dispatch this before this class's fields are initialized.
        if (main == null) return;
        main.removeCallbacks(loadTimeout);
        if (!destroyed && resumed && !fullyRendered && isShown()) main.postDelayed(loadTimeout, 25_000);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private ImageButton control(int kind, String description) {
        ImageButton button = new ImageButton(getContext());
        button.setImageDrawable(new ControlIcon(kind));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22357A51), round(surface, 12), null));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void openCredits() {
        try { getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))); }
        catch (ActivityNotFoundException unavailable) { /* The native attribution dialog remains available. */ }
    }

    private final class ControlIcon extends Drawable {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ControlIcon(int kind) { this.kind = kind; }
        @Override public void draw(Canvas canvas) {
            float width = getBounds().width(), height = getBounds().height();
            float x = getBounds().left + width / 2, y = getBounds().top + height / 2;
            float radius = Math.min(width, height) * .34f;
            paint.setColor(green);
            paint.setStrokeWidth(Math.max(2, dp(2)));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            if (kind == 2) {
                canvas.drawCircle(x, y, radius * .75f, paint);
                canvas.drawLine(x - radius - dp(2), y, x - radius * .5f, y, paint);
                canvas.drawLine(x + radius * .5f, y, x + radius + dp(2), y, paint);
                canvas.drawLine(x, y - radius - dp(2), x, y - radius * .5f, paint);
                canvas.drawLine(x, y + radius * .5f, x, y + radius + dp(2), paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, dp(2), paint);
            } else {
                canvas.drawLine(x - radius, y, x + radius, y, paint);
                if (kind == 0) canvas.drawLine(x, y - radius, x, y + radius, paint);
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
