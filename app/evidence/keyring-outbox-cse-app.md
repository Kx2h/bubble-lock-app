# KEYRING outbox → mTLS MQTT → CORE → 임시 Mobius4 → APP 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계와 debug APK SHA-256 `9f80032086b48db6a7f1b79020b5b9a1cc003991d64b11daff288cabeb372a07`로 시험했다. 작업 폴더 아래 분리 worktree의 KEYRING commit `d233c0f7c445be7b7505a69582a8fb0f471c57c2`를 실행 전 확인했다. 관측과 인증서는 합성 시험용이며 실제 Pi·BLE·키링이나 운영 사용자·권한을 사용하지 않았다.

고정 팀 코드의 `Gateway.observation`, SQLite `Store`, `Gateway._outbox_loop`, `TransportHub`를 호출했다. 시험은 Gateway 객체를 부분 구성해 이 메서드들을 실행했으며 전체 daemon을 기동하지 않았다. 전송 연결을 시작하기 전에 observation 및 CORE telemetry 두 항목이 실패 후 재시도 상태로 남았고, 시험 전용 임시 TLS 1.3 mTLS broker에 연결한 뒤 CORE telemetry 항목은 PUBACK 후 제거됐다. 별도의 일반 observation 항목은 시험 환경에 해당 oneM2M 응답과 HTTPS 서버가 없어 미전달 상태로 남았다. broker는 인증서 없는 연결을 거부했고 유효한 임시 인증서를 가진 KEYRING transport와 CORE 클라이언트를 수락했다.

합성 CORE telemetry 한 건의 broker 수신과 CORE UPDATE HTTP 200 / RSC 2004, 요청 ID 일치를 각각 확인했다. 별도 CSE RETRIEVE 200 / RSC 2000 및 APP 중계에서 `state=true`, `observation=unknown`을 확인했고 공기계에도 `켜짐`과 실제 관측 여부 미확인이 표시됐다 ([화면](keyring-outbox-cse-app.png)). PUBACK은 broker 수신만 뜻하며, 별도 readback은 이 합성 시험의 CSE 자원만 확인한다. 실제 Gateway daemon의 전체 실행·BLE·물리 장치·MQTT ACL·운영 신뢰 또는 모든 장애에서의 데이터 복구는 검증하지 않았다.

생성한 고유 시험 AE는 DELETE 200 / RSC 2002 후 GET 404 / RSC 4004로 삭제를 확인했고 시험 broker와 중계를 종료했다. 임시 SQLite 및 인증서·개인키는 작업 폴더의 시험 디렉터리에서 삭제했다. 기존 앱은 데이터 삭제 없이 복원하여 설치 APK SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했으며 기존 `tcp:8787` reverse와 임시 CSE는 유지했다. 시스템·브라우저 인증서 신뢰는 변경하지 않았다. 재현 절차와 강제 종료 시 정리 한계는 [native README](../../app-test-server/native/README.md)에 있다.
