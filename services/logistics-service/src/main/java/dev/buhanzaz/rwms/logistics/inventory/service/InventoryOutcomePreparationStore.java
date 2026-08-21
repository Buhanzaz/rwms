package dev.buhanzaz.rwms.logistics.inventory.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.domain.TransferFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeResponse;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryAssetOutcomeWatermark;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeReceipt;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeReceiptAsset;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeReceiptState;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskAction;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskActionState;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskTargetType;
import dev.buhanzaz.rwms.logistics.inventory.repository.InventoryAssetOutcomeWatermarkRepository;
import dev.buhanzaz.rwms.logistics.inventory.repository.InventoryOutcomeReceiptAssetRepository;
import dev.buhanzaz.rwms.logistics.inventory.repository.InventoryOutcomeReceiptRepository;
import dev.buhanzaz.rwms.logistics.inventory.repository.InventoryOutcomeTaskActionRepository;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTaskReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.repository.TransferFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the single-transaction validation and local supersession half of completed inventory. It
 * deliberately records task actions instead of invoking any remote dependency under the lock.
 */
@Component
@RequiredArgsConstructor
public class InventoryOutcomePreparationStore {
  private static final Set<LogisticsDocumentState> HISTORICAL_DOCUMENT_STATES =
      Set.of(
          LogisticsDocumentState.ACCEPTED,
          LogisticsDocumentState.ESTIMATE_REQUESTED,
          LogisticsDocumentState.SHIPPED,
          LogisticsDocumentState.COMPLETED,
          LogisticsDocumentState.CANCELLED);
  private static final UUID EMPTY_QUERY_SENTINEL = new UUID(0, 0);

  private final InventoryOutcomeReceiptRepository receipts;
  private final InventoryOutcomeReceiptAssetRepository receiptAssets;
  private final InventoryAssetOutcomeWatermarkRepository watermarks;
  private final InventoryOutcomeTaskActionRepository actions;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository documentLines;
  private final LogisticsGuardRepository guards;
  private final LogisticsTaskReferenceRepository taskReferences;
  private final DriverLogisticsTaskRepository driverTasks;
  private final RentalOrderRepository rentalOrders;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final ShipmentFurnitureMovementTaskRepository shipmentFurnitureTasks;
  private final TransferFurnitureMovementTaskRepository transferFurnitureTasks;
  private final EquipmentMovementTaskRepository equipmentTasks;
  private final LogisticsTransactionLock transactionLock;
  private final ObjectMapper json;

  /**
   * Validates the entire batch and persists every local marker or rolls everything back. An equal
   * completion time can advance only the same warehouse/inventory source to a strictly greater
   * immutable final-plan version.
   */
  @Transactional
  public Preparation prepare(
      UUID idempotencyKey,
      InventoryOutcomeCommand command,
      String requestSha256,
      JsonNode requestJson) {
    transactionLock.acquire("inventory-outcome:idempotency:" + idempotencyKey);
    InventoryOutcomeReceipt existing =
        receipts.findForUpdateByIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      if (!existing.matchesRequest(requestSha256)) {
        throw new LogisticsConflictException(
            "Idempotency-Key is already bound to another inventory outcome request");
      }
      return new Preparation(
          existing.getId(),
          true,
          existing.getState() == InventoryOutcomeReceiptState.COMPLETED
              ? response(existing.getResponseJson(), true)
              : null);
    }

    Map<UUID, InventoryOutcomeCommand.AssetOutcome> byAsset =
        command.outcomes().stream()
            .collect(
                Collectors.toMap(
                    InventoryOutcomeCommand.AssetOutcome::assetId,
                    Function.identity(),
                    (left, right) -> {
                      throw new IllegalArgumentException("Inventory outcome contains duplicate assetId");
                    },
                    LinkedHashMap::new));
    byAsset.keySet().stream()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(assetId -> transactionLock.acquire("inventory-outcome:asset:" + assetId));
    validateAndAdvanceWatermarks(command, byAsset.keySet());

    List<LogisticsDocumentLine> candidateLines =
        documentLines.findAllForUpdateByAssetIdIn(byAsset.keySet());
    candidateLines.stream()
        .map(LogisticsDocumentLine::getDocument)
        .filter(document -> !HISTORICAL_DOCUMENT_STATES.contains(document.getState()))
        .filter(document -> !relevantWarehouse(document, command.warehouseId()))
        .findFirst()
        .ifPresent(
            document -> {
              throw new LogisticsConflictException(
                  "Active logistics document belongs to another warehouse: " + document.getId());
            });
    List<LogisticsDocumentLine> selectedLines =
        candidateLines.stream()
            .filter(line -> line.getDocument().getState() != LogisticsDocumentState.CANCELLED)
            .filter(line -> relevantWarehouse(line.getDocument(), command.warehouseId()))
            .toList();
    Set<UUID> documentIds =
        selectedLines.stream()
            .map(line -> line.getDocument().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<LogisticsDocument> selectedDocuments =
        documentIds.isEmpty() ? List.of() : documents.findAllForUpdateByIdIn(documentIds);
    validateCompleteDocuments(command, byAsset.keySet(), selectedDocuments);

    List<RentalOrderUnitTerm> selectedTerms =
        rentalTerms.findAllForUpdateByRentalItemIdIn(byAsset.keySet()).stream()
            .filter(term -> relevantOrder(term.getOrder(), command.warehouseId()))
            .toList();
    Set<UUID> orderIds =
        selectedTerms.stream()
            .map(term -> term.getOrder().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<RentalOrder> selectedOrders =
        orderIds.isEmpty() ? List.of() : rentalOrders.findAllForUpdateByIdIn(orderIds);

    Set<UUID> currentFindingIds =
        command.outcomes().stream()
            .map(InventoryOutcomeCommand.AssetOutcome::findingId)
            .collect(Collectors.toUnmodifiableSet());
    List<DriverLogisticsTask> selectedDriverTasks =
        driverTasks.findInventoryCandidatesForUpdate(
                byAsset.keySet(), nonempty(documentIds))
            .stream()
            .filter(task -> !currentInventoryMovement(task, currentFindingIds))
            .toList();
    validateDriverTasks(command, byAsset.keySet(), documentIds, selectedDriverTasks);

    Set<UUID> selectedLineIds =
        selectedLines.stream()
            .map(LogisticsDocumentLine::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<LogisticsGuard> selectedGuards =
        selectedLineIds.isEmpty()
            ? List.of()
            : guards.findAllForUpdateByLineIdIn(selectedLineIds);
    validateGuards(selectedGuards, byAsset.keySet());
    List<UUID> documentTaskIds =
        selectedLineIds.isEmpty()
            ? List.of()
            : taskReferences.findActiveIdsByLineIdIn(selectedLineIds);
    List<EquipmentMovementTask> selectedEquipmentTasks =
        selectedEquipmentTasks(selectedDocuments);
    validateEquipmentTasks(selectedEquipmentTasks);

    List<UUID> sortedDocumentIds = sorted(documentIds);
    List<UUID> sortedOrderIds = sorted(orderIds);
    InventoryOutcomeReceipt receipt =
        receipts.saveAndFlush(
            InventoryOutcomeReceipt.prepare(
                idempotencyKey,
                command.inventoryId(),
                command.warehouseId(),
                command.inventoryCompletedAt(),
                command.finalPlanVersion(),
                command.finalPlanSha256(),
                requestSha256,
                requestJson,
                json.valueToTree(sortedDocumentIds),
                json.valueToTree(sortedOrderIds),
                selectedLines.size(),
                selectedTerms.size()));
    receiptAssets.saveAll(
        command.outcomes().stream()
            .map(
                outcome ->
                    InventoryOutcomeReceiptAsset.create(
                        receipt.getId(),
                        outcome.findingId(),
                        outcome.assetId(),
                        outcome.desiredStatus()))
            .toList());

    applyDocumentMarkers(command, byAsset, selectedLines, selectedDocuments, selectedGuards);
    applyRentalMarkers(command, byAsset, selectedTerms, selectedOrders);
    createTaskActions(
        receipt.getId(), selectedDriverTasks, documentTaskIds, selectedEquipmentTasks, selectedGuards);
    return new Preparation(receipt.getId(), false, null);
  }

  /** Freezes a response only after every task action has a durable terminal checkpoint. */
  @Transactional
  public ApplyInventoryOutcomeResponse complete(UUID receiptId, boolean replay) {
    InventoryOutcomeReceipt receipt =
        receipts
            .findForUpdate(receiptId)
            .orElseThrow(() -> new LogisticsConflictException("Inventory outcome receipt is missing"));
    if (receipt.getState() == InventoryOutcomeReceiptState.COMPLETED) {
      return response(receipt.getResponseJson(), true);
    }
    List<InventoryOutcomeTaskAction> pending =
        actions.findAllByReceiptIdAndStateOrderByTargetTypeAscTargetIdAsc(
            receiptId, InventoryOutcomeTaskActionState.PENDING);
    if (!pending.isEmpty()) {
      throw new IllegalStateException("Inventory outcome still has pending task actions");
    }
    List<UUID> cancelledDriverTaskIds =
        actions
            .findAllByReceiptIdAndTargetTypeAndStateOrderByTargetIdAsc(
                receiptId,
                InventoryOutcomeTaskTargetType.DRIVER_TASK,
                InventoryOutcomeTaskActionState.CANCELLED)
            .stream()
            .map(InventoryOutcomeTaskAction::getTargetId)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    ApplyInventoryOutcomeResponse response =
        new ApplyInventoryOutcomeResponse(
            receipt.getInventoryId(),
            receipt.getFinalPlanVersion(),
            uuidList(receipt.getSupersededDocumentIds()),
            uuidList(receipt.getSupersededRentalOrderIds()),
            cancelledDriverTaskIds,
            receipt.getSupersededLineCount(),
            receipt.getSupersededRentalUnitCount(),
            false);
    receipt.complete(json.valueToTree(response));
    receipts.saveAndFlush(receipt);
    return replay ? withReplay(response) : response;
  }

  private void validateAndAdvanceWatermarks(
      InventoryOutcomeCommand command, Collection<UUID> assetIds) {
    Map<UUID, InventoryAssetOutcomeWatermark> existing =
        watermarks.findAllForUpdate(assetIds).stream()
            .collect(Collectors.toMap(InventoryAssetOutcomeWatermark::getAssetId, Function.identity()));
    for (UUID assetId : assetIds) {
      InventoryAssetOutcomeWatermark watermark = existing.get(assetId);
      if (watermark == null) continue;
      int comparison = command.inventoryCompletedAt().compareTo(watermark.getInventoryCompletedAt());
      if (comparison < 0) {
        throw new LogisticsConflictException(
            "A newer completed inventory already owns asset " + assetId);
      }
      boolean exactSource =
          watermark.sameSource(
              command.warehouseId(),
              command.inventoryCompletedAt(),
              command.inventoryId(),
              command.finalPlanVersion(),
              command.finalPlanSha256());
      boolean correctedSource =
          watermark.isStrictlyNewerPlanForSameCompletedInventory(
              command.warehouseId(),
              command.inventoryCompletedAt(),
              command.inventoryId(),
              command.finalPlanVersion());
      if (comparison == 0 && !exactSource && !correctedSource) {
        throw new LogisticsConflictException(
            "Another immutable inventory source has the same completion time for asset " + assetId);
      }
    }
    List<InventoryAssetOutcomeWatermark> advanced = new ArrayList<>(assetIds.size());
    for (UUID assetId : assetIds) {
      InventoryAssetOutcomeWatermark watermark = existing.get(assetId);
      if (watermark == null) {
        watermark =
            InventoryAssetOutcomeWatermark.create(
                assetId,
                command.warehouseId(),
                command.inventoryCompletedAt(),
                command.inventoryId(),
                command.finalPlanVersion(),
                command.finalPlanSha256());
      } else if (!watermark.sameSource(
          command.warehouseId(),
          command.inventoryCompletedAt(),
          command.inventoryId(),
          command.finalPlanVersion(),
          command.finalPlanSha256())) {
        watermark.replace(
            command.warehouseId(),
            command.inventoryCompletedAt(),
            command.inventoryId(),
            command.finalPlanVersion(),
            command.finalPlanSha256());
      }
      advanced.add(watermark);
    }
    watermarks.saveAll(advanced);
  }

  private void validateCompleteDocuments(
      InventoryOutcomeCommand command,
      Set<UUID> selectedAssetIds,
      List<LogisticsDocument> selectedDocuments) {
    if (selectedDocuments.isEmpty()) return;
    List<LogisticsDocumentLine> allLines =
        documentLines.findAllForUpdateByDocumentIdIn(
            selectedDocuments.stream().map(LogisticsDocument::getId).toList());
    Map<UUID, List<LogisticsDocumentLine>> byDocument =
        allLines.stream().collect(Collectors.groupingBy(line -> line.getDocument().getId()));
    for (LogisticsDocument document : selectedDocuments) {
      if (!relevantWarehouse(document, command.warehouseId())
          && !HISTORICAL_DOCUMENT_STATES.contains(document.getState())) {
        throw new LogisticsConflictException(
            "Active logistics document belongs to another warehouse: " + document.getId());
      }
      if (HISTORICAL_DOCUMENT_STATES.contains(document.getState())) continue;
      boolean unrelatedActiveLine =
          byDocument.getOrDefault(document.getId(), List.of()).stream()
              .filter(line -> line.getState() != dev.buhanzaz.rwms.logistics.domain.LogisticsLineState.CANCELLED)
              .anyMatch(
                  line ->
                      !selectedAssetIds.contains(line.getAssetId())
                          && !supersededBySameOrNewer(line, command.inventoryCompletedAt()));
      if (unrelatedActiveLine) {
        throw new LogisticsConflictException(
            "Inventory batch does not contain every active line of document " + document.getId());
      }
    }
  }

  private static boolean supersededBySameOrNewer(
      LogisticsDocumentLine line, OffsetDateTime completedAt) {
    return line.getInventoryCompletedAt() != null
        && !line.getInventoryCompletedAt().isBefore(completedAt);
  }

  private static void validateDriverTasks(
      InventoryOutcomeCommand command,
      Set<UUID> selectedAssetIds,
      Set<UUID> selectedDocumentIds,
      List<DriverLogisticsTask> selectedTasks) {
    for (DriverLogisticsTask task : selectedTasks) {
      boolean selectedDocument = selectedDocumentIds.contains(task.getSourceId());
      if (!selectedDocument && !command.warehouseId().equals(task.getWarehouseId())) {
        throw new LogisticsConflictException(
            "Active driver task belongs to another warehouse: " + task.getId());
      }
      boolean unrelatedCabin =
          task.getCabinId() != null && !selectedAssetIds.contains(task.getCabinId());
      boolean unrelatedMember =
          task.getMembers().stream()
              .anyMatch(member -> !selectedAssetIds.contains(member.getCabinId()));
      if (unrelatedCabin || unrelatedMember) {
        throw new LogisticsConflictException(
            "Inventory batch cannot cancel a grouped task containing another active cabin: "
                + task.getId());
      }
    }
  }

  private List<EquipmentMovementTask> selectedEquipmentTasks(
      List<LogisticsDocument> selectedDocuments) {
    if (selectedDocuments.isEmpty()) return List.of();
    List<UUID> documentIds = selectedDocuments.stream().map(LogisticsDocument::getId).toList();
    LinkedHashSet<UUID> ids =
        selectedDocuments.stream()
            .map(LogisticsDocument::getEquipmentMovementTaskId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    shipmentFurnitureTasks
        .findAllByDocument_IdInOrderByDocument_IdAscUnitNumberAsc(documentIds)
        .stream()
        .map(ShipmentFurnitureMovementTask::getEquipmentMovementTaskId)
        .filter(java.util.Objects::nonNull)
        .forEach(ids::add);
    transferFurnitureTasks
        .findAllByDocument_IdInOrderByDocument_IdAscUnitNumberAsc(documentIds)
        .stream()
        .map(TransferFurnitureMovementTask::getEquipmentMovementTaskId)
        .filter(java.util.Objects::nonNull)
        .forEach(ids::add);
    if (ids.isEmpty()) return List.of();
    List<EquipmentMovementTask> result = equipmentTasks.findAllForUpdateByIdIn(ids);
    if (result.size() != ids.size()) {
      throw new LogisticsConflictException("A selected document has a missing equipment task");
    }
    return result;
  }

  private static void validateEquipmentTasks(List<EquipmentMovementTask> tasks) {
    for (EquipmentMovementTask task : tasks) {
      if (task.getState() == EquipmentMovementTaskState.EXECUTING
          || task.getState() == EquipmentMovementTaskState.RECONCILIATION_REQUIRED) {
        throw new LogisticsConflictException(
            "Equipment movement cannot be safely superseded in state " + task.getState());
      }
    }
  }

  private static void validateGuards(
      List<LogisticsGuard> selectedGuards, Set<UUID> selectedAssetIds) {
    for (LogisticsGuard guard : selectedGuards) {
      if (!selectedAssetIds.contains(guard.getAssetId())
          || !guard.getLine().getAssetId().equals(guard.getAssetId())) {
        throw new LogisticsConflictException("Selected logistics guard has a mismatched asset");
      }
      if (guard.getGuardState() != LogisticsGuardState.RELEASED
          && (guard.getLeaseId() == null
              || guard.getLeaseVersion() == null
              || guard.getLeaseVersion() < 0
              || guard.getFenceToken() == null
              || guard.getFenceToken() < 1)) {
        throw new LogisticsConflictException(
            "Selected logistics guard has no releasable asset lease capability");
      }
    }
  }

  private void applyDocumentMarkers(
      InventoryOutcomeCommand command,
      Map<UUID, InventoryOutcomeCommand.AssetOutcome> byAsset,
      List<LogisticsDocumentLine> selectedLines,
      List<LogisticsDocument> selectedDocuments,
      List<LogisticsGuard> selectedGuards) {
    for (LogisticsDocumentLine line : selectedLines) {
      InventoryOutcomeCommand.AssetOutcome outcome = byAsset.get(line.getAssetId());
      line.supersedeByCompletedInventory(
          command.inventoryId(),
          outcome.findingId(),
          outcome.desiredStatus(),
          command.inventoryCompletedAt(),
          command.finalPlanVersion(),
          command.finalPlanSha256());
    }
    documentLines.saveAll(selectedLines);
    selectedGuards.forEach(guard -> guard.supersedeByCompletedInventory(command.inventoryId()));
    guards.saveAll(selectedGuards);
    for (LogisticsDocument document : selectedDocuments) {
      document.supersedeByCompletedInventory(
          command.inventoryId(),
          command.inventoryCompletedAt(),
          command.finalPlanVersion(),
          command.finalPlanSha256());
    }
    documents.saveAll(selectedDocuments);
  }

  private void applyRentalMarkers(
      InventoryOutcomeCommand command,
      Map<UUID, InventoryOutcomeCommand.AssetOutcome> byAsset,
      List<RentalOrderUnitTerm> selectedTerms,
      List<RentalOrder> selectedOrders) {
    for (RentalOrderUnitTerm term : selectedTerms) {
      InventoryOutcomeCommand.AssetOutcome outcome = byAsset.get(term.getRentalItemId());
      term.supersedeByCompletedInventory(
          command.inventoryId(),
          outcome.findingId(),
          outcome.desiredStatus(),
          command.inventoryCompletedAt(),
          command.finalPlanVersion(),
          command.finalPlanSha256());
    }
    rentalTerms.saveAllAndFlush(selectedTerms);
    for (RentalOrder order : selectedOrders) {
      if (rentalTerms.countActiveByOrderId(order.getId()) == 0) {
        order.supersedeByCompletedInventory(
            command.inventoryId(), command.inventoryCompletedAt());
      }
    }
    rentalOrders.saveAll(selectedOrders);
  }

  private void createTaskActions(
      UUID receiptId,
      List<DriverLogisticsTask> selectedDriverTasks,
      List<UUID> documentTaskIds,
      List<EquipmentMovementTask> selectedEquipmentTasks,
      List<LogisticsGuard> selectedGuards) {
    List<InventoryOutcomeTaskAction> values = new ArrayList<>();
    selectedDriverTasks.forEach(
        task ->
            values.add(
                InventoryOutcomeTaskAction.pending(
                    receiptId, InventoryOutcomeTaskTargetType.DRIVER_TASK, task.getId())));
    documentTaskIds.forEach(
        taskId ->
            values.add(
                InventoryOutcomeTaskAction.pending(
                    receiptId, InventoryOutcomeTaskTargetType.DOCUMENT_TASK, taskId)));
    selectedEquipmentTasks.stream()
        .filter(
            task ->
                task.getState() != EquipmentMovementTaskState.COMPLETED
                    && task.getState() != EquipmentMovementTaskState.CANCELLED
                    && task.getState() != EquipmentMovementTaskState.EXPIRED
                    && task.getState() != EquipmentMovementTaskState.CONFLICT)
        .forEach(
            task ->
                values.add(
                    InventoryOutcomeTaskAction.pending(
                        receiptId, InventoryOutcomeTaskTargetType.EQUIPMENT_TASK, task.getId())));
    selectedGuards.stream()
        .filter(guard -> guard.getGuardState() != LogisticsGuardState.RELEASED)
        .forEach(
            guard ->
                values.add(
                    InventoryOutcomeTaskAction.pending(
                        receiptId, InventoryOutcomeTaskTargetType.GUARD_LEASE, guard.getId())));
    actions.saveAll(values);
  }

  private static boolean relevantWarehouse(LogisticsDocument document, UUID warehouseId) {
    return warehouseId.equals(document.getWarehouseId())
        || warehouseId.equals(document.getDestinationWarehouseId());
  }

  private static boolean relevantOrder(RentalOrder order, UUID warehouseId) {
    if (warehouseId.equals(order.getWarehouseId())) return true;
    if (order.getStatus() == dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus.CLOSED
        || order.getStatus()
            == dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus.CANCELLED) {
      return false;
    }
    throw new LogisticsConflictException(
        "Active rental order belongs to another warehouse: " + order.getId());
  }

  /** Protects only the downstream movement created from a finding in this exact source batch. */
  private static boolean currentInventoryMovement(
      DriverLogisticsTask task, Set<UUID> currentFindingIds) {
    return task.getSourceType() == DriverTaskSourceType.INVENTORY
        && currentFindingIds.contains(task.getSourceId());
  }

  private static Set<UUID> nonempty(Set<UUID> values) {
    return values.isEmpty() ? Set.of(EMPTY_QUERY_SENTINEL) : values;
  }

  private static List<UUID> sorted(Collection<UUID> values) {
    return values.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
  }

  private List<UUID> uuidList(JsonNode node) {
    try {
      return List.copyOf(java.util.Arrays.asList(json.treeToValue(node, UUID[].class)));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored inventory outcome identifier list is invalid", exception);
    }
  }

  private ApplyInventoryOutcomeResponse response(JsonNode node, boolean replay) {
    try {
      ApplyInventoryOutcomeResponse stored =
          json.treeToValue(node, ApplyInventoryOutcomeResponse.class);
      return replay ? withReplay(stored) : stored;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored inventory outcome response is invalid", exception);
    }
  }

  private static ApplyInventoryOutcomeResponse withReplay(ApplyInventoryOutcomeResponse response) {
    return new ApplyInventoryOutcomeResponse(
        response.inventoryId(),
        response.finalPlanVersion(),
        response.supersededDocumentIds(),
        response.supersededRentalOrderIds(),
        response.cancelledDriverTaskIds(),
        response.supersededLineCount(),
        response.supersededRentalUnitCount(),
        true);
  }

  /** Preparation result that distinguishes a frozen replay from recoverable pending work. */
  public record Preparation(
      UUID receiptId, boolean replay, ApplyInventoryOutcomeResponse completedResponse) {}
}
