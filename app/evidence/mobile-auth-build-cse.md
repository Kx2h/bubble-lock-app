# APP 신규 APK의 실기기 Mobius4 조회 회귀 시험

- ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03 · Test ID: APP-MOBIUS4-AUTH-BUILD-01
- 일시: 2026-10-28 현지 시간 · 기기 Android 14 / API 34.
- 기존 설치 APK SHA-256 `e03dbc36af622ee798685c4c3db9c52b926de7cfb469ce4f006ed81d3eb6a1e3` 일치 확인 후 신규 APK를 `adb install -r`로 갱신했다. 호스트와 설치 APK의 SHA-256은 모두 `1047545020454b30a60b157004fb47965a0631957189d05f871e6bf69285ecb0`이다.
- 시험 경로: 공기계 앱 → 기존 Windows ADB reverse → APP 테스트 중계 → 작업 폴더의 실제 임시 Mobius4 상태 자원. 앱은 `꺼짐`, `초기값 · 장치 관측 전`과 요청 ID `app-test-1f6a8db0-24fa-4b12-9184-5dc1e6fed4d3`을 표시했다.
- CSE 로컬 로그에서 동일 요청 ID가 2개 행에 기록됨을 확인했다. 로그 및 개발 관리자 ID는 민감정보가 포함될 수 있어 이 저장소에 넣지 않았다. 화면 증거: [mobile-auth-build-cse.png](mobile-auth-build-cse.png).
- 위 상태는 실제 키링 관측이 아닌 테스트 자원의 초기값이다. 이 시험은 기존 읽기 기능의 회귀 시험이며 인증 UI, 로그인, 사용자 인증, 승인 또는 운영 TLS의 성공을 검증하지 않았다.
- 이 시험에서 시작한 APP 중계만 종료했고 CSE, 기기 앱 및 기존 reverse는 유지했다. 화면 캡처는 ADB stdout으로 호스트에 저장했으며 기기에 파일을 남기거나 시스템 설정을 변경하지 않았다.
