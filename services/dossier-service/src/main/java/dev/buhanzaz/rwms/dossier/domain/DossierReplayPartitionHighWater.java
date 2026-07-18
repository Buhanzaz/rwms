package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dossier_replay_partition_high_water")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierReplayPartitionHighWater {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "replay_run_id", nullable = false)
  private UUID runId;

  @Column(name = "source_topic", nullable = false, length = 200)
  private String sourceTopic;

  @Column(name = "source_partition", nullable = false)
  private int sourcePartition;

  @Column(name = "max_offset", nullable = false)
  private long maxOffset;

  public static DossierReplayPartitionHighWater capture(
      UUID runId, String sourceTopic, int sourcePartition, long maxOffset) {
    if (sourcePartition < 0 || maxOffset < -1) {
      throw new IllegalArgumentException("Replay partition coordinates are invalid");
    }
    DossierReplayPartitionHighWater highWater = new DossierReplayPartitionHighWater();
    highWater.runId = DossierSourceFact.require(runId, "runId");
    highWater.sourceTopic = DossierSourceFact.requireText(sourceTopic, 200, "sourceTopic");
    highWater.sourcePartition = sourcePartition;
    highWater.maxOffset = maxOffset;
    return highWater;
  }
}
