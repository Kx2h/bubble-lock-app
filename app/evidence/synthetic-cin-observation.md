# 합성 관측 CIN 앱 조회 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계에서 debug APK SHA-256 `408aa5f83393d64bb5834e071c0aef575c6f3a71cd1364fdd1943919f7050ced`로 확인했다. KEYRING 고정 팀 소스의 실제 outbox와 임시 mTLS broker, CORE bridge 및 격리 Mobius4의 합성 관측 CIN을 사용했다. 실물 Pi·BLE·키링이나 운영 사용자·권한 시험이 아니다.

사용자가 앱의 합성 관측 화면을 열고 조회하자 중계가 별도 시험 AE의 `observations/la`를 HTTP 200 / RSC 2000과 일치하는 RI를 확인해 읽었다. 공기계에는 합성 기기, sequence 1, 합성 상태, Gateway 수신 기반 시각, 합성 배터리 90%와 RSSI -50 dBm, 기기 조회 시각이 표시됐다 ([화면](synthetic-cin-observation.png)). 화면은 실제 장치 현재 상태나 위치가 아니며 RSSI는 거리가 아니라고 표시한다. 마지막 저장은 실물 기기 최신 관측을 뜻하지 않는다.

시험 중계를 종료한 뒤 재조회하자 이전 값 대신 조회 실패가 표시됐다 ([화면](synthetic-cin-failure.png)). null 측정값과 잘못된 합성 식별자·RI, 빈 결과는 호스트 시험에서 확인했으며 이 공기계 화면에서 null 값을 별도 재현하지 않았다.

고유 AE는 DELETE 200 / RSC 2002 후 GET 404 / RSC 4004로 삭제를 확인했다. 기존 앱은 데이터 삭제 없이 설치 APK SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`로 복원했고 UI dump를 제거했다. 기존 `tcp:8787` reverse와 임시 CSE는 유지했으며 기기 및 브라우저 인증서 신뢰는 변경하지 않았다.
