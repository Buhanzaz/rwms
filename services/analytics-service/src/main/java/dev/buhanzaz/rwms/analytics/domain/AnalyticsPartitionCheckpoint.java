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

/** Tracks the highest locally handled source offset for one topic partition without replacing aggregate ordering checks. */
@Entity
@Table(name = "analytics_partition_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsPartitionCheckpoint {
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

  @Column(name = "source_partition", nullable = false)
  private int sourcePartition;

  @Column(name = "last_accepted_offset", nullable = false)
  private long lastAcceptedOffset;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static AnalyticsPartitionCheckpoint start(
      String consumerGroup, String sourceTopic, int sourcePartition, OffsetDateTime now) {
    if (sourcePartition < 0) throw new IllegalArgumentException("sourcePartition must be non-negative");
    AnalyticsPartitionCheckpoint checkpoint = new AnalyticsPartitionCheckpoint();
    checkpoint.consumerGroup =
        AnalyticsDomainRules.text(consumerGroup, 128, "consumerGroup");
    checkpoint.sourceTopic = AnalyticsDomainRules.text(sourceTopic, 200, "sourceTopic");
    checkpoint.sourcePartition = sourcePartition;
    checkpoint.lastAcceptedOffset = -1;
    checkpoint.updatedAt = AnalyticsDomainRules.require(now, "now");
    return checkpoint;
  }

  public void advance(long offset, OffsetDateTime now) {
    if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
    if (offset > lastAcceptedOffset) {
      lastAcceptedOffset = offset;
      updatedAt = AnalyticsDomainRules.require(now, "now");
    }
  }
}
