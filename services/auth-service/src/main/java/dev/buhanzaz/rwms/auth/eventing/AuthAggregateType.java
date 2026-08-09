package dev.buhanzaz.rwms.auth.eventing;

import java.util.List;

/**
 * Supported auth aggregate families and their fixed Kafka destinations.
 *
 * <p>The family is part of the event contract: it determines which fact schema is valid for an
 * aggregate stream and which topic the transactional outbox may publish to. It is deliberately
 * closed so application data cannot select an arbitrary broker destination.
 */
public enum AuthAggregateType {
    /** Aggregate family for user authorization snapshots and grant state. */
    USER_AUTHORIZATION("rwms.auth.user-authorization.v1"),
    /** Aggregate family for worker-access snapshots and credential availability. */
    WORKER_ACCESS("rwms.auth.worker-access.v1");

    private final String topic;

    AuthAggregateType(String topic) {
        this.topic = topic;
    }

    /**
     * Returns the canonical topic for authoritative facts in this aggregate family.
     *
     * @return the fixed versioned Kafka destination
     */
    public String topic() {
        return topic;
    }

    /**
     * Returns the destination for sanitized dead-letter metadata from this family.
     *
     * <p>The destination carries only safe rejection metadata; the rejected source record is not
     * forwarded by this method.
     *
     * @return the family-specific sanitized DLT destination
     */
    public String sanitizedDltTopic() {
        return topic + ".auth-shadow-v1.dlt";
    }

    /**
     * Lists every producer destination that must be bound when Kafka is enabled.
     *
     * @return the authoritative fact topic followed by its sanitized DLT topic
     */
    public List<String> outputDestinations() {
        return List.of(topic, sanitizedDltTopic());
    }

    /**
     * Resolves a configured or claimed topic to its supported aggregate family.
     *
     * @param topic exact canonical Kafka topic
     * @return the matching aggregate family
     * @throws IllegalArgumentException when the topic is not owned by auth eventing
     */
    public static AuthAggregateType requireTopic(String topic) {
        for (AuthAggregateType aggregateType : values()) {
            if (aggregateType.topic.equals(topic)) {
                return aggregateType;
            }
        }
        throw new IllegalArgumentException("Unsupported auth aggregate-family topic");
    }
}
