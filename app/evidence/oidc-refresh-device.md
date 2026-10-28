# APP 합성 OIDC refresh 공기계 PoC

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10 · TEST: APP-OIDC-REFRESH-DEVICE-01 · 2026-10-28 현지 시간.

- Android 14/API 34 공기계에서 기존 주 앱 설치본 SHA-256 `2fd24392b8a16acc9641f0150bf8520efaee7828fbced13192f2797e4da49457` 및 합성 에이전트·tcp:18443 reverse 부재를 확인한 후 opt-in debug APK `6e62e3551efcb10079e3908963ed55ccf604b71733f6e5e9d7535e0ce0e3bf4c`와 별도 합성 에이전트를 설치했다.
- 기기 화면의 합성 시험을 시작하고 별도 에이전트의 합성 사용자 확인 버튼을 한 번 눌렀다. 정상 흐름은 mock IdP 발견 → AppAuth redirect/PKCE → 인가 코드 교환 → ID 토큰 검증 → refresh 응답의 회전 토큰 검증 순서다. 앱이 기존 access token과 갱신된 token의 차이를 검증하고 세션을 닫은 뒤 `PASS: 합성 인증 및 토큰 갱신 · 실제 권한 없음`을 표시했다.
- `oidc-refresh-agent.png`는 별도 합성 에이전트 화면, `oidc-refresh-success.png`는 앱 결과다. 화면은 ADB stdout으로 호스트에 직접 저장했다. `oidc-refresh-device.trace`는 `app/tests/oidc-mock/device-refresh-trace.mjs` mock 서버에서 경로와 HTTP method만 기록한 결과로 `POST /token` 2회가 포함된다. 로그에 query, state, code 및 토큰 값은 기록하지 않았다.
- 테스트 CA는 두 opt-in 테스트 APK의 제한된 localhost 신뢰에만 사용했고 시스템 또는 외부 브라우저의 인증서 신뢰를 변경하지 않았다. 별도 합성 에이전트와 이 시험에서 만든 tcp:18443 reverse를 제거하고 mock 서버를 종료했다. 기존 tcp:8787 reverse와 CSE를 유지했으며, 주 앱은 기기 설치본 SHA-256 확인 후 일반 debug APK `fe7ce87ed037c8e6b57ad005f98e91663aa26a9c63a8ae1dc6aadbfb07e43088`로 데이터 보존 갱신하고 hash를 재확인했다.
- 이 시험은 **합성 테스트 에이전트와 mock IdP**를 사용한 실기기 인증·갱신 PoC다. Samsung Internet/Chrome의 브라우저 왕복, 실제 계정·운영 IdP·동의/권한 부여, 서버 장애 중 refresh 중복 방지 및 운영 보안 검증을 증명하지 않는다. 합성 refresh token 재사용·오류와 동시성은 별도 단위 및 mock 서버 시험에서 검증한다.
