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

`GET /api/test/bubble-state`는 `{mode: "test", state: boolean, requestId: string}`을 반환한다. 연결 실패나 잘못된 응답은 502와 오류 코드를 반환한다. 실제 사용자 인증 및 권한 계약은 PC-MOBIUS-INTEGRATION이 제공할 때 교체한다. 휴대폰 기기 연결, Mobius 서버 및 실제 상태 조회는 별도 실측 receipt가 필요하다.
