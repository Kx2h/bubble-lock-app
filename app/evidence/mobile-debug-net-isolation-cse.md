# APP debug/release 네트워크 분리 후 실기기 Mobius4 회귀 시험

- ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03 · Test ID: APP-DEBUG-ISOLATION-CSE-01
- 일시: 2026-10-28 현지 시간 · Android 14 / API 34.
- 기존 테스트 앱의 설치 SHA-256 `1047545020454b30a60b157004fb47965a0631957189d05f871e6bf69285ecb0` 확인 후 새 debug APK를 `adb install -r`로 갱신했다. 호스트 APK와 설치 APK SHA-256은 모두 `de26f343d6e5ee4d3133044faab0efa6cda446d58a9de827f28fb7315ae56e94`였다.
- 경로: 공기계 앱 → 기존 Windows ADB reverse → APP 테스트 중계 → 작업 폴더의 실제 임시 Mobius4 상태 자원. 앱은 `꺼짐`, `초기값 · 장치 관측 전`과 요청 ID `app-test-c89d543c-0f6b-4be9-9f20-89a422f589da`를 표시했다. [mobile-debug-net-isolation-cse.png](mobile-debug-net-isolation-cse.png)는 ADB stdout으로 수집한 화면이다.
- CSE 로컬 로그에 같은 요청 ID를 포함한 두 행이 있음을 확인했다. 개발 관리자 ID 및 원본 로그는 민감정보가 포함될 수 있어 저장소에 넣지 않았다.
- 이 값은 실제 키링 관측이 아닌 개발용 초기값이다. 이 시험은 debug 빌드의 기존 읽기 기능 회귀 확인이며, OIDC 로그인·실제 사용자 인증·운영 TLS 또는 release 네트워크 동작의 실기기 증거가 아니다.
- 시험에서 시작한 APP 중계만 종료했으며 CSE, 앱 및 기존 reverse는 유지했다. 앱 전용으로 생성된 기기 파일은 없고 사용자 파일·시스템 설정은 변경하지 않았다.
