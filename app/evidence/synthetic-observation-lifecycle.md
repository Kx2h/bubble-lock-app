# 합성 관측 조회 생명주기 호스트 확인

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-29 관측 조회 화면의 요청 상태를 검토하고 수정했다.

기존 화면은 `onStop()`에서 조회 버튼을 다시 켰지만 이미 시작된 요청은 단일 worker에서 계속 실행될 수 있었다. 화면으로 돌아와 버튼을 누르면 이전 요청이 끝나기 전에 새 작업이 executor에 추가됐다. 반복 이탈·복귀로 대기 요청이 쌓일 수 있으며 세대 검사는 결과 표시만 막고 전송 자체를 막지는 않았다.

`SyntheticObservationActivity`가 기존 `TestRefreshState`를 사용하도록 연결했다. 진행 중인 요청은 화면 이탈 후에도 해당 상태로 남고, 다시 전경에 와도 완료 전에는 새 조회를 시작하지 않는다. 이탈 전 요청이 완료되면 진행 상태는 해제하지만 그 응답을 현재 화면의 조회 결과로 사용하지 않는다. 이미 시작된 네트워크 요청의 즉각적인 취소는 보장하지 않는다.

저장소 루트에서 아래 호스트 시험이 통과했다. `TestRefreshStateTest`는 백그라운드 시작 차단, 중복 시작 차단, 이탈 전 응답의 폐기 및 완료 후 새 요청 허용을 확인한다. 같은 실행은 실제 HTTP 느린 응답과 클라이언트 시간 예산도 확인한다. 이는 Activity와 정책을 연결한 코드의 호스트 검증이며 Android lifecycle 자체의 기기 시험은 아니다.

```sh
JAVA_HOME="$WORKSPACE/.local/jdk17" PATH="$WORKSPACE/.local/jdk17/bin:$PATH" app/scripts/test-client.sh
```

당초 공기계가 패턴 잠금 상태라 기기 시험을 보류했다. 이후 사용자가 직접 해제한 뒤 Android 14 공기계에서 debug APK SHA-256 `ad34c07435bdae3ca31a012153e930e01ac081f6c65ce9bfcc4307fbb4301512`를 임시 설치해 시험했다. 관측은 [지연 fixture](../tests/fixtures/observation-lifecycle.mjs)의 고정 합성 응답이며 실제 CSE·Gateway·키링 관측이 아니다. 응답 헤더는 즉시 보내고 본문은 7초 후 전송했다.

측정 구간의 `/count` 시작 값은 2였다. 새 조회 후 홈 화면을 거쳐 최근 앱으로 같은 관측 화면에 복귀하고 조회 버튼을 두 번 눌렀다. 응답이 아직 진행 중인 동안 버튼은 비활성 상태였고 요청 수는 3으로 한 번만 증가했다 ([화면](synthetic-observation-inflight.png)). 응답이 끝난 뒤에도 요청 수 3을 유지했고, 이전 응답은 표시되지 않았으며 버튼만 다시 활성화됐다 ([화면](synthetic-observation-stale-discarded.png)). 이후 사용자의 새 수동 조회는 요청 수를 4로 증가시키고 합성 결과를 정상 표시했다 ([화면](synthetic-observation-fresh-read.png)). 카운터는 로컬 fixture의 수신 횟수이며 패킷 캡처나 실제 CSE의 처리 횟수는 아니다.

재현 시 PC의 8787 포트가 비어 있고 기존 기기 reverse가 준비된 상태에서 `node app/tests/fixtures/observation-lifecycle.mjs`를 실행한다. 앱의 합성 관측 화면에서 조회 후 같은 화면을 이탈·복귀하고, 7초 경과 전후의 버튼·결과 및 호스트 `GET http://127.0.0.1:8787/count`를 확인한다. 화면을 다시 생성하면 동일 Activity의 복귀 시험이 아니므로 최근 앱을 통해 복귀해야 한다.

시험 fixture만 종료하고 UI dump를 삭제한 뒤 기존 APK를 데이터 삭제 없이 복원하여 설치본 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했다. 기존 `tcp:8787` reverse와 임시 CSE는 유지했고 8787 listener는 종료됐다. `stay_on_while_plugged_in`은 이전과 동일한 0이며, 시스템·브라우저 인증서 신뢰는 변경하지 않았다.
