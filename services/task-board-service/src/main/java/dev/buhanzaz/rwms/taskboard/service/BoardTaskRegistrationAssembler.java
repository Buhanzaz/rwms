package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.BoardTaskRegistrationDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.DriverTaskAudienceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisteredRouteStepDto;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps an already loaded task, route, and driver audience to the source-facing registration. */
@Component
class BoardTaskRegistrationAssembler {
  BoardTaskRegistrationDto assemble(
      BoardTask task, List<QueueEntry> route, DriverTaskAudienceDto driverAudience) {
    List<RegisteredRouteStepDto> routeDtos =
        route.stream()
            .map(
                entry ->
                    new RegisteredRouteStepDto(
                        entry.getId(),
                        entry.getVersion(),
                        entry.getQueue().getDefinition().getId(),
                        entry.getQueue().getId(),
                        entry.getQueue().getName(),
                        entry.getRouteIndex(),
                        entry.getQueuePosition(),
                        entry.getEntryType(),
                        entry.getStatus(),
                        entry.getTaskText(),
                        entry.getPlannedDurationMinutes()))
            .toList();
    return new BoardTaskRegistrationDto(
        task.getId(),
        task.getVersion(),
        task.getWarehouseId(),
        task.getExternalTaskId(),
        task.getTitle(),
        task.getUnitNumber(),
        task.getDescription(),
        task.getStatus(),
        task.getPlannedDurationMinutes(),
        task.getDeadlineAt(),
        task.getScheduledDate(),
        task.getLane(),
        task.getPriority(),
        task.isPinned(),
        driverAudience,
        task.getDoneAt(),
        routeDtos);
  }
}
