package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.RepairCapacitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairCapacitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.RepairCapacitySettings;
import dev.buhanzaz.rwms.maintenance.mapper.RepairCapacitySettingsResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.RepairCapacitySettingsRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for RepairCapacitySettingsService; it coordinates maintenance-owned state and durable effects. */
@Service
public class RepairCapacitySettingsService {
  private final RepairCapacitySettingsRepository repository;
  private final RepairCapacitySettingsResponseMapper mapper;

  public RepairCapacitySettingsService(
      RepairCapacitySettingsRepository repository,
      RepairCapacitySettingsResponseMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public RepairCapacitySettingsResponse get(UUID warehouseId) {
    return repository.findById(warehouseId)
        .map(mapper::toResponse)
        .orElseGet(() -> new RepairCapacitySettingsResponse(
            warehouseId,
            0,
            RepairCapacitySettings.DEFAULT_REPAIR_PLACE_COUNT,
            RepairCapacitySettings.DEFAULT_AUTOMATIC_REFILL_DELAY_MINUTES,
            null,
            null));
  }

  @Transactional
  public RepairCapacitySettingsResponse replace(
      UUID warehouseId, ReplaceRepairCapacitySettingsRequest request) {
    RepairCapacitySettings settings = repository.findById(warehouseId).orElse(null);
    long expectedVersion = request.expectedVersion();
    if (settings == null) {
      if (expectedVersion != 0) {
        throw versionConflict(warehouseId, expectedVersion, null);
      }
      settings = RepairCapacitySettings.create(
          warehouseId,
          request.repairPlaceCount(),
          request.automaticRefillDelayMinutes());
    } else {
      if (settings.getVersion() != expectedVersion) {
        throw versionConflict(warehouseId, expectedVersion, settings.getVersion());
      }
      settings.replace(
          request.repairPlaceCount(),
          request.automaticRefillDelayMinutes());
    }
    return mapper.toResponse(repository.saveAndFlush(settings));
  }

  private static MaintenanceConflictException versionConflict(
      UUID warehouseId, long expectedVersion, Long actualVersion) {
    String actual = actualVersion == null ? "absent" : actualVersion.toString();
    return new MaintenanceConflictException(
        "MAINTENANCE_VERSION_CONFLICT",
        "Repair-place settings for warehouse %s expected version %d but were %s"
            .formatted(warehouseId, expectedVersion, actual));
  }
}
