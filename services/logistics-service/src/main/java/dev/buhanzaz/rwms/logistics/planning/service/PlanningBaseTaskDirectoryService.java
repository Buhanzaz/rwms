package dev.buhanzaz.rwms.logistics.planning.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningBaseTaskResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only projection of existing logistics-owned warehouse movement work. It never creates or
 * claims a task; planners may consider these low-priority candidates only after external work.
 */
@Service
@RequiredArgsConstructor
public class PlanningBaseTaskDirectoryService {
  private static final int MAXIMUM_SUMMARY_LENGTH = 2_000;
  private static final Pattern UNSAFE_SUMMARY_CHARACTERS = Pattern.compile("[\\p{Cntrl}\\p{Cf}]+");
  private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");
  private static final Set<DriverTaskKind> BASE_KINDS =
      Set.of(
          DriverTaskKind.GENERAL_MOVEMENT,
          DriverTaskKind.DELIVER_TO_REPAIR,
          DriverTaskKind.REMOVE_FROM_REPAIR,
          DriverTaskKind.CAPITAL_TO_PRODUCTION,
          DriverTaskKind.TRANSFER);

  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDependencyGateway dependencies;

  /** Returns a bounded, warehouse-isolated candidate list as of one exact instant. */
  @Transactional(readOnly = true)
  public List<PlanningBaseTaskResponse> candidates(
      UUID warehouseId, OffsetDateTime availableAt, int limit) {
    if (warehouseId == null || availableAt == null || limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Base-task query is invalid");
    }
    String timeZone = dependencies.warehouseTimeZoneAt(warehouseId, availableAt).timeZone();
    var availableDate = availableAt.toInstant().atZone(ZoneId.of(timeZone)).toLocalDate();
    return tasks
        .findPlanningBaseTaskCandidates(
            warehouseId,
            availableDate,
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
            BASE_KINDS,
            DriverTaskState.SCHEDULED,
            PageRequest.of(0, limit))
        .stream()
        .map(PlanningBaseTaskDirectoryService::response)
        .toList();
  }

  private static PlanningBaseTaskResponse response(DriverLogisticsTask task) {
    return new PlanningBaseTaskResponse(
        task.getId(),
        task.getExternalTaskId(),
        task.getKind(),
        task.getUnitNumber(),
        summary(task),
        task.getScheduledDate(),
        task.getPriority(),
        task.getState());
  }

  private static String summary(DriverLogisticsTask task) {
    String comment = sanitize(task.getComment());
    if (comment != null) return comment;
    return switch (task.getKind()) {
      case GENERAL_MOVEMENT -> "Перемещение бытовки";
      case DELIVER_TO_REPAIR -> "Перемещение бытовки в ремонт";
      case REMOVE_FROM_REPAIR -> "Вывоз бытовки из ремонта";
      case CAPITAL_TO_PRODUCTION -> "Возврат бытовки из капитального ремонта";
      case TRANSFER -> "Межскладское перемещение";
      case SHIPMENT, RETURN -> throw new IllegalStateException("External task is not base work");
    };
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = UNSAFE_SUMMARY_CHARACTERS.matcher(value).replaceAll(" ");
    normalized = REPEATED_WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    if (normalized.isEmpty()) return null;
    return normalized.length() <= MAXIMUM_SUMMARY_LENGTH
        ? normalized
        : normalized.substring(0, MAXIMUM_SUMMARY_LENGTH).trim();
  }
}
