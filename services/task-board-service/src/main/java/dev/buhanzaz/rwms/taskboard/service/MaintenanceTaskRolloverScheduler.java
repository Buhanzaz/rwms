package dev.buhanzaz.rwms.taskboard.service;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "rwms.task-board.rollover",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MaintenanceTaskRolloverScheduler {
  private final TaskBoardService taskBoard;
  private final ZoneId scheduleZone;

  public MaintenanceTaskRolloverScheduler(
      TaskBoardService taskBoard,
      @Value("${rwms.task-board.rollover.zone:Europe/Moscow}") String scheduleZone) {
    this.taskBoard = taskBoard;
    this.scheduleZone = ZoneId.of(scheduleZone);
  }

  @Scheduled(
      fixedDelayString = "${rwms.task-board.rollover.poll-delay:PT1M}",
      initialDelayString = "${rwms.task-board.rollover.initial-delay:PT30S}")
  public void rollover() {
    taskBoard.rolloverOverdueMaintenanceTasks(LocalDate.now(scheduleZone));
  }
}
