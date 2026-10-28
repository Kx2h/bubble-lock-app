# APP 실기기 읽기 재시도 시험

- ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03 · Test ID: APP-MOCK-RETRY-01
- 날짜: 2026-10-28 현지 시간 · Android 14 / API 34
- 기존 테스트 앱 설치본의 SHA-256 `b675b66ea205813877c1ea2c8660a96bef8f6034db4118fba37e2b91f4736e93`을 확인하고 새 debug APK를 `adb install -r`로 설치했다. 새 호스트 APK 및 휴대폰 설치본의 SHA-256은 `e03dbc36af622ee798685c4c3db9c52b926de7cfb469ce4f006ed81d3eb6a1e3`으로 일치했다.
- 경로는 실제 앱 → 기존 Windows ADB reverse → **모의 APP 중계**다. 이 시험에는 Mobius CSE나 실제 키링 응답을 사용하지 않았다.
- 기대: 상태 조회 버튼을 한 번 누르면 첫 GET의 HTTP 503 후 한 번만 재시도해 HTTP 200 응답을 표시한다.
- 실제: 모의 중계의 런타임 출력을 통해 GET 1의 HTTP 503, GET 2의 HTTP 200을 순서대로 확인했다. 앱은 `테스트 상태: 꺼짐`, `초기값 · 장치 관측 전`, `요청 ID: mock-retry-device-test`를 표시했다. [mobile-retry-mock.png](mobile-retry-mock.png)는 화면 증거다.
- 앱에서 한 번 클릭한 동안 관측된 중계 출력: `{"mode":"mock","requestNumber":1,"status":503}`, `{"mode":"mock","requestNumber":2,"status":200}`. 위 내용은 실행 중 도구 출력에서 전사한 것으로 별도 서버 로그 파일은 아니다.
- 화면은 `adb exec-out screencap -p`로 호스트에 저장했다. 중계는 시험 후 종료했으며 기존 reverse와 기기 앱은 유지했다. 기기 시스템 설정이나 사용자 파일은 변경하지 않았다.
- 이 시험은 GET 읽기의 한정된 모의 재시도 사례다. 실제 Mobius4 장애, 쓰기 재시도, 인증 또는 권한 검증의 증거가 아니다.

## 새 APK의 별도 실제 CSE 정상 조회

- 모의 중계를 종료한 후 APP 중계를 `http://127.0.0.1:50545/Mobius`의 격리 Mobius4에 연결하고 같은 앱에서 상태 조회 버튼을 다시 눌렀다. 이 정상 조회는 위 503 재시도 시험과 별개다.
- 앱은 `꺼짐`, `초기값 · 장치 관측 전`, 요청 ID `app-test-d1d705a0-bd50-4fff-a377-7a63d305b366`을 표시했고 [mobile-retry-real-cse.png](mobile-retry-real-cse.png)로 기록했다. 실제 CSE 로컬 로그에서 동일 요청 ID가 기록된 2개 행을 확인했다. 민감정보가 있을 수 있는 원본 로그와 개발 관리자 ID는 보관소에 넣지 않았다.
- 실제 CSE의 초기 `false/unobserved`는 키링 실제 관측이 아니다. 실제 CSE 경로에서 일시 오류나 재시도는 발생시키지 않았다.
- 시험이 시작한 중계만 종료했고 CSE와 기존 reverse, 앱은 유지했다.
