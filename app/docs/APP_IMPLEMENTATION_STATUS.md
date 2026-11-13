# APP 제품 구현 상태와 상용 배포 잔여 조건

ROLE: APP · DOC-REV: 2.0.0 · 기준일: 2026-11-14 (Asia/Seoul) · APP 0.4.0 / versionCode 4.

현재 소스는 지도·키링 목록·설정과 계약별 인증된 위치 읽기 경로를 제공한다. 기존 MEC 이벤트 읽기와 함께 통합된 중앙·웹 Portal 계약을 명시적으로 선택할 수 있다. Portal 읽기 승인은 최대 5초이며 갱신 요청과 독립된 타이머로 만료 정보를 제거한다. [공통 계획](../../PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md)의 전체 APP 요구와 실제 배포·실물 시험은 아래와 같이 남아 있다. 현재 실행 증거와 운영 연결 조건은 [2026-11-14 통합 배포 보고서](DEPLOYMENT-20261114.md)에 고정한다.

## 현재 실행 경로

```text
배포자가 고정한 API origin + OIDC issuer/public client + API 계약
  → 브라우저 Authorization Code/PKCE S256 / ID token 검증
  → Android Keystore AES-GCM / 계약 AAD / 단일 refresh 시도
  → 계약별 HTTPS GET
      keyrings-v1:       /v1/keyrings
      portal-central-v1: /v1/portal/locations
      portal-web-v1:     /api/portal/locations
  → 응답·권한·시각·위치 원천 검증
  → 지도·25개씩 키링 목록 / Portal 읽기 승인 만료 시 전체 위치 제거
```

[모바일 계약](../contracts/mobile-location.v1.json)의 `keyrings-v1`은 기존 APP 소비자 읽기 확장이고 기본값이다. [읽기 서비스 설명](../../pc-mobius/mobile-api/README.md)에 이 경로의 신뢰 설정·권한·상한을 고정했다. 두 Portal 계약은 [별도 adapter](../app/src/main/java/org/privacybubble/app/location/PortalLocationRepository.java)가 처리한다. 배포의 `mobileApiContract`로 선택하며 endpoint를 자동 탐색하거나 다른 계약으로 fallback하지 않는다. upstream mutation runner의 caller principal·mTLS 주장값을 앱 사용자 인증으로 사용하지 않는다.

기존 `LocationRepository`와 Portal adapter 모두 `HttpsApiTransport`를 사용한다. 제품 로그인은 `MobileSignInActivity`가 담당하며 이전 opt-in `OidcBrowserFlow`와 구분한다. Portal은 `openid portal:read`만 요청하고, 기존 keyrings는 `openid offline_access location:read`를 유지한다. 응답 scope가 있으면 계약에 허용된 중복 없는 scope와 필수 API scope만 인정한다. Portal은 일반 refresh rotation을 사용한다. 앱에 client secret·비밀번호·웹 세션 cookie를 저장하거나 대시보드 로그인을 재사용하는 경로는 없다. 일반 설정에서 issuer/client나 bearer를 임의 입력하지 않는다. 기본 미설정 release의 callback receiver는 제거되고, 신뢰 프로필을 고정한 release에만 production-auth manifest를 적용한다.

## 요구별 현재 상태

| 계획의 APP 요구 | 현재 구현·근거 | 상용 완료에 남은 작업 |
|---|---|---|
| Guardian/Visitor와 OIDC 로그인 | 제품 홈의 임의 역할 전환을 제거했다. 브라우저 Authorization Code + PKCE S256, trusted discovery/JWKS·ID token 검증과 로그인·취소·실패 화면, 계약별 최소 scope가 있다. [로그인 소스](../app/src/main/java/org/privacybubble/app/auth/MobileSignInActivity.java) | 운영 Android public client·redirect가 미등록이다. 신뢰할 HTTPS·API audience를 확정한 뒤 실제 공급자·테스트 계정으로 전체 로그인 검증. 서버가 인정한 Guardian/Visitor 역할과 사용자별 흐름 연결 |
| token refresh·세션 수명 | access/refresh token·subject·검증 identity 맥락을 Keystore AES-GCM으로 보관하고 API 계약을 암호문의 AAD에 결속한다. 구버전·다른 계약 세션은 다시 로그인해야 한다. refresh credential을 요청 전에 내구성 있게 소비하고 한 번만 교환한다. 새 ID token이 있으면 기존 identity와 검증한다. epoch·origin·계약으로 늦은 인증 응답을 막는다. [저장소](../app/src/main/java/org/privacybubble/app/session/MobileSessionStore.java), [refresh](../app/src/main/java/org/privacybubble/app/auth/MobileTokenManager.java) | 실제 IdP의 회전·만료·revoke 정책, 응답 유실·프로세스 종료·재시작 및 로그아웃을 기기에서 검증. 서버 측 세션 폐기 정책 연결 |
| device/household 등록·방문자 초대 | 위치 목록은 서버 관리자가 명시한 principal↔keyring binding에 한정한다. 이를 앱 등록·초대 기능으로 표시하지 않는다. | 실제 등록·연결·초대·해제 API와 UI, 소유권·역할·중복·양도·회수 처리 |
| consent·grant 승인/회수/만료 | 팀 정책 모듈이 존재하고, 위치 binding에 grant가 지정되면 읽기 서비스가 grant와 consent의 대상·scope·revision·활성·만료를 확인한다. 제품 앱의 승인·회수 UI는 아직 없다. [서비스 권한 경계](../../pc-mobius/mobile-api/README.md) | 실제 사용자 요청의 목적·TTL·scope·revision 표시, 명시 승인/회수, idempotency·응답 유실 정산·복원 UI와 전체 시험 |
| incident·Group 명령 승인 | 기존 debug receipt 조회는 유지한다. 제품 앱은 명령을 보내거나 승인하지 않는다. | incident 생성, 정확한 Group/member intent와 approvalHash 검토·승인, commandId별 ACK/readback·부분 실패·미확정 정산 |
| 키링 위치·지도·상태 | MapLibre Native 13.7.1 Vulkan/OpenGL ES 지도, OpenFreeMap 배경, 키링 선택·정확도·유효기간과 접근 가능한 목록을 구현했다. Portal 두 계약은 최대 1,000개 키링을 검증하고 목록은 25개씩 페이지를 나눠 View를 재사용한다. 위치와 BLE 신호의 모의 여부를 각각 표시하며 fixture 좌표로 자동 대체하지 않는다. [지도 경계](MAP_PROVIDER.md), [제품 화면](../app/src/main/java/org/privacybubble/app/MainActivity.java) | 실제 배포의 사용자·기기 binding과 인증된 MEC ingress, 실물 Pi/BLE·키링의 위치 데이터 원천·시각·정확도·지연을 검증 |
| MEC·handover·FL | MEC Location 이벤트 소비를 연결했다. 새 팀 handover·FL 모듈 자체는 존재한다. 앱의 handover epoch·전환·실패와 FL round/model 화면은 연결하지 않았다. | 해당 상태의 사용자 권한·조회 계약, 화면·갱신·오류·유효기간 연결과 실제 원천 검증 |
| push/in-app 알림 | 기존 keyrings는 전경 30초, Portal은 전경 최대 2초 간격으로 조회하며 오류·만료를 안내한다. Portal의 승인 만료 타이머는 조회 응답과 독립적이다. 사건 push와 background 수신은 미구현이다. | event ID·cursor·재연결·중복 제거, push 등록/해지·권한·알림 링크의 재인가 |
| command receipt | 과거 KEYRING SQLite/outbox→MQTT→CORE→격리 CSE→기기 조회 증거를 유지한다. 합성 결과이며 제품의 실제 승인·장치 실행 증거가 아니다. | 앱 승인과 같은 사용자·기기·commandId의 실제 dispatch/ACK/readback·구성원별 결과와 미확정 정산 |
| API retry/idempotency/reconnect | 세 읽기 경로에 HTTPS·응답 상한·no-store·redirect 거부·화면 세대 검사를 적용한다. Portal은 JSON·Date·응답 no-store와 중앙 계약 header를 검사하고 전송 시간·시각 오차를 반영한 최대 5초 승인을 적용한다. 401/403 또는 승인 만료 시 목록·좌표·선택을 지운다. 지연된 응답이 만료 타이머를 막지 않는다. [클라이언트](../app/src/main/java/org/privacybubble/app/location/PortalLocationRepository.java) | 운영 공통 correlation/API version·Retry-After·구조화 오류 계약, mutation idempotency·유실 정산, 장시간 네트워크 전환·재연결 검증 |
| 사용자 권한·UI 일치 | 기존 읽기 API가 RS256 JWT·principal binding과 필요한 grant/consent를 검사한다. Portal adapter는 서버가 허용한 목록과 읽기 승인 기한에 한정해 표시한다. `denied`·`unavailable`에 좌표가 없으며 앱은 역할 문자열로 권한을 부여하지 않는다. | 실제 IdP·principal provisioning·소유권 변경과 회수의 교차 사용자 부정 시험, upstream mutation/ingress의 운영 보안 통합 |
| UI·AX·기기 receipt·녹화 | Material DayNight, scalable sp·48dp 주요 컨트롤·텍스트 상태·이름 있는 지도 액션·페이지 간 View 재사용·시스템바/IME 대응을 구현했다. 0.4의 1,000개 목록·선택·페이지 동작은 기기 fixture로 검증했다. [이전 제품 기기 QA](../evidence/product-map-device.md)의 큰 글자·회전·테마·터치 기록은 당시 APK에 한정한다. | 0.4의 잠금 해제 상태에서 실제 터치·화면 읽기 프로그램·큰 글자·회전 재검증, 다른 OS/GPU 사용성, 실제 사용자→실물 위치·명령 전체 경로의 receipt와 발표 녹화 |

## 위치 정보의 해석과 개인정보

기존 keyrings API는 팀 `IntegrationStore`의 `Location` 이벤트와 binding의 `ueId`를 일치시킨다. 별도 `observedAt`이 없으면 upstream 이벤트의 `timestamp`를 쓴다. Portal은 명시된 gps/mec/relay 좌표와 BLE 신호의 원천·시각·모의 여부를 각각 검증한다. RSSI만 있는 기록이나 gateway의 기준 좌표를 키링 좌표로 바꾸지 않는다. 앱의 “정보 시각”, “N분 전 기록”, “이전 위치 정보”는 물리 센서 fix나 현재 위치 확정을 의미하지 않는다.

최신 이벤트가 잘못됐을 때 이전의 유효 좌표로 조용히 되돌아가지 않는다. `denied`·`unavailable`은 위치 좌표·관련 시각을 비운다. 위치 관측이 오래된 것과 조회 승인이 만료된 것은 구분한다. keyrings의 오래된 유효 fix와 Portal의 아직 승인된 오래된 관측은 `stale`로 표시할 수 있다. Portal의 `readExpiresAt`으로 계산한 읽기 승인이 끝나면 이전 기록으로도 남기지 않고 위치·목록·선택·이전 위치 카메라를 제거한다. 읽기 타이머는 wall clock과 monotonic clock을 함께 검사하며 네트워크 응답과 독립적으로 동작하고 화면 복귀 시 다시 확인한다.

Portal은 HTTPS·JSON·1MiB 응답 상한·명시 계약을 요구하며 응답 `Cache-Control: no-store`와 RFC 형식의 HTTP Date를 검사한다. 휴대폰과 서버 시각 차이가 30초를 넘거나 승인 기한·필드·중복 JSON 등이 잘못되면 거절한다. HTTP Date의 초 단위 오차와 요청 경과 시간을 차감해 실제 남은 승인을 보수적으로 줄이며 최대 5초를 넘기지 않는다. 위치와 BLE의 모의 표시는 각각의 출처에 결속하며 하나의 실측 신호가 모의 좌표를 실측으로 바꾸지 않는다. 제품 기본값에는 표본 키링·좌표가 없다.

휴대폰 위치 권한·RSSI→거리/좌표 추정·자동 역지오코딩을 사용하지 않는다. 위치 목록과 token을 일반 로그에 출력하지 않는다. 지도 제공자는 타일 요청 영역을 알 수 있으며, 기본 OpenFreeMap 공개 서비스에는 SLA가 없다. 출처 표기와 배포 제공자 선택은 [지도 문서](MAP_PROVIDER.md)를 따른다. 시스템·브라우저 인증서 신뢰 변경 없이 진행한다.

## 현재 검증과 그 한계

- JDK 17 / SDK 36 / AGP 8.11.1 / Gradle 8.13의 workspace 격리 도구로 APP 0.4.0 debug·최적화 release 빌드를 통과했다. JVM 시험 debug 85개·release 76개, release lint 문제 0건, release APK 포장 검사 36건이 통과했다.
- 일반 debug 기기 회귀의 91개 assertion에 1,000개 키링·25개 페이지·View 재사용 검증 49개가 포함된다. 별도 HTTPS/Main fixture는 32개 assertion을 통과했다. 403 응답을 7초 지연한 요청에서 위치 제거를 4.124초에 확인했고 거부 응답 완료는 7.198초였다. 잠긴 기기에서 privileged harness로 실행한 합성 시험이며 실제 OIDC 로그인·눈에 보이는 터치·실물 키링 위치 시험이 아니다.
- 통합 소스 `npm run verify` 13단계, 배포 템플릿 시험 10개, 펌웨어 compile-only 4개 환경·host 바이너리 9개·설정 경계 11건이 통과했다. 펌웨어 결과는 실제 provisioning·flash·GPIO·음향 시험과 구분한다.
- 실제 MQTT `bubblelock.iptime.org:8883`에서는 제공 CA·클라이언트 인증서로 TLS 1.3을 검증하고 자신에게 허용된 health 자원 읽기의 oneM2M RSC 2000을 확인했다. 이 결과는 MQTT 연결·제한된 읽기의 증거이며 앱 사용자 로그인·키링 위치·BLE 명령 실행의 증거가 아니다.
- 공개 웹 HTTPS는 제공 ZIP CA와 다른 자체서명 인증서여서 신뢰 검증이 미해결이다. 웹 session·Portal 경로와 issuer 관찰은 인증서 검증을 우회한 비인증 관찰로만 취급한다. 운영 계정으로 로그인하거나 비밀번호를 전송하지 않았다. Android public client 미등록, 실제 API audience 미확정으로 운영 Android OIDC 전체 흐름은 대기 중이다.
- [이전 제품 공기계 QA 기록](../evidence/product-map-device.md)의 14개 시나리오와 큰 글자·회전·테마·오류·접근 제한·로그아웃 증거는 당시 APK에 한정한다. 현재 0.4의 수동 사용성·실제 운영 IdP·실물 위치·다른 OS/GPU 검증은 남아 있다.
- [기존 85초 합성 시연](../evidence/mobile-integration-demo.md), [합성 동의·refresh](../evidence/auth-consent-refresh-device.md), [이전 설정 시험](../evidence/settings-draft-device.md)은 당시 APK·상황의 기록이다. 새 제품 버전의 운영 IdP·실물 키링·상용 배포 검증으로 합산하지 않는다.

## 실제 배포에 필요한 입력과 다음 구현

1. **신뢰된 배포 프로필**: 공개 웹 HTTPS 인증서의 원본·신뢰 경로, Android public client와 `org.privacybubble.app:/oauth2redirect`, 실제 API audience·`portal:read`를 확정한다. 관찰된 issuer는 `https://bubblelock.iptime.org/identity/realms/bubble`이며 웹 client를 Android용으로 재사용하지 않는다. [서버 설정 템플릿](../deployment/README.md)은 미확정 placeholder가 남으면 생성을 거절한다. 실제 관리 설정 반영과 계정 로그인은 아직 하지 않았다.
2. **위치 서비스 운영**: 선택한 읽기 계약의 owner 인수, native bearer를 받는 정확한 공개 경로, 인증된 위치 ingress·사용자/기기 binding·키 회전·rate limit·감사·복구를 확정한다. 비인증 공개 관찰에서 중앙 `/v1/portal/locations`는 nginx 403, 웹 `/api/portal/locations`는 401이었다. 웹 BFF의 HttpOnly 세션을 앱 token으로 취급하지 않는다. 기존 keyrings 확장의 upstream JSON 쓰기는 원자적이지 않아 잘못된 상태를 503으로 거절한다.
3. **실물 연동**: 사용자와 일치하는 실제 Pi/BLE·키링·MEC 원천으로 시각·정확도·지연·offline·revocation을 확인한다. fixture 지도 핀이나 callback 데모를 실물 증거로 대체하지 않는다.
4. **남은 APP 업무**: 기기/household·초대, 실제 consent·approval·incident/Group/receipt, handover·FL 상태, push와 mutation 재정산을 버전 고정 계약으로 연결한다.
5. **배포와 release 증거**: 제품 기기 QA·접근성·실제 IdP 및 실물 시험, 서명·운영 프로필·배포 정책과 최종 commit/APK/로그/영상 hash를 함께 고정하고 계획의 담당자·검토자·gate를 인수한다. 공식 gate ID나 승인자는 만들어내지 않는다.

다음 단계는 구현된 위치 읽기·지도·인증 경로의 실제 배포 인수와 사용자 mutation·서비스 화면 구현이다.
