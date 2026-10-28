# Privacy Bubble Android

ROLE: APP  
DOC: PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md  
DOC-REV: 2.0.0  
RUBRIC: R03, R09, R10, R14, R17

Android 네이티브 초기 화면과 임시 Mobius4 테스트 상태 조회입니다. Guardian/Visitor 선택은 화면 분류일 뿐 로그인이나 접근권한을 부여하지 않습니다. 인증, consent, 승인, 기기 등록 등은 API 계약이 준비될 때 연결해야 합니다. HTTPS 서버 주소 설정은 아직 요청에 사용되지 않습니다. 토큰이나 개인정보를 저장하지 않습니다.

테스트 상태 조회는 `127.0.0.1:8787/api/test/bubble-state`의 loopback 테스트 프록시만 사용합니다. PC에서 프록시를 실행한 후 `adb reverse tcp:8787 tcp:8787`을 설정하고 앱의 테스트 상태 조회를 누릅니다. 이 상태는 테스트 데이터이며 실 서비스 인증이나 명령 승인을 의미하지 않습니다. 연결 실패는 화면에 표시됩니다.

Android SDK 35, JDK 17 및 Gradle 8.7로 `gradle :app:assembleDebug`을 실행합니다. 테스트 앱과 앱 전용 데이터는 `adb uninstall org.privacybubble.app`으로 삭제하고 포트 연결은 `adb reverse --remove tcp:8787`로 제거할 수 있습니다.
