# KEYRING projection → MQTT → CORE → 임시 Mobius4 → APP 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계와 debug APK SHA-256 `def21d6503ba8a15cb1a0ec06989812779f87096b5845f26219718927cf7e165`로 시험했다. 작업 폴더 아래 분리 worktree의 KEYRING commit `d233c0f7c445be7b7505a69582a8fb0f471c57c2`를 사용했고 실행 전 해당 Gateway Python 소스가 수정되지 않았음을 확인했다.

합성 관측을 KEYRING의 실제 `validate_observation`과 `Store.observation`으로 시험용 SQLite outbox에 기록했다. battery 또는 RSSI가 null인 두 관측에서는 CORE projection이 생성되지 않았고 중복 관측도 다시 저장되지 않았다. DB를 닫고 다시 열어 대기 projection이 남아 있음을 확인했으며, 후속 관측의 CORE wire sequence가 0에서 1로 이어졌다. 이 값과 관측은 모두 합성이며 실제 측정이 아니다.

Node 시험 송신자는 outbox에서 읽은 projection 하나를 loopback Aedes broker에 MQTT 3.1.1 QoS 1로 보냈다. 실제 CORE `GatewayBridge`가 격리 CSE 자원을 업데이트했고 별도 확인에서 HTTP 200 / oneM2M RSC 2004와 요청 ID가 일치했다. 같은 telemetry를 한 번 더 전송했을 때 해당 CORE 프로세스는 `DUPLICATE`로 처리했고 CSE UPDATE는 총 1회였다. CSE RETRIEVE 200 / RSC 2000과 APP 중계는 `state=true`, `observation=unknown`을 반환했고 공기계 화면은 `켜짐` 및 실제 관측 여부 미확인을 표시했다 ([화면](keyring-projection-cse-app.png)).

이 시험은 팀 코드의 validator·SQLite·projection과 CORE 경로를 연결하지만 전체 KEYRING Gateway daemon·실물 Pi·BLE·키링·배터리나 RSSI를 시험하지 않는다. 실제 MQTT TLS·ACL, 운영 사용자 인증·권한, 오프라인 메시지 복구 및 CORE 재시작 이후 중복 방지도 증명하지 않는다. Node 송신자는 SQLite projection을 전달할 뿐 Gateway의 실제 outbox 전송 루프가 아니다.

생성한 고유 시험 AE는 DELETE 200 / RSC 2002 후 GET 404 / RSC 4004로 삭제를 확인했다. 시험 broker·중계를 종료하고 시험용 화면 및 UI dump를 삭제했다. 기존 앱은 데이터를 지우지 않고 복원하여 설치 APK SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했으며 기존 `tcp:8787` reverse와 임시 CSE는 유지했다. 기기·브라우저 인증서 신뢰는 변경하지 않았다. 재현 절차는 [native README](../../app-test-server/native/README.md)에 기록했다.
