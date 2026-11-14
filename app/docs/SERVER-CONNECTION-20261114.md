# 0.4.1 실제 서버·휴대폰 연결 결과

ROLE: APP · DOC-REV: 2.0.0 · 2026-11-14 Asia/Seoul · versionCode 5.

**연결된 삼성 휴대폰의 앱에서 실제 guardian/operator 로그인, 위치·대시보드 조회와 로그아웃을 통과했다.** 설치된 최적화 release와 인계 APK의 바이트가 일치한다. 서버가 반환하는 실제 위치 관측은 현재 0개이며 표본 좌표를 추가하지 않았다.

## 연결 방식

사용자가 지정한 사설 서버를 신뢰한다는 명시적 지시에 따라, `https://bubblelock.iptime.org`의 HTTPS 인증서를 앱 전용 exact-domain trust anchor와 SPKI pin으로 구성했다. Android·외부 브라우저의 인증서 설정은 변경하지 않았다. 앱에서 TLS 오류를 무조건 허용하는 handler나 trust-all 검증기를 사용하지 않는다.

현재 운영 중인 웹 OIDC/BFF 인증은 두 계정으로 정상 작동했다. 새 `serverPortalOrigin`/`serverPortalCertificate` 프로필은 앱 실행 시 해당 제품을 `ServerPortalActivity`에서 바로 연다. BFF가 기존 `bubble-lock-dashboard` client의 Code/PKCE·토큰 교환·refresh·권한 검사를 맡고, 앱은 WebView 내부의 Secure/HttpOnly 세션을 사용한다. 앱 코드·설정에 비밀번호·client secret·access/refresh token을 넣거나 쿠키를 Java로 추출하지 않는다.

이 실행 경로에는 새 Android public OIDC client나 native `/v1` proxy가 필요하지 않다. 원격 서버 설정을 수정하거나 새 native client를 등록했다고 주장하지 않는다. 기존 배포의 실제 인터페이스에 앱을 연결한 것이다. 기존 MapLibre/native OIDC 모드는 별도 빌드 경로로 보존했다. [프로필과 빌드 방법](../deployment/SERVER_PORTAL.md)

## 실제 계정·기기 시험

| 실행 대상 | 결과 |
|---|---|
| 호스트의 실제 웹 OIDC | guardian/operator 각각 로그인→callback→인증된 session→locations/snapshot 200→logout 200→미인증 session 확인 |
| 삼성 SM-S908N / Android 14 | 연결된 실제 휴대폰에서 실행. WebView provider 157.0.8103.50 |
| Debug 앱 | 실제 서버 assertion 45개 통과 |
| 최적화 release 앱 | assertion 46개 통과. 실제 target ApplicationInfo에서 debuggable=false 확인 |
| 계정 권한 | 두 계정의 principal·portal:read 확인, guardian의 dashboard:read 부재와 operator의 보유 확인 |
| 실제 화면 | guardian의 `#/user/map`, operator의 `#/operator/map`에서 역할별 shell·map DOM·AuthGate 부재 확인. 안정화 중 JS fatal event 0 |
| 물리 입력 | 최적화 release에서 ADB 터치로 홈·뒤로·새로고침 동작, 역할 진입 화면과 WebView 유지 확인 |
| 실행 아이콘 | PackageManager의 launcher가 ServerPortalActivity로 해석되며 launcher event로 실제 해당 Activity가 전경 실행됨 |
| 실제 관측 | 두 계정 모두 devices=0, positions=0, BLE=0. 핀·텔레메트리를 생성하거나 실물 위치를 주장하지 않음 |

기기 로그인 자동화는 app-private QA 입력 파일을 잠깐 읽어 정확한 Keycloak form에 제출한다. 출력에는 boolean·개수·고정 검사명만 남기며 비밀번호·쿠키·토큰·응답 본문·OAuth query는 남기지 않는다. 테스트가 끝나면 서버 로그아웃, 이 앱의 WebView 쿠키와 SSO 상태, QA 입력 파일을 정리한다. Debug와 release 모두 동일한 운영 endpoint와 실제 계정을 사용했다. [기기·호스트 증거](../evidence/server-connect-20261114/README.md)

이 결과는 실제 앱/서버 경로와 일부 UI 자동화의 증거다. 화면 읽기 프로그램의 수동 수용, 여러 OS·화면·GPU, 장시간 사용·배터리·네트워크 전환 또는 장치 제어 전체의 완료 판정은 아니다. 지도 DOM 검사는 외부 타일 서비스의 SLA나 실물 좌표의 정확도를 증명하지 않는다.

## 빌드·보안 검사

- Debug 92 / Release 83 단위시험, lint 0 issues.
- Portal release 패키지 검사 42개 통과: 정확한 host/공개 인증서/pin·실제 launcher·시험 코드 제외·백업/권한·native NX/RELRO/16KB 경계 포함.
- 전체 release gate의 실제 build·서버 시험·도구 시험·SBOM/schema/OSV stage 통과. 루트 `npm run verify` 13단계도 통과.
- SBOM 492 components / 493 nodes. 정확한 패키지 버전 417개를 5 batch로 조회해 해당 시점의 알려진 OSV 매칭 0건.
- 기본 native 프로필도 별도로 빌드해 system-only trust와 synthetic CA 제외를 검사했다. 운영 프로필의 사설 인증서가 기본 빌드로 섞이지 않는 것을 확인했고, 운영 APK도 동일 바이트로 재생성했다. AAB 재빌드는 런타임 엔트리가 동일하며 R8의 실행시간 metadata만 달라, 전체 gate에서 검증한 원본 AAB를 인계 산출물로 보존했다. [재빌드 비교](../evidence/server-connect-20261114/aab-rebuild-comparison.json)

공개 CVE 버전 조회와 회귀시험의 범위는 위와 같다. CVE 자체의 취약/수정 버전별 동일 PoC 재현, 장시간 coverage-guided fuzzing, native 정적 내부 의존성·OS/container·실제 원격 설치 패키지 전체의 검증 완료로 표현하지 않는다. [현재 APK/AAB의 SBOM·SCA](../security/server-connect-20261114/README.md)

## 설치·정리

휴대폰에는 QA 키로 서명한 최적화된 **0.4.1 운영 서버 연결 APK**를 남겼다. 조직/스토어 운영 서명본은 아니다. 기존 앱은 `install -r`로 갱신했으며 전체 앱 데이터를 지우지 않았다. 이번 instrumentation 패키지는 설치 hash를 확인한 뒤 Android user 0에서만 제거했다. 기기의 다른 사용자·Secure Folder는 제거하지 않았다.

QA 입력·세션을 정리하고 표시 설정을 기준값으로 복원했다. 원래 비어 있던 reverse 연결도 그대로이며 시스템/브라우저 인증서 설정은 변경하지 않았다. 인계 APK SHA256은 `fd9e91807a69555d1bd0fe9208844de25b36116241d8d060bdebf3622570c29d`이다. [최종 기기 상태](../evidence/server-connect-20261114/device-final-state.json)

이전 0.4.0의 미설정 기본 프로필과 당시 연결 대기 기록은 [이전 통합 보고서](DEPLOYMENT-20261114.md)에 보존한다. 해당 대기 조건을 현재 BFF 운영 연결 프로필의 미완료 조건으로 사용하지 않는다.
