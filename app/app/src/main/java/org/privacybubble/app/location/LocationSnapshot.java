package org.privacybubble.app.location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One non-cached, authorized API response. */
public final class LocationSnapshot {
    public final List<KeyringLocation> keyrings;
    public final String generatedAt;

    public LocationSnapshot(List<KeyringLocation> keyrings, String generatedAt) {
        this.keyrings = Collections.unmodifiableList(new ArrayList<>(keyrings));
        this.generatedAt = generatedAt;
    }
}
