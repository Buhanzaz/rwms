package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliation;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRepository;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transactional local half of the return-registration saga. Network calls are
 * deliberately made by {@link ReturnRegistrationProcessor} outside these
 * transactions; this class only claims and records durable truth.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ReturnRegistrationWorkflowStore {
  static final String RETURN_ASSET_SNAPSHOT = "RETURN_ASSET_SNAPSHOT";
  static final String RETURN_ASSET_LEASE_ACQUIRE = "RETURN_ASSET_LEASE_ACQUIRE";
  static final String RETURN_ASSET_INTAKE = "RETURN_ASSET_INTAKE";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsExternalAttemptRepository attemptRepository;
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsReconciliationRepository reconciliationRepository;
  private final LogisticsEventStore eventStore;

  Optional<Work> nextWork(UUID documentId) {
    if (documentId == null) return Optional.empty();
    OffsetDateTime now = now();
    for (LogisticsExternalAttempt attempt :
        attemptRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      LogisticsDocument document = attempt.getDocument();
      if (document.getState() != LogisticsDocumentState.REGISTERING || !attempt.isDue(now)) {
        continue;
      }
      LogisticsDocumentLine line = attempt.getLine();
      if (LogisticsDocumentService.RETURN_WAREHOUSE_IDENTITY.equals(attempt.getOperationType())) {
        return Optional.of(
            Work.warehouse(
                attempt.getOperationId(),
                document.getId(),
                document.getWarehouseId(),
                document.getCorrelationId()));
      }
      if (line == null) continue;
      if (RETURN_ASSET_SNAPSHOT.equals(attempt.getOperationType())) {
        return Optional.of(
            Work.snapshot(
                attempt.getOperationId(),
                document.getId(),
                line.getId(),
                line.getAssetId(),
                line.getAssetVersion(),
                document.getWarehouseId(),
                document.getCorrelationId()));
      }
      if (RETURN_ASSET_LEASE_ACQUIRE.equals(attempt.getOperationType())) {
        return Optional.of(
            Work.lease(
                attempt.getOperationId(),
                document.getId(),
                line.getId(),
                line.getAssetId(),
                line.getAssetVersion(),
                document.getCorrelationId(),
                line.getRentalOrderId()));
      }
      if (RETURN_ASSET_INTAKE.equals(attempt.getOperationType())) {
        Optional<LogisticsGuard> guard = guardRepository.findByLine_Id(line.getId());
        if (guard.isEmpty() || guard.get().getGuardState() != LogisticsGuardState.ACTIVE) {
          throw malformed("Return intake has no active asset guard");
        }
        return Optional.of(
            Work.intake(
                attempt.getOperationId(),
                document.getId(),
                line.getId(),
                line.getAssetId(),
                line.getAssetVersion(),
                guard.get().getLeaseId(),
                guard.get().getFenceToken(),
                document.getCorrelationId()));
      }
    }
    return Optional.empty();
  }

  @Transactional
  public void confirmWarehouse(
      UUID operationId, LogisticsDependencyGateway.WarehouseIdentity identity) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.RETURN_WAREHOUSE_IDENTITY);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    if (document.getState() != LogisticsDocumentState.REGISTERING) return;
    requireWarehouseIdentity(document, identity);

    OffsetDateTime completedAt = now();
    attempt.confirm(
        digest(
            "RETURN_WAREHOUSE_IDENTITY_RESPONSE",
            List.of(identity.id().toString(), Long.toString(identity.version()), Boolean.toString(identity.active()))),
        completedAt);
    for (LogisticsDocumentLine line : lines(document.getId())) {
      createLineAttemptIfMissing(
          document,
          line,
          LogisticsTargetService.ASSET,
          RETURN_ASSET_SNAPSHOT,
          digest(
              RETURN_ASSET_SNAPSHOT,
              List.of(line.getAssetId().toString(), Long.toString(line.getAssetVersion()))),
          completedAt);
    }
  }

  @Transactional
  public void confirmAssetSnapshot(
      UUID operationId, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    LogisticsExternalAttempt attempt = attempt(operationId, RETURN_ASSET_SNAPSHOT);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.REGISTERING) return;
    requireRentedSnapshot(document, line, snapshot);

    OffsetDateTime completedAt = now();
    line.captureExpectedContents(contentsSnapshot(snapshot.contents()));
    attempt.confirm(snapshotDigest("RETURN_ASSET_SNAPSHOT_RESPONSE", snapshot), completedAt);
    createLineAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        RETURN_ASSET_LEASE_ACQUIRE,
        digest(
            RETURN_ASSET_LEASE_ACQUIRE,
            List.of(
                line.getAssetId().toString(),
                Long.toString(line.getAssetVersion()),
                document.getId().toString(),
                line.getId().toString())),
        completedAt);
  }

  @Transactional
  public void confirmLease(UUID operationId, LogisticsDependencyGateway.OperationLease lease) {
    LogisticsExternalAttempt attempt = attempt(operationId, RETURN_ASSET_LEASE_ACQUIRE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.REGISTERING) return;
    requireActiveLease(line, lease);

    OffsetDateTime completedAt = now();
    attempt.confirm(
        digest(
            "RETURN_ASSET_LEASE_RESPONSE",
            List.of(
                lease.leaseId().toString(),
                Long.toString(lease.version()),
                lease.rentalItemId().toString(),
                Long.toString(lease.fencingToken()),
                lease.state(),
                lease.expiresAt().toInstant().toString())),
        completedAt);
    Optional<LogisticsGuard> current = guardRepository.findByLine_Id(line.getId());
    if (current.isPresent()) {
      LogisticsGuard guard = current.get();
      if (guard.getGuardState() != LogisticsGuardState.ACTIVE
          || !lease.leaseId().equals(guard.getLeaseId())
          || lease.fencingToken() != guard.getFenceToken()) {
        throw malformed("Return line already has a different asset guard");
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
    createLineAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        RETURN_ASSET_INTAKE,
        digest(
            RETURN_ASSET_INTAKE,
            List.of(
                line.getAssetId().toString(),
                Long.toString(line.getAssetVersion()),
                lease.leaseId().toString(),
                Long.toString(lease.fencingToken()),
                document.getId().toString(),
                line.getId().toString())),
        completedAt);
  }

  @Transactional
  public void confirmReturnIntake(
      UUID operationId, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    LogisticsExternalAttempt attempt = attempt(operationId, RETURN_ASSET_INTAKE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.REGISTERING) return;
    requireAfterRentSnapshot(document, line, snapshot);

    OffsetDateTime completedAt = now();
    line.captureFactualContents(contentsSnapshot(snapshot.contents()));
    attempt.confirm(snapshotDigest("RETURN_ASSET_INTAKE_RESPONSE", snapshot), completedAt);
    LogisticsGuard guard =
        guardRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> malformed("Return intake did not retain its asset guard"));
    guard.recordObservedAssetVersion(snapshot.version());

    finishRegistrationIfReady(document);
  }

  @Transactional
  public void recordFailure(UUID operationId, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt =
        attemptRepository
            .findByOperationId(operationId)
            .orElseThrow(() -> new IllegalArgumentException("External attempt is missing"));
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = attempt.getLine();
    if (document.getState() != LogisticsDocumentState.REGISTERING) return;

    OffsetDateTime completedAt = now();
    String responseDigest =
        digest(
            "RETURN_REGISTRATION_FAILURE",
            List.of(attempt.getOperationType(), exception.kind().name()));
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      attempt.reject(responseDigest, completedAt);
      if (line != null) {
        guardRepository.findByLine_Id(line.getId()).ifPresent(LogisticsGuard::conflict);
      }
      document.returnRegistrationConflict();
      documentRepository.saveAndFlush(document);
      eventStore.append(
          document,
          lines(document.getId()).size(),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.RETURN_CONFLICT,
          "DEPENDENCY_REJECTED");
      return;
    }

    if (exception.kind() == LogisticsDependencyException.FailureKind.CONFIGURATION
        || attempt.getRetryCount() >= 3) {
      attempt.requireReconciliation(responseDigest, completedAt);
      if (line != null) {
        guardRepository.findByLine_Id(line.getId()).ifPresent(LogisticsGuard::requireReconciliation);
      }
      document.returnRegistrationRequiresReconciliation();
      documentRepository.saveAndFlush(document);
      if (reconciliationRepository
          .findByDocument_IdAndLine_IdAndState(
              document.getId(), line == null ? null : line.getId(), LogisticsReconciliationState.OPEN)
          .isEmpty()) {
        reconciliationRepository.save(
            LogisticsReconciliation.open(
                document, line, "RETURN_REGISTRATION_DEPENDENCY_UNKNOWN", completedAt));
      }
      eventStore.append(
          document,
          lines(document.getId()).size(),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.RETURN_RECONCILIATION_REQUIRED,
          "DEPENDENCY_UNKNOWN");
      return;
    }

    attempt.retry(completedAt.plusSeconds(retryDelaySeconds(attempt.getRetryCount())));
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

  private void createLineAttemptIfMissing(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsTargetService targetService,
      String operationType,
      String requestDigest,
      OffsetDateTime createdAt) {
    if (attemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(document.getId(), line.getId(), operationType)
        .isEmpty()) {
      attemptRepository.save(
          LogisticsExternalAttempt.create(
              document,
              line,
              targetService,
              operationType,
              requestDigest,
              document.getCorrelationId(),
              null,
              createdAt));
    }
  }

  private boolean everyReturnIntakeConfirmed(LogisticsDocument document) {
    List<LogisticsDocumentLine> lines = lines(document.getId());
    if (lines.isEmpty()) return false;
    for (LogisticsDocumentLine line : lines) {
      Optional<LogisticsExternalAttempt> attempt =
          attemptRepository.findByDocument_IdAndLine_IdAndOperationType(
              document.getId(), line.getId(), RETURN_ASSET_INTAKE);
      if (attempt.isEmpty() || attempt.get().getResult() != LogisticsExternalAttemptResult.CONFIRMED) {
        return false;
      }
    }
    return true;
  }

  private void finishRegistrationIfReady(LogisticsDocument document) {
    if (document.getState() != LogisticsDocumentState.REGISTERING
        || !everyReturnIntakeConfirmed(document)) {
      return;
    }
    document.requireReturnInspection();
    documentRepository.saveAndFlush(document);
    eventStore.append(
        document,
        lines(document.getId()).size(),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.RETURN_INSPECTION_REQUIRED,
        null);
  }

  private List<LogisticsDocumentLine> lines(UUID documentId) {
    return lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
  }

  private static LogisticsDocumentLine requiredLine(LogisticsExternalAttempt attempt) {
    LogisticsDocumentLine line = attempt.getLine();
    if (line == null) throw malformed("Return line attempt has no line");
    return line;
  }

  private static void requireWarehouseIdentity(
      LogisticsDocument document, LogisticsDependencyGateway.WarehouseIdentity identity) {
    if (identity == null
        || !document.getWarehouseId().equals(identity.id())
        || identity.version() < 0
        || identity.timeZone() == null
        || identity.timeZone().isBlank()
        || identity.timeZone().length() > 64) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
          "Warehouse-service rejected the return origin");
    }
    try {
      ZoneId.of(identity.timeZone());
    } catch (DateTimeException exception) {
      throw malformed("Warehouse-service returned an invalid IANA timezone");
    }
  }

  private static void requireRentedSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())
        || snapshot.version() != line.getAssetVersion()
        || !document.getWarehouseId().equals(snapshot.warehouseId())
        || !"RENTED".equals(snapshot.status())) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
          "Asset-service rejected the return intake preconditions");
    }
  }

  private static void requireAfterRentSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())
        || !document.getWarehouseId().equals(snapshot.warehouseId())
        || !"AFTER_RENT".equals(snapshot.status())) {
      throw malformed("Asset-service returned malformed return-intake truth");
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
      throw malformed("Asset-service returned malformed operation lease truth");
    }
  }

  private static ObjectNode contentsSnapshot(List<LogisticsDependencyGateway.EquipmentContent> contents) {
    if (contents == null) throw malformed("Asset-service returned missing contents");
    HashSet<UUID> equipmentIds = new HashSet<>();
    ArrayNode values = JsonNodeFactory.instance.arrayNode();
    for (LogisticsDependencyGateway.EquipmentContent content : contents) {
      if (content == null
          || content.equipmentId() == null
          || content.quantity() < 0
          || !equipmentIds.add(content.equipmentId())) {
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

  private static String snapshotDigest(
      String operation, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    List<String> values = new ArrayList<>();
    values.add(snapshot.assetId().toString());
    values.add(Long.toString(snapshot.version()));
    values.add(snapshot.warehouseId().toString());
    values.add(snapshot.status());
    for (LogisticsDependencyGateway.EquipmentContent content : snapshot.contents()) {
      values.add(content.equipmentId().toString());
      values.add(Long.toString(content.quantity()));
    }
    return digest(operation, values);
  }

  private static String digest(String operation, List<String> values) {
    return LogisticsCommandChecksum.sha256(operation, values);
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

  record Work(
      WorkType type,
      UUID operationId,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID correlationId,
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
        long fencingToken,
        UUID correlationId) {
      this(
          type,
          operationId,
          documentId,
          lineId,
          warehouseId,
          assetId,
          expectedAssetVersion,
          leaseId,
          fencingToken,
          correlationId,
          null);
    }

    static Work warehouse(
        UUID operationId, UUID documentId, UUID warehouseId, UUID correlationId) {
      return new Work(
          WorkType.WAREHOUSE_IDENTITY,
          operationId,
          documentId,
          null,
          warehouseId,
          null,
          -1,
          null,
          -1,
          correlationId);
    }

    static Work snapshot(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID warehouseId,
        UUID correlationId) {
      return new Work(
          WorkType.ASSET_SNAPSHOT,
          operationId,
          documentId,
          lineId,
          warehouseId,
          assetId,
          expectedAssetVersion,
          null,
          -1,
          correlationId);
    }

    static Work lease(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID correlationId) {
      return lease(
          operationId,
          documentId,
          lineId,
          assetId,
          expectedAssetVersion,
          correlationId,
          null);
    }

    static Work lease(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID correlationId,
        UUID rentalOrderId) {
      return new Work(
          WorkType.ASSET_LEASE,
          operationId,
          documentId,
          lineId,
          null,
          assetId,
          expectedAssetVersion,
          null,
          -1,
          correlationId,
          rentalOrderId);
    }

    static Work intake(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID leaseId,
        long fencingToken,
        UUID correlationId) {
      return new Work(
          WorkType.ASSET_RETURN_INTAKE,
          operationId,
          documentId,
          lineId,
          null,
          assetId,
          expectedAssetVersion,
          leaseId,
          fencingToken,
          correlationId);
    }
  }

  enum WorkType {
    WAREHOUSE_IDENTITY,
    ASSET_SNAPSHOT,
    ASSET_LEASE,
    ASSET_RETURN_INTAKE
  }
}
