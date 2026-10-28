# APP OIDC 검증 준비

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10

이 코드는 향후 인증 연결을 위한 **검증 경계**이며 로그인 UI, 브라우저 리다이렉트, 인가 코드 교환, 토큰 갱신 또는 서버 권한 검증을 구현하지 않는다. 따라서 현재 앱에서 로그인 성공이나 Guardian/Visitor 권한을 표시하지 않는다. PC-MOBIUS-INTEGRATION이 issuer·client ID·redirect URI·scope·principal/originator binding 및 grant 계약을 제공해야 실제 연동할 수 있다.

`OidcProvider.discover(trustedIssuer)`는 사전에 신뢰한 HTTPS issuer만 사용하여 discovery를 읽고 문서의 issuer가 정확히 같은지 확인한다. 인증 endpoint, token endpoint, JWKS URI는 HTTPS여야 하며 redirect를 따라가지 않는다. 현재 보수적인 호환 범위는 응답 타입 `code`, ID 토큰 `RS256`, PKCE `S256`를 명시적으로 광고하는 provider와 쿼리 없는 endpoint이다. 응답은 64 KiB로 제한하며 네트워크 작업은 호출자가 백그라운드 스레드에서 수행해야 한다. 개발자가 issuer를 사용자의 임의 입력이나 리다이렉트에서 받아서는 안 된다.

`OidcProvider.verifyIdToken(...)`은 검증된 discovery의 JWKS를 플랫폼 TLS 검증으로 가져온 뒤 서명과 `iss`, 단일 `aud`, 선택된 경우 `azp`, `sub`, `exp`, `iat`, `nbf`, 거래별 nonce를 검증한다. 신뢰되지 않은 다중 audience, 모호한 `kid`, 개인키가 포함된 JWK, 잘못된 key use와 RS256 외 알고리즘은 거부한다. 결과는 검증된 subject만 반환한다. 값이나 토큰을 로그·receipt에 남기면 안 된다. `IdTokenVerifier`는 신뢰된 JWKS를 입력받는 하위 계층으로, 네트워크에서 가져온 임의 JSON을 인증 근거로 직접 전달해서는 안 된다.

표준 참고: [OIDC Core](https://openid.net/specs/openid-connect-core-1_0.html), [OIDC Discovery](https://openid.net/specs/openid-connect-discovery-1_0.html), [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html), [AppAuth Android ID token 처리](https://github.com/openid/AppAuth-Android/blob/master/library/java/net/openid/appauth/IdToken.java). AppAuth의 코드 플로우 ID 토큰 처리 자체는 서명 검증을 하지 않으므로 향후 통합 시 이 검증 경계와 연결해야 한다. 구현은 Nimbus JOSE+JWT 9.47을 사용한다.

검증: `:app:testDebugUnitTest`는 테스트용 RSA 키를 사용하여 정상 서명, 잘못된 서명·키·issuer·audience·azp·nonce·만료와 discovery 오류를 시험한다. 이는 JVM 단위시험이며 실제 공급자, Android 브라우저, 인증서 교체, 프로세스 종료·재개 또는 휴대폰 인증 시험의 증거가 아니다. 공급자 계약이 정해지면 브라우저 기반 코드+PKCE, 일회성 state/nonce, 정확한 redirect 검증, 안전한 토큰 저장·폐기, refresh/revoke 및 부정 시험을 별도로 구현·검증해야 한다.
