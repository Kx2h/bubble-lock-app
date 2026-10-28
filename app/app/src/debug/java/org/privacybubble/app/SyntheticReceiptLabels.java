package org.privacybubble.app;

/** Distinct candidate KEYRING outcomes; none implies current physical state. */
final class SyntheticReceiptLabels {
    static String label(String value) {
        switch (value) {
            case "APPLIED": return "APPLIED · 적용 결과";
            case "REJECTED": return "REJECTED · 거부";
            case "TIMEOUT": return "TIMEOUT · 응답 시간 초과, 실행 여부 미확인";
            case "INDETERMINATE": return "INDETERMINATE · 결과 미확정";
            default: throw new IllegalArgumentException("Unknown synthetic status");
        }
    }
    private SyntheticReceiptLabels() { }
}
