package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(
    name = "queue_definition",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_queue_definition_identity",
            columnNames = {"normalized_name", "queue_type"}))
public class QueueDefinition extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @NotBlank
  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @NotBlank
  @Column(name = "normalized_name", nullable = false, length = 128)
  private String normalizedName;

  @Column(name = "description", length = 1000)
  private String description;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "queue_type", nullable = false, length = 32)
  private QueueType type = QueueType.REPAIR;

  @PrePersist
  @PreUpdate
  void normalize() {
    name = name == null ? null : name.trim().replaceAll("\\s+", " ");
    normalizedName = normalizeName(name);
  }

  public static String normalizeName(String value) {
    return value == null
        ? null
        : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    name = value;
    normalizedName = normalizeName(value);
  }

  public String getNormalizedName() {
    return normalizedName;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String value) {
    description = value == null ? null : value.trim();
  }

  public QueueType getType() {
    return type;
  }

  public void setType(QueueType value) {
    type = value;
  }

  public UUID getRevisionMarker() {
    return revisionMarker;
  }

  public void touch() {
    revisionMarker = UUID.randomUUID();
  }
}
