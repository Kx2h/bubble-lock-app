# APP 제품 구현 상태와 상용 배포 잔여 조건

ROLE: APP · DOC-REV: 2.0.0 · 기준일: 2026-10-31 (Asia/Seoul) · APP 0.2.0 / versionCode 2.

현재 소스는 지도·키링 목록·설정의 제품 앱과 인증된 위치 읽기 경로를 제공한다. 이전의 “release는 대기 안내만 표시하고 API caller가 없다”는 상태는 더 이상 현재 구현을 설명하지 않는다. 새 CORE/INTEGRATION의 정책·MEC·handover·FL·MCP 구현을 인수했고, 그중 저장된 MEC 위치 이벤트를 APP 읽기 API로 연결했다. [공통 계획](../../PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md)의 전체 APP 요구와 실제 배포·실물 시험은 아래와 같이 남아 있다. 상용 release나 전체 프로젝트 완료를 선언하지 않는다.

## 현재 실행 경로

```text
배포자가 고정한 OIDC issuer/client + 브라우저 Code/PKCE
  → Android Keystore 암호화 세션 / 단일 refresh 시도
  → HTTPS GET /v1/keyrings
  → RS256·issuer·audience·subject·scope 검증 / 서버 principal binding
  → 팀 IntegrationStore의 MEC Location 이벤트 / grant·consent 확인
  → Android 지도·목록·위치 정보의 유효기간 표시
```

[모바일 계약](../contracts/mobile-location.v1.json)은 APP 소유의 소비자 읽기 확장이다. 중앙 서비스의 정식 배포 계약으로 채택하려면 INTEGRATION owner 검토가 필요하다. upstream mutation runner가 허용하는 caller principal·mTLS 주장값을 이 읽기 경로의 인증 근거로 사용하지 않는다. [읽기 서비스 설명](../../pc-mobius/mobile-api/README.md)에 신뢰 설정·권한·상한·배포 경계를 고정했다.

`LocationRepository`는 현재 `HttpsApiTransport`의 실제 호출자다. 제품 로그인은 새 `MobileSignInActivity`가 담당하며 이전 opt-in `OidcBrowserFlow`와 구분한다. 일반 설정에서 issuer/client나 bearer를 임의 입력하지 않는다. 기본 미설정 release의 callback receiver는 제거되고, 신뢰 프로필을 고정한 release에만 production-auth manifest를 적용한다.

## 요구별 현재 상태

| 계획의 APP 요구 | 현재 구현·근거 | 상용 완료에 남은 작업 |
|---|---|---|
| Guardian/Visitor와 OIDC 로그인 | 제품 홈의 임의 역할 전환을 제거했다. 브라우저 Authorization Code + PKCE S256, trusted discovery/JWKS·ID token 검증과 로그인·취소·실패 화면이 있다. [로그인 소스](../app/src/main/java/org/privacybubble/app/auth/MobileSignInActivity.java) | 실제 공급자·등록 redirect·테스트 계정·API 배포로 전체 로그인 검증. 서버가 인정한 Guardian/Visitor 역할과 사용자별 흐름 연결 |
| token refresh·세션 수명 | access/refresh token·subject·검증 identity 맥락을 Keystore AES-GCM으로 보관한다. refresh credential을 요청 전에 내구성 있게 소비하고 한 번만 교환한다. 새 ID token이 있으면 기존 identity와 검증한다. epoch·origin으로 로그아웃·서비스 변경 뒤 늦은 인증 응답을 막는다. [저장소](../app/src/main/java/org/privacybubble/app/session/MobileSessionStore.java), [refresh](../app/src/main/java/org/privacybubble/app/auth/MobileTokenManager.java) | 실제 IdP의 회전·만료·revoke 정책, 응답 유실·프로세스 종료·재시작 및 로그아웃을 기기에서 검증. 서버 측 세션 폐기 정책 연결 |
| device/household 등록·방문자 초대 | 위치 목록은 서버 관리자가 명시한 principal↔keyring binding에 한정한다. 이를 앱 등록·초대 기능으로 표시하지 않는다. | 실제 등록·연결·초대·해제 API와 UI, 소유권·역할·중복·양도·회수 처리 |
| consent·grant 승인/회수/만료 | 팀 정책 모듈이 존재하고, 위치 binding에 grant가 지정되면 읽기 서비스가 grant와 consent의 대상·scope·revision·활성·만료를 확인한다. 제품 앱의 승인·회수 UI는 아직 없다. [서비스 권한 경계](../../pc-mobius/mobile-api/README.md) | 실제 사용자 요청의 목적·TTL·scope·revision 표시, 명시 승인/회수, idempotency·응답 유실 정산·복원 UI와 전체 시험 |
| incident·Group 명령 승인 | 기존 debug receipt 조회는 유지한다. 제품 앱은 명령을 보내거나 승인하지 않는다. | incident 생성, 정확한 Group/member intent와 approvalHash 검토·승인, commandId별 ACK/readback·부분 실패·미확정 정산 |
| 키링 위치·지도·상태 | MapLibre Native 13.7.1 Vulkan/OpenGL ES 지도, OpenFreeMap 배경, 키링 선택과 정확도 영역·유효기간·이전 기록 표시, 동일 정보의 접근 가능한 목록을 구현했다. 팀 저장 이벤트를 읽으며 fixture 좌표로 자동 대체하지 않는다. [지도 경계](MAP_PROVIDER.md), [제품 화면](../app/src/main/java/org/privacybubble/app/MainActivity.java) | 실제 배포의 사용자·기기 binding과 인증된 MEC ingress, 실물 Pi/BLE·키링의 위치 데이터 원천·시각·정확도·지연을 검증 |
| MEC·handover·FL | MEC Location 이벤트 소비를 연결했다. 새 팀 handover·FL 모듈 자체는 존재한다. 앱의 handover epoch·전환·실패와 FL round/model 화면은 연결하지 않았다. | 해당 상태의 사용자 권한·조회 계약, 화면·갱신·오류·유효기간 연결과 실제 원천 검증 |
| push/in-app 알림 | 제품의 전경 30초 조회와 오류·만료 안내가 있다. 이는 사건 push나 background 수신이 아니다. | event ID·cursor·재연결·중복 제거, push 등록/해지·권한·알림 링크의 재인가 |
| command receipt | 과거 KEYRING SQLite/outbox→MQTT→CORE→격리 CSE→기기 조회 증거를 유지한다. 합성 결과이며 제품의 실제 승인·장치 실행 증거가 아니다. | 앱 승인과 같은 사용자·기기·commandId의 실제 dispatch/ACK/readback·구성원별 결과와 미확정 정산 |
| API retry/idempotency/reconnect | 제품 `GET /v1/keyrings`는 HTTPS·응답 계약/크기/필드 검증·no-store·redirect 거부를 적용한다. 전경 주기·수동 갱신과 화면 세대 검사를 사용하고 401/403에서 위치를 지운다. [클라이언트](../app/src/main/java/org/privacybubble/app/location/LocationRepository.java) | 운영 공통 correlation/API version·Retry-After·구조화 오류 계약, mutation idempotency·유실 정산, 장시간 네트워크 전환·재연결 검증 |
| 사용자 권한·UI 일치 | 읽기 API가 RS256 JWT와 명시 principal binding을 검증하고, grant가 요구되는 binding에서는 최신 grant/consent를 확인한다. `denied`·`unavailable`에 좌표가 없다. 앱은 역할 문자열로 권한을 부여하지 않는다. | 실제 IdP·principal provisioning·소유권 변경과 회수의 교차 사용자 부정 시험, upstream mutation/ingress의 운영 보안 통합 |
| UI·AX·기기 receipt·녹화 | Material DayNight, scalable sp·48dp 주요 컨트롤·텍스트 상태·이름 있는 지도 액션·갱신 중 안정적인 View 재사용·시스템바/IME 대응을 구현했다. 기존 합성 영상은 해당 이전 버전에 한정한다. | 제품 앱의 큰 글자·가로/세로·다크/라이트·오류·접근 거절·로그아웃 기기 QA 기록, 실제 사용자→실물 위치·명령 전체 경로의 receipt와 발표 녹화 |

## 위치 정보의 해석과 개인정보

위치 API는 팀 `IntegrationStore`의 `Location` 이벤트와 binding의 `ueId`를 일치시킨다. 별도 `observedAt`이 없으면 upstream 이벤트의 `timestamp`를 쓰므로 물리 센서 fix 시각을 증명하지 않는다. 앱의 표기는 “정보 시각”, “N분 전 기록”, “이전 위치 정보”이며 실시간 추적이나 현재 위치 확정으로 표시하지 않는다.

최신 이벤트가 잘못됐을 때 이전의 유효 좌표로 조용히 되돌아가지 않는다. `denied`·`unavailable`은 위치 좌표·관련 시각을 비우고, 유효기간이 지난 유효 fix는 원래 시각의 `stale`로 남긴다. 위치와 배터리·연결 정보는 실제 이벤트의 명시 필드만 사용한다. 제품 기본값에는 표본 키링·좌표가 없다.

휴대폰 위치 권한·RSSI→거리/좌표 추정·자동 역지오코딩을 사용하지 않는다. 위치 목록과 token을 일반 로그에 출력하지 않는다. 지도 제공자는 타일 요청 영역을 알 수 있으며, 기본 OpenFreeMap 공개 서비스에는 SLA가 없다. 출처 표기와 배포 제공자 선택은 [지도 문서](MAP_PROVIDER.md)를 따른다. 시스템·브라우저 인증서 신뢰 변경 없이 진행한다.

## 현재 검증과 그 한계

- JDK 17 / SDK 35 / AGP 8.11.1 / Gradle 8.13의 workspace 격리 도구로 debug와 release 빌드를 통과했다. 앱 버전은 0.2.0이다.
- Android JVM 단위시험 28개와 모바일 위치 서비스·팀 모듈 관련 호스트시험 24개가 통과했다. JWT/권한/잘못된 위치/유효기간/회수·만료 등 자동시험은 생성한 키와 명시 fixture를 사용한다.
- 제품 공기계 QA는 현재 진행 중이다. 최종 APK·설정·지도 화면·상태·해시·파일 정리는 `app/evidence/product-map-device.md`에 고정할 예정이다. 그 파일의 실제 확인 범위가 작성되기 전에는 해당 기기 결과를 완료로 취급하지 않는다.
- [기존 85초 합성 시연](../evidence/mobile-integration-demo.md), [합성 동의·refresh](../evidence/auth-consent-refresh-device.md), [이전 설정 시험](../evidence/settings-draft-device.md)은 당시 APK·상황의 기록이다. 새 제품 버전의 운영 IdP·실물 키링·상용 배포 검증으로 합산하지 않는다.

## 실제 배포에 필요한 입력과 다음 구현

1. **신뢰된 배포 프로필**: `mobileApiBaseUrl`·OIDC issuer/client·등록 redirect·API audience와 scope, 실제 계정·principal binding, 유효한 공개 신뢰 HTTPS/TLS 경로를 배포자가 제공한다. 현재 실제 운영 IdP나 계정으로 전체 흐름을 검증하지 않았다.
2. **위치 서비스 운영**: 읽기 확장의 INTEGRATION owner 검토, 인증된 MEC ingress와 실제 상태 파일, 키 회전·요청 상한·rate limit·감사·장애 복구, transactional read model을 확정한다. 현재 upstream JSON 쓰기는 원자적이지 않아 읽기 중 잘못된 상태는 503으로 거절한다.
3. **실물 연동**: 사용자와 일치하는 실제 Pi/BLE·키링·MEC 원천으로 시각·정확도·지연·offline·revocation을 확인한다. fixture 지도 핀이나 callback 데모를 실물 증거로 대체하지 않는다.
4. **남은 APP 업무**: 기기/household·초대, 실제 consent·approval·incident/Group/receipt, handover·FL 상태, push와 mutation 재정산을 버전 고정 계약으로 연결한다.
5. **배포와 release 증거**: 제품 기기 QA·접근성·실제 IdP 및 실물 시험, 서명·운영 프로필·배포 정책과 최종 commit/APK/로그/영상 hash를 함께 고정하고 계획의 담당자·검토자·gate를 인수한다. 공식 gate ID나 승인자는 만들어내지 않는다.

현재 남은 작업은 “사용자 API가 전혀 없으므로 아무 것도 연결할 수 없음”이 아니라, 구현된 위치 읽기·지도·인증 경로를 실제 배포에 인수하고 아직 없는 사용자 mutation·서비스 화면을 완성하는 작업이다.
