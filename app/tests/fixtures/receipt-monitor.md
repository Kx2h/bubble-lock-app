# 합성 결과 자동 조회 fixture

`receipt-monitor.mjs`는 공기계 결과 화면의 최초·동일·변화·실패·복구 안내를 재현하는 HTTP loopback 시험 서버다. 실제 CSE나 사용자·명령을 사용하지 않는다. 기본 포트는 기존 기기 reverse와 같은 8787이며 사용 중인 포트를 덮어쓰지 않는다. 기기와 CSE를 연결하는 중계 프로세스를 먼저 정리한 뒤 실행한다.

```sh
export WORKSPACE=/absolute/path/to/mobius4
export RECEIPT_FIXTURE_DIR="$WORKSPACE/.local/receipt-monitor-fixture"
"$WORKSPACE/.local/node/bin/node" app/tests/fixtures/receipt-monitor.mjs
```

제어 폴더는 workspace `.local` 하위만 허용한다. 처음 실행할 때 `mode`가 없으면 `baseline`을 만들며 기존 모드를 덮어쓰지 않는다. 별도 터미널에서 같은 `RECEIPT_FIXTURE_DIR`을 지정하고 다음 순서로 바꾼다.

```sh
printf 'baseline\n' > "$RECEIPT_FIXTURE_DIR/mode"
# debug 앱의 합성 결과 화면에서 자동 조회 시작: 최초 조회, 다음에는 동일 내용
printf 'changed\n' > "$RECEIPT_FIXTURE_DIR/mode"
# 다음 자동 조회에서 INDETERMINATE → REJECTED 표시 내용 변화
printf 'failure\n' > "$RECEIPT_FIXTURE_DIR/mode"
# 503 응답: 이전 결과 대신 실패 표시
printf 'changed\n' > "$RECEIPT_FIXTURE_DIR/mode"
# 다음 성공 또는 수동 조회에서 복구 안내와 비교 기준 재설정
cat "$RECEIPT_FIXTURE_DIR/fixture-count.json"
```

앱의 자동 조회를 중지하고 12초 뒤에도 요청 수가 같은지 확인할 수 있다. 이미 전송된 요청은 완료될 수 있다. 각 조회의 requestId는 달라지지만 고정된 표시 내용은 그대로여서 요청 ID를 잘못 비교하는 문제도 확인할 수 있다. 알 수 없는 모드나 제어 파일 오류는 503으로 처리한다.

종료는 실행한 프로세스에서 Ctrl-C로 수행한다. 제어 파일과 합성 요청 수 기록은 workspace에 남는다. 기존 reverse를 추가·삭제하지 않으며 기기 시험 후 APK 복원은 별도 절차다. 호스트 시험은 `node --test app/tests/fixtures/receipt-monitor.test.mjs`로 실행한다. 공기계에서 사용한 임시 원본 fixture와 `baseline`·`changed`·`failure` 응답 내용 및 요청 수 기록의 논리는 같으며, 저장소 버전에는 제어 경로 검사와 알 수 없는 모드의 503 처리를 추가했다. [공기계 기록](../../evidence/receipt-monitor-device.md).
