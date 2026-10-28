# Android 합성 외부 에이전트 standalone 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10 · TEST: APP-OIDC-EXTERNAL-AGENT-01 · 2026-10-28.

Android 14 공기계에서 `org.privacybubble.oidcsurrogate` 패키지와 `tcp:18443` reverse가 없음을 확인한 뒤 별도 debug APK를 설치하고 소유한 mock IdP와 reverse만 실행했다. 시험 APK SHA-256은 `adc8286bc5ac32960f66df251211660fb34812d39a1fdfdaf76ba5d51e4b1fd1`이다. 테스트 CA는 이 APK의 debug trust 설정에만 사용했으며 폰 전체의 인증서 저장소나 기존 브라우저 설정은 변경하지 않았다.

고정된 localhost 합성 인가 URL을 명시적으로 전달했을 때 Android TLS 연결이 성공하고 합성 계정만 허용하는 화면 및 계속 버튼이 표시됐다. 다른 호스트 `https://example.org/authorize`는 네트워크 요청 전 거부됐고 버튼은 비활성 상태였다. `local-tls-ready.png`와 `external-host-denied.png`는 공기계에서 ADB stdout으로 수집한 화면이다. 호스트의 `SyntheticRequestTest`는 비허용 호스트, 평문 HTTP, 중복 필드, 실계정 힌트, 잘못된 PKCE 등을 거부한다.

이 standalone 확인만으로는 사용자 인증이나 AppAuth 왕복이 검증되지 않는다. 후속 APP 테스트는 별도 receipt에 기록한다. 사용한 APK의 설치 해시가 호스트 APK와 같은지 확인한 후 그 패키지만 삭제했다. 이 시험에서 만든 `tcp:18443` reverse와 mock 서버를 제거했으며, 기존 주 앱·`tcp:8787`·Mobius4와 Samsung Internet 기본 브라우저는 유지됐다.
