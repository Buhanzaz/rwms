package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.domain.WorkerEmploymentType;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentMode;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Typed private logistics contract for contractor drivers and operational warehouse history. */
public final class LogisticsDriverAssignmentApiModels {
  private LogisticsDriverAssignmentApiModels() {}

  /**
   * Stable contractor-driver profile creation request.
   *
   * @param contractorId caller-stable worker identity used for idempotent replay
   * @param displayName operator-facing name
   * @param phone contractor contact number
   * @param availableFrom inclusive contract availability start
   * @param availableUntil exclusive contract availability end
   * @param comment optional logistics note
   */
  public record CreateContractorDriverRequest(
      @NotNull UUID contractorId,
      @NotBlank @Size(max = 256) String displayName,
      @NotBlank @Size(max = 64) String phone,
      @NotNull OffsetDateTime availableFrom,
      @NotNull OffsetDateTime availableUntil,
      @Size(max = 1000) String comment) {}

  /**
   * Persisted contractor-driver profile without authentication data.
   *
   * @param workerId task-board worker identity
   * @param version optimistic worker version
   * @param homeWarehouseId immutable home/availability warehouse
   * @param displayName operator-facing name
   * @param phone contractor contact number
   * @param availableFrom inclusive contract availability start
   * @param availableUntil exclusive contract availability end
   * @param comment optional logistics note
   * @param active whether the contractor may receive work
   * @param employmentType always CONTRACTOR for this response
   */
  public record ContractorDriverResponse(
      UUID workerId,
      long version,
      UUID homeWarehouseId,
      String displayName,
      String phone,
      OffsetDateTime availableFrom,
      OffsetDateTime availableUntil,
      String comment,
      boolean active,
      WorkerEmploymentType employmentType) {}

  /**
   * Creates one transfer-backed operational assignment without changing a worker's home warehouse.
   *
   * @param transferId logistics transfer identity
   * @param workerId task-board worker identity
   * @param sourceWarehouseId derived operational source expected by the caller
   * @param destinationWarehouseId operational destination
   * @param mode temporary/permanent placement or one trip without changing placement
   * @param travelStartsAt instant from which the driver is unavailable while travelling
   * @param effectiveFrom earliest availability after arrival and technical buffer
   * @param effectiveUntil exclusive end of a temporary placement, or the same instant as {@code
   *     effectiveFrom} for a trip-only commitment
   */
  public record CreateWorkerOperationalAssignmentRequest(
      @NotNull UUID transferId,
      @NotNull UUID workerId,
      @NotNull UUID sourceWarehouseId,
      @NotNull UUID destinationWarehouseId,
      @NotNull WorkerOperationalAssignmentMode mode,
      @NotNull OffsetDateTime travelStartsAt,
      @NotNull OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {}

  /**
   * Expected-version lifecycle transition request.
   *
   * @param expectedVersion observed assignment version
   * @param targetStatus requested next lifecycle state
   */
  public record TransitionWorkerOperationalAssignmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull WorkerOperationalAssignmentStatus targetStatus) {}

  /**
   * Transfer-backed operational warehouse assignment snapshot.
   *
   * @param id assignment identity
   * @param version optimistic assignment version
   * @param transferId logistics transfer identity
   * @param workerId task-board worker identity
   * @param homeWarehouseId immutable worker home warehouse snapshot
   * @param sourceWarehouseId operational source warehouse
   * @param destinationWarehouseId operational destination warehouse
   * @param mode temporary/permanent placement or one trip without changing placement
   * @param status current physical lifecycle state
   * @param travelStartsAt instant from which the driver is unavailable at every warehouse
   * @param effectiveFrom earliest destination availability after arrival buffer
   * @param effectiveUntil exclusive temporary assignment end, or trip-only end instant
   * @param createdAt creation timestamp
   * @param updatedAt last lifecycle update timestamp
   * @param createdBy authenticated service actor
   * @param updatedBy authenticated last service actor
   */
  public record WorkerOperationalAssignmentResponse(
      UUID id,
      long version,
      UUID transferId,
      UUID workerId,
      UUID homeWarehouseId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      WorkerOperationalAssignmentMode mode,
      WorkerOperationalAssignmentStatus status,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      String createdBy,
      String updatedBy) {}
}
