package org.privacybubble.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Only fixed synthetic data may be persisted. Never use for an actual consent request. */
final class SyntheticPending {
    static final String APPROVE_BODY = "{\"requestId\":\"synthetic-request-1\",\"revision\":1,\"purpose\":\"synthetic-location-check\",\"scopes\":[\"test.location.read\"],\"ttlSeconds\":120}";
    final String instance, endpoint, body, key, correlation;
    final boolean approving;

    SyntheticPending(String instance, String endpoint, String body, String key, String correlation) {
        if (!uuid(instance) || !uuid(key) || !uuid(correlation)) throw new IllegalArgumentException();
        if ("approve".equals(endpoint)) {
            if (!APPROVE_BODY.equals(body)) throw new IllegalArgumentException();
            approving = true;
        } else if ("revoke".equals(endpoint)) {
            if (body == null || !body.matches("\\{\\\"requestId\\\":\\\"synthetic-request-1\\\",\\\"grantRevision\\\":[1-9][0-9]{0,9}\\}")) throw new IllegalArgumentException();
            String number = body.substring(body.lastIndexOf(':') + 1, body.length() - 1);
            try { if (Integer.parseInt(number) < 1) throw new IllegalArgumentException(); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException(); }
            approving = false;
        } else throw new IllegalArgumentException();
        this.instance = instance; this.endpoint = endpoint; this.body = body; this.key = key; this.correlation = correlation;
    }
    static boolean uuid(String value) {
        try {
            if (value == null) return false;
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) && parsed.version() == 4 && parsed.variant() == 2;
        }
        catch (IllegalArgumentException failure) { return false; }
    }
    String encode() {
        return "v1\n" + instance + "\n" + endpoint + "\n" + key + "\n" + correlation + "\n" + Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }
    static SyntheticPending decode(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException();
        String[] parts = value.split("\\n", -1);
        if (parts.length != 6 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
        byte[] bytes = Base64.getDecoder().decode(parts[5]);
        if (!Base64.getEncoder().encodeToString(bytes).equals(parts[5])) throw new IllegalArgumentException();
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, body.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
        return new SyntheticPending(parts[1], parts[2], body, parts[3], parts[4]);
    }
}
