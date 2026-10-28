# 모바일 합성 통합 시연

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10, R05 보조 자료. TEST-ID: `APP-MOBILE-SYNTHETIC-OUTBOX-DEMO-20261029`. 2026-10-29 Android 14 공기계에서 촬영했다. 계획의 APP 산출물인 모바일 통합시험 receipt와 발표용 화면 녹화에 해당한다. Raspberry Pi 실물 경로가 필요한 최종 통합 시연이나 운영 앱 완성의 증거로 사용하지 않는다.

[영상](mobile-integration-demo.mp4) · [시험·정리 receipt](mobile-integration-demo.receipt.json) · [원본 호스트 실행 로그](mobile-integration-demo.host.log) · [SHA-256](mobile-integration-demo.SHA256SUMS)

영상은 84.867초, H.264 720×1462, 15fps, 889,462 bytes이며 오디오는 없다. 재생 시간을 생략하거나 가속하지 않았다. 원본 1080×2316에서 `crop=1080:2194:0:80`으로 시스템 상태·내비게이션 표시줄을 제외한 뒤 축소했다. 시작과 종료 및 단계 전환 동안 앱 전경을 확인했다. `https://example.org`는 화면의 고정 입력 힌트이며 실제 서버 주소가 아니다. 시스템 edge handle 선은 일부 남아 있지만 개인 정보는 포함하지 않는다. 앱 전경 확인 기록과 8개 시점의 contact sheet를 검토했다.

수행자는 APP, 검토자는 APP(root의 contact sheet 검토)이다. 운영 release gate는 `not-evaluated`이며 공식 gate ID나 승인자를 지정하지 않았다.

## 무엇을 확인하는가

고정 KEYRING 소스 `d233c0f7c445be7b7505a69582a8fb0f471c57c2`의 실제 SQLite journal·outbox와 MQTT 전송 코드, 임시 loopback mTLS broker와 시험 어댑터, 실제 CORE `GatewayBridge`, 격리된 실제 Mobius4 CSE, Android debug 앱을 사용했다. 입력 관측·상태·receipt는 모두 합성이다. 전체 Gateway daemon, Raspberry Pi, BLE, 물리 키링, 운영 로그인·ACP·명령 인가 또는 push는 시험하지 않았다. `Store.accept`와 `Gateway._finish`는 고정 합성 `INDETERMINATE` 결과를 기록했으며 명령 dispatch·기기 실행은 호출하지 않았다. MQTT 명령 토픽 ingress는 0이었다.

원본 호스트 실행 로그는 `tee`로 받은 stdout/stderr를 변경 없이 보관했다. 시험 AE/ready/최종 JSON만 포함하며 자격 증명이나 기기 serial은 없다. receipt의 `hostRun`은 이 로그의 마지막 JSON 객체를 파싱한 것이며 `hostRun.results`의 값과 순서는 원본 그대로다. 실행 로그 SHA-256은 `cb4402e023a56e2577f257fee5c1858430922916b8c2be1baf154f569f995a8e`이다. receipt의 `expectedActualChecks`에는 testId별 예상·실제 결과와 artifactRef를 기록했다.

시험 AE는 `app-bridge-1814b419261346c7`이었다. 관측·receipt CIN의 CREATE HTTP 201/RSC 2001과 별도 readback HTTP 200/RSC 2000, 요청 ID 및 내용 일치를 확인한 뒤에만 MQTT 성공 응답을 보냈다. SQLite 재열기에도 receipt journal·pending 항목이 유지됐고, 전송 확인 뒤 receipt outbox 제거와 `receipt_delivered=1`을 확인했다. `APPLIED`는 이 시험의 CORE 상태 갱신 결과이며 실제 장치 적용을 뜻하지 않는다.

| 영상 시각 | 앱에서 보이는 내용 | 해석 범위 |
| --- | --- | --- |
| 약 0–20초 | 테스트 상태 조회, 켜짐, bootstrap 표식과 실제 관측 여부 미확인 | CSE 시험 자원 조회이며 현재 물리 상태가 아님 |
| 약 21–40초 | 합성 관측 조회, 순서 1, 배터리 90%, RSSI −50 dBm, Gateway 수신 기반 시각 | 마지막 저장된 합성 관측이며 RSSI는 거리가 아님 |
| 약 45–68초 | 합성 결과 조회, `INDETERMINATE`, `INVALID_RECEIPT`, 수락·적용 시각과 GPIO readback 미확인 | 고정 합성 결과이며 실제 승인·진동·기기 실행의 증거가 아님 |
| 약 69–84초 | 전경 자동 조회, 표시 내용 변화 없음 | 동일 표시 내용만 비교하며 같은 사건·새 실행·push 알림을 증명하지 않음 |

영상 종료 직후 자동 조회를 중지했다. 앱의 조회 시각은 기기 시계이고 Gateway 수신·관측 필드는 합성 시험 데이터이다. 영상 속 조회 시각만으로 장치 관측의 신선도를 판단하지 않는다.

## 재현

작업 폴더 안의 Node·Android 도구와 의존성을 사용한다. [기존 시험 환경](../../app-test-server/native/README.md)과 [합성 결과 범위](../docs/SYNTHETIC_RECEIPT_POC.md)를 먼저 확인한다. 원본 APK 백업, 기기 잠금 해제와 앱 전경 확인, 비어 있는 호스트 8787, 기존 기기 `tcp:8787` reverse, 격리 CSE가 필요하다. 기존 앱 데이터와 시스템·브라우저 인증서 신뢰는 변경하지 않는다.

1. 설치된 APK의 경로와 SHA-256, reverse 전체 목록, `stay_on_while_plugged_in` 원래 값을 기록하고 APK를 작업 폴더에 백업한다. 최신 debug APK를 `install -r`로 설치한다.
2. 저장소 루트의 입력 가능한 터미널에서 아래 명령을 실행하고 `ready`와 새 시험 AE 이름을 확인한다. 이 명령은 기존 reverse를 생성·삭제하지 않는다.

   ```sh
   "$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring-outbox --observation-cin --receipt-outbox --phone
   ```

3. 앱의 Mobius4 테스트 구역을 연 뒤에만 Android `screenrecord`를 시작한다. 테스트 상태, 합성 관측, 합성 결과를 순서대로 조회한다. 결과 화면에서 전경 자동 조회를 켜고 같은 표시 내용의 재조회를 확인한다. 홈이나 알림 화면을 열지 않는다. 85초 제한으로 녹화를 끝내고 자동 조회를 중지한다.
4. 소유 녹화 파일을 작업 폴더로 복사한 뒤 기기에서는 그 녹화 파일과 이번에 만든 UI dump만 제거한다. 호스트 터미널에서 Enter를 눌러 고유 AE의 DELETE와 GET 404 및 `cleanup: verified`를 확인한다.
5. 원본 APK를 앱 데이터 삭제 없이 `install -r`로 복원하고 설치본 해시를 비교한다. reverse와 stay-on 값이 원래와 같은지, 8787 listener가 종료됐는지, 기존 CSE가 유지되는지 확인한다. 녹화의 앱 외 영역을 제외하고 검토한 뒤 영상·receipt·checksum을 함께 보관한다.

이번 시험의 APK SHA-256은 `ad34c07435bdae3ca31a012153e930e01ac081f6c65ce9bfcc4307fbb4301512`이며 소스 기준은 APP commit `3c6d71b351812784768b729dbee18a2138ea8e87`이다. 백업한 기존 APK는 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`로 복원됐다. 앱·앱 데이터는 제거하지 않았다. 고유 AE DELETE HTTP 200/RSC 2002 뒤 GET HTTP 404/RSC 4004, 요청 ID 일치와 `cleanup: verified`를 확인했다. 소유 기기 녹화·UI dump는 제거됐고 reverse는 동일하며 stay-on은 원래 값 0이다. 임시 broker·proxy·SQLite·호스트 테스트 인증서는 정리했고 기존 CSE는 유지했다. 시스템·브라우저 신뢰는 변경하지 않았다.

이 자료는 APP의 제한된 합성 조회 경로를 재현하는 발표 보조 증거다. 운영 issuer/client/redirect와 사용자 API·ACP 계약, 실제 Pi/BLE·장치 동작의 인수인계 및 통합시험은 별도로 남아 있다.
