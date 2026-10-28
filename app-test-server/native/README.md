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
# ready 출력 뒤 기기에서 상태 조회하고, 터미널에서 Enter를 눌러 정리한다.
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
# 공기계 시험: 이미 연결된 reverse를 유지하고 빈 8787 포트에서만 실행
node app-test-server/native/core-app-bridge-check.mjs --mqtt --phone
```

broker는 TCP loopback의 임의 포트에만 바인딩하며 인증·TLS·ACL·영속 저장을 사용하지 않는 시험용이다. 합성 송신자가 실제 MQTT 프로토콜을 사용하지만 물리 Pi·BLE·키링, 외부 broker, TLS/MQTT ACL 및 운영 API를 검증하지 않는다. `APPLIED`만으로 성공을 추정하지 않고 별도의 HTTP RSC·요청 ID와 CSE readback을 확인한다. Node와 의존성·캐시는 작업 폴더 아래에만 둔다. 설치한 `node_modules`는 Git에 포함하지 않는다.
