package org.privacybubble.app;

public final class TestResourceTimestampTest {
    public static void main(String[] args) {
        for (String valid : new String[]{"20261028T102434", "20240229T235959"}) {
            if (!valid.equals(TestResourceTimestamp.rawOrNull(valid))) throw new AssertionError("valid timestamp rejected");
        }
        for (Object invalid : new Object[]{null, 123, "", "20230229T000000", "20261301T000000", "20260101T240000", "00000101T000000", "20261028T102434Z", "20261028T102434\n"}) {
            if (TestResourceTimestamp.rawOrNull(invalid) != null) throw new AssertionError("invalid timestamp accepted");
        }
        System.out.println("PASS: CSE raw timestamp validation and absent values");
    }
}
