package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable validated source journal fact used for deduplication, recovery and generation replay. */
@Entity
@Table(name = "dossier_source_fact")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierSourceFact {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "producer", nullable = false, length = 24)
  private DossierProducer producer;

  @Column(name = "source_topic", nullable = false, length = 200)
  private String sourceTopic;

  @Column(name = "source_partition", nullable = false)
  private int sourcePartition;

  @Column(name = "source_offset", nullable = false)
  private long sourceOffset;

  @Column(name = "kafka_key", nullable = false)
  private UUID kafkaKey;

  @Column(name = "aggregate_type", nullable = false, length = 64)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "event_type", nullable = false, length = 160)
  private String eventType;

  @Column(name = "event_version", nullable = false)
  private int eventVersion;

  @Column(name = "payload_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String payloadSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "canonical_envelope", nullable = false, columnDefinition = "jsonb")
  private String canonicalEnvelope;

  @Column(name = "occurred_at")
  private OffsetDateTime occurredAt;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "actor_subject_id")
  private UUID actorSubjectId;

  @Column(name = "actor_principal_type", length = 64)
  private String actorPrincipalType;

  @Column(name = "actor_profile_revision", length = 64)
  private String actorProfileRevision;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "causation_id")
  private UUID causationId;

  @Column(name = "subject_cabin_id")
  private UUID subjectCabinId;

  @Column(name = "subject_warehouse_id")
  private UUID subjectWarehouseId;

  @Column(name = "subject_secondary_id")
  private UUID subjectSecondaryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "activity_code", length = 64)
  private DossierActivityCode activityCode;

  @Column(name = "ingested_at", nullable = false)
  private OffsetDateTime ingestedAt;

  public static DossierSourceFact record(
      UUID eventId,
      DossierProducer producer,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      UUID kafkaKey,
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      String eventType,
      int eventVersion,
      String payloadSha256,
      String canonicalEnvelope,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      UUID actorSubjectId,
      String actorPrincipalType,
      String actorProfileRevision,
      UUID correlationId,
      UUID causationId,
      UUID subjectCabinId,
      UUID subjectWarehouseId,
      UUID subjectSecondaryId,
      DossierActivityCode activityCode,
      OffsetDateTime ingestedAt) {
    DossierSourceFact fact = new DossierSourceFact();
    fact.eventId = require(eventId, "eventId");
    fact.producer = require(producer, "producer");
    fact.sourceTopic = requireText(sourceTopic, 200, "sourceTopic");
    if (sourcePartition < 0 || sourceOffset < 0 || aggregateVersion < 0) {
      throw new IllegalArgumentException("Kafka coordinates and aggregateVersion must be non-negative");
    }
    fact.sourcePartition = sourcePartition;
    fact.sourceOffset = sourceOffset;
    fact.kafkaKey = require(kafkaKey, "kafkaKey");
    fact.aggregateType = requireText(aggregateType, 64, "aggregateType");
    fact.aggregateId = require(aggregateId, "aggregateId");
    if (!kafkaKey.equals(aggregateId)) throw new IllegalArgumentException("Kafka key must equal aggregateId");
    fact.aggregateVersion = aggregateVersion;
    fact.eventType = requireText(eventType, 160, "eventType");
    if (eventVersion <= 0) throw new IllegalArgumentException("eventVersion must be positive");
    fact.eventVersion = eventVersion;
    fact.payloadSha256 = requireDigest(payloadSha256, "payloadSha256");
    fact.canonicalEnvelope = requireJsonObject(canonicalEnvelope, "canonicalEnvelope");
    fact.occurredAt = occurredAt;
    fact.recordedAt = require(recordedAt, "recordedAt");
    fact.actorSubjectId = actorSubjectId;
    if (actorSubjectId == null) {
      if (actorPrincipalType != null || actorProfileRevision != null) {
        throw new IllegalArgumentException("Actor metadata requires actorSubjectId");
      }
    } else {
      if (actorPrincipalType == null || !actorPrincipalType.matches("[A-Z][A-Z0-9_]{0,63}")) {
        throw new IllegalArgumentException("actorPrincipalType is invalid");
      }
    }
    fact.actorPrincipalType = actorPrincipalType;
    fact.actorProfileRevision = actorProfileRevision;
    fact.correlationId = require(correlationId, "correlationId");
    fact.causationId = causationId;
    if (subjectCabinId != null && subjectWarehouseId == null) {
      throw new IllegalArgumentException("A cabin subject snapshot requires its warehouse snapshot");
    }
    fact.subjectCabinId = subjectCabinId;
    fact.subjectWarehouseId = subjectWarehouseId;
    fact.subjectSecondaryId = subjectSecondaryId;
    fact.activityCode = activityCode;
    fact.ingestedAt = require(ingestedAt, "ingestedAt");
    return fact;
  }

  static <T> T require(T value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    return value;
  }

  static String requireText(String value, int max, String field) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(field + " is required and must not exceed " + max);
    }
    return value;
  }

  static String requireDigest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  static String requireJsonObject(String value, String field) {
    String normalized = requireText(value, Integer.MAX_VALUE, field).strip();
    if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return normalized;
  }
}
