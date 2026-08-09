package dev.buhanzaz.rwms.analytics.eventing;

import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent.KpiDayPayload;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Rejects malformed, unsupported, duplicate-field or incorrectly keyed KPI envelopes before any raw value reaches persistence. */
@Component
public final class AnalyticsEnvelopeValidator {
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
  private static final Set<String> CORRELATION_FIELDS = Set.of("correlationId", "causationId");
  private static final Set<String> ACTOR_FIELDS =
      Set.of("subjectId", "principalType", "profileRevision");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of(
          "evidenceId",
          "warehouseId",
          "workerGroupId",
          "localDate",
          "dataAvailableFrom",
          "formulaVersion",
          "completedBudgetSeconds",
          "earnedRemainingSeconds",
          "activeSeconds",
          "penalizedIdleSeconds",
          "completedTaskCount",
          "openState",
          "openStateStartedAt",
          "penaltyStartsAt",
          "nextTransitionAt",
          "asOf");

  private final ObjectMapper mapper;
  private final AnalyticsEventSchemaValidator schema;

  public AnalyticsEnvelopeValidator(ObjectMapper mapper) {
    this(mapper, new AnalyticsEventSchemaValidator());
  }

  @Autowired
  public AnalyticsEnvelopeValidator(
      ObjectMapper mapper, AnalyticsEventSchemaValidator schema) {
    this.mapper =
        mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    this.schema = schema;
  }

  /**
   * Parses one Kafka record into a canonical validated fact. It checks the configured source,
   * schema, exact object shape and aggregate Kafka key before any persistence is attempted.
   */
  public AnalyticsValidatedEvent validate(
      String topic, int partition, long offset, Object kafkaKey, byte[] raw) {
    if (!AnalyticsTopics.INPUT.equals(topic) || partition < 0 || offset < 0 || raw == null || raw.length == 0) {
      throw invalid("SOURCE_RECORD_INVALID");
    }
    try {
      schema.validate(raw);
      JsonNode root = mapper.readTree(raw);
      exactObject(root, ENVELOPE_FIELDS);
      require(integer(root, "envelopeVersion", 2) == 2);
      UUID eventId = uuid(root, "eventId", false);
      require("task-board.group-kpi-day.changed.v1".equals(text(root, "eventType")));
      require(integer(root, "eventVersion", 1) == 1);
      OffsetDateTime occurredAt = dateTime(root, "occurredAt", true);
      OffsetDateTime recordedAt = dateTime(root, "recordedAt", false);
      require("task-board-service".equals(text(root, "producer")));
      require("GROUP_KPI_DAY".equals(text(root, "aggregateType")));
      UUID aggregateId = uuid(root, "aggregateId", false);
      if (!aggregateId.toString().equals(normalizeKey(kafkaKey))) {
        throw invalid("SOURCE_RECORD_KEY_MISMATCH");
      }
      long aggregateVersion = nonNegativeLong(root, "aggregateVersion");

      JsonNode correlation = root.required("correlation");
      exactObject(correlation, CORRELATION_FIELDS);
      UUID correlationId = uuid(correlation, "correlationId", false);
      UUID causationId = uuid(correlation, "causationId", true);
      validateActor(root.required("actorRef"));

      KpiDayPayload payload = payload(root.required("payload"), aggregateId);
      String canonical = canonical(root);
      return new AnalyticsValidatedEvent(
          topic,
          partition,
          offset,
          eventId,
          aggregateId,
          aggregateVersion,
          occurredAt,
          recordedAt,
          correlationId,
          causationId,
          AnalyticsEventHash.sha256(canonical),
          canonical,
          payload);
    } catch (AnalyticsValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid("SOURCE_SCHEMA_REJECTED", exception);
    }
  }

  private KpiDayPayload payload(JsonNode value, UUID aggregateId) {
    try {
      exactObject(value, PAYLOAD_FIELDS);
      UUID evidenceId = uuid(value, "evidenceId", false);
      requirePayload(evidenceId.equals(aggregateId));
      UUID warehouseId = uuid(value, "warehouseId", false);
      UUID workerGroupId = uuid(value, "workerGroupId", false);
      LocalDate localDate = date(value, "localDate");
      LocalDate dataAvailableFrom = date(value, "dataAvailableFrom");
      requirePayload(!dataAvailableFrom.isAfter(localDate));
      requirePayload("kpi-v1".equals(text(value, "formulaVersion")));
      long completedBudgetSeconds = nonNegativeLong(value, "completedBudgetSeconds");
      long earnedRemainingSeconds = nonNegativeLong(value, "earnedRemainingSeconds");
      requirePayload(earnedRemainingSeconds <= completedBudgetSeconds);
      long activeSeconds = nonNegativeLong(value, "activeSeconds");
      long penalizedIdleSeconds = nonNegativeLong(value, "penalizedIdleSeconds");
      long completedTaskCount = nonNegativeLong(value, "completedTaskCount");
      KpiOpenState openState = nullableEnum(value, "openState", KpiOpenState.class);
      OffsetDateTime openStateStartedAt = dateTime(value, "openStateStartedAt", true);
      OffsetDateTime penaltyStartsAt = dateTime(value, "penaltyStartsAt", true);
      OffsetDateTime nextTransitionAt = dateTime(value, "nextTransitionAt", true);
      OffsetDateTime asOf = dateTime(value, "asOf", false);
      requirePayload((openState == null) == (openStateStartedAt == null));
      if (openStateStartedAt != null) requirePayload(!openStateStartedAt.isAfter(asOf));
      if (openState == KpiOpenState.IDLE_GRACE) {
        requirePayload(penaltyStartsAt != null && !penaltyStartsAt.isBefore(openStateStartedAt));
      } else if (openState == KpiOpenState.IDLE_PENALIZED) {
        requirePayload(penaltyStartsAt != null && !penaltyStartsAt.isAfter(asOf));
      } else {
        requirePayload(penaltyStartsAt == null);
      }
      if (nextTransitionAt != null) requirePayload(nextTransitionAt.isAfter(asOf));
      return new KpiDayPayload(
          evidenceId,
          warehouseId,
          workerGroupId,
          localDate,
          dataAvailableFrom,
          "kpi-v1",
          completedBudgetSeconds,
          earnedRemainingSeconds,
          activeSeconds,
          penalizedIdleSeconds,
          completedTaskCount,
          openState,
          openStateStartedAt,
          penaltyStartsAt,
          nextTransitionAt,
          asOf);
    } catch (AnalyticsValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid("SOURCE_PAYLOAD_REJECTED", exception);
    }
  }

  private static void validateActor(JsonNode actor) {
    if (actor.isNull()) return;
    exactObject(actor, ACTOR_FIELDS);
    uuid(actor, "subjectId", false);
    String principalType = text(actor, "principalType");
    require(principalType.matches("[A-Z][A-Z0-9_]{0,63}"));
    JsonNode profileRevision = actor.required("profileRevision");
    require(
        profileRevision.isNull()
            || (profileRevision.isTextual()
                && profileRevision
                    .stringValue()
                    .matches(
                        "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})")));
  }

  private String canonical(JsonNode value) {
    return mapper
        .writer()
        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .writeValueAsString(mapper.convertValue(value, Object.class));
  }

  private static void exactObject(JsonNode value, Set<String> expected) {
    require(value != null && value.isObject());
    Set<String> actual = new HashSet<>();
    actual.addAll(value.propertyNames());
    require(actual.equals(expected));
  }

  private static UUID uuid(JsonNode node, String field, boolean nullable) {
    JsonNode value = node.required(field);
    if (nullable && value.isNull()) return null;
    require(value.isTextual());
    return UUID.fromString(value.stringValue());
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.required(field);
    require(value.isTextual() && !value.stringValue().isBlank() && value.stringValue().length() <= 256);
    return value.stringValue();
  }

  private static int integer(JsonNode node, String field, int minimum) {
    JsonNode value = node.required(field);
    require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= minimum);
    return value.intValue();
  }

  private static long nonNegativeLong(JsonNode node, String field) {
    JsonNode value = node.required(field);
    require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0);
    return value.longValue();
  }

  private static LocalDate date(JsonNode node, String field) {
    return LocalDate.parse(text(node, field));
  }

  private static OffsetDateTime dateTime(JsonNode node, String field, boolean nullable) {
    JsonNode value = node.required(field);
    if (nullable && value.isNull()) return null;
    require(value.isTextual());
    return OffsetDateTime.parse(value.stringValue());
  }

  private static <E extends Enum<E>> E nullableEnum(
      JsonNode node, String field, Class<E> type) {
    JsonNode value = node.required(field);
    if (value.isNull()) return null;
    require(value.isTextual());
    return Enum.valueOf(type, value.stringValue());
  }

  private static String normalizeKey(Object key) {
    if (key instanceof byte[] bytes) {
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
    if (key instanceof String text) return text;
    if (key instanceof UUID uuid) return uuid.toString();
    throw invalid("SOURCE_RECORD_KEY_MISMATCH");
  }

  private static void require(boolean condition) {
    if (!condition) throw invalid("SOURCE_SCHEMA_REJECTED");
  }

  private static void requirePayload(boolean condition) {
    if (!condition) throw invalid("SOURCE_PAYLOAD_REJECTED");
  }

  private static AnalyticsValidationException invalid(String code) {
    return new AnalyticsValidationException(code);
  }

  private static AnalyticsValidationException invalid(String code, Throwable cause) {
    return new AnalyticsValidationException(code, cause);
  }
}
