import { useEffect, useState } from 'react';
import type { ResourceFixture } from '../shared/index.ts';
import {
  defaultMobiusSettings, discover, errorMessage, fixtureResources, retrieve,
} from './client.ts';
import type { Discovery, LiveResource, MobiusSettings } from './client.ts';
import { Badge, BooleanState } from './ui.tsx';

interface TreeNode {
  readonly uri: string;
  readonly name: string;
  readonly parent: string | null;
  readonly inferred: boolean;
}

function liveTree(uris: readonly string[]): readonly TreeNode[] {
  const nodes = new Map<string, TreeNode>();
  for (const uri of uris) {
    const parts = uri.split('/').filter(Boolean);
    parts.forEach((name, index) => {
      const path = `/${parts.slice(0, index + 1).join('/')}`;
      nodes.set(path, {
        uri: path, name,
        parent: index === 0 ? null : `/${parts.slice(0, index).join('/')}`,
        inferred: !uris.includes(path),
      });
    });
  }
  return [...nodes.values()];
}

function Tree({ nodes, parent = null, selected, onSelect }: {
  readonly nodes: readonly TreeNode[];
  readonly parent?: string | null;
  readonly selected: string;
  readonly onSelect: (uri: string) => void;
}) {
  return <ul className={parent === null ? 'tree' : undefined}>
    {nodes.filter((node) => node.parent === parent).map((node) => <li key={node.uri}>
      <button type="button" aria-pressed={selected === node.uri} onClick={() => onSelect(node.uri)}>
        <span>{node.name}{node.inferred && <span className="caption muted"> (상위 경로)</span>}</span>
        <span className="caption selection-label">{selected === node.uri ? '선택됨' : ' '}</span>
        <span className="path">{node.uri}</span>
      </button>
      {nodes.some((child) => child.parent === node.uri) &&
        <Tree nodes={nodes} parent={node.uri} selected={selected} onSelect={onSelect} />}
    </li>)}
  </ul>;
}

const settingFields = [
  ['baseUrl', 'Mobius 서버 주소', 'http://127.0.0.1:7579'],
  ['originator', '운영자 식별자 (X-M2M-Origin)', '등록된 AE originator'],
  ['aePath', '검색 시작 경로 (운영자 AE)', '/Mobius/operator-ae'],
  ['acp', '접근 정책 참조 (ACP, 표시용)', '/Mobius/operator-acp'],
  ['rvi', '규격 버전 (X-M2M-RVI)', '3'],
  ['ty', '자원 유형 (ty)', '28'],
  ['cnd', '장치 기능 정의 (cnd)', 'org.onem2m.common.moduleclass.binarySwitch'],
  ['limit', '한 번에 가져올 개수 (lim)', '100'],
  ['offset', '건너뛸 개수 (ofst)', '0'],
] as const;

export function Resources({ refresh }: { readonly refresh: number }) {
  const [mode, setMode] = useState<'fixture' | 'live'>('fixture');
  const [draft, setDraft] = useState<MobiusSettings>(defaultMobiusSettings);
  const [settings, setSettings] = useState<MobiusSettings | null>(null);
  const [request, setRequest] = useState(0);
  const [fixtures, setFixtures] = useState<readonly ResourceFixture[]>([]);
  const [discovery, setDiscovery] = useState<Discovery | null>(null);
  const [selected, setSelected] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<LiveResource | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setFixtures([]); setDiscovery(null); setSelected(''); setError(null); setDetail(null);
    if (mode === 'live' && settings === null) {
      setStatus('idle');
      return () => controller.abort();
    }
    setStatus('loading');
    async function load() {
      try {
        if (mode === 'fixture') {
          const resources = await fixtureResources(controller.signal);
          if (!active) return;
          setFixtures(resources);
          setSelected(resources.find((resource) => resource.state !== undefined)?.resourceUri ?? resources[0]?.resourceUri ?? '');
        } else if (settings !== null) {
          const result = await discover(settings, controller.signal);
          if (!active) return;
          setDiscovery(result);
          setSelected(result.uris[0] ?? '');
        }
        if (active) setStatus('ready');
      } catch (cause) {
        if (active) { setError(errorMessage(cause)); setStatus('error'); }
      }
    }
    void load();
    return () => { active = false; controller.abort(); };
  }, [mode, settings, request, refresh]);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setDetail(null); setDetailError(null); setDetailLoading(false);
    if (mode !== 'live' || settings === null || !selected) return () => controller.abort();
    setDetailLoading(true);
    void retrieve(settings, selected, controller.signal).then((resource) => {
      if (active) setDetail(resource);
    }).catch((cause: unknown) => {
      if (active) setDetailError(errorMessage(cause));
    }).finally(() => { if (active) setDetailLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [mode, settings, selected, request, refresh]);

  const nodes: readonly TreeNode[] = mode === 'fixture'
    ? fixtures.map((resource) => ({ uri: resource.resourceUri, name: resource.name, parent: resource.parentUri, inferred: false }))
    : liveTree(discovery?.uris ?? []);
  const fixture = fixtures.find((resource) => resource.resourceUri === selected);

  return <div className="stack">
    <div className="row">
      <label>데이터 출처
        <select value={mode} onChange={(event) => setMode(event.target.value === 'live' ? 'live' : 'fixture')}>
          <option value="fixture">모의 데이터</option><option value="live">실제 서버 (Mobius)</option>
        </select>
      </label>
      <Badge tone={mode === 'fixture' ? 'warning' : 'info'}>{mode === 'fixture' ? '모의 데이터' : '실제 서버 조회 모드'}</Badge>
    </div>
    {mode === 'live' && <form className="panel stack" onSubmit={(event) => {
      event.preventDefault(); setSettings({ ...draft }); setRequest((value) => value + 1);
    }}>
      <h3>서버 연결 설정</h3>
      <div className="field-grid">
        {settingFields.map(([key, title, placeholder]) => <label key={key} className={key === 'cnd' ? 'field-wide' : undefined}>
          {title}
          <input value={draft[key]} placeholder={placeholder} autoComplete="off"
            inputMode={key === 'baseUrl' ? 'url' : ['ty', 'limit', 'offset'].includes(key) ? 'numeric' : 'text'}
            required={key !== 'acp' && key !== 'cnd'}
            onChange={(event) => setDraft({ ...draft, [key]: event.target.value })} />
        </label>)}
      </div>
      <p className="small muted">설정은 이 탭을 닫으면 사라져요. 주소와 경로만 입력하세요. 비밀키와 토큰은 필요 없어요.
        이 화면은 자원을 읽기만 해요. 권한 부여, AE 등록, 인증은 서버 설정에서 처리해요.</p>
      <div className="cluster"><button type="submit" className="primary">자원 찾기</button><code className="caption muted">GET, fu=1, drt=1</code></div>
    </form>}
    {mode === 'live' && <p className="notice">
      학교 PC 서버의 주소, 운영자 AE와 ACP, CORS, RVI, SDT 등록은 아직 실제로 확인하기 전이에요.{' '}
      조회에 실패하면 오류를 그대로 보여 드려요. 모의 데이터로 바꿔 채우지 않아요. 결과에는 접근 권한과 페이지 범위 안에 있는 자원만 담겨요.
    </p>}
    <div className="resource-results stack" aria-busy={status === 'loading'}>
    {status === 'loading' && <div className="panel loading-placeholder"><p role="status">자원을 불러오고 있어요…</p>{[1, 2, 3, 4].map((row) => <div className="placeholder-row" aria-hidden="true" key={row} />)}</div>}
    {status === 'idle' && <p className="notice">서버 연결 설정을 입력하고 자원 찾기를 눌러 주세요.</p>}
    {error !== null && <p className="notice error" role="alert">{error} 설정을 확인하고 새로고침해 주세요.</p>}
    {status === 'ready' && nodes.length === 0 && <p className="notice">찾은 자원이 없어요. 검색 시작 경로, 필터, 접근 권한 범위를 확인해 주세요.</p>}
    {discovery !== null && <p className="small muted">
      이번에 가져온 자원 {discovery.uris.length}개, {discovery.partial ? `더 있음 (다음 시작 위치 ${discovery.nextOffset ?? '서버 미제공'})` : '전체 여부는 서버가 알려 주지 않았어요'}
      {', '}응답 코드 {discovery.rsc ?? '확인 불가 (CORS 헤더 노출 설정 필요)'}
    </p>}
    {nodes.length > 0 && <div className="split">
      <div className="panel stack"><h3>자원 목록</h3><Tree nodes={nodes} selected={selected} onSelect={setSelected} /></div>
      <div className="panel stack" aria-live="polite">
        <div className="row"><h3>선택한 자원</h3><Badge tone={mode === 'fixture' ? 'warning' : 'info'}>{mode === 'fixture' ? '모의 데이터' : '실제 서버'}</Badge></div>
        <code>{selected}</code>
        {mode === 'fixture' && fixture !== undefined && <>
          <dl>
            <dt>보호 상태</dt><dd><BooleanState value={fixture.state} /></dd>
            <dt>장치 상태</dt><dd>{fixture.health === 'healthy' ? '정상 (모의)' : fixture.health === 'degraded' ? '점검 필요 (모의)' : '확인 전'}</dd>
            <dt>장치</dt><dd>{fixture.deviceId ?? '해당 없음'}</dd>
            <dt>수신기 (Gateway)</dt><dd>{fixture.gatewayId ?? '해당 없음'}</dd>
            <dt>자원 유형</dt><dd><code>ty={fixture.ty}</code></dd>
            <dt>장치 기능 정의</dt><dd>{fixture.cnd ? <code>{fixture.cnd}</code> : '해당 없음'}</dd>
          </dl>
          <p className="small muted">모의 데이터 스냅숏이에요. 장치의 최신 상태는 실제 서버를 조회해 확인하세요.</p>
        </>}
        {mode === 'live' && <>
          {detailLoading && <p role="status">선택한 자원을 불러오고 있어요…</p>}
          {detailError !== null && <p className="notice error" role="alert">{detailError} 자원 경로를 확인하고 다시 선택해 주세요.</p>}
          {detail !== null && <>
            <dl>
              <dt>보호 상태</dt><dd><BooleanState value={detail.attributes['state']} /></dd>
              <dt>응답 형식 (envelope)</dt><dd><code>{detail.envelope}</code></dd>
              <dt>응답 코드 (RSC)</dt><dd>{detail.rsc ?? '확인 불가'}</dd>
              <dt>검색 시작 경로</dt><dd>{settings?.aePath}</dd>
              <dt>접근 정책 참조</dt><dd>{settings?.acp || '입력 안 함 (권한은 서버가 판단해요)'}</dd>
              <dt>장치 상태</dt><dd>측정 전. 성공 응답이 와도 장치가 정상이라는 뜻은 아니에요.</dd>
            </dl>
            <details><summary>원본 JSON 보기</summary><pre>{JSON.stringify({ [detail.envelope]: detail.attributes }, null, 2)}</pre></details>
          </>}
        </>}
      </div>
    </div>}
    </div>
  </div>;
}
