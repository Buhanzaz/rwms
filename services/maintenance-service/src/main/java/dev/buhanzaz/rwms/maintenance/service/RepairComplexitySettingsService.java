package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.RepairComplexitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexitySettings;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.mapper.RepairComplexitySettingsResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairComplexitySettingsRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns global complexity thresholds and durable recalculation of active repairs in all warehouses.
 */
@Service
public class RepairComplexitySettingsService {
  private static final List<RepairExecutionState> RECALCULATED_STATES =
      List.of(RepairExecutionState.QUEUED, RepairExecutionState.IN_PROGRESS);

  private final RepairComplexitySettingsRepository repository;
  private final MaintenanceRepairRepository repairs;
  private final MaintenanceReconciliationStore reconciliations;
  private final RepairComplexitySettingsResponseMapper mapper;

  public RepairComplexitySettingsService(
      RepairComplexitySettingsRepository repository,
      MaintenanceRepairRepository repairs,
      MaintenanceReconciliationStore reconciliations,
      RepairComplexitySettingsResponseMapper mapper) {
    this.repository = repository;
    this.repairs = repairs;
    this.reconciliations = reconciliations;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public RepairComplexitySettingsResponse get() {
    return mapper.toResponse(requireSettings());
  }

  @Transactional(readOnly = true)
  public RepairComplexitySettings requireSettings() {
    return repository
        .findById(RepairComplexitySettings.SINGLETON_ID)
        .orElseThrow(
            () -> new IllegalStateException("Global repair complexity settings are missing"));
  }

  /** Saves the global version and all recalculation commands in the same transaction. */
  @Transactional
  public RepairComplexitySettingsResponse replace(ReplaceRepairComplexitySettingsRequest request) {
    RepairComplexitySettings settings = requireSettings();
    if (settings.getVersion() != request.expectedVersion()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT",
          "Global repair complexity settings expected version %d but were %d"
              .formatted(request.expectedVersion(), settings.getVersion()));
    }
    settings.replace(
        request.lightBoundaryMinutes(),
        request.mediumBoundaryMinutes(),
        request.complexBoundaryMinutes());
    RepairComplexitySettings saved = repository.saveAndFlush(settings);
    for (MaintenanceRepair repair :
        repairs.findAllByExecutionStateInOrderByCreatedAtAscIdAsc(RECALCULATED_STATES)) {
      UUID key =
          UUID.nameUUIDFromBytes(
              ("global-repair-complexity-settings:" + saved.getVersion() + ":" + repair.getId())
                  .getBytes(StandardCharsets.UTF_8));
      reconciliations.enqueue(
          repair.getId(),
          "ASSET",
          "SYNC_REPAIR_COMPLEXITY_STATUS",
          key,
          java.util.Map.of("repairId", repair.getId().toString()));
    }
    return mapper.toResponse(saved);
  }
}
