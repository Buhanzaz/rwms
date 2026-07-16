package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(
    name = "queue_usage_reference",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_queue_usage_reference",
            columnNames = {"reference_type", "external_reference_id"}))
public class QueueUsageReference extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "queue_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_queue_usage_queue"))
  private WorkQueue queue;

  @Enumerated(EnumType.STRING)
  @Column(name = "reference_type", nullable = false, length = 32)
  private QueueReferenceType referenceType;

  @Column(name = "external_reference_id", nullable = false, length = 128)
  private String externalReferenceId;

  public WorkQueue getQueue() {
    return queue;
  }

  public void setQueue(WorkQueue v) {
    queue = v;
  }

  public QueueReferenceType getReferenceType() {
    return referenceType;
  }

  public void setReferenceType(QueueReferenceType v) {
    referenceType = v;
  }

  public String getExternalReferenceId() {
    return externalReferenceId;
  }

  public void setExternalReferenceId(String v) {
    externalReferenceId = v;
  }

  public void touch() {
    revisionMarker = UUID.randomUUID();
  }

  public UUID getRevisionMarker() {
    return revisionMarker;
  }
}
