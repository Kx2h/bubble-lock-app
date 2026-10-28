# 임시 Mobius4 전경 자동 갱신 공기계 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계에서 debug APK SHA-256 `b5c1ab35af353b98e641f4f8ae635917574cdd62c02aa108f374f0cbeae52f1e`를 사용했다. 기존 `tcp:8787` ADB reverse와 APP 중계를 통해 임시 Mobius4 CSE를 읽었다. 이 시험은 운영 인증·권한이나 실제 키링 관측을 검증하지 않는다.

- 사용자가 자동 갱신을 켠 뒤 CSE 상태 `꺼짐`, bootstrap 표식과 실제 관측 여부 미확인을 표시했다. 서로 다른 조회 시각과 요청 ID를 통해 주기적인 재조회도 확인했다 ([화면](mobile-autorefresh-success.png)).
- 직접 시작한 중계를 중지하자 자동 갱신은 오류를 표시했고 과거 값을 현재 상태로 남기지 않았다 ([화면](mobile-autorefresh-failure.png)). 중계를 다시 시작하자 자동 조회가 성공으로 복구됐다 ([화면](mobile-autorefresh-recovered.png)).
- 사용자가 자동 갱신을 중지하자 꺼짐 표시가 나왔고 12초 뒤에도 같은 조회 시각과 요청 ID가 유지됐다 ([화면](mobile-autorefresh-stopped.png)). 이는 표시 관찰이며 별도의 패킷 계측은 아니다.
- 자동 갱신을 다시 켠 뒤 홈 화면으로 이동하고 앱에 돌아오자 상태 미확인과 자동 갱신 꺼짐이 표시됐다 ([화면](mobile-autorefresh-background.png)). 이미 시작된 요청의 즉각적인 취소는 보장하지 않는다.

시험 후 직접 시작한 중계만 종료했고 시험 중 생성한 UI dump를 삭제했다. 이전 APK를 데이터 삭제 없이 복원하여 설치 APK SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`를 확인했다. 기존 `tcp:8787` reverse와 임시 CSE는 유지했고 시스템·브라우저 인증서 신뢰는 변경하지 않았다.
