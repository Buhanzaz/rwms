package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskAudience;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns creation, pre-start replanning, and cancellation of document-derived driver trips. Every new
 * shipment, return, or transfer receives one grouped task with durable ordered cabin members.
 * Waiting legacy line tasks are cancelled before grouping; any started legacy task fences the
 * conversion, and no parallel line-task creation path remains. Document lifecycle stays in the
 * document coordinators. A local intent precedes relay registration; registered changes use
 * task-board's version-fenced pre-start commands and reconcile lost responses from the same task.
 */
@Service
@RequiredArgsConstructor
public class DocumentDriverTaskPlanner {
  private static final String PLAN_OPERATION = "PLAN_DOCUMENT_DRIVER_TASK";
  private static final int DEFAULT_PRIORITY = 3;

  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDependencyGateway dependencies;
  private final DriverTaskWorkflowStore workflowStore;
  private final ShipmentTaskSettingsService shipmentTaskSettings;
  private final CustomerDeliveryCapacityFence capacityFence;
  private final DriverTaskWorkerContentCodec workerContentCodec;

  /** Creates or idempotently replans every driver task required by a scheduled document. */
  @Transactional
  public void plan(LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    plan(document, lines, DriverTaskWorkerContent.empty());
  }

  /**
   * Creates or replans a document task with an immutable structured payload for the existing
   * WorkerApp offline route.
   */
  @Transactional
  public void plan(
      LogisticsDocument document,
      List<LogisticsDocumentLine> lines,
      DriverTaskWorkerContent workerContent) {
    requirePlan(document, lines);
    String workerContentJson = workerContentCodec.encode(workerContent);
    DriverTaskKind kind = kind(document.getDocumentType());
    UUID taskWarehouseId = taskWarehouse(document, lines);
    DriverTaskAudience audience = audience(document);
    LogisticsDependencyGateway.WarehouseDriverQueue queue =
        dependencies.readWarehouseDriverQueue(taskWarehouseId);
    shipmentTaskSettings.requireWithinLimit(
        taskWarehouseId, lines.size(), document.getRequestedBySubjectId());
    capacityFence.acquireTaskDay(taskWarehouseId, document.getScheduledDate(), kind);
    cancelLegacyLineTasksBeforeGrouping(document, lines, taskWarehouseId);
    planGroupedDocument(
        document, lines, taskWarehouseId, kind, audience, queue, workerContent, workerContentJson);
  }

  /**
   * Creates or idempotently replans the ordinary document-owned driver task for a confirmed
   * transfer containing only loose furniture. The supplied summary is the frozen cargo snapshot
   * shown through the same queue and WorkerApp task path as cabin transfers; this method neither
   * invents a cabin nor crosses the asset boundary.
   */
  @Transactional
  public void planTransferCargo(LogisticsDocument document, String cargoSummary) {
    planTransferCargo(document, cargoSummary, DriverTaskWorkerContent.empty());
  }

  /** Creates or replans a furniture-only transfer with its exact WorkerApp cargo manifest. */
  @Transactional
  public void planTransferCargo(
      LogisticsDocument document,
      String cargoSummary,
      DriverTaskWorkerContent workerContent) {
    requireTransferCargoPlan(document);
    String normalizedSummary = normalizedCargoSummary(cargoSummary);
    String workerContentJson = workerContentCodec.encode(workerContent);
    DriverTaskKind kind = DriverTaskKind.TRANSFER;
    DriverTaskAudience audience = audience(document);
    LogisticsDependencyGateway.WarehouseDriverQueue queue =
        dependencies.readWarehouseDriverQueue(document.getWarehouseId());
    capacityFence.acquireTaskDay(document.getWarehouseId(), document.getScheduledDate(), kind);

    DriverLogisticsTask existing =
        tasks
            .findActiveForUpdateBySourceTypeAndSourceIdAndKind(
                DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), kind)
            .orElse(null);
    String checksum =
        furnitureCargoChecksum(
            document, normalizedSummary, audience, queue, workerContentJson);
    if (existing == null) {
      DriverLogisticsTask created =
          DriverLogisticsTask.createFurnitureCargoTransfer(
              document.getWarehouseId(),
              document.getId(),
              document.getScheduledDate(),
              DEFAULT_PRIORITY,
              furnitureCargoTaskText(normalizedSummary),
              normalizedSummary,
              queue.queueDefinitionId(),
              audience.mode(),
              audience.workerId(),
              audience.workerName(),
              document.getRequestedBySubjectId(),
              idempotencyKey(document.getId(), kind, checksum),
              checksum);
      created.captureWorkerContent(workerContentJson);
      tasks.saveAndFlush(created);
      return;
    }
    requireSameFurnitureCargoIntent(document, normalizedSummary, queue, existing);
    if (existing.matchesRequest(checksum)) return;
    replanRegisteredTask(
        document, existing, audience, workerContent, workerContentJson, checksum);
  }

  /**
   * Cancels a grouped document task or prior document-line tasks only while task-board still
   * confirms that execution has not begun. A partially observed remote cancellation is safe to
   * retry because each local intent is retained and task-board exposes an explicit
   * already-cancelled outcome.
   */
  @Transactional
  public void cancelBeforeStart(LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    if (document == null
        || document.getId() == null
        || lines == null
        || lines.stream().anyMatch(line -> line == null || line.getId() == null)
        || (lines.isEmpty() && document.getDocumentType() != LogisticsDocumentType.TRANSFER)) {
      throw new IllegalArgumentException(
          "Document and persisted lines are required for cancellation");
    }
    List<UUID> sourceIds = lines.stream().map(LogisticsDocumentLine::getId).toList();
    List<DriverLogisticsTask> documentTasks = new ArrayList<>();
    documentTasks.addAll(
        tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(document.getId())));
    if (!sourceIds.isEmpty()) {
      documentTasks.addAll(
          tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
              DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE, sourceIds));
    }
    for (DriverLogisticsTask task : documentTasks) {
      cancelTask(document, task, taskWarehouse(document, lines));
    }
  }

  private void planGroupedDocument(
      LogisticsDocument document,
      List<LogisticsDocumentLine> lines,
      UUID taskWarehouseId,
      DriverTaskKind kind,
      DriverTaskAudience audience,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      DriverTaskWorkerContent workerContent,
      String workerContentJson) {
    DriverLogisticsTask existing =
        tasks
            .findActiveForUpdateBySourceTypeAndSourceIdAndKind(
                DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), kind)
            .orElse(null);
    List<DocumentMember> members = documentMembers(document, lines);
    String checksum =
        groupedChecksum(
            document,
            taskWarehouseId,
            kind,
            members,
            audience,
            queue,
            workerContentJson);
    if (existing == null) {
      DriverLogisticsTask created =
          DriverLogisticsTask.createGroupedDocument(
              taskWarehouseId,
              members.getFirst().cabinId(),
              document.getId(),
              kind,
              document.getScheduledDate(),
              tripNumber(document),
              DEFAULT_PRIORITY,
              groupedTaskText(document, members),
              clientSnapshot(document),
              groupedUnitSummary(members.size()),
              queue.queueDefinitionId(),
              audience.mode(),
              audience.workerId(),
              audience.workerName(),
              document.getRequestedBySubjectId(),
              idempotencyKey(document.getId(), kind, checksum),
              checksum);
      for (DocumentMember member : members) {
        created.addGroupedDocumentMember(
            member.lineId(), member.cabinId(), member.unitNumber(), member.position());
      }
      created.captureWorkerContent(workerContentJson);
      tasks.saveAndFlush(created);
      return;
    }
    requireSameGroupedDocumentIntent(
        document, taskWarehouseId, kind, members, queue, existing);
    if (existing.matchesRequest(checksum)) return;
    replanRegisteredTask(
        document, existing, audience, workerContent, workerContentJson, checksum);
  }

  /**
   * Converges pre-existing line tasks to the single grouped-trip representation. Every remote
   * member is checked before the first cancellation command, so an already-started legacy task
   * prevents regrouping without mutating its siblings. New line tasks are never created.
   */
  private void cancelLegacyLineTasksBeforeGrouping(
      LogisticsDocument document,
      List<LogisticsDocumentLine> lines,
      UUID taskWarehouseId) {
    List<DriverLogisticsTask> legacyTasks =
        tasks
            .findAllForUpdateBySourceTypeAndSourceIdIn(
                DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
                lines.stream().map(LogisticsDocumentLine::getId).toList())
            .stream()
            .filter(task -> task.getState() != DriverTaskState.CANCELLED)
            .toList();
    if (legacyTasks.isEmpty()) return;

    List<LegacyLineCancellation> cancellations = new ArrayList<>(legacyTasks.size());
    for (DriverLogisticsTask task : legacyTasks) {
      if (!taskWarehouseId.equals(task.getWarehouseId())) {
        throw new LogisticsConflictException("Старое задание водителя принадлежит другому складу");
      }
      if (task.getState() == DriverTaskState.REGISTERING && task.getTaskBoardTaskId() == null) {
        cancellations.add(new LegacyLineCancellation(task, null));
        continue;
      }
      if (task.getState() == DriverTaskState.COMPLETED
          || task.getState() == DriverTaskState.FINALIZING) {
        throw legacyStarted();
      }
      LogisticsDependencyGateway.DriverBoardTask current =
          dependencies.readDriverTask(task.getExternalTaskId());
      if (!"CANCELLED".equals(current.status())
          && (!("ACTIVE".equals(current.status())
              && "WAITING".equals(current.entryStatus())
              && ("SCHEDULED".equals(current.lane()) || "CURRENT".equals(current.lane()))))) {
        throw legacyStarted();
      }
      cancellations.add(new LegacyLineCancellation(task, current));
    }

    for (LegacyLineCancellation cancellation : cancellations) {
      DriverLogisticsTask task = cancellation.task();
      LogisticsDependencyGateway.DriverBoardTask current = cancellation.current();
      if (current == null) {
        task.cancelBeforeExternalRegistration();
      } else if ("CANCELLED".equals(current.status())) {
        task.cancelAfterPreStartCancellation();
      } else {
        LogisticsDependencyGateway.DriverTaskPreStartCancellation outcome =
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(),
                current.taskVersion(),
                "Бытовки объединены в одну водительскую ходку");
        if (outcome.outcome() != DriverTaskPreStartCancellationOutcome.CANCELLED
            && outcome.outcome() != DriverTaskPreStartCancellationOutcome.ALREADY_CANCELLED) {
          throw legacyStarted();
        }
        task.cancelAfterPreStartCancellation();
      }
      tasks.saveAndFlush(task);
    }
  }

  private List<DocumentMember> documentMembers(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    List<DocumentMember> members = new ArrayList<>(lines.size());
    for (LogisticsDocumentLine line :
        lines.stream()
            .sorted(Comparator.comparingInt(LogisticsDocumentLine::getLineNumber))
            .toList()) {
      LogisticsDependencyGateway.RentalItemSnapshot cabin =
          dependencies.readRentalItemSnapshot(line.getAssetId());
      requireCabin(document, line, cabin);
      members.add(
          new DocumentMember(
              line.getId(), line.getAssetId(), cabin.number(), line.getLineNumber()));
    }
    if (members.isEmpty()) {
      throw new IllegalArgumentException("Grouped document requires at least one cabin member");
    }
    return List.copyOf(members);
  }

  private static String clientSnapshot(LogisticsDocument document) {
    String snapshot = document.getPartySnapshot();
    if (snapshot == null || snapshot.isBlank()) return null;
    if (snapshot.trim().length() > 512) {
      throw new LogisticsConflictException("Снимок клиента ходки слишком длинный");
    }
    return snapshot.trim();
  }

  private static String groupedTaskText(LogisticsDocument document, List<DocumentMember> members) {
    String client = clientSnapshot(document);
    String taskText =
        (client == null ? description(document) : "Клиент: " + client)
            + ". Бытовки: "
            + members.stream()
                .map(DocumentMember::unitNumber)
                .collect(java.util.stream.Collectors.joining(", "));
    if (taskText.length() > 2_000) {
      throw new LogisticsConflictException(
          "Список номеров бытовок слишком длинный для задания отгрузки");
    }
    return taskText;
  }

  /** Formats an unambiguous cabin-count summary for a grouped document board card. */
  static String groupedUnitSummary(int count) {
    if (count < 1) {
      throw new IllegalArgumentException("Grouped document must contain at least one cabin");
    }
    int remainder100 = count % 100;
    int remainder10 = count % 10;
    String noun =
        remainder100 >= 11 && remainder100 <= 14
            ? "бытовок"
            : switch (remainder10) {
              case 1 -> "бытовка";
              case 2, 3, 4 -> "бытовки";
              default -> "бытовок";
            };
    return count + " " + noun;
  }

  private static void requireSameGroupedDocumentIntent(
      LogisticsDocument document,
      UUID taskWarehouseId,
      DriverTaskKind kind,
      List<DocumentMember> members,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      DriverLogisticsTask task) {
    if (!taskWarehouseId.equals(task.getWarehouseId())
        || !members.getFirst().cabinId().equals(task.getCabinId())
        || !document.getId().equals(task.getSourceId())
        || task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || task.getKind() != kind
        || !queue.queueDefinitionId().equals(task.getDriverQueueDefinitionId())
        || !Objects.equals(clientSnapshot(document), task.getClientSnapshot())
        || task.getMembers().size() != members.size()) {
      throw new LogisticsConflictException("Отгрузка уже связана с другим заданием водителя");
    }
    for (int index = 0; index < members.size(); index++) {
      DocumentMember expected = members.get(index);
      var actual = task.getMembers().get(index);
      if (!expected.lineId().equals(actual.getDocumentLineId())
          || !expected.cabinId().equals(actual.getCabinId())
          || !expected.unitNumber().equals(actual.getUnitNumber())
          || expected.position() != actual.getPosition()) {
        throw new LogisticsConflictException("Состав бытовок в задании отгрузки нельзя изменить");
      }
    }
  }

  private static void requireSameFurnitureCargoIntent(
      LogisticsDocument document,
      String cargoSummary,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      DriverLogisticsTask task) {
    if (!document.getWarehouseId().equals(task.getWarehouseId())
        || !document.getId().equals(task.getSourceId())
        || task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || task.getKind() != DriverTaskKind.TRANSFER
        || !task.isFurnitureCargoTransfer()
        || !queue.queueDefinitionId().equals(task.getDriverQueueDefinitionId())
        || !cargoSummary.equals(task.getUnitNumber())) {
      throw new LogisticsConflictException(
          "Перемещение мебели уже связано с другим заданием водителя");
    }
  }

  private void replanRegisteredTask(
      LogisticsDocument document,
      DriverLogisticsTask task,
      DriverTaskAudience audience,
      DriverTaskWorkerContent workerContent,
      String workerContentJson,
      String checksum) {
    if (task.getState() == DriverTaskState.REGISTERING && task.getTaskBoardTaskId() == null) {
      task.replanBeforeStart(
          document.getScheduledDate(),
          audience.mode(),
          audience.workerId(),
          audience.workerName(),
          checksum);
      task.captureWorkerContent(workerContentJson);
      tasks.saveAndFlush(task);
      return;
    }
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.readDriverTask(task.getExternalTaskId());
    if (!"ACTIVE".equals(current.status())
        || !"WAITING".equals(current.entryStatus())
        || !("SCHEDULED".equals(current.lane()) || "CURRENT".equals(current.lane()))) {
      throw new LogisticsConflictException(
          "Начатое задание водителя нельзя перепланировать из документа");
    }
    LogisticsDependencyGateway.DriverBoardTask contentUpdated = current;
    if (!Objects.equals(task.getWorkerContentJson(), workerContentJson)) {
      contentUpdated =
          dependencies.updateDriverTaskBeforeStart(
              task.getExternalTaskId(),
              current.taskVersion(),
              current.title(),
              task.getUnitNumber(),
              task.getComment() == null ? current.title() : task.getComment(),
              task.getDriverQueueDefinitionId(),
              workerContent);
    }
    int targetIndex = targetDateSize(task.getWarehouseId(), document.getScheduledDate());
    LogisticsDependencyGateway.DriverBoardTask moved =
        dependencies.moveDriverTask(
            task.getExternalTaskId(),
            contentUpdated.taskVersion(),
            contentUpdated.entryVersion(),
            "SCHEDULED",
            document.getScheduledDate(),
            targetIndex,
            audience);
    workflowStore.confirmStatus(task.getId(), moved);
    DriverLogisticsTask refreshed = tasks.findForUpdate(task.getId()).orElseThrow();
    refreshed.replanBeforeStart(
        document.getScheduledDate(),
        audience.mode(),
        audience.workerId(),
        audience.workerName(),
        checksum);
    refreshed.captureWorkerContent(workerContentJson);
    tasks.saveAndFlush(refreshed);
  }

  private int targetDateSize(UUID warehouseId, LocalDate targetDate) {
    return dependencies.readDriverBoard(warehouseId).dates().stream()
        .filter(column -> targetDate.equals(column.date()))
        .findFirst()
        .map(column -> column.tasks().size())
        .orElse(0);
  }

  private int tripNumber(LogisticsDocument document) {
    if (document.getRentalOrderId() == null) return 1;
    List<LogisticsDocument> orderTrips =
        documents.findAllByRentalOrderIdForUpdate(document.getRentalOrderId());
    for (int index = 0; index < orderTrips.size(); index++) {
      if (document.getId().equals(orderTrips.get(index).getId())) return index + 1;
    }
    throw new LogisticsConflictException("Ходка не входит в текущий заказ");
  }

  private void cancelTask(
      LogisticsDocument document, DriverLogisticsTask task, UUID taskWarehouseId) {
    if (!taskWarehouseId.equals(task.getWarehouseId())) {
      throw new LogisticsConflictException("Задание водителя принадлежит другому складу");
    }
    if (task.getState() == DriverTaskState.CANCELLED) return;
    if (task.getState() == DriverTaskState.REGISTERING && task.getTaskBoardTaskId() == null) {
      task.cancelBeforeExternalRegistration();
      tasks.saveAndFlush(task);
      return;
    }
    if (task.getState() == DriverTaskState.COMPLETED
        || task.getState() == DriverTaskState.FINALIZING) {
      throw new LogisticsConflictException(
          "Документ нельзя отменить после выполнения задания водителя");
    }
    LogisticsDependencyGateway.DriverBoardTask current =
        dependencies.readDriverTask(task.getExternalTaskId());
    if ("CANCELLED".equals(current.status())) {
      task.cancelAfterPreStartCancellation();
      tasks.saveAndFlush(task);
      return;
    }
    LogisticsDependencyGateway.DriverTaskPreStartCancellation cancellation =
        dependencies.cancelDriverTaskIfPreStart(
            task.getExternalTaskId(),
            current.taskVersion(),
            "Отменён исходный логистический документ");
    if (cancellation.outcome() == DriverTaskPreStartCancellationOutcome.CANCELLED
        || cancellation.outcome() == DriverTaskPreStartCancellationOutcome.ALREADY_CANCELLED) {
      task.cancelAfterPreStartCancellation();
      tasks.saveAndFlush(task);
      return;
    }
    throw new LogisticsConflictException(
        "Документ нельзя отменить: задание водителя уже начато или изменилось");
  }

  private static DriverTaskAudience audience(LogisticsDocument document) {
    if (document.isWarehouseDriverPool()) {
      return new DriverTaskAudience(DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null);
    }
    UUID workerId = document.getDriverWorkerId();
    String workerName = document.getDriverSnapshot();
    if (document.getDocumentType() == LogisticsDocumentType.TRANSFER) {
      return workerId == null
          ? new DriverTaskAudience(DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null)
          : new DriverTaskAudience(
              DriverTaskAudienceMode.ASSIGNED_DRIVER, workerId, workerName);
    }
    return workerId == null
        ? new DriverTaskAudience(DriverTaskAudienceMode.UNASSIGNED, null, null)
        : new DriverTaskAudience(DriverTaskAudienceMode.ASSIGNED_DRIVER, workerId, workerName);
  }

  private static DriverTaskKind kind(LogisticsDocumentType type) {
    return switch (Objects.requireNonNull(type, "documentType")) {
      case SHIPMENT -> DriverTaskKind.SHIPMENT;
      case RETURN -> DriverTaskKind.RETURN;
      case TRANSFER -> DriverTaskKind.TRANSFER;
    };
  }

  private static String description(LogisticsDocument document) {
    return switch (document.getDocumentType()) {
      case SHIPMENT -> "Отгрузка бытовки";
      case RETURN -> "Возврат бытовки";
      case TRANSFER -> "Перемещение бытовки между складами";
    };
  }

  private static void requirePlan(LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    if (document == null
        || document.getId() == null
        || document.getWarehouseId() == null
        || document.getScheduledDate() == null
        || document.getRequestedBySubjectId() == null
        || lines == null
        || lines.isEmpty()
        || lines.stream().anyMatch(line -> line == null || line.getId() == null)) {
      throw new IllegalArgumentException("Scheduled document and persisted lines are required");
    }
  }

  private static void requireTransferCargoPlan(LogisticsDocument document) {
    if (document == null
        || document.getId() == null
        || document.getDocumentType() != LogisticsDocumentType.TRANSFER
        || document.getWarehouseId() == null
        || document.getScheduledDate() == null
        || document.getRequestedBySubjectId() == null) {
      throw new IllegalArgumentException("Scheduled persisted transfer is required");
    }
  }

  private static String normalizedCargoSummary(String cargoSummary) {
    String normalized = cargoSummary == null ? "" : cargoSummary.trim();
    if (normalized.isEmpty() || normalized.length() > 64) {
      throw new IllegalArgumentException("cargoSummary is invalid");
    }
    return normalized;
  }

  private static String furnitureCargoTaskText(String cargoSummary) {
    return "Межскладской груз: " + cargoSummary;
  }

  private static void requireCabin(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.RentalItemSnapshot cabin) {
    if (cabin == null
        || !line.getAssetId().equals(cabin.assetId())
        || !line.getInventorySourceWarehouseId().equals(cabin.warehouseId())
        || cabin.number() == null
        || cabin.number().isBlank()) {
      throw new LogisticsConflictException(
          "Бытовка документа не принадлежит складу или не имеет номера");
    }
  }

  private static String groupedChecksum(
      LogisticsDocument document,
      UUID taskWarehouseId,
      DriverTaskKind kind,
      List<DocumentMember> members,
      DriverTaskAudience audience,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      String workerContentJson) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(document.getWarehouseId().toString());
    values.add(taskWarehouseId.toString());
    values.add(kind.name());
    values.add(document.getScheduledDate().toString());
    values.add(audience.mode().name());
    values.add(audience.workerId() == null ? null : audience.workerId().toString());
    values.add(audience.workerName());
    values.add(queue.queueDefinitionId().toString());
    values.add(workerContentJson);
    for (DocumentMember member : members) {
      values.add(member.lineId().toString());
      values.add(member.cabinId().toString());
      values.add(member.unitNumber());
      values.add(Integer.toString(member.position()));
    }
    return DriverTaskChecksum.sha256(PLAN_OPERATION, values);
  }

  private static String furnitureCargoChecksum(
      LogisticsDocument document,
      String cargoSummary,
      DriverTaskAudience audience,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      String workerContentJson) {
    return DriverTaskChecksum.sha256(
        PLAN_OPERATION,
        List.of(
            document.getId().toString(),
            document.getWarehouseId().toString(),
            DriverTaskKind.TRANSFER.name(),
            "FURNITURE_CARGO",
            document.getScheduledDate().toString(),
            cargoSummary,
            audience.mode().name(),
            audience.workerId() == null ? "" : audience.workerId().toString(),
            audience.workerName() == null ? "" : audience.workerName(),
            queue.queueDefinitionId().toString(),
            workerContentJson));
  }

  private static LogisticsConflictException legacyStarted() {
    return new LogisticsConflictException(
        "Старые задания бытовок уже начаты; объединить их в одну ходку нельзя");
  }

  /** Resolves the physical task origin and rejects an unsupported mixed-source shipment batch. */
  static UUID taskWarehouse(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    if (document.getDocumentType() != LogisticsDocumentType.SHIPMENT) {
      return document.getWarehouseId();
    }
    if (lines == null || lines.isEmpty()) {
      throw new LogisticsConflictException("В ходке отгрузки отсутствуют бытовки");
    }
    UUID source = lines.getFirst().getInventorySourceWarehouseId();
    if (source == null
        || lines.stream()
            .anyMatch(line -> !source.equals(line.getInventorySourceWarehouseId()))) {
      throw new LogisticsConflictException(
          "Одна ходка отгрузки не может содержать несколько складов-источников");
    }
    return source;
  }

  /** Derives one stable local task identity from a grouped logistics document. */
  private static UUID idempotencyKey(UUID sourceId, DriverTaskKind kind, String requestSha256) {
    return UUID.nameUUIDFromBytes(
        ("document-driver-task-v2:" + sourceId + ":" + kind + ":" + requestSha256)
            .getBytes(StandardCharsets.UTF_8));
  }

  /** Immutable input assembled from one document line and the asset-owned cabin number snapshot. */
  private record DocumentMember(UUID lineId, UUID cabinId, String unitNumber, int position) {}

  /** Prevalidated legacy task and its stable task-board version used for cancellation. */
  private record LegacyLineCancellation(
      DriverLogisticsTask task, LogisticsDependencyGateway.DriverBoardTask current) {}
}
