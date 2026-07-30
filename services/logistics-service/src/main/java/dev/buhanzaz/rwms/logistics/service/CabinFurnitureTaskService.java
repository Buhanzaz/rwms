package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureTaskResult;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one worker task from a complete desired cabin composition. The browser never computes
 * physical sources or orchestrates a follow-up task: asset-service returns the fenced plan and
 * this service owns the task command.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CabinFurnitureTaskService {
  private static final int DEFAULT_PLANNED_DURATION_MINUTES = 60;
  private final LogisticsDependencyGateway dependencies;
  private final EquipmentMovementTaskService movementTasks;

  @Transactional
  public CabinFurnitureTaskResult create(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID warehouseId,
      UUID rentalItemId,
      LocalDate scheduledDate,
      List<CabinFurnitureRequirement> requirements) {
    if (actorSubjectId == null
        || idempotencyKey == null
        || warehouseId == null
        || rentalItemId == null
        || scheduledDate == null
        || requirements == null) {
      throw new IllegalArgumentException("Cabin furniture task command is invalid");
    }
    if (scheduledDate.isBefore(OffsetDateTime.now(ZoneOffset.UTC).toLocalDate())) {
      throw new IllegalArgumentException("Cabin furniture task date cannot be in the past");
    }

    LogisticsDependencyGateway.CabinFurnitureMovementPlan plan =
        dependencies.planCabinFurnitureMovements(
            warehouseId,
            rentalItemId,
            requirements.stream()
                .map(
                    requirement ->
                        new LogisticsDependencyGateway.CabinFurnitureRequirement(
                            requirement.equipmentId(), requirement.quantity()))
                .toList());
    validatePlan(plan, warehouseId, rentalItemId);
    if (plan.lines().isEmpty()) {
      return new CabinFurnitureTaskResult(plan.rentalItemId(), plan.unitNumber(), null, 0);
    }

    EquipmentMovementTaskService.CreateResult task =
        movementTasks.create(
            actorSubjectId,
            idempotencyKey,
            new CreateEquipmentMovementTaskRequest(
                warehouseId,
                null,
                plan.unitNumber(),
                DEFAULT_PLANNED_DURATION_MINUTES,
                reservationDeadline(scheduledDate),
                plan.lines().stream().map(CabinFurnitureTaskService::toTaskLine).toList()));
    return new CabinFurnitureTaskResult(
        plan.rentalItemId(), plan.unitNumber(), task.response().id(), plan.lines().size());
  }

  /**
   * Equipment reservations require an instant even when the user schedules by date only. The
   * chosen calendar date is therefore kept as the public value and expires at the exclusive start
   * of the following UTC date; no user-selected time is persisted by this flow.
   */
  private static OffsetDateTime reservationDeadline(LocalDate scheduledDate) {
    return scheduledDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
  }

  private static void validatePlan(
      LogisticsDependencyGateway.CabinFurnitureMovementPlan plan,
      UUID warehouseId,
      UUID rentalItemId) {
    if (plan == null
        || !rentalItemId.equals(plan.rentalItemId())
        || plan.unitNumber() == null
        || plan.unitNumber().isBlank()
        || plan.lines() == null) {
      throw new LogisticsConflictException("Складской сервис вернул некорректный план наполнения");
    }
    for (LogisticsDependencyGateway.CabinFurnitureMovementPlanLine line : plan.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.equipmentName() == null
          || line.equipmentName().isBlank()
          || line.sourceBalanceId() == null
          || !warehouseId.equals(line.sourceWarehouseId())
          || line.sourceLocationKind() == null
          || line.expectedSourceBalanceVersion() < 0
          || !warehouseId.equals(line.targetWarehouseId())
          || line.targetLocationKind() == null
          || line.quantity() < 1
          || !hasMatchingRentalItem(line.sourceLocationKind(), line.sourceRentalItemId())
          || !hasMatchingRentalItem(line.targetLocationKind(), line.targetRentalItemId())) {
        throw new LogisticsConflictException(
            "Складской сервис вернул некорректную строку плана наполнения");
      }
    }
  }

  private static boolean hasMatchingRentalItem(String locationKind, UUID rentalItemId) {
    EquipmentMovementLocationKind location = location(locationKind);
    return (location == EquipmentMovementLocationKind.STOCK) == (rentalItemId == null);
  }

  private static EquipmentMovementLineRequest toTaskLine(
      LogisticsDependencyGateway.CabinFurnitureMovementPlanLine line) {
    return new EquipmentMovementLineRequest(
        line.equipmentId(),
        line.sourceRentalItemId(),
        location(line.sourceLocationKind()),
        line.expectedSourceBalanceVersion(),
        line.targetRentalItemId(),
        location(line.targetLocationKind()),
        line.quantity());
  }

  private static EquipmentMovementLocationKind location(String value) {
    try {
      return EquipmentMovementLocationKind.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new LogisticsConflictException("Складской сервис вернул неизвестный тип размещения мебели");
    }
  }
}
