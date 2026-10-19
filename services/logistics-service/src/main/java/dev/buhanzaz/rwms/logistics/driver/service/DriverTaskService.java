package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.CreateDriverTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.ExpiredTripNoticeResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.mapper.DriverTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
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

/**
 * Owns driver-task commands and persists their local workflow before processor-driven external
 * effects.
 */
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
  private final DriverTripProjectionService tripProjection;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final LogisticsWarehouseOperationMarkStore warehouseOperationMarks;
  private final LogisticsTransactionLock transactionLock;
  private final CustomerDeliveryCapacityFence capacityFence;
  private final CapitalRepairDriverTaskContentService capitalRepairContent;
  private final DriverTaskWorkerContentCodec workerContentCodec;

  public DriverTaskResponse get(UUID taskId) {
    DriverLogisticsTask task = required(taskId);
    return mapper.toResponse(task, tripProjection.details(taskId));
  }

  public List<DriverTaskResponse> list(UUID warehouseId) {
    return tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId).stream()
        .map(mapper::toResponse)
        .toList();
  }

  /**
   * Returns the latest 50 automatically cancelled trips without loading the full warehouse history.
   */
  public List<DriverTaskResponse> expiredTrips(UUID warehouseId) {
    var recent =
        tasks.findByWarehouseIdAndStateAndTripExpiryRequestedAtIsNotNullOrderByUpdatedAtDescIdDesc(
            warehouseId,
            DriverTaskState.CANCELLED,
            org.springframework.data.domain.PageRequest.of(0, 50));
    var details = tripProjection.boardDetails(recent);
    return recent.stream().map(task -> mapper.toResponse(task, details.get(task.getId()))).toList();
  }

  public DriverLogisticsTask required(UUID taskId) {
    return tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
  }

  /** Operational trip facts only; this feed grants no access to another manager's order. */
  public List<ExpiredTripNoticeResponse> expiredTripsForManager(
      dev.buhanzaz.rwms.logistics.order.security.OrderActor actor) {
    if (!actor.rentalAccess()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Rental manager access required");
    }
    if (actor.readableWarehouses().isEmpty()) return List.of();
    return tasks
        .findByWarehouseIdInAndStateAndTripExpiryRequestedAtIsNotNullOrderByUpdatedAtDescIdDesc(
            actor.readableWarehouses(),
            DriverTaskState.CANCELLED,
            org.springframework.data.domain.PageRequest.of(0, 50))
        .stream()
        .map(mapper::toExpiredTripNotice)
        .toList();
  }

  @Transactional
  public CreateResult create(
      UUID actorSubjectId, UUID idempotencyKey, CreateDriverTaskRequest request) {
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
    return createInternal(actorSubjectId, idempotencyKey, request, admission, false);
  }

  /**
   * Creates scheduled maintenance movement work without bypassing the logistics queue. Ordinary
   * repair delivery and external-capital production movement are the only accepted kinds.
   */
  @Transactional
  public CreateResult createFromMaintenance(UUID idempotencyKey, CreateDriverTaskRequest request) {
    if (idempotencyKey == null || request == null) {
      throw new IllegalArgumentException(
          "Maintenance driver task request and Idempotency-Key are required");
    }
    if (!List.of(
            DriverTaskSourceType.REPAIR,
            DriverTaskSourceType.ESTIMATE,
            DriverTaskSourceType.INVENTORY,
            DriverTaskSourceType.CAPITAL_REPAIR)
        .contains(request.sourceType())) {
      throw new IllegalArgumentException(
          "Maintenance intake requires a repair, estimate, inventory, or capital-repair source");
    }
    if (request.kind() != DriverTaskKind.DELIVER_TO_REPAIR
        && request.kind() != DriverTaskKind.CAPITAL_TO_PRODUCTION) {
      throw new IllegalArgumentException(
          "Maintenance intake supports only repair delivery or capital movement tasks");
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

  /**
   * Persists the same maintenance movement after warehouse admission has been obtained outside the
   * domain decision.
   */
  @Transactional
  public CreateResult createFromMaintenance(
      UUID idempotencyKey, CreateDriverTaskRequest request, AdmissionTicket admission) {
    if (idempotencyKey == null || request == null) {
      throw new IllegalArgumentException(
          "Maintenance driver task request and Idempotency-Key are required");
    }
    if (!List.of(
            DriverTaskSourceType.REPAIR,
            DriverTaskSourceType.ESTIMATE,
            DriverTaskSourceType.INVENTORY,
            DriverTaskSourceType.CAPITAL_REPAIR)
        .contains(request.sourceType())) {
      throw new IllegalArgumentException(
          "Maintenance intake requires a repair, estimate, inventory, or capital-repair source");
    }
    if (request.kind() != DriverTaskKind.DELIVER_TO_REPAIR
        && request.kind() != DriverTaskKind.CAPITAL_TO_PRODUCTION) {
      throw new IllegalArgumentException(
          "Maintenance intake supports only repair delivery or capital movement tasks");
    }
    if (request.activateNow()) {
      throw new IllegalArgumentException(
          "Maintenance intake cannot bypass the logistics scheduler");
    }
    return createInternal(MAINTENANCE_ACTOR, idempotencyKey, request, admission, true);
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
            ("driver-removal:" + warehouseId + ":" + repairId).getBytes(StandardCharsets.UTF_8));
    transactionLock.acquire(
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
                  DriverTaskSourceType.REPAIR_PLACE, repairId, DriverTaskKind.REMOVE_FROM_REPAIR)
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
            List.of(new AdmissionRequirement(warehouseId, WarehouseOperationDirection.OUTGOING)));
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
        admission,
        false);
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
      UUID actorSubjectId, UUID idempotencyKey, UUID warehouseId, UUID repairId) {
    return createCapitalMovement(
        actorSubjectId, idempotencyKey, warehouseId, repairId, DriverTaskPlanningMode.AUTO, null);
  }

  @Transactional
  public CreateResult createCapitalMovement(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate) {
    if (actorSubjectId == null
        || idempotencyKey == null
        || warehouseId == null
        || repairId == null) {
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
            List.of(new AdmissionRequirement(warehouseId, WarehouseOperationDirection.OUTGOING))));
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
        tasks
            .findByCreatedBySubjectIdAndIdempotencyKey(actorSubjectId, idempotencyKey)
            .orElse(null);
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
    LogisticsDependencyGateway.CapitalRepair repair = dependencies.readCapitalRepair(repairId);
    if (repair == null || !warehouseId.equals(repair.warehouseId())) {
      throw new LogisticsConflictException("Капитальный ремонт не принадлежит выбранному складу");
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
        admission,
        false);
  }

  /**
   * Resolves an existing immutable task before applying today's warehouse-local date gate. Replay
   * validates the original fixed-date intent before returning the persisted effective date, while
   * only a fresh create derives AUTO scheduling from a remote admission ticket.
   */
  private CreateResult createInternal(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateDriverTaskRequest request,
      AdmissionTicket admission,
      boolean maintenanceIntake) {
    validateSource(request);
    validatePlanning(request);
    List<AdmissionRequirement> expectedAdmission = admissionRequirements(request);
    if (admission == null || !expectedAdmission.equals(admission.requirements())) {
      throw new LogisticsConflictException(
          "Warehouse admission ticket does not match the driver task");
    }
    transactionLock.acquire(
        "driver-task:create:"
            + request.sourceType()
            + ":"
            + request.sourceId()
            + ":"
            + request.kind());
    DriverLogisticsTask replay =
        tasks
            .findByCreatedBySubjectIdAndIdempotencyKey(actorSubjectId, idempotencyKey)
            .orElse(null);
    if (replay == null) {
      replay =
          tasks
              .findActiveBySourceTypeAndSourceIdAndKind(
                  request.sourceType(), request.sourceId(), request.kind())
              .orElse(null);
    }
    if (replay != null) {
      String replayChecksum =
          checksum(
              request,
              replay.getUnitNumber(),
              replay.getDriverQueueDefinitionId());
      if (!replay.matchesRequest(replayChecksum)) {
        throw new LogisticsConflictException(
            "Источник или Idempotency-Key уже использован для другого логистического задания");
      }
      return new CreateResult(mapper.toResponse(replay), true, request.activateNow());
    }

    LocalDate today = admission.localDate(request.warehouseId());
    LocalDate effectiveScheduledDate =
        effectiveScheduledDate(maintenanceIntake, request, today);
    LocalDate scheduledDate =
        request.planningMode() == DriverTaskPlanningMode.AUTO ? today : effectiveScheduledDate;
    if (scheduledDate.isBefore(today)) {
      throw new IllegalArgumentException("Дата логистического задания не может быть в прошлом");
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
    LogisticsDependencyGateway.CapitalRepair capitalRepair = null;
    if (request.kind() == DriverTaskKind.CAPITAL_TO_PRODUCTION) {
      capitalRepair = dependencies.readCapitalRepair(request.sourceId());
      if (capitalRepair == null
          || !request.sourceId().equals(capitalRepair.repairId())
          || !request.cabinId().equals(capitalRepair.rentalItemId())
          || !request.warehouseId().equals(capitalRepair.warehouseId())) {
        throw new LogisticsConflictException(
            "Капитальный ремонт не принадлежит выбранному складу или бытовке");
      }
    }
    String checksum = checksum(request, cabin.number(), queue.queueDefinitionId());

    warehouseLifecycle.consume(admission);
    capacityFence.acquireTaskDay(request.warehouseId(), scheduledDate, request.kind());

    DriverLogisticsTask task =
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
            checksum);
    if (capitalRepair != null) {
      task.captureWorkerContent(
          workerContentCodec.encode(
              capitalRepairContent.build(
                  capitalRepair, cabin, OffsetDateTime.now(ZoneOffset.UTC))));
    }
    task = tasks.saveAndFlush(task);
    warehouseOperationMarks.enqueue(
        task.getWarehouseId(),
        task.getId(),
        admission.occurredAt(),
        admission.evidenceFor(task.getWarehouseId()).orElse(null));
    return new CreateResult(mapper.toResponse(task), false, request.activateNow());
  }

  /**
   * Normalizes only a fresh overdue fixed-date maintenance intake to today's warehouse date.
   * The request remains fixed-date and its original day stays in the checksum, while the task
   * persists today's effective date and returns it on an exact replay.
   */
  private static LocalDate effectiveScheduledDate(
      boolean maintenanceIntake, CreateDriverTaskRequest request, LocalDate today) {
    if (isOverdueMaintenance(maintenanceIntake, request, today)) {
      return today;
    }
    return request.scheduledDate();
  }

  /** Identifies the private recovery case without weakening the public past-date gate. */
  private static boolean isOverdueMaintenance(
      boolean maintenanceIntake, CreateDriverTaskRequest request, LocalDate today) {
    return maintenanceIntake
        && request.planningMode() == DriverTaskPlanningMode.FIXED_DATE
        && request.scheduledDate().isBefore(today);
  }

  public static List<AdmissionRequirement> admissionRequirements(CreateDriverTaskRequest request) {
    if (request == null || request.warehouseId() == null || request.kind() == null) {
      throw new IllegalArgumentException("Driver task warehouse and kind are required");
    }
    WarehouseOperationDirection direction =
        switch (request.kind()) {
          case REMOVE_FROM_REPAIR, CAPITAL_TO_PRODUCTION -> WarehouseOperationDirection.OUTGOING;
          case DELIVER_TO_REPAIR, GENERAL_MOVEMENT, RETURN -> WarehouseOperationDirection.INCOMING;
          case SHIPMENT, TRANSFER -> WarehouseOperationDirection.OUTGOING;
        };
    return List.of(new AdmissionRequirement(request.warehouseId(), direction));
  }

  public static UUID maintenanceActorId() {
    return MAINTENANCE_ACTOR;
  }

  private static void validateSource(CreateDriverTaskRequest request) {
    if (request.sourceType() == DriverTaskSourceType.LOGISTICS_DOCUMENT
        || request.sourceType() == DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE) {
      throw new IllegalArgumentException(
          "Document-derived driver task sources are reserved for logistics document workflows");
    }
    boolean manualMovement = request.sourceType() == DriverTaskSourceType.MANUAL;
    if (manualMovement != (request.kind() == DriverTaskKind.GENERAL_MOVEMENT)) {
      throw new IllegalArgumentException("MANUAL source is allowed only for GENERAL_MOVEMENT");
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
      throw new IllegalArgumentException("Капитальный ремонт не занимает обычное ремонтное место");
    }
  }

  /** Validates planning shape without applying a time-relative fresh-create decision. */
  private static void validatePlanning(CreateDriverTaskRequest request) {
    if (request.planningMode() == null) {
      throw new IllegalArgumentException("scheduledDate must be set only for FIXED_DATE planning");
    }
    boolean fixedDate = request.planningMode() == DriverTaskPlanningMode.FIXED_DATE;
    if ((fixedDate && request.scheduledDate() == null)
        || (!fixedDate && request.scheduledDate() != null)) {
      throw new IllegalArgumentException("scheduledDate must be set only for FIXED_DATE planning");
    }
  }

  private static String checksum(
      CreateDriverTaskRequest request,
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
                : request.scheduledDate().toString(),
            request.priority().toString(),
            normalizedComment(request.comment()),
            unitNumber,
            queueDefinitionId.toString(),
            request.kind().defaultAudienceMode().name()));
  }

  private static String normalizedComment(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  /** Durable driver-task create result and whether its immediate activation remains required. */
  public record CreateResult(DriverTaskResponse response, boolean replayed, boolean activateNow) {}
}
