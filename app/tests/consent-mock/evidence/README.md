# 합성 consent 서버 호스트 시험

ROLE: APP · DOC-REV: 2.0.0 · TEST: APP-CONSENT-MOCK-HOST-01 · 2026-10-28.

Node 22에서 `node --test app/tests/consent-mock/server.test.mjs`를 실행해 8개 시험이 통과했다. 고정 가상 요청 조회, 목적·범위·TTL·revision 불일치 거부, 승인·회수 및 서버 시각 기준 만료, 동일 idempotency key의 중복과 충돌, 동시 요청, 응답 유실 후 같은 key 재전송 및 과거 영수증 재조회 시 현재 상태 구분을 포함한다. 이 호스트 시험은 합성 메모리 서버만 검증하며 실제 사용자 인증·ACP·grant·기기 명령이나 휴대폰 시험의 증거가 아니다. TAP의 SHA-256은 `SHA256SUMS`에 기록했다.

후속 `APP-CONSENT-INSTANCE-HOST-02` 시험은 `instance-test.tap`에 11개 통과를 기록한다. 정상 조회와 변경 응답의 instance ID, 잘못되거나 오래된 instance 거부, 응답 유실 후 같은 주소·포트의 새 서버에 옛 요청이 적용되지 않는 점을 확인했다. 이 fence는 테스트 서버의 메모리 소실을 감지할 뿐 실제 인증이나 사라진 작업의 결과 복구를 보장하지 않는다.
