# APP 갱신 ID 토큰 바인딩 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10 · TEST: APP-OIDC-REFRESH-ID-DEVICE-01 · 2026-10-28 현지 시간.

Android 14/API 34 공기계에서 합성 IdP와 별도 합성 외부 에이전트로 AppAuth 인가 코드·PKCE와 refresh를 실행했다. 설치 전 기존 앱 SHA-256 `9d3cc111f90ccfb5d15acd1bb6e13593957a8d84bdf39da82bcc9dac661f2739`, 합성 에이전트와 tcp:18443 reverse 부재를 확인했다. opt-in debug APK `c7f0be9e7b56d4f3b514b72fe76382ef88939141b52b3e08d983be5ff33ff430`와 같은 서명의 테스트 에이전트를 설치해 시험했다. 테스트 CA는 앱에 한정해 사용했고 시스템·외부 브라우저의 신뢰 저장소는 변경하지 않았다.

- 정상: mock이 최초 인증과 같은 issuer·subject·audience의 **서명된 갱신 ID 토큰**을 nonce 없이 반환했을 때 앱이 `PASS: 합성 인증 및 토큰 갱신`을 표시했다. [화면](oidc-refresh-idtoken-valid.png)과 [method/path trace](oidc-refresh-idtoken-valid.trace)를 보존했다.
- 부정: 같은 키로 서명됐지만 subject가 다른 갱신 ID 토큰을 mock이 반환했을 때 앱이 `FAIL: 합성 인증 또는 갱신 실패`를 표시했다. [화면](oidc-refresh-idtoken-wrong-sub.png)과 [method/path trace](oidc-refresh-idtoken-wrong-sub.trace)를 보존했다.
- 각 trace에는 `POST /token` 2회와 `GET /jwks` 2회가 있으며 query·state·인가 코드·토큰은 기록하지 않았다. trace만으로 토큰 서명이나 클레임 내용을 증명하지 않으며, mock 고정 모드와 앱의 결과를 함께 기록했다.
- 최초 두 번의 자동화 시도는 합성 에이전트 버튼이 Android에서 대문자로 표시된 사실을 매칭하지 못해 확인 버튼을 누르지 못했다. 두 시도는 discovery와 authorize GET까지만 수행됐고 각각 시험 자원 정리 및 기존 앱 복원을 확인했다. 이후 대소문자를 구별하지 않는 탐색으로 위 두 시험을 실행했다.

시험 후 직접 설치한 합성 에이전트 APK의 해시를 확인해 해당 패키지만 제거했고, 직접 만든 tcp:18443 reverse 및 mock 프로세스만 종료했다. 주 앱은 데이터 보존 갱신으로 위 일반 debug APK를 복원하고 설치 해시를 다시 확인했다. 기존 tcp:8787 reverse와 CSE는 유지했다. 캡처는 ADB stdout으로 호스트에 저장했다.

이 결과는 **합성 테스트 계정과 에이전트**에서의 클라이언트 회귀 시험이다. 삼성 인터넷·Chrome 브라우저, 운영 IdP, 실제 사용자, 운영 consent/grant, 인증서 교체 및 서버 장애 중 모든 복구 경로를 검증한 것은 아니다. 다른 issuer·audience·nonce·서명·auth_time과 경과시간에 대한 부정 시험은 별도 JUnit에서 실행했다.
