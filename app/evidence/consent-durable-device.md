# 합성 동의 미확정 요청 프로세스 복구 실기기 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10 · TEST: APP-CONSENT-DURABLE-DEVICE-01 · 2026-10-28 현지 시간.

Android 14/API 34 공기계에서 기존 주 앱 APK SHA-256 `9d3cc111f90ccfb5d15acd1bb6e13593957a8d84bdf39da82bcc9dac661f2739`, 신규 debug APK SHA-256 `44f8aaa71f0c567662001143a9d853d414f4b946eee0846ae0a688ff5976736a`, 같은 debug 서명 및 tcp:18787 매핑 부재를 확인했다. 앱 데이터를 보존하여 갱신하고 이 시험의 18787 reverse만 만들었다.

- 같은 서버: 서버가 합성 승인을 처리한 후 첫 응답을 끊어 앱이 미확정 상태를 표시했다 ([화면](consent-restart-unknown.png)). 서버의 상태는 `active`, revision 1이었다. `adb shell am force-stop` 후 앱을 재실행하자 저장된 미확정 요청이 복원되고 다른 변경이 잠겼다 ([화면](consent-process-restored.png)). 사용자가 합성 선택 체크 후 동일 요청을 재전송하자 `active`가 표시됐다 ([화면](consent-process-recovered.png)).
- 같은 서버의 두 승인 POST에 대해 **idempotency key의 SHA-256 비교 결과만** 확인했으며, 동일했다. 서버 instance, receipt ID 및 grant revision도 전후 동일했다. 원문 key와 body는 증거에 기록하지 않았다. 비교 결과는 [consent-process-recovery.json](consent-process-recovery.json)이다.
- 다른 서버: 새 서버 A에서 응답을 유실한 합성 승인을 만든 뒤 앱을 force-stop하고 서버를 B로 교체했다. A는 `active`, B는 `none`, instance는 서로 달랐다. 복원 요청의 재전송을 누르면 앱은 서버 재시작으로 재전송할 수 없다고 표시하고 승인·회수·재전송을 차단했다 ([화면](consent-instance-blocked.png)). B에서 mutation POST는 0건이고 grant는 여전히 `none`이었다 ([비교 결과](consent-instance-blocked.json)).
- 차단된 합성 보류 기록 삭제 버튼은 이전 결과를 확인할 수 없다는 별도 확인창을 표시했다. 명시적 확인 뒤 합성 보류 기록만 삭제됐고 ([화면](consent-pending-discarded.png)), 다른 앱 preference 파일 한 개의 SHA-256은 전후 같았다. 앱을 다시 force-stop·재실행해 미조회 상태로 시작하고 B에서 `none`을 읽었다 ([화면](consent-discard-restart.png)). 전체 앱 데이터 삭제는 하지 않았다.
- 테스트 종료 후 직접 만든 서버와 `tcp:18787` reverse만 제거했으며, 기존 `tcp:8787` reverse와 앱을 유지했다. 화면은 ADB stdout으로 호스트에 직접 저장했고, 기기의 시스템 인증서 신뢰·기본 브라우저·회전 설정은 변경하지 않았다.

이 시험은 인증 없는 localhost **합성** PoC이며 실제 사용자, 운영 consent/grant/ACP 또는 실제 권한 회수의 증거가 아니다. 서버 instance ID는 테스트용 재시작 식별자이며 인증 수단이나 영속 서버 ledger의 대체가 아니다. 증거 해시는 `consent-durable-device.SHA256SUMS`에 기록한다.
