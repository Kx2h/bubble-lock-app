# APP 합성 외부 에이전트 인증 왕복 실기기 시험

- ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10 · TEST: APP-OIDC-SYNTHETIC-ROUNDTRIP-01 · 2026-10-28 현지 시간
- Android 14 / API 34 공기계에서 기존 state-probe 설치 APK SHA-256 `2d65422abed4e1641751ebf5b4c2355c809c9b49db4aac46612176fcab39b913`을 확인한 뒤, opt-in debug APK `a162ede7ec7f0cdf0ad01077fcd869b692722303208fa4c37b0e3a941a88bb7e`를 설치했다.
- 시험 APK에만 로컬 테스트 CA를 추가하고 서버는 ADB reverse를 통해 localhost의 합성 IdP로 연결했다. 별도 `org.privacybubble.oidcsurrogate` 에이전트는 앱과 동일한 debug 서명자로 확인해 선택했다. 시스템·브라우저 신뢰 저장소는 변경하지 않았다.
- 정상 거래: 합성 시험 화면의 시작 버튼을 누르고 별도 에이전트 화면에서 합성 계정을 선택했다. 앱은 `PASS: 합성 계정 검증 완료 · 실제 권한 없음`을 표시했다. 앱의 구현은 발견·PKCE·redirect/state/issuer 검사, code 교환과 ID token 검증 후 합성 subject를 확인하고 메모리 세션을 즉시 폐기한다. 화면은 `oidc-synthetic-agent.png`, `oidc-synthetic-success.png`에 저장했다.
- 취소 거래: 서버를 method/path만 기록하도록 재시작하고 별도 새 거래를 시작한 뒤 에이전트에서 Android BACK으로 취소했다. 앱은 `FAIL: 합성 인증이 취소되었거나 검증에 실패했습니다.`를 표시했다 (`oidc-synthetic-cancel.png`). 이 거래의 `oidc-synthetic-cancel.trace`에는 discovery와 authorize GET만 있고 token endpoint 호출은 없었다. trace에는 query·state·code·token이 없다.
- 이는 **합성 테스트 전용 에이전트 및 테스트 IdP**를 사용한 실기기 왕복으로, 삼성 인터넷·Chrome 같은 실제 브라우저, 실제 사용자 로그인, consent/grant 또는 운영 인증·승인을 검증한 것이 아니다. 스크린샷은 ADB stdout으로 호스트에 저장했고 기기에 별도 이미지 파일을 쓰지 않았다.
- 시험 후 에이전트 패키지와 tcp:18443 reverse를 제거하고 테스트 서버를 종료했다. 본 앱은 일반 debug APK로 복원했으며 기기 설치본과 호스트 APK의 SHA-256이 모두 `2fd24392b8a16acc9641f0150bf8520efaee7828fbced13192f2797e4da49457`임을 확인했다. 기존 CSE 및 tcp:8787 reverse는 유지했다.
