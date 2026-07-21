package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dossier_aggregate_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierAggregateCheckpoint {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "consumer_group", nullable = false, length = 128)
  private String consumerGroup;

  @Enumerated(EnumType.STRING)
  @Column(name = "producer", nullable = false, length = 24)
  private DossierProducer producer;

  @Column(name = "source_topic", nullable = false, length = 200)
  private String sourceTopic;

  @Column(name = "aggregate_type", nullable = false, length = 64)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "applied_version", nullable = false)
  private long appliedVersion;

  @Column(name = "blocked", nullable = false)
  private boolean blocked;

  @Enumerated(EnumType.STRING)
  @Column(name = "blocked_reason", length = 40)
  private DossierAggregateBlockReason blockedReason;

  @Column(name = "expected_version")
  private Long expectedVersion;

  @Column(name = "observed_version")
  private Long observedVersion;

  @Column(name = "blocked_at")
  private OffsetDateTime blockedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static DossierAggregateCheckpoint start(
      String consumerGroup,
      DossierProducer producer,
      String sourceTopic,
      String aggregateType,
      UUID aggregateId,
      long initialAppliedVersion,
      OffsetDateTime now) {
    if (initialAppliedVersion < -1 || initialAppliedVersion > 0) {
      throw new IllegalArgumentException("initialAppliedVersion must be -1 or 0");
    }
    DossierAggregateCheckpoint checkpoint = new DossierAggregateCheckpoint();
    checkpoint.consumerGroup = DossierSourceFact.requireText(consumerGroup, 128, "consumerGroup");
    checkpoint.producer = DossierSourceFact.require(producer, "producer");
    checkpoint.sourceTopic = DossierSourceFact.requireText(sourceTopic, 200, "sourceTopic");
    checkpoint.aggregateType = DossierSourceFact.requireText(aggregateType, 64, "aggregateType");
    checkpoint.aggregateId = DossierSourceFact.require(aggregateId, "aggregateId");
    checkpoint.appliedVersion = initialAppliedVersion;
    checkpoint.updatedAt = DossierSourceFact.require(now, "now");
    return checkpoint;
  }

  public long nextExpectedVersion() {
    return Math.addExact(appliedVersion, 1);
  }

  public void apply(long version, OffsetDateTime now) {
    if (blocked) throw new IllegalStateException("Blocked aggregate must be reconciled before apply");
    if (version != nextExpectedVersion()) throw new IllegalArgumentException("Aggregate version is not contiguous");
    appliedVersion = version;
    updatedAt = DossierSourceFact.require(now, "now");
  }

  public void blockGap(long observedVersion, OffsetDateTime now) {
    long expected = nextExpectedVersion();
    if (observedVersion <= expected) throw new IllegalArgumentException("Observed version does not form a gap");
    block(DossierAggregateBlockReason.MISSING_PREFIX, expected, observedVersion, now);
  }

  public void blockConflict(DossierAggregateBlockReason reason, OffsetDateTime now) {
    if (reason == null || reason == DossierAggregateBlockReason.MISSING_PREFIX) {
      throw new IllegalArgumentException("A fixed identity or media conflict reason is required");
    }
    block(reason, null, null, now);
  }

  public void block(
      DossierAggregateBlockReason reason,
      Long expectedVersion,
      Long observedVersion,
      OffsetDateTime now) {
    DossierSourceFact.require(reason, "reason");
    if (reason == DossierAggregateBlockReason.MISSING_PREFIX) {
      long expected = nextExpectedVersion();
      if (expectedVersion == null
          || observedVersion == null
          || expectedVersion != expected
          || observedVersion <= expectedVersion) {
        throw new IllegalArgumentException("Missing-prefix block requires the exact version gap");
      }
    } else if (expectedVersion != null || observedVersion != null) {
      throw new IllegalArgumentException("Conflict blocks do not carry gap versions");
    }
    blocked = true;
    blockedReason = reason;
    this.expectedVersion = expectedVersion;
    this.observedVersion = observedVersion;
    blockedAt = DossierSourceFact.require(now, "now");
    updatedAt = now;
  }

  public void reconcile(long appliedVersion, OffsetDateTime now) {
    if (!blocked
        || blockedReason != DossierAggregateBlockReason.MISSING_PREFIX
        || appliedVersion < this.appliedVersion) {
      throw new IllegalStateException("Only a missing-prefix block can be reconciled forward");
    }
    this.appliedVersion = appliedVersion;
    blocked = false;
    blockedReason = null;
    expectedVersion = null;
    observedVersion = null;
    blockedAt = null;
    updatedAt = DossierSourceFact.require(now, "now");
  }

  public void recoverProcessingFailure(long appliedVersion, OffsetDateTime now) {
    if (!blocked
        || blockedReason != DossierAggregateBlockReason.PROCESSING_FAILED
        || appliedVersion <= this.appliedVersion) {
      throw new IllegalStateException("Only a failed forward projection can be recovered");
    }
    this.appliedVersion = appliedVersion;
    blocked = false;
    blockedReason = null;
    expectedVersion = null;
    observedVersion = null;
    blockedAt = null;
    updatedAt = DossierSourceFact.require(now, "now");
  }
}
