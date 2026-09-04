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
import java.time.LocalDate;
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

  @Column(name = "source_plan_id")
  private UUID sourcePlanId;

  @Column(name = "source_plan_version")
  private Long sourcePlanVersion;

  @Column(name = "source_plan_warehouse_id")
  private UUID sourcePlanWarehouseId;

  @Column(name = "source_plan_date")
  private LocalDate sourcePlanDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "planner_membership_state", nullable = false, length = 16)
  private PlannerMembershipState plannerMembershipState = PlannerMembershipState.ACTIVE;

  @Column(name = "removed_at")
  private OffsetDateTime removedAt;

  @Column(name = "removed_source_plan_version")
  private Long removedSourcePlanVersion;

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
    this(boardTaskId, externalTaskId, sourceClientId, sourceType, sourceId, null, null, null, null);
  }

  /** Creates a source link with optional, all-or-nothing planner lineage. */
  public TaskSyncSource(
      UUID boardTaskId,
      UUID externalTaskId,
      String sourceClientId,
      TaskSourceType sourceType,
      UUID sourceId,
      UUID sourcePlanId,
      Long sourcePlanVersion,
      UUID sourcePlanWarehouseId,
      LocalDate sourcePlanDate) {
    if ((sourceType == null) != (sourceId == null)) {
      throw new IllegalArgumentException("Task source type and id must be set together");
    }
    boolean plannerLineageAbsent =
        sourcePlanId == null
            && sourcePlanVersion == null
            && sourcePlanWarehouseId == null
            && sourcePlanDate == null;
    boolean plannerLineageValid =
        sourcePlanId != null
            && sourcePlanVersion != null
            && sourcePlanVersion >= 1
            && sourcePlanWarehouseId != null
            && sourcePlanDate != null
            && "logistics-service".equals(sourceClientId)
            && sourceType == TaskSourceType.LOGISTICS_DRIVER_TASK;
    if (!plannerLineageAbsent && !plannerLineageValid) {
      throw new IllegalArgumentException("Planner task lineage is incomplete or unauthorized");
    }
    this.boardTaskId = Objects.requireNonNull(boardTaskId);
    this.externalTaskId = Objects.requireNonNull(externalTaskId);
    this.sourceClientId = Objects.requireNonNull(sourceClientId);
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.sourcePlanId = sourcePlanId;
    this.sourcePlanVersion = sourcePlanVersion;
    this.sourcePlanWarehouseId = sourcePlanWarehouseId;
    this.sourcePlanDate = sourcePlanDate;
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

  public UUID getSourcePlanId() {
    return sourcePlanId;
  }

  public Long getSourcePlanVersion() {
    return sourcePlanVersion;
  }

  public UUID getSourcePlanWarehouseId() {
    return sourcePlanWarehouseId;
  }

  public LocalDate getSourcePlanDate() {
    return sourcePlanDate;
  }

  public PlannerMembershipState getPlannerMembershipState() {
    return plannerMembershipState;
  }

  public OffsetDateTime getRemovedAt() {
    return removedAt;
  }

  public Long getRemovedSourcePlanVersion() {
    return removedSourcePlanVersion;
  }

  /** Advances only the revision of the same planner lineage under an exact old-version fence. */
  public void advanceSourcePlan(long expectedVersion, long replacementVersion) {
    if (plannerMembershipState != PlannerMembershipState.ACTIVE
        || sourcePlanId == null
        || sourcePlanVersion == null
        || sourcePlanVersion != expectedVersion
        || replacementVersion <= expectedVersion) {
      throw new IllegalStateException("Planner task lineage version is stale or absent");
    }
    sourcePlanVersion = replacementVersion;
  }

  /** Retains a tombstone when an agreed cross-date reschedule removes old-day work. */
  public void removeFromSourcePlan(
      long expectedVersion, long replacementVersion, OffsetDateTime timestamp) {
    if (plannerMembershipState != PlannerMembershipState.ACTIVE
        || sourcePlanId == null
        || sourcePlanVersion == null
        || sourcePlanVersion != expectedVersion
        || replacementVersion <= expectedVersion) {
      throw new IllegalStateException("Planner task lineage version is stale or absent");
    }
    plannerMembershipState = PlannerMembershipState.REMOVED;
    removedAt = Objects.requireNonNull(timestamp);
    removedSourcePlanVersion = replacementVersion;
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
