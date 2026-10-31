# APP 0.2.0 팀 연동 인수 기록

ROLE: APP · DOC-REV: 2.0.0 · 2026-10-31.

| 팀 revision | APP 반영 |
|---|---|
| CORE/INTEGRATION `d54a43a95a7ef9748d795fd614114877efa7787c` | `9a26203`에서 병합했다. 정책·MEC·handover·FL·MCP와 갱신된 공통 계획을 보존하고 `IntegrationStore`의 MEC `Location` 이벤트를 위치 읽기 서비스의 원천으로 사용한다. |
| KEYRING `432a7aa239f73e0db862163ff54909493a80af07` | BLE 세션·ACK 재시도·MQTT 주소 변경을 검토했다. 위치 schema 변경이나 GPS 좌표·UE 매핑은 없으며, 기존 관측 시각은 Gateway 수신 기반이다. 지도 좌표를 RSSI나 기기 ID에서 만들지 않는다. |
| WEB `80ed37fc526ae5d82482fb835085917d63116941` | 대시보드의 상태 표현과 forest 계열 시각 체계를 참고했다. 웹의 receipt 재생·모의 API를 운영 위치 API로 취급하지 않는다. |

## 소비자 확장

[모바일 위치 계약](../contracts/mobile-location.v1.json)과 [위치 읽기 서비스](../../pc-mobius/mobile-api/README.md)를 추가했다. 이는 APP의 읽기 확장이며 INTEGRATION owner의 중앙 계약·배포 인수 검토가 필요하다. 상위 정책·mutation 경로를 변경하지 않았다.

`GET /v1/keyrings`는 서명이 검증된 주체와 서버가 등록한 UE↔키링 바인딩에 한정한다. grant가 지정된 바인딩은 최신 grant/consent의 대상·scope·revision·활성·만료를 확인한다. 읽기 서비스는 상위 portable runner의 decode-only 인증이나 클라이언트가 주장하는 mTLS 헤더를 사용하지 않는다.

위치 제공자는 실제 `Location` 이벤트와 `ueId`를 공급해야 한다. 별도 `observedAt`이 없는 이벤트의 `timestamp`는 위치 정보의 기록 시각으로 표시하며 물리 센서의 측정 시각을 증명하지 않는다. 앱의 휴대폰 GPS·Wi-Fi 조회 권한은 제거했다.

## 배포 인수 입력

실제 HTTPS API origin, OIDC issuer/client·등록 redirect·scope/audience, 신뢰된 서명 키의 운영·회전 정책, 사용자별 UE↔키링 바인딩과 인증된 MEC ingress가 필요하다. 상위 JSON 상태 파일은 읽기 중 불완전하면 503으로 거절하며, 상용 서비스의 transactional read model은 운영 담당과 인수해야 한다.

제품 UI와 위치 읽기 경로는 구현했고, 실제 IdP·사용자 및 실물 키링 좌표의 전체 시험은 남아 있다. 기기 fixture 좌표를 실물 증거로 제출하지 않는다.
