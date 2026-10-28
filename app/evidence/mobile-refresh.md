# APP 실기기 상태 조회 시각 시험

- ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03, R10 · Test ID: APP-MOBIUS4-REFRESH-01
- 날짜: 2026-10-28 현지 시간 · Android 14, API 34
- 기존 PoC APK의 SHA-256이 `41dda402d2fe1292d13cd968ed0628f1c0cf17c3a0e0c914b05fcbeab17802df`임을 확인한 후에만 `adb install -r`을 실행했다. 새 APK와 휴대폰 설치본은 모두 `b675b66ea205813877c1ea2c8660a96bef8f6034db4118fba37e2b91f4736e93`이다.
- 경로: 실기기 앱 → 기존 Windows ADB reverse → 변경된 APP 중계 → 작업 폴더의 실제 임시 Mobius4 CSE. 개발 관리자 ID 및 원본 로그는 저장하지 않았다.
- 테스트 상태 조회 결과: `꺼짐`, `초기값 · 장치 관측 전`, 조회 시각 `2026. 10. 28. 오후 7:30:24 (기기 시간)`, `자동 갱신되지 않습니다.` 및 요청 ID `app-test-f5db3580-fe61-4473-baa8-3174a6887965` 표시. CSE 로컬 로그에서도 요청 ID가 2개 행에 기록된 것을 확인했다. 전체 로그는 민감 정보가 포함될 수 있어 저장소에 포함하지 않는다.
- Guardian에서 Visitor로 전환한 후 같은 상태, 조회 시각과 요청 ID가 유지됐다. 앱 내부 역할 화면 선택일 뿐 인증이나 권한 변경은 아니다.
- 이 시험에서 시작한 APP 중계만 종료하고 같은 버튼을 다시 누르자 `조회 실패 · 테스트 프록시와 adb reverse 연결을 확인하세요.`가 표시됐다. 서버와 기존 reverse는 유지했다. 기기 회전 등 시스템 설정은 변경하지 않았다.
- `mobile-refresh-cse.png`는 성공 화면, `mobile-refresh-visitor.png`는 전환 후 조회, `mobile-refresh-visitor-role.png`는 Visitor 선택 화면, `mobile-refresh-failure.png`는 중계 중단 후 실패 화면이다. 캡처는 휴대폰에 파일을 저장하지 않고 ADB stdout으로 호스트에 기록했다.
- `false/unobserved`는 개발용 자원의 초기값이다. 실제 키링 관측, OIDC, 승인, ACP, 운영 TLS 및 운영 알림의 증거가 아니다.
