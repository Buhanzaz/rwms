package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.GroupAvailabilityRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerGroupDto;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkerGroupAvailabilityService {
  private final TaskBoardService taskBoard;
  private final WorkforceService workforce;
  private final GroupKpiEvidenceService kpiEvidence;

  @Transactional
  public WorkerGroupDto disable(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    if (request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("Укажите причину недоступности группы");
    }
    taskBoard.returnActiveWorkForGroup(warehouseId, groupId);
    WorkerGroupDto result = workforce.disableGroupState(warehouseId, groupId, request);
    kpiEvidence.refreshGroup(warehouseId, groupId, OffsetDateTime.now(ZoneOffset.UTC));
    return result;
  }

  @Transactional
  public WorkerGroupDto enable(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    WorkerGroupDto result = workforce.enableGroupState(warehouseId, groupId, request);
    kpiEvidence.refreshGroup(warehouseId, groupId, OffsetDateTime.now(ZoneOffset.UTC));
    return result;
  }
}
