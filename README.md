# Bubble · Privacy Bubble APP

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03, R09, R10, R14, R17

공유가 허용된 키링의 위치 정보를 지도와 목록으로 확인하는 Android 앱입니다. 현재 제품 버전은 **0.2.0**이며 `위치 / 키링 / 설정` 화면을 제공합니다. MapLibre Native 지도, 라이트·다크 테마, 큰 글자·접근성 대응과 연결·만료·권한 거절 상태를 구현합니다. 기본 화면에 가상 키링이나 임의 위치 핀을 만들지 않습니다.

새 팀 INTEGRATION의 MEC `Location` 이벤트를 읽는 인증 API와 Android 위치 클라이언트를 연결했습니다. 위치 제공 시각·정확도·출처를 함께 표시하고, 오래되거나 갱신하지 못한 정보는 이전 기록으로 구분합니다. 휴대폰 GPS나 RSSI를 키링 위치로 사용하지 않습니다.

- [앱 사용·빌드·설치·정리](app/README.md)
- [요구별 구현 상태와 상용 배포 잔여 조건](app/docs/APP_IMPLEMENTATION_STATUS.md)
- [모바일 위치 응답 계약](app/contracts/mobile-location.v1.json)
- [팀 이벤트를 읽는 위치 서비스](pc-mobius/mobile-api/README.md)
- [지도 제공자·출처·데이터 경계](app/docs/MAP_PROVIDER.md)
- [팀 INTEGRATION 구현](pc-mobius/integration/README.md)
- [기기 시험·과거 합성 시험 증거](app/evidence/)

로그인은 신뢰된 빌드 프로필의 브라우저 OIDC + PKCE 흐름을 사용하고, token은 Android Keystore 기반 암호화 저장소에 보관합니다. 실제 배포용 IdP·계정·HTTPS 서비스와 실물 Pi/BLE·키링의 위치 경로는 아직 검증하지 않았습니다. 기기·가정 등록, 실제 동의·명령 승인, push, handover·FL 화면과 공식 release 검증도 남아 있어 상용 배포 완료로 판정하지 않습니다. 현재 위치 API는 APP 소비자 읽기 확장이며 중앙 통합 전 owner 검토가 필요합니다.

공동 개발 기준은 [PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md](PROJECT_IMPLEMENTATION_RUBRIC_PLAN.md)입니다. 팀 원본은 [tirofina/bubble_lock](https://github.com/tirofina/bubble_lock), APP 공개 작업 저장소는 [Kx2h/bubble-lock-app](https://github.com/Kx2h/bubble-lock-app)이며 APP 변경은 `feature/app`에서 진행합니다.

도구·의존성·백업·시험 자료는 작업 폴더 아래 `.local`에 관리합니다. 비밀키·토큰·운영 credential과 로컬 상태 DB는 Git에 포함하지 않습니다. 시스템·브라우저 인증서 신뢰는 변경하지 않습니다. 이전 합성 시험은 debug 개발 도구로 유지하며 제품의 실제 위치·승인 증거와 구분합니다. AppAuth 수정 소스의 출처와 라이선스는 [vendor 안내](app/vendor/appauth/README.md)에 있습니다.
