package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceOperation;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSuccessor;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceOperationRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSuccessorRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns immutable inventory-publication source identity, idempotency registration, replay mapping,
 * and the durable successor relation associated with a published source.
 */
@Component
final class InventoryPublicationSourceLifecycle {
  private final InventoryPublicationSourceOperationRepository operations;
  private final InventoryPublicationSourceRepository sources;
  private final InventoryPublicationSuccessorRepository successors;
  private final InventoryPublicationSourceOperationRegistrar registrar;
  private final MaintenanceJsonbCanonicalizer canonicalizer;
  private final ObjectMapper mapper;
  private final InventoryPublicationSuccessorActivator successorActivator;

  InventoryPublicationSourceLifecycle(
      InventoryPublicationSourceOperationRepository operations,
      InventoryPublicationSourceRepository sources,
      InventoryPublicationSuccessorRepository successors,
      InventoryPublicationSourceOperationRegistrar registrar,
      MaintenanceJsonbCanonicalizer canonicalizer,
      ObjectMapper mapper,
      InventoryPublicationSuccessorActivator successorActivator) {
    this.operations = operations;
    this.sources = sources;
    this.successors = successors;
    this.registrar = registrar;
    this.canonicalizer = canonicalizer;
    this.mapper = mapper;
    this.successorActivator = successorActivator;
  }

  InventoryPublicationSourceId sourceId(
      UUID inventoryId, InventoryPublicationApplyRequest request, UUID findingId) {
    return new InventoryPublicationSourceId(inventoryId, request.finalPlanVersion(), findingId);
  }

  String requestSha256(
      UUID inventoryId, UUID findingId, InventoryPublicationApplyRequest request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("inventoryId", inventoryId);
    value.put("findingId", findingId);
    value.put("warehouseId", request.warehouseId());
    value.put("finalPlanVersion", request.finalPlanVersion());
    value.put("finalPlanSha256", request.finalPlanSha256());
    value.put("findingRevision", request.findingRevision());
    value.put("assetId", request.assetId());
    value.put("assetVersion", request.assetVersion());
    value.put("planFingerprintSha256", request.planFingerprintSha256());
    value.put("priority", request.priority());
    value.put("movementToRepair", request.movementToRepair());
    value.put("movementScheduledDate", request.movementScheduledDate());
    value.put("repairScheduledDate", request.repairScheduledDate());
    value.put("snapshot", request.snapshot());
    value.put("media", request.media());
    value.put("snapshotSchemaVersion", request.snapshotSchemaVersion());
    value.put("strategy", request.strategy());
    value.put("selectedTargetKind", request.selectedTargetKind());
    value.put("selectedTargetId", request.selectedTargetId());
    return canonicalizer.sha256(value);
  }

  InventoryPublicationRegisteredSource registerAndLock(
      InventoryPublicationSourceId sourceId, String requestSha256, boolean discardOnRollback) {
    registerConcurrentSafe(() -> registrar.register(sourceId, requestSha256));
    if (discardOnRollback) {
      discardUnpublishedOperationOnRollback(sourceId, requestSha256);
    }
    InventoryPublicationSourceOperation operation = requireOperation(sourceId);
    requireSameRequest(requestSha256, operation.getRequestSha256());
    InventoryPublicationSource replay = sources.findByIdForUpdate(sourceId).orElse(null);
    if (replay != null) {
      requireSameRequest(requestSha256, replay.getRequestSha256());
    }
    return new InventoryPublicationRegisteredSource(operation, replay);
  }

  InventoryPublicationSource requireReplayOrNull(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationSource source = sources.findByIdForUpdate(sourceId).orElse(null);
    if (source != null) {
      requireSameRequest(requestSha256, source.getRequestSha256());
    }
    return source;
  }

  void requireRegisteredRequest(InventoryPublicationSourceId sourceId, String requestSha256) {
    requireSameRequest(requestSha256, requireOperation(sourceId).getRequestSha256());
  }

  boolean sourceBoundToDifferentRequest(InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationSourceOperation operation = operations.findByIdForUpdate(sourceId).orElse(null);
    if (operation != null && !requestSha256.equals(operation.getRequestSha256())) return true;
    InventoryPublicationSource source = sources.findByIdForUpdate(sourceId).orElse(null);
    return source != null && !requestSha256.equals(source.getRequestSha256());
  }

  boolean abortUnpublished(
      InventoryPublicationSourceId sourceId, String requestSha256, BooleanSupplier canDeleteIntent) {
    InventoryPublicationSourceOperation operation = operations.findByIdForUpdate(sourceId).orElse(null);
    if (operation == null) return true;
    if (!requestSha256.equals(operation.getRequestSha256())) return false;
    if (sources.findByIdForUpdate(sourceId).isPresent()) return false;
    if (!canDeleteIntent.getAsBoolean()) return false;
    operations.delete(operation);
    operations.flush();
    return true;
  }

  InventoryPublicationWorkflowResult persist(InventoryPublicationSourceWrite write) {
    InventoryPublicationSource source = sources.saveAndFlush(InventoryPublicationSource.create(
        write.sourceId(),
        write.request().warehouseId(),
        write.finding().findingRevision(),
        write.finding().assetId(),
        write.finding().assetVersion(),
        write.request().finalPlanSha256(),
        write.finding().planFingerprintSha256(),
        write.finding().snapshotSchemaVersion(),
        write.publication().rawSnapshot(),
        write.publication().rawMedia(),
        write.finding().priority(),
        write.finding().movementToRepair(),
        write.finding().movementScheduledDate(),
        write.finding().repairScheduledDate(),
        write.request().strategy().name(),
        enumName(write.request().selectedTargetKind()),
        write.request().selectedTargetId(),
        write.superseded() == null ? null : write.superseded().kind().name(),
        write.superseded() == null ? null : write.superseded().id(),
        write.outcome().name(),
        write.predecessorRepairId(),
        write(write.delta()),
        write.created().kind() == null ? null : write.created().kind().name(),
        write.created().id(),
        write.created().estimateId(),
        write.created().repairId(),
        write.requestSha256(),
        write.idempotencyKey()));
    if (write.outcome() == InventoryPublicationOutcome.SUCCESSOR) {
      MaintenanceRepair predecessor = write.predecessor();
      if (predecessor == null || write.created().repairId() == null) {
        throw new IllegalArgumentException("Inventory successor source binding is incomplete");
      }
      successors.saveAndFlush(InventoryPublicationSuccessor.waiting(
          write.sourceId(), predecessor.getId(), write.created().repairId()));
      if (write.terminalProof() != null) {
        successorActivator.releaseAfterTaskBoardCompletion(
            predecessor,
            write.terminalProof().eventId(),
            write.terminalProof().occurredAt());
      }
      if (write.acceptanceProof() != null) {
        successorActivator.releaseAfterAcceptance(
            predecessor,
            write.acceptanceProof().eventId(),
            write.acceptanceProof().occurredAt());
      }
    }
    return new InventoryPublicationWorkflowResult(result(source), false);
  }

  InventoryPublicationWorkflowResult replay(InventoryPublicationSource source) {
    return new InventoryPublicationWorkflowResult(result(source), true);
  }

  InventoryPublicationWorkflowResult replayRequired(
      InventoryPublicationSourceId sourceId, String requestSha256, String absentMessage) {
    InventoryPublicationSource source =
        sources.findByIdForUpdate(sourceId).orElseThrow(() -> new IllegalStateException(absentMessage));
    requireSameRequest(requestSha256, source.getRequestSha256());
    return replay(source);
  }

  UUID stableKey(String operation, InventoryPublicationSourceId source) {
    return UUID.nameUUIDFromBytes(
        (operation
                + ":"
                + source.getInventoryId()
                + ":"
                + source.getFinalPlanVersion()
                + ":"
                + source.getFindingId())
            .getBytes(StandardCharsets.UTF_8));
  }

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory publication value cannot be serialized", exception);
    }
  }

  private InventoryPublicationSourceOperation requireOperation(InventoryPublicationSourceId sourceId) {
    return operations
        .findByIdForUpdate(sourceId)
        .orElseThrow(
            () -> new IllegalStateException("Inventory publication source registration failed"));
  }

  private InventoryPublicationApplyResult result(InventoryPublicationSource source) {
    InventoryPublicationSourceReference reference = new InventoryPublicationSourceReference(
        source.getId().getInventoryId(),
        source.getId().getFinalPlanVersion(),
        source.getId().getFindingId(),
        source.getFindingRevision(),
        source.getFinalPlanSha256(),
        source.getPlanFingerprintSha256(),
        InventoryPublicationStrategy.valueOf(source.getStrategy()),
        enumValue(source.getSelectedTargetKind()),
        source.getSelectedTargetId(),
        enumValue(source.getSupersededTargetKind()),
        source.getSupersededTargetId());
    InventoryPublicationSuccessorStatus successor = null;
    if ("SUCCESSOR".equals(source.getPublicationOutcome())) {
      InventoryPublicationSuccessor relation = successors.findById(source.getId()).orElseThrow(
          () -> new IllegalStateException(
              "Inventory publication successor source has no durable relation"));
      successor = new InventoryPublicationSuccessorStatus(
          relation.getPredecessorRepairId(),
          InventoryPublicationSuccessorState.valueOf(relation.getState()),
          relation.getTerminalFact() == null
              ? null : InventoryPublicationTerminalFact.valueOf(relation.getTerminalFact()),
          relation.getTerminalFactEventId(),
          relation.getTerminalFactOccurredAt(),
          relation.getReleasedAt());
    }
    return new InventoryPublicationApplyResult(
        reference,
        InventoryPublicationOutcome.valueOf(source.getPublicationOutcome()),
        enumValue(source.getTargetKind()),
        source.getTargetId(),
        source.getEstimateId(),
        source.getRepairId(),
        successor,
        readDelta(source));
  }

  private InventoryPublicationDelta readDelta(InventoryPublicationSource source) {
    try {
      return mapper.readValue(source.getDeltaSnapshot(), InventoryPublicationDelta.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory publication delta is invalid", exception);
    }
  }

  private void discardUnpublishedOperationOnRollback(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) {
          registrar.discardIfUnpublished(sourceId, requestSha256);
        }
      }
    });
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the immutable source key; lock and validate it above.
    }
  }

  private static void requireSameRequest(String expected, String actual) {
    if (!expected.equals(actual)) {
      throw InventoryPublicationPlanValidation.conflict(
          "Completed inventory source is already bound to different publication input");
    }
  }

  private static String enumName(InventoryPublicationTargetKind value) {
    return value == null ? null : value.name();
  }

  private static InventoryPublicationTargetKind enumValue(String value) {
    return value == null ? null : InventoryPublicationTargetKind.valueOf(value);
  }
}

/** Locked source-operation state and an optional immutable replay row. */
record InventoryPublicationRegisteredSource(
    InventoryPublicationSourceOperation operation, InventoryPublicationSource replay) {}

/** Identifiers of the maintenance aggregate produced by one publication path. */
record InventoryPublicationCreatedTarget(
    InventoryPublicationTargetKind kind, UUID id, UUID estimateId, UUID repairId) {
  static InventoryPublicationCreatedTarget none() {
    return new InventoryPublicationCreatedTarget(null, null, null, null);
  }
}

/** Complete immutable source-row binding assembled by an owning publication use case. */
record InventoryPublicationSourceWrite(
    InventoryPublicationSourceId sourceId,
    InventoryPublicationApplyRequest request,
    InventoryPublicationFindingInput finding,
    InventoryPublicationValidatedPlan publication,
    InventoryPublicationSupersededTarget superseded,
    InventoryPublicationOutcome outcome,
    MaintenanceRepair predecessor,
    UUID predecessorRepairId,
    InventoryPublicationDelta delta,
    InventoryPublicationCreatedTarget created,
    String requestSha256,
    UUID idempotencyKey,
    InventoryPublicationTerminalProof terminalProof,
    InventoryPublicationTerminalProof acceptanceProof) {}

/** Public response plus the durable replay marker used by the facade. */
record InventoryPublicationWorkflowResult(InventoryPublicationApplyResult response, boolean replayed) {}
