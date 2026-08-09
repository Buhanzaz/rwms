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
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

/** Audits one controlled rebuild of a target projection generation from the local source journal. */
@Entity
@Table(name = "dossier_replay_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierReplayRun {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "source_generation_id", nullable = false)
  private UUID sourceGenerationId;

  @Column(name = "target_generation_id", nullable = false)
  private UUID targetGenerationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private DossierReplayState state;

  @Column(name = "journal_high_water_at", nullable = false)
  private OffsetDateTime journalHighWaterAt;

  @Column(name = "partition_high_water_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String partitionHighWaterSha256;

  @Column(name = "source_row_count")
  private Long sourceRowCount;

  @Column(name = "target_row_count")
  private Long targetRowCount;

  @Column(name = "source_canonical_sha256", length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String sourceCanonicalSha256;

  @Column(name = "target_canonical_sha256", length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String targetCanonicalSha256;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  public static DossierReplayRun start(
      UUID sourceGenerationId,
      UUID targetGenerationId,
      OffsetDateTime journalHighWaterAt,
      String partitionHighWaterSha256,
      OffsetDateTime startedAt) {
    if (sourceGenerationId.equals(targetGenerationId)) {
      throw new IllegalArgumentException("Replay target generation must be inactive");
    }
    DossierReplayRun run = new DossierReplayRun();
    run.sourceGenerationId = DossierSourceFact.require(sourceGenerationId, "sourceGenerationId");
    run.targetGenerationId = DossierSourceFact.require(targetGenerationId, "targetGenerationId");
    run.state = DossierReplayState.BUILDING;
    run.journalHighWaterAt = DossierSourceFact.require(journalHighWaterAt, "journalHighWaterAt");
    run.partitionHighWaterSha256 = DossierSourceFact.requireDigest(partitionHighWaterSha256, "partitionHighWaterSha256");
    run.startedAt = DossierSourceFact.require(startedAt, "startedAt");
    return run;
  }

  public void tailing() {
    transition(DossierReplayState.BUILDING, DossierReplayState.TAILING);
  }

  public void verifying() {
    transition(DossierReplayState.TAILING, DossierReplayState.VERIFYING);
  }

  public boolean parity(long sourceCount, long targetCount, String sourceHash, String targetHash) {
    if (state != DossierReplayState.VERIFYING) throw new IllegalStateException("Replay is not verifying");
    if (sourceCount < 0 || targetCount < 0) throw new IllegalArgumentException("Replay counts must be non-negative");
    sourceRowCount = sourceCount;
    targetRowCount = targetCount;
    sourceCanonicalSha256 = DossierSourceFact.requireDigest(sourceHash, "sourceHash");
    targetCanonicalSha256 = DossierSourceFact.requireDigest(targetHash, "targetHash");
    boolean matches = sourceCount == targetCount && sourceHash.equals(targetHash);
    if (matches) state = DossierReplayState.READY;
    return matches;
  }

  public void reject(OffsetDateTime now) {
    if (state == DossierReplayState.REJECTED) return;
    if (state == DossierReplayState.ACTIVATED) {
      throw new IllegalStateException("Activated replay cannot be rejected");
    }
    state = DossierReplayState.REJECTED;
    completedAt = DossierSourceFact.require(now, "now");
  }

  public void activated(OffsetDateTime now) {
    transition(DossierReplayState.READY, DossierReplayState.ACTIVATED);
    completedAt = DossierSourceFact.require(now, "now");
  }

  private void transition(DossierReplayState expected, DossierReplayState target) {
    if (state != expected) throw new IllegalStateException("Invalid replay transition");
    state = target;
  }
}
