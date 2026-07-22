package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsEquipmentHoldReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsEquipmentHoldState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliation;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsEquipmentHoldReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTaskReferenceRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Durable local half of shipment preparation, confirmation and cancellation. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ShipmentWorkflowStore {
  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsExternalAttemptRepository attemptRepository;
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsEquipmentHoldReferenceRepository holdRepository;
  private final LogisticsTaskReferenceRepository taskRepository;
  private final LogisticsReconciliationRepository reconciliationRepository;
  private final LogisticsEventStore eventStore;

  Optional<Work> nextWork(UUID documentId) {
    if (documentId == null) return Optional.empty();
    OffsetDateTime now = now();
    for (LogisticsExternalAttempt attempt :
        attemptRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      LogisticsDocument document = attempt.getDocument();
      if (!isShipmentWorkState(document.getState()) || !attempt.isDue(now)) continue;
      LogisticsDocumentLine line = requiredLine(attempt);
      Optional<Work> work = switch (document.getState()) {
        case PREPARING -> preparationWork(attempt, document, line);
        case CONFIRMING_PREPARATION -> confirmationWork(attempt, document, line);
        case CANCELLING -> cancellationWork(attempt, document, line);
        default -> Optional.empty();
      };
      if (work.isPresent()) return work;
    }
    return Optional.empty();
  }

  @Transactional
  public void confirmSnapshot(UUID operationId, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.SHIPMENT_ASSET_SNAPSHOT);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.PREPARING) return;
    requireFreeSnapshot(document, line, snapshot);

    OffsetDateTime completedAt = now();
    line.captureExpectedContents(contentsSnapshot(snapshot.contents()));
    attempt.confirm(snapshotDigest("SHIPMENT_ASSET_SNAPSHOT_RESPONSE", snapshot), completedAt);
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.SHIPMENT_ASSET_LEASE_ACQUIRE,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.SHIPMENT_ASSET_LEASE_ACQUIRE,
            List.of(
                line.getAssetId().toString(),
                Long.toString(line.getAssetVersion()),
                document.getId().toString(),
                line.getId().toString())),
        completedAt);
  }

  @Transactional
  public void confirmLease(UUID operationId, LogisticsDependencyGateway.OperationLease lease) {
    LogisticsExternalAttempt attempt =
        attempt(operationId, LogisticsDocumentService.SHIPMENT_ASSET_LEASE_ACQUIRE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.PREPARING) return;
    requireActiveLease(line, lease);

    OffsetDateTime completedAt = now();
    attempt.confirm(leaseDigest("SHIPMENT_ASSET_LEASE_RESPONSE", lease), completedAt);
    Optional<LogisticsGuard> existing = guardRepository.findByLine_Id(line.getId());
    if (existing.isPresent()) {
      LogisticsGuard guard = existing.get();
      if (guard.getGuardState() != LogisticsGuardState.ACTIVE
          || !lease.leaseId().equals(guard.getLeaseId())
          || lease.fencingToken() != guard.getFenceToken()) {
        throw malformed("Shipment line has a conflicting asset guard");
      }
    } else {
      guardRepository.save(
          LogisticsGuard.active(
              document,
              line,
              lease.leaseId(),
              lease.version(),
              lease.fencingToken(),
              line.getAssetVersion(),
              completedAt));
    }
    List<Allocation> allocations = allocations(line);
    if (allocations.isEmpty()) {
      createTaskRegistrationAttempt(document, line, completedAt);
      return;
    }
    for (Allocation allocation : allocations) {
      String operation = LogisticsDocumentService.holdAcquireOperation(allocation.equipmentId());
      createAttemptIfMissing(
          document,
          line,
          LogisticsTargetService.ASSET,
          operation,
          LogisticsCommandChecksum.sha256(
              operation,
              List.of(
                  allocation.equipmentId().toString(),
                  document.getWarehouseId().toString(),
                  document.getId().toString(),
                  line.getId().toString(),
                  Long.toString(allocation.quantity()),
                  Long.toString(allocation.expectedStockVersion()))),
          completedAt);
    }
  }

  @Transactional
  public void confirmHoldAcquire(UUID operationId, LogisticsDependencyGateway.EquipmentHold hold) {
    LogisticsExternalAttempt attempt = attemptByPrefix(operationId, LogisticsDocumentService.SHIPMENT_HOLD_ACQUIRE_PREFIX);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.PREPARING) return;
    Allocation allocation = allocationForOperation(line, attempt.getOperationType(), LogisticsDocumentService.SHIPMENT_HOLD_ACQUIRE_PREFIX);
    requireActiveHold(hold);

    OffsetDateTime completedAt = now();
    attempt.confirm(holdDigest("SHIPMENT_HOLD_ACQUIRE_RESPONSE", hold), completedAt);
    Optional<LogisticsEquipmentHoldReference> existing =
        holdRepository.findByLine_IdAndEquipmentId(line.getId(), allocation.equipmentId());
    if (existing.isPresent()) {
      if (!existing.get().getHoldId().equals(hold.holdId())
          || existing.get().getHoldState() != LogisticsEquipmentHoldState.ACTIVE) {
        throw malformed("Shipment allocation has a conflicting equipment hold");
      }
    } else {
      holdRepository.save(
          LogisticsEquipmentHoldReference.active(
              document,
              line,
              hold.holdId(),
              allocation.equipmentId(),
              document.getWarehouseId(),
              allocation.quantity(),
              allocation.expectedStockVersion(),
              hold.version(),
              completedAt));
    }
    if (allHoldsAcquired(document, line)) createTaskRegistrationAttempt(document, line, completedAt);
  }

  @Transactional
  public void confirmTaskRegistration(UUID operationId, LogisticsDependencyGateway.PreparationTask task) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.SHIPMENT_TASK_REGISTER);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.PREPARING) return;
    LogisticsTaskReference reference =
        taskRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> malformed("Shipment task registration has no local reference"));
    requireRegisteredTask(document, reference, task);

    OffsetDateTime completedAt = now();
    attempt.confirm(taskDigest("SHIPMENT_TASK_REGISTER_RESPONSE", task), completedAt);
    reference.register(task.taskId(), task.taskVersion(), task.status(), task.doneAt());
    if (allTasksRegistered(document)) {
      document.awaitShipmentConfirmation();
      documentRepository.saveAndFlush(document);
      eventStore.append(
          document,
          lineCount(document),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.SHIPMENT_PLANNED,
          null);
    }
  }

  @Transactional
  public void confirmTaskStatus(UUID operationId, LogisticsDependencyGateway.PreparationTask task) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.SHIPMENT_TASK_STATUS);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.CONFIRMING_PREPARATION) return;
    LogisticsTaskReference reference =
        taskRepository.findByLine_Id(line.getId()).orElseThrow(() -> malformed("Shipment line has no task reference"));
    requireDoneTask(document, reference, task);

    OffsetDateTime completedAt = now();
    attempt.confirm(taskDigest("SHIPMENT_TASK_STATUS_RESPONSE", task), completedAt);
    reference.markDone(task.taskVersion(), task.doneAt());
    List<LogisticsEquipmentHoldReference> holds = holds(line);
    if (holds.isEmpty()) {
      createShipmentConfirmAttempt(document, line, completedAt);
      return;
    }
    for (LogisticsEquipmentHoldReference hold : holds) {
      String operation = LogisticsDocumentService.holdCommitOperation(hold.getHoldId());
      createAttemptIfMissing(
          document,
          line,
          LogisticsTargetService.ASSET,
          operation,
          LogisticsCommandChecksum.sha256(
              operation,
              List.of(
                  hold.getHoldId().toString(),
                  Long.toString(hold.getHoldVersion()),
                  document.getId().toString(),
                  line.getId().toString())),
          completedAt);
    }
  }

  @Transactional
  public void confirmHoldCommit(UUID operationId, LogisticsDependencyGateway.EquipmentHold result) {
    LogisticsExternalAttempt attempt = attemptByPrefix(operationId, LogisticsDocumentService.SHIPMENT_HOLD_COMMIT_PREFIX);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.CONFIRMING_PREPARATION) return;
    LogisticsEquipmentHoldReference hold = holdForOperation(attempt, LogisticsDocumentService.SHIPMENT_HOLD_COMMIT_PREFIX);
    requireCommittedHold(hold, result);

    OffsetDateTime completedAt = now();
    attempt.confirm(holdDigest("SHIPMENT_HOLD_COMMIT_RESPONSE", result), completedAt);
    hold.commit(result.version(), completedAt);
    if (allHoldsCommitted(document, line)) createShipmentConfirmAttempt(document, line, completedAt);
  }

  @Transactional
  public void confirmShipmentEffect(UUID operationId, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.SHIPMENT_ASSET_CONFIRM);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.CONFIRMING_PREPARATION) return;
    LogisticsGuard guard = activeGuard(line);
    requireShippedSnapshot(document, line, guard, snapshot);

    OffsetDateTime completedAt = now();
    attempt.confirm(snapshotDigest("SHIPMENT_ASSET_CONFIRM_RESPONSE", snapshot), completedAt);
    guard.recordObservedAssetVersion(snapshot.version());
    createLeaseReleaseAttempt(document, line, guard, completedAt);
  }

  @Transactional
  public void confirmTaskCancellation(UUID operationId, LogisticsDependencyGateway.PreparationTask task) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.SHIPMENT_TASK_CANCEL);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.CANCELLING) return;
    LogisticsTaskReference reference =
        taskRepository.findByLine_Id(line.getId()).orElseThrow(() -> malformed("Shipment cancellation has no task"));
    requireCancelledTask(document, reference, task);
    OffsetDateTime completedAt = now();
    attempt.confirm(taskDigest("SHIPMENT_TASK_CANCEL_RESPONSE", task), completedAt);
    reference.cancel(task.taskVersion());
    finishCancellationIfComplete(document);
  }

  @Transactional
  public void confirmHoldRelease(UUID operationId, LogisticsDependencyGateway.EquipmentHold result) {
    LogisticsExternalAttempt attempt = attemptByPrefix(operationId, LogisticsDocumentService.SHIPMENT_HOLD_RELEASE_PREFIX);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.CANCELLING) return;
    LogisticsEquipmentHoldReference hold = holdForOperation(attempt, LogisticsDocumentService.SHIPMENT_HOLD_RELEASE_PREFIX);
    requireReleasedHold(hold, result);
    OffsetDateTime completedAt = now();
    attempt.confirm(holdDigest("SHIPMENT_HOLD_RELEASE_RESPONSE", result), completedAt);
    hold.release(result.version(), completedAt);
    finishCancellationIfComplete(document);
  }

  @Transactional
  public void confirmLeaseRelease(UUID operationId, LogisticsDependencyGateway.OperationLease lease) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.SHIPMENT_ASSET_LEASE_RELEASE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.CONFIRMING_PREPARATION
        && document.getState() != LogisticsDocumentState.CANCELLING) return;
    LogisticsGuard guard = activeGuard(line);
    requireReleasedLease(line, guard, lease);
    OffsetDateTime completedAt = now();
    attempt.confirm(leaseDigest("SHIPMENT_ASSET_LEASE_RELEASE_RESPONSE", lease), completedAt);
    guard.release();
    if (document.getState() == LogisticsDocumentState.CONFIRMING_PREPARATION) {
      finishShipmentIfComplete(document);
    } else {
      finishCancellationIfComplete(document);
    }
  }

  @Transactional
  public void recordFailure(UUID operationId, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt =
        attemptRepository
            .findByOperationId(operationId)
            .orElseThrow(() -> new IllegalArgumentException("External attempt is missing"));
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    if (!isShipmentWorkState(document.getState())) return;
    LogisticsDocumentLine line = requiredLine(attempt);
    OffsetDateTime completedAt = now();
    String responseDigest =
        LogisticsCommandChecksum.sha256(
            "SHIPMENT_FAILURE", List.of(attempt.getOperationType(), exception.kind().name()));
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      attempt.reject(responseDigest, completedAt);
      markConflict(line);
      document.shipmentConflict();
      documentRepository.saveAndFlush(document);
      eventStore.append(
          document,
          lineCount(document),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.SHIPMENT_CONFLICT,
          "DEPENDENCY_REJECTED");
      return;
    }
    if (exception.kind() == LogisticsDependencyException.FailureKind.CONFIGURATION
        || attempt.getRetryCount() >= 3) {
      attempt.requireReconciliation(responseDigest, completedAt);
      markReconciliationRequired(line);
      document.shipmentRequiresReconciliation();
      documentRepository.saveAndFlush(document);
      if (reconciliationRepository
          .findByDocument_IdAndLine_IdAndState(document.getId(), line.getId(), LogisticsReconciliationState.OPEN)
          .isEmpty()) {
        reconciliationRepository.save(
            LogisticsReconciliation.open(document, line, "SHIPMENT_DEPENDENCY_UNKNOWN", completedAt));
      }
      eventStore.append(
          document,
          lineCount(document),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.SHIPMENT_RECONCILIATION_REQUIRED,
          "DEPENDENCY_UNKNOWN");
      return;
    }
    attempt.retry(completedAt.plusSeconds(retryDelaySeconds(attempt.getRetryCount())));
  }

  private Optional<Work> preparationWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    if (LogisticsDocumentService.SHIPMENT_ASSET_SNAPSHOT.equals(attempt.getOperationType())) {
      return Optional.of(Work.snapshot(attempt.getOperationId(), line.getAssetId()));
    }
    if (LogisticsDocumentService.SHIPMENT_ASSET_LEASE_ACQUIRE.equals(attempt.getOperationType())) {
      return Optional.of(
          Work.lease(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              line.getAssetId(),
              line.getAssetVersion(),
              line.getRentalOrderId()));
    }
    if (attempt.getOperationType().startsWith(LogisticsDocumentService.SHIPMENT_HOLD_ACQUIRE_PREFIX)) {
      Allocation allocation =
          allocationForOperation(line, attempt.getOperationType(), LogisticsDocumentService.SHIPMENT_HOLD_ACQUIRE_PREFIX);
      return Optional.of(
          Work.holdAcquire(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              document.getWarehouseId(),
              allocation));
    }
    if (LogisticsDocumentService.SHIPMENT_TASK_REGISTER.equals(attempt.getOperationType())) {
      LogisticsTaskReference task =
          taskRepository.findByLine_Id(line.getId()).orElseThrow(() -> malformed("Shipment registration has no task"));
      return Optional.of(
          Work.taskRegister(
              attempt.getOperationId(), document.getWarehouseId(), task.getExternalTaskId()));
    }
    return Optional.empty();
  }

  private Optional<Work> confirmationWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    if (LogisticsDocumentService.SHIPMENT_TASK_STATUS.equals(attempt.getOperationType())) {
      LogisticsTaskReference task =
          taskRepository.findByLine_Id(line.getId()).orElseThrow(() -> malformed("Shipment confirmation has no task"));
      return Optional.of(Work.taskStatus(attempt.getOperationId(), task.getExternalTaskId()));
    }
    if (attempt.getOperationType().startsWith(LogisticsDocumentService.SHIPMENT_HOLD_COMMIT_PREFIX)) {
      LogisticsEquipmentHoldReference hold =
          holdForOperation(attempt, LogisticsDocumentService.SHIPMENT_HOLD_COMMIT_PREFIX);
      return Optional.of(
          Work.holdCommand(
              attempt.getOperationId(),
              LogisticsDependencyGateway.EquipmentHoldAction.COMMIT,
              hold.getHoldId(),
              hold.getHoldVersion(),
              document.getId(),
              line.getId()));
    }
    if (LogisticsDocumentService.SHIPMENT_ASSET_CONFIRM.equals(attempt.getOperationType())) {
      LogisticsGuard guard = activeGuard(line);
      return Optional.of(
          Work.effect(
              attempt.getOperationId(),
              LogisticsDependencyGateway.AssetEffect.SHIPMENT_CONFIRM,
              document.getId(),
              line.getId(),
              line.getAssetId(),
              guard.getObservedAssetVersion(),
              guard.getLeaseId(),
              guard.getFenceToken(),
              null));
    }
    if (LogisticsDocumentService.SHIPMENT_ASSET_LEASE_RELEASE.equals(attempt.getOperationType())) {
      return Optional.of(releaseWork(attempt, document, line));
    }
    return Optional.empty();
  }

  private Optional<Work> cancellationWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    if (LogisticsDocumentService.SHIPMENT_TASK_CANCEL.equals(attempt.getOperationType())) {
      LogisticsTaskReference task =
          taskRepository.findByLine_Id(line.getId()).orElseThrow(() -> malformed("Shipment cancellation has no task"));
      return Optional.of(
          Work.taskCancel(attempt.getOperationId(), task.getExternalTaskId(), task.getTaskVersion()));
    }
    if (attempt.getOperationType().startsWith(LogisticsDocumentService.SHIPMENT_HOLD_RELEASE_PREFIX)) {
      LogisticsEquipmentHoldReference hold =
          holdForOperation(attempt, LogisticsDocumentService.SHIPMENT_HOLD_RELEASE_PREFIX);
      return Optional.of(
          Work.holdCommand(
              attempt.getOperationId(),
              LogisticsDependencyGateway.EquipmentHoldAction.RELEASE,
              hold.getHoldId(),
              hold.getHoldVersion(),
              document.getId(),
              line.getId()));
    }
    if (LogisticsDocumentService.SHIPMENT_ASSET_LEASE_RELEASE.equals(attempt.getOperationType())) {
      return Optional.of(releaseWork(attempt, document, line));
    }
    return Optional.empty();
  }

  private Work releaseWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    LogisticsGuard guard = activeGuard(line);
    return Work.leaseRelease(
        attempt.getOperationId(),
        document.getId(),
        line.getId(),
        guard.getLeaseId(),
        guard.getLeaseVersion(),
        guard.getFenceToken());
  }

  private void createTaskRegistrationAttempt(
      LogisticsDocument document, LogisticsDocumentLine line, OffsetDateTime createdAt) {
    LogisticsTaskReference reference =
        taskRepository
            .findByLine_Id(line.getId())
            .orElseGet(
                () ->
                    taskRepository.save(
                        LogisticsTaskReference.pending(
                            document,
                            line,
                            externalTaskId(document, line),
                            document.getWarehouseId(),
                            createdAt)));
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.TASK_BOARD,
        LogisticsDocumentService.SHIPMENT_TASK_REGISTER,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.SHIPMENT_TASK_REGISTER,
            List.of(
                document.getWarehouseId().toString(),
                reference.getExternalTaskId().toString(),
                "0",
                "<null>")),
        createdAt);
  }

  private void createShipmentConfirmAttempt(
      LogisticsDocument document, LogisticsDocumentLine line, OffsetDateTime createdAt) {
    LogisticsGuard guard = activeGuard(line);
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.SHIPMENT_ASSET_CONFIRM,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.SHIPMENT_ASSET_CONFIRM,
            List.of(
                line.getAssetId().toString(),
                Long.toString(guard.getObservedAssetVersion()),
                guard.getLeaseId().toString(),
                Long.toString(guard.getFenceToken()),
                document.getId().toString(),
                line.getId().toString())),
        createdAt);
  }

  private void createLeaseReleaseAttempt(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      OffsetDateTime createdAt) {
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.SHIPMENT_ASSET_LEASE_RELEASE,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.SHIPMENT_ASSET_LEASE_RELEASE,
            List.of(
                guard.getLeaseId().toString(),
                Long.toString(guard.getLeaseVersion()),
                Long.toString(guard.getFenceToken()),
                document.getId().toString(),
                line.getId().toString())),
        createdAt);
  }

  private void createAttemptIfMissing(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsTargetService target,
      String operation,
      String digest,
      OffsetDateTime createdAt) {
    if (attemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(document.getId(), line.getId(), operation)
        .isPresent()) return;
    attemptRepository.save(
        LogisticsExternalAttempt.create(
            document, line, target, operation, digest, document.getCorrelationId(), null, createdAt));
  }

  private boolean allHoldsAcquired(LogisticsDocument document, LogisticsDocumentLine line) {
    for (Allocation allocation : allocations(line)) {
      Optional<LogisticsExternalAttempt> attempt =
          attemptRepository.findByDocument_IdAndLine_IdAndOperationType(
              document.getId(), line.getId(), LogisticsDocumentService.holdAcquireOperation(allocation.equipmentId()));
      if (attempt.isEmpty() || attempt.get().getResult() != LogisticsExternalAttemptResult.CONFIRMED) return false;
    }
    return true;
  }

  private boolean allTasksRegistered(LogisticsDocument document) {
    List<LogisticsDocumentLine> lines = lines(document.getId());
    if (lines.isEmpty()) return false;
    for (LogisticsDocumentLine line : lines) {
      Optional<LogisticsTaskReference> task = taskRepository.findByLine_Id(line.getId());
      if (task.isEmpty() || task.get().getTaskState() != LogisticsTaskReferenceState.REGISTERED) return false;
      Optional<LogisticsExternalAttempt> attempt =
          attemptRepository.findByDocument_IdAndLine_IdAndOperationType(
              document.getId(), line.getId(), LogisticsDocumentService.SHIPMENT_TASK_REGISTER);
      if (attempt.isEmpty() || attempt.get().getResult() != LogisticsExternalAttemptResult.CONFIRMED) return false;
    }
    return true;
  }

  private boolean allHoldsCommitted(LogisticsDocument document, LogisticsDocumentLine line) {
    for (LogisticsEquipmentHoldReference hold : holds(line)) {
      Optional<LogisticsExternalAttempt> attempt =
          attemptRepository.findByDocument_IdAndLine_IdAndOperationType(
              document.getId(), line.getId(), LogisticsDocumentService.holdCommitOperation(hold.getHoldId()));
      if (attempt.isEmpty()
          || attempt.get().getResult() != LogisticsExternalAttemptResult.CONFIRMED
          || hold.getHoldState() != LogisticsEquipmentHoldState.COMMITTED) return false;
    }
    return true;
  }

  private void finishShipmentIfComplete(LogisticsDocument document) {
    for (LogisticsDocumentLine line : lines(document.getId())) {
      Optional<LogisticsGuard> guard = guardRepository.findByLine_Id(line.getId());
      Optional<LogisticsExternalAttempt> release =
          attemptRepository.findByDocument_IdAndLine_IdAndOperationType(
              document.getId(), line.getId(), LogisticsDocumentService.SHIPMENT_ASSET_LEASE_RELEASE);
      if (guard.isEmpty()
          || guard.get().getGuardState() != LogisticsGuardState.RELEASED
          || release.isEmpty()
          || release.get().getResult() != LogisticsExternalAttemptResult.CONFIRMED) return;
    }
    document.ship();
    documentRepository.saveAndFlush(document);
    eventStore.append(
        document,
        lineCount(document),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.SHIPMENT_PREPARATION_CONFIRMED,
        null);
  }

  private void finishCancellationIfComplete(LogisticsDocument document) {
    for (LogisticsTaskReference task : taskRepository.findAllByDocument_IdOrderByCreatedAtAsc(document.getId())) {
      if (task.getTaskState() != LogisticsTaskReferenceState.CANCELLED) return;
    }
    for (LogisticsEquipmentHoldReference hold : holdRepository.findAllByDocument_IdOrderByCreatedAtAsc(document.getId())) {
      if (hold.getHoldState() != LogisticsEquipmentHoldState.RELEASED) return;
    }
    for (LogisticsDocumentLine line : lines(document.getId())) {
      Optional<LogisticsGuard> guard = guardRepository.findByLine_Id(line.getId());
      if (guard.isPresent() && guard.get().getGuardState() != LogisticsGuardState.RELEASED) return;
    }
    document.cancelShipment();
    documentRepository.saveAndFlush(document);
    eventStore.append(
        document,
        lineCount(document),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.SHIPMENT_CANCELLED,
        null);
  }

  private Optional<LogisticsEquipmentHoldReference> findHoldById(UUID holdId) {
    return holdRepository.findByHoldId(holdId);
  }

  private LogisticsEquipmentHoldReference holdForOperation(
      LogisticsExternalAttempt attempt, String prefix) {
    UUID holdId = idAfterPrefix(attempt.getOperationType(), prefix);
    return findHoldById(holdId).orElseThrow(() -> malformed("Shipment hold attempt has no local reference"));
  }

  private LogisticsExternalAttempt attempt(UUID operationId, String operationType) {
    LogisticsExternalAttempt attempt =
        attemptRepository
            .findByOperationId(operationId)
            .orElseThrow(() -> new IllegalArgumentException("External attempt is missing"));
    if (!operationType.equals(attempt.getOperationType())) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
  }

  private LogisticsExternalAttempt attemptByPrefix(UUID operationId, String prefix) {
    LogisticsExternalAttempt attempt =
        attemptRepository
            .findByOperationId(operationId)
            .orElseThrow(() -> new IllegalArgumentException("External attempt is missing"));
    if (!attempt.getOperationType().startsWith(prefix)) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
  }

  private LogisticsGuard activeGuard(LogisticsDocumentLine line) {
    LogisticsGuard guard =
        guardRepository.findByLine_Id(line.getId()).orElseThrow(() -> malformed("Shipment line has no asset guard"));
    if (guard.getGuardState() != LogisticsGuardState.ACTIVE
        || guard.getLeaseId() == null
        || guard.getLeaseVersion() == null
        || guard.getFenceToken() == null
        || guard.getObservedAssetVersion() == null) {
      throw malformed("Shipment asset guard is inactive or incomplete");
    }
    return guard;
  }

  private List<LogisticsEquipmentHoldReference> holds(LogisticsDocumentLine line) {
    return holdRepository.findAllByLine_IdOrderByCreatedAtAsc(line.getId());
  }

  private List<LogisticsDocumentLine> lines(UUID documentId) {
    return lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
  }

  private int lineCount(LogisticsDocument document) {
    return lines(document.getId()).size();
  }

  private static LogisticsDocumentLine requiredLine(LogisticsExternalAttempt attempt) {
    if (attempt.getLine() == null) throw malformed("Shipment attempt has no line");
    return attempt.getLine();
  }

  private static boolean isShipmentWorkState(LogisticsDocumentState state) {
    return state == LogisticsDocumentState.PREPARING
        || state == LogisticsDocumentState.CONFIRMING_PREPARATION
        || state == LogisticsDocumentState.CANCELLING;
  }

  private static void requireFreeSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())
        || snapshot.version() != line.getAssetVersion()
        || !document.getWarehouseId().equals(snapshot.warehouseId())
        || !"FREE".equals(snapshot.status())) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
          "Asset-service rejected the shipment preconditions");
    }
  }

  private static void requireShippedSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())
        || !document.getWarehouseId().equals(snapshot.warehouseId())
        || snapshot.version() <= guard.getObservedAssetVersion()
        || !"RENTED".equals(snapshot.status())) {
      throw malformed("Asset-service returned malformed shipment confirmation truth");
    }
  }

  private static void requireSnapshotShape(LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    if (snapshot == null
        || snapshot.assetId() == null
        || snapshot.warehouseId() == null
        || snapshot.version() < 0
        || snapshot.status() == null
        || snapshot.contents() == null) {
      throw malformed("Asset-service returned malformed rental-item truth");
    }
  }

  private static void requireActiveLease(
      LogisticsDocumentLine line, LogisticsDependencyGateway.OperationLease lease) {
    if (lease == null
        || lease.leaseId() == null
        || !line.getAssetId().equals(lease.rentalItemId())
        || lease.version() < 0
        || lease.fencingToken() < 1
        || !"ACTIVE".equals(lease.state())
        || lease.expiresAt() == null) {
      throw malformed("Asset-service returned malformed operation lease");
    }
  }

  private static void requireReleasedLease(
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.OperationLease lease) {
    if (lease == null
        || !guard.getLeaseId().equals(lease.leaseId())
        || !line.getAssetId().equals(lease.rentalItemId())
        || lease.version() < guard.getLeaseVersion()
        || lease.fencingToken() != guard.getFenceToken()
        || !"RELEASED".equals(lease.state())
        || lease.expiresAt() == null) {
      throw malformed("Asset-service returned malformed released operation lease");
    }
  }

  private static void requireActiveHold(LogisticsDependencyGateway.EquipmentHold hold) {
    if (hold == null
        || hold.holdId() == null
        || hold.version() < 0
        || !"ACTIVE".equals(hold.state())
        || hold.expiresAt() == null
        || hold.committedAt() != null) {
      throw malformed("Asset-service returned malformed active equipment hold");
    }
  }

  private static void requireCommittedHold(
      LogisticsEquipmentHoldReference expected, LogisticsDependencyGateway.EquipmentHold actual) {
    if (actual == null
        || !expected.getHoldId().equals(actual.holdId())
        || actual.version() < expected.getHoldVersion()
        || !"COMMITTED".equals(actual.state())
        || actual.expiresAt() == null
        || actual.committedAt() == null) {
      throw malformed("Asset-service returned malformed committed equipment hold");
    }
  }

  private static void requireReleasedHold(
      LogisticsEquipmentHoldReference expected, LogisticsDependencyGateway.EquipmentHold actual) {
    if (actual == null
        || !expected.getHoldId().equals(actual.holdId())
        || actual.version() < expected.getHoldVersion()
        || !"RELEASED".equals(actual.state())
        || actual.expiresAt() == null) {
      throw malformed("Asset-service returned malformed released equipment hold");
    }
  }

  private static void requireRegisteredTask(
      LogisticsDocument document,
      LogisticsTaskReference reference,
      LogisticsDependencyGateway.PreparationTask task) {
    if (task == null
        || task.taskId() == null
        || task.taskVersion() < 0
        || !document.getWarehouseId().equals(task.warehouseId())
        || !reference.getExternalTaskId().equals(task.externalTaskId())
        || !"ACTIVE".equals(task.status())) {
      throw malformed("Task-board returned malformed preparation registration");
    }
  }

  private static void requireDoneTask(
      LogisticsDocument document,
      LogisticsTaskReference reference,
      LogisticsDependencyGateway.PreparationTask task) {
    if (task == null
        || !reference.getTaskId().equals(task.taskId())
        || task.taskVersion() < reference.getTaskVersion()
        || !document.getWarehouseId().equals(task.warehouseId())
        || !reference.getExternalTaskId().equals(task.externalTaskId())
        || !"DONE".equals(task.status())
        || task.doneAt() == null) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
          "Task-board has not confirmed shipment preparation");
    }
  }

  private static void requireCancelledTask(
      LogisticsDocument document,
      LogisticsTaskReference reference,
      LogisticsDependencyGateway.PreparationTask task) {
    if (task == null
        || !reference.getTaskId().equals(task.taskId())
        || task.taskVersion() < reference.getTaskVersion()
        || !document.getWarehouseId().equals(task.warehouseId())
        || !reference.getExternalTaskId().equals(task.externalTaskId())
        || !"CANCELLED".equals(task.status())) {
      throw malformed("Task-board returned malformed cancellation truth");
    }
  }

  private static List<Allocation> allocations(LogisticsDocumentLine line) {
    JsonNode snapshot = line.getSourceAllocationSnapshot();
    JsonNode values = snapshot == null ? null : snapshot.get("allocations");
    if (values == null || !values.isArray()) throw malformed("Shipment allocation snapshot is malformed");
    List<Allocation> allocations = new ArrayList<>();
    HashSet<UUID> equipmentIds = new HashSet<>();
    for (JsonNode value : values) {
      JsonNode equipmentId = value.get("equipmentId");
      JsonNode quantity = value.get("quantity");
      JsonNode expectedVersion = value.get("expectedStockVersion");
      if (equipmentId == null
          || !equipmentId.isTextual()
          || quantity == null
          || !quantity.isIntegralNumber()
          || quantity.longValue() < 1
          || expectedVersion == null
          || !expectedVersion.isIntegralNumber()
          || expectedVersion.longValue() < 0) {
        throw malformed("Shipment allocation snapshot is malformed");
      }
      try {
        UUID id = UUID.fromString(equipmentId.textValue());
        if (!equipmentIds.add(id)) throw malformed("Shipment allocation snapshot has duplicate equipment");
        allocations.add(new Allocation(id, quantity.longValue(), expectedVersion.longValue()));
      } catch (IllegalArgumentException exception) {
        throw malformed("Shipment allocation snapshot has an invalid equipment identifier");
      }
    }
    return allocations;
  }

  private static Allocation allocationForOperation(
      LogisticsDocumentLine line, String operation, String prefix) {
    UUID equipmentId = idAfterPrefix(operation, prefix);
    return allocations(line).stream()
        .filter(value -> equipmentId.equals(value.equipmentId()))
        .findFirst()
        .orElseThrow(() -> malformed("Shipment hold operation has no allocation"));
  }

  private static UUID idAfterPrefix(String value, String prefix) {
    if (value == null || !value.startsWith(prefix)) throw malformed("Shipment operation is invalid");
    try {
      return UUID.fromString(value.substring(prefix.length()));
    } catch (IllegalArgumentException exception) {
      throw malformed("Shipment operation identifier is invalid");
    }
  }

  private static ObjectNode contentsSnapshot(List<LogisticsDependencyGateway.EquipmentContent> contents) {
    if (contents == null) throw malformed("Asset-service returned missing contents");
    HashSet<UUID> ids = new HashSet<>();
    ArrayNode values = JsonNodeFactory.instance.arrayNode();
    for (LogisticsDependencyGateway.EquipmentContent content : contents) {
      if (content == null
          || content.equipmentId() == null
          || content.quantity() < 0
          || !ids.add(content.equipmentId())) {
        throw malformed("Asset-service returned malformed equipment contents");
      }
      ObjectNode value = values.addObject();
      value.put("equipmentId", content.equipmentId().toString());
      value.put("quantity", content.quantity());
    }
    ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
    snapshot.set("contents", values);
    return snapshot;
  }

  private static UUID externalTaskId(LogisticsDocument document, LogisticsDocumentLine line) {
    return UUID.nameUUIDFromBytes(
        ("rwms:shipment:" + document.getId() + ":" + line.getId()).getBytes(StandardCharsets.UTF_8));
  }

  private void markConflict(LogisticsDocumentLine line) {
    if (line.getState() != dev.buhanzaz.rwms.logistics.domain.LogisticsLineState.CONFLICT
        && line.getState() != dev.buhanzaz.rwms.logistics.domain.LogisticsLineState.CANCELLED) {
      line.conflict();
    }
    guardRepository.findByLine_Id(line.getId()).ifPresent(ShipmentWorkflowStore::conflictGuard);
    for (LogisticsEquipmentHoldReference hold : holds(line)) {
      if (hold.getHoldState() != LogisticsEquipmentHoldState.RELEASED) hold.conflict();
    }
    taskRepository.findByLine_Id(line.getId()).ifPresent(ShipmentWorkflowStore::conflictTask);
  }

  private void markReconciliationRequired(LogisticsDocumentLine line) {
    guardRepository.findByLine_Id(line.getId()).ifPresent(ShipmentWorkflowStore::reconcileGuard);
    for (LogisticsEquipmentHoldReference hold : holds(line)) {
      if (hold.getHoldState() != LogisticsEquipmentHoldState.RELEASED) hold.requireReconciliation();
    }
    taskRepository.findByLine_Id(line.getId()).ifPresent(ShipmentWorkflowStore::reconcileTask);
  }

  private static void conflictGuard(LogisticsGuard guard) {
    if (guard.getGuardState() == LogisticsGuardState.ACTIVE) guard.conflict();
  }

  private static void reconcileGuard(LogisticsGuard guard) {
    if (guard.getGuardState() == LogisticsGuardState.ACTIVE) guard.requireReconciliation();
  }

  private static void conflictTask(LogisticsTaskReference task) {
    if (task.getTaskState() != LogisticsTaskReferenceState.CANCELLED) task.conflict();
  }

  private static void reconcileTask(LogisticsTaskReference task) {
    if (task.getTaskState() != LogisticsTaskReferenceState.CANCELLED) task.requireReconciliation();
  }

  private static String snapshotDigest(
      String operation, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    List<String> values = new ArrayList<>();
    values.add(snapshot.assetId().toString());
    values.add(Long.toString(snapshot.version()));
    values.add(snapshot.warehouseId().toString());
    values.add(snapshot.status());
    for (LogisticsDependencyGateway.EquipmentContent content : snapshot.contents()) {
      values.add(content.equipmentId().toString());
      values.add(Long.toString(content.quantity()));
    }
    return LogisticsCommandChecksum.sha256(operation, values);
  }

  private static String leaseDigest(String operation, LogisticsDependencyGateway.OperationLease lease) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            lease.leaseId().toString(),
            Long.toString(lease.version()),
            lease.rentalItemId().toString(),
            Long.toString(lease.fencingToken()),
            lease.state(),
            lease.expiresAt().toInstant().toString()));
  }

  private static String holdDigest(String operation, LogisticsDependencyGateway.EquipmentHold hold) {
    List<String> values = new ArrayList<>();
    values.add(hold.holdId().toString());
    values.add(Long.toString(hold.version()));
    values.add(hold.state());
    values.add(hold.expiresAt().toInstant().toString());
    values.add(hold.committedAt() == null ? null : hold.committedAt().toInstant().toString());
    return LogisticsCommandChecksum.sha256(
        operation, values);
  }

  private static String taskDigest(String operation, LogisticsDependencyGateway.PreparationTask task) {
    List<String> values = new ArrayList<>();
    values.add(task.taskId().toString());
    values.add(Long.toString(task.taskVersion()));
    values.add(task.warehouseId().toString());
    values.add(task.externalTaskId().toString());
    values.add(task.status());
    values.add(task.doneAt() == null ? null : task.doneAt().toInstant().toString());
    return LogisticsCommandChecksum.sha256(
        operation, values);
  }

  private static int retryDelaySeconds(int currentRetryCount) {
    return switch (currentRetryCount) {
      case 0 -> 1;
      case 1 -> 2;
      default -> 4;
    };
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static LogisticsDependencyException malformed(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

  private record Allocation(UUID equipmentId, long quantity, long expectedStockVersion) {}

  record Work(
      WorkType type,
      UUID operationId,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      UUID equipmentId,
      long quantity,
      long expectedStockVersion,
      UUID holdId,
      LogisticsDependencyGateway.EquipmentHoldAction holdAction,
      UUID externalTaskId,
      long expectedTaskVersion,
      LogisticsDependencyGateway.AssetEffect assetEffect,
      UUID rentalOrderId) {
    Work(
        WorkType type,
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID warehouseId,
        UUID assetId,
        long expectedAssetVersion,
        UUID leaseId,
        long expectedLeaseVersion,
        long fencingToken,
        UUID equipmentId,
        long quantity,
        long expectedStockVersion,
        UUID holdId,
        LogisticsDependencyGateway.EquipmentHoldAction holdAction,
        UUID externalTaskId,
        long expectedTaskVersion,
        LogisticsDependencyGateway.AssetEffect assetEffect) {
      this(
          type,
          operationId,
          documentId,
          lineId,
          warehouseId,
          assetId,
          expectedAssetVersion,
          leaseId,
          expectedLeaseVersion,
          fencingToken,
          equipmentId,
          quantity,
          expectedStockVersion,
          holdId,
          holdAction,
          externalTaskId,
          expectedTaskVersion,
          assetEffect,
          null);
    }

    static Work snapshot(UUID operationId, UUID assetId) {
      return new Work(
          WorkType.SNAPSHOT,
          operationId,
          null,
          null,
          null,
          assetId,
          -1,
          null,
          -1,
          -1,
          null,
          -1,
          -1,
          null,
          null,
          null,
          -1,
          null);
    }

    static Work lease(
        UUID operationId, UUID documentId, UUID lineId, UUID assetId, long expectedAssetVersion) {
      return lease(
          operationId,
          documentId,
          lineId,
          assetId,
          expectedAssetVersion,
          null);
    }

    static Work lease(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID rentalOrderId) {
      return new Work(
          WorkType.LEASE,
          operationId,
          documentId,
          lineId,
          null,
          assetId,
          expectedAssetVersion,
          null,
          -1,
          -1,
          null,
          -1,
          -1,
          null,
          null,
          null,
          -1,
          null,
          rentalOrderId);
    }

    static Work holdAcquire(
        UUID operationId, UUID documentId, UUID lineId, UUID warehouseId, Allocation allocation) {
      return new Work(
          WorkType.HOLD_ACQUIRE,
          operationId,
          documentId,
          lineId,
          warehouseId,
          null,
          -1,
          null,
          -1,
          -1,
          allocation.equipmentId(),
          allocation.quantity(),
          allocation.expectedStockVersion(),
          null,
          null,
          null,
          -1,
          null);
    }

    static Work taskRegister(UUID operationId, UUID warehouseId, UUID externalTaskId) {
      return new Work(
          WorkType.TASK_REGISTER,
          operationId,
          null,
          null,
          warehouseId,
          null,
          -1,
          null,
          -1,
          -1,
          null,
          -1,
          -1,
          null,
          null,
          externalTaskId,
          -1,
          null);
    }

    static Work taskStatus(UUID operationId, UUID externalTaskId) {
      return new Work(
          WorkType.TASK_STATUS,
          operationId,
          null,
          null,
          null,
          null,
          -1,
          null,
          -1,
          -1,
          null,
          -1,
          -1,
          null,
          null,
          externalTaskId,
          -1,
          null);
    }

    static Work taskCancel(UUID operationId, UUID externalTaskId, long expectedTaskVersion) {
      return new Work(
          WorkType.TASK_CANCEL,
          operationId,
          null,
          null,
          null,
          null,
          -1,
          null,
          -1,
          -1,
          null,
          -1,
          -1,
          null,
          null,
          externalTaskId,
          expectedTaskVersion,
          null);
    }

    static Work holdCommand(
        UUID operationId,
        LogisticsDependencyGateway.EquipmentHoldAction action,
        UUID holdId,
        long expectedHoldVersion,
        UUID documentId,
        UUID lineId) {
      return new Work(
          WorkType.HOLD_COMMAND,
          operationId,
          documentId,
          lineId,
          null,
          null,
          -1,
          null,
          -1,
          -1,
          null,
          -1,
          -1,
          holdId,
          action,
          null,
          expectedHoldVersion,
          null);
    }

    static Work effect(
        UUID operationId,
        LogisticsDependencyGateway.AssetEffect effect,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID leaseId,
        long fencingToken,
        UUID destinationWarehouseId) {
      return new Work(
          WorkType.EFFECT,
          operationId,
          documentId,
          lineId,
          destinationWarehouseId,
          assetId,
          expectedAssetVersion,
          leaseId,
          -1,
          fencingToken,
          null,
          -1,
          -1,
          null,
          null,
          null,
          -1,
          effect);
    }

    static Work leaseRelease(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID leaseId,
        long expectedLeaseVersion,
        long fencingToken) {
      return new Work(
          WorkType.LEASE_RELEASE,
          operationId,
          documentId,
          lineId,
          null,
          null,
          -1,
          leaseId,
          expectedLeaseVersion,
          fencingToken,
          null,
          -1,
          -1,
          null,
          null,
          null,
          -1,
          null);
    }
  }

  enum WorkType {
    SNAPSHOT,
    LEASE,
    HOLD_ACQUIRE,
    TASK_REGISTER,
    TASK_STATUS,
    HOLD_COMMAND,
    EFFECT,
    TASK_CANCEL,
    LEASE_RELEASE
  }
}
