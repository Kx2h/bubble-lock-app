# 인증된 합성 consent 연결 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 이 화면은 테스트용 IdP·별도 합성 에이전트·테스트 서버에서만 동작하는 opt-in debug PoC다. 실제 브라우저, 사용자 인증, 운영 consent/grant/ACP, 위치 또는 기기 제어의 증거가 아니다. 기존 인증 없는 debug consent 화면과 별도이다.

`AuthenticatedConsentActivity`는 고정 합성 issuer/client와 명시적 합성 scope로 OIDC 세션을 얻고, 고정 HTTPS localhost 서버만 호출한다. CA는 opt-in APK의 localhost에만 한정되며 시스템·브라우저 신뢰를 바꾸지 않는다. access token은 메모리와 HTTPS Authorization 헤더에서만 사용하며 파일, Intent, receipt 또는 로그에 넣지 않는다. 서버는 합성 bearer의 issuer·audience·subject·scope 및 수명을 별도로 확인해야 한다. 이 검증은 아직 PC-MOBIUS-INTEGRATION의 운영 인증 계약이 아니다.

화면은 합성 목적·scope·TTL을 보여주고 체크 후에만 승인/회수를 전송한다. POST 전에 합성 미확정 **boolean 표시만** 앱 전용 저장소에 동기 기록한다. token, 개인 정보, 요청 본문, correlation 및 idempotency key는 저장하지 않는다. 실패하거나 응답·후속 조회가 불확실하면 새 변경을 잠그고, 같은 프로세스에 같은 요청이 남아 있을 때만 명시적 재전송을 허용한다. 프로세스 종료 후엔 표시만 복원되므로 이전 요청을 재전송하거나 결과를 복구했다고 주장하지 않는다. 사용자가 경고를 확인한 뒤 합성 표시만 삭제할 수 있으며, 이전 결과는 알 수 없다.

Activity 종료 중 전송 직전의 종료·중단 여부와 세션을 다시 검사하지만, 그 검사 직후 종료되거나 이미 전송된 HTTP 요청이 서버에서 처리되는 것을 취소한다고 보장하지 않는다. 이 경우에도 미확정 표시를 남긴다. 성공 receipt는 서버 instance와 correlation을 확인하고 서버의 현재 상태를 다시 읽어야 표시를 해제한다. 테스트 서버의 휘발성 ledger나 instance ID는 운영 권한 또는 실제 복구 보장이 아니다.

정상·부정·종료/재시작·토큰 만료와 기기 정리 증거는 별도 receipt로 기록한다. 실제 연동 전에는 issuer, client, verified App Links, API의 principal·scope·idempotency·durability 계약, secret 보관 및 운영 테스트가 필요하다.
