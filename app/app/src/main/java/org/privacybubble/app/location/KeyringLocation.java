package org.privacybubble.app.location;

import java.time.Instant;

public final class KeyringLocation {
    public final String id, name, status, source, observedAt, receivedAt, expiresAt, connectivity;
    public final Double latitude, longitude, accuracyMeters;
    public final Integer batteryPercent;
    public final boolean simulation, bleSimulation;
    public final String provenance, gatewayId, bleObservedAt, relayAssociationId;
    public final Integer rssiDbm;

    public KeyringLocation(String id, String name, String status, Double latitude, Double longitude,
            Double accuracyMeters, String observedAt, String receivedAt, String expiresAt,
            String source, String connectivity, Integer batteryPercent) {
        this(id, name, status, latitude, longitude, accuracyMeters, observedAt, receivedAt, expiresAt,
                source, connectivity, batteryPercent, false, "legacy", null, null, null, false, null);
    }

    public KeyringLocation(String id, String name, String status, Double latitude, Double longitude,
            Double accuracyMeters, String observedAt, String receivedAt, String expiresAt,
            String source, String connectivity, Integer batteryPercent, boolean simulation, String provenance,
            String gatewayId, Integer rssiDbm, String bleObservedAt, boolean bleSimulation, String relayAssociationId) {
        this.id = id; this.name = name; this.status = status;
        this.latitude = latitude; this.longitude = longitude; this.accuracyMeters = accuracyMeters;
        this.observedAt = observedAt; this.receivedAt = receivedAt; this.expiresAt = expiresAt;
        this.source = source; this.connectivity = connectivity; this.batteryPercent = batteryPercent;
        this.simulation = simulation; this.provenance = provenance;
        this.gatewayId = gatewayId; this.rssiDbm = rssiDbm; this.bleObservedAt = bleObservedAt;
        this.bleSimulation = bleSimulation; this.relayAssociationId = relayAssociationId;
    }

    public KeyringLocation asStale() {
        if (!hasCoordinates() || "stale".equals(status)) return this;
        return new KeyringLocation(id, name, "stale", latitude, longitude, accuracyMeters, observedAt, receivedAt,
                expiresAt, source, connectivity, batteryPercent, simulation, provenance, gatewayId, rssiDbm,
                bleObservedAt, bleSimulation, relayAssociationId);
    }

    public boolean hasCoordinates() {
        return ("available".equals(status) || "stale".equals(status))
                && latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    public boolean isFresh(long nowMillis) {
        if (!"available".equals(status) || !hasCoordinates() || expiresAt == null || observedAt == null) return false;
        try {
            return Instant.parse(expiresAt).toEpochMilli() > nowMillis
                    && Instant.parse(observedAt).toEpochMilli() <= nowMillis + 30_000;
        } catch (RuntimeException invalidTime) { return false; }
    }
}
