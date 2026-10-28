# MQTT 재연결 → CORE 브릿지 → 임시 Mobius4 → APP 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계와 debug APK SHA-256 `ff7ccc87ca197a2b0905e084a2f032a28bc301c2dacf34213777ff2ce41fc175`로 시험했다. 같은 작업 컴퓨터의 loopback에서 Aedes 1.2.0 broker와 합성 송신자를 사용했다. 실제 Pi·BLE·키링이나 운영 사용자 인증을 시험하지 않았다.

고유한 임시 AE와 `bubbleState`를 만들고, 실제 CORE `GatewayBridge`의 MQTT 3.1.1 QoS 1 구독으로 `state=true`를 전송했다. 같은 sequence를 다시 보냈을 때 기존 CORE 인스턴스는 `DUPLICATE`로 처리했고 CSE UPDATE를 반복하지 않았다. 이후 broker를 중단하고 같은 loopback 포트에 새 broker를 시작했다. 동일 CORE 클라이언트의 재연결 후 `GatewayBridge.start()`를 다시 호출했으며 broker에서 QoS 1 재구독을 관찰했다. 새 sequence의 `state=false`를 전송한 뒤 CORE UPDATE 두 건 모두 HTTP 200 / RSC 2004와 일치하는 요청 ID를 독립 확인했다.

CSE RETRIEVE 200 / RSC 2000과 앱 중계 응답은 최종 `false`를 확인했다. 공기계 화면도 `꺼짐`, bootstrap 표식과 실제 관측 여부 미확인을 표시했다 ([화면](core-mqtt-reconnect.png)). 이는 합성 메시지의 CSE readback이며 실제 장치 관측이 아니다. 끊긴 동안에는 메시지를 보내지 않아 오프라인 저장·복구, 프로세스 재시작 후 중복 방지 또는 MQTT TLS·ACL을 검증하지 않았다.

생성한 AE만 DELETE 200 / RSC 2002 후 GET 404 / RSC 4004로 삭제했다. 시험 broker·중계를 종료하고 UI dump를 지웠다. 공기계에는 기존 APK를 데이터 삭제 없이 복원해 설치 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했으며, 기존 `tcp:8787` reverse와 임시 CSE는 유지했다. 기기 및 브라우저 인증서 신뢰는 변경하지 않았다.
