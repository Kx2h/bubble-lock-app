import { lazy, Suspense, useState } from 'react';
import { Icon } from './Icon.tsx';
import { Metric, PortalShell } from './PortalShell.tsx';
import { Badge, Timestamp } from './ui.tsx';
import { usePortal } from './portal-client.ts';
import { useAuth } from './auth-client.tsx';
import type { PortalController } from './portal-client.ts';
import { label, portalStatusLabels, scopeList } from './labels.ts';
const LocationMap = lazy(() => import('./LocationMap.tsx').then((module) => ({ default: module.LocationMap })));
const statusName = (status: string) => label(portalStatusLabels, status);

function Empty({ children }: { children: React.ReactNode }) { return <div className="empty-state"><Icon name="bubble" size={30} /><p>{children}</p></div>; }
function StepTitle({ step, children }: { step: number; children: React.ReactNode }) { return <h2><span className="step-number" aria-hidden="true">{step}</span>{children}</h2>; }
function HouseholdSelect({ portal, value, onChange }: { portal: PortalController; value: string; onChange: (value: string) => void }) {
  return <label>가족 공간<select value={value} onChange={(event) => onChange(event.target.value)} required><option value="">가족 공간을 선택하세요</option>{portal.snapshot?.households.map((house) => <option key={house.householdId} value={house.householdId}>{house.name}</option>)}</select></label>;
}

function Devices({ portal }: { portal: PortalController }) {
  const [householdId, setHouseholdId] = useState('');
  return <div className="stack">
    <div className="content-card"><div className="card-heading"><div><h2>우리 가족과 키링</h2><p>가족 공간마다 연결된 키링을 확인하고 관리해요.</p></div><Icon name="users" /></div>
      {portal.snapshot?.households.map((house) => <div className="household-block" key={house.householdId}><div className="row"><h3>{house.name}</h3><Badge>{house.members.length}명</Badge></div>
        <div className="device-card-grid">{portal.snapshot?.devices.filter((device) => device.householdId === house.householdId).map((device) => <article className="mini-device" key={device.deviceId}><span className="device-glyph"><Icon name="key" size={22} /></span><div><h4>{device.label}</h4><p>{device.deviceId}, 수신기 {device.gatewayId}</p></div><a href="#/user/map" aria-label={`${device.label} 위치 보기`}><Icon name="arrow" /></a></article>)}</div>
        {portal.snapshot?.devices.every((device) => device.householdId !== house.householdId) && <Empty>키링을 연결하면 여기에 표시돼요.</Empty>}
      </div>)}
      {portal.snapshot?.households.length === 0 && <Empty>첫 가족 공간을 만들어 보세요.</Empty>}
    </div>
    {portal.snapshot?.principal.role === 'guardian' && <div className="two-columns"><form className="content-card stack" onSubmit={async (event) => { event.preventDefault(); const form = event.currentTarget; const values = new FormData(form); if (await portal.action('households', { name: values.get('name') }, '가족 공간을 만들었어요.')) form.reset(); }}><div className="card-heading"><h2>가족 공간 만들기</h2><Icon name="plus" /></div><label>공간 이름<input name="name" required maxLength={80} placeholder="예: 우리 가족" /></label><button className="primary" disabled={portal.pending}>공간 만들기</button></form>
      <form className="content-card stack" onSubmit={async (event) => { event.preventDefault(); const form = event.currentTarget; const values = new FormData(form); if (await portal.action('devices', { householdId, deviceId: values.get('deviceId'), gatewayId: values.get('gatewayId'), label: values.get('label') }, '키링을 연결했어요.')) form.reset(); }}><div className="card-heading"><h2>키링 연결하기</h2><Icon name="key" /></div><HouseholdSelect portal={portal} value={householdId} onChange={setHouseholdId} /><div className="field-grid"><label>키링 이름<input name="label" required maxLength={80} placeholder="예: 가방 키링" /></label><label>키링 ID<input name="deviceId" required placeholder="예: keyring-A" /></label></div><label>수신기 ID<input name="gatewayId" required defaultValue="pi-01" /><span className="field-hint">집에 둔 수신기(Gateway)의 ID예요.</span></label><button className="primary" disabled={portal.pending || !householdId}>키링 연결하기</button></form></div>}
  </div>;
}

function Privacy({ portal }: { portal: PortalController }) {
  const [householdId, setHouseholdId] = useState('');
  const [consentId, setConsentId] = useState('');
  const guardian = portal.snapshot?.principal.role === 'guardian';
  const consents = portal.snapshot?.consents ?? [];
  return <div className="stack">
    <div className="privacy-banner"><Icon name="shield" size={36} /><div><h2>무엇을, 얼마 동안 공유할지 내가 정해요.</h2><p>공유 목적과 기간을 정하고 필요한 만큼만 허락하세요.</p></div></div>
    {guardian && <div className="two-columns"><form className="content-card stack" onSubmit={async (event) => { event.preventDefault(); const values = new FormData(event.currentTarget); await portal.action('consents', { householdId, purpose: values.get('purpose'), scopes: values.getAll('scopes'), ttlSec: Number(values.get('ttlSec')) }, '공유 동의를 저장했어요.'); }}><div className="card-heading"><StepTitle step={1}>공유 동의 만들기</StepTitle><Icon name="file" /></div><HouseholdSelect portal={portal} value={householdId} onChange={setHouseholdId} /><label>사용 목적<input name="purpose" required maxLength={180} placeholder="예: 가족 외출 중 위치 확인" /></label><fieldset><legend>허락할 항목</legend><label className="check-label"><input type="checkbox" name="scopes" value="location:read" defaultChecked />위치 보기</label><label className="check-label"><input type="checkbox" name="scopes" value="bubble:command" defaultChecked />키링 켜고 끄기</label></fieldset><label>동의 기간<select name="ttlSec" defaultValue="3600"><option value="900">15분</option><option value="3600">1시간</option><option value="86400">24시간</option></select></label><button className="primary" disabled={portal.pending || !householdId}>동의 저장하기</button></form>
      <form className="content-card stack" onSubmit={async (event) => { event.preventDefault(); const values = new FormData(event.currentTarget); const consent = consents.find((item) => item.consentId === consentId); if (consent) await portal.action('grants', { consentId, purpose: consent.purpose, scopes: consent.scopes, ttlSec: Number(values.get('ttlSec')) }, '사용 권한을 켰어요.'); }}><div className="card-heading"><StepTitle step={2}>사용 권한 켜기</StepTitle><Icon name="check" /></div><p className="card-note">저장한 동의 안에서 실제로 쓸 권한과 기간을 정해요.</p><label>공유 동의<select value={consentId} onChange={(event) => setConsentId(event.target.value)} required><option value="">동의를 선택하세요</option>{consents.filter((item) => item.effectiveStatus === 'ACTIVE').map((item) => <option key={item.consentId} value={item.consentId}>{item.purpose}</option>)}</select></label><label>권한 사용 기간<select name="ttlSec" defaultValue="900"><option value="300">5분</option><option value="900">15분</option><option value="3600">1시간</option></select></label><button className="primary" disabled={portal.pending || !consentId}>권한 켜기</button></form></div>}
    <div className="content-card stack"><div className="card-heading"><h2>내 공유 동의와 권한</h2><Badge>동의 {consents.length}개</Badge></div>
      {consents.map((consent) => <article className="permission-item" key={consent.consentId}><div className="row"><h3>{consent.purpose}</h3><Badge tone={consent.effectiveStatus === 'ACTIVE' ? 'success' : ''}>{statusName(consent.effectiveStatus)}</Badge></div><p className="small muted">{scopeList(consent.scopes)}</p><div className="row"><p className="small"><Timestamp at={consent.expiresAt} variant="friendly" />까지</p>{guardian && consent.effectiveStatus === 'ACTIVE' && <button disabled={portal.pending} onClick={() => void portal.action('consents/revoke', { consentId: consent.consentId }, '동의와 연결된 권한을 함께 취소했어요.')}>동의 취소</button>}</div>
        {portal.snapshot?.grants.filter((grant) => grant.consentId === consent.consentId).map((grant) => <div className="grant-row" key={grant.grantId}><div><strong className="small">사용 권한: {statusName(grant.effectiveStatus)}</strong><p className="caption muted">{scopeList(grant.scopes)}, <Timestamp at={grant.expiresAt} variant="friendly" />까지</p></div>{guardian && grant.effectiveStatus === 'ACTIVE' && <button disabled={portal.pending} onClick={() => void portal.action('grants/revoke', { grantId: grant.grantId }, '사용 권한을 껐어요.')}>권한 끄기</button>}</div>)}
      </article>)}{consents.length === 0 && <Empty>저장한 공유 동의가 여기에 표시돼요.</Empty>}
    </div>
  </div>;
}

function CommandActions({ portal }: { portal: PortalController }) {
  const [householdId, setHouseholdId] = useState('');
  const [grantId, setGrantId] = useState('');
  const snapshot = portal.snapshot;
  const deviceLabel = (id: string) => snapshot?.devices.find((item) => item.deviceId === id)?.label ?? id;
  return <div className="two-columns"><form className="content-card stack" onSubmit={async (event) => { event.preventDefault(); const values = new FormData(event.currentTarget); await portal.action('commands/request', { householdId, deviceIds: values.getAll('deviceIds'), desiredState: { state: values.get('state') === 'on' }, reason: values.get('reason'), ttlSec: 300 }, '요청을 보냈어요. 보호자가 승인하면 키링에 적용돼요.'); }}><div className="card-heading"><h2>보호 모드 요청</h2><Icon name="bolt" /></div><HouseholdSelect portal={portal} value={householdId} onChange={setHouseholdId} /><fieldset><legend>키링 선택</legend>{snapshot?.devices.filter((item) => item.householdId === householdId).map((device) => <label className="check-label" key={device.deviceId}><input name="deviceIds" type="checkbox" value={device.deviceId} />{device.label}</label>)}{!householdId && <p className="caption muted">가족 공간을 고르면 키링 목록이 나와요.</p>}</fieldset><label>동작<select name="state"><option value="on">프라이버시 보호 켜기</option><option value="off">프라이버시 보호 끄기</option></select></label><label>요청 이유<input name="reason" required maxLength={160} placeholder="예: 가족 외출 시작" /></label><button className="primary" disabled={portal.pending || !householdId}>승인 요청 보내기</button></form>
    <div className="content-card stack"><div className="card-heading"><h2>요청과 승인 상태</h2><Icon name="activity" /></div>{snapshot?.principal.role === 'guardian' && <label>승인에 쓸 사용 권한<select value={grantId} onChange={(event) => setGrantId(event.target.value)}><option value="">사용 권한을 선택하세요</option>{snapshot.grants.filter((grant) => grant.effectiveStatus === 'ACTIVE' && grant.scopes.includes('bubble:command')).map((grant) => <option key={grant.grantId} value={grant.grantId}>{snapshot.consents.find((consent) => consent.consentId === grant.consentId)?.purpose ?? grant.subjectId}</option>)}</select></label>}
      {snapshot?.commandRequests.map((request) => <article className="request-row" key={request.requestId}><div className="row"><strong>{request.desiredState.state ? '보호 켜기' : '보호 끄기'}</strong><Badge tone={request.status === 'QUEUED' ? 'info' : 'warning'}>{statusName(request.effectiveStatus)}</Badge></div><p className="small muted">{request.deviceIds.map(deviceLabel).join(', ')}, {request.reason}</p>{['PENDING', 'PARTIAL'].includes(request.effectiveStatus) && snapshot.principal.role === 'guardian' && <button className="primary" disabled={portal.pending || !grantId} onClick={() => void portal.action('commands/approve', { requestId: request.requestId, grantId }, '승인했어요. 차례가 되면 키링에 보내요.')}>승인하기</button>}</article>)}
      {snapshot?.commandRequests.length === 0 && <Empty>보낸 요청의 진행 상황이 여기에 표시돼요.</Empty>}<p className="caption muted">키링이 응답하고 실제 상태가 다시 확인되면 적용된 것으로 표시해요.</p>
    </div>
  </div>;
}

function LocationSharing({ portal }: { portal: PortalController }) {
  const [grantId, setGrantId] = useState('');
  const [incidentId, setIncidentId] = useState('');
  const snapshot = portal.snapshot;
  const guardian = snapshot?.principal.role === 'guardian';
  const grants = snapshot?.grants.filter((grant) => grant.effectiveStatus === 'ACTIVE' && grant.scopes.includes('location:read')) ?? [];
  const grant = grants.find((item) => item.grantId === grantId);
  const household = snapshot?.households.find((item) => item.householdId === grant?.subjectId);
  const purpose = snapshot?.consents.find((item) => item.consentId === grant?.consentId)?.purpose ?? '';
  const incident = snapshot?.incidents.find((item) => item.incidentId === incidentId);
  const recipients = household ? [...new Set([household.ownerId, ...household.members.map((item) => item.principalId)])] : [];
  const leases = snapshot?.locationLeases ?? [];
  return <div className="two-columns">{guardian && <form className="content-card stack" onSubmit={async (event) => {
    event.preventDefault(); const values = new FormData(event.currentTarget);
    await portal.action('location-leases', { grantId, recipientId: values.get('recipientId'), deviceIds: values.getAll('deviceIds'), purpose, incidentId: values.get('incidentId'), ttlSec: Number(values.get('ttlSec')) }, '위치 공유를 시작했어요.');
  }}><div className="card-heading"><div><h2>위치 공유하기</h2><p>도움 요청에 맞춰 누구에게, 얼마 동안 보여 줄지 정하세요.</p></div><Icon name="shield" /></div>
    <label>위치 보기 권한<select value={grantId} onChange={(event) => { setGrantId(event.target.value); setIncidentId(''); }} required><option value="">권한을 선택하세요</option>{grants.map((item) => <option key={item.grantId} value={item.grantId}>{snapshot?.consents.find((consent) => consent.consentId === item.consentId)?.purpose ?? item.subjectId}</option>)}</select></label>
    {grants.length === 0 && <p className="small muted">먼저 <a href="#/user/privacy">공유 권한</a> 화면에서 위치 보기 권한을 켜 주세요.</p>}
    <div key={grantId} className="stack"><label>관련 도움 요청<select name="incidentId" value={incidentId} onChange={(event) => setIncidentId(event.target.value)} required><option value="">도움 요청을 선택하세요</option>{snapshot?.incidents.filter((item) => item.householdId === household?.householdId && item.status === 'OPEN').map((item) => <option key={item.incidentId} value={item.incidentId}>{item.message}</option>)}</select></label>
      <label>받는 사람<select name="recipientId" required><option value="">받는 사람을 선택하세요</option>{recipients.map((id) => <option key={id} value={id}>{id === snapshot?.principal.principalId ? '나' : id}</option>)}</select></label>
      <fieldset key={incidentId}><legend>공유할 키링</legend>{snapshot?.devices.filter((item) => item.householdId === household?.householdId && (!incident?.deviceId || item.deviceId === incident.deviceId)).map((item) => <label className="check-label" key={item.deviceId}><input type="checkbox" name="deviceIds" value={item.deviceId} />{item.label}</label>)}{!grant && <p className="caption muted">권한을 고르면 키링 목록이 나와요.</p>}</fieldset>
      <label>공유 시간<select name="ttlSec" defaultValue="180"><option value="60">1분</option><option value="180">3분</option><option value="300">5분</option></select></label></div>
    <button className="primary" disabled={portal.pending || !grant}>위치 공유 시작</button><a className="small" href="#/user/activity">도움 요청 만들기</a>
  </form>}
    <div className="content-card stack"><div className="card-heading"><h2>위치 공유 기록</h2><Icon name="activity" /></div>{leases.map((lease) => {
      const status = lease.effectiveStatus ?? (lease.status === 'ACTIVE' && Date.parse(lease.expiresAt) <= Date.now() ? 'EXPIRED' : lease.status);
      return <article className="permission-item" key={lease.leaseId}><div className="row"><strong>{lease.purpose}</strong><Badge tone={status === 'ACTIVE' ? 'success' : ''}>{statusName(status)}</Badge></div><p className="small muted">{lease.recipientId === snapshot?.principal.principalId ? '나에게 공유' : `${lease.recipientId}에게 공유`}, {lease.deviceIds.map((id) => snapshot?.devices.find((item) => item.deviceId === id)?.label ?? id).join(', ')}</p><div className="row"><p className="small"><Timestamp at={lease.expiresAt} variant="friendly" />까지</p>{status === 'ACTIVE' && <button disabled={portal.pending} onClick={() => void portal.action('location-leases/revoke', { leaseId: lease.leaseId }, '위치 공유를 멈췄어요.')}>공유 멈추기</button>}</div></article>;
    })}{leases.length === 0 && <Empty>위치를 공유하면 여기에 기록돼요.</Empty>}<p className="caption muted">정한 시간이 지나거나, 도움 요청이 처리되거나, 동의를 취소하면 공유가 끝나요.</p></div>
  </div>;
}

function Activity({ portal }: { portal: PortalController }) {
  const [householdId, setHouseholdId] = useState('');
  return <div className="stack"><CommandActions portal={portal} /><div className="two-columns"><form className="content-card stack" onSubmit={async (event) => { event.preventDefault(); const values = new FormData(event.currentTarget); await portal.action('incidents', { householdId, kind: values.get('kind'), message: values.get('message') }, '가족에게 도움 요청을 보냈어요.'); }}><div className="card-heading"><h2>도움 요청</h2><Icon name="bell" /></div><HouseholdSelect portal={portal} value={householdId} onChange={setHouseholdId} /><label>요청 종류<select name="kind"><option value="HELP">도움이 필요해요</option><option value="LOST">키링을 찾고 있어요</option><option value="SAFETY">안전을 확인해 주세요</option></select></label><label>내용<textarea name="message" required rows={3} maxLength={500} placeholder="가족에게 전할 내용을 적어 주세요." /></label><button className="primary" disabled={portal.pending || !householdId}>요청 보내기</button></form>
    <div className="content-card stack"><div className="card-heading"><h2>가족 요청 기록</h2><Icon name="users" /></div>{portal.snapshot?.incidents.map((incident) => <article className="request-row" key={incident.incidentId}><div className="row"><strong>{incident.message}</strong><Badge tone={incident.status === 'OPEN' ? 'warning' : 'success'}>{statusName(incident.status)}</Badge></div><p className="small muted"><Timestamp at={incident.createdAt} variant="friendly" /></p>{incident.status === 'OPEN' && portal.snapshot?.principal.role === 'guardian' && <button disabled={portal.pending} onClick={() => void portal.action('incidents/resolve', { incidentId: incident.incidentId }, '처리 완료로 표시했어요.')}>처리 완료</button>}</article>)}{portal.snapshot?.incidents.length === 0 && <Empty>가족의 요청이 여기에 모여요.</Empty>}</div></div>
    <div className="content-card stack"><div className="card-heading"><h2>알림</h2><Icon name="bell" /></div>{portal.snapshot?.notifications.map((item) => <article className="notification-row" key={item.notificationId}><span className={`notification-dot${item.readAt ? ' read' : ''}`} aria-hidden="true" /><div><strong>{item.title}</strong><p className="small muted"><Timestamp at={item.createdAt} variant="friendly" />{item.readAt ? ', 읽음' : ', 새 알림'}</p></div>{!item.readAt && <button disabled={portal.pending} onClick={() => void portal.action('notifications/read', { notificationId: item.notificationId }, '읽음으로 표시했어요.')}>읽음</button>}</article>)}{portal.snapshot?.notifications.length === 0 && <Empty>새 소식이 오면 알려 드릴게요.</Empty>}</div>
  </div>;
}

export function UserDashboard({ page }: { page: string }) {
  const [role, setRole] = useState<'guardian' | 'visitor'>('guardian');
  const portal = usePortal(role);
  const auth = useAuth();
  const snapshot = portal.snapshot;
  const demo = portal.data?.mode !== 'live';
  return <PortalShell role="user" page={page} actions={<div className="cluster">{auth.session?.mode === 'demo' && <label className="compact-label">체험할 역할<select value={role} onChange={(event) => setRole(event.target.value as typeof role)}><option value="guardian">보호자</option><option value="visitor">방문자</option></select></label>}<button className="icon-button" onClick={portal.reload} aria-label="새로고침" title="새로고침"><Icon name="refresh" /></button></div>}>
    <div className="context-strip"><span className={`environment-tag${demo ? ' demo' : ''}`}><i />{demo ? '체험 모드' : '연결된 계정'}</span><span>모든 요청은 가족이 허락한 범위 안에서만 처리돼요.</span>{demo && <Badge tone="warning">체험 데이터</Badge>}</div>
    {portal.error && <div className="notice error" role="alert">{portal.error}</div>}{portal.notice && <div className="notice success-notice" role="status"><Icon name="check" />{portal.notice}</div>}
    {page === 'overview' && <>
      <div className="user-greeting"><div><p className="eyebrow">내 프라이버시</p><h2>나와 가족의 연결을 한눈에</h2><p>키링 위치, 공유 권한, 알림을 필요할 때 바로 확인하세요.</p><a className="button primary" href="#/user/map">키링 위치 보기<Icon name="arrow" size={18} /></a></div><div className="greeting-bubble" aria-hidden="true"><Icon name="shield" size={64} /></div></div>
      <div className="metrics-grid"><Metric label="연결된 키링" value={snapshot?.devices.length ?? '-'} note="가족 공간에 연결된 키링" icon="key" /><Metric label="켜져 있는 권한" value={snapshot?.grants.filter((grant) => grant.effectiveStatus === 'ACTIVE').length ?? '-'} note="지금 사용할 수 있는 권한" icon="shield" /><Metric label="읽지 않은 알림" value={snapshot?.notifications.filter((item) => !item.readAt).length ?? '-'} note="새로 도착한 소식" icon="bell" /></div>
      <div className="content-card"><div className="card-heading"><div><h2>바로가기</h2><p>자주 쓰는 기능이에요.</p></div></div><div className="quick-actions"><a href="#/user/devices"><Icon name="users" /><strong>가족과 키링 연결하기</strong><Icon name="arrow" /></a><a href="#/user/privacy"><Icon name="shield" /><strong>공유 권한 관리하기</strong><Icon name="arrow" /></a><a href="#/user/activity"><Icon name="bolt" /><strong>요청 보내고 승인하기</strong><Icon name="arrow" /></a></div></div>
      <div className="content-card"><div className="card-heading"><h2>내 키링</h2><a href="#/user/devices">전체 보기</a></div><div className="device-card-grid">{snapshot?.devices.map((device) => <a className="mini-device" href="#/user/map" key={device.deviceId}><span className="device-glyph"><Icon name="key" size={22} /></span><div><h3>{device.label}</h3><p>{snapshot.households.find((house) => house.householdId === device.householdId)?.name}</p></div><Icon name="arrow" /></a>)}</div>{snapshot?.devices.length === 0 && <Empty>가족 공간에 첫 키링을 연결해 보세요.</Empty>}</div></>}
    {page === 'map' && <div className="stack"><div className="content-card map-content"><Suspense fallback={<p role="status">지도를 불러오고 있어요…</p>}><LocationMap refresh={portal.revision} variant="user" locationUrl={`/api/portal/locations?role=${role}`} /></Suspense></div><LocationSharing portal={portal} /></div>}
    {page === 'devices' && <Devices portal={portal} />}{page === 'privacy' && <Privacy portal={portal} />}{page === 'activity' && <Activity portal={portal} />}
  </PortalShell>;
}
