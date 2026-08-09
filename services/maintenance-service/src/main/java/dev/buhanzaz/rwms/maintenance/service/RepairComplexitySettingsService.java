package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.ImportRepairComplexitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.api.RepairComplexitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexitySettings;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairComplexitySettingsRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for RepairComplexitySettingsService; it coordinates maintenance-owned state and durable effects. */
@Service
public class RepairComplexitySettingsService {
  private static final List<RepairExecutionState> RECALCULATED_STATES =
      List.of(RepairExecutionState.QUEUED, RepairExecutionState.IN_PROGRESS);

  private final RepairComplexitySettingsRepository repository;
  private final MaintenanceRepairRepository repairs;
  private final MaintenanceReconciliationStore reconciliations;

  public RepairComplexitySettingsService(
      RepairComplexitySettingsRepository repository,
      MaintenanceRepairRepository repairs,
      MaintenanceReconciliationStore reconciliations) {
    this.repository = repository;
    this.repairs = repairs;
    this.reconciliations = reconciliations;
  }

  @Transactional(readOnly = true)
  public RepairComplexitySettingsResponse get(UUID warehouseId) {
    return repository
        .findById(warehouseId)
        .map(RepairComplexitySettingsService::response)
        .orElseGet(() -> defaultResponse(warehouseId));
  }

  @Transactional(readOnly = true)
  public RepairComplexitySettings requireSettings(UUID warehouseId) {
    return repository
        .findById(warehouseId)
        .orElseGet(
            () ->
                RepairComplexitySettings.create(
                    warehouseId,
                    RepairComplexitySettings.DEFAULT_LIGHT_BOUNDARY_MINUTES,
                    RepairComplexitySettings.DEFAULT_MEDIUM_BOUNDARY_MINUTES,
                    RepairComplexitySettings.DEFAULT_COMPLEX_BOUNDARY_MINUTES));
  }

  @Transactional
  public RepairComplexitySettingsResponse replace(
      UUID warehouseId, ReplaceRepairComplexitySettingsRequest request) {
    RepairComplexitySettings settings = repository.findById(warehouseId).orElse(null);
    if (settings == null) {
      if (request.expectedVersion() != 0) {
        throw versionConflict(warehouseId, request.expectedVersion(), null);
      }
      settings =
          RepairComplexitySettings.create(
              warehouseId,
              request.lightBoundaryMinutes(),
              request.mediumBoundaryMinutes(),
              request.complexBoundaryMinutes());
    } else {
      requireVersion(warehouseId, settings, request.expectedVersion());
      settings.replace(
          request.lightBoundaryMinutes(),
          request.mediumBoundaryMinutes(),
          request.complexBoundaryMinutes());
    }
    RepairComplexitySettings saved = repository.saveAndFlush(settings);
    enqueueActiveRecalculations(saved);
    return response(saved);
  }

  @Transactional
  public RepairComplexitySettingsResponse importOnce(
      UUID warehouseId, ImportRepairComplexitySettingsRequest request) {
    RepairComplexitySettings existing = repository.findById(warehouseId).orElse(null);
    if (existing != null) {
      if (existing.getImportedFromTaskBoardVersion() != null
          && existing.getImportedFromTaskBoardVersion().equals(request.taskBoardVersion())
          && existing.getLightBoundaryMinutes() == request.lightBoundaryMinutes()
          && existing.getMediumBoundaryMinutes() == request.mediumBoundaryMinutes()
          && existing.getComplexBoundaryMinutes() == request.complexBoundaryMinutes()) {
        return response(existing);
      }
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair complexity thresholds were already initialized for warehouse "
              + warehouseId);
    }
    if (request.expectedVersion() != 0) {
      throw versionConflict(warehouseId, request.expectedVersion(), null);
    }
    RepairComplexitySettings saved =
        repository.saveAndFlush(
            RepairComplexitySettings.imported(
                warehouseId,
                request.lightBoundaryMinutes(),
                request.mediumBoundaryMinutes(),
                request.complexBoundaryMinutes(),
                request.taskBoardVersion()));
    enqueueActiveRecalculations(saved);
    return response(saved);
  }

  private void enqueueActiveRecalculations(RepairComplexitySettings settings) {
    for (MaintenanceRepair repair :
        repairs.findAllByWarehouseIdAndExecutionStateInOrderByCreatedAtAscIdAsc(
            settings.getWarehouseId(), RECALCULATED_STATES)) {
      UUID key =
          UUID.nameUUIDFromBytes(
              ("repair-complexity-settings:"
                      + settings.getWarehouseId()
                      + ":"
                      + settings.getVersion()
                      + ":"
                      + repair.getId())
                  .getBytes(StandardCharsets.UTF_8));
      reconciliations.enqueue(
          repair.getId(),
          "ASSET",
          "SYNC_REPAIR_COMPLEXITY_STATUS",
          key,
          java.util.Map.of("repairId", repair.getId().toString()));
    }
  }

  private static RepairComplexitySettingsResponse defaultResponse(UUID warehouseId) {
    return new RepairComplexitySettingsResponse(
        warehouseId,
        0,
        RepairComplexitySettings.DEFAULT_LIGHT_BOUNDARY_MINUTES,
        RepairComplexitySettings.DEFAULT_MEDIUM_BOUNDARY_MINUTES,
        RepairComplexitySettings.DEFAULT_COMPLEX_BOUNDARY_MINUTES,
        null,
        null,
        null,
        null);
  }

  private static RepairComplexitySettingsResponse response(RepairComplexitySettings value) {
    return new RepairComplexitySettingsResponse(
        value.getWarehouseId(),
        value.getVersion(),
        value.getLightBoundaryMinutes(),
        value.getMediumBoundaryMinutes(),
        value.getComplexBoundaryMinutes(),
        value.getImportedFromTaskBoardVersion(),
        value.getImportedAt(),
        value.getCreatedAt(),
        value.getUpdatedAt());
  }

  private static void requireVersion(
      UUID warehouseId, RepairComplexitySettings settings, long expectedVersion) {
    if (settings.getVersion() != expectedVersion) {
      throw versionConflict(warehouseId, expectedVersion, settings.getVersion());
    }
  }

  private static MaintenanceConflictException versionConflict(
      UUID warehouseId, long expectedVersion, Long actualVersion) {
    String actual = actualVersion == null ? "absent" : actualVersion.toString();
    return new MaintenanceConflictException(
        "MAINTENANCE_VERSION_CONFLICT",
        "Repair complexity settings for warehouse %s expected version %d but were %s"
            .formatted(warehouseId, expectedVersion, actual));
  }
}
