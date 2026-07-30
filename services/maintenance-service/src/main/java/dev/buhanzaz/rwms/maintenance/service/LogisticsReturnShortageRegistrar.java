package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.MediaReferenceInput;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.repository.LogisticsReturnShortageRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Uses the source primary key as the concurrent first-write arbiter. */
@Service
@RequiredArgsConstructor
public class LogisticsReturnShortageRegistrar {
  private final LogisticsReturnShortageRepository sources;
  private final MaintenanceApplicationService maintenance;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean register(
      LogisticsReturnShortage candidate,
      LocalDate dispatchDate,
      List<MediaReferenceInput> mediaReferences) {
    if (sources.existsById(candidate.getId())) {
      return false;
    }
    candidate.bindEstimate(
        maintenance.createLogisticsReturnEstimate(
            candidate.getWarehouseId(),
            candidate.getRentalItemId(),
            candidate.getRentalItemVersionSnapshot(),
            dispatchDate,
            mediaReferences));
    sources.saveAndFlush(candidate);
    return true;
  }
}
