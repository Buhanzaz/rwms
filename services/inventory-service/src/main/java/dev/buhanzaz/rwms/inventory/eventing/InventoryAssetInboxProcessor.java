package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.persistence.InventoryPostgresJsonbCanonicalizer;
import dev.buhanzaz.rwms.inventory.service.InventoryApplicationService;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates and deduplicates asset rental-item facts before applying inventory membership changes.
 * Invalid or conflicting deliveries are retained in the local retry/DLT path instead of guessed.
 */
@Service
public class InventoryAssetInboxProcessor {
  static final String CONSUMER = InventoryAssetInboxStore.CONSUMER;
  static final String TOPIC = InventoryAssetInboxStore.TOPIC;
  private static final Set<String> ROOT_FIELDS =
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
  private static final Set<String> STATE_EVENT_TYPES =
      Set.of(
          "asset.rental-item.created.v1",
          "asset.rental-item.passport-changed.v1",
          "asset.rental-item.status-changed.v1",
          "asset.rental-item.warehouse-changed.v1",
          "asset.rental-item.logistics-effect-applied.v1");
  private static final Set<String> IGNORED_EVENT_TYPES =
      Set.of(
          "asset.rental-item.general-comment-changed.v1",
          "asset.rental-item.manual-note-added.v1");
  private static final Set<String> CORRELATION_FIELDS =
      Set.of("correlationId", "causationId");
  private static final Set<String> ACTOR_FIELDS =
      Set.of("subjectId", "principalType", "profileRevision");
  private static final Set<String> STATE_PAYLOAD_FIELDS =
      Set.of("rentalItemId", "warehouseId", "status", "numberSha256");
  private static final Set<String> COMMENT_PAYLOAD_FIELDS =
      Set.of("rentalItemId", "commentRevision");
  private static final Set<String> NOTE_PAYLOAD_FIELDS =
      Set.of("rentalItemId", "noteId");

  private final InventoryAssetInboxStore inbox;
  private final InventoryPostgresJsonbCanonicalizer jsonb;
  private final ObjectMapper mapper;
  private final InventoryDeadLetterStore deadLetters;
  private final InventoryApplicationService inventory;

  public InventoryAssetInboxProcessor(
      InventoryAssetInboxStore inbox,
      InventoryPostgresJsonbCanonicalizer jsonb,
      ObjectMapper mapper,
      InventoryDeadLetterStore deadLetters,
      InventoryApplicationService inventory) {
    this.inbox = inbox;
    this.jsonb = jsonb;
    this.mapper = mapper;
    this.deadLetters = deadLetters;
    this.inventory = inventory;
  }

  /**
   * Persists and deduplicates one broker delivery before membership application. The record key is
   * checked against the event aggregate so an at-least-once retry cannot be redirected to another asset.
   */
  @Transactional
  public void initial(byte[] bytes, byte[] recordKey) {
    String hash = InventoryEventChecksum.sha256(bytes);
    AssetEvent event;
    try {
      event = validate(bytes, recordKey);
    } catch (InvalidAssetEvent exception) {
      deadLetters.record("VALIDATION_REJECTED", hash, TOPIC, exception.eventId());
      return;
    }
    String body = canonical(new String(bytes, StandardCharsets.UTF_8));
    int inserted =
        inbox.insertReceived(
            event.eventId(),
            event.assetId(),
            event.aggregateVersion(),
            event.eventType(),
            hash,
            body);
    if (inserted == 0) {
      String existing = inbox.payloadSha256(event.eventId());
      if (!hash.equals(existing == null ? null : existing.trim())) {
        deadLetters.record("EVENT_ID_CONFLICT", hash, TOPIC, event.eventId());
      }
      return;
    }
    apply(event);
  }

  @Transactional
  public void retry(UUID eventId) {
    String body = inbox.lockRetryEnvelope(eventId);
    String recordKey = inbox.retryRecordKey(eventId);
    if (body == null || recordKey == null) return;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    try {
      apply(validate(bytes, recordKey.getBytes(StandardCharsets.UTF_8)));
    } catch (InvalidAssetEvent exception) {
      String hash = InventoryEventChecksum.sha256(bytes);
      inbox.markValidationRejected(eventId);
      deadLetters.record("VALIDATION_REJECTED", hash, TOPIC, eventId);
    }
  }

  private void apply(AssetEvent event) {
    if (STATE_EVENT_TYPES.contains(event.eventType())) {
      inventory.reconcileAssetMembership(
          event.assetId(),
          event.aggregateVersion(),
          event.warehouseId(),
          event.status(),
          event.actor(),
          event.correlationId(),
          event.eventId(),
          event.occurredAt());
    }
    inbox.markProcessed(event.eventId());
  }

  private AssetEvent validate(byte[] bytes, byte[] recordKey) {
    JsonNode root;
    UUID eventId = null;
    try {
      root = mapper.readTree(bytes);
      if (root != null && root.isObject() && root.hasNonNull("eventId")) {
        eventId = UUID.fromString(root.path("eventId").asText());
      }
      exact(root, ROOT_FIELDS);
      String eventType = root.path("eventType").asText();
      if (root.path("envelopeVersion").asInt(-1) != 2
          || root.path("eventVersion").asInt(-1) != 1
          || !"asset-service".equals(root.path("producer").asText())
          || !"RENTAL_ITEM".equals(root.path("aggregateType").asText())
          || (!STATE_EVENT_TYPES.contains(eventType)
              && !IGNORED_EVENT_TYPES.contains(eventType))) {
        throw invalid(eventId);
      }
      long aggregateVersion = root.path("aggregateVersion").asLong(-1);
      if (aggregateVersion < 0) throw invalid(eventId);
      UUID assetId = UUID.fromString(root.path("aggregateId").asText());
      String key =
          recordKey == null ? null : new String(recordKey, StandardCharsets.UTF_8);
      if (!assetId.toString().equals(key)) throw invalid(eventId);
      OffsetDateTime recordedAt = OffsetDateTime.parse(root.path("recordedAt").asText());
      OffsetDateTime occurredAt =
          root.path("occurredAt").isNull()
              ? recordedAt
              : OffsetDateTime.parse(root.path("occurredAt").asText());
      JsonNode correlation = root.path("correlation");
      exact(correlation, CORRELATION_FIELDS);
      UUID correlationId = UUID.fromString(correlation.path("correlationId").asText());
      if (!correlation.path("causationId").isNull()) {
        UUID.fromString(correlation.path("causationId").asText());
      }
      OpaqueActorReference actor = actor(root.path("actorRef"));
      JsonNode payload = root.path("payload");
      Set<String> payloadFields =
          STATE_EVENT_TYPES.contains(eventType)
              ? STATE_PAYLOAD_FIELDS
              : "asset.rental-item.general-comment-changed.v1".equals(eventType)
                  ? COMMENT_PAYLOAD_FIELDS
                  : NOTE_PAYLOAD_FIELDS;
      exact(payload, payloadFields);
      if (!assetId.equals(UUID.fromString(payload.path("rentalItemId").asText()))) {
        throw invalid(eventId);
      }
      if (STATE_EVENT_TYPES.contains(eventType)) {
        UUID.fromString(payload.path("warehouseId").asText());
        if (payload.path("status").asText().isBlank()
            || !payload.path("numberSha256").asText().matches("^[0-9a-f]{64}$")) {
          throw invalid(eventId);
        }
      } else if ("asset.rental-item.general-comment-changed.v1".equals(eventType)) {
        if (payload.path("commentRevision").asLong(-1) < 0) throw invalid(eventId);
      } else {
        UUID.fromString(payload.path("noteId").asText());
      }
      return new AssetEvent(
          eventId,
          eventType,
          assetId,
          aggregateVersion,
          correlationId,
          actor,
          occurredAt,
          STATE_EVENT_TYPES.contains(eventType) ? UUID.fromString(payload.path("warehouseId").asText()) : null,
          STATE_EVENT_TYPES.contains(eventType) ? payload.path("status").asText() : null);
    } catch (InvalidAssetEvent exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid(eventId);
    }
  }

  private OpaqueActorReference actor(JsonNode value) throws JacksonException {
    if (value.isNull()) return null;
    exact(value, ACTOR_FIELDS);
    return mapper.treeToValue(value, OpaqueActorReference.class);
  }

  private void exact(JsonNode value, Set<String> fields) {
    if (value == null || !value.isObject() || value.size() != fields.size()) {
      throw invalid(null);
    }
    for (String field : fields) {
      if (!value.has(field)) throw invalid(null);
    }
  }

  private String canonical(String raw) {
    String value = jsonb.canonicalize(raw);
    if (value == null) throw new IllegalStateException("PostgreSQL did not canonicalize asset fact");
    return value;
  }

  private InvalidAssetEvent invalid(UUID eventId) {
    return new InvalidAssetEvent(eventId);
  }

  private record AssetEvent(
      UUID eventId,
      String eventType,
      UUID assetId,
      long aggregateVersion,
      UUID correlationId,
      OpaqueActorReference actor,
      OffsetDateTime occurredAt,
      UUID warehouseId,
      String status) {}

  private static final class InvalidAssetEvent extends RuntimeException {
    private final UUID eventId;

    private InvalidAssetEvent(UUID eventId) {
      this.eventId = eventId;
    }

    private UUID eventId() {
      return eventId;
    }
  }
}
