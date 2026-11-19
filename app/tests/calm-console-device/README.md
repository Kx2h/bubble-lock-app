# Calm Console 실제 Android 수용 시험

설치된 비디버그 Server Portal 앱을 대상으로 한다. 별도 시험 패키지는
`org.privacybubble.designqa`이며 제품 APK에는 포함하지 않는다. 제품 앱과 같은
workspace QA 서명이 필요하다. 운영 배포 서명과 구분한다.

```sh
export WORKSPACE_ROOT=/path/to/workspace
export ANDROID_SERIAL=authorized-phone-serial
bash app/tests/calm-console-device/build.sh
"$PWD/app/scripts/adb-windows.sh" -s "$ANDROID_SERIAL" install --user 0 -r \
  "$WORKSPACE_ROOT/.local/calm-console-device-qa/design-qa.apk"
python3 app/tests/calm-console-device/stage-input.py
bash app/tests/calm-console-device/run-phase.sh portrait
```

`ANDROID_HOME`, `JAVA_HOME`, `ANDROID_BUILD_TOOLS_VERSION`, `QA_KEYSTORE`,
`QA_OUTPUT_DIR`, `ADB`로 도구 위치를 지정할 수 있다. 기본 빌드 도구는 SDK 36,
build-tools 36.0.0, JDK 17이다. 이 과정은 의존성·SDK를 설치하지 않는다.
잠금이 해제된 승인된 휴대폰만 사용한다.

입력은 매 단계 전에 다시 준비한다. 이미 로그인했다면 빈 비밀번호를 입력한다.
비밀번호를 인자·환경변수·소스·로그에 넣지 않는다. 입력은 시험 패키지의 0600
파일에만 들어가며, 정확한 제품 UID와 일치하는 서명을 검사한 provider가 한 번
전달한 후 삭제한다. 공급된 계정으로 실제 BFF 로그인이 필요한 경우에만 첫 화면의
로그인 폼을 제출한다. 쿠키·토큰을 native로 추출하지 않는다.

| 단계 | 실제 검사 |
|---|---|
| `portrait` | 56dp 상단바·48dp 버튼·접근성 이름·배치·시스템 글씨·공개 시작 화면 캡처 |
| `navigation` | Android TOUCHSCREEN 이벤트로 5개 가족 탭·더 보기 메뉴·선택 상태·배경 blank·RAM 경로 복원 |
| `recovery` | 연결 오류 콜백 상태를 통제된 방법으로 주입 → 실제 재시도 버튼 터치 → 운영 화면 복구 |
| `layout` | 시험 담당자가 설정한 큰 글씨·가로·밝은/어두운 화면에서 같은 배치 검사 |

DOM의 단어 배치는 Range 사각형의 수가 아니라 수직 위치를 묶어 실제 줄 수를
확인한다. 글꼴 shaping이 같은 줄에 여러 사각형을 반환해도 줄바꿈으로 오인하지
않으며, 적용된 `white-space: nowrap`도 따로 확인한다. 로컬 앱 스타일은 CSP를
완화하지 않고 동일 origin의 고정 stylesheet 경로로만 제공한다.

`recovery`는 서버 장애나 실제 인터넷 단절을 발생시키는 시험이 아니다. 현재
WebViewClient의 공개 `onReceivedHttpError` 콜백에 main-frame HTTP 503 응답을
주입한다. R8의 private 메서드 이름이나 별도 제품 API·keep 규칙에 의존하지
않으며 주입 후에는 Android 실제 터치로 재시도한다.

시험은 앱 데이터·로그인 정보를 초기화하거나 로그아웃하지 않는다. 기기 등록,
권한 발급, 명령 등 제품 mutation을 실행하지 않는다. 종료 전에 WebView 쿠키
저장을 flush하고 정상 Activity 종료를 기다려 별도 instrumentation 프로세스의
강제 종료가 로그인 수용 결과를 왜곡하지 않도록 한다.

시험 provider의 `files/<phase>-report.json`과 공개 시작 화면 PNG를 binary-safe
ADB `exec-out run-as`로 비공개 작업 폴더에 보관한다. PNG는 실제 앱 내부의
`View.draw` 렌더링이며 OS 스크린샷이나 사람의 손가락 조작 기록이 아니다.
`FLAG_SECURE`를 유지하고 실제 좌표·키링 상세·비밀번호 폼은 캡처하지 않는다.

설치 전 원래 APK·설정을 백업한다. 시험 후 본인이 바꾼 글씨·회전·야간·화면
켜짐 설정을 복원하고 시험 패키지만 사용자 0에서 제거한다. 제품 앱을 지우거나
다른 사용자 프로필을 변경하지 않는다. 실패 로그는 성공 로그와 별도로 보관한다.
빌드 성공을 실제 기기 시험 성공으로 기록하지 않는다.
