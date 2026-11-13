# 0.4.0 팀 변경·실서버 통합 기록

ROLE: APP · DOC-REV: 2.0.0 · 2026-11-14 Asia/Seoul.

최신 GitHub 브랜치를 병합하고 실제 `bubblelock.iptime.org:8883`의 인증서 기반 MQTT → CSE 읽기를 확인했다. 앱은 중앙 Portal 위치 계약과 짧은 표시 권한을 처리하며, 서버의 Android public client와 정확한 bearer 읽기 경로도 소스로 연결했다. **실제 운영 브라우저 로그인·서버 반영·상용 배포 완료를 선언하지 않는다.** 아래 실행 증거와 남은 입력을 구분한다.

## 병합 범위

기존 앱 `844896f`에서 `codex/integrate-all-branches`를 만들고 `origin/main`의 `bc2a45d`, `origin/codex/keyring-namho`의 `f8bbc92`, `origin/feature/final-integration`의 `4f3899b`를 순서대로 merge했다. CORE·웹 브랜치도 이 이력에 포함된다. 마지막 fetch 후 확인한 각 브랜치의 미통합 commit 수는 0이다. [SHA와 ancestry 기록](../evidence/deployment-20261114/source-integration.json)

Gateway 충돌은 최신 브랜치의 내구성 있는 중앙 영수증 staging·claim/receipt hash 확인·명령 dispatch fence 구현을 보존하는 방향으로 해결했다. 이전 인터페이스를 사용하는 통합 시험은 enabled route, observation identity, idempotency와 bound claim을 명시하도록 수정했다. 보호된 MEC read model을 우회하도록 코드를 완화하지 않았다.

최신 펌웨어의 누락된 `KEYRING_AUDIO_OUTPUT`·`KEYRING_HEADPHONE_VOLUME` 기본값과 범위 검사를 복구했다. 최대 길이 관측의 512-byte fallback은 정상 동작이므로 host 시험을 보통 크기의 에너지 정보 보존과 최대 크기의 안전한 fallback으로 분리했다.

## 앱과 배포 소스

- `mobileApiContract`를 빌드 때 고정한다. `portal-central-v1`은 `GET /v1/portal/locations`, `portal-web-v1`은 `GET /api/portal/locations`를 사용한다. 기존 `keyrings-v1`도 별개로 유지하며 endpoint 자동 전환은 하지 않는다.
- Portal 응답은 HTTPS origin·계약·no-store·Date·크기·숫자·시각을 검증한다. `readExpiresAt`, 최대 5초, request RTT, wall/monotonic clock을 적용한다. 별도 만료 timer가 지연 갱신 중에도 좌표·선택·카메라 정보를 지우고 늦은 응답의 복원을 거절한다.
- GPS/MEC/릴레이 출처와 위치·BLE 신호 각각의 모의 여부를 유지한다. RSSI-only 관측에 좌표를 붙이지 않으며 없는 배터리·연결 상태를 생성하지 않는다. 모의 위치는 명시적 텍스트와 빈 테두리 점으로 표시한다.
- 최대 1,000개 모델을 처리하고 목록 View는 25개로 제한한다. 스크롤 밖의 이전·다음 컨트롤, 페이지 알림, View 재사용을 적용했다. 기기 fixture의 초기/다음/마지막 페이지 처리 시간은 533/109/92 ms이며 일반 사용자 성능 보장은 아니다.
- Portal 요청 scope는 `openid portal:read`이다. 일반 refresh rotation을 사용하며 API 계약도 Keystore 암호문의 AAD에 묶는다. 0.3.0 세션은 다시 로그인해야 한다. 비밀번호·웹 쿠키·Gateway 개인키로 네이티브 bearer 인증을 대체하지 않는다.
- 새 `deployment/configure.mjs`는 BFF와 분리된 `bubble-lock-android` public S256 client를 생성한다. 정확한 Android redirect, 직접 subject/API-audience mapper를 사용하고 password·implicit·service-account grant를 끈다. 서버 프록시는 정확한 위치 GET만 허용하며 Gateway mTLS 자격을 사용하지 않는다. [기존 배포의 안전한 추가 절차](../../deployment/RUNBOOK.md#android-네이티브-위치-읽기), [검증·렌더링 준비물](../deployment/README.md)

기존 서버에서 구성 생성기 전체를 다시 실행하면 기존 비밀값·인증서를 손상시킬 수 있다. 이미 운영 중인 realm은 import 파일 교체만으로 갱신되지 않는다. 실제 운영 반영은 아직 실행하지 않았다.

## 이번 실행 결과

| 검증 | 결과와 범위 |
|---|---|
| 루트 `npm run verify` | 13단계 PASS. CORE 171, 웹 87, Gateway 264, 실제 로컬 TLS 교차 6개, 계약/XSD/데모/재생/웹 타입·빌드 포함 |
| 별도 배포 시험 | 10개 PASS. public native client 생성 및 Compose 네트워크 격리 포함 |
| Android | Debug 85 / Release 76 단위시험 PASS, lint 0 issues, release 패키지 경계 36개 PASS |
| 전체 release gate | 실제 build·패키지 검사·서버 시험·도구 시험·SBOM schema·OSV stage PASS. preflight만 실행한 결과와 구분한다 |
| API 공격 입력 | seed 3개 × 4,000 iterations; 각 30개 시험 PASS, 합계 38,958개 입력. 격리 loopback 시험이며 원격 침투 시험이 아니다 |
| 삼성 Android 14 | 일반 instrumentation assertion 91개, HTTPS/Main lease assertion 32개 PASS |
| 펌웨어 | ESP32 compile-only 4개 프로필, host 바이너리 9개, 설정 경계 11건 PASS. 실물 flash·음향·GPIO 검증은 하지 않음 |
| Nginx | 공식 PGP 검증 후 workspace에서 빌드한 1.30.6으로 full template 구문 및 로컬 TLS proxy 20개 검사 PASS |
| 공급망 | 492 components / 493 nodes, 라이브러리 460개 선언 라이선스 확인, 정확한 417개 버전 OSV 5 batch에서 알려진 매칭 0건 |

[실행 증거와 체크섬](../evidence/deployment-20261114/README.md) · [APK/AAB에 연결한 SBOM·SCA](../security/deployment-20261114/README.md)

기기 HTTPS 시험은 테스트 CA를 **localhost 전용 debug 앱**에만 포함하고, 실제 Android transport→parser→Main 상태를 실행했다. 모의 JWT로 로그인 단계를 우회한 privileged harness다. 서버가 403을 7초 늦추었을 때 4,124 ms에 위치·선택·카메라 cache가 지워졌고 늦은 거절 후 복원되지 않았다. 휴대폰은 잠겨 있었으므로 새 버전의 가시 화면 터치·회전·큰 글씨·TalkBack 또는 실제 OIDC 로그인 증거가 아니다. [재현 fixture](../tests/portal-network/README.md)

시험 후 user 0의 이번 instrumentation 패키지와 소유한 reverse mapping·모의 토큰을 제거했다. 디스플레이 설정은 기준값과 같고 시스템·브라우저 신뢰는 변경하지 않았다. 최적화된 0.4.0의 QA 서명 사본을 기기에 설치해 실제 설치 APK hash를 확인했다. 운영 서명본이 아니며 기본 프로필은 미설정이다. [최종 기기 기록](../evidence/deployment-20261114/device-final-state.json)

## 실제 서버에서 확인한 범위

제공된 CA·`gateway-1` 인증서/키로 MQTT TLS 1.3와 hostname 검증을 통과했다. 새 임의 client ID와 clean session, retain=false를 사용해 기존 Pi client ID를 차지하지 않았다. `gateway-1`의 health 컨테이너에 oneM2M `op=2` RETRIEVE 하나를 보내 matching request ID와 CSE `rsc=2000` 응답을 받았다. Gateway 인증서 없는 연결은 거절됐다. 제어·관측 쓰기·물리 장치 명령은 보내지 않았다. 이 결과는 실제 Pi daemon·BLE 센서의 연결 증거가 아니다. [실제 MQTT 읽기 기록](../evidence/deployment-20261114/mqtt-read-result.json)

웹 443의 관측 인증서는 자체 서명이며 system CA와 제공된 MQTT CA 양쪽으로 검증되지 않았다. 관측한 SHA256은 `396F1A7AAD4C417A91D2C87C66B59D4F67FB80A415D0D10D4DC312440E35FD6A`이나 운영 측 원본과 별도로 대조하지 못했으므로 신뢰 지문으로 채택하지 않았다. 계정 없이 수행한 protocol discovery에서 웹 BFF client `bubble-lock-dashboard`와 `/identity/realms/bubble` 경로를 보았고, native `/v1`는 403이었다. **사용자가 제공한 운영 계정은 전송·저장·커밋하지 않았다.** 로컬 Docker·workspace에는 해당 운영 서버의 관리 구성이나 공개 인증서 원본이 없었다.

## 운영 연결 완료에 필요한 입력

1. 휴대폰 외부 브라우저가 시스템 신뢰 변경 없이 검증할 수 있는 공개 HTTPS 인증서. 제공된 Gateway client 인증서로 HTTPS 서버 인증서를 대체할 수 없다.
2. 운영 서버 관리 접근 또는 현재 배포 작업 폴더/설정 위치. 별도 Android public client와 정확한 bearer read proxy를 기존 상태를 보존하며 추가해야 한다.
3. 실제 실행 중인 issuer·native client ID·API audience·principal enrollment를 확인한 후, 그 값으로 production profile을 빌드하고 기기에서 guardian/operator 로그인·권한 차이·회수·만료를 검증한다.
4. 운영 서명, 지도 제공자의 운영 조건, 실제 Pi/BLE/키링 위치·ACK, 장시간 재연결·사용성·접근성 수용 시험. [전체 제품 요구별 잔여 작업](APP_IMPLEMENTATION_STATUS.md)

제공된 Gateway 인증서의 만료는 2026-12-12 UTC다. 재배포 전에 운영 회전 절차와 경보를 확인해야 한다. 기존 [배포 runbook](../../deployment/RUNBOOK.md)의 CAA·공개 인증서 경보 제한도 운영 상태에서 다시 확인해야 하며, 이전 문서의 로컬 수용 결과를 이번 공개 배포 증거로 사용하지 않는다.
