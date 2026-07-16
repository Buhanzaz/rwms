package dev.buhanzaz.rwms.auth.eventing;

import java.util.List;

public enum AuthAggregateType {
    USER_AUTHORIZATION("rwms.auth.user-authorization.v1"),
    WORKER_ACCESS("rwms.auth.worker-access.v1");

    private final String topic;

    AuthAggregateType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }

    public String sanitizedDltTopic() {
        return topic + ".auth-shadow-v1.dlt";
    }

    public List<String> outputDestinations() {
        return List.of(topic, sanitizedDltTopic());
    }

    public static AuthAggregateType requireTopic(String topic) {
        for (AuthAggregateType aggregateType : values()) {
            if (aggregateType.topic.equals(topic)) {
                return aggregateType;
            }
        }
        throw new IllegalArgumentException("Unsupported auth aggregate-family topic");
    }
}
