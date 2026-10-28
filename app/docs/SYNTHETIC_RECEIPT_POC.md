# 합성 결과 조회 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 이 화면은 debug APK에서만 열 수 있고 고정 합성 명령·장치의 결과만 조회한다. 운영 로그인·승인·명령 전송 또는 실제 장치 동작이 아니다. `APPLIED`, `REJECTED`, `TIMEOUT`, `INDETERMINATE`를 구분하고 시간 초과나 미확정은 실행 여부를 추정하지 않는다. GPIO readback은 출력 값일 뿐 물리 진동 확인이 아니다. `gateway-estimated` 시각은 Gateway의 추정치이며 정확한 장치 UTC를 뜻하지 않는다. `unavailable` 또는 null은 미확인으로 표시한다.

기존 격리 CSE 시험을 `--mqtt --keyring-outbox --observation-cin --receipt-cin --phone`으로 실행하고 debug 앱의 `합성 결과 조회 화면 열기`에서 조회한다. 기존 `tcp:8787` reverse가 준비돼 있고 PC의 8787 포트가 비어 있어야 한다. KEYRING 고정 소스와 Python·MQTT 의존성 준비 및 정리는 [임시 서버 README](../../app-test-server/native/README.md)를 따른다.

시험 도구는 고정 KEYRING 소스의 `Gateway._receipt`로 `INDETERMINATE` 결과를 합성하고 고유 시험 AE의 receipts container에 직접 저장한다. HTTP/oneM2M 결과·요청 ID와 별도 조회의 내용 일치 후에만 화면 중계를 연다. 실제 KEYRING 명령 처리, receipt 전송 loop, 물리 장치 ACK 또는 운영 승인 API를 실행하지 않는다. 중계는 고정 합성 ID·해시·시험 AE만 허용하며 정상 조회도 서버가 저장한 합성 값일 뿐 실제 기기 상태의 근거가 아니다. 다른 결과 3종은 호스트 fixture로 표시 구분과 경계 검사를 확인한다.

`--receipt-cin` 대신 `--receipt-outbox`를 선택하면 고정 합성 결과를 팀의 실제 SQLite journal과 receipt 전송 loop로 보낸다. `Store.accept`와 `Gateway._finish`로 terminal `INDETERMINATE` 결과만 기록하고, `_outbox_loop`가 `Store.stage_receipts`·`TransportHub.publish`를 거쳐 mTLS MQTT로 전달한다. 격리 CSE의 생성·readback을 검증한 시험 어댑터가 응답한 뒤에만 pending 제거와 journal의 전달 표시를 확인한다. 중계는 이 모드에서만 고정 합성 command ID로 계산한 정확한 outbox CIN 이름을 허용한다. 화면 형식과 합성 범위는 동일하며, 명령 dispatch·인가·BLE·물리 ACK는 호출하지 않는다. 직접 생성 모드는 그대로 사용할 수 있고 두 receipt 옵션은 함께 사용하지 않는다. 재현 명령은 [임시 서버 README](../../app-test-server/native/README.md)의 receipt outbox 절차를 따른다.

고정 APK와 실제 outbox 경로로 공기계 조회를 확인한 내용 및 시험 AE·기존 APK 복원 결과는 [기기 시험 기록](../evidence/keyring-receipt-outbox-device.md)에 있다.

`자동 조회 시작`을 선택하면 전경에서 조회 완료 10초 후에 다시 읽는다. 요청은 한 번에 하나만 수행하며 매 조회 동안 이전 값을 지운다. 최초 조회는 비교 기준만 설정하고, 연속 성공 조회의 검증된 표시 내용이 달라질 때 화면 안에 변화 안내를 표시한다. 요청 ID와 로컬 조회 시각은 비교하지 않는다. 조회 실패 뒤 복구는 새 비교 기준으로 취급하며 놓친 사건이나 순서를 추정하지 않는다. 현재 endpoint에는 CIN 식별자·revision이 없으므로 같은 내용도 같은 사건이라고 보장할 수 없다. 이 안내는 push나 실제 기기 명령 알림이 아니다.

중지 버튼은 다음 자동 조회를 취소하고 이미 진행 중인 읽기는 완료될 수 있다. 화면 이탈 시 자동 조회와 비교 기준을 초기화하며 이전 화면에서 시작된 결과는 복귀 후 사용하지 않는다. 돌아온 뒤에는 직접 다시 조회하거나 자동 조회를 켜야 한다. 프로세스·화면 재생성 후 비교 기록은 남지 않는다.

화면의 변화·실패·복구만 재현할 때는 [loopback fixture](../tests/fixtures/receipt-monitor.md)를 사용할 수 있다. 이 경로는 CSE의 실제 내용 변경과 별도의 합성 UI 시험이다.

시험 종료 시 Enter를 입력하거나 stdin을 닫으면 고유 AE를 지우고 DELETE·404 결과를 출력한다. 기기 시험 중에는 stdin이 열린 터미널에서 실행해야 한다. stdin이 이미 닫힌 자동 실행은 조회 준비 직후 정리된다. 강제 종료, 생성 결과 유실 또는 소유 확인 실패는 자동 삭제를 보장하지 않으며 출력한 AE 이름으로 수동 검토한다. 실제 API, principal·ACP·receipt 계약 및 오류 회복이 정해지면 별도 운영 연결과 검증이 필요하다.
