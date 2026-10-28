# APP 실기기 임시 Mobius4 조회 receipt

- ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03, R10
- Test ID: APP-MOBIUS4-DEVICE-01 · 날짜: 2026-10-28 (현지 시간)
- 기기: Android 14, API 34 · 설치된 디버그 APK SHA-256: `41dda402d2fe1292d13cd968ed0628f1c0cf17c3a0e0c914b05fcbeab17802df`
- 경로: 실제 기기 앱 → Windows ADB reverse → APP 중계 → 임시 로컬 Mobius4 CSE HTTP resource `/Mobius/privacy-bubble-test-ae/bubbleState`. 이 시험에서 CSE의 개발 관리자 식별자는 로컬 설정에서만 읽고 기록하지 않았다.
- 기대: 테스트 자원 초기 상태가 `false`이고 label에 `unobserved`가 있으면 앱이 꺼짐·장치 관측 전을 표시한다.
- 실제: 앱의 테스트 상태 조회 버튼을 눌렀을 때 `테스트 상태: 꺼짐`, `초기값 · 장치 관측 전`, 새 요청 ID `app-test-f0e7626a-4b0e-43f8-b70f-e8f30d7ede7a`가 표시되었다. [mobile-mobius4-result.png](mobile-mobius4-result.png)는 앱 화면 캡처다.
- 별도의 직접 CSE GET 검증은 HTTP 200, oneM2M RSC 2000, 요청 및 응답 request ID 일치, `state=false`, `dev-bootstrap` 및 `unobserved` label을 확인했다. [mobile-mobius4-direct.json](mobile-mobius4-direct.json)은 **앱 요청이 아닌 별도 직접 요청**의 receipt다.
- CSE 로컬 로그 `.local/mobius-runtime/logs/mobius.log`에서 앱 요청 ID가 기록된 행 2개를 확인했다 (경로는 작업 폴더 기준). 로그에는 민감정보가 포함될 수 있어 저장소에 첨부하지 않았다.
- 실제 장치 관측은 수행하지 않았다. 이 테스트는 초기 개발 자원의 읽기 경로만 확인하며 OIDC, 승인, ACP, 운영 TLS 또는 키링 연동의 증거가 아니다.

화면 캡처는 `adb exec-out screencap -p`로 호스트에 직접 저장했다. 시험 후 APP 중계만 종료했으며, 기존 CSE와 기기 앱·reverse는 남겼다. 증거 hash는 `mobile-mobius4.SHA256SUMS`에서 검증한다.
