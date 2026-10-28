# 서버 주소 초안의 화면 재생성 시험

ROLE: APP · DOC-REV: 2.0.0 · 기준일: 2026-10-29 (Asia/Seoul).

서버 주소를 저장하기 전에 Guardian/Visitor를 전환하거나 화면을 회전하면 입력이 사라지는 결함을 수정했다. 주소 초안·선택 범위·피드백은 화면 재생성에 전달하고, `SharedPreferences`의 저장·삭제는 기존의 명시적인 `주소 저장` 버튼에서만 수행한다. 설정 주소는 여전히 인증이나 API 요청에 사용하지 않는다.

[기기 결과 원본](settings-draft-device.checks.json) · [빌드·복원 receipt](settings-draft-device.receipt.json) · [SHA-256](settings-draft-device.SHA256SUMS)

## 확인 결과

Android 14 공기계의 기존 APK에서 입력 후 Visitor로 전환했을 때 입력이 비어지는 현상을 먼저 재현했다. 수정 debug APK에서는 아래 12개 확인을 모두 통과했다. 시험 주소는 `https://draft.example.org/v1`이며 해당 주소로 네트워크 요청을 보내지 않았다.

- 역할 전환·가로 회전·세로 회전 후 저장 전 초안 유지: 3개.
- 저장하지 않고 강제 종료·재실행하면 초안이 남지 않음: 1개.
- HTTP 주소 거부와 역할 전환 후 오류 피드백 유지: 2개.
- 명시 저장 확인·역할 전환 후 저장 피드백 유지·재실행 후 저장값 유지: 3개.
- 명시 삭제 확인·역할 전환 후 삭제 피드백 유지·재실행 후 삭제값 유지: 3개.

선택 범위 복원과 길이 제한은 독립 소스 검토로 확인했다. 커서 위치나 키보드 복원을 instrumentation으로 검증한 결과는 아니다. 운영 로그인·권한·등록·grant·명령·알림의 완료 증거로 사용하지 않는다.

## 빌드와 재현

수정 소스는 `8c3e30c`, Windows ADB 경로 수정은 `e201002`에 고정했다. APK는 해당 소스 변경을 커밋하기 전에 빌드했으며, receipt에 소스 파일 해시와 실제 시험 APK 해시를 기록했다. 격리 도구를 사용한 `:app:assembleDebug :app:assembleRelease`와 `app/scripts/check-build-isolation.sh`가 통과했다. 기존 AGP/SDK XML 버전 경고는 남아 있다. 이 실행에서 별도 호스트·암호 단위시험을 다시 실행하지 않았다.

재현 시 [빌드·기기 관리 안내](../README.md)를 따른다. 기기 사용자·설치 APK와 설정 파일을 먼저 확인·백업하고 다음을 수행한다.

1. 기존 앱 데이터를 지우지 않고 debug APK를 `install -r`로 설치한다. 서버 설정에 시험 HTTPS 주소를 입력하고 저장 전에 역할 전환·회전 후 입력을 확인한다.
2. 저장하지 않고 앱을 강제 종료한 뒤 재실행해 초안이 저장되지 않았는지 확인한다.
3. HTTP 주소의 저장 거부를 확인한다. HTTPS 주소의 명시 저장과 빈 주소의 명시 삭제를 각각 실행하고 역할 전환·재실행 후 결과를 확인한다.
4. 시험에서 바꾼 설정과 회전 값을 복원하고 원본 APK를 `install -r`로 복원한다. APK 해시·설정·reverse 목록과 소유 시험 파일 정리를 확인한다.

실제 시험 자동화와 원본 APK·설정 백업은 workspace `.local/server-settings-draft-20261029/`에 보관했다. Windows ADB가 읽을 수 없는 비공개 백업 폴더는 유지하고, 복원 APK만 읽을 수 있는 workspace 하위 폴더에 임시 배치한 뒤 그 파일과 폴더를 제거했다. ADB wrapper는 이제 `install -r`처럼 옵션이 있는 경우에도 최종 로컬 APK 경로를 변환한다.

## 복원

기존 APK의 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`와 복원본이 일치했다. 시험 전 존재하지 않았던 `connection.xml`은 시험 저장·삭제 후 빈 파일임을 확인하고 제거했다. 앱 전체 데이터는 삭제하지 않았다. 회전 설정·stay-on·reverse 목록은 시험 전과 일치하고, 소유 UI dump와 APK 임시 배치 파일은 제거했다. 시스템·브라우저 신뢰는 변경하지 않았다.
