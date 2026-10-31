# Bubble Android

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03, R09, R10, R14, R17

DOC: PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md · APP: 0.2.0 / versionCode 2

Bubble은 공유가 허용된 키링의 위치 정보를 지도와 목록으로 확인하는 Android 앱입니다. 제품 화면은 `위치 / 키링 / 설정`으로 구성합니다. 기존 합성 시험 화면은 debug 빌드의 `설정 → 개발 도구 → 시험 도구 열기`로 옮겼습니다.

## 제품 화면과 위치의 의미

- MapLibre Native Android 13.7.1의 Vulkan/OpenGL ES 지도와 OpenFreeMap 배경을 사용합니다. 키링 핀은 인증된 위치 API의 유효 좌표로만 만듭니다. 기본 한국 광역 배경은 키링의 위치가 아닙니다.
- 선택한 키링의 정보 시각·정확도·출처·좌표·제공된 연결/배터리 정보를 표시합니다. 지도 제스처를 사용하지 않아도 `키링` 목록에서 같은 정보를 읽고 위치를 선택할 수 있습니다.
- 유효기간 경과와 연결 실패의 마지막 정보는 이전 위치로 구분합니다. 로그인 만료·접근 거절·로그아웃 시 지도와 목록의 위치를 지웁니다. 위치나 키링이 없을 때 표본 좌표로 대체하지 않습니다.
- 전경에서 30초 간격으로 조회하고 수동 새로고침을 제공합니다. 화면 이탈 시 반복 조회를 중지하고 진행 요청을 취소하며, 이전 화면의 응답은 generation 검사를 통과하지 못하면 반영하지 않습니다. 네트워크 취소가 서버 처리의 즉시 중단까지 보장하지는 않습니다.
- Material DayNight 테마, sp 글자 크기, 텍스트 상태 표시, 48dp 이상 주요 터치 영역을 사용합니다. 갱신 시 기존 카드 View를 재사용해 읽기 위치와 접근성 포커스를 유지하도록 구현했습니다. 높이가 짧은 화면에서는 헤더·탭 구성을 압축하고 각 탭의 스크롤 상태를 복원합니다. 큰 글자·가로 화면·다크 모드의 실제 확인 범위는 기기 기록에서 구분합니다.

위치 데이터는 새 팀 INTEGRATION의 `IntegrationStore`에 저장된 MEC `Location` 이벤트에서 읽습니다. 상위 이벤트에 별도 `observedAt`이 없으면 이벤트의 `timestamp`를 사용하므로 이는 실물 센서의 측정 시각을 증명하지 않습니다. 앱은 이를 **정보 시각·이전 기록**으로 표시하며, 휴대폰 GPS나 RSSI를 키링 좌표로 바꾸지 않습니다. 휴대폰의 위치 권한도 요청하지 않습니다.

[모바일 응답 계약](contracts/mobile-location.v1.json) · [위치 읽기 서비스](../pc-mobius/mobile-api/README.md) · [지도 제공자·출처·데이터 경계](docs/MAP_PROVIDER.md) · [요구별 구현 상태와 배포 잔여 조건](docs/APP_IMPLEMENTATION_STATUS.md)

## 로그인과 연결 설정

`설정`에서 배포자가 안내한 서비스 주소를 저장한 후 로그인합니다. `서비스 연결 해제`를 확인하면 사용자가 입력한 연결 설정·로그인 정보·표시된 위치를 지웁니다. 빌드에 고정된 기본 서비스 주소가 있으면 해당 기본값을 표시합니다. 제품의 issuer와 client ID는 빌드 프로필에 고정하며, 일반 설정 화면에서 임의 공급자나 bearer를 입력하지 않습니다. 브라우저 Authorization Code + PKCE S256 흐름과 discovery/JWKS·ID token 검증을 사용합니다.

access/refresh token과 subject·identity 검증 맥락은 Android Keystore의 비추출 키를 사용하는 AES-GCM 저장소에 암호화합니다. access token이 만료되면 worker에서 refresh를 한 번 수행합니다. 회전하는 refresh credential은 요청 전에 내구성 있게 소비하며, 응답 유실·검증 실패 시 이전 credential을 재전송하지 않고 로그인을 다시 요구합니다. 서비스 변경·로그아웃 후 늦게 도착한 인증 응답은 session epoch와 origin 검사를 통과하지 못하면 저장하지 않습니다. 토큰은 앱 로그나 UI saved state에 넣지 않습니다.

실제 배포에서는 아래 빌드 절의 환경변수를 설정한 뒤 다음 세 프로필을 함께 제공합니다. 사용자 설정에 비밀키나 client secret을 넣지 않습니다.

```sh
"$WORKSPACE/.local/gradle-8.13/bin/gradle" --no-daemon :app:assembleRelease \
  -PmobileApiBaseUrl=https://api.example.org \
  -PoidcIssuer=https://identity.example.org \
  -PoidcClientId=registered-android-client
```

기본 미설정 release에서는 로그인 callback receiver를 제거합니다. 세 값을 모두 고정한 release는 `src/productionAuth/AndroidManifest.xml`의 callback을 사용합니다. 공급자에 등록한 redirect와 실제 도메인·API audience·scope·서버 principal binding을 맞춰야 합니다. 현재 실제 운영 IdP·계정의 전체 브라우저 로그인과 실물 키링 위치는 검증하지 않았습니다.

debug 빌드에만 `설정 → 개발 도구 → 개발 인증 연결`과 `http://127.0.0.1:8787`의 명시적인 loopback 예외가 있습니다. 이는 통제된 기기 QA용이며 제품 release에서 임시 bearer를 직접 입력하는 기능을 제공하지 않습니다. 개발 인증도 서버의 서명·권한 검증을 통과해야 합니다.

## 빌드와 검증

작업 폴더 하위의 JDK 17, Android SDK 35, AGP 8.11.1, Gradle 8.13을 사용합니다. `WORKSPACE`는 `mobius4` 폴더의 절대 경로이며 아래 명령은 저장소 `app/`에서 실행합니다.

```sh
export JAVA_HOME="$WORKSPACE/.local/jdk17"
export ANDROID_HOME="$WORKSPACE/.local/android"
export ANDROID_USER_HOME="$WORKSPACE/.local/android-user"
export GRADLE_USER_HOME="$WORKSPACE/.local/gradle-home"
export JAVA_TOOL_OPTIONS="-Duser.home=$ANDROID_USER_HOME"
"$WORKSPACE/.local/gradle-8.13/bin/gradle" --no-daemon \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```

APK는 저장소 기준 `app/app/build/outputs/apk/debug/app-debug.apk`와 `app/app/build/outputs/apk/release/`에 생성됩니다. 서명·배포용 키와 운영 프로필은 별도 배포 입력입니다. 기본 release의 callback 및 시험 화면 분리는 저장소 루트에서 `ANDROID_HOME="$WORKSPACE/.local/android" app/scripts/check-build-isolation.sh`로 확인합니다. 세 로그인 프로필을 고정한 release를 빌드한 뒤에는 같은 명령에 `EXPECT_PRODUCTION_AUTH=1`을 지정해 등록된 callback을 검사합니다.

현재 개발 검증에서 debug/release 빌드, Android JVM 단위시험 28개, 위치 서비스와 팀 연동 관련 호스트시험 24개가 통과했습니다. 이 결과는 실제 운영 IdP·물리 키링이나 공식 release gate의 완료를 뜻하지 않습니다. 제품 앱의 공기계 지도·상태·회전·큰 글자 시험은 진행 중이며, 결과를 `evidence/product-map-device.md`에 별도로 고정합니다. 기존 `evidence/android-build.log`와 2026-10-28~29의 스크린샷은 당시 버전의 기록입니다.

## 공기계 설치·업그레이드·복원

먼저 연결 기기·현재 사용자·기존 APK와 앱 데이터를 확인합니다. 기존 설치본을 교체하는 작업에서는 workspace `.local`에 원본 APK와 필요한 설정 백업을 남기고 `install -r`로 업그레이드합니다. 전체 앱 데이터를 지우거나, 설치 실패를 해결하려고 알 수 없는 기존 앱을 자동으로 삭제하지 않습니다.

```sh
# 저장소 루트에서 실행; 설치 대상과 APK를 먼저 확인
export ADB="$PWD/app/scripts/adb-windows.sh"
"$ADB" -s "$SERIAL" shell pm path org.privacybubble.app
"$ADB" -s "$SERIAL" install -r "$PWD/app/app/build/outputs/apk/debug/app-debug.apk"
```

WSL wrapper는 `install -r`의 최종 로컬 APK 경로도 Windows 경로로 변환합니다. Windows ADB가 읽을 수 없는 비공개 백업 폴더는 권한을 풀지 않고 APK만 읽을 수 있는 workspace 임시 폴더에 배치할 수 있습니다. 사용한 파일과 임시 폴더만 정리합니다.

versionCode 2에서 이전 debug APK로 복원할 때는 확인된 백업에 한해 `install -r -d <backup.apk>`가 필요할 수 있습니다. 버전·서명·데이터 호환성을 확인하고, 실패 시 기존 데이터를 보존합니다. 앱·데이터 삭제 후 재설치는 자동 대안으로 사용하지 않습니다.

이번 제품 QA는 사용자가 검토할 수 있도록 최신 APK를 기기에 남기고 시험용 인증·연결 프로필을 지우는 방식으로 진행할 계획입니다. 실제 남긴 버전·백업·설정 및 파일 정리는 제품 기기 기록을 기준으로 확인합니다. 시스템·브라우저 인증서 신뢰는 변경하지 않습니다.

## 소유권을 확인하는 개발 시험 설치·정리 도구

실기기 PoC용 도구는 기기 serial을 명시해야 하며 기존 앱과 포트가 있으면 설치·연결을 덮어쓰지 않습니다. `cleanup`은 이 도구가 설치할 때 기록한 APK SHA-256과 설치 경로, 앱 전용 데이터 안의 임의 소유 표시가 모두 일치할 때만 앱과 앱 데이터를 제거하며, 도구가 생성한 `tcp:8787` reverse 연결만 제거합니다. 로컬 소유 기록은 Git에서 제외되는 `app/.local/device-poc/`에 저장됩니다. 해시만 있던 구 버전 기록이나 경로·표시를 읽을 수 없는 경우 자동 삭제를 거부합니다. 앱을 삭제한 뒤 같은 APK를 다시 설치하거나 앱 데이터를 지우면 표시가 사라지므로 수동 확인이 필요합니다. 소유 기록을 지우기 전에 정리하세요. `adb uninstall`은 다른 사용자에게도 영향을 줄 수 있어 설치와 정리 시 Android 사용자가 owner 0 하나뿐이고 현재 사용자도 0인지 확인하며, 다중 사용자나 조회 실패 시 거부합니다. 앱 표시도 `run-as`로 현재 사용자 0의 데이터에서 확인합니다. 이 검사는 확인과 삭제 사이의 기기 상태 변경이나 표시의 의도적 복제까지 원자적으로 막지는 못합니다.

```sh
# 저장소 루트에서, PC 테스트 중계 실행 후
export ADB="$PWD/app/scripts/adb-windows.sh"  # WSL + Windows ADB인 경우; Linux ADB 경로도 사용 가능
"$ADB" devices -l
app/scripts/device-poc.sh <serial> status
app/scripts/device-poc.sh <serial> install
app/scripts/device-poc.sh <serial> launch
# 시험 후 명시적으로 앱/앱 데이터 및 포트 제거
app/scripts/device-poc.sh <serial> cleanup
# 앱을 이 도구로 설치하지 않아 cleanup이 중단된 경우, 도구가 만든 포트만 정리
app/scripts/device-poc.sh <serial> cleanup-port
node --test app/scripts/device-poc.test.mjs
```

`cleanup-port`는 앱을 삭제하지 않습니다. 도구의 소유 기록이 있고 현재 reverse 대상이 그대로일 때만 `tcp:8787`을 제거합니다. 연결이 바뀐 경우에는 제거를 거부합니다.

이 도구를 거치지 않고 직접 ADB로 설치한 앱과 포트에는 소유 기록이 없으므로 `cleanup`이 자동으로 제거하지 않습니다. 기록을 별도로 삭제하거나 작업 폴더를 옮겨 기록을 잃은 경우에도 마찬가지입니다. `gradle clean`은 이 기록을 삭제하지 않습니다. 직접 설치한 PoC를 제거할 때는 먼저 지정 기기와 앱·포트의 상태를 확인한 후, 제거 대상이 맞으면 아래 명령을 실행하세요. 앱 삭제는 앱 전용 데이터도 함께 삭제합니다.

```sh
"$ADB" -s "$SERIAL" shell pm path org.privacybubble.app
"$ADB" -s "$SERIAL" reverse --list
"$ADB" -s "$SERIAL" uninstall org.privacybubble.app
"$ADB" -s "$SERIAL" reverse --remove tcp:8787
```


## 기존 시험 도구와 증거

아래 자료는 제품 지도·운영 계정의 검증을 대신하지 않는 이전 debug 시험입니다. `설정 → 개발 도구 → 시험 도구 열기`에서 접근하며 release에는 해당 Activity가 없습니다.

- [임시 Mobius4·KEYRING outbox 연동](../app-test-server/native/README.md), [85초 합성 통합 시연](evidence/mobile-integration-demo.md): 격리 CSE와 합성 관측/receipt 조회입니다. 실제 승인·물리 동작·현재 위치 증거가 아닙니다.
- [인증된 합성 동의·refresh](docs/AUTHENTICATED_CONSENT_POC.md), [합성 consent 서버](tests/consent-mock/README.md): 합성 IdP·승인·회수·미확정 처리의 과거 검증입니다. 시험 CA는 별도 opt-in debug 앱에만 한정합니다.
- [합성 receipt 조회](docs/SYNTHETIC_RECEIPT_POC.md), [이전 주소 초안 시험](evidence/settings-draft-device.md): 해당 APK·화면·상황에 대한 기록입니다.

이전 loopback 테스트 중계의 `127.0.0.1:8787/api/test/...` 경로와 새 제품 읽기 서비스의 `GET /v1/keyrings`는 서로 다른 인터페이스입니다. 같은 포트에 동시에 실행하지 않습니다. legacy 테스트 GET의 자동 갱신은 조회 완료 10초 후이고, 제품 위치 조회는 전경 30초 주기입니다.

legacy GET은 연결 오류·503·504에 한 번 재시도하며 403·502·잘못된 응답은 재시도하지 않습니다. 두 시도의 경과 예산과 느린 본문 처리를 검사하지만, 이미 시작된 소켓 호출의 엄격한 전체 시간 제한을 의미하지는 않습니다. `app/scripts/test-client.sh`는 legacy 클라이언트 JVM 시험입니다. 운영 mutation의 idempotency·재정산 구현은 별도로 남아 있습니다.
