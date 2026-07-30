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
import jakarta.validation.constraints.NotNull;
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

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "queue_definition_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_queue_usage_definition"))
  private QueueDefinition definition;

  @Enumerated(EnumType.STRING)
  @Column(name = "reference_type", nullable = false, length = 32)
  private QueueReferenceType referenceType;

  @Column(name = "external_reference_id", nullable = false, length = 128)
  private String externalReferenceId;

  public QueueDefinition getDefinition() {
    return definition;
  }

  public void setDefinition(QueueDefinition value) {
    definition = value;
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
