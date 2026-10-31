package org.privacybubble.app.net;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StrictJson {
    private StrictJson() { }

    public static String utf8(byte[] bytes, int maximum) {
        if (bytes == null || bytes.length > maximum) throw invalid();
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) { throw invalid(); }
    }

    public static Map<String, Object> object(String text, int maximum) {
        if (text == null || text.length() > maximum) throw invalid();
        Parser parser = new Parser(text);
        Object result = parser.value(0);
        parser.space();
        if (!(result instanceof Map) || parser.position != text.length()) throw invalid();
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>)result;
        return object;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid JSON document"); }

    private static final class Parser {
        private final String text;
        private int position, values;
        Parser(String text) { this.text = text; }

        private void space() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
                position++;
            }
        }

        private boolean take(char c) {
            if (position < text.length() && text.charAt(position) == c) { position++; return true; }
            return false;
        }

        private Object value(int depth) {
            if (depth > 16 || ++values > 8192) throw invalid();
            space();
            if (position == text.length()) throw invalid();
            char first = text.charAt(position);
            if (first == '"') return string();
            if (take('{')) {
                Map<String, Object> object = new LinkedHashMap<>();
                space();
                if (take('}')) return object;
                do {
                    space();
                    if (position == text.length() || text.charAt(position) != '"') throw invalid();
                    String name = string();
                    if (object.containsKey(name)) throw invalid();
                    space();
                    if (!take(':')) throw invalid();
                    object.put(name, value(depth + 1));
                    space();
                    if (take('}')) return object;
                } while (take(','));
                throw invalid();
            }
            if (take('[')) {
                List<Object> array = new ArrayList<>();
                space();
                if (take(']')) return array;
                do {
                    array.add(value(depth + 1));
                    space();
                    if (take(']')) return array;
                } while (take(','));
                throw invalid();
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (text.startsWith(literal, position)) {
                    position += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = position;
            take('-');
            if (!take('0')) digits();
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
            if (position - start == 0 || position - start > 64) throw invalid();
            try { return new BigDecimal(text.substring(start, position)); }
            catch (NumberFormatException failure) { throw invalid(); }
        }

        private void digits() {
            int start = position;
            while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++;
            if (position == start) throw invalid();
        }

        private String string() {
            if (!take('"')) throw invalid();
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    for (int index = 0; index < result.length(); index++) {
                        char unit = result.charAt(index);
                        if (Character.isHighSurrogate(unit)) {
                            if (++index >= result.length() || !Character.isLowSurrogate(result.charAt(index))) throw invalid();
                        } else if (Character.isLowSurrogate(unit)) throw invalid();
                    }
                    return result.toString();
                }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (position == text.length()) throw invalid();
                    c = text.charAt(position++);
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            if (position + 4 > text.length()) throw invalid();
                            int unit = 0;
                            for (int index = 0; index < 4; index++) {
                                char hex = text.charAt(position++);
                                int digit = hex >= '0' && hex <= '9' ? hex - '0' : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10 : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                                if (digit < 0) throw invalid();
                                unit = unit * 16 + digit;
                            }
                            c = (char)unit;
                            break;
                        default: throw invalid();
                    }
                }
                result.append(c);
            }
            throw invalid();
        }
    }
}
