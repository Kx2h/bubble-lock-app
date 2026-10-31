# 0.3.0 보안 릴리스 후보

ROLE: APP · DOC-REV: 2.0.0 · 2026-11-01 KST · versionCode 3

제품 Android 앱과 위치 읽기 API를 보강하고 최적화된 release APK/AAB를 생성했다. 삼성 Android 14 기기에는 같은 release APK에 workspace QA 서명만 적용한 사본을 설치했다. 운영 계정의 서명이나 운영 IdP/API 프로필을 포함한 최종 배포본은 아니다.

## 적용한 변경

- release에 R8 코드 최적화·난독화와 리소스 축소를 적용했다. 개발용 클라이언트 4개와 이전 합성 인증 클래스 3개를 debug 소스로 옮기고 개발 인증 UI도 variant 소스로 분리했다. 제품 저장소의 `setDebugAccessToken` 메서드는 삭제했다.
- 자사 제품 Java/XML의 주석 49곳과 위치 API/Gradle의 개발 설명 주석을 정리했다. 보안 경계 설명은 이 디렉터리의 문서로 옮겼다. 외부 소프트웨어의 저작권·라이선스 원문은 보존하고 앱 설정에서 오프라인으로 열 수 있다.
- 현재 release 빌드의 API origin을 강제한다. 이전 주소에 묶인 암호화 세션은 업데이트 후 무효화된다. 잘못된 포트·authority도 거절한다.
- 로그인 PKCE verifier를 메모리에만 보관한다. AppAuth에 전달되는 공개 요청은 실제 verifier를 포함하지 않고 동일 S256 challenge를 사용한다. 회전은 ViewModel을 유지하고, 프로세스 소실·취소·로그아웃은 이전 작업이 세션을 복원하지 못하게 한다. 인증 화면에는 `FLAG_SECURE`를 적용했다.
- 모호한 JSON·중복 키·잘못된 UTF-8·과도한 깊이·비정규 JWT 인코딩·중복 JWK 식별자·날짜 overflow를 거절한다. 좌표·정확도·배터리·토큰 만료의 범위와 정수 여부는 원문 십진수 정밀도로 검증한다.
- API의 중복 인증 헤더, 모호한 HTTP framing, 과도한 입력·동시 읽기·느린 연결을 차단한다. 참조된 grant/consent의 ID·revision·scope를 검증한다. 운영자가 명시한 subject/UE binding은 신뢰되는 권한 입력이다.
- 이벤트를 요청당 한 번 인덱싱해 허용된 2만 이벤트/100개 키링 입력의 조회를 약 364–371ms에서 최종 약 4.5–10.1ms로 줄였다.
- 앱 API/OIDC의 TLS 1.3 정책에 맞춰 최소 Android 10(API 29), target/compile API 36을 사용한다. release는 시스템 인증서만 신뢰한다. 로그인 저장소는 백업·기기 이전에서 제외한다.

## 최종 검증

| 항목 | 결과 |
| --- | --- |
| Android JUnit | Debug 56 / Release 47, 실패·오류 0 |
| Android lintRelease | 0 issues |
| CORE/INTEGRATION | 21개 통과 |
| 위치 API | 30개 시험 × 3개 seed, 공격 입력 총 38,958개, 실패 0 |
| 실제 기기 보안 | 세션 15 + 인증 12 = 27개 assertion 통과 |
| 최적화 release UI | 지도 렌더링·확대·이동, 읽기 전용 서비스 주소, 개발 메뉴 제외, 오프라인 고지, 라이트/다크·200% 글씨·가로 회전 등 11개 검사 통과 |
| 패키지 검사 | 36개 통과: 시험 클래스 제외, 권한·TLS·백업·callback, 64bit ELF NX/RELRO 및 ELF/ZIP 16KB 정렬 |
| 의존성 검증 | 정확한 SHA256 metadata의 정상 해석 성공, 격리 사본의 잘못된 Nimbus 해시 거절 |
| SBOM/SCA | CycloneDX 1.6 공식 스키마 통과, 145개 구성요소, 정확한 라이브러리 버전 117개 OSV 조회에서 알려진 매칭 0 |

0.3.0 debug와 최적화 release의 DEX 합계는 각각 13,906,504 / 1,713,984 bytes로 약 87.7% 감소했다. 범용 release APK는 94,433,233 bytes, AAB는 35,896,909 bytes다. 범용 APK는 네이티브 ABI 사본을 함께 포함하므로 실제 스토어의 기기별 다운로드 크기를 뜻하지 않는다. 비교 원본은 [크기·해시 기록](evidence/release-size-comparison.json)에 있다.

공급망 metadata는 HTTPS 저장소에서 받은 최초 바이트를 SHA256으로 고정한 기준이다. 최초 공급자의 진위를 별도로 인증한 서명 검증으로 표현하지 않는다. 정상 기준과 변조한 기대 해시의 차단은 [격리 시험](evidence/dependency-verification-negative.json)에서 확인했다. 새 의존성은 검토 후 lock과 checksum을 명시적으로 갱신한다.

지도 터치 listener는 상위 ScrollView의 가로채기만 조절하고 `false`를 반환한다. 지도 SDK가 제스처·클릭을 처리하므로 별도의 가짜 `performClick`을 추가하지 않았다. `ClickableViewAccessibility` 예외는 해당 초기화 메서드에 한정한다. `ViewConstructor` 예외는 Bundle로 생성하는 코드 전용 View에 한정한다. 실제 지도 확대·이동 및 목록 대체 탐색과 큰 글씨를 기기에서 확인했다. 화면읽기 도구 전체 인증을 의미하지는 않는다.

## 산출물과 기기 상태

- [SBOM·패키지 해시와 공급망 증거](README.md), [공격면·위협 모델](THREAT_MODEL.md), [Android 회귀·퍼징](ANDROID_TESTS.md), [API 공격 시험](../../pc-mobius/mobile-api/SECURITY-TESTS.md), [릴리스 검증 절차](BUILD_GATES.md).
- [실제 기기 세션 결과](evidence/device-session-security.json), [QA 설치·서명 구분](evidence/installed-qa-release.json), [최적화 UI 검사](evidence/release-ui-checks.json), [패키지 검사](release-inspection.json).
- [라이트 지도](../evidence/security-release-map-light.png), [다크 지도](../evidence/security-release-map-dark.png), [큰 글씨](../evidence/security-release-large-font.png), [큰 글씨·가로](../evidence/security-release-large-landscape.png).

기존 0.2.0 APK와 세션 설정 백업은 workspace `.local/security-release/`에 보관했다. 원래 세션에는 인증 정보가 없었다. 테스트 세션은 삭제했고 기존 앱의 전체 데이터는 지우지 않았다. 최종 앱은 `debuggable=false`이며 `run-as`가 거절됨을 확인했다. 임시 instrumentation 패키지와 UI dump를 제거하고 글자·회전·테마·화면 꺼짐 설정을 원래 값으로 복원했다. USB reverse는 원래 비어 있었으며 새 연결을 만들지 않았다. 시스템·브라우저 CA 신뢰와 기존 CSE 서비스는 변경하지 않았다. [정리 결과](evidence/device-cleanup.json).

QA 서명 사본은 `.local/security-release/bubble-0.3.0-qa-signed.apk`, 미서명 배포 산출물은 `app/app/build/outputs/apk/release/app-release-unsigned.apk`와 `app/app/build/outputs/bundle/release/app-release.aab`다. 원본 0.2.0의 복원은 `.local/security-release/original.apk`와 백업을 확인한 뒤 버전·서명·데이터 호환성을 고려해 진행한다. 자동 삭제나 데이터 초기화를 복원 대안으로 사용하지 않는다.

## 운영 배포 전 남는 조건

실제 HTTPS API·IdP·등록 client 및 키링 subject/UE binding, 운영 서명, 실제 계정의 로그인·refresh 회전/재사용 탐지·폐기, 물리 키링 좌표 경로를 운영 환경에서 검증해야 한다. 현재 기기의 배경 지도는 실물 키링의 현재 위치를 주장하지 않는다. Android 10/16 기기별 실행과 16KB 페이지 기기에서의 동작은 별도 배포 매트릭스에 남아 있다.

OSV 매칭 0은 안전 인증이 아니다. 네이티브 정적 내부 의존성은 파일 해시와 공식 법적 고지를 남겼지만 패키지 버전별로 분해해 검사하지 못했다. 읽기 API 앞의 HTTPS proxy 요청 제한·rate limit, 비공개 mutation ingress, custom-scheme callback의 도메인 소유 검증 한계는 [위협 모델](THREAT_MODEL.md)에 남겼다. 실제 운영 IdP의 브라우저 로그인 중 회전·프로세스 kill 시나리오를 로컬 모델 시험으로 대체해 통과했다고 주장하지 않는다.
