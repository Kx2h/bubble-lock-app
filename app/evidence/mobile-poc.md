# APP 실기기 PoC receipt

- ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03, R09, R10
- Test ID: APP-MOCK-DEVICE-01 · 날짜: 2026-10-28 (현지 시간)
- 기기: Android 14, API 34 · Android 디버그 APK SHA-256: `41dda402d2fe1292d13cd968ed0628f1c0cf17c3a0e0c914b05fcbeab17802df`
- 모드: 실제 휴대폰과 Windows ADB reverse, APP 중계, **모의 oneM2M HTTP 응답**. 실제 Mobius4 CSE, 키링 관측, OIDC, 승인 또는 실제 서버 보안 검증이 아니다.
- 모의 입력: `/Mobius/privacy-bubble-test-ae/bubbleState`에 `{"pb:binarySwitch":{"state":false,"lbl":["dev-bootstrap","unobserved"]}}`.

| 동작 | 기대 결과 | 실제 결과 |
|---|---|---|
| 기존 앱·reverse 확인 후 `adb install <debug-apk>` | 새 테스트 앱 설치 | 기존 패키지 없음, 설치 성공. 기기의 설치 APK와 호스트 APK SHA-256 일치 |
| `adb reverse tcp:8787 tcp:8787`, `adb shell am start -n org.privacybubble.app/.MainActivity` | 앱 화면 및 중계 연결 | 실행 성공 |
| 앱에서 테스트 상태 조회 버튼 | 꺼짐과 장치 관측 전 표시 | `테스트 상태: 꺼짐`, `초기값 · 장치 관측 전`, 요청 ID 표시 |
| Guardian에서 Visitor로 화면 전환 | 이전 조회와 요청 ID 유지 | 같은 상태와 요청 ID 유지 |
| `adb shell cmd window user-rotation lock 1` | 회전 후 조회 상태와 요청 ID 유지 | 가로 화면에서 같은 상태와 요청 ID 유지. 기존 `lock 0`으로 복원 |

화면 증거는 `mobile-poc-result.png`, `mobile-poc-visitor.png`, `mobile-poc-rotation.png`이며 해시는 `mobile-poc.SHA256SUMS`에 기록했다. 스크린샷은 `adb exec-out screencap -p`로 호스트에 직접 수집했다. 휴대폰에 별도 화면 캡처 파일을 저장하지 않았다. 모의 서버 및 중계는 시험 종료 후 중지했고, 앱과 reverse는 후속 시험을 위해 남겼다. 이 시험은 직접 ADB로 설치해 도구의 소유 기록이 없다. 제거 시 `app/README.md`의 직접 설치한 PoC 수동 제거 절차를 사용한다.
