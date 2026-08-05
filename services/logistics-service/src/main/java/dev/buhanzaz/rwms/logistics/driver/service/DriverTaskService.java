package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.CreateDriverTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.mapper.DriverTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DriverTaskService {
  private static final String CREATE_OPERATION = "CREATE_DRIVER_LOGISTICS_TASK";
  private static final UUID SYSTEM_ACTOR =
      UUID.nameUUIDFromBytes("rwms:logistics-driver-scheduler".getBytes(StandardCharsets.UTF_8));
  private static final UUID MAINTENANCE_ACTOR =
      UUID.nameUUIDFromBytes("rwms:maintenance-service".getBytes(StandardCharsets.UTF_8));

  private final DriverLogisticsTaskRepository tasks;
  private final DriverTaskResponseMapper mapper;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final LogisticsWarehouseOperationMarkStore warehouseOperationMarks;

  public DriverTaskResponse get(UUID taskId) {
    return mapper.toResponse(required(taskId));
  }

  public List<DriverTaskResponse> list(UUID warehouseId) {
    return tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId).stream()
        .map(mapper::toResponse)
        .toList();
  }

  public DriverLogisticsTask required(UUID taskId) {
    return tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
  }

  @Transactional
  public CreateResult create(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateDriverTaskRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException(
          "Driver task actor, request and Idempotency-Key are required");
    }
    return create(
        actorSubjectId,
        idempotencyKey,
        request,
        warehouseLifecycle.disabledTicket(
            actorSubjectId, CREATE_OPERATION, idempotencyKey, admissionRequirements(request)));
  }

  @Transactional
  public CreateResult create(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateDriverTaskRequest request,
      AdmissionTicket admission) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException(
          "Driver task actor, request and Idempotency-Key are required");
    }
    return createInternal(actorSubjectId, idempotencyKey, request, admission);
  }

  @Transactional
  public CreateResult createFromMaintenance(
      UUID idempotencyKey,
      CreateDriverTaskRequest request) {
    if (idempotencyKey == null || request == null) {
      throw new IllegalArgumentException(
          "Maintenance driver task request and Idempotency-Key are required");
    }
    if (!List.of(
            DriverTaskSourceType.REPAIR,
            DriverTaskSourceType.ESTIMATE,
            DriverTaskSourceType.INVENTORY)
        .contains(request.sourceType())) {
      throw new IllegalArgumentException(
          "Maintenance intake requires a repair, estimate, or inventory source");
    }
    if (request.kind() != DriverTaskKind.DELIVER_TO_REPAIR) {
      throw new IllegalArgumentException(
          "Maintenance intake cannot create removal or capital movement tasks");
    }
    if (request.activateNow()) {
      throw new IllegalArgumentException(
          "Maintenance intake cannot bypass the logistics scheduler");
    }
    return createFromMaintenance(
        idempotencyKey,
        request,
        warehouseLifecycle.disabledTicket(
            MAINTENANCE_ACTOR, CREATE_OPERATION, idempotencyKey, admissionRequirements(request)));
  }

  @Transactional
  public CreateResult createFromMaintenance(
      UUID idempotencyKey,
      CreateDriverTaskRequest request,
      AdmissionTicket admission) {
    if (idempotencyKey == null || request == null) {
      throw new IllegalArgumentException(
          "Maintenance driver task request and Idempotency-Key are required");
    }
    if (!List.of(
            DriverTaskSourceType.REPAIR,
            DriverTaskSourceType.ESTIMATE,
            DriverTaskSourceType.INVENTORY)
        .contains(request.sourceType())) {
      throw new IllegalArgumentException(
          "Maintenance intake requires a repair, estimate, or inventory source");
    }
    if (request.kind() != DriverTaskKind.DELIVER_TO_REPAIR) {
      throw new IllegalArgumentException(
          "Maintenance intake cannot create removal or capital movement tasks");
    }
    if (request.activateNow()) {
      throw new IllegalArgumentException(
          "Maintenance intake cannot bypass the logistics scheduler");
    }
    return createInternal(MAINTENANCE_ACTOR, idempotencyKey, request, admission);
  }

  @Transactional
  public CreateResult ensureRemovalTask(
      UUID warehouseId, UUID repairId, UUID cabinId, int priority) {
    if (warehouseId == null || repairId == null || cabinId == null) {
      throw new IllegalArgumentException("Removal task warehouse, repair and cabin are required");
    }
    if (priority < 1 || priority > 5) {
      throw new IllegalArgumentException("Removal task priority must be between 1 and 5");
    }
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("driver-removal:" + warehouseId + ":" + repairId)
                .getBytes(StandardCharsets.UTF_8));
    tasks.acquireTransactionLock(
        "driver-task:create:"
            + DriverTaskSourceType.REPAIR_PLACE
            + ":"
            + repairId
            + ":"
            + DriverTaskKind.REMOVE_FROM_REPAIR);
    DriverLogisticsTask existing =
        tasks.findByCreatedBySubjectIdAndIdempotencyKey(SYSTEM_ACTOR, idempotencyKey).orElse(null);
    if (existing == null) {
      existing =
          tasks
              .findActiveBySourceTypeAndSourceIdAndKind(
                  DriverTaskSourceType.REPAIR_PLACE,
                  repairId,
                  DriverTaskKind.REMOVE_FROM_REPAIR)
              .orElse(null);
    }
    if (existing != null) {
      requireMatchingReleaseContext(existing, warehouseId, repairId, cabinId);
      return new CreateResult(mapper.toResponse(existing), true, false);
    }
    AdmissionTicket admission =
        warehouseLifecycle.ownedContinuation(
            repairId,
            idempotencyKey,
            List.of(
                new AdmissionRequirement(
                    warehouseId, WarehouseOperationDirection.OUTGOING)));
    return createInternal(
        SYSTEM_ACTOR,
        idempotencyKey,
        new CreateDriverTaskRequest(
            warehouseId,
            cabinId,
            repairId,
            DriverTaskSourceType.REPAIR_PLACE,
            repairId,
            DriverTaskKind.REMOVE_FROM_REPAIR,
            DriverTaskPlanningMode.AUTO,
            null,
            priority,
            false,
            null),
        admission);
  }

  private static void requireMatchingReleaseContext(
      DriverLogisticsTask task, UUID warehouseId, UUID repairId, UUID cabinId) {
    if (task.getSourceType() != DriverTaskSourceType.REPAIR_PLACE
        || task.getKind() != DriverTaskKind.REMOVE_FROM_REPAIR
        || !warehouseId.equals(task.getWarehouseId())
        || !repairId.equals(task.getSourceId())
        || !repairId.equals(task.getRepairId())
        || !cabinId.equals(task.getCabinId())) {
      throw new LogisticsConflictException(
          "Существующее задание на перемещение с ремонта не совпадает с ремонтным местом");
    }
  }

  @Transactional
  public CreateResult createCapitalMovement(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId) {
    return createCapitalMovement(
        actorSubjectId,
        idempotencyKey,
        warehouseId,
        repairId,
        DriverTaskPlanningMode.AUTO,
        null);
  }

  @Transactional
  public CreateResult createCapitalMovement(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate) {
    if (actorSubjectId == null || idempotencyKey == null || warehouseId == null || repairId == null) {
      throw new IllegalArgumentException("Capital movement identity is required");
    }
    return createCapitalMovement(
        actorSubjectId,
        idempotencyKey,
        warehouseId,
        repairId,
        planningMode,
        scheduledDate,
        warehouseLifecycle.disabledTicket(
            actorSubjectId,
            CREATE_OPERATION,
            idempotencyKey,
            List.of(
                new AdmissionRequirement(
                    warehouseId, WarehouseOperationDirection.OUTGOING))));
  }

  @Transactional
  public CreateResult createCapitalMovement(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate,
      AdmissionTicket admission) {
    if (planningMode == null) {
      throw new IllegalArgumentException("Capital movement planning mode is required");
    }
    if (planningMode == DriverTaskPlanningMode.FIXED_DATE && scheduledDate == null) {
      throw new IllegalArgumentException(
          "Capital movement scheduled date is required for FIXED_DATE planning");
    }
    DriverLogisticsTask replay =
        tasks.findByCreatedBySubjectIdAndIdempotencyKey(actorSubjectId, idempotencyKey).orElse(null);
    if (replay != null) {
      if (!warehouseId.equals(replay.getWarehouseId())
          || replay.getSourceType() != DriverTaskSourceType.CAPITAL_REPAIR
          || !repairId.equals(replay.getSourceId())
          || replay.getKind() != DriverTaskKind.CAPITAL_TO_PRODUCTION
          || replay.getPlanningMode() != planningMode
          || (planningMode == DriverTaskPlanningMode.FIXED_DATE
              && !scheduledDate.equals(replay.getFixedDateLowerBound()))) {
        throw new LogisticsConflictException(
            "Idempotency-Key is already used for another capital movement");
      }
      return new CreateResult(mapper.toResponse(replay), true, true);
    }
    LogisticsDependencyGateway.CapitalRepair repair =
        dependencies.readCapitalRepair(repairId);
    if (!warehouseId.equals(repair.warehouseId())) {
      throw new LogisticsConflictException(
          "Капитальный ремонт не принадлежит выбранному складу");
    }
    return createInternal(
        actorSubjectId,
        idempotencyKey,
        new CreateDriverTaskRequest(
            warehouseId,
            repair.rentalItemId(),
            repair.repairId(),
            DriverTaskSourceType.CAPITAL_REPAIR,
            repair.repairId(),
            DriverTaskKind.CAPITAL_TO_PRODUCTION,
            planningMode,
            scheduledDate,
            repair.priority(),
            true,
            null),
        admission);
  }

  private CreateResult createInternal(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateDriverTaskRequest request,
      AdmissionTicket admission) {
    validateSource(request);
    List<AdmissionRequirement> expectedAdmission = admissionRequirements(request);
    if (admission == null || !expectedAdmission.equals(admission.requirements())) {
      throw new LogisticsConflictException(
          "Warehouse admission ticket does not match the driver task");
    }
    LocalDate today = admission.localDate(request.warehouseId());
    LocalDate scheduledDate =
        request.planningMode() == DriverTaskPlanningMode.AUTO
            ? today
            : request.scheduledDate();
    if (scheduledDate == null || scheduledDate.isBefore(today)) {
      throw new IllegalArgumentException(
          "Дата логистического задания не может быть в прошлом");
    }

    tasks.acquireTransactionLock(
        "driver-task:create:"
            + request.sourceType()
            + ":"
            + request.sourceId()
            + ":"
            + request.kind());
    DriverLogisticsTask replay =
        tasks.findByCreatedBySubjectIdAndIdempotencyKey(actorSubjectId, idempotencyKey)
            .orElse(null);
    if (replay == null) {
      replay =
          tasks.findActiveBySourceTypeAndSourceIdAndKind(
                  request.sourceType(), request.sourceId(), request.kind())
              .orElse(null);
    }
    if (replay != null) {
      String replayChecksum =
          checksum(
              request,
              scheduledDate,
              replay.getUnitNumber(),
              replay.getDriverQueueDefinitionId());
      if (!replay.matchesRequest(replayChecksum)) {
        throw new LogisticsConflictException(
            "Источник или Idempotency-Key уже использован для другого логистического задания");
      }
      return new CreateResult(mapper.toResponse(replay), true, request.activateNow());
    }

    LogisticsDependencyGateway.WarehouseDriverQueue queue =
        dependencies.readWarehouseDriverQueue(request.warehouseId());
    LogisticsDependencyGateway.RentalItemSnapshot cabin =
        dependencies.readRentalItemSnapshot(request.cabinId());
    if (!request.cabinId().equals(cabin.assetId())
        || !request.warehouseId().equals(cabin.warehouseId())
        || cabin.number() == null
        || cabin.number().isBlank()) {
      throw new LogisticsConflictException(
          "Бытовка не принадлежит выбранному складу или не имеет номера");
    }
    String checksum = checksum(request, scheduledDate, cabin.number(), queue.queueDefinitionId());

    warehouseLifecycle.consume(admission);

    DriverLogisticsTask task =
        tasks.saveAndFlush(
            DriverLogisticsTask.create(
                request.warehouseId(),
                request.cabinId(),
                request.repairId(),
                request.sourceType(),
                request.sourceId(),
                request.kind(),
                request.planningMode(),
                scheduledDate,
                request.priority(),
                request.comment(),
                cabin.number(),
                queue.queueDefinitionId(),
                actorSubjectId,
                idempotencyKey,
                checksum));
    warehouseOperationMarks.enqueue(
        task.getWarehouseId(), task.getId(), admission.occurredAt());
    return new CreateResult(mapper.toResponse(task), false, request.activateNow());
  }

  public static List<AdmissionRequirement> admissionRequirements(
      CreateDriverTaskRequest request) {
    if (request == null || request.warehouseId() == null || request.kind() == null) {
      throw new IllegalArgumentException("Driver task warehouse and kind are required");
    }
    WarehouseOperationDirection direction =
        switch (request.kind()) {
          case REMOVE_FROM_REPAIR, CAPITAL_TO_PRODUCTION ->
              WarehouseOperationDirection.OUTGOING;
          case DELIVER_TO_REPAIR, GENERAL_MOVEMENT -> WarehouseOperationDirection.INCOMING;
        };
    return List.of(new AdmissionRequirement(request.warehouseId(), direction));
  }

  public static UUID maintenanceActorId() {
    return MAINTENANCE_ACTOR;
  }

  private static void validateSource(CreateDriverTaskRequest request) {
    boolean manualMovement = request.sourceType() == DriverTaskSourceType.MANUAL;
    if (manualMovement != (request.kind() == DriverTaskKind.GENERAL_MOVEMENT)) {
      throw new IllegalArgumentException(
          "MANUAL source is allowed only for GENERAL_MOVEMENT");
    }
    if (manualMovement && (request.comment() == null || request.comment().isBlank())) {
      throw new IllegalArgumentException("Manual movement comment is required");
    }
    if (request.kind() == DriverTaskKind.CAPITAL_TO_PRODUCTION
        && request.sourceType() != DriverTaskSourceType.CAPITAL_REPAIR) {
      throw new IllegalArgumentException(
          "Капитальное перемещение должно ссылаться на капитальный ремонт");
    }
    if (request.kind() == DriverTaskKind.REMOVE_FROM_REPAIR
        && request.sourceType() != DriverTaskSourceType.REPAIR_PLACE) {
      throw new IllegalArgumentException(
          "Вывоз после ремонта создаётся из фактического ремонтного места");
    }
    if (request.kind() == DriverTaskKind.DELIVER_TO_REPAIR
        && request.sourceType() == DriverTaskSourceType.CAPITAL_REPAIR) {
      throw new IllegalArgumentException(
          "Капитальный ремонт не занимает обычное ремонтное место");
    }
  }

  private static String checksum(
      CreateDriverTaskRequest request,
      LocalDate scheduledDate,
      String unitNumber,
      UUID queueDefinitionId) {
    return DriverTaskChecksum.sha256(
        CREATE_OPERATION,
        java.util.Arrays.asList(
            request.warehouseId().toString(),
            request.cabinId().toString(),
            request.repairId() == null ? null : request.repairId().toString(),
            request.sourceType().name(),
            request.sourceId().toString(),
            request.kind().name(),
            request.planningMode().name(),
            request.planningMode() == DriverTaskPlanningMode.AUTO
                ? "<auto>"
                : scheduledDate.toString(),
            request.priority().toString(),
            normalizedComment(request.comment()),
            unitNumber,
            queueDefinitionId.toString()));
  }

  private static String normalizedComment(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  public record CreateResult(
      DriverTaskResponse response, boolean replayed, boolean activateNow) {}
}
