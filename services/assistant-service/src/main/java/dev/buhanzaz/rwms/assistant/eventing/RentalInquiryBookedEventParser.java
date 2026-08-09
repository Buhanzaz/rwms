package dev.buhanzaz.rwms.assistant.eventing;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Validates the exact canonical V2 booking envelope, its source coordinates and conversation key
 * before any supplied value reaches assistant persistence.
 */
@Component
public class RentalInquiryBookedEventParser {
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "envelopeVersion",
          "eventId",
          "eventType",
          "eventVersion",
          "occurredAt",
          "recordedAt",
          "producer",
          "aggregateType",
          "aggregateId",
          "aggregateVersion",
          "correlation",
          "actorRef",
          "payload");
  private static final Set<String> CORRELATION_FIELDS =
      Set.of("correlationId", "causationId");
  private static final Set<String> ACTOR_FIELDS =
      Set.of("subjectId", "principalType", "profileRevision");
  private static final Set<String> PAYLOAD_FIELDS = Set.of("conversationId", "orderId");
  private static final Pattern ACTOR_REVISION =
      Pattern.compile(
          "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})");
  private static final Pattern SAFE_TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");

  private final ObjectMapper mapper;

  public RentalInquiryBookedEventParser(ObjectMapper mapper) {
    this.mapper =
        mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  }

  /**
   * Parses one durable Kafka receipt and returns only its validated canonical envelope plus safe
   * derived identities.
   */
  public ParsedEvent parse(
      String topic,
      int partition,
      long offset,
      String recordKey,
      String rawEnvelope) {
    if (!hasDurableSource(topic, partition, offset)
        || !RentalInquiryBookedEvent.SOURCE_TOPIC.equals(topic)
        || rawEnvelope == null
        || rawEnvelope.isBlank()) {
      throw invalid("SOURCE_RECORD_INVALID");
    }
    try {
      JsonNode root = mapper.readTree(rawEnvelope);
      exactObject(root, ENVELOPE_FIELDS);
      require(integer(root, "envelopeVersion") == 2);
      UUID eventId = uuid(root, "eventId", false);
      require(RentalInquiryBookedEvent.TYPE.equals(text(root, "eventType", 256)));
      require(integer(root, "eventVersion") == 1);
      OffsetDateTime occurredAt = timestamp(root, "occurredAt");
      OffsetDateTime recordedAt = timestamp(root, "recordedAt");
      require(RentalInquiryBookedEvent.PRODUCER.equals(text(root, "producer", 128)));
      require(
          RentalInquiryBookedEvent.AGGREGATE_TYPE.equals(text(root, "aggregateType", 128)));
      UUID aggregateId = uuid(root, "aggregateId", false);
      long aggregateVersion = nonNegativeLong(root, "aggregateVersion");

      JsonNode payload = root.required("payload");
      exactObject(payload, PAYLOAD_FIELDS);
      UUID conversationId = uuid(payload, "conversationId", false);
      UUID orderId = uuid(payload, "orderId", false);

      JsonNode correlation = root.required("correlation");
      exactObject(correlation, CORRELATION_FIELDS);
      UUID correlationId = uuid(correlation, "correlationId", false);
      UUID causationId = uuid(correlation, "causationId", true);
      require(correlationId.equals(conversationId));
      if (!conversationId.toString().equals(recordKey)) {
        throw invalid("SOURCE_RECORD_KEY_MISMATCH");
      }

      OpaqueActorReference actor = actor(root.required("actorRef"));
      rejectSensitiveText(root);
      new DomainEventEnvelopeV2<>(
          2,
          eventId,
          RentalInquiryBookedEvent.TYPE,
          1,
          occurredAt.toInstant(),
          recordedAt.toInstant(),
          RentalInquiryBookedEvent.PRODUCER,
          RentalInquiryBookedEvent.AGGREGATE_TYPE,
          aggregateId.toString(),
          aggregateVersion,
          new CorrelationContext(correlationId, causationId),
          actor,
          Map.of(
              "conversationId", conversationId.toString(),
              "orderId", orderId.toString()));

      String canonicalEnvelope = canonical(root);
      RentalInquiryBookedEvent event =
          new RentalInquiryBookedEvent(
              eventId,
              RentalInquiryBookedEvent.TYPE,
              occurredAt,
              recordedAt,
              aggregateVersion,
              aggregateId,
              conversationId,
              orderId,
              causationId);
      return new ParsedEvent(
          event,
          canonicalEnvelope,
          AssistantCanonicalEventHasher.sha256(canonicalEnvelope),
          topic,
          partition,
          offset);
    } catch (AssistantEventValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid("SOURCE_SCHEMA_REJECTED", exception);
    }
  }

  /** Returns whether sanitized evidence can be durably and uniquely keyed by this source tuple. */
  public static boolean hasDurableSource(String topic, int partition, long offset) {
    return topic != null
        && SAFE_TOPIC.matcher(topic).matches()
        && partition >= 0
        && offset >= 0;
  }

  private String canonical(JsonNode value) {
    return mapper
        .writer()
        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .writeValueAsString(mapper.convertValue(value, Object.class));
  }

  private static OpaqueActorReference actor(JsonNode value) {
    if (value.isNull()) return null;
    exactObject(value, ACTOR_FIELDS);
    UUID subjectId = uuid(value, "subjectId", false);
    String principalType = text(value, "principalType", 64);
    JsonNode revision = value.required("profileRevision");
    require(
        revision.isNull()
            || (revision.isString()
                && ACTOR_REVISION.matcher(revision.stringValue()).matches()));
    return new OpaqueActorReference(
        subjectId.toString(), principalType, revision.isNull() ? null : revision.stringValue());
  }

  private static void rejectSensitiveText(JsonNode value) {
    if (value.isString()
        && DomainEventEnvelopeV2.containsSensitiveTechnicalValue(value.stringValue())) {
      throw invalid("SOURCE_SCHEMA_REJECTED");
    }
    value.forEach(RentalInquiryBookedEventParser::rejectSensitiveText);
  }

  private static void exactObject(JsonNode value, Set<String> expected) {
    require(value != null && value.isObject());
    Set<String> actual = new HashSet<>(value.propertyNames());
    require(actual.equals(expected));
  }

  private static UUID uuid(JsonNode parent, String field, boolean nullable) {
    JsonNode value = parent.required(field);
    if (nullable && value.isNull()) return null;
    require(value.isString());
    UUID parsed = UUID.fromString(value.stringValue());
    require(parsed.toString().equals(value.stringValue()));
    return parsed;
  }

  private static OffsetDateTime timestamp(JsonNode parent, String field) {
    JsonNode value = parent.required(field);
    require(value.isString() && value.stringValue().length() <= 64);
    return OffsetDateTime.parse(value.stringValue());
  }

  private static String text(JsonNode parent, String field, int maximumLength) {
    JsonNode value = parent.required(field);
    require(
        value.isString()
            && !value.stringValue().isBlank()
            && value.stringValue().length() <= maximumLength);
    return value.stringValue();
  }

  private static int integer(JsonNode parent, String field) {
    JsonNode value = parent.required(field);
    require(value.isIntegralNumber() && value.canConvertToInt());
    return value.intValue();
  }

  private static long nonNegativeLong(JsonNode parent, String field) {
    JsonNode value = parent.required(field);
    require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0);
    return value.longValue();
  }

  private static void require(boolean condition) {
    if (!condition) throw invalid("SOURCE_SCHEMA_REJECTED");
  }

  private static AssistantEventValidationException invalid(String code) {
    return new AssistantEventValidationException(code);
  }

  private static AssistantEventValidationException invalid(String code, Throwable cause) {
    return new AssistantEventValidationException(code, cause);
  }

  /** Validated canonical envelope together with its immutable Kafka source receipt. */
  public record ParsedEvent(
      RentalInquiryBookedEvent event,
      String canonicalEnvelope,
      String canonicalSha256,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset) {}
}
