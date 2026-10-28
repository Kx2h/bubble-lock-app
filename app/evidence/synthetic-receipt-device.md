# 합성 KEYRING 결과 조회 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계와 debug APK SHA-256 `a414783ba8d2d778be227e0e87e873be09182fad29ea9c8001dce7fb7705ac45`를 사용했다. 고정 KEYRING commit `d233c0f7c445be7b7505a69582a8fb0f471c57c2`의 `Gateway._receipt`로 고정 합성 `INDETERMINATE` 결과를 생성하고, 별도 시험 AE의 receipts container에 직접 저장했다. 실제 기기 명령·승인이나 receipt 전송 루프는 실행하지 않았다.

CSE의 CREATE 및 별도 RETRIEVE에 대해 HTTP/oneM2M RSC·요청 ID와 원래 합성 본문 일치를 확인했다. 앱 메인 화면에서 합성 결과 화면을 열어 `INDETERMINATE · 결과 미확정`, `INVALID_RECEIPT`, 수락·적용 시각과 GPIO readback 미확인, GPIO가 물리 진동 확인이 아니라는 안내를 조회했다 ([화면](synthetic-receipt.png)). 중계를 종료한 후 재조회하면 이전 값 대신 실패를 표시했다 ([화면](synthetic-receipt-failure.png)). APPLIED·REJECTED·TIMEOUT 구분과 Gateway 추정 시각·readback 값은 호스트 fixture에서 시험했으며 이번 공기계 화면에서 별도 재현하지 않았다.

기기 시험 프로세스의 표준입력이 닫혀 정상 종료 신호를 보낼 수 없어 해당 프로세스만 종료한 뒤, 생성한 고유 AE의 이름과 ID를 일치 확인하고 그 AE만 삭제했다. 삭제 응답 본문 파싱 오류로 DELETE 응답 코드는 기록하지 못했지만 이후 독립 GET에서 HTTP 404/RSC 4004와 일치하는 요청 ID를 확인했다. 별도의 호스트 실행에서는 스크립트의 정상 DELETE 200/RSC 2002와 404/RSC 4004가 확인됐다. 기존 앱은 데이터 삭제 없이 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`로 복원했으며 UI dump는 제거했다. 기존 8787 reverse와 임시 CSE는 유지했고 기기·브라우저 인증서 신뢰는 바꾸지 않았다.

이 시험은 합성 결과 표시와 읽기 경로만 확인하며 운영 API·principal/ACP·실제 Pi/BLE/키링의 동작이나 물리 진동 검증을 의미하지 않는다.
