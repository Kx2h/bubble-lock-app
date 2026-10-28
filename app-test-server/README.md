# APP 로컬 Mobius4 테스트 중계

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03, R10

PC-MOBIUS-CORE의 `pc-mobius/contracts/oneM2M-test-contract.v1.json` v1.0.0에 정의된 binarySwitch 상태만 읽는다. 실제 Mobius4가 실행되고 해당 자원이 생성되어야 한다. 인증·승인·명령 API나 운영 서비스가 아니다. 중계는 loopback에만 바인딩하며 관리자 originator는 PC에서만 사용한다. Mobius 응답 오류를 앱의 상태로 바꾸지 않는다.

```sh
node --test app-test-server/server.test.mjs
MOBIUS4_URL=http://127.0.0.1:7599/Mobius MOBIUS4_ORIGINATOR=pb-core-admin-local node app-test-server/server.mjs
# USB 디버깅을 승인한 기기에서 앱의 127.0.0.1:8787을 PC에 연결
adb reverse tcp:8787 tcp:8787
# 테스트 종료 후
adb reverse --remove tcp:8787
```

`GET /api/test/bubble-state`는 `{mode: "test", state: boolean, observation: "unknown", bootstrapLabelPresent: boolean, resourceLastModifiedRaw: string | null, requestId: string}`을 반환한다. `bootstrapLabelPresent`는 자원의 `lbl` 배열에 `unobserved`가 있는지만 뜻한다. CORE의 상태 갱신은 이 라벨을 제거하지 않으므로 라벨 유무로 실제 장치 관측 여부를 판정하지 않고 `observation`은 항상 `unknown`이다. 이전 버전의 `observation: "unobserved"`도 현재 관측 여부가 아니라 라벨 존재만 뜻했다. `resourceLastModifiedRaw`는 CSE가 반환한 `lt` 중 유효한 `YYYYMMDDThhmmss` 원문만 전달하며 없거나 다른 형식·유효하지 않은 날짜면 null이다. 이 원문에 시간대는 포함되지 않으며 자원 변경 시각이지 기기 관측 시각이 아니다. 기존 클라이언트는 추가 필드를 무시할 수 있다. HTTP와 mTLS 모두에서 HTTP 200, oneM2M `X-M2M-RSC: 2000`, 요청과 같은 `X-M2M-RI`, 예상된 자원명 `bubbleState`와 `cnd`를 확인한 후에만 상태를 반환한다. 누락되거나 다른 헤더는 `invalid_mobius_headers`로 실패 처리한다. 연결 실패나 잘못된 응답은 502와 오류 코드를 반환한다. 실제 사용자 인증 및 권한 계약은 PC-MOBIUS-INTEGRATION이 제공할 때 교체한다. 휴대폰 기기 연결, Mobius 서버 및 실제 상태 조회는 별도 실측 receipt가 필요하다.

`MOBIUS4_TEST_AE`로 격리 시험 AE 이름을 바꿀 수 있다. 영문자·숫자·밑줄·하이픈 1~64자만 허용하며 생략하면 `privacy-bubble-test-ae`다. 이는 로컬 시험 자원 선택이며 인증이나 운영 API 설정이 아니다. [CORE 브릿지 연결 시험](native/README.md)을 참고한다.

KEYRING의 격리된 개발 Mobius4 proxy는 TLS 1.3과 client 인증서를 요구한다. 개발 인증서와 키는 Git에 넣지 않고 로컬 파일 경로를 지정한다. 서버 인증서 검증과 hostname 검증은 항상 활성화되며 HTTPS에는 세 파일이 모두 필요하다. proxy는 그 개발 CSE에서만 임시 관리자 originator를 매핑한다.

```sh
export MOBIUS4_URL=https://localhost:8759/Mobius
export TLS_CA_FILE=/absolute/path/to/ca.pem
export TLS_CERT_FILE=/absolute/path/to/client.pem
export TLS_KEY_FILE=/absolute/path/to/client.key
node app-test-server/server.mjs
```

이 인터페이스는 테스트 상태만 표시한다. KEYRING bootstrap이 만든 초기 `false`는 실제 장치 관측을 뜻하지 않는다. 이후 같은 라벨이 남아 있어도 현재 값의 출처나 관측 여부를 판정할 수 없다. HTTPS/HTTP의 응답 본문은 최대 64 KiB이며 timeout, 인증 실패 및 잘못된 응답은 502로 표시한다. 테스트는 임시 인증서를 생성하므로 `openssl`이 필요하다.

실제 Mobius4를 프로젝트 폴더 아래에 격리해 준비하는 절차는 [native/README.md](native/README.md)를 참고한다. 이 native 환경은 HTTP loopback 전용이며 위 KEYRING mTLS 구성과 별개다.
