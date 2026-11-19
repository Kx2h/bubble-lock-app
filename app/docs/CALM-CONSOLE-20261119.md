# Android 0.5.0 · Calm Console 개편

APP · DOC-REV 2.0.0 · 2026-11-19 · versionCode 6.

운영 앱의 중복 상단 UI를 줄이고, 최신 반응형 웹과 Android의 색상·탐색·상태
표시를 맞췄다. 실제 휴대폰에 최적화 release를 기존 QA 서명으로 업데이트했고
앱 데이터를 초기화하지 않았다.

## 적용한 디자인과 동작

웹 기준은 `origin/main`의 `0d8e2f3` Calm Console 개편이다. `faacfc0`에서 기존
앱·통합 이력과 병합하며 동의 목적 전달 수정과 지도 공급자 설정을 보존했다.
작업 중 추가된 `8f89ba2`의 저장소 라이선스·NOTICE도 별도로 통합했다.

- 파란 액션·흰색/검정 바탕, 18dp 카드·12dp 내부 영역·pill 버튼·선 아이콘을
  native 기본 화면과 런처에 적용했다. native 기본 모드의 3개 탭은 유지한다.
- 운영 Server Portal의 두 줄 헤더·주소·네 버튼을 56dp 상단바와 뒤로/더 보기로
  정리했다. 제품 탐색은 웹의 홈·위치·가족·권한·알림 5개 탭을 사용한다.
- 시작 화면은 가족 대시보드다. 로딩·연결 오류·재시도는 별도 스크롤 가능한
  영역으로 표시하고 주요 native 제어의 터치 영역은 48dp 이상이다.
- 시스템 글자 크기를 WebView에도 반영한다. 앱 전용 CSS는 모바일 헤더에서
  브랜드 단어를 유지하고 계정 액션이 다음 줄로 이동할 수 있게 한다.
- CSS는 서명된 앱의 고정 파일을 동일 origin의 한 subresource 경로로 제공한다.
  서버 CSP를 완화하거나 API·인증 응답을 교체하지 않는다. origin·경로·query
  경계 회귀시험을 추가했다.
- 백그라운드에서는 WebView를 비운다. 화면 복귀·회전에는 허용된 메뉴 경로만
  RAM에서 복원하며 query·인증 callback·좌표·문서 상태는 보존하지 않는다.
  이 설명은 Server Portal에 한정하며 native 지도 저장 상태와 구분한다.
- WebView 쿠키 jar를 화면 이탈 시 flush한다. 쿠키·비밀번호·토큰을 native
  인증 저장소로 추출하는 bridge는 추가하지 않았다.

## 검증 결과

| 범위 | 결과 |
|---|---|
| 루트 통합 `npm run verify` | 모듈·로컬 실제 TLS·계약·데모·웹 빌드 13단계 PASS |
| JVM 단위시험 | Debug 97 / Release 88, 실패 0 |
| release lint / 포장 경계 | 문제 0 / 42개 PASS |
| 전체 release gate | 최적화 APK/AAB·의존성·SBOM·스키마·OSV PASS |
| SBOM / OSV | 492개 구성요소·493개 노드 / 정확 버전 417개 조회에서 알려진 매치 0 |
| 실제 단말 최종 선택 시나리오 | 자동 검사 265개, Android 터치 이벤트 8회 |
| 설치·정리 | 최종 APK 해시 대조, 시험 패키지 사용자0 제거, 표시 설정 복원 |

실제 guardian 계정으로 로그인한 설치 앱에서 세로 주야간·200% 글씨,
5개 탭과 선택 상태·더 보기 메뉴·배경 blank/복귀 경로를 확인했다. 연결 오류는
실제 `WebViewClient.onReceivedHttpError`에 HTTP 503 콜백을 주입하고, 표시된
재시도 버튼을 Android 터치 이벤트로 눌러 운영 화면 복구를 확인했다.
서버 장애나 인터넷 단절을 발생시킨 시험은 아니다.

회전 명령을 세 방식으로 시도했지만 이 실행의 앱 뷰포트는 384×722dp 세로에
머물렀다. **가로 실기 수용 시험으로 포함하지 않는다.** 별도 브라우저의
합성 웹 반응형 6개 시나리오는 Android 실기나 실제 위치 증거가 아니다.
native 보조 화면은 이번에 스타일을 변경하고 빌드했으며 별도 실기 검증은
완료했다고 주장하지 않는다.

이번 디자인 검사는 조회·탐색과 로그인 범위다. 등록·동의·명령 mutation과
실물 위치 정확도·모터는 새로 시험하지 않았다. 2026-11-16의 실제 키링
등록·RSSI 증거는 [별도 기록](../../keyring/evidence/2026-11-16-wsl-esp32-app/README.md)에
보존한다. 자동 터치를 사람의 손가락 조작이나 전체 수동 TalkBack 수용 시험으로
합산하지 않는다.

초기 헤더 줄바꿈·CSP 적용 실패와 시험 도구의 세션 종료·JSON 배열 비교 문제는
수정 전 실패 기록을 비공개 작업 증거에 남겼다. 최종 성공 범위는
[검증 요약](../evidence/calm-console-20261119/verification-summary.json)에 한정한다.

## 산출물과 재현

설치 APK는 0.5.0/code6의 비디버그 QA 서명본이다. 이전 설치본과 서명이 같아
`install -r`로 업데이트했다. 조직·스토어 배포 서명과 구분한다.

```text
SHA-256 ee396bcc8266f20d73c12f9fc98149ea1a68b10f838c53c6e2ece715adffee7f
```

[실기 기록과 공개 화면](../evidence/calm-console-20261119/README.md) ·
[보안·산출물·SBOM](../security/calm-console-20261119/README.md) ·
[별도 실제 기기 시험 도구](../tests/calm-console-device/README.md)

기존 exact-host 인증서 신뢰·pin·SSL 오류 거절·백업 차단·`FLAG_SECURE`를 유지했다.
공개 PNG는 좌표나 계정 본문이 없는 시작 화면의 앱 내부 `View.draw` 결과다.
OS 스크린샷이 아니며 WebView 합성 방식 때문에 화면 chrome 표현에 차이가
있을 수 있다. 단말 식별자·원본 UI 트리·자격증명·설치 백업은 Git에 포함하지 않는다.
