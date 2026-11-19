import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { estimateBleProximity, freshness } from '../shared/location.ts';
import type { Coordinates, LocationDevice } from '../shared/location.ts';
import { mapTileStyle, validMapGeofence } from '../shared/map-presentation.ts';
import type { MapGeofence, MapStyle } from '../shared/map-presentation.ts';
import { useLocations } from './useLocations.ts';
import { Badge, Timestamp } from './ui.tsx';
import './location-map.css';

const colors = ['#0070d1', '#7d4cdb', '#c2410c', '#0f8a5f', '#b4237a'];
const coordinate = (point: Coordinates): L.LatLngTuple => [point.lat, point.lon];
const meters = (value: number) => value < 10 ? `${value.toFixed(1)} m` : `${Math.round(value).toLocaleString()} m`;
const observationTime = (device: LocationDevice) => [device.position?.observedAt, device.ble?.observedAt]
  .filter((at): at is string => at !== undefined).sort((left, right) => Date.parse(left) - Date.parse(right)).at(-1);
const emptyGeofences: readonly MapGeofence[] = [];

function textElement(text: string, className = '') {
  const element = document.createElement('span');
  element.className = className;
  element.textContent = text;
  return element;
}

function MapIcon({ name }: { readonly name: 'locate' | 'refresh' | 'pin' | 'signal' | 'layers' }) {
  const paths = {
    locate: <><circle cx="12" cy="12" r="6" /><circle cx="12" cy="12" r="2" /><path d="M12 2v3m0 14v3M2 12h3m14 0h3" /></>,
    refresh: <><path d="M20 7v5h-5M4 17v-5h5" /><path d="M6 7a7 7 0 0 1 11-2l3 3M4 16l3 3a7 7 0 0 0 11-2" /></>,
    pin: <><path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" /><circle cx="12" cy="10" r="2" /></>,
    signal: <><path d="M5 18v2m5-7v7m5-12v12m5-17v17" /></>,
    layers: <><path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5" /></>,
  };
  return <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

function sourceName(device: LocationDevice, user: boolean) {
  if (device.position?.source === 'relay') return user ? '근처 릴레이가 감지한 범위' : '릴레이 범위 (BLE, MEC)';
  if (device.position?.source === 'gps') return user ? 'GPS로 확인한 위치' : 'GPS 위치';
  if (device.position?.source === 'mec') return user ? '통신망 위치 서비스로 확인' : '통신망 위치 (MEC)';
  return user ? '수신기 근처에서 감지' : '수신기 기준 (BLE)';
}

export function LocationMap({ refresh, variant = 'operator', geofences = emptyGeofences, locationUrl = '/api/locations' }: {
  readonly refresh: number;
  readonly variant?: 'operator' | 'user';
  readonly locationUrl?: string;
  readonly geofences?: readonly MapGeofence[];
}) {
  const feed = useLocations(refresh, locationUrl);
  const [selectedId, setSelectedId] = useState('');
  const [tileStyle, setTileStyle] = useState<MapStyle>('light');
  const [tileError, setTileError] = useState(false);
  const [showProximity, setShowProximity] = useState(true);
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const layers = useRef<L.FeatureGroup | null>(null);
  const tiles = useRef<L.TileLayer | null>(null);
  const initialFit = useRef(false);
  const markers = useRef(new Map<string, L.Marker>());
  const devices = feed.snapshot?.devices ?? [];
  const selected = devices.find((device) => device.deviceId === selectedId) ?? devices[0];
  const selectedProximity = selected?.ble && feed.snapshot ? estimateBleProximity(selected.ble, feed.snapshot.gateways) : null;
  const demo = feed.snapshot?.mode === 'demo';
  const user = variant === 'user';
  const plotSignature = JSON.stringify({ devices, gateways: feed.snapshot?.gateways, selected: selected?.deviceId, demo, showProximity, geofences,
    ages: devices.map((device) => [device.position && freshness(device.position.observedAt, feed.now), device.ble && freshness(device.ble.observedAt, feed.now)]) });

  useEffect(() => {
    if (!container.current) return;
    const instance = L.map(container.current, { scrollWheelZoom: false, zoomControl: false, zoomAnimation: false }).setView([37.5665, 126.978], 16);
    L.control.zoom({ zoomInTitle: '지도 확대', zoomOutTitle: '지도 축소', position: 'bottomright' }).addTo(instance);
    L.control.scale({ imperial: false, position: 'bottomleft' }).addTo(instance);
    map.current = instance;
    layers.current = L.featureGroup().addTo(instance);
    const observer = new ResizeObserver(() => instance.invalidateSize({ pan: false }));
    observer.observe(container.current);
    return () => { observer.disconnect(); instance.stop(); instance.remove(); map.current = null; layers.current = null; tiles.current = null; initialFit.current = false; };
  }, []);

  useEffect(() => {
    const instance = map.current;
    if (!instance) return;
    const source = mapTileStyle(tileStyle, {
      customUrl: import.meta.env['VITE_MAP_TILE_URL'] || '',
      customAttribution: import.meta.env['VITE_MAP_ATTRIBUTION'] || '',
      stadiaKey: import.meta.env['VITE_STADIA_MAPS_KEY'] || '',
    });
    setTileError(false);
    instance.setMaxZoom(source.maxZoom);
    const background = L.tileLayer(source.url, { maxZoom: source.maxZoom, attribution: source.attribution }).addTo(instance);
    background.on('tileerror', () => setTileError(true));
    tiles.current = background;
    return () => { background.remove(); };
  }, [tileStyle]);

  useEffect(() => {
    const group = layers.current;
    const instance = map.current;
    if (!group || !instance) return;
    if(!feed.snapshot){group.clearLayers();markers.current.clear();initialFit.current=false;instance.setView([37.5665,126.978],12,{animate:false});return;}
    group.clearLayers();
    markers.current.clear();
    if(!devices.length){initialFit.current=false;instance.setView([37.5665,126.978],12,{animate:false});}
    for (const fence of geofences.filter(validMapGeofence)) {
      const style = { color: '#348e70', weight: 1.5, fillColor: '#79c9aa', fillOpacity: 0.09, dashArray: '4 6' };
      const shape = fence.shape;
      const geometry = shape.type === 'circle' ? L.circle([shape.lat, shape.lon], { ...style, radius: shape.radiusM })
        : L.rectangle([[shape.minLat, shape.minLon], [shape.maxLat, shape.maxLon]], style);
      geometry.bindTooltip(textElement(`${fence.label} (${fence.simulation ? '모의 안전 구역' : '등록한 안전 구역'})`)).addTo(group);
    }
    for (const gateway of feed.snapshot.gateways) {
      L.circleMarker(coordinate(gateway.position), { radius: 5, color: '#3b5067', fillColor: '#fff', fillOpacity: 1, weight: 2 })
        .bindTooltip(textElement(`${gateway.label} (수신기)`)).addTo(group);
    }
    devices.forEach((device, index) => {
      const color = colors[index % colors.length]!;
      const proximity = device.ble ? estimateBleProximity(device.ble, feed.snapshot!.gateways) : null;
      const fix = device.position;
      const active = selected?.deviceId === device.deviceId;
      if (proximity && device.ble && showProximity) {
        const old = freshness(device.ble.observedAt, feed.now) !== 'fresh' && !demo;
        L.circle(coordinate(proximity.center), {
          radius: proximity.radiusM, color, weight: active ? 2 : 1,
          fillOpacity: old ? 0.025 : active ? 0.14 : 0.055, opacity: old ? 0.35 : active ? 0.85 : 0.45,
          dashArray: old ? '2 6' : '5 5',
        }).bindTooltip(textElement(`${device.label}: ${proximity.gatewayLabel}에서 약 ${meters(proximity.distanceM)} (${device.ble.rssiDbm} dBm, ${proximity.simulation ? '모의' : '실측'})`))
          .on('click', () => setSelectedId(device.deviceId)).addTo(group);
      }
      if (fix?.accuracyM) {
        L.circle(coordinate(fix.coordinates), { radius: fix.accuracyM, color, weight: 1, fillOpacity: 0.035, opacity: 0.25 })
          .bindTooltip(textElement(`${device.label}: 오차 범위 ${meters(fix.accuracyM)}`)).addTo(group);
      }
      const point = fix?.coordinates ?? proximity?.center;
      if (!point) return;
      const markerName = `${device.label}, ${fix ? `${fix.source.toUpperCase()} 위치` : '수신기 기준 위치'}`;
      const icon = document.createElement('div');
      icon.className = `keyring-pin${fix ? '' : ' receiver-pin'}${active ? ' selected' : ''}`;
      icon.style.setProperty('--pin-color', color);
      icon.append(textElement(String(index + 1), 'keyring-pin-number'));
      if (active) icon.append(textElement(device.label, 'keyring-pin-label'));
      const marker = L.marker(coordinate(point), {
        icon: L.divIcon({ html: icon, className: 'keyring-marker', iconSize: [40, 48], iconAnchor: fix ? [20, 46] : [20, 20] }),
        title: markerName, alt: markerName, keyboard: true, riseOnHover: true,
        opacity: freshness((fix ?? device.ble)!.observedAt, feed.now) === 'fresh' || demo ? 1 : 0.5,
        zIndexOffset: active ? 500 : 0,
      }).bindTooltip(textElement(markerName), { direction: 'top', offset: [0, -45] })
        .on('click', () => setSelectedId(device.deviceId)).addTo(group);
      markers.current.set(device.deviceId, marker);
    });
    if (!initialFit.current && devices.length && group.getBounds().isValid()) {
      instance.fitBounds(group.getBounds(), { padding: [70, 70], maxZoom: 18 });
      initialFit.current = true;
    }
  }, [plotSignature]);

  function fitAll() {
    const bounds = layers.current?.getBounds();
    if (bounds?.isValid()) map.current?.fitBounds(bounds, { padding: [70, 70], maxZoom: 18 });
  }
  function focusDevice(device: LocationDevice) {
    setSelectedId(device.deviceId);
    const marker = markers.current.get(device.deviceId);
    if (marker) map.current?.setView(marker.getLatLng(), Math.max(map.current.getZoom(), 18));
  }
  const selectedTime = selected && observationTime(selected);
  const status = selectedTime ? freshness(selectedTime, feed.now) : 'unknown';
  const signalLabel = selected?.ble ? selected.ble.rssiDbm >= -65 ? '강함' : selected.ble.rssiDbm >= -80 ? '보통' : '약함' : '신호 기다리는 중';
  const sourceLabel = demo ? '모의 데이터' : feed.snapshot ? feed.snapshot.simulation ? '모의 데이터' : '실제 위치' : '연결하는 중';
  const geofenceCount = geofences.filter(validMapGeofence).length;

  return <div className={`location-experience location-${variant}`}>
    <div className="location-toolbar">
      <div className="location-feed-state"><span className={`location-status-dot${feed.error ? ' attention' : ''}`} />
        <strong>{user ? '키링 위치' : '위치 모니터링'}</strong><span className="location-divider" /><span>키링 {devices.length}개</span>
        <Badge tone={feed.snapshot?.simulation ? 'warning' : 'info'}>{sourceLabel}</Badge>
      </div>
      <div className="location-actions">
        <button type="button" className="map-quiet-button" onClick={fitAll}><MapIcon name="locate" />모두 보기</button>
        <button type="button" className="map-icon-button" onClick={feed.reload} aria-label="위치 새로고침" title="위치 새로고침"><MapIcon name="refresh" /></button>
      </div>
    </div>
    {feed.error && <p className="location-alert" role="alert">{feed.error}</p>}
    {tileError && <div className="location-alert" role="status"><span>배경 지도를 불러오지 못했어요. 키링 위치와 범위는 그대로 보여 드려요.</span>
      <button type="button" onClick={() => { setTileError(false); if (tileStyle === 'light') setTileStyle('street'); else tiles.current?.redraw(); }}>{tileStyle === 'light' ? '일반 지도로 바꾸기' : '지도 다시 불러오기'}</button></div>}
    <div className="location-layout">
      <div className="map-frame">
        <div ref={container} className="location-canvas" role="region" aria-label="키링 위치 지도" />
        <div className="map-style-switch" role="group" aria-label="지도 배경 선택"><MapIcon name="layers" />
          <button type="button" aria-pressed={tileStyle === 'light'} onClick={() => setTileStyle('light')}>{import.meta.env['VITE_MAP_TILE_URL'] ? '기본 지도' : '밝은 지도'}</button>
          <button type="button" aria-pressed={tileStyle === 'street'} onClick={() => setTileStyle('street')}>일반 지도</button>
        </div>
        <div className="map-corner-note"><span className="map-live-dot" />4초마다 새로고침</div>
        <div className="map-legend"><span><i className="legend-pin" />GPS/통신망 위치</span><span><i className="legend-ring" />블루투스 추정 범위</span><span><i className="legend-anchor" />수신기</span>
          {geofenceCount > 0 && <span><i className="legend-zone" />안전 구역 {geofenceCount}개</span>}
          <button type="button" className="map-layer-toggle" aria-pressed={showProximity} onClick={() => setShowProximity((value) => !value)}>{showProximity ? '추정 범위 숨기기' : '추정 범위 보기'}</button>
        </div>
      </div>
      <aside className="location-side" aria-label="키링 위치 정보">
        <div className="keyring-list-header"><h3>{user ? '연결된 키링' : '키링 목록'}</h3><span>{devices.length}</span></div>
        <div className="keyring-list" aria-label="지도 키링 선택">
          {devices.map((device, index) => <button key={device.deviceId} type="button" className="keyring-option" aria-pressed={selected?.deviceId === device.deviceId}
            onClick={() => focusDevice(device)}>
            <span className="keyring-number" style={{ color: colors[index % colors.length], backgroundColor: `${colors[index % colors.length]}14` }}>{index + 1}</span>
            <span className="keyring-option-copy"><strong>{device.label}</strong><span>{sourceName(device, user)}</span></span>
            <span className="keyring-option-signal">{device.ble ? <><MapIcon name="signal" /><span>{device.ble.rssiDbm}</span></> : <MapIcon name="pin" />}</span>
          </button>)}
          {devices.length === 0 && <p className="location-empty" role="status">{feed.loading ? '키링 위치를 불러오고 있어요…' : '키링 위치가 들어오면 여기에 표시돼요.'}</p>}
        </div>
        {selected && <article className="location-detail" aria-label="선택한 키링 위치 상세">
          <div className="location-detail-heading"><div><span className="map-overline">선택한 키링</span><h3>{selected.label}</h3></div>
            <Badge tone={demo ? 'warning' : status === 'fresh' ? 'success' : 'warning'}>{demo ? '모의 데이터' : status === 'fresh' ? '방금 받음' : status === 'future' ? '시각 확인 필요' : '업데이트 기다리는 중'}</Badge></div>
          <div className="location-measurements"><div><span>블루투스 신호</span><strong>{selected.ble?.rssiDbm ?? '-'}<small>dBm</small></strong><span>{signalLabel}{selected.ble ? (selected.ble.simulation ? ' (모의)' : ' (실측)') : ''}</span></div>
            <div><span>수신기와의 거리</span><strong>{selectedProximity ? meters(selectedProximity.distanceM) : '-'}</strong><span>{selectedProximity?.simulation ? '모의 수신기 기준 추정' : selectedProximity ? '수신기 기준 추정' : '수신기 등록 전'}</span></div></div>
          <dl className="location-facts"><dt>확인 방법</dt><dd>{sourceName(selected, user)}{selected.position && (selected.position.simulation ? ' (모의)' : ' (실측)')}</dd>
            <dt>수신기</dt><dd>{selectedProximity?.gatewayLabel ?? selected.ble?.gatewayId ?? '확인 전'}</dd>
            {selectedTime && <><dt>마지막 확인</dt><dd><Timestamp at={selectedTime} variant={user ? 'friendly' : 'precise'} /></dd></>}
            {selected.position && !demo && freshness(selected.position.observedAt, feed.now) !== 'fresh' && <><dt>위치 상태</dt><dd>마지막으로 받은 좌표예요. 받은 시각을 확인해 주세요.</dd></>}
          </dl>
          <details className="location-coordinate-details" open={!user}><summary>{user ? '좌표 자세히 보기' : '좌표와 수신 정보'}</summary>
            <dl className="location-facts">
              {selected.position && <><dt>위도, 경도</dt><dd className="map-coordinate">{selected.position.coordinates.lat.toFixed(6)}, {selected.position.coordinates.lon.toFixed(6)}</dd>
                {selected.position.accuracyM !== null && <><dt>오차 범위</dt><dd>{meters(selected.position.accuracyM)}</dd></>}
                <dt>위치 받은 시각</dt><dd><Timestamp at={selected.position.observedAt} /></dd></>}
              {selected.ble && <><dt>신호 받은 시각</dt><dd><Timestamp at={selected.ble.observedAt} /></dd></>}
              {selectedProximity && <><dt>수신기 좌표</dt><dd className="map-coordinate">{selectedProximity.center.lat.toFixed(6)}, {selectedProximity.center.lon.toFixed(6)}</dd>
                {selectedProximity.capped && <><dt>지도 표시 반경</dt><dd>최대 {meters(selectedProximity.radiusM)}까지 표시</dd></>}
              </>}
            </dl>
          </details>
        </article>}
      </aside>
    </div>
    <p className="location-caption">핀은 GPS나 위치 서비스로 받은 좌표예요. 점선 원은 수신기를 중심으로 키링이 있을 만한 범위이고 옅은 실선 원은 위치 오차 범위예요.</p>
    {!user && <details className="location-method"><summary>위치를 받는 방법과 거리 계산</summary>
      <div><p>위치와 블루투스 신호는 4초마다 새로 받고, 각 기록이 만들어진 시각을 그대로 보여 줘요. 모의 데이터는 배지로 구분해요.</p>
        <p>추정 거리 = 10 ^ ((1 m 기준 RSSI - 현재 RSSI) / (10 x 경로 손실 계수)). 수신기 교정값과 주변 환경이 반경에 반영돼요.</p>
        {selectedProximity && <p>선택한 수신기: 1 m 기준 {selectedProximity.referenceRssiDbm} dBm, 경로 손실 계수 {selectedProximity.pathLossExponent}</p>}
        {!!feed.snapshot?.rejectedRecords && <p>형식이 맞지 않는 기록 {feed.snapshot.rejectedRecords}건은 빼고 올바른 기록만 표시해요.</p>}
        <p>밝은 지도는 Stadia Maps Alidade Smooth, 일반 지도는 OpenStreetMap을 써요. 키링 식별자와 신호 값은 로컬 서버에서 처리해요.</p>
      </div>
    </details>}
  </div>;
}
