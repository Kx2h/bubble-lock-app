# 합성 인증·consent 결합 시험 서버

ROLE: APP · DOC-REV 2.0.0 · RUBRIC R03/R10. 이 구성은 opt-in 로컬 테스트일 뿐 운영 인증·consent·grant·ACP 서비스가 아니다. 고정 synthetic identity만 허용하며 실제 사용자 또는 장치를 사용하지 않는다. PC-MOBIUS-INTEGRATION의 계약을 대신하지 않는다.

합성 IdP는 `openid offline_access test.consent.read test.consent.write`와 `prompt=consent`로 요청한 경우 `consent-test-api` audience의 RS256 access JWT를 발행한다. 서버는 TLS 1.3, 서명·issuer·audience·subject·만료·발급 시각과 읽기/쓰기 scope를 확인하고, 고정 HTTPS issuer에 인증된 introspection으로 토큰의 활성 여부를 재확인한다. 회수 후 토큰 및 거짓 인증값은 거부되며 issuer가 중단되면 503으로 fail-closed 한다. 기존 idempotency·instance fence는 유지한다. TLS 인증서 검증은 끄지 않는다.

이 시험은 **요청 인가 시점의 확인**만 수행한다. introspection과 mutation 사이에는 회수가 발생할 수 있으며 두 동작은 원자적이지 않다. 실제 grant revoke·dispatch fence나 운영 보안을 증명하지 않는다. 발급·회수·동의 데이터는 메모리 전용이며 재시작 시 사라진다. secret·token·query·요청 본문은 로그에 기록하지 않는다.

```sh
# Node 22, OpenSSL; 작업 폴더 아래 기존 .local/oidc-mock 인증서 사용
node --test app/tests/auth-consent-mock/server.test.mjs
umask 077
mkdir -p "$WORKSPACE/.local/auth-consent"
# 신규 비밀을 필요한 경우에만 만들고 재사용한다. 기존 값을 덮어쓰지 않는다.
if [ ! -e "$WORKSPACE/.local/auth-consent/introspection-secret" ]; then
  openssl rand -hex 32 > "$WORKSPACE/.local/auth-consent/introspection-secret"
fi
export MOCK_INTROSPECTION_SECRET="$(cat "$WORKSPACE/.local/auth-consent/introspection-secret")"
export MOCK_OIDC_CERT="$WORKSPACE/.local/oidc-mock/server.pem"
export MOCK_OIDC_KEY="$WORKSPACE/.local/oidc-mock/server.key"
export MOCK_OIDC_CA="$WORKSPACE/.local/oidc-mock/ca.pem"
# 별도 터미널에서 각각 실행; 첫 프로세스의 issuer는 https://localhost:18443
node app/tests/oidc-mock/server.mjs
node app/tests/auth-consent-mock/server.mjs
```

두 터미널에 동일한 환경변수가 필요하다. 두 서비스 모두 loopback만 수신하며 consent는 `https://localhost:18788/api/mock-consent/v1/`이다. `GET request/grant`는 `test.consent.read`, `POST approve/revoke`는 `test.consent.write`가 필요하다. request 및 grant 본문, `X-Mock-Instance-ID`, `Idempotency-Key`, `X-Correlation-ID`는 [합성 consent 계약](../consent-mock/README.md)을 따른다. Android는 별도의 debug opt-in 환경에서만 해당 테스트 CA를 localhost에 신뢰해야 한다. 시스템·브라우저 CA를 설치하거나 인증 검증을 우회하지 않는다. 종료 후 직접 만든 프로세스와 ADB reverse만 제거한다.

응답 유실 시험은 consent 서버 실행 명령에 `--drop-first-response`를 추가한다. 첫 mutation 처리 후 응답 연결을 끊으며, 같은 idempotency key 재시도와 grant 조회를 host 테스트에서 검증한다. 기본 실행에는 이 옵션이 적용되지 않는다.
