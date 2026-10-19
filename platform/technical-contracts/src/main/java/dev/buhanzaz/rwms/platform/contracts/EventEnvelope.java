package dev.buhanzaz.rwms.platform.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned, framework-neutral envelope for facts published by an owning service. */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        CorrelationContext correlation,
        ActorSnapshot actor,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        requireText(eventType, "eventType");
        requireText(producer, "producer");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
