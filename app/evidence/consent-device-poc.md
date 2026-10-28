# APP 합성 동의·승인·회수 공기계 PoC

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10 · TEST: APP-CONSENT-DEVICE-01 · 2026-10-28 현지 시간.

Android 14/API 34 공기계에서 기존 주 앱 설치본 SHA-256 `fe7ce87ed037c8e6b57ad005f98e91663aa26a9c63a8ae1dc6aadbfb07e43088`을 확인했다. 첫 빌드는 서명 불일치로 Android가 갱신을 거부했고 앱 데이터는 변경되지 않았다. 기존 앱과 같은 서명으로 다시 빌드한 일반 debug APK `9d3cc111f90ccfb5d15acd1bb6e13593957a8d84bdf39da82bcc9dac661f2739`을 설치해 시험했다. 신규 `tcp:18787` reverse만 생성해 작업 폴더의 localhost 합성 서버에 연결했다.

- 정상 시나리오: 가상 요청의 대상·목적·scope·120초 TTL을 화면에서 확인하고 명시적 체크 후 승인했다. 서버 재조회로 `active`를 표시했다 ([consent-normal-active.png](consent-normal-active.png)). 다시 선택해 회수하자 `revoked`가 표시됐다 ([consent-normal-revoked.png](consent-normal-revoked.png)).
- 응답 유실 시나리오: 별도의 `--drop-first-response` 서버를 실행하고 초기 `none`을 조회한 뒤 승인했다. 서버는 승인을 적용하고 응답을 끊었으며 앱은 성공을 추정하지 않고 미확정·수동 재전송 안내를 표시했다 ([consent-drop-unknown.png](consent-drop-unknown.png)). 회전 후에도 미확정이 유지됐다 ([consent-drop-rotated.png](consent-drop-rotated.png)).
- 서버의 유실 직후 상태를 확인한 다음, 기기에서 명시적으로 같은 요청을 재전송하자 앱이 최신 서버 상태 `active`를 표시했다 ([consent-drop-recovered.png](consent-drop-recovered.png)). 전후 서버 조회의 합성 receipt ID와 grantRevision이 동일했고 둘 다 revision 1이었다 ([consent-retry-comparison.json](consent-retry-comparison.json)). 실제 idempotency key 원문은 기록하지 않았다. 서버가 이미 active인 새 key 요청은 거절하고 같은 key의 재전송만 기존 성공 영수증을 반환하는 구현이므로, 이 결과와 클라이언트 코드를 함께 볼 때 같은 작업의 재전송으로 정산된 것으로 판단한다.
- 테스트 담당자가 생성한 tcp:18787 reverse와 두 합성 서버 프로세스는 종료했다. 기존 tcp:8787 reverse와 주 앱을 유지했고, 기기 회전 설정을 기존 `lock 0`으로 되돌렸다. 캡처는 ADB stdout으로 호스트에 직접 저장했다. 기기의 시스템 인증서 신뢰와 기본 브라우저는 변경하지 않았다.

이 시험은 인증 없는 localhost **합성 데이터**를 사용하는 debug 전용 PoC다. 실제 사용자 인증, 운영 consent·grant·ACP, 위치, 권한 회수 또는 기기 명령을 실행하거나 검증하지 않는다. 서버 상태·idempotency 기록은 메모리에서만 유지되며, 앱 프로세스 종료 시 미확정 요청은 복구할 수 없다. 운영 API·idempotency·approval hash 계약은 PC-MOBIUS-INTEGRATION 담당이 확정해야 한다. 호스트 장애·만료 시험은 `app/tests/consent-mock/evidence/`에 별도 기록한다.
