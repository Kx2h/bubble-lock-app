# PC-MOBIUS-INTEGRATION

Privacy Bubble의 정책·API·MEC·handover·FL·MCP 통합 계층이다. PC-MOBIUS-CORE가 제공하는 oneM2M resource·MQTT/HTTP·Gateway receipt 계약을 그대로 사용하며, 앱·웹·키링이 같은 `DOC-REV 2.0.0` 이벤트 흐름으로 연결된다.

## 역할 경계

```text
PC-MOBIUS-CORE
  oneM2M CSE / SDT / Group / Subscription / Gateway bridge / core receipt
        │ resource·event·command·ACK/readback
        ▼
PC-MOBIUS-INTEGRATION
  API·OIDC/mTLS·ACP/grant → MEC Location/RNIS → handover
  → ESTIMED VM FL → multi-server MCP → audit/release evidence
```

## 구현 모듈

| 모듈 | 구현 내용 | Rubric |
|---|---|---|
| `src/policy-service.mjs` | OIDC/mTLS principal binding, ACP scope, consent·grant 발급·회수·만료, approval hash, audit receipt | R10 |
| `src/mec-adapter.mjs` | Location·RNIS adapter, callback ingress, event dedup, geofence·health reconcile, 양방향 trace | R13, R15, R16 |
| `src/handover-coordinator.mjs` | Edge-A/B inventory, health/load selection, drain→active, epoch, subscription·security context, rollback | R12, R13 |
| `src/fl-coordinator.mjs` | ESTIMED VM selection, multi-round update, consent revision, artifact fence, checkpoint·recovery | R14 |
| `src/mcp-orchestrator.mjs` | oneM2M·MEC·Audit server registry, approval hash, persistent run, checkpoint·resume, audit | R17 |
| `src/api-gateway.mjs` | `/v1` 정책·MEC·handover·FL·MCP API 및 idempotency route | R10~R17 |
| `src/integration-store.mjs` | JSON state persistence와 receipt append | R06, R17 |
| `src/integration-runtime.mjs` | CORE GatewayBridge와 통합 모듈 wiring | R03, R06 |

## 계약과 실행

- `contracts/integration-contract.v1.json`: integration wire·security·MEC·FL·MCP 계약 정본
- `scripts/validate-integration.mjs`: R06/R10/R12~R17 계약 구조 검증
- `scripts/run-integration.mjs`: consent→grant→MEC callback→handover→FL→MCP 전체 데모
- `scripts/run-integration-api.mjs`: `127.0.0.1:8787/v1` API 실행 프로세스
- `scripts/build-integration-evidence.mjs`: artifact SHA-256와 Rubric mapping release manifest 생성
- `scripts/replay-integration.mjs`: integration summary receipt 재생 검증
- `fixtures/fault-fixtures.json`: MQTT duplicate, MEC replay, edge drain, FL crash, MCP resume 장애 재현 기준
- `integration/release/manifest.json`: 구현 artifact와 실행 명령의 release 기준

```powershell
npm run integration:validate
npm test
npm run integration
npm run integration:api
npm run integration:evidence
npm run integration:replay -- --input=receipts/integration-demo.json
```

실행 결과는 `pc-mobius/receipts/`의 JSONL receipt와 `integration/state/`의 persistent run state로 남는다. 발표용 release packet에는 `integration/release/manifest.json`, summary receipt, CORE release manifest, 실제 Mobius4·Raspberry Pi trace를 함께 묶는다.

## API 대표 흐름

1. `/v1/auth/bind`에서 OIDC claim과 mTLS fingerprint를 oneM2M originator에 연결한다.
2. `/v1/consents` → `/v1/grants`에서 purpose·scope·TTL·revision을 발급한다.
3. `/v1/mec/callback`에서 Location/RNIS event를 dedup하고 geofence·health 정책으로 reconcile한다.
4. 승인 intent가 Edge-A/B handover와 subscription·security context epoch를 갱신한다.
5. `/v1/fl/rounds`와 `/v1/fl/updates`가 ESTIMED VM을 동적으로 선택하고 artifact hash fence를 적용한다.
6. `/v1/mcp/runs`가 oneM2M·MEC·Audit MCP를 approval hash로 실행하고 checkpoint·resume receipt를 남긴다.

모든 이벤트에 `contractId`, `docRevision`, `eventId`, `timestamp`, `owner`를 포함해 CORE receipt와 연결한다.
