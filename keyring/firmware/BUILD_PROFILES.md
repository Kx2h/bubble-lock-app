# 빌드용 프로필과 실제 로컬 결과

keyring-0.7.3의 부팅별 버튼 누계는 세 프로필과 실제 관측 코드의 호스트 시험을 통과했다. [시험과 바이너리 해시](../evidence/2026-11-10-button-presses/README.md)

keyring-0.7.2의 BLE 명령 프레임 검사는 같은 세 프로필과 r18·Feather 호스트 시험으로 확인했다. [빌드·시험 로그와 해시](../evidence/2026-11-10-command-wire/README.md)

keyring-0.7.1의 출력 차단 변경은 아래 세 프로필과 호스트 시험 6개에서 확인했다. [빌드·시험 로그와 해시](../evidence/2026-11-10-motor-gate/README.md)

ROLE KEYRING / 남호 · DOC-REV 2.0.0 · firmware keyring-0.7.0.

## 2026-11-16 factory 업로드 주소와 NVS 보존

`r18` 파티션의 NVS는 `0x9000..0x18fff`, factory 앱 시작은 `0x20000`이다. Arduino/PlatformIO 기본 앱 주소 `0x10000`과 OTA 초기화 이미지 `boot_app0.bin`의 `0xe000` 쓰기가 모두 NVS 안에 들어가는 문제를 수정했다. 앱 주소를 명시하고 `scripts/factory_upload.py`에서 OTA 이미지의 실제 업로더 인수도 제거한다. 업로드 직전 생성된 파일의 쓰기 범위·앱 크기를 확인하며, provisioning을 제외하는 `keyring_compile_*` 환경의 업로드는 거절한다.

로컬 PlatformIO `espressif32@6.9.0`의 `keyring_compile_minimal -t envdump`를 실행하여 앱 주소 `0x20000`, 부트로더 `0x0000`, 파티션 테이블 `0x8000`과 `boot_app0.bin`이 없는 업로드 인수를 확인했다. 회귀시험 10개는 이전 주소·OTA 레이아웃·추가 쓰기·실제 파일의 NVS 침범·앱 크기 초과·compile-only 업로드 거절을 확인한다.

```sh
python3 -m unittest discover -s keyring/firmware/tests -p test_factory_upload.py -v
```

이 결과는 `LOCAL_TOOLCHAIN_AND_MOCK_FILES` 검증이다. 기존 장치의 파티션이 이 CSV와 같은지, 전원 중단 시 보존되는지와 실제 BLE bond·journal 보존은 실물에서 별도로 확인한다. `keyring_r18` 계열은 ESP32-S3 N16R8용이며 classic ESP32-PICO-V3-02에는 업로드하지 않는다. Feather V2 실물은 [별도 스케치](feather_probe/README.md)를 사용한다.

같은 날 Feather 스케치를 Arduino CLI 1.4.1·ESP32 Arduino 3.3.11·정식 Feather V2 FQBN으로 컴파일했다. `KEYRING_FEATHER_IGNORE_PROVISIONING=1`인 **BLE LOCKED 로컬 빌드**이며 program 905433 bytes, global 36724 bytes다. 공식 릴리스·패키지 index에 있는 CLI·core/toolchain 19개·라이브러리 6개의 SHA-256을 내려받은 파일과 대조했다. 선택적 장치 ID의 실제 constexpr 검증 함수는 허용·거절 경계값 11개를 호스트 컴파일러로 통과했다. flash·실물 등록은 이 빌드 검증에 포함하지 않는다. Arduino의 `merged.bin`은 8MB 전체 이미지이므로 기존 NVS 보존이 필요한 갱신에 사용하지 않는다.

## 2026-11-10 MAX9850 PCM 컴파일 및 host 시험

승인 generation·deadline·GPIO mute와 MAX9850 DAC 순서를 연결한 최종 소스로 4개 환경을 컴파일했다. 아래 결과는 `LOCAL_COMPILE_AND_MOCK_IO`이며 [receipt·소스 및 산출물 SHA-256](../evidence/2026-11-10-headphone-pcm/receipt.json), [컴파일 로그](../evidence/2026-11-10-headphone-pcm/compile.log), [host 시험 로그](../evidence/2026-11-10-headphone-pcm/host-test.log)에 결속한다.

| environment | 선택 기능 | 결과 | RAM bytes | flash bytes | firmware.bin bytes |
|---|---|---|---:|---:|---:|
| keyring_compile_minimal | 기본 기능·DAC standby | PASS | 58160 | 807929 | 808288 |
| keyring_compile_st7789 | ST7789·스피커·SD·sleep | PASS | 59484 | 890773 | 891184 |
| keyring_compile_jd9853 | JD9853·스피커·SD·sleep | PASS | 59484 | 890397 | 890816 |
| keyring_compile_headphone | MAX9850 PCM·MCLK·sleep | PASS | 59180 | 847725 | 848144 |

최종 incremental matrix는 exit 0, 4 succeeded, 21.320초다. 도구는 PlatformIO 6.1.18, espressif32 6.9.0, Arduino 2.0.17이다. 실제 `headphone_standby.cpp`를 Zig 0.13.0 C++로 빌드한 host 시험은 모의 I2C·clock에서 초기화·레지스터 순서·배타 제어·취소·재시도·readback·ready timeout·NACK·설정 drift·권한 전이를 통과했다.

```powershell
$env:PLATFORMIO_BUILD_DIR = 'C:/Users/user/.cache/bubble-keyring-pcm/build'
python -m platformio run -e keyring_compile_minimal -e keyring_compile_st7789 -e keyring_compile_jd9853 -e keyring_compile_headphone
python test/run_headphone_host.py --output ../../.local/firmware-pcm/host
```

4개 compile 환경은 `KEYRING_IGNORE_PROVISIONING`으로 공개 기본 설정을 사용한다. 실물 TODO는 provisioning·flash, MCLK/LRCLK/BCLK/SDIN 파형, status bit 물리 극성, 헤드폰 음량·click/pop·전류, deadline·연결 해제 시 출력 정착 시간이다. 기준 핀과 제조사 Rev4 근거는 [MAX9850 PCM 설명](HEADPHONE_STANDBY.md)에 연결한다.

## 2026-10-29 원본 컴파일 기록

2026-10-29 사용자 명시 허가로 개발 dependency 설치와 세 프로필의 로컬 빌드를 수행했고 모두 통과했다. 실제 flash/COM·장비 시험은 실행하지 않았다. source commit은3c27f4d이며 [원본 로그·receipt·SHA256](../evidence/2026-10-29-local-validation/README.md)에 실제 환경과 실패·수정 이력도 보존했다.

| environment | 선택 기능 |
|---|---|
| keyring_compile_minimal | LCD/audio/SD/sleep 기본값, INA219·DAC standby 포함 |
| keyring_compile_st7789 | ST7789, audio/SD/sleep을 컴파일에 포함 |
| keyring_compile_jd9853 | JD9853, audio/SD/sleep을 컴파일에 포함 |

세 compile profile은 KEYRING_IGNORE_PROVISIONING으로 로컬 provisioning.h와 jd9853_panel.h를 포함하지 않는다. 장치별 passkey는 기본 invalid0이며 이 결과물을 provisioned device firmware로 취급하지 않는다. local 실제 장치 환경 keyring_r18은 기존 provisioning 경로를 사용한다. bare pio run의 default는 keyring_r18으로 유지했다.

`keyring_r18_bringup`은 기존 로컬 provisioning의 장치 ID·passkey를 쓰는 첫 구동용 프로필이다. BLE revision은 `keyring-0.7.25-bringup`이며 LCD·오디오 재생·SD·deep sleep 및 BLE 명령·저널을 비활성화한다. USB 모니터에서 `?`를 보내면 부팅 ID, 초기 GPIO 읽기값, 실행 중인 앱의 `appSha256`을 다시 받을 수 있다. 정식 `keyring_r18`의 revision은 `keyring-0.7.25`다. 두 revision의 빌드와 실물 구동은 미실행이다. [구동 절차](../pcb/BRING_UP.md)

`keyring_r18_bringup_st7789`와 `keyring_r18_bringup_jd9853`는 첫 구동 뒤 장착한 패널 한 개를 점검한다. 로컬 provisioning은 장치 등록과 패널 geometry 설정에 사용하고, 컨트롤러는 환경 이름으로 선택한다. 명령·저널·오디오 재생·SD·deep sleep은 비활성 상태다. 두 프로필의 빌드·실물 화면 시험은 미실행이다. [화면만 연결한 두 번째 구동](../pcb/BRING_UP.md#화면만-연결한-두-번째-구동)

정식 `keyring_r18`에서 `KEYRING_ENABLE_SLEEP=1`을 지정하면 펌웨어는 `keyring-0.7.25-sleep`으로 식별된다. 이 빌드의 절전 진입과 복구는 별도 실물 시험으로 기록한다. [저전력·깨우기](POWER_WAKE.md)

```sh
pio run -e keyring_compile_minimal -e keyring_compile_st7789 -e keyring_compile_jd9853
```

Windows에서 한글 출력 경로는 Xtensa 링커가 map 파일을 열지 못했다. 저장소를 옮기지 않고 영문 경로를 지정해 해결했다. 이번 실행의 명령은 다음과 같다. 이 환경변수는 해당 shell의 출력 위치만 바꾼다.

```powershell
$env:PLATFORMIO_BUILD_DIR = 'C:/Users/NH/.cache/keyring-validation-20261029/build'
python -m platformio run -e keyring_compile_minimal -e keyring_compile_st7789 -e keyring_compile_jd9853
```

| environment | 실제 결과 | RAM 사용 bytes | flash 사용 bytes | firmware.bin bytes |
|---|---|---:|---:|---:|
| keyring_compile_minimal | SUCCESS | 58160 | 807093 | 807456 |
| keyring_compile_st7789 | SUCCESS | 59484 | 889965 | 890384 |
| keyring_compile_jd9853 | SUCCESS | 59484 | 889581 | 890000 |

최종 matrix exit0,3 succeeded,총5분59.074초다. 사용한 도구는 PlatformIO Core6.2.0/espressif32 6.9.0/Arduino2.0.17/Xtensa8.4.0+2021r2-patch5다. ArduinoJson6.21.5는 유지했다. 처음의 Arduino_GFX1.6.9는 core3.x 헤더 부재로 실패해 Arduino2.x 분기가 있는 v1.4.9 commit3cc08c4e9ab6d85807e49b657d73fae10871616e로 바꿨다. Button에는 C++11 호환 명시적 생성자를 추가했다.

프로필의 extends/build_flags 상속은 [PlatformIO 공식 설명](https://docs.platformio.org/en/latest/projectconf/sections/env/options/advanced/extends.html)에 맞춰 기본 USB/PSRAM flags를 명시적으로 이어받는다. 빌드 성공은 두 패널의 실제 초기화·크기·색·전기적 동작, BLE pairing·command execution·전력·RTC wake·SD·음향·중앙 연동을 증명하지 않는다.

각 바이너리·ELF·map·bootloader·partition의 실제 크기/hash는 receipt.json에 있다. 바이너리는 로컬 build directory에 보관하며 Git에는 소스와 로그·hash만 올린다. 전체 목표의 verified/released 상태는 올리지 않는다.
