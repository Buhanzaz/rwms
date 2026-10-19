package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.GroupAvailabilityRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerGroupDto;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates operational availability of a worker group with its active work and KPI evidence.
 *
 * <p>Disabling a group first returns its active work, then changes group availability and refreshes
 * KPI evidence in the same task-board transaction. This prevents an unavailable group from
 * retaining an active assignment.
 */
@Service
@RequiredArgsConstructor
public class WorkerGroupAvailabilityService {
  private final TaskBoardService taskBoard;
  private final WorkforceService workforce;
  private final GroupKpiEvidenceService kpiEvidence;

  /** Disables a group after returning its active work to the operational board. */
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

  /** Enables a group and refreshes its current KPI evidence. */
  @Transactional
  public WorkerGroupDto enable(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    WorkerGroupDto result = workforce.enableGroupState(warehouseId, groupId, request);
    kpiEvidence.refreshGroup(warehouseId, groupId, OffsetDateTime.now(ZoneOffset.UTC));
    return result;
  }
}
