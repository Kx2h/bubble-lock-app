# APP 합성 consent 시험 서버

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10 · PoC contract: `app.synthetic-consent.v0.1`.

이 프로세스는 가상 사용자·목적·범위만 취급하며 운영 consent, 사용자 인증, ACP, grant 또는 기기 제어 권한을 부여하지 않는다. PC-MOBIUS-INTEGRATION이 사용자 API의 소유자이며 그 계약이 제공되면 이 PoC 계약을 그대로 운영에 이관하지 않는다. 서버의 메모리 상태와 영수증은 재시작 시 사라진다. 로컬 보안 경계나 유효한 인증 서비스가 아니며 127.0.0.1에만 바인딩한다.

```sh
# 저장소 루트, Node 22
node --test app/tests/consent-mock/server.test.mjs
node app/tests/consent-mock/server.mjs
# 불확실 결과 검증: 첫 성공 mutation 적용 후 연결을 끊는다
node app/tests/consent-mock/server.mjs --drop-first-response
```

공기계에서는 앱이 127.0.0.1:18787을 사용하므로, 먼저 기존 매핑이 없는지 `adb -s <serial> reverse --list`로 확인한 뒤 `adb -s <serial> reverse --no-rebind tcp:18787 tcp:18787`을 설정한다. 시험 후 본인이 생성한 매핑만 `adb -s <serial> reverse --remove tcp:18787`으로 제거한다. 앱이나 포트가 이미 존재하면 덮어쓰지 않는다.

## 시험 전용 HTTP 계약

모든 응답에 `mode: "synthetic"`이 포함된다. GET `/api/mock-consent/v1/request`는 다음 고정 요청을 돌려준다.

```json
{"mode":"synthetic","request":{"requestId":"synthetic-request-1","principal":"synthetic-visitor","purpose":"synthetic-location-check","scopes":["test.location.read"],"ttlSeconds":120,"revision":1}}
```

GET `/api/mock-consent/v1/grant`는 `status`가 `none`, `active`, `expired`, `revoked` 중 하나이고, `none`일 때 `grant: null`, 그 외에는 `{requestId, consentRevision, grantRevision, expiresAt, receiptId}`인 grant를 반환한다. 만료와 회수 시 `grantRevision`이 증가한다. `expiresAt`은 서버 UTC 시각이다.

POST `/api/mock-consent/v1/approve`의 JSON은 `{requestId, revision, purpose, scopes, ttlSeconds}`이며 위 고정 요청과 정확히 일치해야 한다. POST `/api/mock-consent/v1/revoke`의 JSON은 `{requestId, grantRevision}`이며 현재 active grant revision과 일치해야 한다. 두 요청은 `Content-Type: application/json`, `Idempotency-Key`, `X-Correlation-ID`를 필요로 한다. 각 ID는 영숫자와 하이픈만 사용한 16~64자여야 한다. 성공은 HTTP 200 `{mode, status, grant, correlationId}`이며, 잘못된 요청과 불일치는 HTTP 400 또는 409 `{mode, error}`다.

같은 key와 같은 요청은 최초 결과를 그대로 반환하고, 같은 key를 다른 내용이나 경로에 쓰면 `409 idempotency_conflict`로 거절한다. **재전송 영수증은 과거 결과**이므로 앱은 결과 수신 후 GET `/grant`로 현재 상태를 확인한다. 요청 응답이 유실되면 앱은 성공을 추정하지 않고 확인 필요 상태를 표시해야 한다. `--drop-first-response`는 서버에서 mutation을 처리한 후 연결을 끊는 방식으로 이를 재현한다. 실제 API의 인가, 권한 철회, hash, 원자성이나 장애 복구는 이 서버의 동작으로 증명되지 않는다.
