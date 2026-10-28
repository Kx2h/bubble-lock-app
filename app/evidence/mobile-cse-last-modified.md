# 임시 Mobius4 CSE 자원 변경 시각 조회 실기기 시험

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. 2026-10-28 Android 14 공기계와 debug APK SHA-256 `5b61333c4409c20bf42b74c5b248f599ddf8f67729c50bfbe301f30f8429d863`로 시험했다.

기존 `tcp:8787` reverse와 로컬 APP 중계를 통해 임시 Mobius4 CSE에서 `bubbleState`를 RETRIEVE했다. 중계는 HTTP 200, oneM2M rsc 2000과 일치하는 요청 ID, `rn`/`cnd`를 확인했다. 화면은 `꺼짐`, `초기값 · 장치 관측 전`, CSE 자원 변경 시각 원문 `20261028T102434`와 시간대 미표기, 기기 시계로 표시한 별도의 조회 시각을 보여 주었다 ([화면](mobile-cse-last-modified.png)). 자원 변경 시각은 기기 관측 시각이 아니며 bootstrap의 상태도 실제 장치 관측이 아니다. 요청 ID는 임시 로컬 시험값이다.

이 시험은 실물 Gateway·키링, 운영 인증·권한 또는 실제 사용자 접근을 검증하지 않는다. 원문은 지원하는 `YYYYMMDDThhmmss` 형식만 표시하며 timezone을 추정·변환하지 않는다. 호스트 시험에서 누락·잘못된 날짜·지원하지 않는 형식의 값은 미확인으로 처리했다.

시험 후 직접 기동한 APP 중계만 종료하고 이전 APK를 데이터 삭제 없이 복원했다. 복원된 설치 APK SHA-256은 `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`; 기존 `tcp:8787` reverse와 임시 CSE는 유지했다. 시스템·브라우저 인증서 신뢰는 변경하지 않았다.
