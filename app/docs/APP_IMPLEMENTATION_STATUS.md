# APP 구현 상태와 실제 연동 인수 조건

ROLE: APP · DOC-REV: 2.0.0 · 기준일: 2026-10-29 (Asia/Seoul) · 감사 기준 커밋: `3c6d71b` (`feature/app`).

APP 운영 산출물은 아직 완료되지 않았다. 현재 구현은 Guardian/Visitor 초기 화면, 향후 연동을 위한 HTTPS/OIDC 검증 코드, 격리된 Mobius4와 합성 서버를 사용하는 debug 시험이다. 운영 로그인·권한·등록·승인·명령·실제 상태·알림은 연결되어 있지 않다. 이 판정은 [공통 구현 계획](../../PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md)의 앱 담당 업무·앱 담당 산출물·HTTPS 계약·첫 통합 데모 인수 조건과 현재 소스 및 evidence를 대조한 결과다. Rubric 점수나 전체 프로젝트 완료를 판정하지 않는다.

## 빌드와 실행 범위

- `main` 소스에 파일이 있다는 사실만으로 운영 기능을 완료로 인정하지 않는다. `HttpsApiTransport`는 운영 호출자가 없고, `OidcBrowserFlow.start()`는 release 실행을 거부한다.
- 기본 debug에는 테스트 상태 조회와 합성 consent·관측·receipt 화면이 있다. 합성 인증·refresh·인증된 consent 화면과 localhost 테스트 CA는 별도 opt-in debug에만 포함된다.
- release에서는 합성 Activity와 테스트 redirect receiver를 제외한다. 현재 남는 사용자 화면은 서버 계약 대기 안내와 미연결 주소 설정이다.
- 화면의 Guardian/Visitor 선택은 문자열 기반 화면 전환이다. 실제 로그인, 역할 검증, 서버 접근권한 변경을 수행하지 않는다. 저장한 HTTPS 주소도 요청에는 사용하지 않는다.

근거: [앱 범위](../README.md), [메인 화면](../app/src/main/java/org/privacybubble/app/MainActivity.java), [빌드 구분](../app/build.gradle), [release manifest](../app/src/release/AndroidManifest.xml), [OIDC 검증 경계](OIDC_TRUST_BOUNDARY.md), [API 전송 경계](API_TRANSPORT.md).

## 요구별 상태

아래의 “남은 구현”은 APP 담당의 실제 개발 잔여분이다. 계약이 없다는 사유로 이 개발까지 완료 처리하지 않는다. “필수 인수 계약”의 C 번호는 다음 절과 연결한다.

| 공통 계획의 APP 요구 | 현재 구현과 확인된 증거 | 운영 완료에 남은 구현 | 필수 인수 계약 |
|---|---|---|---|
| Guardian/Visitor 화면과 로그인 | 역할별 초기 화면은 존재한다. 화면 스스로 인증·권한 변경이 아니라고 표시한다. OIDC discovery·RS256·issuer/audience/subject/nonce·PKCE 검증 코드는 있다. 운영 로그인 UI 호출자는 없고 release 흐름 시작은 차단된다. [소스](../app/src/main/java/org/privacybubble/app/MainActivity.java), [OIDC 범위](OIDC_TRUST_BOUNDARY.md) | 실제 공급자의 로그인·취소·재인증 UI, 신뢰된 설정 연결, 서버가 인정한 역할·접근 범위 표시, 실제 브라우저와 계정의 기기 시험 | C1, C2 |
| OIDC token refresh와 세션 수명 | 메모리 세션과 refresh 회전은 opt-in 합성 흐름으로 시험했다. 실패 시 세션 폐기와 미확정 요청 표시를 확인했다. 실제 공급자·브라우저, 운영 token revoke·저장·프로세스 복원은 구현·검증하지 않았다. [합성 refresh 기기 증거](../evidence/auth-consent-refresh-device.md) | 공급자 정책에 맞춘 운영 refresh/만료/로그아웃·폐기, 필요한 경우 OS 보호 저장 및 재시작 복원, 세션 상실 뒤 데이터·변경 UI 갱신 | C2, C8 |
| device/household registration과 방문자 연결 | Guardian의 기기·가정 등록과 Visitor의 초대·접근 범위는 계약 대기 문구다. 등록 API 호출과 실제 연결된 기기 목록은 없다. [메인 화면](../app/src/main/java/org/privacybubble/app/MainActivity.java) | 실제 등록·연결·초대·해제 흐름, 소유권 및 역할별 검증 결과, 실패·중복·회수 처리 | C3, C8 |
| consent·목적·TTL·scope, grant approval/revoke/expiry | 비인증 고정 합성과 opt-in 인증 합성 화면이 있다. 합성 승인·회수·기기 시간 경과·서버 expired, 응답 유실 후 미확정 처리를 시험했다. 실제 사용자 grant나 ACP를 생성하거나 회수하지 않는다. 인증 합성의 프로세스 종료 후에는 boolean 미확정 표시만 남아 기존 요청 재전송이 불가능하다. [PoC 범위](AUTHENTICATED_CONSENT_POC.md), [만료 증거](../evidence/auth-consent-expiry.md) | 실제 요청의 목적·scope·TTL·revision 검토, 서버 grant에 대한 명시 승인/회수/만료 재확인, 운영 idempotency 및 미확정 요청 정산·복원, 최신 grant 반영 확인 | C2, C4, C8 |
| incident 요청과 Group 명령 승인 | 메인 화면의 대기 문구 외 운영 요청·승인 흐름이 없다. 합성 receipt 화면은 명령을 보내거나 인가하지 않는다. [메인 화면](../app/src/main/java/org/privacybubble/app/MainActivity.java), [receipt 범위](SYNTHETIC_RECEIPT_POC.md) | 실제 incident 생성, 승인 대상과 Group 구성원 검토, approvalHash를 포함한 승인 intent, 구성원별 수락/거부/전송/ACK/readback·미확정 결과 표시 | C4, C5, C8 |
| 키링·Gateway live status와 위치 | debug에서 격리 CSE binarySwitch와 고정 합성 관측 CIN을 읽는다. 실제 팀 KEYRING outbox→MQTT→CORE→CSE 경로를 거친 합성 배터리/RSSI/시각을 공기계에 표시했다. 실물 Pi·BLE·키링·현재 위치 증거는 아니다. [합성 관측 기기 증거](../evidence/synthetic-cin-observation.md) | 실제 등록 기기의 인증된 상태 조회, freshness/unknown/offline·기기/수신/저장 시각 구분, 권한 있는 위치와 원천·정확도·유효기간 표시 | C1, C3, C6, C8 |
| MEC·handover·FL 상태 | 대기 문구만 있다. 합성 RSSI를 거리로 바꾸거나 CSE 마지막 저장을 현재 상태로 해석하지 않는다. MEC 위치·handover·FL 상태 API와 화면 연결은 없다. [메인 화면](../app/src/main/java/org/privacybubble/app/MainActivity.java) | 계약으로 제공되는 MEC·handover 상태와 epoch/전환/실패, FL 참여·round·모델 상태의 사용자별 조회·갱신 | C6, C8 |
| push/in-app 알림 | 합성 receipt의 전경 반복 GET과 내용 차이 안내가 있다. 최초·동일·변화·실패·복구·중지를 별도 loopback fixture로 기기 시험했다. 실제 CSE 변경 알림·서버 사건·push 수신 증거는 아니다. [모니터 기기 증거](../evidence/receipt-monitor-device.md) | 실제 사건 식별자를 가진 알림 수신·재연결·중복 제거·미확인 상태, 앱 전경 안내, push 권한/등록/해지와 사용자·기기 연결, 눌렀을 때 권한 재확인 | C7, C8 |
| command receipt 확인 | 실제 KEYRING `Store.accept`/`Gateway._finish`·SQLite 재열기·outbox MQTT·격리 CSE readback을 거친 고정 합성 `INDETERMINATE`를 공기계가 표시했다. 명령 토픽 ingress는 0이고 dispatch·인가·BLE·물리 동작을 호출하지 않았다. [기기 증거](../evidence/keyring-receipt-outbox-device.md), [호스트 증거](../../app-test-server/native/evidence/keyring-receipt-outbox.md) | 실제 앱 승인과 같은 commandId/사용자/기기에 속한 receipt 조회, 상태 전이와 구성원별 결과, 수락·적용·ACK·GPIO readback 구분, 불확실하거나 누락된 결과 표시 | C4, C5, C8 |
| API retry/idempotency/correlation/reconnect | 테스트 GET은 네트워크 오류·503·504에서 최대 한 번 재시도한다. 합성 mutation에는 별도 idempotency·미확정 처리가 있다. 준비된 HTTPS 전송 계층은 TLS 1.3·동일 origin·redirect 거부·크기 제한을 제공하나 운영 caller·헤더·응답 매핑이 없다. [API 범위](API_TRANSPORT.md), [테스트 GET 소스](../app/src/main/java/org/privacybubble/app/TestStateClient.java) | 실제 API adapter, request/correlation/idempotency/API version, HTTP·oneM2M·구조화 오류·Retry-After 매핑, 운영 mutation 정산, 인증 만료·네트워크 전환·재연결·수명주기 처리 | C1, C8 |
| 사용자 접근 범위에 맞춘 ACP/UI 검증 | 역할 선택은 서버 권한과 무관하다. 실제 principal→canonical AE originator, household/device scope, ACP·grantRevision과 UI의 일치 증거가 없다. 합성 bearer 검사는 운영 권한 검증을 대신하지 않는다. [앱 범위](../README.md), [합성 인증 범위](AUTHENTICATED_CONSENT_POC.md) | 서버가 판정한 접근 범위에 따른 화면·변경 동작, 권한 거절·회수·기기 소유권 변경 시 화면 갱신, Guardian/Visitor 교차 접근 부정 시험 | C2, C3, C4, C8 |
| 모바일 통합 receipt와 발표용 녹화 | Android 공기계 screenshot·APK hash·합성/격리 CSE 연동 receipt와 [85초 합성 통합 녹화](../evidence/mobile-integration-demo.md)가 있다. 영상은 상태·합성 관측·미확정 결과와 전경 재조회를 보여준다. 실제 사용자 승인→실물 키링 ACK/readback 및 최신 grant 회수의 전체 경로는 증명하지 않았다. | 실제 운영 연동을 수행한 고정 release의 재현 가능한 receipt, 실물/합성 구분과 실패·회수를 포함한 녹화, 해당 commit/APK/로그/영상 hash 연결 | C9 |

## 연결 전에 인수해야 하는 계약

경로·헤더·claim·상태 이름을 APP이 임의로 정해 운영 계약처럼 사용하지 않는다. 아래 항목은 새 mock을 요구하는 목록이 아니라 실제 서비스 owner에게 받을 입력과 인수 조건이다. 계약 수령 후에도 위 표의 APP 구현과 실제 시험은 남는다.

| ID | 제공 책임과 필수 내용 | APP 연결을 위한 인수 조건 |
|---|---|---|
| C1 · API/resource 기준 | PC-MOBIUS-CORE + PC-MOBIUS-INTEGRATION: 버전 고정 OpenAPI, 운영 HTTPS origin·TLS 정책, oneM2M resource/status/receipt 식별 규칙, 인증 API와 내부 CSE 경계 | 실행 가능한 서비스와 해당 revision을 함께 제공한다. 앱이 관리자 originator를 보유하지 않으며 authenticated principal과 resource 접근이 연결되어야 한다. 현재 `privacy-bubble.oneM2M.test` v1.0.0은 로컬 관리자 기반 시험 계약이므로 이 입력을 충족하지 않는다. |
| C2 · 사용자 인증·세션·역할 | PC-MOBIUS-INTEGRATION: trusted issuer, client ID, 등록 redirect·소유 검증 방식, audience/scope, Guardian/Visitor claim/서버 역할 모델, principal→canonical AE originator, refresh/만료/revoke·보관 정책 | 실제 브라우저에서 사용할 공급자·테스트 계정·API 검증 주체를 제공하고 정상/거부/만료/회수 결과를 재현한다. 사용자/기기 시스템 신뢰 변경 없이 사용할 수 있는 신뢰 경로가 필요하다. |
| C3 · 등록·household·초대 | PC-MOBIUS-INTEGRATION + KEYRING/CORE: 실제 device/Gateway/household 식별자, 등록/연결/초대/해제 작업, 소유권·역할·동의 전제, 중복과 양도/회수 정책 | 등록 가능한 실제 기기 및 household, 권한 있는/없는 두 사용자, 중복·해제 후 접근 거절 결과를 제공한다. 단순 텍스트 역할 선택으로 권한을 부여하지 않는다. |
| C4 · consent·grant·approval | PC-MOBIUS-INTEGRATION: request/principal/purpose/scope/TTL, consent revision·grantRevision, 서버 시각·expiry/revoke, approvalHash 대상과 정규화 규칙, 승인 결과 조회와 미확정 mutation 정산·보관 계약 | 승인된 정확한 intent와 revision을 서버가 검증하고 최신 grant 회수·만료가 이후 명령 판정에 적용됨을 조회할 수 있어야 한다. process restart 후 replay 허용 여부와 동일 키 결과의 보존 범위를 명시한다. |
| C5 · incident·Group·실제 receipt | PC-MOBIUS-INTEGRATION + CORE + KEYRING: incident와 Group/member 식별·권한, commandId/desiredState/issuedAt/grantRevision/ownershipEpoch/deadline, intent→dispatch→ACK/readback 연결, receipt 상태·단말 정산 규칙 | 앱 승인 하나를 실제 기기 결과까지 같은 ID로 추적한다. 구성원별 거절·부분 실패·결과 미확정, 동일 commandId 재시작/재전송 시 하나의 정산, 실제 ACK와 readback을 재현한다. |
| C6 · 상태·위치·handover·FL | PC-MOBIUS-INTEGRATION + CORE/KEYRING: 실제 관측/기기 health와 MEC Location/RNIS, handover/epoch, FL 상태 schema·권한·조회/구독 방식, 단위·null·원천·관측/수신/저장 시각·freshness | 실제 데이터 원천과 샘플, stale/offline/unknown 및 권한 거절 응답을 제공한다. RSSI를 거리로, CSE last-modified를 기기 관측 시각으로 대체하지 않는다. |
| C7 · notification | PC-MOBIUS-INTEGRATION: push 또는 정해진 전달 방식, 수신자/기기 등록·해지, event ID·cursor/순서·재전송·중복 제거·ack/읽음, 구독 만료/회수·재연결·링크 대상 권한 | 실제 서버 사건을 해당 사용자에게 전달하고 전경/백그라운드·재접속·중복·권한 회수 시 동작을 재현한다. 내용 비교 polling만으로 사건 ID나 push 도착을 추정하지 않는다. |
| C8 · 공통 요청·오류·복구 | CORE + PC-MOBIUS-INTEGRATION: request ID/correlation/idempotency/API version, 키의 principal·본문·작업 결합과 TTL·내구성, HTTP 200/201/202/204와 oneM2M 4000/4030/4040/4125 매핑, structured error·Retry-After·timeout·audit receipt | 각 API의 정상·거절·유실·재시도 응답 예시와 재현 경로를 제공한다. mutation timeout은 미확정으로 취급하고 서버 조회/동일 키 재시도로만 정산한다. 프레임워크 재전송 가능성까지 포함한 서버 중복 방지가 필요하다. |
| C9 · 실제 통합·release 증거 | APP + 전원, release owner는 PC-MOBIUS-INTEGRATION: 동일 contract/DOC-REV·실행 commit·기기/host·APK, testId/expected/actual/logSha256/artifactRef 및 gate performer/approver | 첫 통합 데모의 실물 BLE→Gateway→CSE→앱, 앱 승인 Group→키링 ACK/readback, 회수 후 최신 grant 거절, 같은 commandId의 재전송 정산을 증명한다. 합성 시험·운영 시험·화면 설명 영상의 범위를 각각 명시한다. |

공통 계획의 인수 순서는 CORE contract → INTEGRATION contract → KEYRING/Gateway → APP/WEB → 통합시험이다. [현재 CORE 시험 계약](../../pc-mobius/contracts/oneM2M-test-contract.v1.json)과 [테스트 중계](../../app-test-server/README.md)는 존재하지만 auth·등록·consent·approval·notification을 포괄하는 운영 OpenAPI는 이번 감사 범위의 파일 검색에서 확인하지 못했다. 계획이 연결한 `design_plan.html`, `contracts_round03.json`, `active_release.json`, Rubric 원문도 현재 workspace 파일 검색에서 확인하지 못했으므로 해당 정본의 추가 요구·최종 점수·release 일치는 검증할 수 없다. 이 부재를 운영 계약으로 추정해서 보완하지 않는다.

## 현재 증거가 입증하는 범위

| 증거 | 입증하는 내용 | 입증하지 않는 내용 |
|---|---|---|
| [합성 관측 CIN 공기계 시험](../evidence/synthetic-cin-observation.md) | 팀 KEYRING의 실제 outbox, 임시 mTLS broker, CORE, 격리 CSE를 거친 합성 관측을 Android가 조회·표시하고 중계 중단 시 과거 값 대신 오류를 표시함 | 실물 BLE/Pi/키링 관측, 실제 현재 상태·위치, 운영 인증·권한 |
| [receipt outbox 공기계 시험](../evidence/keyring-receipt-outbox-device.md) | 실제 SQLite journal/outbox의 합성 결과 보존·전송·CSE readback·pending 제거와 앱 표시 | 앱의 실제 승인, command dispatch, 물리 동작·ACK/readback, 실제 grant, push |
| [receipt 전경 모니터](../evidence/receipt-monitor-device.md) | 별도 loopback fixture의 최초·동일·변화·오류·수동 복구와 중지 시 요청 수 유지 | CSE 내용 변화를 기기에서 재현한 것, 실제 사건의 시간순 진행, push, 이미 보낸 요청의 서버 취소 |
| [합성 인증 동의](../evidence/auth-consent-device.md), [refresh](../evidence/auth-consent-refresh-device.md), [expiry](../evidence/auth-consent-expiry.md) | 별도 합성 에이전트/IdP/동의 서버의 인증·명시 승인/회수·미확정·세션 폐기·재인증·합성 서버 만료 처리 | 실제 브라우저/사용자/운영 IdP·grant·ACP, 기기 시험에서 기다려 재현한 access-token 만료, 프로세스 재시작 후 인증 mutation replay |
| [HTTPS 전송 호스트 시험 설명](API_TRANSPORT.md), [OIDC 검증 호스트 시험 설명](OIDC_TRUST_BOUNDARY.md) | 제한된 전송·암호 검증 경계와 합성 정상/부정 입력에 대한 결과 | 실제 공급자·API의 호환성·인가, 운영 UI·토큰 정책·전체 기능 완료 |
| [85초 모바일 합성 통합 시연](../evidence/mobile-integration-demo.md) | 실제 KEYRING outbox·CORE·격리 CSE의 합성 데이터를 Android에서 조회한 녹화, 영상/APK hash와 시험 자원·기기 복원 기록 | 운영 사용자 승인·권한·grant 회수, 실물 Pi/BLE·키링 ACK/readback, 전체 통합 시연 완료 |

수동 기기 시험·정리 기록은 각 evidence에 기재된 APK·경로·상황에 한정한다. 저장소의 재현 도구나 호스트 상태 시험만 추가된 경우 그 버전을 공기계에서 다시 실행했다고 간주하지 않는다. 시스템·브라우저 신뢰 변경 없이 진행한다는 사용자 제약은 유지한다.

## 공개 저장소·제출 상태

로컬 별도 브랜치 `feature/app`는 존재한다. [Kx2h/bubble-lock-app](https://github.com/Kx2h/bubble-lock-app) 공개 저장소는 생성되었으나, 현재 작업 세션의 코드 푸시는 HTTP 403으로 실패해 코드가 업로드되지 않은 상태다. 저장소 접근 및 Contents 쓰기 권한을 확인해야 한다. 저장소 생성만으로 APP 코드 공개·원격 브랜치·release SHA·재현 가능한 제출을 완료 처리하지 않는다. 토큰 값은 문서·코드·증거에 포함하지 않는다. 이 상태는 현재 세션의 확인 결과이며 권한이나 원격 상태가 바뀌면 다시 확인해야 한다.

발표용 [합성 화면 녹화와 receipt](../evidence/mobile-integration-demo.md)는 APP 소스 `3c6d71b`의 debug APK를 기준으로 고정했다. APK·영상 hash와 시험·정리 범위는 해당 receipt에 연결한다. 이 영상이 실제 사용자 승인과 실물 기기 명령의 운영 통합을 완료시키지는 않는다.

## 실제 완료를 향한 다음 순서

1. 서비스 owner에게 C1~C8의 버전 고정 운영 계약과 실제 테스트 가능한 배포·계정·기기를 인수한다. 기준 자료의 누락 경로와 정본 revision도 확인한다.
2. 운영 로그인/역할/등록과 실제 consent/grant를 앱에 연결한다. 합성 Activity를 운영 경로로 표시하거나 임의 endpoint를 만들어 이 단계를 대체하지 않는다.
3. 승인 intent와 실제 incident/Group command·receipt를 연결하고, 회수·만료·미확정 결과를 같은 서버 계약으로 처리한다.
4. 실제 상태/위치/handover/FL와 알림을 연결한 뒤 C9의 정상·거절·회수·재전송 기기 통합시험과 증거를 고정한다.
5. 쓰기 가능한 Git 인증으로 검토된 commit을 공개 저장소에 push하고 원격 SHA를 확인한다. 녹화·release·재현 절차와 코드 commit을 같은 제출 기록에 연결한다.

운영 계약이 없는 동안 실제 기능 연결은 막혀 있으나 APP 구현 잔여분은 그대로 남는다. 추가 합성 기능이나 screenshot 수 증가를 이 요구들의 완료 조건으로 사용하지 않는다.
