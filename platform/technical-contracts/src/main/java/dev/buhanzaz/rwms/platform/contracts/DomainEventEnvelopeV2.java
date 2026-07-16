package dev.buhanzaz.rwms.platform.contracts;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Framework-neutral envelope for event-store facts and Kafka integration.
 * Payload implementations must be immutable service-local value types.
 */
public record DomainEventEnvelopeV2<T>(
        int envelopeVersion,
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Instant recordedAt,
        String producer,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        CorrelationContext correlation,
        OpaqueActorReference actorRef,
        T payload) {

    private static final Pattern EVENT_TYPE = Pattern.compile(
            "^([a-z0-9]+(?:-[a-z0-9]+)*)\\.([a-z0-9]+(?:-[a-z0-9]+)*)\\.([a-z0-9]+(?:-[a-z0-9]+)*)\\.v([1-9][0-9]*)$");
    private static final Pattern PRODUCER = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern AGGREGATE_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Pattern AGGREGATE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$");
    private static final Pattern EMAIL =
            Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Z0-9._~+/=-]+");
    private static final Pattern BASIC_AUTHORIZATION = Pattern.compile("(?i)\\bbasic\\s+[A-Z0-9+/=]{4,}");
    private static final Pattern JWT =
            Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern SIGNED_URL =
            Pattern.compile("(?i)[?&](?:x-amz-signature|x-amz-credential|x-amz-security-token|signature|token|sig)=");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])\\+[1-9][0-9 -]{7,20}(?![0-9])");
    private static final int EVENT_TYPE_MAX_LENGTH = 256;
    private static final int PRODUCER_MAX_LENGTH = 128;

    public DomainEventEnvelopeV2 {
        if (envelopeVersion != 2) {
            throw new IllegalArgumentException("envelopeVersion must be 2");
        }
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Matcher eventTypeMatcher = requireMatch(eventType, EVENT_TYPE, EVENT_TYPE_MAX_LENGTH, "eventType");
        requireMatch(producer, PRODUCER, PRODUCER_MAX_LENGTH, "producer");
        requireMatch(aggregateType, AGGREGATE_TYPE, 128, "aggregateType");
        requireMatch(aggregateId, AGGREGATE_ID, 256, "aggregateId");
        if (containsSensitiveTechnicalValue(aggregateId)) {
            throw new IllegalArgumentException("aggregateId must be an opaque non-sensitive technical value");
        }
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        if (Integer.parseInt(eventTypeMatcher.group(4)) != eventVersion) {
            throw new IllegalArgumentException("eventVersion must match the eventType version suffix");
        }
        String canonicalAggregateType = aggregateType.toLowerCase(Locale.ROOT).replace('_', '-');
        if (!eventTypeMatcher.group(2).equals(canonicalAggregateType)) {
            throw new IllegalArgumentException("aggregateType must match the eventType aggregate segment");
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        requireObjectPayload(payload);
    }

    private static Matcher requireMatch(String value, Pattern pattern, int maxLength, String name) {
        if (value == null || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be a safe technical value");
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(name + " must be a safe technical value");
        }
        return matcher;
    }

    private static void requireObjectPayload(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            if (map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
                throw new IllegalArgumentException("payload map keys must be strings");
            }
            return;
        }
        Class<?> payloadType = payload.getClass();
        if (payloadType.isRecord()) {
            return;
        }
        if (payloadType.isArray()
                || payload instanceof Iterable<?>
                || payload instanceof CharSequence
                || payload instanceof Number
                || payload instanceof Boolean
                || payload instanceof Character
                || payload instanceof Enum<?>
                || payloadType.getPackageName().startsWith("java.")) {
            throw new IllegalArgumentException("payload must be an object value");
        }
    }

    public static boolean containsSensitiveTechnicalValue(String value) {
        return value != null
                && (EMAIL.matcher(value).find()
                || BEARER.matcher(value).find()
                || BASIC_AUTHORIZATION.matcher(value).find()
                || JWT.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find()
                || SIGNED_URL.matcher(value).find()
                || PHONE.matcher(value).find());
    }
}
