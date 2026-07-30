package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.RepairComplexityColorsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexityColorsRequest;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexityColors;
import dev.buhanzaz.rwms.maintenance.repository.RepairComplexityColorsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepairComplexityColorsService {
  private final RepairComplexityColorsRepository repository;

  public RepairComplexityColorsService(RepairComplexityColorsRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public RepairComplexityColorsResponse get() {
    return response(
        repository
            .findById(RepairComplexityColors.SINGLETON_ID)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Repair complexity color settings are missing")));
  }

  @Transactional(readOnly = true)
  public RepairComplexityColors requireColors() {
    return repository
        .findById(RepairComplexityColors.SINGLETON_ID)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Repair complexity color settings are missing"));
  }

  @Transactional
  public RepairComplexityColorsResponse replace(ReplaceRepairComplexityColorsRequest request) {
    RepairComplexityColors value =
        repository
            .findById(RepairComplexityColors.SINGLETON_ID)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Repair complexity color settings are missing"));
    if (value.getVersion() != request.expectedVersion()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT",
          "Repair complexity colors expected version %d but are %d"
              .formatted(request.expectedVersion(), value.getVersion()));
    }
    value.replace(
        request.lightColor(),
        request.mediumColor(),
        request.complexColor(),
        request.capitalColor());
    return response(repository.saveAndFlush(value));
  }

  private static RepairComplexityColorsResponse response(RepairComplexityColors value) {
    return new RepairComplexityColorsResponse(
        value.getVersion(),
        value.getLightColor(),
        value.getMediumColor(),
        value.getComplexColor(),
        value.getCapitalColor(),
        value.getUpdatedAt());
  }
}
