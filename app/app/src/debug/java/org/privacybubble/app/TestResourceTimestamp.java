package org.privacybubble.app;

import java.time.DateTimeException;
import java.time.LocalDateTime;

/** Strict raw CSE timestamp validation; never infer its timezone or observation time. */
final class TestResourceTimestamp {
    static String rawOrNull(Object value) {
        if (!(value instanceof String)) return null;
        String text = (String) value;
        if (!text.matches("[0-9]{8}T[0-9]{6}")) return null;
        try {
            int year = Integer.parseInt(text.substring(0, 4));
            if (year == 0) return null;
            LocalDateTime.of(year, Integer.parseInt(text.substring(4, 6)), Integer.parseInt(text.substring(6, 8)),
                    Integer.parseInt(text.substring(9, 11)), Integer.parseInt(text.substring(11, 13)),
                    Integer.parseInt(text.substring(13, 15)));
            return text;
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
    }
    private TestResourceTimestamp() { }
}
