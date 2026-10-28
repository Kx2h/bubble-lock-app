# 실제 receipt outbox 합성 결과 공기계 조회

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-29 Android 14 공기계에서 고정 debug APK SHA-256 `ad34c07435bdae3ca31a012153e930e01ac081f6c65ce9bfcc4307fbb4301512`로 시험했다. [호스트 연동 기록](../../app-test-server/native/evidence/keyring-receipt-outbox.md)의 실제 KEYRING SQLite journal·outbox·MQTT 전송 경로와 격리 Mobius4 CSE를 사용했다. 입력은 고정 합성 receipt이며 실제 명령 dispatch·인가·BLE·기기 동작은 호출하지 않았다.

```sh
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring-outbox --observation-cin --receipt-outbox --phone
```

고유 시험 AE는 `app-bridge-432b220e128c4796`이었다. 팀의 `Store.accept`와 `Gateway._finish`로 고정 `INDETERMINATE` 결과를 기록한 뒤 실제 `_outbox_loop`가 receipt CIN을 전송했다. 전송 전 SQLite 재열기에도 receipt journal과 pending 항목이 유지됐고, 테스트 어댑터의 CSE CREATE HTTP 201/RSC 2001 및 별도 readback HTTP 200/RSC 2000의 요청 ID·내용 일치 뒤에만 MQTT 성공 응답을 전달했다. 이후 receipt pending 제거와 journal의 `receipt_delivered=1`을 확인했다. 명령 토픽 ingress는 0이었다. 임시 MQTT 테스트 CA는 호스트 전송용이며 기기 신뢰 저장소에는 설치하지 않았다.

기존 `tcp:8787` reverse를 통해 앱의 `합성 결과 조회`를 눌렀다. 프록시는 직접 생성 fixture 이름 대신 고정 합성 ID로 계산한 정확한 outbox CIN 이름을 조회했다. [기기 화면](keyring-receipt-outbox-device.png)에는 첫 조회 완료, `INDETERMINATE · 결과 미확정`, `INVALID_RECEIPT`, 수락·적용 시각 및 GPIO readback 미확인이 표시됐다. Gateway 수신 시각은 CSE에 저장된 합성 값 `2026-10-28T16:29:40.678Z`였다. 이 조회는 실제 outbox가 저장한 합성 내용을 앱이 표시한 증거이며 실제 장치 상태·물리 진동·운영 승인·push 알림의 증거가 아니다.

Enter로 시험을 종료한 뒤 고유 AE의 DELETE HTTP 200/RSC 2002 및 GET HTTP 404/RSC 4004, 요청 ID 일치와 `cleanup: verified`를 확인했다. 임시 broker·proxy·SQLite·호스트 테스트 인증서는 정리됐다. 기기 UI dump를 삭제하고 앱 데이터 삭제 없이 기존 APK를 복원해 설치본 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했다. `stay_on_while_plugged_in`은 원래 값 0으로 복원했다. 8787 listener는 종료됐으며 기존 reverse와 127.0.0.1:50545의 CSE는 유지했다. 시스템·브라우저 인증서 신뢰는 변경하지 않았다.
