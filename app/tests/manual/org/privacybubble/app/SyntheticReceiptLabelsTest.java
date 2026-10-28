package org.privacybubble.app;

public final class SyntheticReceiptLabelsTest {
    public static void main(String[] args) {
        for (String status : new String[]{"APPLIED", "REJECTED", "TIMEOUT", "INDETERMINATE"}) {
            if (!SyntheticReceiptLabels.label(status).startsWith(status)) throw new AssertionError("status mislabeled");
        }
        try { SyntheticReceiptLabels.label("UNKNOWN"); throw new AssertionError("unknown accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS: distinct synthetic receipt outcomes");
    }
}
