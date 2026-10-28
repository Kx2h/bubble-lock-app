# KEYRING 원본 관측 CIN 및 APP 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계의 기본 debug APK SHA-256 `b74375d141481b4803d38639c7ded1440105dddc82bfb69b37119036f92da18e`와 고정 KEYRING commit `d233c0f7c445be7b7505a69582a8fb0f471c57c2`를 사용했다. 관측과 인증서는 합성 시험용이며 실제 Pi·BLE·키링·사용자 또는 운영 권한을 사용하지 않았다.

별도 AE에 `bubbleState`와 `observations` container를 생성했다. 팀 코드의 `Gateway.observation`, SQLite outbox 루프 및 `TransportHub`를 실행했고 연결 전 보류된 항목이 남는 것을 확인했다. mTLS broker의 임시 oneM2M MQTT→HTTP 시험 어댑터가 원본 관측의 CIN CREATE를 CSE에 전달했다. HTTP 201/RSC 2001과 요청 ID, 별도 RETRIEVE의 HTTP 200/RSC 2000과 요청 ID, CIN 이름과 본문 일치를 확인한 뒤에만 MQTT 성공 응답을 보냈고, Gateway가 응답을 수신한 뒤 해당 outbox 항목을 제거했다. 인증서가 없는 broker 접속은 거부됐다.

별도 CORE telemetry는 MQTT PUBACK과 CORE HTTP UPDATE 200/RSC 2004 및 요청 ID를 확인했고, CSE state readback과 APP 중계는 `true`/`observation=unknown`을 반환했다. 공기계 화면은 테스트 상태 `켜짐`과 실제 관측 여부 미확인을 표시했다 ([화면](keyring-observation-cin.png)). 앱이 원본 CIN을 직접 읽거나 표시한 시험은 아니다.

시험 브릿지는 Mobius native MQTT binding이나 운영 중계가 아니며, 전체 Gateway daemon, 물리 장치, MQTT ACL과 운영 인증은 검증하지 않았다. CREATE나 readback 확인 전 연결이 끊긴 뒤의 CIN 충돌 복구도 구현·검증하지 않았다. 부정 host 시험에서 잘못된 RSC·요청 ID·본문은 성공 응답을 만들지 않았다.

생성한 시험 AE만 DELETE 200/RSC 2002 후 GET 404/RSC 4004로 삭제했다. 시험 broker와 중계 및 UI dump를 정리하고 기존 앱을 데이터 삭제 없이 복원해 설치 APK SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했다. 기존 `tcp:8787` reverse와 임시 CSE는 유지했고 기기·브라우저 인증서 신뢰는 변경하지 않았다.
