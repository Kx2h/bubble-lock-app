# APP OIDC 검증 준비

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10

이 코드는 향후 인증 연결을 위한 검증 경계와 비활성 브라우저 흐름이며 로그인 UI, 토큰 갱신 또는 서버 권한 검증에 연결되어 있지 않다. 따라서 현재 앱에서 로그인 성공이나 Guardian/Visitor 권한을 표시하지 않는다. PC-MOBIUS-INTEGRATION이 issuer·client ID·redirect URI·scope·principal/originator binding 및 grant 계약을 제공해야 실제 연동할 수 있다.

`OidcProvider.discover(trustedIssuer)`는 사전에 신뢰한 HTTPS issuer만 사용하여 discovery를 읽고 문서의 issuer가 정확히 같은지 확인한다. 인증 endpoint, token endpoint, JWKS URI는 HTTPS여야 하며 redirect를 따라가지 않는다. 현재 보수적인 호환 범위는 응답 타입 `code`, ID 토큰 `RS256`, PKCE `S256`를 명시적으로 광고하는 provider와 쿼리 없는 endpoint이다. 응답은 64 KiB로 제한하며 네트워크 작업은 호출자가 백그라운드 스레드에서 수행해야 한다. 개발자가 issuer를 사용자의 임의 입력이나 리다이렉트에서 받아서는 안 된다.

`OidcProvider.verifyIdToken(...)`은 검증된 discovery의 JWKS를 플랫폼 TLS 검증으로 가져온 뒤 서명과 `iss`, 단일 `aud`, 선택된 경우 `azp`, `sub`, `exp`, `iat`, `nbf`, 거래별 nonce를 검증한다. 신뢰되지 않은 다중 audience, 모호한 `kid`, 개인키가 포함된 JWK, 잘못된 key use와 RS256 외 알고리즘은 거부한다. 결과는 검증된 subject만 반환한다. 값이나 토큰을 로그·receipt에 남기면 안 된다. `IdTokenVerifier`는 신뢰된 JWKS를 입력받는 하위 계층으로, 네트워크에서 가져온 임의 JSON을 인증 근거로 직접 전달해서는 안 된다.

표준 참고: [OIDC Core](https://openid.net/specs/openid-connect-core-1_0.html), [OIDC Discovery](https://openid.net/specs/openid-connect-discovery-1_0.html), [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html), [AppAuth Android ID token 처리](https://github.com/openid/AppAuth-Android/blob/master/library/java/net/openid/appauth/IdToken.java). AppAuth의 코드 플로우 ID 토큰 처리 자체는 서명 검증을 하지 않으므로 향후 통합 시 이 검증 경계와 연결해야 한다. 구현은 Nimbus JOSE+JWT 9.47을 사용한다.

검증: `:app:testDebugUnitTest`는 테스트용 RSA 키를 사용하여 정상 서명, 잘못된 서명·키·issuer·audience·azp·nonce·만료와 discovery 오류를 시험한다. 이는 JVM 단위시험이며 실제 공급자, Android 브라우저, 인증서 교체, 프로세스 종료·재개 또는 휴대폰 인증 시험의 증거가 아니다. 공급자 계약이 정해지면 브라우저 흐름의 실기기 통합과 부정 시험, 안전한 토큰 저장·폐기 및 refresh/revoke를 별도로 구현·검증해야 한다.

## 비활성 브라우저 흐름

`OidcBrowserFlow`는 AppAuth 0.11.1의 외부 브라우저 호출과 code/S256 PKCE를 사용한다. 신뢰된 issuer의 discovery 결과와 사전 등록된 client ID를 코드로 제공한 호출자가 `start(requestCode)`를 호출하고 `onActivityResult(requestCode, resultCode, data)`를 전달해야 한다. 현재 앱에는 이 호출자가 없으며 임의 주소나 계정으로 시작하는 UI도 없다. 시험용 redirect는 `org.privacybubble.app:/oauth2redirect`다. 디버그 빌드에만 이 redirect receiver가 등록되며 release 빌드에서는 제거하고 흐름 시작도 거부한다. 이 URI는 운영 등록값이 아니고, custom scheme은 운영체제가 소유권을 검증하지 않으므로 운영에서는 공급자 계약과 검증된 App Links 등을 결정해야 한다.

각 거래의 state·nonce·PKCE verifier는 AppAuth가 생성하며 S256만 허용한다. 결과 URI는 정확한 scheme/path, 중복 없는 state·code 및 요청과 같은 `iss`를 요구한다. 이 보수적인 구현은 RFC 9207의 응답 issuer를 생략하는 공급자를 거부한다. 코드는 리다이렉트를 따르지 않는 HTTPS POST로 한 번 교환하며 응답은 64 KiB, 토큰 수명은 `expires_in` 1초~7일로 제한한다. 서명·issuer·audience·nonce가 검증되기 전에는 세션을 반환하지 않는다. 세션은 메모리에만 있으며 만료 후 접근을 거부하고 `close()`로 참조를 폐기한다. refresh·revoke·권한 매핑 및 영속 저장은 없다.

호출자는 결과를 받은 뒤 또는 Activity 파괴 시 `close()`해야 한다. 화면 회전이나 프로세스 종료 후 거래 상태를 복구하지 않고 취소하므로 새 거래가 필요하다. 오류는 민감한 내용을 반환하지 않는다. 단, AppAuth 0.11.1 자체는 state가 불일치할 때 state 값을 Android WARN 로그에 출력할 수 있다. 실서비스에 연결하기 전 이 로그 문제와 검증된 redirect·신뢰 issuer 등록 정책을 해결해야 한다. JWT/redirect 단위시험 및 APK 빌드만 실행했으며, 브라우저 왕복·인가 코드 교환·서버 인증서·휴대폰 OIDC 시험은 수행하지 않았다.
