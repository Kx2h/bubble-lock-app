# CORE 브릿지 → 임시 Mobius4 → APP 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계와 debug APK SHA-256 `b5c1ab35af353b98e641f4f8ae635917574cdd62c02aa108f374f0cbeae52f1e`로 시험했다. CORE `91a281b`의 `GatewayBridge`에 합성 telemetry를 직접 전달했으며 MQTT broker, 실물 Pi·키링, BLE, 실제 사용자 인증·권한 또는 운영 API를 시험한 것은 아니다.

임시 CSE에 별도의 고유 AE와 `bubbleState`를 생성하고 초기 `false`를 읽었다. CORE 브릿지가 state-only HTTP UPDATE를 보냈고 HTTP 200, oneM2M RSC 2004, 일치하는 요청 ID를 독립 확인했다. 이어 별도 CSE RETRIEVE 200/2000과 APP 중계 조회에서 `true`를 확인했다. 공기계 화면은 `켜짐`, bootstrap 표식 존재와 실제 관측 여부 미확인을 표시했다 ([화면](core-bridge-cse-app.png)). 이때 CSE는 `unobserved` 라벨을 유지했다. 이 결과는 해당 임시 서버에서의 동작이며 라벨을 실제 관측 여부의 근거로 삼지 않는다.

CORE 브릿지의 자체 `APPLIED` 판정은 HTTP 코드만 사용하므로 여기서는 별도의 RSC·요청 ID 검사와 readback을 함께 수행했다. 생성한 AE만 DELETE 200/2002 후 GET 404/4004로 삭제를 확인했고, 기존 bootstrap 자원은 시험 후 별도로 `false`를 반환했다. 기존 앱을 데이터 삭제 없이 복원한 설치 APK SHA-256은 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`이다. 직접 시작한 중계와 UI dump를 제거하고 기존 `tcp:8787` reverse와 CSE는 유지했다. 기기·브라우저 인증서 신뢰는 변경하지 않았다.
