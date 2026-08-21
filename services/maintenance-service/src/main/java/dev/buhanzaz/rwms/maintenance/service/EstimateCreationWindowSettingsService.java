package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.EstimateCreationWindowSettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceEstimateCreationWindowSettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.EstimateCreationWindowSettings;
import dev.buhanzaz.rwms.maintenance.mapper.EstimateCreationWindowSettingsResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.EstimateCreationWindowSettingsRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns reads and optimistic replacements of the warehouse estimate creation window. */
@Service
public class EstimateCreationWindowSettingsService {
  private final EstimateCreationWindowSettingsRepository repository;
  private final EstimateCreationWindowSettingsResponseMapper mapper;

  public EstimateCreationWindowSettingsService(
      EstimateCreationWindowSettingsRepository repository,
      EstimateCreationWindowSettingsResponseMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  /** Returns the persisted setting or the non-persisted seven-day default. */
  @Transactional(readOnly = true)
  public EstimateCreationWindowSettingsResponse get(UUID warehouseId) {
    return repository
        .findById(warehouseId)
        .map(mapper::toResponse)
        .orElseGet(
            () ->
                new EstimateCreationWindowSettingsResponse(
                    warehouseId,
                    0,
                    EstimateCreationWindowSettings.DEFAULT_DAYS,
                    null,
                    null));
  }

  /** Returns only the effective days used by the estimate creation policy. */
  @Transactional(readOnly = true)
  public int effectiveDays(UUID warehouseId) {
    return repository
        .findById(warehouseId)
        .map(EstimateCreationWindowSettings::getDays)
        .orElse(EstimateCreationWindowSettings.DEFAULT_DAYS);
  }

  /** Creates or replaces one setting under the request's optimistic version fence. */
  @Transactional
  public EstimateCreationWindowSettingsResponse replace(
      UUID warehouseId, ReplaceEstimateCreationWindowSettingsRequest request) {
    EstimateCreationWindowSettings settings = repository.findById(warehouseId).orElse(null);
    long expectedVersion = request.expectedVersion();
    if (settings == null) {
      if (expectedVersion != 0) {
        throw versionConflict(warehouseId, expectedVersion, null);
      }
      settings = EstimateCreationWindowSettings.create(warehouseId, request.days());
    } else {
      if (settings.getVersion() != expectedVersion) {
        throw versionConflict(warehouseId, expectedVersion, settings.getVersion());
      }
      settings.replace(request.days());
    }
    return mapper.toResponse(repository.saveAndFlush(settings));
  }

  private static MaintenanceConflictException versionConflict(
      UUID warehouseId, long expectedVersion, Long actualVersion) {
    String actual = actualVersion == null ? "absent" : actualVersion.toString();
    return new MaintenanceConflictException(
        "MAINTENANCE_VERSION_CONFLICT",
        "Estimate creation window for warehouse %s expected version %d but was %s"
            .formatted(warehouseId, expectedVersion, actual));
  }
}
