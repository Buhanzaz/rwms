package dev.buhanzaz.rwms.maintenance.integration;

import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MaintenanceDependencyGateway {
  default boolean productionReady() { return true; }
  LeaseSnapshot acquireLease(
      UUID idempotencyKey,
      UUID rentalItemId,
      long rentalItemExpectedVersion,
      String ownerType,
      String ownerId);

  LeaseSnapshot renewLease(
      UUID idempotencyKey,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId);

  AssetSnapshot fencedStatus(
      UUID idempotencyKey,
      UUID rentalItemId,
      UUID warehouseId,
      long rentalItemExpectedVersion,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      String ownerId,
      String transition,
      boolean linkedReturn);

  void releaseLease(
      UUID idempotencyKey,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId);

  TaskSnapshot registerTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      UUID warehouseId,
      UUID rentalItemId,
      List<TaskStage> stages);

  TaskSnapshot updatePreStartTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      long expectedVersion,
      List<TaskStage> stages);

  TaskSnapshot getTask(UUID externalTaskId);

  TaskSnapshot cancelTask(
      UUID idempotencyKey, UUID externalTaskId, long expectedVersion);

  record LeaseSnapshot(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      String ownerType,
      UUID ownerId,
      long fencingToken,
      OffsetDateTime expiresAt) {}

  record AssetSnapshot(UUID rentalItemId, long version, UUID warehouseId, String status) {}

  record TaskStage(
      UUID stageId,
      int order,
      RepairStageKind kind,
      String title,
      String queueRef,
      OffsetDateTime taskDeadline) {}

  record TaskStageSnapshot(int routeIndex, UUID taskBoardEntryId, long entryVersion) {}

  record TaskSnapshot(
      UUID externalTaskId,
      long version,
      String state,
      List<TaskStageSnapshot> stages) {}
}
