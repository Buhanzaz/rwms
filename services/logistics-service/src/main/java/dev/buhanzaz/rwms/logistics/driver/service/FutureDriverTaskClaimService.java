package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskAudience;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lets a qualified DriverApp worker reserve shared future logistics without starting execution.
 * Task-board performs the authoritative worker qualification and optimistic assignment fence;
 * logistics then consumes the echoed audience as its local workflow projection.
 */
@Service
@RequiredArgsConstructor
public class FutureDriverTaskClaimService {
  private final DriverTaskService tasks;
  private final DriverTaskWorkflowStore workflow;
  private final LogisticsDependencyGateway dependencies;

  /** Returns whether a shared task may expose its route preview before a driver claims it. */
  public boolean isPreviewable(DriverLogisticsTask task) {
    return task != null
        && task.getDriverAudienceMode() == DriverTaskAudienceMode.WAREHOUSE_DRIVERS
        && task.getScheduledDate() != null
        && task.getScheduledDate().isAfter(warehouseToday(task.getWarehouseId()));
  }

  /**
   * Claims one shared task for the exact worker. A retry after a lost response observes the same
   * task-board assignment and returns successfully; another worker always receives a conflict.
   */
  public DriverTaskResponse claim(UUID taskId, UUID workerId) {
    if (taskId == null || workerId == null) {
      throw new IllegalArgumentException("Driver task and worker identities are required");
    }
    DriverLogisticsTask local = tasks.required(taskId);
    if (local.getDriverAudienceMode() == DriverTaskAudienceMode.ASSIGNED_DRIVER) {
      if (workerId.equals(local.getPlannedDriverWorkerId())) return tasks.get(taskId);
      throw new LogisticsConflictException("Дополнительное задание уже взял другой водитель");
    }
    if (!isPreviewable(local)) {
      throw new LogisticsConflictException(
          "Водитель может взять только общее логистическое задание на будущий день");
    }
    if (local.getExternalTaskId() == null) {
      throw new LogisticsConflictException("Задание ещё не опубликовано водителям");
    }

    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.readDriverTask(local.getExternalTaskId());
    requireMatchingTask(local, current);
    if (current.driverAudience().mode() == DriverTaskAudienceMode.ASSIGNED_DRIVER) {
      if (!workerId.equals(current.driverAudience().workerId())) {
        throw new LogisticsConflictException("Дополнительное задание уже взял другой водитель");
      }
      workflow.confirmStatus(local.getId(), current);
      return tasks.get(taskId);
    }
    if (current.driverAudience().mode() != DriverTaskAudienceMode.WAREHOUSE_DRIVERS
        || !"ACTIVE".equals(current.status())
        || !"SCHEDULED".equals(current.lane())
        || !"WAITING".equals(current.entryStatus())
        || current.scheduledDate() == null
        || !current.scheduledDate().isAfter(warehouseToday(local.getWarehouseId()))) {
      throw new LogisticsConflictException(
          "Задание уже нельзя взять как дополнительное на будущий день");
    }

    LogisticsDependencyGateway.DriverBoardTask claimed =
        dependencies.moveDriverTask(
            current.externalTaskId(),
            current.taskVersion(),
            current.entryVersion(),
            current.lane(),
            current.scheduledDate(),
            current.queuePosition(),
            new DriverTaskAudience(DriverTaskAudienceMode.ASSIGNED_DRIVER, workerId, null));
    requireMatchingTask(local, claimed);
    if (claimed.driverAudience().mode() != DriverTaskAudienceMode.ASSIGNED_DRIVER
        || !workerId.equals(claimed.driverAudience().workerId())) {
      throw new LogisticsConflictException(
          "Task-board не подтвердил назначение дополнительного задания");
    }
    workflow.confirmStatus(local.getId(), claimed);
    return tasks.get(taskId);
  }

  private void requireMatchingTask(
      DriverLogisticsTask local, LogisticsDependencyGateway.DriverBoardTask board) {
    if (board == null
        || !local.getWarehouseId().equals(board.warehouseId())
        || !local.getExternalTaskId().equals(board.externalTaskId())) {
      throw new LogisticsConflictException("Task-board вернул другое логистическое задание");
    }
  }

  private LocalDate warehouseToday(UUID warehouseId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String timeZone = dependencies.warehouseTimeZoneAt(warehouseId, now).timeZone();
    return now.toInstant().atZone(ZoneId.of(timeZone)).toLocalDate();
  }
}
