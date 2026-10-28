# 인증된 합성 동의 화면 진입 공기계 확인

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계에서 인증된 합성 동의 화면으로 앱 내부에서 이동하는 동작을 확인했다. 합성 인증이나 서버 요청은 이 시험에서 시작하지 않았다.

기본 debug APK SHA-256 `550b041d45be8c04b20ab65b5f86983186a22402e645bcb144fcc9bbc6748446`를 설치해 메인 화면의 기존 합성 동의 버튼은 있고 인증된 합성 동의 진입 버튼은 없는 것을 확인했다. 같은 소스의 opt-in debug APK SHA-256 `5bbe2f7990fc6acd29354879275e051f145f16b19651c76baa523383ac26677d`에서는 별도 버튼과 시험 전용 설명이 표시됐고 ([화면](auth-consent-navigation.png)), 이를 눌러 인증 전 합성 동의 화면으로 이동했다 ([화면](auth-consent-navigation-target.png)). 해당 화면은 실제 로그인이나 권한 부여가 아니다.

기본 debug/release APK의 시험 Activity 및 네트워크 정책 분리 검사가 통과했다. release APK는 이 화면 진입 테스트를 위해 기기에 설치하지 않았다. 기기 원래 APK는 앱 데이터 삭제 없이 SHA-256 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`로 복원했고, 기기 UI dump를 지우고 기존 `tcp:8787` reverse를 유지했다. 시스템·브라우저 인증서 신뢰는 변경하지 않았다.
