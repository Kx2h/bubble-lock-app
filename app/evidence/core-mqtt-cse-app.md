# MQTT → CORE 브릿지 → 임시 Mobius4 → APP 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계와 debug APK SHA-256 `44a661200c17fd09fd866733ec8d0cc9fb98d2abeafac0f65fd6ab5862ed2b43`로 시험했다. 합성 송신자와 Aedes 1.2.0 임시 broker는 같은 작업 컴퓨터의 loopback에서 실행했다. broker는 인증·TLS·ACL 없이 임의 포트에서만 수신했다. 이 시험은 실제 Pi·BLE·키링, 운영 사용자 인증이나 MQTT 보안을 검증하지 않는다.

MQTT 3.1.1 QoS 1로 `privacy-bubble/gateway/app-isolated/telemetry`에 합성 상태를 전송했다. 실제 CORE `GatewayBridge.start()`의 구독 후 broker 송신과 CORE 수신의 QoS 1을 각각 확인했다. CORE의 HTTP UPDATE는 별도로 200 / oneM2M RSC 2004 및 요청 ID 일치를 확인했고, 같은 `deviceId`·`sequence`를 다시 전송했을 때 CORE가 `DUPLICATE`로 판정하고 UPDATE는 1회에 그쳤다. 이는 해당 프로세스의 시험 결과이며 CORE 재시작 후 중복 방지나 모든 장애에서 exactly-once를 증명하지 않는다.

별도 CSE RETRIEVE는 200 / RSC 2000 및 `state=true`를 확인했다. 기존 `tcp:8787` reverse와 APP 중계를 거친 공기계 화면도 `켜짐`과 bootstrap 표식·실제 관측 여부 미확인을 표시했다 ([화면](core-mqtt-cse-app.png)). `unobserved` 라벨은 이 시험의 state-only UPDATE 후에도 남았으므로 현재 실제 장치 관측 여부를 뜻하지 않는다. 재현 명령과 설치 방법은 [native README](../../app-test-server/native/README.md)에 있다.

생성한 고유 AE만 DELETE 200 / RSC 2002 후 GET 404 / RSC 4004로 삭제를 확인했다. broker, MQTT 클라이언트와 임시 중계는 종료했고 UI dump도 삭제했다. 기존 앱은 데이터 삭제 없이 복원하여 설치 APK SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했으며 기존 `tcp:8787` reverse와 임시 CSE는 유지했다. 시스템·브라우저 인증서 신뢰는 변경하지 않았다.
