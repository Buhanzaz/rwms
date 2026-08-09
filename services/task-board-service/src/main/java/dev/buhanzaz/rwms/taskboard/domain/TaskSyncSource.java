package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Check;
import org.hibernate.proxy.HibernateProxy;

/**
 * Stable ownership link between a task-board task and the source service's external task identity.
 *
 * <p>The source client and fingerprint make retried private synchronization idempotent and prevent
 * another producer from mutating the task.
 */
@Entity
@Table(
    name = "task_sync_source",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_task_sync_source_external",
            columnNames = "external_task_id"),
    indexes =
        @Index(
            name = "idx_task_sync_source_client_external",
            columnList = "source_client_id,external_task_id"))
@Check(
    name = "ck_task_sync_source_client",
    constraints = "source_client_id = btrim(source_client_id) and source_client_id <> ''")
@Check(
    name = "ck_task_sync_source_reference",
    constraints =
        "(source_type is null and source_id is null) or "
            + "(source_type in ('MAINTENANCE_REPAIR','LOGISTICS_DRIVER_TASK') "
            + "and source_id is not null)")
public class TaskSyncSource {
  @Id
  @NotNull
  @Column(name = "board_task_id", nullable = false)
  private UUID boardTaskId;

  @NotNull
  @Column(name = "external_task_id", nullable = false)
  private UUID externalTaskId;

  @NotBlank
  @Column(name = "source_client_id", nullable = false, length = 100)
  private String sourceClientId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", length = 32)
  private TaskSourceType sourceType;

  @Column(name = "source_id")
  private UUID sourceId;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected TaskSyncSource() {}

  public TaskSyncSource(UUID boardTaskId, UUID externalTaskId, String sourceClientId) {
    this(boardTaskId, externalTaskId, sourceClientId, null, null);
  }

  public TaskSyncSource(
      UUID boardTaskId,
      UUID externalTaskId,
      String sourceClientId,
      TaskSourceType sourceType,
      UUID sourceId) {
    if ((sourceType == null) != (sourceId == null)) {
      throw new IllegalArgumentException("Task source type and id must be set together");
    }
    this.boardTaskId = Objects.requireNonNull(boardTaskId);
    this.externalTaskId = Objects.requireNonNull(externalTaskId);
    this.sourceClientId = Objects.requireNonNull(sourceClientId);
    this.sourceType = sourceType;
    this.sourceId = sourceId;
  }

  public UUID getBoardTaskId() {
    return boardTaskId;
  }

  public UUID getExternalTaskId() {
    return externalTaskId;
  }

  public String getSourceClientId() {
    return sourceClientId;
  }

  public TaskSourceType getSourceType() {
    return sourceType;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public boolean hasSourceReference() {
    return sourceType != null;
  }

  public boolean hasSourceReference(TaskSourceType type, UUID id) {
    return sourceType == type && Objects.equals(sourceId, id);
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    if (thisClass != otherClass) return false;
    TaskSyncSource source = (TaskSyncSource) other;
    return boardTaskId != null && Objects.equals(boardTaskId, source.boardTaskId);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
