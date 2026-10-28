# APP AppAuth state 로그 패치 실기기 회귀

ROLE: APP · DOC-REV: 2.0.0 · R10 · TEST: APP-APPAUTH-STATE-01 · 2026-10-28.

Android 14/API 34의 기존 테스트 앱 APK hash를 확인한 뒤 debug APK `2d65422abed4e1641751ebf5b4c2355c809c9b49db4aac46612176fcab39b913`을 설치했다. debug 전용 `StateMismatchProbeActivity`는 실제 패치된 AppAuth `extractResponseData`를 합성 요청·응답으로 직접 실행한다. 외부 브라우저, 네트워크 또는 실제 자격 증명은 사용하지 않았다.

- state 불일치: `STATE_MISMATCH` 오류 반환과 성공 응답 부재를 확인했다.
- 일치: 합성 인가 코드가 정상 응답으로 반환됨을 확인했다.
- 취소: 합성 `access_denied` 오류가 반환됨을 확인했다.
- 해당 앱 PID의 AppAuth WARN 로그에서 state mismatch 경고의 존재를 확인했고 합성 요청·응답 state 두 값이 모두 없음을 확인했다. 개인 로그나 전체 logcat은 저장하지 않았다.

화면은 ADB stdout 캡처이며 `appauth-state-probe.png`에 기록했다. 이 테스트는 AppAuth 응답 처리 메서드만의 검증이며 Activity 재생성, 외부 브라우저 왕복, 실제 공급자 인증 또는 운영 승인을 의미하지 않는다. 기기의 기존 ADB reverse와 앱 데이터는 유지했다.
