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
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
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
    return createInternal(actorSubjectId, idempotencyKey, request);
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
    if (request.kind() != DriverTaskKind.DELIVER_TO_REPAIR
        && request.kind() != DriverTaskKind.MOVE_TO_SHIPMENT) {
      throw new IllegalArgumentException(
          "Maintenance intake cannot create removal or capital movement tasks");
    }
    if (request.activateNow()) {
      throw new IllegalArgumentException(
          "Maintenance intake cannot bypass the logistics scheduler");
    }
    return createInternal(MAINTENANCE_ACTOR, idempotencyKey, request);
  }

  @Transactional
  public CreateResult ensureRemovalTask(
      UUID warehouseId, UUID repairId, UUID cabinId) {
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("driver-removal:" + warehouseId + ":" + repairId)
                .getBytes(StandardCharsets.UTF_8));
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
            3,
            false));
  }

  @Transactional
  public CreateResult createCapitalMovement(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId) {
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
            DriverTaskPlanningMode.AUTO,
            null,
            repair.priority(),
            true));
  }

  private CreateResult createInternal(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateDriverTaskRequest request) {
    validateSource(request);
    LogisticsDependencyGateway.WarehouseIdentity warehouse =
        dependencies.readWarehouseIdentity(request.warehouseId());
    if (!warehouse.active()) {
      throw new LogisticsConflictException("Склад неактивен");
    }
    ZoneId zone;
    try {
      zone = ZoneId.of(warehouse.timeZone());
    } catch (RuntimeException exception) {
      throw new LogisticsConflictException("Для склада не настроен часовой пояс");
    }
    LocalDate today = LocalDate.now(zone);
    LocalDate scheduledDate =
        request.planningMode() == DriverTaskPlanningMode.AUTO
            ? today
            : request.scheduledDate();
    if (scheduledDate == null || scheduledDate.isBefore(today)) {
      throw new IllegalArgumentException(
          "Дата логистического задания не может быть в прошлом");
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
          tasks.findBySourceTypeAndSourceIdAndKind(
                  request.sourceType(), request.sourceId(), request.kind())
              .orElse(null);
    }
    if (replay != null) {
      if (!replay.matchesRequest(checksum)) {
        throw new LogisticsConflictException(
            "Источник или Idempotency-Key уже использован для другого логистического задания");
      }
      return new CreateResult(mapper.toResponse(replay), true, request.activateNow());
    }

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
                cabin.number(),
                queue.queueDefinitionId(),
                actorSubjectId,
                idempotencyKey,
                checksum));
    return new CreateResult(mapper.toResponse(task), false, request.activateNow());
  }

  private static void validateSource(CreateDriverTaskRequest request) {
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
            unitNumber,
            queueDefinitionId.toString()));
  }

  public record CreateResult(
      DriverTaskResponse response, boolean replayed, boolean activateNow) {}
}
