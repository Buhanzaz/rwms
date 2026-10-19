package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable transfer-backed history of a worker's operational warehouse without mutating the home
 * warehouse stored by {@link Worker}.
 */
@Entity
@Table(
    name = "worker_operational_assignment",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_worker_operational_assignment_transfer_worker",
            columnNames = {"transfer_id", "worker_id"}),
    indexes = {
      @Index(
          name = "idx_worker_operational_assignment_worker_status",
          columnList = "worker_id,status,travel_starts_at"),
      @Index(
          name = "idx_worker_operational_assignment_destination_status",
          columnList = "destination_warehouse_id,status,effective_from")
    })
public class WorkerOperationalAssignment extends AbstractVersionedEntity {
  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_worker_operational_assignment_worker"))
  private Worker worker;

  @NotNull
  @Column(name = "transfer_id", nullable = false)
  private UUID transferId;

  @NotNull
  @Column(name = "home_warehouse_id", nullable = false)
  private UUID homeWarehouseId;

  @NotNull
  @Column(name = "source_warehouse_id", nullable = false)
  private UUID sourceWarehouseId;

  @NotNull
  @Column(name = "destination_warehouse_id", nullable = false)
  private UUID destinationWarehouseId;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "assignment_mode", nullable = false, length = 16)
  private WorkerOperationalAssignmentMode mode;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private WorkerOperationalAssignmentStatus status;

  @NotNull
  @Column(name = "travel_starts_at", nullable = false)
  private OffsetDateTime travelStartsAt;

  @NotNull
  @Column(name = "effective_from", nullable = false)
  private OffsetDateTime effectiveFrom;

  @Column(name = "effective_until")
  private OffsetDateTime effectiveUntil;

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @NotBlank
  @Column(name = "created_by", nullable = false, length = 128)
  private String createdBy;

  @NotBlank
  @Column(name = "updated_by", nullable = false, length = 128)
  private String updatedBy;

  protected WorkerOperationalAssignment() {}

  /**
   * Creates a planned assignment or trip commitment whose caller-calculated interval controls
   * operational availability.
   */
  public static WorkerOperationalAssignment planned(
      Worker worker,
      UUID transferId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      WorkerOperationalAssignmentMode mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil,
      OffsetDateTime now,
      String actor) {
    Objects.requireNonNull(worker, "worker");
    var assignment = new WorkerOperationalAssignment();
    assignment.worker = worker;
    assignment.transferId = Objects.requireNonNull(transferId, "transferId");
    assignment.homeWarehouseId = worker.getWarehouseId();
    assignment.sourceWarehouseId = Objects.requireNonNull(sourceWarehouseId, "sourceWarehouseId");
    assignment.destinationWarehouseId =
        Objects.requireNonNull(destinationWarehouseId, "destinationWarehouseId");
    assignment.mode = Objects.requireNonNull(mode, "mode");
    assignment.status = WorkerOperationalAssignmentStatus.PLANNED;
    assignment.travelStartsAt =
        Objects.requireNonNull(travelStartsAt, "travelStartsAt");
    assignment.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    assignment.effectiveUntil = effectiveUntil;
    assignment.createdAt = Objects.requireNonNull(now, "now");
    assignment.updatedAt = now;
    assignment.createdBy = requireActor(actor);
    assignment.updatedBy = assignment.createdBy;
    assignment.validateDefinition();
    return assignment;
  }

  /**
   * Applies one legal physical lifecycle transition; replaying the current status is a no-op.
   */
  public void transitionTo(
      WorkerOperationalAssignmentStatus target, OffsetDateTime now, String actor) {
    Objects.requireNonNull(target, "target");
    if (status == target) {
      return;
    }
    boolean allowed =
        switch (status) {
          case PLANNED ->
              target == WorkerOperationalAssignmentStatus.IN_TRANSIT
                  || target == WorkerOperationalAssignmentStatus.CANCELLED;
          case IN_TRANSIT ->
              mode == WorkerOperationalAssignmentMode.TRIP_ONLY
                  ? target == WorkerOperationalAssignmentStatus.COMPLETED
                      || target == WorkerOperationalAssignmentStatus.CANCELLED
                  : target == WorkerOperationalAssignmentStatus.ACTIVE;
          case ACTIVE -> target == WorkerOperationalAssignmentStatus.COMPLETED;
          case COMPLETED, CANCELLED -> false;
        };
    if (!allowed) {
      throw new IllegalStateException(
          "Недопустимый переход оперативного назначения: " + status + " -> " + target);
    }
    status = target;
    updatedAt = Objects.requireNonNull(now, "now");
    updatedBy = requireActor(actor);
  }

  /** Returns whether a create replay carries exactly the original assignment definition. */
  public boolean hasDefinition(
      UUID requestedSourceWarehouseId,
      UUID requestedDestinationWarehouseId,
      WorkerOperationalAssignmentMode requestedMode,
      OffsetDateTime requestedTravelStartsAt,
      OffsetDateTime requestedEffectiveFrom,
      OffsetDateTime requestedEffectiveUntil) {
    return sourceWarehouseId.equals(requestedSourceWarehouseId)
        && destinationWarehouseId.equals(requestedDestinationWarehouseId)
        && mode == requestedMode
        && travelStartsAt.isEqual(requestedTravelStartsAt)
        && effectiveFrom.isEqual(requestedEffectiveFrom)
        && sameInstant(effectiveUntil, requestedEffectiveUntil);
  }

  /** Returns whether this assignment's operational interval intersects the requested interval. */
  public boolean overlaps(OffsetDateTime requestedFrom, OffsetDateTime requestedUntil) {
    boolean startsBeforeRequestedEnd =
        requestedUntil == null || travelStartsAt.isBefore(requestedUntil);
    boolean endsAfterRequestedStart =
        effectiveUntil == null || effectiveUntil.isAfter(requestedFrom);
    return startsBeforeRequestedEnd && endsAfterRequestedStart;
  }

  /** Returns whether the assignment still blocks another operational assignment. */
  public boolean isNonTerminal() {
    return status == WorkerOperationalAssignmentStatus.PLANNED
        || status == WorkerOperationalAssignmentStatus.IN_TRANSIT
        || status == WorkerOperationalAssignmentStatus.ACTIVE;
  }

  private void validateDefinition() {
    if (sourceWarehouseId.equals(destinationWarehouseId)) {
      throw new IllegalArgumentException("Склад отправления совпадает со складом назначения");
    }
    if (!travelStartsAt.isBefore(effectiveFrom)) {
      throw new IllegalArgumentException(
          "Начало дороги должно быть раньше доступности после прибытия");
    }
    if (mode == WorkerOperationalAssignmentMode.TEMPORARY
        && (effectiveUntil == null || !effectiveUntil.isAfter(effectiveFrom))) {
      throw new IllegalArgumentException(
          "Временное назначение должно заканчиваться после начала");
    }
    if (mode == WorkerOperationalAssignmentMode.PERMANENT && effectiveUntil != null) {
      throw new IllegalArgumentException("Постоянное назначение не может иметь дату окончания");
    }
    if (mode == WorkerOperationalAssignmentMode.TRIP_ONLY
        && (effectiveUntil == null || !effectiveUntil.isEqual(effectiveFrom))) {
      throw new IllegalArgumentException(
          "Разовый рейс должен завершаться в момент окончания поездки");
    }
  }

  private static String requireActor(String actor) {
    String normalized = actor == null ? null : actor.trim();
    if (normalized == null || normalized.isEmpty() || normalized.length() > 128) {
      throw new IllegalArgumentException("Некорректный субъект операции назначения");
    }
    return normalized;
  }

  private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
    return left == null ? right == null : right != null && left.isEqual(right);
  }

  public Worker getWorker() {
    return worker;
  }

  public UUID getTransferId() {
    return transferId;
  }

  public UUID getHomeWarehouseId() {
    return homeWarehouseId;
  }

  public UUID getSourceWarehouseId() {
    return sourceWarehouseId;
  }

  public UUID getDestinationWarehouseId() {
    return destinationWarehouseId;
  }

  public WorkerOperationalAssignmentMode getMode() {
    return mode;
  }

  public WorkerOperationalAssignmentStatus getStatus() {
    return status;
  }

  public OffsetDateTime getTravelStartsAt() {
    return travelStartsAt;
  }

  public OffsetDateTime getEffectiveFrom() {
    return effectiveFrom;
  }

  public OffsetDateTime getEffectiveUntil() {
    return effectiveUntil;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }
}
