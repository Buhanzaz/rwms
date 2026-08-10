package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskAudience;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns creation, pre-start replanning, and cancellation of document-derived driver tasks. New
 * shipment documents receive one task with durable cabin members; return and transfer documents,
 * plus legacy shipment documents, retain their existing one-task-per-line representation.
 * Document lifecycle remains in the document coordinators. A local intent is persisted before the
 * relay registers it; changes to an already registered task use task-board's version-fenced
 * pre-start commands and reconcile a lost response by reading the same external task again.
 */
@Service
@RequiredArgsConstructor
public class DocumentDriverTaskPlanner {
  private static final String PLAN_OPERATION = "PLAN_DOCUMENT_DRIVER_TASK";
  private static final int DEFAULT_PRIORITY = 3;

  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDependencyGateway dependencies;
  private final DriverTaskWorkflowStore workflowStore;

  /** Creates or idempotently replans every driver task required by a scheduled document. */
  public void plan(LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    requirePlan(document, lines);
    DriverTaskKind kind = kind(document.getDocumentType());
    DriverTaskAudience audience = audience(document);
    LogisticsDependencyGateway.WarehouseDriverQueue queue =
        dependencies.readWarehouseDriverQueue(document.getWarehouseId());
    if (document.getDocumentType() == LogisticsDocumentType.SHIPMENT
        && !legacyLineTasksExist(lines)) {
      planGroupedShipment(document, lines, audience, queue);
      return;
    }
    for (LogisticsDocumentLine line : lines) {
      planLine(document, line, kind, audience, queue);
    }
  }

  /**
   * Cancels a grouped shipment task or the prior document-line tasks only while task-board still
   * confirms that execution has not begun. A partially observed remote cancellation is safe to
   * retry because each local intent is retained and task-board exposes an explicit
   * already-cancelled outcome.
   */
  public void cancelBeforeStart(LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    if (document == null || lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("Document and lines are required for driver cancellation");
    }
    List<UUID> sourceIds = lines.stream().map(LogisticsDocumentLine::getId).toList();
    List<DriverLogisticsTask> documentTasks = new ArrayList<>();
    documentTasks.addAll(
        tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(document.getId())));
    documentTasks.addAll(
        tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE, sourceIds));
    for (DriverLogisticsTask task : documentTasks) {
      cancelTask(document, task);
    }
  }

  private boolean legacyLineTasksExist(List<LogisticsDocumentLine> lines) {
    return tasks.existsBySourceTypeAndSourceIdIn(
        DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
        lines.stream().map(LogisticsDocumentLine::getId).toList());
  }

  private void planGroupedShipment(
      LogisticsDocument document,
      List<LogisticsDocumentLine> lines,
      DriverTaskAudience audience,
      LogisticsDependencyGateway.WarehouseDriverQueue queue) {
    DriverLogisticsTask existing =
        tasks
            .findActiveForUpdateBySourceTypeAndSourceIdAndKind(
                DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.SHIPMENT)
            .orElse(null);
    List<ShipmentMember> members = shipmentMembers(document, lines);
    if (existing == null) {
      DriverLogisticsTask created =
          DriverLogisticsTask.createGroupedShipment(
              document.getWarehouseId(),
              members.getFirst().cabinId(),
              document.getId(),
              document.getScheduledDate(),
              DEFAULT_PRIORITY,
              groupedTaskText(document, members),
              requiredClientSnapshot(document),
              groupedUnitSummary(members.size()),
              queue.queueDefinitionId(),
              audience.mode(),
              audience.workerId(),
              audience.workerName(),
              document.getRequestedBySubjectId(),
              idempotencyKey(document.getId(), DriverTaskKind.SHIPMENT),
              groupedChecksum(document, members, audience, queue));
      for (ShipmentMember member : members) {
        created.addGroupedShipmentMember(
            member.lineId(), member.cabinId(), member.unitNumber(), member.position());
      }
      tasks.saveAndFlush(created);
      return;
    }
    requireSameGroupedShipmentIntent(document, members, queue, existing);
    String checksum = groupedChecksum(document, members, audience, queue);
    if (existing.matchesRequest(checksum)) return;
    replanRegisteredTask(document, existing, audience, checksum);
  }

  private void planLine(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      DriverTaskKind kind,
      DriverTaskAudience audience,
      LogisticsDependencyGateway.WarehouseDriverQueue queue) {
    DriverLogisticsTask existing =
        tasks
            .findActiveForUpdateBySourceTypeAndSourceIdAndKind(
                DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE, line.getId(), kind)
            .orElse(null);
    LogisticsDependencyGateway.RentalItemSnapshot cabin =
        dependencies.readRentalItemSnapshot(line.getAssetId());
    requireCabin(document, line, cabin);
    String checksum = checksum(document, line, kind, audience, queue, cabin.number());
    if (existing == null) {
      tasks.saveAndFlush(
          DriverLogisticsTask.create(
              document.getWarehouseId(),
              line.getAssetId(),
              null,
              DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
              line.getId(),
              kind,
              DriverTaskPlanningMode.FIXED_DATE,
              document.getScheduledDate(),
              DEFAULT_PRIORITY,
              description(document),
              cabin.number(),
              queue.queueDefinitionId(),
              audience.mode(),
              audience.workerId(),
              audience.workerName(),
              document.getRequestedBySubjectId(),
              idempotencyKey(line.getId(), kind),
              checksum));
      return;
    }
    requireSameIntent(document, line, kind, queue, existing);
    if (existing.matchesRequest(checksum)) return;
    replanRegisteredTask(document, existing, audience, checksum);
  }

  private List<ShipmentMember> shipmentMembers(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    List<ShipmentMember> members = new ArrayList<>(lines.size());
    for (LogisticsDocumentLine line :
        lines.stream().sorted(Comparator.comparingInt(LogisticsDocumentLine::getLineNumber)).toList()) {
      LogisticsDependencyGateway.RentalItemSnapshot cabin =
          dependencies.readRentalItemSnapshot(line.getAssetId());
      requireCabin(document, line, cabin);
      members.add(
          new ShipmentMember(
              line.getId(), line.getAssetId(), cabin.number(), line.getLineNumber()));
    }
    if (members.isEmpty()) {
      throw new IllegalArgumentException("Grouped shipment requires at least one cabin member");
    }
    return List.copyOf(members);
  }

  private static String requiredClientSnapshot(LogisticsDocument document) {
    String snapshot = document.getPartySnapshot();
    if (snapshot == null || snapshot.isBlank() || snapshot.trim().length() > 512) {
      throw new LogisticsConflictException("Отгрузка не содержит снимок клиента");
    }
    return snapshot.trim();
  }

  private static String groupedTaskText(
      LogisticsDocument document, List<ShipmentMember> members) {
    String taskText =
        "Клиент: "
            + requiredClientSnapshot(document)
            + ". Бытовки: "
            + members.stream().map(ShipmentMember::unitNumber).collect(java.util.stream.Collectors.joining(", "));
    if (taskText.length() > 2_000) {
      throw new LogisticsConflictException(
          "Список номеров бытовок слишком длинный для задания отгрузки");
    }
    return taskText;
  }

  /** Formats an unambiguous cabin-count summary for a grouped shipment board card. */
  static String groupedUnitSummary(int count) {
    if (count < 1) {
      throw new IllegalArgumentException("Grouped shipment must contain at least one cabin");
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

  private static void requireSameGroupedShipmentIntent(
      LogisticsDocument document,
      List<ShipmentMember> members,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      DriverLogisticsTask task) {
    if (!document.getWarehouseId().equals(task.getWarehouseId())
        || !members.getFirst().cabinId().equals(task.getCabinId())
        || !document.getId().equals(task.getSourceId())
        || task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || task.getKind() != DriverTaskKind.SHIPMENT
        || !queue.queueDefinitionId().equals(task.getDriverQueueDefinitionId())
        || task.getClientSnapshot() == null
        || task.getClientSnapshot().isBlank()
        || task.getMembers().size() != members.size()) {
      throw new LogisticsConflictException(
          "Отгрузка уже связана с другим заданием водителя");
    }
    for (int index = 0; index < members.size(); index++) {
      ShipmentMember expected = members.get(index);
      var actual = task.getMembers().get(index);
      if (!expected.lineId().equals(actual.getDocumentLineId())
          || !expected.cabinId().equals(actual.getCabinId())
          || !expected.unitNumber().equals(actual.getUnitNumber())
          || expected.position() != actual.getPosition()) {
        throw new LogisticsConflictException(
            "Состав бытовок в задании отгрузки нельзя изменить");
      }
    }
  }

  private void replanRegisteredTask(
      LogisticsDocument document,
      DriverLogisticsTask task,
      DriverTaskAudience audience,
      String checksum) {
    if (task.getState() == DriverTaskState.REGISTERING && task.getTaskBoardTaskId() == null) {
      task.replanBeforeStart(
          document.getScheduledDate(),
          audience.mode(),
          audience.workerId(),
          audience.workerName(),
          checksum);
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
    int targetIndex = targetDateSize(document.getWarehouseId(), document.getScheduledDate());
    LogisticsDependencyGateway.DriverBoardTask moved =
        dependencies.moveDriverTask(
            task.getExternalTaskId(),
            current.taskVersion(),
            current.entryVersion(),
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
    tasks.saveAndFlush(refreshed);
  }

  private int targetDateSize(UUID warehouseId, LocalDate targetDate) {
    return dependencies.readDriverBoard(warehouseId).dates().stream()
        .filter(column -> targetDate.equals(column.date()))
        .findFirst()
        .map(column -> column.tasks().size())
        .orElse(0);
  }

  private void cancelTask(LogisticsDocument document, DriverLogisticsTask task) {
    if (!document.getWarehouseId().equals(task.getWarehouseId())) {
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
    UUID workerId = document.getDriverWorkerId();
    String workerName = document.getDriverSnapshot();
    if (document.getDocumentType() == LogisticsDocumentType.TRANSFER) {
      return new DriverTaskAudience(DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null);
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

  private static void requirePlan(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {
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

  private static void requireCabin(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.RentalItemSnapshot cabin) {
    if (cabin == null
        || !line.getAssetId().equals(cabin.assetId())
        || !document.getWarehouseId().equals(cabin.warehouseId())
        || cabin.number() == null
        || cabin.number().isBlank()) {
      throw new LogisticsConflictException(
          "Бытовка документа не принадлежит складу или не имеет номера");
    }
  }

  private static void requireSameIntent(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      DriverTaskKind kind,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      DriverLogisticsTask task) {
    if (!document.getWarehouseId().equals(task.getWarehouseId())
        || !line.getAssetId().equals(task.getCabinId())
        || !line.getId().equals(task.getSourceId())
        || task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE
        || task.getKind() != kind
        || !queue.queueDefinitionId().equals(task.getDriverQueueDefinitionId())) {
      throw new LogisticsConflictException(
          "Строка документа уже связана с другим заданием водителя");
    }
  }

  private static String checksum(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      DriverTaskKind kind,
      DriverTaskAudience audience,
      LogisticsDependencyGateway.WarehouseDriverQueue queue,
      String unitNumber) {
    return DriverTaskChecksum.sha256(
        PLAN_OPERATION,
        Arrays.asList(
            document.getId().toString(),
            line.getId().toString(),
            document.getWarehouseId().toString(),
            line.getAssetId().toString(),
            kind.name(),
            document.getScheduledDate().toString(),
            audience.mode().name(),
            audience.workerId() == null ? null : audience.workerId().toString(),
            audience.workerName(),
            queue.queueDefinitionId().toString(),
            unitNumber));
  }

  private static String groupedChecksum(
      LogisticsDocument document,
      List<ShipmentMember> members,
      DriverTaskAudience audience,
      LogisticsDependencyGateway.WarehouseDriverQueue queue) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(document.getWarehouseId().toString());
    values.add(DriverTaskKind.SHIPMENT.name());
    values.add(document.getScheduledDate().toString());
    values.add(audience.mode().name());
    values.add(audience.workerId() == null ? null : audience.workerId().toString());
    values.add(audience.workerName());
    values.add(queue.queueDefinitionId().toString());
    for (ShipmentMember member : members) {
      values.add(member.lineId().toString());
      values.add(member.cabinId().toString());
      values.add(member.unitNumber());
      values.add(Integer.toString(member.position()));
    }
    return DriverTaskChecksum.sha256(PLAN_OPERATION, values);
  }

  /** Derives one stable local task identity from either a document or a document line source. */
  private static UUID idempotencyKey(UUID sourceId, DriverTaskKind kind) {
    return UUID.nameUUIDFromBytes(
        ("document-driver-task:" + sourceId + ":" + kind)
            .getBytes(StandardCharsets.UTF_8));
  }

  /** Immutable input assembled from one document line and the asset-owned cabin number snapshot. */
  private record ShipmentMember(UUID lineId, UUID cabinId, String unitNumber, int position) {}
}
