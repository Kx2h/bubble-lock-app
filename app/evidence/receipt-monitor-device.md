# 합성 결과 전경 자동 조회 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-29 Android 14 공기계에서 debug APK SHA-256 `ad34c07435bdae3ca31a012153e930e01ac081f6c65ce9bfcc4307fbb4301512`로 시험했다. 화면 내 안내와 전경 반복 조회의 시험이며 실제 명령·기기 동작·사용자 권한이나 push 알림을 사용하지 않았다.

기존 KEYRING·CORE·격리 CSE harness의 호스트 조회가 성공한 뒤 고유 시험 AE `app-bridge-249790eeb1f64f85`는 DELETE 200/RSC 2002 및 GET 404/RSC 4004와 요청 ID 일치를 확인하고 정리했다. 이때 기기 패턴 잠금 때문에 화면 조회는 수행하지 않았다. 사용자가 잠금을 해제한 뒤 아래 화면 시험은 별도 loopback HTTP fixture와 기존 `tcp:8787` reverse로 수행했다. 따라서 화면 시험은 CSE 내용 변화를 직접 재현한 증거가 아니다.

fixture는 `/api/test/receipt`에 고정 합성 ID·미확인 시각/readback과 `INDETERMINATE` 또는 `REJECTED` 값을 반환했다. `requestId`는 매 요청 바뀌고, 실패 모드는 503을 반환했다. 로컬 제어 파일만으로 응답 모드를 바꿨으며 실제 외부 서비스는 사용하지 않았다.

- 자동 조회 시작 뒤 첫 성공은 비교 기준 설정 안내였고 변경 안내가 아니었다 ([화면](receipt-monitor-first.png)).
- 같은 내용의 반복 성공은 표시 내용 변화 없음으로 구분됐다. 요청 ID와 기기 조회 시각은 바뀌어도 변경으로 판정하지 않았다 ([화면](receipt-monitor-same.png)).
- 연속 성공 조회 사이에서 표시 내용을 바꾸자 화면에 합성 표시 내용 변화 안내가 나왔다 ([화면](receipt-monitor-changed.png)). 이는 새로운 사건이나 시간순 진척을 뜻하지 않는다.
- 503 응답 뒤에는 과거 값 대신 조회 실패가 표시됐다 ([화면](receipt-monitor-failure.png)). 정상 응답으로 복구한 뒤 사용자가 조회를 눌렀을 때는 새 비교 기준을 설정했다고 표시했고 변화로 추정하지 않았다 ([화면](receipt-monitor-recovered.png)).
- 자동 조회 중지 후 12초 동안 fixture 요청 수는 19로 유지됐다 ([화면](receipt-monitor-stopped.png)). 이미 진행 중인 읽기까지 즉시 취소한다는 의미는 아니다.
- 자동 조회를 다시 켜고 홈 화면으로 나갔다가 메인 화면에서 결과 화면을 다시 열었을 때 자동 조회는 꺼져 있었고 조회 전 상태였다 ([화면](receipt-monitor-background.png)). 이 경로는 동일 Activity 인스턴스 복귀를 특정하지 않는다. 빠른 이탈·재진입 중 늦은 응답 무시와 요청 중복 방지는 호스트 상태 시험으로 확인했다.

시험 fixture 프로세스를 종료하고 기기의 UI dump를 제거한 뒤 기존 APK를 데이터 삭제 없이 복원해 설치 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했다. 시험 중 화면을 유지하기 위해 임시 설정한 `stay_on_while_plugged_in`은 원래 값 `0`으로 복원했다. 기존 `tcp:8787` reverse와 임시 CSE는 유지했고 시스템·브라우저 인증서 신뢰는 변경하지 않았다. fixture 코드와 제어 기록은 workspace `.local/receipt-monitor-device/`에 있다.
