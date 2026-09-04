package dev.buhanzaz.rwms.logistics.planning.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningProvisionalEtaResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningProvisionalEtaUpdateRequest;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns planner refresh and invalidation of versioned shared future-task ETA previews. */
@Service
@RequiredArgsConstructor
public class PlanningDriverTaskPreviewService {
  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDependencyGateway dependencies;
  private final Clock clock;

  /** Updates one exact task aggregate; an older task or plan fence is rejected without mutation. */
  @Transactional
  public PlanningProvisionalEtaResponse replace(
      UUID externalTaskId, PlanningProvisionalEtaUpdateRequest request) {
    DriverLogisticsTask task =
        tasks
            .findForUpdateByExternalTaskId(externalTaskId)
            .orElseThrow(LogisticsNotFoundException::new);
    try {
      requireFuturePreview(task, request.provisionalEta());
      task.replaceProvisionalEta(
          request.expectedTaskVersion(),
          request.provisionalEta(),
          request.sourcePlanId(),
          request.sourcePlanVersion());
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException(
          "Предварительное время устарело или задание уже назначено водителю");
    }
    tasks.saveAndFlush(task);
    return new PlanningProvisionalEtaResponse(
        task.getId(),
        task.getVersion(),
        task.getExternalTaskId(),
        task.getProvisionalEta(),
        task.getProvisionalEtaSourcePlanId(),
        task.getProvisionalEtaSourcePlanVersion());
  }

  private void requireFuturePreview(DriverLogisticsTask task, OffsetDateTime provisionalEta) {
    if (provisionalEta == null) return;
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    String timeZone = dependencies.warehouseTimeZoneAt(task.getWarehouseId(), now).timeZone();
    ZoneId zoneId = ZoneId.of(timeZone);
    if (task.getScheduledDate() == null
        || !task.getScheduledDate().isAfter(now.toInstant().atZone(zoneId).toLocalDate())
        || !task.getScheduledDate()
            .equals(provisionalEta.toInstant().atZone(zoneId).toLocalDate())) {
      throw new IllegalStateException("Provisional ETA is not a future scheduled-day preview");
    }
  }
}
