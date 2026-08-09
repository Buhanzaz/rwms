package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateResult;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecisionDraft;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.repository.PropertyDispositionDecisionRepository;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.WarehouseLifecycleOperations;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Materializes repair-derived furniture custody and unaccounted-cabin decisions.
 *
 * <p>The caller-facing custody path completes warehouse admission before it opens its short local
 * transaction; local reconciliation callers use the facade's existing MANDATORY seam instead.
 */
@Service
final class PropertyDispositionFurnitureMaterialization {
  private final PropertyDispositionDecisionRepository decisions;
  private final PropertyDispositionCommandBoundary commands;
  private final PropertyDispositionDecisionPersistence persistence;
  private final PropertyDispositionReadProjection readProjection;
  private final PropertyDispositionRepairChain repairChain;
  private final PropertyDispositionActorCodec actors;
  private final WarehouseLifecycleOperations warehouseLifecycle;

  PropertyDispositionFurnitureMaterialization(
      PropertyDispositionDecisionRepository decisions,
      PropertyDispositionCommandBoundary commands,
      PropertyDispositionDecisionPersistence persistence,
      PropertyDispositionReadProjection readProjection,
      PropertyDispositionRepairChain repairChain,
      PropertyDispositionActorCodec actors,
      WarehouseLifecycleOperations warehouseLifecycle) {
    this.decisions = decisions;
    this.commands = commands;
    this.persistence = persistence;
    this.readProjection = readProjection;
    this.repairChain = repairChain;
    this.actors = actors;
    this.warehouseLifecycle = warehouseLifecycle;
  }

  int materializeFurnitureCustody(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    validateFurnitureCustodyInput(completedRepair, equipmentNames, claims);
    // Warehouse admission is a remote read. It must complete before the short local transaction
    // that creates durable disposition decisions and can acquire aggregate/row locks.
    warehouseLifecycle.requireOutgoing(completedRepair.getWarehouseId());
    return commands.requiredResult(
        commands.execute(
            () -> materializeFurnitureCustodyLocally(completedRepair, equipmentNames, claims)));
  }

  int materializeFurnitureCustodyLocally(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    validateFurnitureCustodyInput(completedRepair, equipmentNames, claims);
    return materializeFurnitureCustodyInTransaction(completedRepair, equipmentNames, claims);
  }

  int materializeUnaccountedFurnitureLocally(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      Map<UUID, Long> equipmentQuantities) {
    require(completedRepair, "Completed repair is required for unaccounted furniture");
    require(equipmentNames, "Furniture equipment names are required");
    require(equipmentQuantities, "Furniture equipment quantities are required");
    if (completedRepair.getFurnitureAccountingMode()
        != FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS) {
      throw conflict("Repair does not use unaccounted furniture accounting");
    }
    if (completedRepair.getAcceptanceState() != RepairAcceptanceState.PENDING) {
      throw conflict("Unaccounted furniture can be proposed only after repair completion");
    }
    UUID rootRepairId = PropertyDispositionRepairChain.rootId(completedRepair);
    int createdCount = 0;
    for (Map.Entry<UUID, Long> line : equipmentQuantities.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
        .toList()) {
      UUID equipmentId = line.getKey();
      Long quantity = line.getValue();
      if (equipmentId == null || quantity == null || quantity < 1) {
        throw conflict("Unaccounted furniture quantity is invalid");
      }
      String equipmentName = Optional.ofNullable(equipmentNames.get(equipmentId))
          .map(String::trim)
          .filter(value -> !value.isEmpty() && value.length() <= 255)
          .orElseThrow(() -> conflict("Furniture equipment name is missing from repair truth"));
      PropertyDispositionDecision existing = decisions
          .findBySourceAndSourceRepairIdAndAssetId(
              PropertyDispositionSource.UNACCOUNTED, completedRepair.getId(), equipmentId)
          .orElse(null);
      if (existing != null) {
        if (!existing.getWarehouseId().equals(completedRepair.getWarehouseId())
            || existing.getAssetKind() != PropertyDispositionAssetKind.EQUIPMENT
            || !existing.getAssetId().equals(equipmentId)
            || !java.util.Objects.equals(existing.getRootRepairId(), rootRepairId)
            || !java.util.Objects.equals(existing.getQuantity(), quantity)
            || existing.getMaintenanceCustodyClaimId() != null
            || existing.getExpectedAssetVersion() != null
            || existing.getExpectedSourceBalanceVersion() != null) {
          throw conflict("Unaccounted furniture is already bound to another disposition");
        }
        continue;
      }
      String reason =
          "Мебель отсутствует в записанном наполнении бытовки; требуется отдельное решение по утрате";
      String requestHash = commands.hash(
          new UnaccountedFurnitureDecisionFingerprint(
              rootRepairId, completedRepair.getId(), equipmentId, quantity, reason));
      PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
          new PropertyDispositionDecisionDraft(
              completedRepair.getWarehouseId(),
              PropertyDispositionAssetKind.EQUIPMENT,
              equipmentId,
              equipmentName,
              PropertyDispositionKind.WRITE_OFF,
              PropertyDispositionSource.UNACCOUNTED,
              null,
              null,
              quantity,
              null,
              null,
              reason,
              null,
              completedRepair.getId(),
              rootRepairId,
              null,
              null,
              null,
              null,
              requestHash,
              actors.systemActorJson(),
              null,
              List.of()));
      created(decision);
      createdCount++;
    }
    return createdCount;
  }

  private int materializeFurnitureCustodyInTransaction(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    UUID rootRepairId = PropertyDispositionRepairChain.rootId(completedRepair);
    MaintenanceRepair rootRepair = completedRepair.getId().equals(rootRepairId)
        ? completedRepair
        : repairChain.requireById(rootRepairId);
    PropertyDispositionSource source = rootRepair.getEstimateId() == null
        ? PropertyDispositionSource.REPAIR
        : PropertyDispositionSource.ESTIMATE;
    int createdCount = 0;
    for (MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim claim : claims) {
      PropertyDispositionDecision existing = decisions.findByMaintenanceCustodyClaimId(claim.id())
          .orElse(null);
      if (existing != null) {
        if (!existing.getWarehouseId().equals(claim.warehouseId())
            || existing.getAssetKind() != PropertyDispositionAssetKind.EQUIPMENT
            || !existing.getAssetId().equals(claim.equipmentId())
            || !java.util.Objects.equals(existing.getRootRepairId(), rootRepairId)
            || existing.getQuantity() == null
            || existing.getQuantity() < 1
            || existing.getQuantity() > claim.unresolvedQuantity()) {
          throw conflict("Furniture custody claim is already bound to another disposition");
        }
        continue;
      }
      if (!completedRepair.getWarehouseId().equals(claim.warehouseId())
          || !completedRepair.getRentalItemId().equals(claim.rentalItemId())
          || claim.availableForDispositionQuantity() < 1) {
        throw conflict("Furniture custody claim is not available for this repair decision");
      }
      String equipmentName = Optional.ofNullable(equipmentNames.get(claim.equipmentId()))
          .map(String::trim)
          .filter(value -> !value.isEmpty() && value.length() <= 255)
          .orElseThrow(
              () -> conflict("Furniture custody equipment name is missing from repair truth"));
      String reason = source == PropertyDispositionSource.ESTIMATE
          ? "Мебель не возвращена на склад после завершения сметы"
          : "Мебель не возвращена на склад после завершения ремонта";
      String requestHash = commands.hash(
          new FurnitureCustodyDecisionFingerprint(
              claim.id(),
              claim.custodyVersion(),
              rootRepairId,
              completedRepair.getId(),
              claim.equipmentId(),
              claim.availableForDispositionQuantity(),
              reason));
      PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
          new PropertyDispositionDecisionDraft(
              claim.warehouseId(),
              PropertyDispositionAssetKind.EQUIPMENT,
              claim.equipmentId(),
              equipmentName,
              PropertyDispositionKind.WRITE_OFF,
              source,
              null,
              null,
              claim.availableForDispositionQuantity(),
              claim.id(),
              claim.custodyVersion(),
              reason,
              null,
              completedRepair.getId(),
              rootRepairId,
              null,
              null,
              null,
              null,
              requestHash,
              actors.systemActorJson(),
              null,
              List.of()));
      created(decision);
      createdCount++;
    }
    return createdCount;
  }

  private CreateResult created(PropertyDispositionDecision decision) {
    PropertyDispositionDecision saved = persistence.persistRequested(decision);
    return new CreateResult(
        readProjection.response(saved, repairChain.repairChain(saved.getRootRepairId())), false);
  }

  private static void validateFurnitureCustodyInput(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    require(completedRepair, "Completed repair is required for furniture custody");
    require(equipmentNames, "Furniture equipment names are required");
    require(claims, "Furniture custody claims are required");
    if (completedRepair.getAcceptanceState() != RepairAcceptanceState.PENDING) {
      throw conflict("Furniture custody can be proposed only after repair completion");
    }
  }

  private static void require(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }

  /** Stable claim-bound fields for a furniture-custody decision hash. */
  private record FurnitureCustodyDecisionFingerprint(
      UUID claimId,
      long custodyVersion,
      UUID rootRepairId,
      UUID completedRepairId,
      UUID equipmentId,
      long quantity,
      String reason) {}

  /** Stable legacy-cabin fields for an unaccounted-furniture decision hash. */
  private record UnaccountedFurnitureDecisionFingerprint(
      UUID rootRepairId,
      UUID completedRepairId,
      UUID equipmentId,
      long quantity,
      String reason) {}
}
