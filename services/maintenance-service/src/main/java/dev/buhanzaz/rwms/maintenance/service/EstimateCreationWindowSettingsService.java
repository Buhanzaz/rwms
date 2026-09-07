package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.EstimateCreationWindowSettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceEstimateCreationWindowSettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.EstimateCreationWindowSettings;
import dev.buhanzaz.rwms.maintenance.mapper.EstimateCreationWindowSettingsResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.EstimateCreationWindowSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the global estimate creation window and its optimistic version fence. */
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

  @Transactional(readOnly = true)
  public EstimateCreationWindowSettingsResponse get() {
    return mapper.toResponse(requireSettings());
  }

  /** The same number of days applies in each warehouse's local calendar. */
  @Transactional(readOnly = true)
  public int effectiveDays() {
    return requireSettings().getDays();
  }

  @Transactional
  public EstimateCreationWindowSettingsResponse replace(
      ReplaceEstimateCreationWindowSettingsRequest request) {
    EstimateCreationWindowSettings settings = requireSettings();
    if (settings.getVersion() != request.expectedVersion()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT",
          "Global estimate creation window expected version %d but was %d"
              .formatted(request.expectedVersion(), settings.getVersion()));
    }
    settings.replace(request.days());
    return mapper.toResponse(repository.saveAndFlush(settings));
  }

  private EstimateCreationWindowSettings requireSettings() {
    return repository
        .findById(EstimateCreationWindowSettings.SINGLETON_ID)
        .orElseThrow(() -> new IllegalStateException("Global estimate creation window is missing"));
  }
}
