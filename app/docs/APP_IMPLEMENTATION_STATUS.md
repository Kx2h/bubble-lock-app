# APP 제품 구현 상태와 상용 배포 잔여 조건

ROLE: APP · DOC-REV: 2.0.0 · 기준일: 2026-11-19 (Asia/Seoul) · APP 0.5.0 / versionCode 6.

현재 Server Portal 프로필은 운영 서버 `https://bubblelock.iptime.org/`의 웹 BFF를 사용한다. 0.5.0은 최신 웹 `origin/main`의 `0d8e2f3`을 통합한 `faacfc0`을 기준으로 [Calm Console 디자인](../../web-dashboard/DESIGN.md)을 앱에 맞췄다. 런처는 `ServerPortalActivity`에서 `/#/user/overview`를 열고, 가족용 5개 탭과 운영자 메뉴는 웹이 담당한다. native 상단은 최소 56dp 바·뒤로·더보기로 압축하고 로딩·오류·재시도 화면을 정리했다. 별도의 기본 native 프로필은 MapLibre 지도·키링 목록·설정의 3개 탭과 public OIDC/API 경로를 유지한다. 이 native public client의 미등록 상태는 이미 연결된 Server Portal 프로필의 선행 조건이 아니다.

0.5.0 소스의 JVM debug 97개·release 88개, release lint 0건, release gate와 루트 통합 검증 13단계가 통과했다. 최종 APK의 잠금 해제 상태에서 세로·밝은/어두운 화면·200% 글씨·실제 5탭 터치·오류 콜백/재시도 자동 검사 265개가 통과했다. 가로 회전 시도는 세로 뷰포트에 머물러 가로 수용 시험에서 제외했다. [0.5.0 개편 보고서](CALM-CONSOLE-20261119.md)에 최종 결과를 고정한다. 2026-11-14 로그인 시험은 [0.4.1 보고서](SERVER-CONNECTION-20261114.md), 2026-11-16 실물 등록·RSSI·권한 회수는 [별도 실물 기록](../../keyring/evidence/2026-11-16-wsl-esp32-app/README.md)에 보존한다. [공통 계획](../../PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md)의 남은 요구나 [0.4.0 통합 기록](DEPLOYMENT-20261114.md)과 검증 범위를 합산하지 않는다.

## 현재 실행 경로

```text
Server Portal 프로필: 고정 HTTPS origin + 승인 leaf의 앱 전용 trust anchor/SPKI pin
  → ServerPortalActivity 런처 / 사용자 overview / same-origin WebView
  → 간결한 native 상단바 + 웹의 역할별 반응형 메뉴
  → 기존 웹 BFF client의 OIDC 로그인 / HttpOnly 웹 세션
  → 웹 session·snapshot·locations 조회 / 배포 Leaflet 지도·웹 read lease
  → BFF logout 200 후 앱 WebView 쿠키·웹 저장소 정리
```

이 경로는 웹 BFF의 서버 보관 token을 사용하고 cookie·비밀번호·token을 Java나 native 저장소로 꺼내지 않는다. 시스템·외부 브라우저 신뢰는 변경하지 않는다. native OAuth client 등록이나 native Keystore AAD를 재사용한 경로가 아니다. 설정·수명·탐색 경계는 [Server Portal 프로필](../deployment/SERVER_PORTAL.md)에 고정한다. 아래 실행 경로와 native 세부 요구는 별도 모드다.

0.5.0의 앱 전용 CSS는 같은 origin의 루트·`index.html`에서 좁은 화면의 헤더 줄바꿈만 보완한다. 데이터·권한·좌표·라우팅을 바꾸거나 native 인증 bridge를 추가하지 않는다. WebView 글씨는 시스템 fontScale을 따르며 오류 안내는 스크롤 가능한 영역에 재시도와 함께 표시한다. 복원에는 고정 메뉴 경로만 RAM에 남기고 query·인증 callback·임의 fragment·좌표·문서 상태를 저장하지 않는다. 이 복원 설명은 Server Portal에 한정하며 별도 native 모드의 구현을 대체하지 않는다.

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

native `LocationRepository`와 Portal adapter 모두 `HttpsApiTransport`를 사용한다. native 로그인은 `MobileSignInActivity`가 담당하며 이전 opt-in `OidcBrowserFlow`와 구분한다. Portal은 `openid portal:read`만 요청하고, 기존 keyrings는 `openid offline_access location:read`를 유지한다. 응답 scope가 있으면 계약에 허용된 중복 없는 scope와 필수 API scope만 인정한다. native Portal은 일반 refresh rotation을 사용한다. native token 저장소에 client secret·비밀번호·웹 세션 cookie를 넣지 않으며 일반 설정에서 issuer/client나 bearer를 임의 입력하지 않는다. 기본 미설정 release의 callback receiver는 제거되고, native 프로필을 고정한 release에만 production-auth manifest를 적용한다.

## 요구별 현재 상태

| 계획의 APP 요구 | 현재 구현·근거 | 상용 완료에 남은 작업 |
|---|---|---|
| Guardian/Visitor와 OIDC 로그인 | Server Portal은 기존 BFF를 통해 guardian·operator의 실제 로그인·역할별 화면·조회·로그아웃을 host와 기기에서 통과했다. 별도 native는 브라우저 PKCE S256·discovery/JWKS·ID token 검증·계약별 scope를 구현했다. [native 로그인](../app/src/main/java/org/privacybubble/app/auth/MobileSignInActivity.java) | Visitor 수용 시험과 교차 사용자 권한 시험. 별도 native 모드의 Android public client·redirect 등록, API audience와 전체 native 로그인 시험 |
| token refresh·세션 수명 | Server Portal은 서버 보관 token과 WebView 내부 BFF 쿠키를 사용하며 실제 로그아웃을 통과했다. native는 access/refresh token·subject·identity를 Keystore AES-GCM·계약 AAD로 보관하고 refresh를 한 번만 교환한다. epoch·origin·계약이 늦은 응답을 차단한다. [native 저장소](../app/src/main/java/org/privacybubble/app/session/MobileSessionStore.java), [refresh](../app/src/main/java/org/privacybubble/app/auth/MobileTokenManager.java) | 두 모드의 장시간 회전·만료·revoke·응답 유실·프로세스 종료·재시작 시험과 서버 세션 폐기 정책 인수 |
| device/household 등록·방문자 초대 | 2026-11-16의 0.4.1 실제 등록 폼 POST로 실물 `keyring-A`의 등록·Gateway/가구 결합과 장치 카드 표시를 확인했다. 새 0.5.0은 같은 웹 기능의 반응형 화면을 사용한다. native 위치 목록은 principal↔keyring binding을 읽으며 별도 등록·초대 화면은 없다. | 초대·해제·소유권·역할·중복·양도·회수 시험, 0.5.0 변경 화면의 최종 수용 시험과 별도 native UI 연결 |
| consent·grant 승인/회수/만료 | 2026-11-16에는 같은 목적의 동의·grant·300초 위치 lease 생성, 만료·회수와 권한 없는 운영자의 위치 비노출을 실제 서버·앱에서 확인했다. 별도 native의 승인·회수 UI는 없다. [서비스 권한 경계](../../pc-mobius/mobile-api/README.md) | 여러 사용자·목적·TTL·scope·revision 조합, idempotency·응답 유실 정산·복원과 0.5.0 최종 수용 시험, 별도 native UI |
| incident·Group 명령 승인 | 배포 웹은 사건·명령 화면과 운영자 타임라인을 제공한다. 2026-11-16에는 위치 공유 시험 사건 생성·종료를 수행했지만 실제 LED·모터 명령 쓰기는 0건이었다. 별도 native는 기존 debug receipt 조회만 유지한다. | 실제 Group/member intent·approvalHash 승인, commandId별 dispatch/ACK/readback·부분 실패·미확정 정산과 native 연결 |
| 키링 위치·지도·상태 | 2026-11-16에는 Windows BLE → 운영 HTTPS API → 0.4.1 앱의 실제 RSSI·키링 표시를 확인했다. 좌표는 `null`, 마커는 0개였고 실물 등록은 유지했다. 0.5.0은 같은 Leaflet·웹 lease 경로의 디자인 개편이다. 별도 native는 MapLibre 13.7.1·OpenFreeMap, 최대 1,000개 검증·25개 목록·View 재사용·독립 lease를 구현했다. [지도 경계](MAP_PROVIDER.md) | 인증된 MEC ingress·상시 Pi/BLE 운영과 실제 좌표·원천·시각·정확도·지연·회수, 0.5.0 최종 기기 검증 |
| MEC·handover·FL | native의 MEC Location 이벤트 소비와 팀 handover·FL 모듈이 있다. 별도 native의 handover epoch·전환·실패와 FL round/model 화면은 미연결이다. 웹 운영 서비스·영수증 화면은 이 native 잔여 작업과 구분한다. | 상태별 사용자 권한·조회 계약, 갱신·오류·유효기간과 실제 원천 검증, native UI 연결 |
| push/in-app 알림 | 웹은 기존 polling과 웹 read lease를 사용한다. native keyrings는 전경 30초, native Portal은 최대 2초 간격이며 독립 만료 timer가 있다. 사건 push·background 수신은 미구현이다. | event ID·cursor·재연결·중복 제거, push 등록/해지·권한·알림 링크의 재인가 |
| command receipt | 과거 KEYRING SQLite/outbox→MQTT→CORE→격리 CSE→기기 조회 증거를 유지한다. 합성 결과이며 제품의 실제 승인·장치 실행 증거가 아니다. | 앱 승인과 같은 사용자·기기·commandId의 실제 dispatch/ACK/readback·구성원별 결과와 미확정 정산 |
| API retry/idempotency/reconnect | Server Portal은 기존 웹 BFF와 웹 read lease를 사용한다. 별도 native 세 읽기 경로는 HTTPS·응답 상한·redirect 거부·세대 검사를 적용하며 Portal은 Date·no-store·계약 header·경과 시간을 반영한 최대 5초 승인을 독립 검사한다. [native 클라이언트](../app/src/main/java/org/privacybubble/app/location/PortalLocationRepository.java) | 각 모드의 장시간 네트워크 전환·재연결, 운영 correlation/API version·Retry-After·구조화 오류, mutation idempotency·유실 정산 |
| 사용자 권한·UI 일치 | 기존 읽기 API가 RS256 JWT·principal binding과 필요한 grant/consent를 검사한다. Portal adapter는 서버가 허용한 목록과 읽기 승인 기한에 한정해 표시한다. `denied`·`unavailable`에 좌표가 없으며 앱은 역할 문자열로 권한을 부여하지 않는다. | 실제 IdP·principal provisioning·소유권 변경과 회수의 교차 사용자 부정 시험, upstream mutation/ingress의 운영 보안 통합 |
| UI·AX·기기 receipt·녹화 | 0.5.0은 웹 Calm Console에 맞춘 색상·글자·카드·상단바·overflow·오류 복구를 구현했다. 잠금 해제된 최종 APK의 기록된 범위에서 자동 검사 265개가 통과했다. 2026-11-14의 45/46개와 11-16 실물 RSSI 계측은 당시 버전의 증거다. native 3탭·DayNight·sp·48dp·카드 재사용·시스템바/IME를 유지한다. | 가로 실기·다른 글자 크기·다른 OS/GPU와 수동 화면 읽기 프로그램 시험, 실제 위치·명령 receipt·녹화. 자동 검사를 수동 AX로 합산하지 않음 |

## 위치 정보의 해석과 개인정보

이 절의 엄격한 HTTP Date·wall/monotonic lease와 Keystore 관련 설명은 별도 native adapter의 구현이다. Server Portal은 배포 웹의 Leaflet·`useLocations` read lease·same-origin BFF 세션을 사용한다. WebView를 떠나면 페이지를 비우고 복귀 때 다시 조회한다. native 독립 타이머의 합성 검증을 웹 모드의 동일한 보장으로 합산하지 않는다. 현재 live 응답에 실물 좌표가 없어 실제 핀·정확도·지연의 수용 검증은 남아 있다.

기존 keyrings API는 팀 `IntegrationStore`의 `Location` 이벤트와 binding의 `ueId`를 일치시킨다. 별도 `observedAt`이 없으면 upstream 이벤트의 `timestamp`를 쓴다. Portal은 명시된 gps/mec/relay 좌표와 BLE 신호의 원천·시각·모의 여부를 각각 검증한다. RSSI만 있는 기록이나 gateway의 기준 좌표를 키링 좌표로 바꾸지 않는다. 앱의 “정보 시각”, “N분 전 기록”, “이전 위치 정보”는 물리 센서 fix나 현재 위치 확정을 의미하지 않는다.

최신 이벤트가 잘못됐을 때 이전의 유효 좌표로 조용히 되돌아가지 않는다. `denied`·`unavailable`은 위치 좌표·관련 시각을 비운다. 위치 관측이 오래된 것과 조회 승인이 만료된 것은 구분한다. keyrings의 오래된 유효 fix와 Portal의 아직 승인된 오래된 관측은 `stale`로 표시할 수 있다. Portal의 `readExpiresAt`으로 계산한 읽기 승인이 끝나면 이전 기록으로도 남기지 않고 위치·목록·선택·이전 위치 카메라를 제거한다. 읽기 타이머는 wall clock과 monotonic clock을 함께 검사하며 네트워크 응답과 독립적으로 동작하고 화면 복귀 시 다시 확인한다.

Portal은 HTTPS·JSON·1MiB 응답 상한·명시 계약을 요구하며 응답 `Cache-Control: no-store`와 RFC 형식의 HTTP Date를 검사한다. 휴대폰과 서버 시각 차이가 30초를 넘거나 승인 기한·필드·중복 JSON 등이 잘못되면 거절한다. HTTP Date의 초 단위 오차와 요청 경과 시간을 차감해 실제 남은 승인을 보수적으로 줄이며 최대 5초를 넘기지 않는다. 위치와 BLE의 모의 표시는 각각의 출처에 결속하며 하나의 실측 신호가 모의 좌표를 실측으로 바꾸지 않는다. 제품 기본값에는 표본 키링·좌표가 없다.

휴대폰 위치 권한·RSSI→거리/좌표 추정·자동 역지오코딩을 사용하지 않는다. 위치 목록과 token을 일반 로그에 출력하지 않는다. 지도 제공자는 타일 요청 영역을 알 수 있으며, 기본 OpenFreeMap 공개 서비스에는 SLA가 없다. 출처 표기와 배포 제공자 선택은 [지도 문서](MAP_PROVIDER.md)를 따른다. 시스템·브라우저 인증서 신뢰 변경 없이 진행한다.

## 현재 검증과 그 한계

- JDK 17 / SDK 36 / AGP 8.11.1 / Gradle 8.13으로 APP 0.5.0 debug·최적화 release를 빌드했다. JVM 시험 debug 97개·release 88개, release lint 0건, release gate와 루트 `npm run verify` 13단계가 통과했다. 통합 release SBOM은 구성요소 492개·의존성 노드 493개다. 2026-11-19 21:52:19 KST의 OSV 정확 패키지·버전 417개 조회에서 해당 버전의 알려진 취약점은 0건이었다.
- 최종 0.5.0 QA 서명 APK를 잠금 해제된 휴대폰에서 실행해 세로·주야간·200% 글씨·실제 탐색 터치·오류 콜백/재시도 265개 검사를 통과했다. 가로 실기는 세로 뷰포트 유지로 미검증이며 [개편 보고서](CALM-CONSOLE-20261119.md)에 결과를 고정한다. 설치본은 workspace QA 서명본이며 조직의 운영 배포 서명본이 아니다.
- 2026-11-14의 0.4.1에서는 guardian·operator의 host BFF 로그인·조회·로그아웃과 기기 debug 45개·최적화 release 46개 assertion을 통과했다. 당시 기기·좌표가 0개였다는 수치는 [당시 보고서](SERVER-CONNECTION-20261114.md)의 상태이며 현재 배포 상태를 뜻하지 않는다. 당시 JVM 92/83개·lint 0건·포장 검사 42건과 SBOM은 기존 증거 폴더에 보존한다.
- 2026-11-16의 0.4.1 실제 설치 앱에서는 실물 키링 등록, 실제 BLE RSSI 수신·서버 반영·앱 표시와 동의·grant·위치 lease·시험 사건의 정리·로그아웃을 확인했다. 좌표와 마커는 없었으며 LED·모터 명령을 실행하지 않았다. 당시 잠금 상태의 자동 계측을 새 버전의 수동 UI·AX 시험으로 합산하지 않는다. [실물 시험](../../keyring/evidence/2026-11-16-wsl-esp32-app/README.md)
- 이전 0.4.0의 일반 debug 기기 회귀 91개 assertion에는 1,000개 키링·25개 페이지·View 재사용 검증 49개가 포함된다. HTTPS/Main fixture 32개 assertion에서는 7초 지연된 403을 기다리는 동안 4.124초에 위치를 제거했고 거부 응답 완료는 7.198초였다. 잠긴 기기의 합성 시험으로, 현재 Server Portal의 실제 BFF 로그인 시험과 분리한다.
- 이전 통합 기록의 배포 템플릿 시험 10개, 펌웨어 compile-only 4개 환경·host 바이너리 9개·설정 경계 11건은 당시 범위다. 11-16 실물 flash·BLE 기록과 이번 0.5.0 Android 변경 시험을 구분한다.
- 실제 MQTT `bubblelock.iptime.org:8883`에서는 제공 CA·클라이언트 인증서로 TLS 1.3을 검증하고 자신에게 허용된 health 자원 읽기의 oneM2M RSC 2000을 확인했다. 이 결과는 MQTT 연결·제한된 읽기의 증거이며 앱 사용자 로그인·키링 위치·BLE 명령 실행의 증거가 아니다.
- 공개 HTTPS leaf는 사용자가 이 서버의 앱 전용 신뢰 기준으로 명시 승인했다. 0.4.1 Server Portal은 exact-domain anchor·SPKI pin으로 검증하고 실제 운영 계정 연결을 완료했다. 시스템·브라우저 신뢰 변경이나 SSL 오류 우회는 사용하지 않았다. 별도 native public client 등록·API audience·전체 native OIDC는 남아 있다.
- [이전 제품 공기계 QA 기록](../evidence/product-map-device.md)의 14개 시나리오와 큰 글자·회전·테마·오류·접근 제한·로그아웃은 당시 APK에 한정한다. 0.5.0의 최종 수용 범위와 전체 수동 AX·실물 좌표·다른 OS/GPU·별도 native 운영 OIDC 시험을 구분한다.
- [기존 85초 합성 시연](../evidence/mobile-integration-demo.md), [합성 동의·refresh](../evidence/auth-consent-refresh-device.md), [이전 설정 시험](../evidence/settings-draft-device.md)은 당시 APK·상황의 기록이다. 새 제품 버전의 운영 IdP·실물 키링·상용 배포 검증으로 합산하지 않는다.

## 실제 배포에 필요한 입력과 다음 구현

1. **연결된 웹 프로필 운영**: Server Portal의 실제 BFF 연결과 11-16 실물 등록·RSSI 경로를 확인했다. 0.5.0의 최종 기기 수용 시험을 마치고, 승인 leaf/key 교체·운영 배포 서명·배포 정책·세션 폐기·복구를 인수한다. 전체 제어 mutation과 Visitor·교차 계정 권한은 별도 수용 시험으로 남긴다.
2. **별도 native 모드 배포**: Android public client와 `org.privacybubble.app:/oauth2redirect`, 실제 API audience·`portal:read`, native bearer를 받는 정확한 공개 경로를 등록·인수한다. [native 서버 설정 템플릿](../deployment/README.md)을 사용하며 웹 BFF의 HttpOnly 쿠키나 confidential client secret을 native token으로 취급하지 않는다. 이 작업은 연결된 웹 프로필의 조건이 아니다. 기존 keyrings 확장은 원자적이지 않은 upstream JSON 쓰기를 503으로 거절한다.
3. **실물 연동**: 확인한 Windows BLE RSSI 경로를 실제 Pi 상시 운영·키링·MEC 좌표 원천으로 확장하고 시각·정확도·지연·offline·revocation을 확인한다. fixture 지도 핀이나 callback 데모를 실물 증거로 대체하지 않는다.
4. **남은 APP 업무**: 확인한 등록·동의·시험 사건 외의 전체 mutation 수용 범위와 native의 별도 UI, 초대·approval·Group/receipt·handover·FL 상태·push·mutation 재정산을 버전 고정 계약으로 연결한다.
5. **배포와 release 증거**: 추가 기기 QA·접근성·IdP 실패/회전·실물 시험, 운영 서명·프로필·배포 정책과 최종 commit/APK/로그/영상 hash를 함께 고정하고 담당자·검토자·gate를 인수한다. 현재 기기의 release는 workspace QA 서명본이며 공식 운영 서명·gate 승인자는 만들어내지 않는다.

다음 단계는 0.5.0 디자인 개편의 최종 기기 검증, 실제 좌표·상시 Gateway·장치 명령·권한 변경의 수용 시험과 별도 native API/OIDC 경로의 배포 인수다.
