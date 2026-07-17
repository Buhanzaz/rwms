package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected TaskSyncSource() {}

  public TaskSyncSource(UUID boardTaskId, UUID externalTaskId, String sourceClientId) {
    this.boardTaskId = Objects.requireNonNull(boardTaskId);
    this.externalTaskId = Objects.requireNonNull(externalTaskId);
    this.sourceClientId = Objects.requireNonNull(sourceClientId);
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
