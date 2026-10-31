# APP OIDC 검증 경계

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10

제품 앱은 `MobileSignInActivity`의 브라우저 로그인과 `MobileTokenManager`·`MobileSessionStore`의 세션 경로를 사용한다. 이 문서는 제품과 공통으로 사용하는 검증 경계, 그리고 기존 `OidcBrowserFlow`·합성 시험의 범위를 함께 기록한다. 배포 프로필·빌드·현재 기기 검증 상태는 [앱 안내](../README.md)를 기준으로 확인한다. 실제 운영 IdP·계정의 전체 로그인과 서버 principal/권한 provisioning은 아직 검증하지 않았다.

## 제품 로그인·세션

- issuer·client ID·API origin은 신뢰된 빌드 프로필로 제공한다. 일반 설정에서 임의 issuer/client나 bearer를 입력하지 않는다. 로그인은 외부 브라우저의 Authorization Code + PKCE S256을 사용하고 `openid offline_access location:read`를 요청한다.
- [MobileSignInActivity](../app/src/main/java/org/privacybubble/app/auth/MobileSignInActivity.java)는 state·issuer·redirect·code를 확인하고 code를 한 번 교환한 뒤 ID token을 검증한다. `verifyIdentityToken`의 검증 결과와 identity 맥락을 세션에 연결한다. access token의 실제 `location:read` 권한과 principal binding은 [위치 API](../../pc-mobius/mobile-api/README.md)가 별도로 검증한다.
- [MobileSessionStore](../app/src/main/java/org/privacybubble/app/session/MobileSessionStore.java)는 access/refresh token·subject·identity 맥락을 Android Keystore 비추출 키의 AES-GCM으로 저장한다. token을 일반 로그나 UI saved state에 넣지 않는다. 배포 프로필과 일치하지 않거나 복호화할 수 없는 저장값은 세션으로 사용하지 않는다.
- [MobileTokenManager](../app/src/main/java/org/privacybubble/app/auth/MobileTokenManager.java)는 worker에서 refresh를 한 번 수행한다. refresh credential은 네트워크 요청 전에 내구성 있게 소비한다. 새 ID token이 있으면 최초 검증 identity의 issuer·subject·audience·azp·auth_time·nonce 맥락과 발급 시각을 확인한다. 응답 유실·검증 실패 시 이전 credential을 다시 전송하지 않고 재로그인을 요구한다. session epoch·origin 검사는 로그아웃·서비스 변경 후 늦게 도착한 인증 응답의 저장을 막는다.
- 제품 token 수명은 `expires_in` 30초~7일의 정수에 한정한다. 기본 미설정 release에서는 callback receiver가 제거된다. issuer/client/API 프로필을 모두 고정한 release에 production-auth manifest를 적용한다. 현재 callback은 `org.privacybubble.app:/oauth2redirect`이며 공급자의 client/redirect 등록과 실제 기기 호환성을 배포에서 확인해야 한다. custom scheme의 소유권을 OS가 검증한 것으로 취급하지 않는다.

## 공통 discovery·ID token 검증

`OidcProvider.discover(trustedIssuer)`는 사전에 신뢰한 HTTPS issuer만 사용하여 discovery를 읽고 문서의 issuer가 정확히 같은지 확인한다. 인증 endpoint, token endpoint, JWKS URI는 HTTPS여야 하며 redirect를 따라가지 않는다. 현재 보수적인 호환 범위는 응답 타입 `code`, ID 토큰 `RS256`, PKCE `S256`를 명시적으로 광고하는 provider와 쿼리 없는 endpoint이다. 응답은 64 KiB로 제한하며 네트워크 작업은 호출자가 백그라운드 스레드에서 수행해야 한다. 개발자가 issuer를 사용자의 임의 입력이나 리다이렉트에서 받아서는 안 된다.

앱이 직접 수행하는 discovery·JWKS 조회와 authorization code·refresh token 교환은 공통 TLS 1.3 socket factory를 사용하며, 이를 지원하지 않는 기기나 서버와의 연결은 실패한다. 현재 Android 최소 버전은 26이므로 일부 기기에서 사용할 수 없으며 낮은 TLS 버전으로 자동 대체하지 않는다. 이 설정은 외부 브라우저의 TLS 협상을 제어하지 않는다. factory와 hostname verifier는 프로세스의 HTTPS 기본값을 사용하므로 다른 코드가 해당 전역 기본값을 변경하면 플랫폼 기본 신뢰 검증을 보장하지 않는다. 시스템 시계나 브라우저·서버 정책의 검증은 별도 범위다.

`OidcProvider.verifyIdToken(...)`은 검증된 discovery의 JWKS를 플랫폼 TLS 검증으로 가져온 뒤 서명과 `iss`, 단일 `aud`, 선택된 경우 `azp`, `sub`, `exp`, `iat`, `nbf`, 거래별 nonce를 검증한다. `sub`는 대소문자를 구분하는 최대 255 ASCII 문자로 제한하며 초기 인증과 갱신에 동일한 규칙을 적용한다. 신뢰되지 않은 다중 audience, 모호한 `kid`, 개인키가 포함된 JWK, 잘못된 key use와 RS256 외 알고리즘은 거부한다. 공개 `verifyIdToken` helper는 검증된 subject를 반환한다. 제품의 `verifyIdentityToken`은 refresh 연속성 검증에 필요한 검증 identity를 반환하며 그 맥락은 암호화 저장소에만 직렬화한다. 값이나 토큰을 로그·receipt에 남기면 안 된다. `IdTokenVerifier`는 신뢰된 JWKS를 입력받는 하위 계층으로, 네트워크에서 가져온 임의 JSON을 인증 근거로 직접 전달해서는 안 된다.

공개 `verifyIdToken` 경로는 JWKS 수신 후 시스템 시각으로 토큰을 검증한다. 기존 4인자 호출은 호환을 위해 유지하지만 전달된 시각은 무시하고, 새 코드는 3인자 호출을 사용한다. 따라서 호출자가 과거나 미래 시각을 지정해 검증을 우회할 수 없지만, 시스템 시각 자체가 잘못된 상황은 별도 신뢰 시간 대책이 필요하다. 하위 `IdTokenVerifier`는 명시적으로 전달된 시각을 사용하므로 직접 호출자는 검증 시점의 시각을 제공해야 한다.

표준 참고: [OIDC Core](https://openid.net/specs/openid-connect-core-1_0.html), [OIDC Discovery](https://openid.net/specs/openid-connect-discovery-1_0.html), [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html), [AppAuth Android ID token 처리](https://github.com/openid/AppAuth-Android/blob/master/library/java/net/openid/appauth/IdToken.java). AppAuth의 코드 플로우 ID 토큰 처리 자체는 서명 검증을 하지 않는다. 제품 code 교환 결과는 `OidcProvider.verifyIdentityToken`을 거쳐 서명을 확인한 뒤 세션으로 저장한다. 구현은 Nimbus JOSE+JWT 9.47을 사용한다.

검증: `:app:testDebugUnitTest`는 테스트용 RSA 키를 사용하여 정상 서명, 잘못된 서명·키·issuer·audience·azp·nonce·만료와 discovery 오류를 시험한다. 이는 JVM 단위시험이며 실제 공급자, Android 브라우저, 인증서 교체, 프로세스 종료·재개 또는 휴대폰 인증 시험의 증거가 아니다. Keystore 저장·폐기와 제품 refresh 경로는 위 소스에 구현되어 있다. 실제 공급자의 브라우저 흐름·호환성·부정 응답·만료·revoke 및 프로세스 복원은 운영 프로필과 기기로 별도 검증해야 한다.

## 기존 OidcBrowserFlow·합성 시험

`OidcBrowserFlow`는 AppAuth 0.11.1의 외부 브라우저 호출과 code/S256 PKCE를 사용한다. 신뢰된 issuer의 discovery 결과와 사전 등록된 client ID를 코드로 제공한 호출자가 `start(requestCode)`를 호출하고 `onActivityResult(requestCode, resultCode, data)`를 전달해야 한다. 이 클래스의 호출자는 별도 opt-in debug 빌드의 합성 시험 Activity이며 localhost 테스트 공급자를 사용한다. 임의 주소나 계정으로 시작할 수 없다. 이 기존 클래스의 `start`는 release에서 거부된다. 제품 UI는 위의 `MobileSignInActivity`를 사용한다. 합성 시험의 redirect는 `org.privacybubble.app:/oauth2redirect`다. 당시 합성 기기 시험은 운영 공급자의 redirect 등록이나 앱 소유 검증을 증명하지 않는다. 기본 release와 설정된 제품 release의 callback 구성은 위 제품 프로필 정책을 따른다.

각 거래의 state·nonce·PKCE verifier는 AppAuth가 생성하며 S256만 허용한다. 결과 URI는 정확한 scheme/path, 중복 없는 state·code 및 요청과 같은 `iss`를 요구한다. 이 보수적인 구현은 RFC 9207의 응답 issuer를 생략하는 공급자를 거부한다. 코드는 리다이렉트를 따르지 않는 HTTPS POST로 한 번 교환하며 응답은 64 KiB, 토큰 수명은 `expires_in` 1초~7일로 제한한다. 서명·issuer·audience·nonce가 검증되기 전에는 세션을 반환하지 않는다. 세션은 메모리에만 있으며 만료 후 접근을 거부하고 `close()`로 참조를 폐기한다. 합성 opt-in 시험에서만 메모리 refresh 회전을 수행하며 revoke·권한 매핑 및 영속 저장은 없다.

초기 토큰 응답의 `scope`가 있으면 요청한 범위와 정확히 일치해야 한다. 일반 흐름은 `openid`만 허용하고 합성 흐름은 각 시험 범위와 일치해야 하며, 범위의 추가·누락·중복과 잘못된 타입을 거부한다. `scope` 생략은 요청 범위와 동일하다는 OAuth 토큰 응답 의미로 취급한다. 이 검사는 access token의 실제 권한을 검증하지 않으며 API 서버 인가는 별도로 필요하다.

초기 또는 갱신 응답의 ID 토큰에 `at_hash`가 있으면 RS256의 SHA-256 절반값으로 해당 응답의 access token을 대조하고, 누락된 access token·잘못된 타입·불일치를 거부한다. 이 검사는 access token 자체의 서명이나 서버 권한을 검증하지 않는다. access token을 받지 않는 공개 `verifyIdToken(...)` 경로는 `at_hash`가 포함된 ID 토큰을 확인할 수 없어 거부한다.

호출자는 결과를 받은 뒤 또는 Activity 파괴 시 `close()`해야 한다. 화면 회전이나 프로세스 종료 후 거래 상태를 복구하지 않고 취소하므로 새 거래가 필요하다. 오류는 민감한 내용을 반환하지 않는다. 원본 AppAuth 0.11.1은 state 불일치 시 값을 WARN 로그에 출력할 수 있어, 고정된 소스 모듈의 해당 로그 인자를 제거하고 합성 기기 시험으로 확인했다. 다른 로그 전체의 안전성을 보증하지 않는다. 제품 배포에서는 등록 redirect와 신뢰 issuer 정책을 실제 공급자·계정으로 검증해야 한다. [패치 근거](APPAUTH_STATE_LOG_ASSESSMENT.md). 합성 Android 시험의 결과와 한계는 별도 evidence에 기록하며, 실제 외부 브라우저·실제 IdP 또는 운영 인증을 뜻하지 않는다.

### Opt-in 합성 시험 빌드

`-PoidcTestCa=<workspace>/.local/oidc-mock/ca.pem`을 명시한 debug 빌드에만 localhost 전용 테스트 CA와 `OidcSyntheticActivity`가 포함된다. 앱은 이 시험에서 고정된 합성 issuer·client만 사용하고, 별도 UID의 `org.privacybubble.oidcsurrogate` 패키지를 앱과 동일한 서명 인증서인 경우에만 선택한다. 이는 합성 테스트 에이전트로, 일반 브라우저나 실제 사용자 인증의 증거가 아니다. 입력된 CA 경로는 작업 폴더의 고정 파일과 일치해야 한다. 기본 debug에는 이 CA와 Activity가 없고, release는 opt-in 설정 여부와 관계없이 테스트 Activity와 CA를 포함하지 않으며 `OIDC_TEST_ENABLED=false`다. 기존 네트워크 정책의 debug 전용 127.0.0.1 HTTP 예외는 유지한다. 시험 후 CA 포함 APK를 기본 debug APK로 교체하고 임시 에이전트와 18443 포트를 정리한다.


### 합성 세션 갱신 경계

합성 opt-in 거래만 `openid offline_access`와 `prompt=consent`를 요청한다. 초기 ID 토큰의 서명·issuer·audience·nonce로 확인한 subject를 세션에 연결하고, 갱신은 같은 discovery에서 얻은 HTTPS token endpoint와 client ID로만 보낸다. ID 토큰이 없는 갱신 응답의 subject 연속성은 합성 서버가 같은 grant에 refresh token을 묶는다는 전제에 의존하며, 클라이언트가 새 subject를 독립적으로 검증한 것은 아니다. 갱신 응답에 ID 토큰이 있으면 서명과 최초 issuer·subject·audience를 검증하고, nonce가 있다면 최초 거래와 같은 값이어야 한다. 초기 토큰에 없던 auth_time을 새로 제시하거나 azp의 존재 여부가 바뀌는 경우, 최초 JWT 재사용 및 발급 시각 불일치는 보수적으로 거부한다.

이 공개 클라이언트 시험은 매 갱신에서 새로운 refresh token을 요구하며, 동일 토큰·누락·변조 응답, 통신 실패나 시간 초과 등 결과가 불확실한 경우 기존 access/refresh 참조를 모두 폐기한다. 예전 refresh token은 재시도하지 않는다. 한 세션의 동시 갱신은 거부하고, 종료 중 도착한 결과는 사용하지 않는다. access token이 만료된 뒤에도 서버가 살아 있는 refresh grant를 승인하면 갱신할 수 있다. 토큰과 subject는 메모리에만 두고 로그·Intent·파일에 저장하지 않는다. `close()`는 결과 전달과 참조를 폐기하지만 이미 전송된 네트워크 요청을 서버에서 취소하거나 토큰을 revoke하지 않으며, JVM의 immutable 문자열 메모리를 즉시 지울 수 있다고 보장하지 않는다.

이 정책은 합성 회전 서버와 [RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14)의 공개 클라이언트 재사용 탐지 모델에 맞춘 제한적인 시험 구현이다. 이 합성 `OidcBrowserFlow` 세션에는 OS 보호 저장·프로세스 복원·제품 UI가 없다. 제품 세션은 위의 Keystore 저장·단일 refresh 경로를 사용한다. 실제 공급자의 sender-constrained 토큰·범위 축소·ID token 갱신 호환성·refresh 만료/revoke와 전체 프로세스 복원은 실제 배포에서 검증할 항목으로 남아 있다. 합성 시험 결과를 실제 사용자 인증 성공으로 간주하지 않는다.
