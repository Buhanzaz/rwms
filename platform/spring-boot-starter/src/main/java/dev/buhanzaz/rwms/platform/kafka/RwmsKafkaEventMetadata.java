package dev.buhanzaz.rwms.platform.kafka;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the technical metadata required to publish a canonical V2 aggregate-keyed event through the platform publisher. */
public record RwmsKafkaEventMetadata(
        int envelopeVersion,
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        String producer,
        Instant recordedAt,
        UUID correlationId,
        UUID causationId) {

    private static final Pattern EVENT_TYPE = Pattern.compile(
            "^([a-z0-9]+(?:-[a-z0-9]+)*)\\.([a-z0-9]+(?:-[a-z0-9]+)*)\\.([a-z0-9]+(?:-[a-z0-9]+)*)\\.v([1-9][0-9]*)$");
    private static final Pattern PRODUCER = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern AGGREGATE_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Pattern AGGREGATE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$");
    // This is a frozen cross-service contract: its compact event segment is intentionally
    // different from the stable technical aggregate name consumed by media-service.
    private static final String TASK_BOARD_OWNER_PROOF_PRODUCER = "task-board-service";
    private static final String TASK_BOARD_OWNER_PROOF_EVENT_TYPE = "task-board.entry-owner-proof.changed.v1";
    private static final String TASK_BOARD_OWNER_PROOF_AGGREGATE_TYPE = "TASK_BOARD_ENTRY_OWNER_PROOF";
    private static final String TASK_BOARD_OWNER_PROOF_DESTINATION = "rwms.task-board.entry-owner-proof.v1";

    public RwmsKafkaEventMetadata {
        if (envelopeVersion != 2) {
            throw new IllegalArgumentException("envelopeVersion must be 2");
        }
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(recordedAt, "recordedAt is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        Matcher eventTypeMatcher = eventType == null ? null : EVENT_TYPE.matcher(eventType);
        if (eventTypeMatcher == null || !eventTypeMatcher.matches()) {
            throw new IllegalArgumentException("eventType must be a versioned lowercase dotted fact name");
        }
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        if (aggregateType == null || !AGGREGATE_TYPE.matcher(aggregateType).matches()) {
            throw new IllegalArgumentException("aggregateType must be a stable uppercase technical name");
        }
        if (!AGGREGATE_ID.matcher(aggregateId == null ? "" : aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId must be a non-blank opaque identifier");
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        if (!PRODUCER.matcher(producer == null ? "" : producer).matches()) {
            throw new IllegalArgumentException("producer must be a non-blank technical name");
        }
        if (Integer.parseInt(eventTypeMatcher.group(4)) != eventVersion) {
            throw new IllegalArgumentException("eventVersion must match the eventType version suffix");
        }
        boolean taskBoardOwnerProof = isTaskBoardOwnerProof(producer, eventType, aggregateType);
        if ((TASK_BOARD_OWNER_PROOF_EVENT_TYPE.equals(eventType)
                        || TASK_BOARD_OWNER_PROOF_AGGREGATE_TYPE.equals(aggregateType))
                && !taskBoardOwnerProof) {
            throw new IllegalArgumentException("task-board entry owner proof must use its frozen producer/eventType/aggregateType contract");
        }
        String canonicalAggregateType = aggregateType.toLowerCase(Locale.ROOT).replace('_', '-');
        if (!eventTypeMatcher.group(2).equals(canonicalAggregateType) && !taskBoardOwnerProof) {
            throw new IllegalArgumentException("aggregateType must match the eventType aggregate segment");
        }
    }

    public String aggregateFamilyDestination() {
        Matcher matcher = EVENT_TYPE.matcher(eventType);
        if (!matcher.matches()) {
            throw new IllegalStateException("validated eventType no longer matches its contract");
        }
        return "rwms.%s.%s.v%s".formatted(matcher.group(1), matcher.group(2), matcher.group(4));
    }

    boolean matchesDestination(String destination) {
        if (isTaskBoardOwnerProof(producer, eventType, aggregateType)) {
            return TASK_BOARD_OWNER_PROOF_DESTINATION.equals(destination);
        }
        return aggregateFamilyDestination().equals(destination);
    }

    boolean isFrozenTaskBoardOwnerProof() {
        return isTaskBoardOwnerProof(producer, eventType, aggregateType);
    }

    public static RwmsKafkaEventMetadata from(DomainEventEnvelopeV2<?> envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        return new RwmsKafkaEventMetadata(
                envelope.envelopeVersion(),
                envelope.eventId(),
                envelope.eventType(),
                envelope.eventVersion(),
                envelope.occurredAt(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                envelope.producer(),
                envelope.recordedAt(),
                envelope.correlation().correlationId(),
                envelope.correlation().causationId());
    }

    private static boolean isTaskBoardOwnerProof(String producer, String eventType, String aggregateType) {
        return TASK_BOARD_OWNER_PROOF_PRODUCER.equals(producer)
                && TASK_BOARD_OWNER_PROOF_EVENT_TYPE.equals(eventType)
                && TASK_BOARD_OWNER_PROOF_AGGREGATE_TYPE.equals(aggregateType);
    }
}
