package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.domain.InventoryMediaFactProjection;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates and deduplicates media facts for inventory findings before updating the local media
 * projection. Facts owned by another context are acknowledged without being interpreted as findings.
 */
@Service
public class InventoryMediaInboxProcessor {
  private static final String CONSUMER = "inventory-service-media-inbox-v1";
  private static final String TOPIC = "rwms.media.media.v1";
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
  /**
   * The media facts contract deliberately keeps folderId and clientReferenceId optional so
   * older owners can publish a fact without a folder and task-board evidence can carry its
   * own reference. Do not use {@link #exactObject(JsonNode, Set, String)} for this payload:
   * treating optional fields as required was what made real inventory image READY facts land
   * in the DLT instead of the readiness projection.
   */
  private static final Set<String> REQUIRED_PAYLOAD_FIELDS =
      Set.of(
          "mediaId",
          "ownerType",
          "ownerId",
          "warehouseId",
          "kind",
          "status",
          "generation",
          "rotationDegrees");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of(
          "mediaId",
          "folderId",
          "ownerType",
          "ownerId",
          "warehouseId",
          "clientReferenceId",
          "kind",
          "status",
          "generation",
          "rotationDegrees");
  private static final Set<String> CORRELATION_FIELDS = Set.of("correlationId", "causationId");
  private static final Set<String> ACTOR_FIELDS =
      Set.of("subjectId", "principalType", "profileRevision");
  private static final Set<String> EVENT_TYPES =
      Set.of(
          "media.media.uploaded.v1",
          "media.media.ready.v1",
          "media.media.failed.v1",
          "media.media.rotated.v1",
          "media.media.deleted.v1");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final InventoryDeadLetterStore deadLetters;
  private final InventoryFindingRepository findings;
  private final InventoryMediaFactProjectionRepository mediaFacts;

  public InventoryMediaInboxProcessor(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      InventoryDeadLetterStore deadLetters,
      InventoryFindingRepository findings,
      InventoryMediaFactProjectionRepository mediaFacts) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.deadLetters = deadLetters;
    this.findings = findings;
    this.mediaFacts = mediaFacts;
  }

  @Transactional
  public void initial(byte[] bytes) {
    initial(bytes, null);
  }

  /**
   * Persists and deduplicates one broker delivery before projecting an inventory finding's media
   * fact. The received record key is part of validation so a retry cannot change media identity.
   */
  @Transactional
  public void initial(byte[] bytes, byte[] recordKey) {
    String rawHash = InventoryEventChecksum.sha256(bytes);
    String body = canonical(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    JsonNode root = read(body);
    UUID eventId = uuid(root.path("eventId").asText(), "eventId");
    long version = root.path("aggregateVersion").asLong(-1);
    String aggregateId = root.path("aggregateId").asText();
    String key =
        recordKey == null
            ? aggregateId
            : new String(recordKey, java.nio.charset.StandardCharsets.UTF_8);
    String eventType = root.path("eventType").asText();
    int inserted =
        jdbc.update(
            """
            insert into inbox_message(
              consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,aggregate_version,
              event_type,payload_sha256,envelope_body,status,attempt_count,received_at)
            values (?,?,'rwms.media.media.v1','MEDIA',?,?,?,?,?,?::jsonb,'RECEIVED',0,clock_timestamp())
            on conflict do nothing
            """,
            CONSUMER,
            eventId,
            aggregateId,
            key,
            version,
            eventType,
            rawHash,
            body);
    if (inserted == 0) {
      String existing =
          jdbc.queryForObject(
              "select payload_sha256 from inbox_message where consumer_group=? and event_id=?",
              String.class,
              CONSUMER,
              eventId);
      if (!rawHash.equals(existing == null ? null : existing.trim())) {
        deadLetters.record("EVENT_ID_CONFLICT", rawHash, TOPIC, eventId);
      }
      return;
    }
    process(eventId, root, rawHash, key);
  }

  @Transactional
  public void retry(UUID eventId) {
    String body =
        jdbc.queryForObject(
            """
            select envelope_body::text from inbox_message
             where consumer_group=? and event_id=? and status='RETRY' for update
            """,
            String.class,
            CONSUMER,
            eventId);
    if (body == null) return;
    String hash =
        jdbc.queryForObject(
            "select payload_sha256 from inbox_message where consumer_group=? and event_id=?",
            String.class,
            CONSUMER,
            eventId);
    if (hash == null) return;
    String recordKey =
        jdbc.queryForObject(
            "select record_key from inbox_message where consumer_group=? and event_id=?",
            String.class,
            CONSUMER,
            eventId);
    if (recordKey == null) return;
    process(eventId, read(body), hash.trim(), recordKey);
  }

  private void process(UUID eventId, JsonNode root, String hash, String recordKey) {
    // The topic is shared by all bounded contexts. Route on the owner first so inventory never
    // rejects a valid foreign-owner fact merely because that owner's identifiers or actor shape
    // are intentionally different from an inventory finding.
    if (!isInventoryFindingFact(root)) {
      markFactProcessed(eventId);
      return;
    }
    try {
      MediaFact fact = validate(root, recordKey);
      ensureCheckpoint(fact.mediaId());
      Checkpoint checkpoint = lockCheckpoint(fact.mediaId());
      if (checkpoint.blocked()) {
        quarantineInbox(eventId, "AGGREGATE_ALREADY_QUARANTINED");
        return;
      }
      // MEDIA facts are public snapshots. Private upload/rotation work advances the source
      // aggregate between facts, so public versions are monotonic but not necessarily contiguous.
      if (fact.aggregateVersion() <= checkpoint.version()) {
        markFactProcessed(eventId);
        return;
      }
      if (findings.findOwnedInventoryId(fact.ownerId(), fact.warehouseId()).isEmpty()) {
        validationFailure(eventId, hash, "UNKNOWN_INVENTORY_OWNER");
        return;
      }
      InventoryMediaFactProjection projection =
          mediaFacts
              .findByMediaIdAndGeneration(fact.mediaId(), fact.generation())
              .map(
                  existing -> {
                    existing.apply(
                        fact.aggregateVersion(),
                        fact.ownerId(),
                        fact.warehouseId(),
                        fact.kind(),
                        fact.status(),
                        fact.rotationDegrees());
                    return existing;
                  })
              .orElseGet(
                  () ->
                      InventoryMediaFactProjection.create(
                          fact.mediaId(),
                          fact.generation(),
                          fact.aggregateVersion(),
                          fact.ownerId(),
                          fact.warehouseId(),
                          fact.kind(),
                          fact.status(),
                          fact.rotationDegrees()));
      mediaFacts.saveAndFlush(projection);
      jdbc.update(
          """
          update consumer_aggregate_checkpoint set last_event_id=?,last_aggregate_version=?,
            updated_at=clock_timestamp() where consumer_group=? and aggregate_type='MEDIA'
            and aggregate_id=?
          """,
          eventId,
          fact.aggregateVersion(),
          CONSUMER,
          fact.mediaId().toString());
      jdbc.update(
          """
          update inbox_message set status='PROCESSED',processed_at=clock_timestamp(),
            next_attempt_at=null,dlt_at=null,quarantine_reason=null
           where consumer_group=? and event_id=?
          """,
          CONSUMER,
          eventId);
    } catch (InvalidMediaFact exception) {
      validationFailure(eventId, hash, exception.code());
    }
  }

  private MediaFact validate(JsonNode root, String recordKey) {
    exactObject(root, ROOT_FIELDS, "INVALID_ENVELOPE_FIELDS");
    if (root.path("envelopeVersion").asInt(-1) != 2
        || root.path("eventVersion").asInt(-1) != 1
        || !"media-service".equals(root.path("producer").asText())
        || !"MEDIA".equals(root.path("aggregateType").asText())
        || !EVENT_TYPES.contains(root.path("eventType").asText())) {
      throw invalid("INVALID_ENVELOPE_METADATA");
    }
    uuid(root.path("eventId").asText(), "eventId");
    dateTime(root.path("recordedAt"), false, "recordedAt");
    dateTime(root.path("occurredAt"), true, "occurredAt");
    JsonNode correlation = root.path("correlation");
    exactObject(correlation, CORRELATION_FIELDS, "INVALID_CORRELATION_FIELDS");
    uuid(correlation.path("correlationId").asText(), "correlation.correlationId");
    JsonNode causation = correlation.path("causationId");
    if (!causation.isNull()) uuid(causation.asText(), "correlation.causationId");
    validateActor(root.path("actorRef"));
    UUID mediaId = uuid(root.path("aggregateId").asText(), "aggregateId");
    if (!mediaId.toString().equals(recordKey)) throw invalid("INVALID_RECORD_KEY");
    long version = root.path("aggregateVersion").asLong(-1);
    if (version < 1) throw invalid("INVALID_AGGREGATE_VERSION");
    JsonNode payload = root.path("payload");
    validatePayloadFields(payload);
    UUID payloadMedia = uuid(payload.path("mediaId").asText(), "payload.mediaId");
    if (payload.has("folderId")) {
      uuid(payload.path("folderId").asText(), "payload.folderId");
    }
    // INVENTORY_FINDING is an ordinary owner, not task-board worker evidence. The canonical
    // schema permits the optional field only as null in this branch.
    if (payload.has("clientReferenceId") && !payload.path("clientReferenceId").isNull()) {
      throw invalid("INVALID_MEDIA_PAYLOAD");
    }
    UUID ownerId = uuid(payload.path("ownerId").asText(), "payload.ownerId");
    UUID warehouseId = uuid(payload.path("warehouseId").asText(), "payload.warehouseId");
    String kind = payload.path("kind").asText();
    String status = payload.path("status").asText();
    long generation = payload.path("generation").asLong(-1);
    int rotation = payload.path("rotationDegrees").asInt(-1);
    String ownerType = payload.path("ownerType").asText();
    if (!mediaId.equals(payloadMedia)
        || !"INVENTORY_FINDING".equals(ownerType)
        || !Set.of("IMAGE", "VIDEO").contains(kind)
        || !Set.of("PROCESSING", "READY", "FAILED", "DELETED").contains(status)
        || !eventMatchesStatus(root.path("eventType").asText(), status)
        || generation < 0
        || !Set.of(0, 90, 180, 270).contains(rotation)) {
      throw invalid("INVALID_MEDIA_PAYLOAD");
    }
    return new MediaFact(
        mediaId,
        version,
        ownerId,
        warehouseId,
        kind,
        status,
        generation,
        rotation);
  }

  /** The shared media topic contains facts for every owning service, not only inventory findings. */
  private void markFactProcessed(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='PROCESSED',processed_at=clock_timestamp(),
          next_attempt_at=null,dlt_at=null,quarantine_reason=null
         where consumer_group=? and event_id=?
        """,
        CONSUMER,
        eventId);
  }

  private static boolean isInventoryFindingFact(JsonNode root) {
    JsonNode payload = root.path("payload");
    return payload.isObject() && "INVENTORY_FINDING".equals(payload.path("ownerType").asText());
  }

  private void ensureCheckpoint(UUID mediaId) {
    jdbc.update(
        """
        insert into consumer_aggregate_checkpoint(
          consumer_group,aggregate_type,aggregate_id,last_event_id,last_aggregate_version,
          blocked,quarantine_reason,updated_at)
        values (?,'MEDIA',?,null,-1,false,null,clock_timestamp()) on conflict do nothing
        """,
        CONSUMER,
        mediaId.toString());
  }

  private Checkpoint lockCheckpoint(UUID mediaId) {
    return jdbc.queryForObject(
        """
        select last_aggregate_version,blocked from consumer_aggregate_checkpoint
         where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=? for update
        """,
        (resultSet, rowNumber) ->
            new Checkpoint(
                resultSet.getLong("last_aggregate_version"), resultSet.getBoolean("blocked")),
        CONSUMER,
        mediaId.toString());
  }

  @Transactional
  public void reconcileQuarantinedAggregate(
      UUID mediaId,
      long reconciledVersion,
      UUID reconciledEventId,
      String reason,
      UUID subjectId) {
    if (reconciledVersion < 0
        || reason == null
        || reason.isBlank()
        || reason.length() > 500) {
      throw new IllegalArgumentException("Invalid media aggregate reconciliation evidence");
    }
    Checkpoint checkpoint = lockCheckpoint(mediaId);
    if (!checkpoint.blocked()) {
      throw new IllegalStateException("Media aggregate is not quarantined");
    }
    Long receivedVersion =
        jdbc.queryForObject(
            """
            select received_version from version_gap_quarantine
             where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=?
               and status='OPEN' for update
            """,
            Long.class,
            CONSUMER,
            mediaId.toString());
    if (receivedVersion == null || reconciledVersion != receivedVersion - 1) {
      throw new IllegalArgumentException(
          "Reconciled version must immediately precede the quarantined fact");
    }
    jdbc.update(
        """
        update version_gap_quarantine set status='RESOLVED',resolved_at=clock_timestamp(),
          resolution_reason=?,resolved_by_subject_id=?
         where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=? and status='OPEN'
        """,
        reason.trim(),
        subjectId,
        CONSUMER,
        mediaId.toString());
    jdbc.update(
        """
        update consumer_aggregate_checkpoint set last_event_id=?,last_aggregate_version=?,
          blocked=false,quarantine_reason=null,updated_at=clock_timestamp()
         where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=?
        """,
        reconciledEventId,
        reconciledVersion,
        CONSUMER,
        mediaId.toString());
    jdbc.update(
        """
        update inbox_message set status='RETRY',next_attempt_at=clock_timestamp(),
          quarantine_reason=null
         where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=?
           and status='QUARANTINED' and aggregate_version>?
        """,
        CONSUMER,
        mediaId.toString(),
        reconciledVersion);
  }

  private void quarantineInbox(UUID eventId, String reason) {
    jdbc.update(
        """
        update inbox_message set status='QUARANTINED',quarantine_reason=?,
          next_attempt_at=null where consumer_group=? and event_id=?
        """,
        reason,
        CONSUMER,
        eventId);
  }

  private void validationFailure(UUID eventId, String hash, String reason) {
    jdbc.update(
        """
        update inbox_message set status='DLT',dlt_at=clock_timestamp(),
          next_attempt_at=null,quarantine_reason=? where consumer_group=? and event_id=?
        """,
        reason,
        CONSUMER,
        eventId);
    deadLetters.record("VALIDATION_REJECTED", hash, TOPIC, eventId);
  }

  private void exactObject(JsonNode value, Set<String> fields, String code) {
    if (!value.isObject()) throw invalid(code);
    Set<String> actual = new java.util.HashSet<>();
    actual.addAll(value.propertyNames());
    if (!actual.equals(fields)) throw invalid(code);
  }

  private void validatePayloadFields(JsonNode payload) {
    if (!payload.isObject()) throw invalid("INVALID_MEDIA_PAYLOAD_FIELDS");
    Set<String> actual = new java.util.HashSet<>();
    actual.addAll(payload.propertyNames());
    if (!actual.containsAll(REQUIRED_PAYLOAD_FIELDS) || !PAYLOAD_FIELDS.containsAll(actual)) {
      throw invalid("INVALID_MEDIA_PAYLOAD_FIELDS");
    }
  }

  private void validateActor(JsonNode actor) {
    if (actor.isNull()) return;
    exactObject(actor, ACTOR_FIELDS, "INVALID_ACTOR_FIELDS");
    uuid(actor.path("subjectId").asText(), "actorRef.subjectId");
    if (!Set.of("USER", "WORKER").contains(actor.path("principalType").asText())) {
      throw invalid("INVALID_ACTOR_TYPE");
    }
    JsonNode revision = actor.path("profileRevision");
    if (!revision.isNull()
        && !revision.asText().matches(
            "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})")) {
      throw invalid("INVALID_ACTOR_REVISION");
    }
  }

  private static boolean eventMatchesStatus(String eventType, String status) {
    return switch (eventType) {
      case "media.media.uploaded.v1" -> "PROCESSING".equals(status);
      case "media.media.ready.v1", "media.media.rotated.v1" -> "READY".equals(status);
      case "media.media.failed.v1" -> "FAILED".equals(status);
      case "media.media.deleted.v1" -> "DELETED".equals(status);
      default -> false;
    };
  }

  private void dateTime(JsonNode value, boolean nullable, String field) {
    if (nullable && value.isNull()) return;
    if (!value.isTextual()) throw invalid("INVALID_" + field.toUpperCase());
    try {
      OffsetDateTime.parse(value.asText());
    } catch (java.time.format.DateTimeParseException exception) {
      throw invalid("INVALID_" + field.toUpperCase());
    }
  }

  private UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw invalid("INVALID_" + field.replace('.', '_').toUpperCase());
    }
  }

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new InvalidMediaFact("INVALID_JSON");
    }
  }

  private String canonical(String value) {
    try {
      return mapper.writeValueAsString(mapper.readTree(value));
    } catch (JacksonException exception) {
      throw new InvalidMediaFact("INVALID_JSON");
    }
  }

  private static InvalidMediaFact invalid(String code) {
    return new InvalidMediaFact(code);
  }

  private record Checkpoint(long version, boolean blocked) {}

  private record MediaFact(
      UUID mediaId,
      long aggregateVersion,
      UUID ownerId,
      UUID warehouseId,
      String kind,
      String status,
      long generation,
      int rotationDegrees) {}

  private static final class InvalidMediaFact extends RuntimeException {
    private final String code;

    InvalidMediaFact(String code) {
      super(code);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
