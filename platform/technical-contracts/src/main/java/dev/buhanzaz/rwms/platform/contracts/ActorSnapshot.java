package dev.buhanzaz.rwms.platform.contracts;

/** Minimal immutable actor metadata suitable for audit and event envelopes. */
public record ActorSnapshot(String actorId, String actorType, String displayName) {

    public ActorSnapshot {
        requireText(actorId, "actorId");
        requireText(actorType, "actorType");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
