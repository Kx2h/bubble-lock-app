# AppAuth state 로그 검토

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10 · 2026-10-28

현재 `OidcBrowserFlow`는 운영 UI가 없고 release에서 시작을 거부한다. 아래는 원본 AAR의 검토 기록이다. 이후 버전 고정 소스 모듈에서 해당 mismatch 분기의 state 값만 로그에서 제거했으며, 비교·거부 동작은 유지했다. 합성 공기계 시험에서 해당 분기의 로그 비노출을 확인했다. 다른 로그 전체의 안전성이나 운영 로그인 준비를 의미하지 않는다. [소스 출처 및 검증](../vendor/appauth/README.md) · [실기기 시험](../evidence/appauth-state-probe.md).

## 확인한 사실

- 원래 앱에 사용한 `net.openid:appauth:0.11.1` AAR의 로컬 Gradle cache SHA-256은 `d9ff43b14f7ee8e81bc802148190ad808beb5389953e6e6ffa53cd5575ea611e`이다.
- [AppAuth 0.11.1 소스의 AuthorizationManagementActivity](https://github.com/openid/AppAuth-Android/blob/0.11.1/library/java/net/openid/appauth/AuthorizationManagementActivity.java) `extractResponseData`는 응답 state가 요청 state와 다르면 두 문자열을 `Logger.warn` 인자로 전달한 다음 `STATE_MISMATCH`를 반환한다. 배포 AAR의 해당 클래스 역어셈블에서도 같은 분기와 로그 호출을 확인했다. 실제 출력 여부는 기기 로그 설정에 좌우된다.
- [AppAuth Logger](https://github.com/openid/AppAuth-Android/blob/0.11.1/library/java/net/openid/appauth/internal/Logger.java)의 `setInstance`는 `@VisibleForTesting` 내부 인터페이스이며 일반 앱을 위한 공개 로그 설정 API가 아니다. 해당 AppAuth 태그의 역참조 commit은 `5966cc7efc1c7cacf78598ecae45b5e7d9365f9c`이며 소스는 Apache-2.0 라이선스다. 2026-10-28에 확인한 upstream master도 같은 state 경고 코드를 포함한다.
- 수신 Activity에서 잘못된 state가 포함된 URI를 먼저 거부하면 해당 경로를 줄일 수 있다. 그러나 그 가드가 기억하는 거래와 AppAuth 관리 Activity가 복원한 거래가 같다는 사실까지 보장하지 않으면, AppAuth 내부에서 불일치가 발생해 로그가 출력될 가능성을 배제할 수 없다. `singleTask` 관리 Activity의 이전 거래가 남거나 프로세스 재생성·중복 거래가 있는 경우의 위험은 소스에 근거한 추론이며 아직 실기기에서 재현하지 않았다. 단순 수신 가드만으로 로그 문제가 해결됐다고 주장하지 않는다.

## 검토한 해결 방향

1. **upstream 수정 또는 버전 고정 소스 패치:** state mismatch 오류와 흐름은 유지하고 로그 메시지에서 두 값을 제거한다. 의존성 출처·원본 hash·Apache-2.0 라이선스·패치 및 재빌드 절차를 함께 고정한다. 가장 직접적이지만 upstream 갱신 시 패치 및 검증 유지보수가 필요하다. 이후 원본 소스 모듈을 고정하고 해당 분기를 패치했다.
2. **수신 가드:** AppAuth의 stock exported redirect receiver를 제거하고 활성 거래의 redirect/state/issuer를 검증한 뒤 유효한 URI만 전달하며, 나머지는 원문 없이 취소한다. 프로세스 종료 후 fail-closed해야 한다. 다만 위 내부 상태 불일치 가능성 때문에 단독 조치로는 로그 제거를 보장하지 않는다.
3. **내부 Logger 교체:** `@VisibleForTesting` 내부 생성자와 전역 상태에 의존하므로 버전 변경, 프로세스 초기화 순서 및 다른 라이브러리 호출에 취약하다. 보안 경계의 공식 해결로 사용하지 않는다.
4. **브라우저 관리 코드 재구현:** stock 관리 Activity를 거치지 않아 해당 로그를 피할 수 있지만, 브라우저 복귀·취소·프로세스 복원·PKCE 수명을 직접 안전하게 구현해야 한다. 충분한 부정·실기기 시험 없이 교체하지 않는다.

패치 후보를 채택할 때에는 정확한 AppAuth 버전과 산출물 hash를 고정하고, 자동 빌드에서 패치 적용 지점이 하나임을 확인해야 한다. 공기계에서 합성 state만 사용해 불일치·중복·취소·프로세스 재시작 시 성공/코드 교환이 발생하지 않는지와 앱 PID의 로그에 두 합성 값이 나타나지 않는지를 확인해야 한다. 로그에는 실제 state나 token을 기록하지 않는다. release에서 테스트 redirect receiver가 제거되고 실제 issuer와 검증된 App Links 계약이 준비됐는지도 별도 확인한다. 합성 기기 시험은 직접 응답 처리 메서드의 불일치·일치·취소와 로그 비노출을 확인했으나, 프로세스 재시작 및 실제 브라우저/운영 인증은 검증하지 않았다.
