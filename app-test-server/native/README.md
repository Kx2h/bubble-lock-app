# 격리된 Mobius4 소스 시험 환경

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10 · 기준 CORE `91a281b`, KEYRING `c2ad489`.

이 구성은 Ubuntu 24.04 amd64에서 별도 PostgreSQL 16/PostGIS 3.4 데이터베이스와 IoTKETI Mobius4 `e8f75b1d6ee13a0e034379ec8dab26a6ef4fe8f8`를 작업 폴더의 `.local` 아래에 둔다. 시스템 패키지나 기존 Docker daemon, 이미지, 볼륨을 수정하지 않는다. Node 22는 별도로 `$WORKSPACE/.local/node`에 준비해야 한다. Ubuntu APT 저장소에 스크립트에 고정된 버전이 남아 있어야 최초 설치가 가능하다. root로 실행할 경우 workspace 소유자가 root가 아니어야 한다.

```sh
# 저장소 루트에서; 작업 폴더의 절대 경로를 지정
export WORKSPACE=/absolute/path/to/mobius4
app-test-server/native/setup.sh
app-test-server/native/start.sh
app-test-server/native/bootstrap.sh
# 중계 서버를 별도 터미널에서 실행 (config에는 local 관리자 identity가 있다)
MOBIUS4_URL=http://127.0.0.1:50545/Mobius \
MOBIUS4_ORIGINATOR="$("$WORKSPACE/.local/node/bin/node" -p 'require(process.argv[1]).cse.admin' "$WORKSPACE/.local/mobius-runtime/config.json")" \
  "$WORKSPACE/.local/node/bin/node" app-test-server/server.mjs
# 시험을 끝낸 후 중계 서버를 종료하고
app-test-server/native/stop.sh
```

`MOBIUS_PORT`를 최초 `setup.sh`에 지정했다면 중계 URL도 같은 포트로 바꾼다. setup은 기존 runtime/source 경로를 절대 덮어쓰지 않는다. 시작은 포트 충돌을 거부한다. stop은 PID의 프로세스와 소스 경로를 확인하며, PID가 없거나 유효하지 않은 상태에서 CSE 포트가 열려 있으면 PostgreSQL을 중단하지 않는다. 정상 중지는 DB 데이터를 보존한다. 설치 실패 시 남은 파일을 자동 삭제하지 않는다. `config.json`, 로그와 DB는 Git에 추가하거나 공개하지 않는다. 인증 정보, 녹화 화면과 로그에는 개인정보가 포함될 수 있다.

Bootstrap은 KEYRING 시험 구성의 AE와 ty=28 `pb:binarySwitch`를 생성하고 라벨 `dev-bootstrap`, `unobserved`를 사용한다. 존재하는 자원은 덮어쓰지 않고 409/4105를 기록한다. 매 실행은 `.local/mobius-runtime/bootstrap-receipt.*.json`에 별도 receipt를 남긴다. 초기 `false`는 실제 센서 관측이 아니다. CORE 소유의 `oneM2M-test-contract.v1.json`은 현재 flexContainer CREATE를 ty=4로 기록하므로 그대로 수정하지 않았고, KEYRING 쪽 ty=28과의 차이는 팀 인수인계가 필요하다.

서버의 HTTP 바인딩을 로컬 소스 checkout에서 `127.0.0.1`로 제한하고, KEYRING의 specialization registry를 이 디렉터리의 고정 fixture로 복사한다. PostgreSQL은 해당 runtime의 권한 제한된 Unix socket에서만 수신하며 MQTT와 TLS는 이 환경에서 비활성화한다. 이는 임시 로컬 조회 실험에만 적합하고 운영 인증·권한 또는 실제 Gateway 경로가 아니다. 고정된 upstream npm 의존성의 2026-10-28 설치에서 취약점 경고가 있었으며 외부 네트워크에 노출해서는 안 된다.

2026-10-28 독립 테스트 폴더에서 setup, start, bootstrap(201/2001), 재실행(409/4105), stop과 데이터 보존 후 재시작을 확인했다. 살아 있는 CSE의 PID 파일이 없을 때 stop이 DB 중단을 거부하는 것도 확인했다. 실제 앱 시험용으로 별도 수동 기동한 서버와 이 재현 시험은 구분한다. mTLS, MQTT, 실물 Gateway·키링 및 운영 API 통합은 이 시험의 범위가 아니다.

`evidence/bootstrap-created.json`은 신규 격리 DB에서 실제 자원 생성 결과이고, `evidence/restart-test.log` 및 `evidence/pid-loss-test.log`는 재시작과 안전 중지 거부 결과이다. `sha256sum -c app-test-server/native/evidence/SHA256SUMS`로 무결성을 확인할 수 있다. 독립 검증 서비스는 종료했고 DB 파일은 보존했다. 앱 실기기 시험에서 사용한 기존 서버는 별도이며 이 증거와 혼동하지 않는다.

## CORE 브릿지와 APP 연결 시험

기존 CSE `127.0.0.1:50545`에서 아래 명령은 고유 AE를 CSE에 등록하고 별도의 `bubbleState`를 생성한다. 합성 telemetry를 실제 CORE `GatewayBridge`에 입력해 HTTP UPDATE와 독립 CSE readback 및 APP 중계를 확인한 뒤, 생성한 AE의 이름과 ID가 일치할 때만 그 AE를 삭제하고 404를 확인한다. 기존 bootstrap 자원은 수정하지 않는다. MQTT client는 시험용이며 실제 broker, Pi, BLE, 키링, 인증·승인 또는 운영 연동 증거가 아니다.

```sh
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs
"$WORKSPACE/.local/node/bin/node" --test app-test-server/native/core-app-bridge-check.test.mjs app-test-server/server.test.mjs
# 휴대폰으로 조회할 때: 8787 포트가 비어 있고 해당 기기의 reverse가 준비된 경우에만
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --phone
# stdin이 열린 터미널에서 ready 출력 뒤 기기 조회를 수행하고 Enter를 눌러 정리한다.
```

기본 주소가 아닌 격리 CSE는 `APP_CORE_TEST_CSE_URL=http://127.0.0.1:<port>/Mobius`로 지정한다. HTTP loopback만 허용한다. 스크립트는 AE 생성 요청 직전에 고유 이름을 출력한다. 생성 응답이 유실되거나 신원이 맞지 않으면 `cleanup: manual-review`를 출력하고 자동 삭제하지 않는다. 강제 종료 시에도 자동 삭제를 보장하지 않으므로 출력의 AE 이름과 CSE 상태를 직접 확인하고, 소유권이 확정되지 않으면 다른 자원을 삭제하지 않는다. `--phone`은 기존 reverse를 생성·삭제하지 않으며 이미 사용 중인 8787 포트를 덮어쓰지 않는다. 실패 시 종료 코드는 0이 아니고 `cleanup` 결과를 확인해야 한다. 실기기 결과는 [APP 증거](../../app/evidence/core-bridge-cse-app.md)에 별도 기록한다.

### 로컬 MQTT 전달 경로

다음 옵션은 작업 폴더에 설치한 Aedes 1.2.0 broker를 임시 loopback 포트로 열고, `mqtt` 5.16.1 클라이언트로 MQTT 3.1.1 QoS 1 telemetry를 전송한다. 실제 CORE `GatewayBridge.start()`의 구독과 수신을 거쳐 위의 격리 CSE HTTP UPDATE 및 APP 중계 조회까지 확인한다. 같은 `deviceId`·`sequence`로 다시 전송해 CORE의 `DUPLICATE`와 단일 UPDATE도 확인한다. 종료 시 broker와 그 클라이언트를 닫고 소유 확인된 시험 AE만 삭제한다. 기본 실행은 기존의 직접 호출 경로를 유지한다.

```sh
export PATH="$WORKSPACE/.local/node/bin:$PATH"
export npm_config_cache="$WORKSPACE/.local/npm-cache"
npm ci --prefix app-test-server/native/mqtt --ignore-scripts --no-audit --no-fund
node --test app-test-server/native/core-app-bridge-check.test.mjs
node app-test-server/native/core-app-bridge-check.mjs --mqtt
# broker 중단·재시작 후 CORE 재구독 및 새 상태 전달을 확인
node app-test-server/native/core-app-bridge-check.mjs --mqtt --mqtt-reconnect
# 공기계 시험: 이미 연결된 reverse를 유지하고 빈 8787 포트에서만 실행
node app-test-server/native/core-app-bridge-check.mjs --mqtt --phone
```

broker는 TCP loopback의 임의 포트에만 바인딩하며 인증·TLS·ACL·영속 저장을 사용하지 않는 시험용이다. 합성 송신자가 실제 MQTT 프로토콜을 사용하지만 물리 Pi·BLE·키링, 외부 broker, TLS/MQTT ACL 및 운영 API를 검증하지 않는다. `APPLIED`만으로 성공을 추정하지 않고 별도의 HTTP RSC·요청 ID와 CSE readback을 확인한다. Node와 의존성·캐시는 작업 폴더 아래에만 둔다. 설치한 `node_modules`는 Git에 포함하지 않는다.

### KEYRING 저장소의 관측 변환 연결

`--keyring`은 별도 고정 worktree의 KEYRING `d233c0f7c445be7b7505a69582a8fb0f471c57c2`에서 실제 `validate_observation`, `Store.observation` 및 CORE telemetry 변환을 실행한다. 합성 관측을 새로운 임시 SQLite outbox에 저장하고 battery 또는 RSSI가 null이면 CORE projection이 생성되지 않는지, 중복 관측이 거부되는지 확인한다. DB를 닫았다가 열어 대기 projection과 wire sequence의 보존을 확인한 뒤, 생성된 합성 payload 하나를 위 로컬 MQTT→CORE→격리 CSE→APP 경로에 전송한다. SQLite 시험 폴더는 정상 종료와 처리된 오류 시 삭제하며 강제 종료 시에는 남을 수 있다.

별도 worktree가 아직 없다면 저장소 루트에서 한 번 준비한다. 기존 경로는 덮어쓰지 않는다. `--keyring`은 실행 전 해당 checkout의 HEAD와 Gateway Python 소스의 변경·추가 여부를 확인하며, 다른 revision이나 수정된 소스는 거부한다. Python 3.11 이상과 위 MQTT 의존성이 필요하다.

```sh
git worktree add --detach "$WORKSPACE/.local/keyring-app-pinned" d233c0f7c445be7b7505a69582a8fb0f471c57c2
"$WORKSPACE/.local/node/bin/node" --test app-test-server/native/keyring-projection.test.mjs
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring
# 빈 8787과 기존 기기의 reverse가 준비된 경우
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring --phone
```

이 시험은 KEYRING 코드의 validator·SQLite·projection을 호출하지만 전체 Gateway daemon이나 outbox의 실제 전송 루프는 실행하지 않는다. Node 합성 송신자가 projection을 읽어 MQTT로 전달한다. `--mqtt-reconnect`는 두 번째 projection을 KEYRING 코드에서 생성하지 않으므로 `--keyring`과 함께 사용할 수 없다. 실제 BLE, Pi, 키링, 배터리·RSSI 관측, 인증 및 운영 권한의 증거가 아니다. checkout이 없는 경우 별도 단위시험은 skip되지만 `--keyring` 실경로 시험은 CSE 자원 생성 전에 실패한다.

`--mqtt --keyring-outbox --observation-cin --phone` 시험에서는 같은 격리 AE의 `observations/la`를 읽는 합성 관측 화면을 사용할 수 있다. 중계는 이 시험에 한해 고유 `app-bridge-<16 hex>` AE와 고정된 합성 기기·Gateway·시간 fixture만 허용하며 HTTP/RSC·요청 ID와 CIN 이름을 확인한다. 화면의 관측·배터리·RSSI는 합성 값이다. 마지막 저장 순서는 기기의 실제 최신 상태나 관측 시간 순서를 증명하지 않고 RSSI는 거리가 아니다. 빈 container, 식별자 불일치 또는 통신 오류는 값을 추정하지 않고 조회 실패로 표시한다. 기본 중계에는 이 조회 경로가 없다.

`--mqtt --keyring-outbox --observation-cin --receipt-cin --phone`은 같은 격리 AE에 고정 KEYRING 코드로 생성한 합성 `INDETERMINATE` receipt를 직접 저장해 앱의 합성 결과 조회 화면까지 연결한다. 생성·readback의 oneM2M RI/RSC와 전체 내용을 대조하며 합성 명령을 전송하거나 기기를 실행하지 않는다. Enter 또는 stdin EOF로 정리를 시작하며 stdin이 닫힌 자동 실행은 조회 준비 직후 종료한다. `APP_CORE_TEST_PROXY_PORT=0`은 호스트 시험용 임의 포트를 사용하며, 휴대폰의 고정 8787 연결에는 기본 포트를 사용한다. 정확한 범위와 정리 절차는 [APP 합성 결과 시험](../../app/docs/SYNTHETIC_RECEIPT_POC.md)을 참고한다.

`--receipt-cin` 대신 `--receipt-outbox`를 쓰면 같은 고정 합성 결과를 팀 코드의 실제 command journal과 receipt outbox에 기록해 전송한다. `Store.accept`와 `Gateway._finish`로 `INDETERMINATE` 결과만 기록하고 dispatch·authorize·BLE·command publish는 호출하지 않는다. 연결 전 `_outbox_loop`의 재시도 및 DB 재열기 뒤 pending·미전달 journal 보존을 확인한다. 연결 후 `Store.stage_receipts`와 `TransportHub.publish`가 만든 정확한 `gw-SHA256` CIN만 별도 mTLS MQTT→HTTP 시험 어댑터로 전달하고, CSE CREATE·독립 readback의 RI/RSC와 전체 본문이 일치한 뒤에만 응답한다. 이후 실제 outbox 항목 제거와 journal의 `receipt_delivered=1`을 확인한다.

```sh
node --test app-test-server/native/mqtt/receipt-bridge.test.mjs app-test-server/native/core-app-bridge-check.test.mjs app-test-server/server.test.mjs
node app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring-outbox --observation-cin --receipt-outbox
# 기존 기기의 8787 reverse와 빈 8787 포트에서 같은 합성 결과 화면을 확인하려면 --phone 추가
```

이 모드에서 APP 중계는 `syntheticReceiptSource: 'outbox'`라는 명시적 시험 설정과 고정 합성 command ID에서 계산한 CIN 이름을 사용한다. 기존 `direct` 모드는 `synthetic-receipt` 이름만 허용하며 임의의 receipts CIN으로 확장되지 않는다. 서버 응답 본문의 형태와 앱은 기존 합성 조회와 동일하다. 이 검증은 새 명령 실행·물리 ACK·실제 사용자 권한의 증거가 아니며, 응답 유실 뒤 CIN 이름 충돌의 회복이나 실제 Gateway daemon 전체 실행은 구현하지 않는다. 호스트 결과는 [receipt outbox 기록](evidence/keyring-receipt-outbox.md)에 있다.

`--mqtt --mqtt-reconnect`는 브로커를 중단한 뒤 같은 loopback 포트에서 새 인스턴스를 시작한다. 기존 CORE 클라이언트의 재연결과 `GatewayBridge.start()` 재호출 뒤 브로커에서 QoS 1 재구독을 관찰하고, 새 sequence의 합성 상태를 전송하여 두 번째 HTTP UPDATE의 RSC·요청 ID와 CSE 및 앱 중계 readback을 확인한다. 연결이 끊긴 동안에는 메시지를 보내지 않으므로 오프라인 저장·유실 복구나 재시작 뒤 중복 방지를 검증하지 않는다. 기기에서 확인할 때는 `--phone`을 함께 사용한다.

`--mqtt --keyring-outbox --observation-cin`은 고유 AE 아래 시험용 `observations` container를 추가하고 KEYRING 원본 관측 outbox도 확인한다. 별도 mTLS MQTT 시험 어댑터는 해당 container의 CREATE만 실제 loopback CSE HTTP에 전달하며, CSE의 CREATE HTTP 201/RSC 2001/요청 ID와 별도 RETRIEVE HTTP 200/RSC 2000/요청 ID 및 CIN 이름·내용 일치를 확인한 후에만 같은 MQTT 요청 ID로 성공 응답을 보낸다. 이후 팀 Gateway의 SQLite outbox에서 해당 항목이 제거되는지 검사한다. 오류나 불일치에는 성공 응답을 보내지 않는다. CORE telemetry는 별도 경로로 이전과 같이 검증한다.

```sh
node --test app-test-server/native/mqtt/observation-bridge.test.mjs app-test-server/native/core-app-bridge-check.test.mjs
node app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring-outbox --observation-cin
# 기기 상태 화면 확인 시 --phone 추가; 기존 8787 reverse는 그대로 둔다.
```

이 브릿지는 임시 시험 어댑터이며 Mobius의 native MQTT binding이나 운영 중계가 아니다. CREATE 결과를 확인하지 못하거나 readback이 실패한 뒤의 기존 CIN 충돌 회복은 구현하지 않는다. 성공 확인 전에 연결이 끊겼을 때 서버 처리 여부는 불확실하고 시험은 실패한다. 실제 Pi·BLE·키링·운영 인증이나 기기가 생성한 관측을 의미하지 않는다.

### KEYRING outbox와 mTLS 전송 시험

`--mqtt --keyring-outbox`는 고정된 KEYRING 코드의 `Gateway.observation`, 실제 SQLite outbox 처리 루프 및 `TransportHub`를 호출한다. 연결 전 합성 관측 항목 두 개가 재시도 상태로 남는지 확인하고, 별도 loopback TLS 1.3 broker에 클라이언트 인증서로 연결한 뒤 CORE telemetry 항목이 PUBACK 후 제거되는지 확인한다. broker ingress와 CORE의 HTTP UPDATE·RSC·요청 ID, 독립 CSE readback 및 APP 중계를 각각 확인한다. 인증서 없는 접속은 broker가 거절하는지도 검사한다. 이때 일반 observation 항목은 시험 대상 CSE container의 MQTT 응답·HTTPS 서버가 없어 미전달 상태로 남는다. PUBACK은 broker 수신만 뜻하며 CORE 처리나 물리 실행을 보장하지 않는다.

```sh
python3 -m venv "$WORKSPACE/.local/keyring-gateway-venv"
PIP_CACHE_DIR="$WORKSPACE/.local/pip-cache" "$WORKSPACE/.local/keyring-gateway-venv/bin/pip" install -r app-test-server/native/keyring-outbox-requirements.txt
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring-outbox
# 공기계 시험: 빈 8787과 기존 reverse가 준비된 경우
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring-outbox --phone
```

테스트용 CA·서버·클라이언트 개인키와 SQLite는 작업 폴더의 임시 디렉터리에만 생성하며 정상 종료와 처리된 오류 시 삭제한다. 기기나 시스템 인증서 신뢰는 바꾸지 않는다. 강제 종료 시 임시 파일이 남을 수 있다. 이 시험은 전체 Gateway daemon, BLE·실물 Pi·키링, MQTT ACL, 운영 인증이나 오프라인 데이터의 종단 간 복구를 증명하지 않는다.
