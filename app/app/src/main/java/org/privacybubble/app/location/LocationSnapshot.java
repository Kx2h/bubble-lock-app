package org.privacybubble.app.location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LocationSnapshot {
    public final List<KeyringLocation> keyrings;
    public final String generatedAt;
    public final long readAuthorizedUntilMillis;
    public final String serverReadExpiresAt, provenance;
    public final boolean simulation, caughtUp;
    public final long rejectedRecords;

    public LocationSnapshot(List<KeyringLocation> keyrings, String generatedAt) {
        this(keyrings, generatedAt, 0, null, "mobile-keyrings", false, true, 0);
    }

    public LocationSnapshot(List<KeyringLocation> keyrings, String generatedAt, long readAuthorizedUntilMillis,
            String serverReadExpiresAt, String provenance, boolean simulation, boolean caughtUp, long rejectedRecords) {
        this.keyrings = Collections.unmodifiableList(new ArrayList<>(keyrings));
        this.generatedAt = generatedAt;
        this.readAuthorizedUntilMillis = readAuthorizedUntilMillis;
        this.serverReadExpiresAt = serverReadExpiresAt; this.provenance = provenance;
        this.simulation = simulation; this.caughtUp = caughtUp; this.rejectedRecords = rejectedRecords;
    }

    public boolean isReadAuthorized(long nowMillis) {
        return nowMillis >= 0 && (readAuthorizedUntilMillis == 0 || nowMillis < readAuthorizedUntilMillis);
    }
}
