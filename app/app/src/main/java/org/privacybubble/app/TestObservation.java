package org.privacybubble.app;

/** A bootstrap label is not evidence of a real observation, present or absent. */
final class TestObservation {
    static boolean bootstrapPresent(boolean hasObservation, Object observation, boolean hasLabel, Object label) {
        if (hasObservation && (!(observation instanceof String)
                || !("unknown".equals(observation) || "unobserved".equals(observation)))) {
            throw new IllegalArgumentException("Invalid observation");
        }
        if (hasLabel && !(label instanceof Boolean)) throw new IllegalArgumentException("Invalid bootstrap label");
        boolean legacyLabel = "unobserved".equals(observation);
        if (hasLabel && legacyLabel && !((Boolean) label)) throw new IllegalArgumentException("Conflicting bootstrap labels");
        return hasLabel ? (Boolean) label : legacyLabel;
    }
    private TestObservation() { }
}
