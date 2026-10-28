# Privacy Bubble APP

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03, R09, R10, R14, R17

Android 앱과 임시 Mobius4 연동을 개발하는 저장소입니다. 공동 개발 기준은 [PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md](PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md)이며, 원본 팀 저장소는 [tirofina/bubble_lock](https://github.com/tirofina/bubble_lock)입니다. APP 변경은 `feature/app`에서 진행합니다. 이 공개 저장소는 원본 저장소 쓰기 권한을 확보하기 전 APP 작업을 공유하기 위해 만들었습니다.

- [Android 빌드·실기기 설치·정리](app/README.md)
- [요구별 구현 상태와 실제 연동 인수 조건](app/docs/APP_IMPLEMENTATION_STATUS.md)
- [임시 Mobius4 및 격리된 팀 연동 시험](app-test-server/native/README.md)
- [인증된 합성 동의·세션 갱신](app/docs/AUTHENTICATED_CONSENT_POC.md)
- [합성 결과 조회 및 전경 변경 안내](app/docs/SYNTHETIC_RECEIPT_POC.md)
- [실기기 시험 기록](app/evidence/)
- [85초 모바일 합성 통합 시연](app/evidence/mobile-integration-demo.md)

현재 debug 앱은 임시 CSE의 상태, 고정 합성 관측과 결과를 조회합니다. KEYRING 고정 코드의 SQLite outbox·mTLS MQTT·CORE·실제 임시 CSE를 연결한 시험과 Android 공기계 검증을 포함합니다. 시험 앱·포트·CSE 자원의 정리 절차와 결과는 각 기록에 있습니다. 기기의 시험 CA 신뢰는 opt-in 앱에 한정하며 시스템·브라우저 신뢰를 변경하지 않습니다.

운영 로그인·사용자 API·device/household 등록·실제 승인 및 명령·위치·handover·FL·MCP 연결은 아직 완료되지 않았습니다. Guardian/Visitor 화면 선택은 권한을 부여하지 않습니다. 합성 결과와 GPIO readback은 실제 키링 동작이나 물리 진동의 증거가 아니며, 전체 Pi·BLE·키링 경로와 운영 ACP·MQTT ACL은 추가 검증이 필요합니다.

도구와 의존성은 작업 폴더 아래 `.local`에 격리합니다. 비밀키·토큰·운영 credential과 로컬 DB는 Git에 포함하지 않습니다. AppAuth 수정 소스의 출처와 라이선스는 [vendor 안내](app/vendor/appauth/README.md)에 기록했습니다.
