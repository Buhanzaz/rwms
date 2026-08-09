package dev.buhanzaz.rwms.dossier.domain;

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

/** Tracks locally processed offsets for one Kafka topic partition independently from per-aggregate ordering. */
@Entity
@Table(name = "dossier_partition_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierPartitionCheckpoint {
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

  public static DossierPartitionCheckpoint start(
      String consumerGroup, String sourceTopic, int sourcePartition, OffsetDateTime now) {
    if (sourcePartition < 0) throw new IllegalArgumentException("sourcePartition must be non-negative");
    DossierPartitionCheckpoint checkpoint = new DossierPartitionCheckpoint();
    checkpoint.consumerGroup = DossierSourceFact.requireText(consumerGroup, 128, "consumerGroup");
    checkpoint.sourceTopic = DossierSourceFact.requireText(sourceTopic, 200, "sourceTopic");
    checkpoint.sourcePartition = sourcePartition;
    checkpoint.lastAcceptedOffset = -1;
    checkpoint.updatedAt = DossierSourceFact.require(now, "now");
    return checkpoint;
  }

  public void advance(long offset, OffsetDateTime now) {
    if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
    if (offset <= lastAcceptedOffset) return;
    lastAcceptedOffset = offset;
    updatedAt = DossierSourceFact.require(now, "now");
  }
}
