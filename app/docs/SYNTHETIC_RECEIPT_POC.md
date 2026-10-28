# 합성 결과 조회 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 이 화면은 debug APK에서만 열 수 있고 고정 합성 명령·장치의 결과만 조회한다. 운영 로그인·승인·명령 전송 또는 실제 장치 동작이 아니다. `APPLIED`, `REJECTED`, `TIMEOUT`, `INDETERMINATE`를 구분하고 시간 초과나 미확정은 실행 여부를 추정하지 않는다. GPIO readback은 출력 값일 뿐 물리 진동 확인이 아니다. `gateway-estimated` 시각은 Gateway의 추정치이며 정확한 장치 UTC를 뜻하지 않는다. `unavailable` 또는 null은 미확인으로 표시한다.

기존 격리 CSE 시험을 `--mqtt --keyring-outbox --observation-cin --receipt-cin --phone`으로 실행하고 debug 앱의 `합성 결과 조회 화면 열기`에서 조회한다. 기존 `tcp:8787` reverse가 준비돼 있고 PC의 8787 포트가 비어 있어야 한다. KEYRING 고정 소스와 Python·MQTT 의존성 준비 및 정리는 [임시 서버 README](../../app-test-server/native/README.md)를 따른다.

시험 도구는 고정 KEYRING 소스의 `Gateway._receipt`로 `INDETERMINATE` 결과를 합성하고 고유 시험 AE의 receipts container에 직접 저장한다. HTTP/oneM2M 결과·요청 ID와 별도 조회의 내용 일치 후에만 화면 중계를 연다. 실제 KEYRING 명령 처리, receipt 전송 loop, 물리 장치 ACK 또는 운영 승인 API를 실행하지 않는다. 중계는 고정 합성 ID·해시·시험 AE만 허용하며 정상 조회도 서버가 저장한 합성 값일 뿐 실제 기기 상태의 근거가 아니다. 다른 결과 3종은 호스트 fixture로 표시 구분과 경계 검사를 확인한다.

시험 종료 시 Enter를 입력하거나 stdin을 닫으면 고유 AE를 지우고 DELETE·404 결과를 출력한다. 기기 시험 중에는 stdin이 열린 터미널에서 실행해야 한다. stdin이 이미 닫힌 자동 실행은 조회 준비 직후 정리된다. 강제 종료, 생성 결과 유실 또는 소유 확인 실패는 자동 삭제를 보장하지 않으며 출력한 AE 이름으로 수동 검토한다. 실제 API, principal·ACP·receipt 계약 및 오류 회복이 정해지면 별도 운영 연결과 검증이 필요하다.
