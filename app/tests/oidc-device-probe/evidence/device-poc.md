# Android 합성 OIDC TLS 검증 receipt

ROLE: APP · DOC-REV: 2.0.0 · R10 · TEST: APP-OIDC-ANDROID-PROBE-01 · 2026-10-28.

Android 14(API 34) 공기계에서 기존 `org.privacybubble.oidcprobe` 패키지가 없고 `tcp:18443` reverse가 없음을 확인했다. 별도 debug APK를 설치하고 새 `adb reverse --no-rebind tcp:18443 tcp:18443`을 만들었다. 설치된 APK와 호스트 APK의 SHA-256은 모두 `016dc1b5e72635929f9823d4577ff098796a90cc4523c62b3c9cba9d5f485aa2`였다. 기존 APP 패키지와 tcp:8787 reverse는 변경하지 않았다.

이 별도 probe APK에만 `.local/oidc-mock/ca.pem`의 공개 CA를 debug trust anchor로 포함했다. 휴대폰 또는 호스트의 시스템 신뢰 저장소는 변경하지 않았다. 로컬의 합성 IdP와 probe 앱 사이에 TLS 검증을 유지한 상태에서, 앱의 `OidcProvider`, `OidcRedirect`, `IdTokenVerifier`로 discovery, 합성 사용자 PKCE 코드 교환, 서명 및 nonce 확인을 실행했다. 올바른 synthetic nonce는 성공, 잘못된 nonce는 거부되어 `authorized-test-ca.png`의 PASS 화면을 확인했다.

동일 주소에서 이 테스트 CA로 서명되지 않은 별도의 self-signed 인증서로 서버를 재시작하고 probe를 다시 열었을 때 `SSLHandshakeException`으로 거부되는 `untrusted-server.png`를 확인했다. 두 캡처는 ADB stdout으로 호스트에 직접 저장했고 전화기 안에는 파일을 쓰지 않았다. 시험 종료 후 APK 해시가 일치함을 다시 확인하고 probe 패키지를 삭제했으며, 새로 만든 tcp:18443 reverse만 제거했다. 기존 tcp:8787 reverse는 유지되었다. 시험 서버는 종료했다.

이것은 probe 앱의 **내부 HTTP 요청**과 합성 테스트 계정에 대한 시험이다. 실제 외부 브라우저, AppAuth 브라우저 결과 전달, 실제 사용자 로그인, Guardian/Visitor 권한, consent, refresh, 실제 인증 공급자 또는 운영 인증의 증거가 아니다. 올바른 CA가 없는 폰의 실제 외부 브라우저는 검증하지 않았다. 서명된 코드는 테스트 전용 코드다. 인증 성공 화면의 텍스트는 probe 내부 결과이며 실제 인증 서비스를 뜻하지 않는다.
