# Bubble 0.2.0 제품 지도·목록·접근성 기기 기록

ROLE: APP · DOC-REV: 2.0.0 · 2026-10-31 · 구현 commit `f2200d1`.

지도·키링·설정 제품 화면과 실제 위치 읽기 API를 Android 14 삼성 SM_S908N에서 확인했다. MapLibre가 OpenFreeMap의 실제 네트워크 벡터 지도를 렌더링했고, 새 팀 `IntegrationStore`/`MecCallbackIngress`에 명시적으로 넣은 **QA 위치 이벤트**를 서명 검증 API가 읽어 앱에 표시했다. 화면의 “지도 검증용 키링”은 실제 사람이나 물리 키링의 위치가 아니다. 제품 기본값에는 그 키링이나 좌표가 포함되지 않는다.

[시험 receipt](product-map-device.receipt.json) · [기기 확인 원본](product-map-device.checks.json) · [호스트 38개](product-host-tests.tap) · [JVM 28개](product-unit-tests.xml) · [최종 빌드 로그](product-build.log) · [소스 해시](product-app-source.sha256.json) · [SHA-256](product-map-device.SHA256SUMS)

## 제품 화면

지도 이미지 출처: [OpenFreeMap](https://openfreemap.org/), © [OpenMapTiles](https://openmaptiles.org/), Data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).

| 확인 | 기기 화면 | 실제 결과 |
|---|---|---|
| 최근 위치와 정확도 | [라이트 지도](product-map-light.png), [다크 지도](product-map-dark.png) | 서울의 QA 좌표에 선택 핀과 35m 정확도 영역 표시, 실제 배경 지도·출처 표시 |
| 지도 외 정보 접근 | [키링 목록](product-keyrings-light.png) | 이름·정보 시각·정확도·MEC 출처·좌표·제공된 배터리/연결 상태를 텍스트로 확인 |
| 큰 글자·짧은 화면 | [200% 세로](product-large-font.png), [200% 가로](product-large-font-landscape.png) | 글자를 축소하지 않고 스크롤·탭으로 이동, 짧은 화면에서 브랜드 영역을 접어 콘텐츠 공간 확보 |
| 유효기간 경과 | [이전 위치](product-stale-location.png) | 원래 기록 시각과 이전 위치 문구, 빈 테두리/황토색 상태로 구분 |
| 잘못된 최신 좌표 | [위치 미확인](product-unavailable-location.png) | 이전 유효 좌표를 조용히 다시 쓰지 않고 좌표·핀 제거 |
| 공유 권한 제한 | [접근 제한](product-permission-denied.png) | API 좌표 null, 앱의 위치 정보·핀 제거와 한국 광역 배경으로 복귀 |
| 연결된 키링 없음 | [빈 목록](product-empty-keyrings.png) | 표본 키링이나 표본 위치를 만들지 않음 |
| API 연결 중단 | [연결 중단](product-network-stale.png) | 마지막 위치가 남는 경우 이전 정보로만 표시하고 연결 오류 안내 |
| 기존 인증 거절 | [세션 거절](product-session-rejected.png) | 시험 서버 서명 키 교체 후 이전 인증을 거절하고 로그인 정보·위치 제거 |
| 명시적 개인정보 정리 | [로그아웃](product-logged-out.png), [연결 해제](product-disconnected.png) | 로그인 정보와 위치 제거, 연결 해제 시 입력한 서비스 주소도 제거 |
| 최종 설치 상태 | [정리 후 제품 앱](product-installed-clean.png) | 새 0.2.0 앱을 남기고 QA 인증·주소·위치를 지운 상태 |

기기 확인 JSON은 25회의 실행 assertion과 22개의 서로 다른 확인 이름을 기록한다. 라이트 전환·최근 위치 확인을 중간에 반복한 실행도 포함하므로 이를 25개의 독립 시나리오로 계산하지 않는다. 위 표와 receipt의 14개 시나리오가 실제 확인 범위다.

## 빌드·보안·연동

- JDK 17, SDK 35, AGP 8.11.1, Gradle 8.13으로 debug·unsigned release APK·unsigned release AAB를 빌드했다. 시험 debug APK SHA-256은 `f7c2a3ae77c932593c54f60411ef0af002c49b6afc3ebf974a9f74060aa47625`이며 구현 commit 고정 뒤의 빌드에서도 동일했다.
- JVM 28개와 CORE/INTEGRATION·새 읽기 서비스의 호스트 38개가 통과했다. 서명·issuer·audience·scope·주체별 바인딩·회수·만료·무효/이전 좌표 등을 포함한다. 실제 IdP의 사용자 로그인 시험은 아니다.
- 기본 release는 설정되지 않은 OAuth callback을 제거하고, 고정 HTTPS API/issuer/client 프로필을 넣은 별도 release 빌드는 callback을 포함하는 것을 검사했다. 두 빌드에서 개발 도구·합성 Activity·cleartext 및 휴대폰 GPS/Wi-Fi 조회 권한의 분리를 확인했다. 프로필 검사는 예시 도메인을 사용한 패키징 시험이다.
- 실제 기기의 세션 설정에 인증 평문이 없고 암호문이 있는 것을 확인했으며 APK 업그레이드/프로세스 재시작 후 API 연결이 복원됐다. 로그인 취소·늦은 응답의 epoch/LOCK 경계는 독립 소스 검토를 거쳤다. 초기 ID token 문맥을 암호화 보존하고 기존 엄격한 refresh 검증을 재사용하는 JVM 회귀시험도 통과했다.
- arm64·x86_64 지도 native library의 ELF 16KB 정렬과 release APK의 16KB ZIP 정렬을 확인했다. 이 기기에서 실제 지도를 렌더링했으며 다른 OS/GPU 전체 행렬을 검증한 결과는 아니다.

폰의 현재 위치를 읽거나 RSSI로 좌표를 추정하지 않았다. 제품 API는 메모리의 명시된 이전 위치만 유지하며 위치 응답을 디스크에 캐시하지 않는다. 지도 SDK의 배경 타일 캐시는 별개다. 지도 출처·공개 제공자 SLA와 운영 대체는 [지도 문서](../docs/MAP_PROVIDER.md)를 따른다.

## 재현과 정리

[제품 빌드·설치 안내](../README.md), [읽기 API 실행·권한 구성](../../pc-mobius/mobile-api/README.md), [모바일 계약](../contracts/mobile-location.v1.json)을 따른다. 이 세션의 자동화·원본 APK·baseline·기기 캡처·환경은 workspace `.local/mobile-product-20261031/`에 보관했다. 실제 운영 데이터로 반복할 때는 해당 사용자의 실제 바인딩·MEC ingress·IdP를 사용한다.

이번 시험은 기존 APK와 설정을 백업하고 새 debug APK를 `install -r`로 적용했다. 원래 reverse가 비어 있음을 확인한 뒤 소유한 `tcp:8787` 연결만 만들고, 완료 후 같은 매핑인지 확인해 제거했다. 시험 서버는 소유 프로세스만 종료했고 호스트 8787 listener가 사라졌으며 기존 CSE `127.0.0.1:50545`/PID 62814는 유지했다.

명시적 로그아웃과 서비스 연결 해제로 시험 인증·주소·위치를 제거했다. 시험 서명 키·토큰, 소유 기기 UI dump를 삭제했다. 글자 크기 1.0, 회전 0/자동회전 꺼짐, 원래 다크 모드와 stay-on 0을 복원했다. 시스템·브라우저 신뢰는 변경하지 않았다. 기존 앱 전체 데이터를 지우거나 앱을 제거하지 않았으며, 새 제품 APK를 사용자 검토용으로 설치해 두었다. 원본 APK의 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24` 백업은 workspace에 남겼다.

실제 운영 IdP/사용자·물리 키링/MEC 좌표 원천, 수동 화면 읽기 프로그램 사용성, 지원 OS/GPU 행렬, 등록·초대·동의/명령·push 등 남은 작업과 정식 배포 gate는 [구현 상태표](../docs/APP_IMPLEMENTATION_STATUS.md)에 있다. 이 기록은 상용 release gate의 승인으로 취급하지 않는다.
