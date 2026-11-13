# Android Native 배포 연결 템플릿

ROLE: APP · DOC-REV: 2.0.0 · 2026-11-14 KST

이 디렉터리는 기존 웹 BFF와 별도의 Native public OIDC client, 그 client의 사용자 token을 받는 정확한 읽기 API 경로를 준비한다. 원격 Keycloak 등록·Nginx 수정·reload는 실행하지 않는다. 현재 서버의 Native client 등록과 공개 API audience는 확인되지 않았으며, 기본 템플릿은 그 값을 채우기 전 검증에 실패한다.

공개 관찰의 issuer는 `https://bubblelock.iptime.org/identity/realms/bubble`, 웹 client는 `bubble-lock-dashboard`다. 웹 BFF는 HttpOnly 세션 cookie와 서버 메모리 token을 사용한다. Native 앱이 웹 client secret, 계정 비밀번호, 브라우저 cookie 또는 Gateway 키를 복사하지 않는다. 제공된 Gateway 개인키는 Pi에만 둔다. 443 웹 인증서는 제공 ZIP의 MQTT CA와 다르므로 웹 인증서의 신뢰 경로·fingerprint를 독립적으로 확인해야 한다.

## 필요한 실제 값

`deployment.template.json`을 workspace `.local/`의 운영 파일로 복사한다. `__REQUIRED_*__` 값은 예시가 아닌 미확정 항목이다. API audience는 현재 integration API의 OIDC verifier 설정과 일치하는 실제 값을 확인한다. 웹 client ID나 Android client ID로 추정하지 않는다. Native client ID는 웹 client와 별도로 등록할 식별자를 정한다. upstream origin, 인증서 SAN과 CA 파일은 실제 서버 배치에서 확인한다.

확인 여부 필드도 실제 조사 후 채운다. `publicCertificateConfirmed`는 신뢰된 경로로 확인한 인증서를 뜻하며, 인터넷에서 방금 받은 자체서명 인증서를 무조건 신뢰했다는 뜻이 아니다. `upstreamVerifiesOidcAndEnrollment`는 upstream이 issuer·서명·audience·scope와 서버 principal 등록을 검증한다는 확인이다. 이 도구는 그 사실을 원격으로 검사하거나 증명하지 않는다.

Keycloak 25 이상에서는 `basic` client scope가 `sub` 같은 필수 mapper를 제공한다. 해당 scope의 **Include in token scope가 꺼져 있음**을 확인해 `basicScopeExcludedFromTokenScope=true`로 기록한다. 앱의 요청과 허용 응답 scope는 `openid offline_access portal:read`이며 `basic`, `roles`, `profile`, `email`, `dashboard:read` 등 여분 scope를 응답에 더하지 않는다. 기존 realm의 공통 `basic` scope나 웹 client를 이 작업에서 무조건 수정하지 않는다. [Keycloak client scopes와 audience](https://www.keycloak.org/docs/latest/server_admin/index.html)

```sh
python3 app/deployment/prepare.py --config "$WORKSPACE/.local/native-deployment/profile.json"
python3 app/deployment/prepare.py \
  --config "$WORKSPACE/.local/native-deployment/profile.json" \
  --workspace-root "$WORKSPACE" \
  --output "$WORKSPACE/.local/native-deployment/prepared"
```

첫 명령은 값과 템플릿 경계만 검증한다. 두 번째는 비어 있는 workspace 하위 디렉터리에 public 설정 파일을 생성한다. 생성 결과는 `locally-validated-not-applied`이며 원격 작업이 아니다. 미확정 placeholder, 비밀 입력 필드, 같은 웹 client 재사용, HTTP downgrade, Nginx 삽입 문자열, 미확인 TLS/audience, 잘못된 경로는 거절한다. `preparation.json`에 실제 입력과 산출물 SHA256 및 남은 운영 검증을 기록한다. 값의 확인 여부는 운영자 입력이므로 실제 서버 상태를 자동 보증하지 않는다.

## Keycloak 등록 순서

1. 실제 API verifier audience와 등록된 사용자 subject·scope를 확인한다. guardian/operator 이름만으로 권한을 부여하지 않는다.
2. `portal-read-scope.json`의 `portal:read` scope와 access-token audience mapper를 검토한다. 같은 이름의 scope가 이미 있으면 덮어쓰지 말고 현재 client 연결과 mapper를 비교한다. mapper는 ID token의 audience를 바꾸지 않는다.
3. `native-client.json`을 별도 public client로 등록한다. Authorization Code만 켜고 PKCE `S256`을 강제한다. Password/Direct Access, Implicit, Service Accounts와 미사용 Device/CIBA grant는 끈다. client secret을 생성하거나 앱에 넣지 않는다.
4. redirect는 정확히 `org.privacybubble.app:/oauth2redirect` 하나만 등록한다. wildcard와 웹 BFF callback 재사용을 허용하지 않는다. 선택 scope는 `offline_access`, `portal:read`이고 기본은 검토한 `basic`만 연결한다. 실제 배포 버전의 client-scope 연결과 RS256 발급을 확인한다.
5. realm의 refresh 회전·재사용 탐지·폐기 정책을 확인한다. 공유 realm의 정책을 이 템플릿이 일괄 변경하지 않는다. 실제 계정의 Code/PKCE·만료·refresh·로그아웃 시험 후 Native 등록 완료로 기록한다.

Custom-scheme redirect는 OS가 도메인 소유권을 검증하는 HTTPS App Link와 다른 경계가 있다. 현재 앱의 정확한 redirect·state·issuer와 PKCE 검증을 함께 유지한다. [RFC 8252](https://datatracker.ietf.org/doc/html/rfc8252), [Keycloak PKCE 설정](https://www.keycloak.org/docs/latest/server_admin/index.html)

## Nginx 읽기 경로

`nginx-http.conf`는 `http {}`에 한 번, `nginx-server.conf`는 기존 `bubblelock.iptime.org` HTTPS server 안에서 검토한다. 완성된 전체 `nginx.conf`를 교체하는 파일이 아니다. 공개 인증서·기존 보안 헤더·다른 경로를 보존한다. 현재의 blanket `/v1/` 차단에서 정확한 `GET /v1/portal/locations`만 예외로 upstream에 전달한다. 나머지 `/v1/`는 공개하지 않고, 이 읽기 예외는 POST/HEAD/다른 방법·query·body를 거절한다. 별도 사설 Gateway ingress와 MQTT 8883은 변경 대상이 아니다.

인증 경계는 upstream OIDC 검증이다. Nginx는 사용자 `Authorization`을 보존하고 cookie·임의 principal·인증서 주장 헤더는 전달하지 않는다. 상위 설정의 proxy client certificate도 빈 값으로 해제해 사용자를 Gateway identity로 바꾸지 않는다. upstream TLS는 CA·SAN/SNI·TLS 1.3으로 검증하고 `proxy_ssl_verify off`를 사용하지 않는다. upstream은 Native 사용자를 mTLS 없이 OIDC로 검증하는 설정이어야 한다. [NGINX proxy TLS와 header 규칙](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)

IP당 5회/초와 burst 10은 시작값이다. 실제 NAT 공유 이용자와 부하를 측정해 조정한다. 신뢰된 socket IP를 사용하며 caller의 사용자 ID나 `X-Forwarded-For`를 rate-limit 신원으로 삼지 않는다. 앞단 proxy가 있다면 운영자가 확인한 real-IP 신뢰 설정을 별도로 검토한다. [NGINX 요청 제한](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)

proxy cache·stale 재시도·응답 buffering을 끈다. 현재 integration API는 `Cache-Control: no-store, private`, `Vary: Authorization`, `X-Content-Type-Options: nosniff`를 제공하므로 이 헤더를 제거하거나 캐시 허용으로 바꾸지 않는다. location에 새 `add_header` 목록을 무턱대고 넣어 기존 서버 HSTS/CSP 등의 상속을 없애지 않는다. 기존 server에 전역 mTLS·웹 SSO `auth_request`·allow/deny가 있으면 해당 Native 예외에 대한 동작을 운영자가 확인해야 한다.

이 도구의 템플릿 검사는 `nginx -t`를 대신하지 않는다. 운영 서버에서 실제 include 위치·모듈·인증서 파일을 확인한 후 `nginx -t`를 통과시키고 owner가 reload한다. 실제 서명 없는 token·다른 audience·다른 subject·scope 누락·POST·query·다른 경로는 차단되고, 등록된 사용자 GET만 현재 lease 범위로 전달되는지 확인한다. `readExpiresAt`은 최대 약 5초이며 앱은 그 뒤 좌표를 지우므로 proxy가 이전 성공 응답을 재사용하면 안 된다.

## 앱 프로필과 검증 상태

`android-profile.properties`는 `mobileApiBaseUrl`, `oidcIssuer`, `oidcClientId`와 `mobileApiContract=portal-central-v1`을 제공한다. 등록이 끝난 public client와 실제 API 신뢰 경로로만 빌드한다. Android에 Gateway 개인키나 웹 client secret을 포함하지 않는다. 기본 미설정 release와 이 운영 프로필을 구분하고 최종 APK/AAB가 바뀌면 SBOM·해시·기기 시험을 다시 만든다.

이 디렉터리에서 실행한 시험은 placeholder 거절·필수 배포 사실·issuer/client 분리·TLS/CA 경로 삽입 방지·잘못된 grant/scope·읽기 proxy 경계·workspace 출력의 로컬 검증이다. 실제 Keycloak import·Nginx reload·원격 계정 인증은 수행하지 않았다.

```sh
TMPDIR="$WORKSPACE/.local/tmp/security" PYTHONDONTWRITEBYTECODE=1 \
  python3 -m unittest discover -s app/deployment -p 'test_*.py' -v
```
