package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.service.InventoryException;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends inventory-owned facts and their transactional event/outbox records in the local database.
 */
@Repository
public class InventoryEventStore {
  private static final Set<String> FORBIDDEN_KEYS =
      Set.of(
          "login",
          "authorization",
          "actor",
          "displayName",
          "email",
          "tenant",
          "passport",
          "equipment",
          "equipmentObservation",
          "displayCanonicalNumber",
          "identityMatchKey",
          "mediaUrl",
          "objectKey",
          "objectPath",
          "signedUrl",
          "rawError",
          "requestBody",
          "password",
          "token",
          "jwt",
          "secret",
          "reason",
          "comment");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public InventoryEventStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional
  public AppendResult initialize(
      String aggregateType,
      UUID aggregateId,
      String eventType,
      String topic,
      JsonNode payload,
      UUID correlationId,
      UUID causationId,
      OpaqueActorReference actor) {
    requireFamily(aggregateType, eventType, topic);
    rejectForbidden(payload);
    UUID eventId = UUID.randomUUID();
    int initialized =
        jdbc.update(
            """
            insert into event_stream_head(
              aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
            values (?,?,0,?,clock_timestamp()) on conflict do nothing
            """,
            aggregateType,
            aggregateId.toString(),
            eventId);
    if (initialized != 1) {
      throw InventoryException.conflict("Inventory event stream already exists");
    }
    return writeEventAndOutbox(
        aggregateType,
        aggregateId,
        0,
        eventId,
        eventType,
        topic,
        payload,
        correlationId,
        causationId,
        actor);
  }

  @Transactional
  public AppendResult append(
      String aggregateType,
      UUID aggregateId,
      long expectedVersion,
      String eventType,
      String topic,
      JsonNode payload,
      UUID correlationId,
      UUID causationId,
      OpaqueActorReference actor) {
    return appendInternal(
        new AppendCommand(
            aggregateType,
            aggregateId,
            expectedVersion,
            eventType,
            topic,
            payload,
            correlationId,
            causationId,
            actor));
  }

  @Transactional
  public List<AppendResult> appendAll(List<AppendCommand> commands) {
    List<AppendCommand> ordered =
        commands.stream()
            .sorted(
                Comparator.comparing(AppendCommand::aggregateType)
                    .thenComparing(value -> value.aggregateId().toString()))
            .toList();
    List<AppendResult> results = new ArrayList<>(ordered.size());
    for (AppendCommand command : ordered) {
      results.add(appendInternal(command));
    }
    return List.copyOf(results);
  }

  private AppendResult appendInternal(AppendCommand command) {
    requireFamily(command.aggregateType(), command.eventType(), command.topic());
    rejectForbidden(command.payload());
    if (command.expectedVersion() < 0) {
      throw new IllegalArgumentException("Expected event stream version cannot be negative");
    }
    long nextVersion = Math.addExact(command.expectedVersion(), 1);
    UUID eventId = UUID.randomUUID();
    int advanced =
        jdbc.update(
            """
            update event_stream_head
               set current_version=?,last_event_id=?,updated_at=clock_timestamp()
             where aggregate_type=? and aggregate_id=? and current_version=?
            """,
            nextVersion,
            eventId,
            command.aggregateType(),
            command.aggregateId().toString(),
            command.expectedVersion());
    if (advanced != 1) {
      throw InventoryException.conflict("Inventory event stream changed concurrently");
    }
    return writeEventAndOutbox(
        command.aggregateType(),
        command.aggregateId(),
        nextVersion,
        eventId,
        command.eventType(),
        command.topic(),
        command.payload(),
        command.correlationId(),
        command.causationId(),
        command.actor());
  }

  private AppendResult writeEventAndOutbox(
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      UUID eventId,
      String eventType,
      String topic,
      JsonNode payload,
      UUID correlationId,
      UUID causationId,
      OpaqueActorReference actor) {
    Instant recordedAt = Instant.now();
    Map<String, Object> envelopePayload =
        mapper.convertValue(payload, new TypeReference<Map<String, Object>>() {});
    DomainEventEnvelopeV2<Map<String, Object>> envelope =
        new DomainEventEnvelopeV2<>(
            2,
            eventId,
            eventType,
            1,
            null,
            recordedAt,
            "inventory-service",
            aggregateType,
            aggregateId.toString(),
            aggregateVersion,
            new CorrelationContext(correlationId, causationId),
            actor,
            envelopePayload);
    String envelopeBody = canonicalJson(json(envelope));
    String eventBody = canonicalJson(json(payload));
    String envelopeHash = InventoryEventChecksum.sha256(envelopeBody);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,causation_id,actor_ref,event_body,event_sha256)
        values (?,?,?,?,?,1,null,?,?,?,?::jsonb,?::jsonb,?)
        """,
        eventId,
        aggregateType,
        aggregateId.toString(),
        aggregateVersion,
        eventType,
        OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC),
        correlationId,
        causationId,
        actor == null ? null : json(actor),
        eventBody,
        InventoryEventChecksum.sha256(eventBody));
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
          envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,?,?,?,?,?,?::jsonb,?,'PENDING',0,clock_timestamp(),clock_timestamp())
        """,
        eventId,
        aggregateType,
        aggregateId.toString(),
        aggregateVersion,
        eventType,
        topic,
        envelopeBody,
        envelopeHash);
    return new AppendResult(eventId, aggregateVersion, envelopeHash);
  }

  public long currentVersion(String aggregateType, UUID aggregateId) {
    Long value =
        jdbc
            .query(
                "select current_version from event_stream_head where aggregate_type=? and"
                    + " aggregate_id=?",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                aggregateType,
                aggregateId.toString())
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Inventory event stream does not exist"));
    return value;
  }

  public List<StoredEvent> readStream(String aggregateType, UUID aggregateId) {
    return jdbc.query(
        """
        select event_id,aggregate_version,event_type,event_body::text,event_sha256
          from domain_event where aggregate_type=? and aggregate_id=?
         order by aggregate_version
        """,
        (resultSet, rowNumber) -> {
          String body = resultSet.getString("event_body");
          String hash = resultSet.getString("event_sha256").trim();
          if (!InventoryEventChecksum.sha256(body).equals(hash)) {
            throw new IllegalStateException("Stored inventory event checksum drifted");
          }
          return new StoredEvent(
              resultSet.getObject("event_id", UUID.class),
              resultSet.getLong("aggregate_version"),
              resultSet.getString("event_type"),
              read(body),
              hash);
        },
        aggregateType,
        aggregateId.toString());
  }

  @Transactional
  public Snapshot saveSnapshot(
      String aggregateType, UUID aggregateId, long aggregateVersion, JsonNode snapshot) {
    requireApprovedAggregateType(aggregateType);
    rejectForbidden(snapshot);
    if (currentVersion(aggregateType, aggregateId) != aggregateVersion) {
      throw InventoryException.conflict("Inventory snapshot version is stale");
    }
    String body = canonicalJson(json(snapshot));
    String hash = InventoryEventChecksum.sha256(body);
    int inserted =
        jdbc.update(
            """
            insert into aggregate_snapshot(
              aggregate_type,aggregate_id,aggregate_version,snapshot_body,snapshot_sha256,created_at)
            values (?,?,?,?::jsonb,?,clock_timestamp()) on conflict do nothing
            """,
            aggregateType,
            aggregateId.toString(),
            aggregateVersion,
            body,
            hash);
    if (inserted == 0) {
      String stored =
          jdbc.queryForObject(
              """
              select snapshot_sha256 from aggregate_snapshot
               where aggregate_type=? and aggregate_id=? and aggregate_version=?
              """,
              String.class,
              aggregateType,
              aggregateId.toString(),
              aggregateVersion);
      if (!hash.equals(stored == null ? null : stored.trim())) {
        throw InventoryException.conflict("Inventory snapshot bytes changed at the same version");
      }
    }
    return new Snapshot(aggregateVersion, read(body), hash);
  }

  public Snapshot latestSnapshot(String aggregateType, UUID aggregateId) {
    return jdbc
        .query(
            """
            select aggregate_version,snapshot_body::text,snapshot_sha256
              from aggregate_snapshot where aggregate_type=? and aggregate_id=?
             order by aggregate_version desc limit 1
            """,
            (resultSet, rowNumber) ->
                new Snapshot(
                    resultSet.getLong("aggregate_version"),
                    read(resultSet.getString("snapshot_body")),
                    resultSet.getString("snapshot_sha256").trim()),
            aggregateType,
            aggregateId.toString())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Inventory aggregate snapshot does not exist"));
  }

  private static void requireFamily(String aggregateType, String eventType, String topic) {
    requireApprovedAggregateType(aggregateType);
    boolean approved =
        switch (aggregateType) {
          case "SESSION" ->
              eventType.startsWith("inventory.session.")
                  && topic.equals("rwms.inventory.session.v1");
          case "FINDING" ->
              eventType.startsWith("inventory.finding.")
                  && topic.equals("rwms.inventory.session.v1");
          case "PUBLICATION" ->
              eventType.startsWith("inventory.publication.")
                  && topic.equals("rwms.inventory.publication.v1");
          default -> false;
        };
    if (!approved) throw new IllegalArgumentException("Inventory event family is not approved");
  }

  private static void requireApprovedAggregateType(String aggregateType) {
    if (!Set.of("SESSION", "FINDING", "PUBLICATION").contains(aggregateType)) {
      throw new IllegalArgumentException("Inventory aggregate type is not approved");
    }
  }

  private static void rejectForbidden(JsonNode node) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException("Event payload must be an object");
    node.propertyNames()
        .forEach(
            name -> {
              if (FORBIDDEN_KEYS.contains(name)) {
                throw new IllegalArgumentException("Event payload contains forbidden information");
              }
              JsonNode value = node.get(name);
              rejectNested(value);
            });
  }

  private static void rejectNested(JsonNode node) {
    if (node.isObject()) {
      node.propertyNames()
          .forEach(
              name -> {
                if (FORBIDDEN_KEYS.contains(name)) {
                  throw new IllegalArgumentException(
                      "Event payload contains forbidden information");
                }
                JsonNode value = node.get(name);
                rejectNested(value);
              });
    } else if (node.isArray()) {
      node.forEach(InventoryEventStore::rejectNested);
    } else if (node.isTextual()
        && DomainEventEnvelopeV2.containsSensitiveTechnicalValue(node.stringValue())) {
      throw new IllegalArgumentException("Event payload contains sensitive information");
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory event is not serializable", exception);
    }
  }

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory event JSON is corrupt", exception);
    }
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null)
      throw new IllegalStateException("PostgreSQL did not canonicalize event JSON");
    return canonical;
  }

  public record AppendResult(UUID eventId, long aggregateVersion, String envelopeSha256) {}

  public record AppendCommand(
      String aggregateType,
      UUID aggregateId,
      long expectedVersion,
      String eventType,
      String topic,
      JsonNode payload,
      UUID correlationId,
      UUID causationId,
      OpaqueActorReference actor) {}

  public record StoredEvent(
      UUID eventId,
      long aggregateVersion,
      String eventType,
      JsonNode payload,
      String payloadSha256) {}

  public record Snapshot(long aggregateVersion, JsonNode body, String sha256) {}
}
