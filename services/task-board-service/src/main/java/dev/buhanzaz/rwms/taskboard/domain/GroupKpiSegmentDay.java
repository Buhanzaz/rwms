package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(
    name = "group_kpi_segment_day",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_group_kpi_segment_day",
            columnNames = {"segment_id", "evidence_id"}))
public class GroupKpiSegmentDay extends AbstractVersionedEntity {
  @Column(name = "segment_id", nullable = false)
  private UUID segmentId;

  @Column(name = "evidence_id", nullable = false)
  private UUID evidenceId;

  @Column(name = "active_seconds", nullable = false)
  private long activeSeconds;

  protected GroupKpiSegmentDay() {}

  public GroupKpiSegmentDay(UUID segmentId, UUID evidenceId) {
    this.segmentId = segmentId;
    this.evidenceId = evidenceId;
  }

  public void addActiveSeconds(long value) {
    if (value < 0) throw new IllegalArgumentException("Активное время не может быть отрицательным");
    activeSeconds = Math.addExact(activeSeconds, value);
  }

  public UUID getSegmentId() {
    return segmentId;
  }

  public UUID getEvidenceId() {
    return evidenceId;
  }

  public long getActiveSeconds() {
    return activeSeconds;
  }
}
