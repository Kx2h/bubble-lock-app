# Privacy Bubble Android

ROLE: APP  
DOC: PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md  
DOC-REV: 2.0.0  
RUBRIC: R03, R09, R10, R14, R17

Android 네이티브 초기 화면과 임시 Mobius4 테스트 상태 조회입니다. Guardian/Visitor 선택은 화면 분류일 뿐 로그인이나 접근권한을 부여하지 않습니다. 인증, consent, 승인, 기기 등록 등은 API 계약이 준비될 때 연결해야 합니다. HTTPS 서버 주소 설정은 아직 요청에 사용되지 않습니다. 토큰이나 개인정보를 저장하지 않습니다.

테스트 상태 조회는 `127.0.0.1:8787/api/test/bubble-state`의 loopback 테스트 프록시만 사용합니다. PC에서 프록시를 실행한 후 `adb reverse tcp:8787 tcp:8787`을 설정하고 앱의 테스트 상태 조회를 누릅니다. 이 상태는 테스트 데이터이며 실 서비스 인증이나 명령 승인을 의미하지 않습니다. 연결 실패는 화면에 표시됩니다.

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

2026-10-28 빌드는 성공했으나 AGP 8.5.2가 SDK 35에 대해 검증 범위 경고를 출력했습니다. `evidence/android-build.log`는 빌드 로그이며 실제 휴대폰 시험을 의미하지 않습니다. ADB 기기 미연결로 설치와 동적 시험은 아직 수행하지 않았습니다. CORE 계약 및 테스트 중계의 자동시험 로그와 hash는 `evidence/`에 있습니다.

실기기 PoC용 도구는 기기 serial을 명시해야 하며 기존 앱과 포트가 있으면 설치·연결을 덮어쓰지 않습니다. `cleanup`은 지정 기기의 이 앱 및 앱 데이터와 일치하는 `tcp:8787` reverse 연결을 제거합니다. 이미 사용 중인 앱이나 포트인 경우 정리 명령을 실행하지 마세요.

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
