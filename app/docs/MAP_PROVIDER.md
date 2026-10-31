# 키링 위치 지도

ROLE: APP · DOC-REV: 2.0.0 · 기준일: 2026-10-31.

제품 지도는 MapLibre Native Android `13.7.1`의 벡터 지도이며 기본 스타일은 OpenFreeMap Liberty다. 지도 배경과 키링 위치 데이터는 별도로 처리한다. 배경을 불러오지 못해도 키링 목록에서 위치·관측 시각·정확도를 확인할 수 있고, 위치 API에 데이터가 없으면 배경에 임의의 키링 핀을 만들지 않는다.

## 구현과 데이터 경계

[KeyringMapView](../app/src/main/java/org/privacybubble/app/ui/KeyringMapView.java)는 지도 SDK의 전체 Activity lifecycle을 위임한다. SDK의 실험적 plugin API나 deprecated marker API 대신 GeoJSON source와 Circle/Fill layer를 사용한다. 선택한 키링의 실제 좌표에만 카메라를 맞추며, 첫 응답 전의 한국 광역 배경은 키링 위치가 아니다.

- `available`·`stale` 상태이고 유한한 유효 좌표인 항목만 렌더링한다. `denied`·`unavailable`은 핀과 정확도 영역을 만들지 않는다.
- 유효기간이 지나면 최근 위치의 채워진 점을 이전 기록의 빈 테두리 점으로 바꾼다. 이 갱신은 화면 전경에서만 수행한다. 화면 복귀 시에도 현재 시각으로 다시 평가한다.
- 선택한 위치의 정확도가 양수·유한값이고 100km 이하면 구면 거리 계산의 64구간 다각형으로 오차 영역을 표시한다. 더 큰 불확실성은 거짓된 좁은 원으로 대체하지 않고 목록의 수치로 확인한다.
- 권한이 사라지거나 위치가 제거되면 해당 GeoJSON을 지우고 남은 허용 위치에 맞춰 카메라를 다시 잡는다. 로그아웃처럼 모든 위치가 제거되면 이전 위치를 드러내는 카메라도 한국 광역 배경으로 되돌린다.
- 회전 후 카메라는 같은 선택 키링이 현재 응답에서도 허용된 좌표를 가진 것이 확인된 뒤에만 복원한다. 위치 목록이나 토큰을 지도 상태 Bundle에 넣지 않는다.
- 위치를 역지오코딩 서비스로 전송하거나 이름·좌표를 앱 로그로 출력하지 않는다. 지도 서버는 배경 타일 요청을 통해 조회 영역을 알 수 있으므로 지도 제공자를 정할 때 이를 고려한다.

휴대폰 GPS를 키링 위치로 사용하지 않으며, `ACCESS_FINE_LOCATION`·`ACCESS_COARSE_LOCATION` 권한을 요청하지 않는다. 지도 제스처를 사용하지 않아도 키링 목록에서 같은 정보를 확인하고 위치를 선택할 수 있다. 확대·축소·선택 위치 이동 버튼의 터치 영역은 각각 48dp다. 배경 로딩 실패 안내와 재시도는 위치 API 실패 상태와 구분한다.

기본 OpenFreeMap dark 스타일의 도로·지역·물 이름은 위치를 찾는 데 읽을 수 있도록 밝은 글자와 어두운 halo로 대비를 높인다. 이 보정은 정확히 `https://tiles.openfreemap.org/styles/dark`일 때만 적용하며 다른 제공자나 사용자 설정 스타일에는 적용하지 않는다. 상태 범례는 지도 아래의 가변 높이 줄에 배치해 큰 글자에서도 SDK 출처·로고와 조작 버튼을 가리지 않는다. SDK 출처 버튼은 로고 옆 여백을 확보하는 기본 배치를 유지한다.

## 제공자와 라이선스

MapLibre Native는 BSD 2-Clause로 배포된다. 사용하는 `android-sdk-vulkan-opengl` 아티팩트는 Vulkan과 OpenGL ES를 포함하며 SDK의 자동 선택을 사용한다. 한 backend만 포함된 아티팩트와 다른 backend를 선택하는 조합은 사용하지 않는다. [공식 SDK·라이선스](https://github.com/maplibre/maplibre-native), [13.7.1 릴리스](https://github.com/maplibre/maplibre-native/releases/tag/android-v13.7.1), [backend 문서](https://maplibre.org/maplibre-native/android/examples/artifacts/)

OpenFreeMap은 Native 모바일과 상업 사용을 지원하며 기본 공개 서비스에 API 키·계정이 필요 없다. 공개 서비스에는 SLA 보장이 없으므로 실제 배포에서 보장 수준이 필요하면 제공자 또는 자체 호스팅을 선택해야 한다. `setStyleUrl()`로 인증 정보·query·fragment가 없는 HTTPS 스타일 URL을 바꿀 수 있다. 제공자의 API 키나 별도 인증 방식이 필요하면 그 제공자의 SDK 설정과 배포 정책을 추가해야 한다. [공식 Quick Start](https://openfreemap.org/quick_start/), [상업 사용·SLA·출처 안내](https://openfreemap.org/)

SDK의 attribution을 유지하고 지도 아래에 OpenStreetMap·OpenMapTiles 출처를 표시한다. 기본 제공자 외의 스타일을 사용하면 해당 스타일에 포함된 추가 출처도 SDK attribution에서 확인해야 한다. 영상·인쇄물에도 관련 출처를 함께 표기한다. 이 구현은 오프라인 영역 다운로드 기능을 제공하지 않는다. 추후 추가 시 OpenFreeMap의 다운로드 크기·동시 요청·User-Agent 조건을 적용해야 한다. [OpenStreetMap 저작권](https://www.openstreetmap.org/copyright), [OpenFreeMap 이용 조건](https://openfreemap.org/tos/)

## 검증 범위

SDK `13.7.1`의 실제 cached classes와 모델을 사용한 독립 Java 컴파일로 API 호환을 확인했다. 지도와 앱 전체의 공기계 실행·네트워크 실패·권한 회수·큰 글자·화면 회전 검증은 APP 통합 시험 기록에서 따로 고정한다. 이 문서만으로 운영 위치 소스나 실물 키링 위치가 검증됐다고 판정하지 않는다.
