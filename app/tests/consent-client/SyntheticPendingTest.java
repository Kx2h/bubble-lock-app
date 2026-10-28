package org.privacybubble.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class SyntheticPendingTest {
    private static void rejected(Runnable action) {
        try { action.run(); throw new AssertionError("invalid data accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        String epoch = UUID.randomUUID().toString(), key = UUID.randomUUID().toString(), correlation = UUID.randomUUID().toString();
        SyntheticPending approve = new SyntheticPending(epoch, "approve", SyntheticPending.APPROVE_BODY, key, correlation);
        SyntheticPending restored = SyntheticPending.decode(approve.encode());
        if (!restored.instance.equals(epoch) || !restored.key.equals(key) || !restored.correlation.equals(correlation)
                || !restored.body.equals(SyntheticPending.APPROVE_BODY) || !restored.approving) throw new AssertionError("lost request fields");
        SyntheticPending revoke = new SyntheticPending(epoch, "revoke", "{\"requestId\":\"synthetic-request-1\",\"grantRevision\":2147483647}", key, correlation);
        if (SyntheticPending.decode(revoke.encode()).approving) throw new AssertionError("wrong action");
        rejected(() -> SyntheticPending.decode(null));
        rejected(() -> SyntheticPending.decode(approve.encode() + "\n"));
        rejected(() -> SyntheticPending.decode(approve.encode().replace(epoch, "not-a-uuid")));
        rejected(() -> SyntheticPending.decode(approve.encode().replace(epoch, "11111111-1111-1111-8111-111111111111")));
        rejected(() -> SyntheticPending.decode(approve.encode().replace(epoch, "11111111-1111-4111-7111-111111111111")));
        rejected(() -> SyntheticPending.decode(approve.encode().replace("approve", "revoke")));
        rejected(() -> new SyntheticPending(epoch, "approve", SyntheticPending.APPROVE_BODY.replace("120", "900"), key, correlation));
        rejected(() -> new SyntheticPending(epoch, "revoke", "{\"requestId\":\"synthetic-request-1\",\"grantRevision\":2147483648}", key, correlation));
        rejected(() -> new SyntheticPending(epoch, "revoke", "{\"requestId\":\"other\",\"grantRevision\":1}", key, correlation));
        String badUtf8 = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xff});
        rejected(() -> SyntheticPending.decode("v1\n"+epoch+"\napprove\n"+key+"\n"+correlation+"\n"+badUtf8));
        System.out.println("PASS: persisted synthetic requests preserve exact identifiers and reject corruption");
    }
}
