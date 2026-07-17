package dev.buhanzaz.rwms.maintenance.integration;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Development/test fixture only; production safety rejects disabled dependencies. */
final class NoOpMaintenanceDependencyGateway implements MaintenanceDependencyGateway {
  @Override
  public boolean productionReady() { return false; }
  @Override
  public LeaseSnapshot acquireLease(
      UUID key, UUID rentalItemId, long expectedVersion, String ownerType, String ownerId) {
    return new LeaseSnapshot(
        deterministic("lease", key), 0, rentalItemId, ownerType, UUID.fromString(ownerId), 1,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
  }

  @Override
  public LeaseSnapshot renewLease(
      UUID key,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {
    return new LeaseSnapshot(
        leaseId,
        Math.addExact(leaseExpectedVersion, 1),
        deterministic("rental-item", leaseId),
        ownerType,
        UUID.fromString(ownerId),
        fencingToken,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
  }

  @Override
  public AssetSnapshot fencedStatus(
      UUID key,
      UUID rentalItemId,
      UUID warehouseId,
      long rentalItemExpectedVersion,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      String ownerId,
      String transition,
      boolean linkedReturn) {
    String status = transition.contains("FREE") ? "FREE"
        : transition.contains("WRITE_OFF") ? "WRITTEN_OFF"
        : transition.contains("WAITING") ? "WAITING_REPAIR_CHECK" : "REPAIR";
    return new AssetSnapshot(
        rentalItemId, Math.addExact(rentalItemExpectedVersion, 1), warehouseId, status);
  }

  @Override
  public void releaseLease(
      UUID key,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {}

  @Override
  public TaskSnapshot registerTask(
      UUID key,
      UUID externalTaskId,
      UUID warehouseId,
      UUID rentalItemId,
      List<TaskStage> stages) {
    return task(externalTaskId, stages, 0, "ACTIVE");
  }

  @Override
  public TaskSnapshot updatePreStartTask(
      UUID key, UUID externalTaskId, long expectedVersion, List<TaskStage> stages) {
    return task(externalTaskId, stages, Math.addExact(expectedVersion, 1), "ACTIVE");
  }

  @Override
  public TaskSnapshot getTask(UUID externalTaskId) {
    return new TaskSnapshot(externalTaskId, 0, "ACTIVE", List.of());
  }

  @Override
  public TaskSnapshot cancelTask(UUID key, UUID externalTaskId, long expectedVersion) {
    return new TaskSnapshot(
        externalTaskId, Math.addExact(expectedVersion, 1), "CANCELLED", List.of());
  }

  private static TaskSnapshot task(
      UUID externalTaskId, List<TaskStage> stages, long version, String state) {
    return new TaskSnapshot(externalTaskId, version, state, stages.stream()
        .map(stage -> new TaskStageSnapshot(
            stage.order(), deterministic("entry:" + stage.order(), externalTaskId), 0))
        .toList());
  }

  private static UUID deterministic(String prefix, UUID key) {
    return UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8));
  }
}
