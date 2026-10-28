# Privacy Bubble Android

ROLE: APP  
DOC: PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md  
DOC-REV: 2.0.0  
RUBRIC: R03, R09, R10, R14, R17

Android 네이티브 초기 화면과 임시 Mobius4 테스트 상태 조회입니다. Guardian/Visitor 선택은 화면 분류일 뿐 로그인이나 접근권한을 부여하지 않습니다. 인증, consent, 승인, 기기 등록 등은 API 계약이 준비될 때 연결해야 합니다. HTTPS 서버 주소 설정은 아직 요청에 사용되지 않습니다. 토큰이나 개인정보를 저장하지 않습니다.

테스트 상태 조회는 debug 빌드에서만 표시되며 `127.0.0.1:8787/api/test/bubble-state`의 loopback 테스트 프록시만 사용합니다. release 빌드의 네트워크 정책은 이 HTTP 예외를 포함하지 않습니다. PC에서 프록시를 실행한 후 `adb reverse tcp:8787 tcp:8787`을 설정하고 앱의 테스트 상태 조회를 누릅니다. 이 상태는 테스트 데이터이며 실 서비스 인증이나 명령 승인을 의미하지 않습니다. 연결 실패는 화면에 표시됩니다. 성공 결과에는 조회 완료 시각(기기 시계)과 자동 갱신되지 않는다는 안내를 표시합니다.

Android SDK 35, JDK 17 및 Gradle 8.7로 `gradle :app:assembleDebug`을 실행합니다. 테스트 앱과 앱 전용 데이터는 `adb uninstall org.privacybubble.app`으로 삭제하고 포트 연결은 `adb reverse --remove tcp:8787`로 제거할 수 있습니다.

현재 작업 폴더의 격리된 도구를 사용할 때 저장소 `app/`에서 다음과 같이 빌드할 수 있습니다. `WORKSPACE`는 `mobius4` 폴더의 절대 경로입니다. 빌드 결과는 `app/build/outputs/apk/debug/app-debug.apk`입니다.

```sh
export JAVA_HOME="$WORKSPACE/.local/jdk17"
export ANDROID_HOME="$WORKSPACE/.local/android"
export ANDROID_USER_HOME="$WORKSPACE/.local/android-user"
export GRADLE_USER_HOME="$WORKSPACE/.local/gradle-home"
export JAVA_TOOL_OPTIONS="-Duser.home=$ANDROID_USER_HOME"
"$WORKSPACE/.local/gradle-8.7/bin/gradle" --no-daemon :app:assembleDebug
```

2026-10-28 빌드는 성공했으나 AGP 8.5.2가 SDK 35에 대해 검증 범위 경고를 출력했습니다. `evidence/android-build.log`는 빌드 로그이며 실제 휴대폰 시험을 의미하지 않습니다. 이후 실제 기기와 임시 Mobius4 조회 시험을 수행했으며 결과와 hash는 `evidence/`에 있습니다. 인증 공급자와의 실기기 로그인은 아직 시험하지 않았습니다.

실기기 PoC용 도구는 기기 serial을 명시해야 하며 기존 앱과 포트가 있으면 설치·연결을 덮어쓰지 않습니다. `cleanup`은 이 도구가 해당 기기에 설치한 APK의 SHA-256이 여전히 일치할 때만 앱과 앱 데이터를 제거하며, 도구가 생성한 `tcp:8787` reverse 연결만 제거합니다. 소유 기록은 무시되는 로컬 `app/.local/device-poc/`에 저장됩니다. 기록이 없거나 설치된 APK의 해시를 읽을 수 없으면 자동 삭제하지 않으므로 기기 상태를 직접 확인해야 합니다. 이 기록을 지우기 전에 정리하세요.

```sh
# 저장소 루트에서, PC 테스트 중계 실행 후
export ADB="$PWD/app/scripts/adb-windows.sh"  # WSL + Windows ADB인 경우; Linux ADB 경로도 사용 가능
"$ADB" devices -l
app/scripts/device-poc.sh <serial> status
app/scripts/device-poc.sh <serial> install
app/scripts/device-poc.sh <serial> launch
# 시험 후 명시적으로 앱/앱 데이터 및 포트 제거
app/scripts/device-poc.sh <serial> cleanup
node --test app/scripts/device-poc.test.mjs
```

이 도구를 거치지 않고 직접 ADB로 설치한 앱과 포트에는 소유 기록이 없으므로 `cleanup`이 자동으로 제거하지 않습니다. 기록을 별도로 삭제하거나 작업 폴더를 옮겨 기록을 잃은 경우에도 마찬가지입니다. `gradle clean`은 이 기록을 삭제하지 않습니다. 직접 설치한 PoC를 제거할 때는 먼저 지정 기기와 앱·포트의 상태를 확인한 후, 제거 대상이 맞으면 아래 명령을 실행하세요. 앱 삭제는 앱 전용 데이터도 함께 삭제합니다.

```sh
"$ADB" -s "$SERIAL" shell pm path org.privacybubble.app
"$ADB" -s "$SERIAL" reverse --list
"$ADB" -s "$SERIAL" uninstall org.privacybubble.app
"$ADB" -s "$SERIAL" reverse --remove tcp:8787
```

OIDC는 아직 로그인 UI나 토큰 교환에 연결되지 않았습니다. 신뢰된 issuer의 HTTPS discovery/JWKS와 RS256 ID 토큰 검증 경계는 [OIDC 검증 준비](docs/OIDC_TRUST_BOUNDARY.md)에 설명되어 있으며 Nimbus JOSE+JWT 9.47이 필요합니다. `:app:testDebugUnitTest`의 JVM 단위시험은 검증 경계에 대한 시험이고 실제 IdP 또는 휴대폰 로그인 시험은 아닙니다.

로컬 테스트 상태 조회는 연결 오류와 HTTP 503·504에서 한 번 재시도합니다. HTTP 403·502와 잘못된 응답은 재시도하지 않습니다. 실제 사용자 인증이나 서버 권한에 적용되는 재시도 정책은 아닙니다. 저장소 루트에서 `app/scripts/test-client.sh`로 JVM 클라이언트 시험을 실행할 수 있습니다.

기기에서 읽기 재시도를 재현할 때는 실제 중계를 중지하고 `node app/tests/fixtures/retry-mock.mjs`를 실행한 뒤 기존 reverse 연결로 상태 조회를 한 번 누릅니다. 이 fixture는 첫 GET에 503, 다음 GET에 200을 반환하는 모의 서버입니다. 완료 후 실행한 fixture를 종료합니다. 실제 Mobius4나 키링 데이터가 아닙니다.

Debug·release APK를 모두 빌드한 다음 `ANDROID_HOME="$WORKSPACE/.local/android" app/scripts/check-build-isolation.sh`로 패키지된 네트워크 정책과 테스트 redirect receiver의 빌드별 분리를 확인할 수 있습니다.
