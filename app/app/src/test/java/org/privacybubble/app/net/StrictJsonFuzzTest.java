package org.privacybubble.app.net;

import static org.junit.Assert.*;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class StrictJsonFuzzTest {
    private static final long SEED = 0x5354524943544A53L;
    private Map<String,Object> parse(String value) { return StrictJson.object(value, 65536); }

    @Test public void seededValidJsonAndEscapedDuplicateKeys() {
        Random random = new Random(SEED);
        for (int index=0; index<1024; index++) {
            Map<String,Object> object = new LinkedHashMap<>();
            object.put("name", "키링-" + random.nextInt() + "\"\\\t\n\ud83d\udd11");
            object.put("n", random.nextLong()); object.put("fraction", random.nextDouble());
            object.put("nested", Arrays.asList(true, null, Collections.singletonMap("item", index)));
            String encoded = JSONObjectUtils.toJSONString(object);
            Map<String,Object> decoded = parse(encoded);
            assertEquals(object.get("name"), decoded.get("name"));
            assertEquals(encoded, StrictJson.utf8(encoded.getBytes(StandardCharsets.UTF_8), 65536));
            String duplicate = encoded.substring(0, encoded.length()-1) + ",\"\\u006eame\":\"other\"}";
            assertThrows(IllegalArgumentException.class, () -> parse(duplicate));
        }
        System.out.println("StrictJsonFuzz seed=" + SEED + " valid-documents=1024 escaped-duplicate-documents=1024");
    }

    @Test public void strictGrammarAndUnicodeRejectPermissiveParserExtensions() {
        for (String value : new String[]{"", "[]", "null", "{}{}", "{} trailing", "{'x':1}", "{x:1}", "{\"x\":01}", "{\"x\":+1}", "{\"x\":.1}",
                "{\"x\":1.}", "{\"x\":1e}", "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":undefined}", "{\"x\":1,}", "{\"x\":[1,]}",
                "{/*comment*/\"x\":1}", "{\"x\":1;\"y\":2}", "{\"x\"=1}", "{\"x\":\"\\v\"}", "{\"x\":\"\\uD800\"}", "{\"x\":\"\\uDC00\"}",
                "{\"x\":\"\uD800\"}", "{\"x\":\"a\n b\"}", "{\"x\":null,\"x\":1}", "\uFEFF{}", "{\"x\":0x10}"}) {
            assertThrows("accepted: " + value, IllegalArgumentException.class, () -> parse(value));
        }
        assertEquals("\ud83d\udd11", parse("{\"x\":\"\\ud83d\\udd11\"}").get("x"));
        assertEquals(4, parse("{\"a\":false,\"b\":true,\"c\":null,\"d\":-1.2e+3}").size());
    }

    @Test public void byteLengthDepthTokenLengthAndValueBudgetsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> StrictJson.utf8(new byte[65537], 65536));
        for (byte[] bytes : new byte[][]{{(byte)0xC0,(byte)0xAF}, {(byte)0xED,(byte)0xA0,(byte)0x80}, {(byte)0xF4,(byte)0x90,(byte)0x80,(byte)0x80}, {(byte)0xE2,(byte)0x82}, {(byte)0xFF}}) {
            assertThrows(IllegalArgumentException.class, () -> StrictJson.utf8(bytes, 65536));
        }
        String nested = "{\"x\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}";
        assertThrows(IllegalArgumentException.class, () -> parse(nested));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"n\":" + "1".repeat(65) + "}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"x\":[" + "0,".repeat(8192) + "0]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(" ".repeat(65537)));
    }
}
