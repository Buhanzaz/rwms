package dev.buhanzaz.rwms.platform.eventing;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Framework-neutral bounded delivery policy for service-owned Kafka consumers.
 * The owning service still configures its listener, consumer group and DLT binding.
 */
public final class RwmsEventDeliveryPolicy {

    public static final int TOTAL_DELIVERY_ATTEMPTS = 4;
    public static final List<Duration> RETRY_BACKOFFS =
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    private static final int KAFKA_TOPIC_MAX_LENGTH = 249;
    private static final Pattern AGGREGATE_FAMILY_TOPIC = Pattern.compile(
            "^rwms\\.[a-z0-9]+(?:-[a-z0-9]+)*\\.[a-z0-9]+(?:-[a-z0-9]+)*\\.v[1-9][0-9]*$");
    private static final Pattern CONSUMER_GROUP =
            Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public FailureDecision afterFailure(FailureCategory category, int failedDeliveryAttempt) {
        Objects.requireNonNull(category, "failure category is required");
        if (failedDeliveryAttempt < 1 || failedDeliveryAttempt > TOTAL_DELIVERY_ATTEMPTS) {
            throw new IllegalArgumentException("failed delivery attempt must be between 1 and 4");
        }
        if (category == FailureCategory.VALIDATION || failedDeliveryAttempt == TOTAL_DELIVERY_ATTEMPTS) {
            return FailureDecision.deadLetter();
        }
        return FailureDecision.retryAfter(RETRY_BACKOFFS.get(failedDeliveryAttempt - 1));
    }

    public String deadLetterTopic(String topic, String consumerGroup) {
        requireAggregateFamilyTopic(topic);
        requireConsumerGroup(consumerGroup);
        String destination = topic + "." + consumerGroup + ".dlt";
        if (destination.length() > KAFKA_TOPIC_MAX_LENGTH) {
            throw new IllegalArgumentException("dead-letter topic exceeds Kafka's maximum topic length");
        }
        return destination;
    }

    private static void requireAggregateFamilyTopic(String topic) {
        if (topic == null
                || topic.length() > KAFKA_TOPIC_MAX_LENGTH
                || !AGGREGATE_FAMILY_TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException("topic must be an exact versioned aggregate-family topic");
        }
    }

    private static void requireConsumerGroup(String consumerGroup) {
        if (consumerGroup == null
                || consumerGroup.length() > 128
                || !CONSUMER_GROUP.matcher(consumerGroup).matches()) {
            throw new IllegalArgumentException("consumer group must be a lowercase hyphenated technical name");
        }
    }

    public enum FailureCategory {
        VALIDATION,
        TRANSIENT_INFRASTRUCTURE
    }

    public enum Action {
        RETRY,
        DEAD_LETTER
    }

    public record FailureDecision(Action action, Duration retryAfter) {

        public FailureDecision {
            Objects.requireNonNull(action, "delivery action is required");
            if (action == Action.RETRY && (retryAfter == null || retryAfter.isZero() || retryAfter.isNegative())) {
                throw new IllegalArgumentException("retry action requires a positive backoff");
            }
            if (action == Action.DEAD_LETTER && retryAfter != null) {
                throw new IllegalArgumentException("dead-letter action must not carry a retry backoff");
            }
        }

        public static FailureDecision retryAfter(Duration backoff) {
            return new FailureDecision(Action.RETRY, backoff);
        }

        public static FailureDecision deadLetter() {
            return new FailureDecision(Action.DEAD_LETTER, null);
        }
    }
}
