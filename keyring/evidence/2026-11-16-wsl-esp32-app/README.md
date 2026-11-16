# WSL에서 실제 ESP32 업로드와 앱 연동 시험

KEYRING · DOC-REV 2.0.0 · 2026-11-16.

실제 ESP32의 flash 백업·프로비저닝·업로드부터 BLE 수집, 운영 서버 등록·전송, 실제 Android 앱의 RSSI 표시까지 **일회 기능 통합 시험을 완료했다.** 시험용 권한을 정리하고 앱에서 로그아웃했으며 실제 기기 등록은 유지했다. 상시 Gateway daemon은 실행하지 않는다. 이 결과는 상용화 품질이나 좌표 기반 위치 추적의 완료 선언이 아니다.

## 장치와 빌드

| 항목 | 확인 결과 |
|---|---|
| 개발 호스트 | WSL, Windows의 USB serial·BLE를 통해 실물 접근 |
| 실제 chip | ESP32-PICO-V3-02 revision 3.1 |
| 실행 중 메모리 | flash 8MB, PSRAM 2MB를 새 펌웨어의 UART 진단으로 확인 |
| 선택한 프로필 | `esp32:esp32:adafruit_feather_esp32_v2` |
| 펌웨어 identity | `feather-probe-0.2.1`, `feather-esp32-v2` |
| 도구 | Arduino CLI 1.4.1, ESP32 Arduino 3.3.11, ArduinoJson 6.21.5 |
| 로컬 컴파일 | program 1172668 bytes, global 48960 bytes, PASS |

칩·메모리·기존 전기적 계약을 근거로 Feather V2 프로필을 선택했다. 실물 외관이나 보드 모델 표시는 확인하지 않았다. 위 board revision은 실행한 프로필의 식별값이며 제작 r18 PCB의 구동 증거가 아니다. ESP32-S3 N16R8용 `keyring_r18` 바이너리는 이 장치에 사용하지 않았다.

CLI 릴리스의 checksum과 공식 패키지 index의 core/toolchain 19개·라이브러리 6개 SHA-256을 내려받은 파일과 독립 대조했다. 기본 `BLE LOCKED` 빌드도 별도로 성공했다. 실제 업로드용 소스 복사본·장치별 PIN·바이너리는 비공개 경로에 두고 소유자를 개발 사용자로, 디렉터리를 0700·파일을 0600으로 제한했다. PIN은 콘솔이나 공개 문서에 출력하지 않았다.

## 업로드와 저장 영역 보존

기존 프로그램은 UART에서 self tester로 식별됐다. 쓰기 전에 8MB 전체 flash를 백업하고, 기존 파티션 테이블이 새 `default_8MB` 테이블과 바이트 전체가 같은지 확인했다. 기존 OTA metadata도 공식 app0 선택 초기값과 8192바이트 전체가 같았다.

| flash 주소 | 쓴 파일 | bytes |
|---|---|---:|
| `0x1000` | `feather_probe.ino.bootloader.bin` | 23488 |
| `0x8000` | `feather_probe.ino.partitions.bin` | 3072 |
| `0x10000` | `feather_probe.ino.bin` | 1172816 |

전체 merged 이미지를 쓰거나 OTA metadata를 초기화하지 않았다. 세 이미지의 쓰기·4KB erase 범위가 NVS와 OTA metadata를 침범하지 않는지 계산하고 실제 쓰기 검증을 통과했다. 첫 새 펌웨어 부팅 전에 reset을 보류하고 `0x9000`부터 `0x7000` bytes를 다시 읽었다. **NVS와 OTA metadata 28672 bytes가 백업과 바이트 전체 일치했다.** 이 보존 결과는 첫 부팅 전의 flash 작업에 대한 것이다. 부팅 뒤 bonding·명령 journal은 정상 NVS 쓰기를 수행할 수 있다.

새 UART 부팅 기록의 `appSha256`이 업로드 이미지의 검증된 SHA-256 footer와 일치했다. 이 값은 `.bin` 전체 파일의 SHA-256과 구분한다.

```text
ff0a632fb70737144d7bd79db1d8e0ef3a9180069d4e9da6b6a20e278ab52975
```

## 실제 BLE 관측과 재연결

Windows에서 대상 서비스의 광고를 한 대만 식별하고 기존 bond를 사용해 암호화된 GATT identity·관측을 읽었다. identity의 firmware·board·boot ID를 USB 부팅 기록과 대조했다.

| 실물 수신 세션 | 전체 관측 | 그중 notification | 결과 |
|---|---:|---:|---|
| 첫 정상 수신 | 9 | 8 | USB와 같은 부팅, 완성 프레임 수신 PASS |
| 연결 종료 후 재연결 | 8 | 7 | 같은 부팅에서 수신 복구 PASS |
| 운영 전송용 수집 04 | 9 | 8 | PASS |
| 다음 연결 시도 05 | 0 | 0 | GATT identity 단계의 `BleakError`, FAIL |
| 재시도 06 | 9 | 8 | PASS |
| 재부팅 후 07 | 8 | 7 | 새 boot ID·같은 앱 이미지·기존 bond 수신 PASS |
| 후속 수집 08 | 8 | 7 | PASS |
| 다음 연결 시도 09 | 0 | 0 | GATT identity 단계의 `BleakError`, FAIL |
| 앱 최종 대조용 재시도 10 | 9 | 8 | PASS |

첫 두 성공 세션의 마지막 미완성 프레임은 0 bytes다. 성공 세션 전에 새 PIN 페어링도 시도했다. Windows API는 `PAIRED`를 반환했지만 보호 수준은 `NONE`으로 보고하여 첫 strict probe는 관측 0건으로 실패 처리했다. 이후 세션은 기존 bond로 MITM 암호화가 필요한 GATT 읽기와 펌웨어의 Secure Connections·MITM 인증 gate를 통과했다. Windows 보호 수준 API를 통한 새 bond 증명은 미확정이며, 기존 bond를 해제한 뒤 새 페어링을 다시 검증하지 않았다. 별도로 발생한 수집 05·09의 연결 실패도 위 표에 보존했으며 간헐 연결 오류가 없다고 주장하지 않는다.

명령 프로필을 올렸지만 수신 도구의 명령 쓰기는 비활성 상태였으며 실제 command write는 0건이다. LED ON/OFF, 명령 receipt·journal, 모터 출력의 실물 검증으로 확대 해석하지 않는다.

## 설치된 Android 앱 검사

실제 연결된 휴대폰에 설치된 0.4.1 nondebug 앱에서 기존 보호자 로그인 세션과 WebView 상태를 검사했다. 앱을 교체하거나 데이터를 지우지 않았다. 초기 검사 **26개 PASS**이며 당시 운영 API의 등록 장치 목록은 비어 있었다.

휴대폰은 잠긴 상태였다. 앱 내부 WebView의 계측과 DOM 폼 자동화를 사용했으며 손가락 터치·카메라·잠금 해제·육안 화면 검사를 수행했다고 주장하지 않는다. 이 결과는 실제 설치 앱 실행에 대한 자동 검사다.

## 운영 등록과 서버 telemetry

실제 앱의 등록 폼으로 제출한 요청 1건이 운영 BFF에서 HTTP 200으로 완료됐다. 이어 운영 snapshot에서 등록한 키링·Gateway·household의 결합을 대조하고, 같은 설치 앱의 장치 카드 DOM 표시를 확인했다. 등록 단계 **21개 PASS**다. 초기 검사 26개와 등록 검사 21개는 각각 다른 시점의 검사다.

Windows에서 실제로 수집한 BLE 관측을 host Gateway helper로 운영 HTTPS API에 전달했다. 기존 승인된 인증서와 client certificate를 해당 프로세스에서 사용했고 실제 응답 연결의 TLS 1.3을 기록했다. 시스템 신뢰 저장소는 변경하지 않았다. 이번 전송에는 MQTT나 Raspberry Pi를 사용하지 않았다.

| 운영 요청 | POST 수 | `ACCEPTED` | `DUPLICATE` | `STALE` |
|---|---:|---:|---:|---:|
| 수집 04 재시도 | 9 | 8 | 1 | 0 |
| 수집 06 | 9 | 9 | 0 | 0 |
| 같은 수집 06 재생 | 9 | 0 | 1 | 8 |
| 재부팅 후 수집 07 | 8 | 8 | 0 | 0 |
| 후속 수집 08 | 8 | 8 | 0 | 0 |
| 앱 최종 대조용 수집 10 | 9 | 9 | 0 | 0 |

수집 04의 첫 요청은 서버 HTTP 200 뒤 helper의 native `getpeercert` 호출에서 `TypeError`가 발생하여 로컬 영수증 기록이 실패했다. 호출 호환성을 고친 뒤 같은 관측으로 재시도했고 서버의 중복 처리 1건을 확인했다. 첫 요청을 전송 전 실패로 취급하지 않았다. 수집 06을 그대로 재생한 요청은 새 `ACCEPTED`가 0건이었으며 관측 시각을 바꾸지 않았다. 각 전송 helper의 application 응답 기록과 별도로 서버 조회를 수행했다.

운영 보호자 포털 API의 허용된 조회에서 수집 04의 마지막 관측과 `deviceId`, `gatewayId`, `observedAt`, `rssiDbm`이 정확히 일치했다. `position`은 `null`, `simulation`은 `false`였다. 이는 해당 필드의 readback이며 원래 telemetry 본문 전체 해시의 저장 대조는 아니다.

휴대폰의 기존 로그인 세션에서 동의·grant·300초 INCIDENT lease를 만드는 첫 단계는 **21개 PASS**, 다음 독립 시험 단계는 **22개 PASS**했다. lease 전, 보호자의 lease가 유효할 때 운영자 계정으로 조회한 경우, lease 만료 후의 조회는 모두 노출 장치 0개였다.

최종 수집 10의 9건이 모두 수락된 뒤 같은 설치 앱의 location 단계 **20개 PASS**를 확인했다. 실제 BLE 관측·RSSI·신선도·실물 키링 카드가 DOM에 표시됐고 GPS 부재·`position:null` 상태를 유지했다. 기준 위치가 없어 지도 마커는 0개였다. 따라서 이 결과는 실제 RSSI와 위치 미확인 상태의 앱 표시 성공이며 좌표 기반 추적의 성공을 뜻하지 않는다.

앞선 앱 지도 DOM 검사는 실패했으며 최종 성공과 구분해 비공개 로그에 남겼다. 별도의 진단 20개 검사에서도 현재 DOM의 실제 RSSI와 query 유무에 따른 HTTP 200 응답을 확인했다. 운영 표시 이름이 중앙 등록 라벨과 일치하는 항목은 `false`였으며, 아래 로컬 라벨 수정은 아직 운영에 배포하지 않았다.

## 시험 권한 정리와 종료

| 실제 앱 정리 단계 | 검사 결과 |
|---|---|
| 만료된 첫 시험 권한 정리 | 22개 PASS, lease는 이미 만료·제거된 상태이며 grant·동의를 회수하고 incident를 종료 |
| 다음 시험 권한 정리 | 22개 PASS, lease·grant·동의 회수와 incident 종료, 등록 장치 유지·위치 비노출 확인 |
| 앱 로그아웃 | 20개 PASS, 실제 BFF의 `authenticated:false` 확인, 앱 데이터 초기화 없음 |

처음 만료된 lease의 삭제 요청은 403을 반환했다. 이를 만료 전 lease의 회수 성공으로 처리하지 않고, 서버에서 이미 만료·제거된 상태와 구분했다. 마지막에는 휴대폰 검사와 독립적으로 운영 snapshot과 locations를 다시 조회했다.

| 최종 운영 조회 | 결과 |
|---|---:|
| 등록 장치 | 1개, 실제 키링·Gateway 결합 유지 |
| 활성 동의 | 0 |
| 활성 grant | 0 |
| 열린 incident | 0 |
| 활성 location lease | 0 |
| 조회 가능한 위치 | 0 |

수집·전송 helper의 일회 실행을 마쳤으며 백그라운드 Gateway daemon은 남기지 않았다. 이번 시험용 계측 보조 패키지만 휴대폰의 현재 사용자에서 제거했고, 현재 펌웨어와 기존 0.4.1 앱·데이터, 실제 장치 등록은 유지한다. 이후 관측의 지속 수집·전송을 운영하는 상시 Gateway 배포는 이번 완료 범위에 포함하지 않는다.

## 반영한 소스와 검증 한계

- [Feather 스케치](../../firmware/feather_probe/feather_probe.ino): 선택적 `KEYRING_FEATHER_DEVICE_ID`와 ASCII 1~64자 등록 계약의 compile-time 검증을 추가했다. 기본 ID와 관측·명령 wire 계약은 유지했다. 실제 검증 함수에 대한 호스트 경계값 11개가 통과했다.
- [Feather 사용 절차](../../firmware/feather_probe/README.md): Arduino 3.3.11과 장치별 등록 ID, 비밀 없는 UART 부팅 진단을 설명한다. `?`로 같은 부팅의 ID·firmware·board·앱 이미지 해시·메모리 크기를 다시 읽을 수 있다.
- [r18 업로드 정책](../../firmware/scripts/factory_upload.py): r18의 별도 factory 앱 주소 `0x20000`을 명시하고 NVS와 충돌하던 `boot_app0.bin@0xe000` 쓰기를 제거했다. 실제 PlatformIO 환경 덤프와 회귀시험 10개가 통과했다. 이 정책과 위 Feather의 `app0@0x10000` 주소는 각자의 파티션 테이블을 따른다.
- [중앙 위치 응답](../../../pc-mobius/integration/src/portal-location-service.mjs): 현재 가구 등록의 라벨을 조회 응답에 채운다. [회귀시험](../../../pc-mobius/test/portal-location.test.mjs)은 새 telemetry 없이 이름을 변경해도 현재 등록 이름을 반환하고 원본 RSSI·관측 시각·접근 범위를 유지하는지 확인한다.
- [웹 권한 폼](../../../web-dashboard/src/UserDashboard.tsx): grant 생성에 선택한 동의의 `purpose`를 전달한다. [HTTP 회귀시험](../../../web-dashboard/test/portal-http.test.ts)은 동의·grant·lease의 같은 목적을 보존하고 다른 목적의 lease 요청은 403으로 거절하는지 확인한다.

위 중앙 라벨·웹 권한 폼 변경 뒤 `npm run verify`의 13개 검증 항목이 통과했다. 이 소스 변경은 로컬에서 검증했으며 운영 서버·웹에는 아직 배포하지 않았다. 업로더 회귀시험 10개도 마지막 소스에서 다시 통과했다.

이번 경로에는 Raspberry Pi를 사용하지 않았다. 키링은 GPS 좌표를 보내지 않으며 배터리 관측은 `null`이다. 실제 위치 추적·배터리 계측·휴대폰과 키링의 직접 BLE 페어링·실제 LED 명령·화면 수동 조작은 이 기록의 완료 범위에 포함하지 않는다. Arduino core가 특정 NVS 초기화 오류에서 자동 erase하는 기존 동작은 남아 있어 이후 모든 부팅의 저장 보존을 보장한 시험도 아니다.

원시 USB·BLE·휴대폰 계측 로그, 전체 flash 백업, 운영 응답, 프로비저닝된 소스·바이너리는 Git 제외 `.local/` 증거에 보관한다. 공개 문서에는 PIN·인증서·MAC·호스트 기기 식별자·원시 관측을 포함하지 않는다.
