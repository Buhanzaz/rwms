package dev.buhanzaz.rwms.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "analytics_aggregate_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsAggregateCheckpoint {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "consumer_group", nullable = false, length = 128)
  private String consumerGroup;

  @Column(name = "source_topic", nullable = false, length = 200)
  private String sourceTopic;

  @Column(name = "aggregate_type", nullable = false, length = 64)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "applied_version", nullable = false)
  private long appliedVersion;

  @Column(name = "gap_open", nullable = false)
  private boolean gapOpen;

  @Column(name = "expected_version")
  private Long expectedVersion;

  @Column(name = "observed_version")
  private Long observedVersion;

  @Column(name = "gap_attempt_count", nullable = false)
  private int gapAttemptCount;

  @Column(name = "gap_first_seen_at")
  private OffsetDateTime gapFirstSeenAt;

  @Column(name = "terminally_blocked", nullable = false)
  private boolean terminallyBlocked;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static AnalyticsAggregateCheckpoint start(
      String consumerGroup,
      String sourceTopic,
      String aggregateType,
      UUID aggregateId,
      OffsetDateTime now) {
    AnalyticsAggregateCheckpoint checkpoint = new AnalyticsAggregateCheckpoint();
    checkpoint.consumerGroup =
        AnalyticsDomainRules.text(consumerGroup, 128, "consumerGroup");
    checkpoint.sourceTopic = AnalyticsDomainRules.text(sourceTopic, 200, "sourceTopic");
    checkpoint.aggregateType = AnalyticsDomainRules.text(aggregateType, 64, "aggregateType");
    checkpoint.aggregateId = AnalyticsDomainRules.uuid(aggregateId, "aggregateId");
    checkpoint.appliedVersion = -1;
    checkpoint.updatedAt = AnalyticsDomainRules.require(now, "now");
    return checkpoint;
  }

  public long nextExpectedVersion() {
    return Math.addExact(appliedVersion, 1);
  }

  public void holdGap(long observed, OffsetDateTime now) {
    if (terminallyBlocked) throw new IllegalStateException("Aggregate checkpoint is terminal");
    long expected = nextExpectedVersion();
    if (observed <= expected) throw new IllegalArgumentException("Observed version is not a gap");
    gapOpen = true;
    expectedVersion = expected;
    observedVersion = observedVersion == null ? observed : Math.max(observedVersion, observed);
    if (gapFirstSeenAt == null) gapFirstSeenAt = AnalyticsDomainRules.require(now, "now");
    updatedAt = AnalyticsDomainRules.require(now, "now");
  }

  public void apply(long version, OffsetDateTime now) {
    if (terminallyBlocked) throw new IllegalStateException("Aggregate checkpoint is terminal");
    if (version != nextExpectedVersion()) {
      throw new IllegalArgumentException("Aggregate version is not contiguous");
    }
    appliedVersion = version;
    updatedAt = AnalyticsDomainRules.require(now, "now");
    if (gapOpen) {
      if (observedVersion != null && appliedVersion >= observedVersion) {
        clearGap();
      } else {
        expectedVersion = nextExpectedVersion();
      }
    }
  }

  public boolean retryGap(int maximumAttempts, OffsetDateTime now) {
    if (!gapOpen || terminallyBlocked) return terminallyBlocked;
    if (maximumAttempts < 1) throw new IllegalArgumentException("maximumAttempts must be positive");
    gapAttemptCount = Math.addExact(gapAttemptCount, 1);
    updatedAt = AnalyticsDomainRules.require(now, "now");
    if (gapAttemptCount >= maximumAttempts) {
      terminallyBlocked = true;
      return true;
    }
    return false;
  }

  private void clearGap() {
    gapOpen = false;
    expectedVersion = null;
    observedVersion = null;
    gapAttemptCount = 0;
    gapFirstSeenAt = null;
  }
}
