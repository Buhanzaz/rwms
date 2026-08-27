package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacityCommandReceipt;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.mapper.ScenarioCapacitySnapshotResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacityCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacitySnapshotResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningCapacitySnapshotRequest;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns atomic, idempotent replacement of simulator-only CustomerApp capacity facts. */
@Service
@RequiredArgsConstructor
public class ScenarioCapacitySnapshotService {
  private static final Comparator<PlanningCapacityJobRequest> JOB_ORDER =
      Comparator.comparing(PlanningCapacityJobRequest::deliveryDate)
          .thenComparing(PlanningCapacityJobRequest::windowStart)
          .thenComparing(PlanningCapacityJobRequest::sourceJobId);

  private final ScenarioCapacitySnapshotRepository snapshots;
  private final ScenarioCapacityCommandReceiptRepository receipts;
  private final ScenarioCapacitySnapshotResponseMapper responses;
  private final LogisticsTransactionLock transactionLock;
  private final Clock clock;

  /** Replaces one active warehouse projection while preserving exact command replay semantics. */
  @Transactional
  public PlanningCapacitySnapshotResponse replace(
      UUID sourceScenarioId,
      UUID idempotencyKey,
      ReplacePlanningCapacitySnapshotRequest request) {
    List<PlanningCapacityJobRequest> sortedJobs = request.jobs().stream().sorted(JOB_ORDER).toList();
    String requestSha256 = ScenarioCapacityChecksum.sha256(sourceScenarioId, request, sortedJobs);
    transactionLock.acquire("customer-scenario-capacity-command:" + idempotencyKey);
    ScenarioCapacityCommandReceipt accepted = receipts.findById(idempotencyKey).orElse(null);
    if (accepted != null) {
      if (!accepted.matchesRequest(requestSha256)) throw idempotencyConflict();
      return responses.toResponse(accepted, true);
    }
    transactionLock.acquire("customer-scenario-capacity:" + request.warehouseId());
    ScenarioCapacityCommandReceipt acceptedRevision =
        receipts
            .findFirstByWarehouseIdAndSourceScenarioIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                request.warehouseId(),
                sourceScenarioId,
                request.sourceGeneration(),
                request.sourceRevision())
            .orElse(null);
    OffsetDateTime now = now();
    if (acceptedRevision != null) {
      if (!acceptedRevision.matchesRequest(requestSha256)) throw revisionConflict();
      ScenarioCapacityCommandReceipt alias =
          receipts.saveAndFlush(
              ScenarioCapacityCommandReceipt.replayAlias(
                  idempotencyKey, acceptedRevision, now));
      return responses.toResponse(alias, true);
    }
    ScenarioCapacitySnapshot snapshot =
        snapshots.findByWarehouseIdForUpdate(request.warehouseId()).orElse(null);
    if (snapshot == null) {
      snapshot =
          ScenarioCapacitySnapshot.create(
              request.warehouseId(),
              sourceScenarioId,
              request.sourceGeneration(),
              request.sourceRevision(),
              sortedJobs,
              now);
    } else {
      if (request.sourceGeneration() < snapshot.getSourceGeneration()) {
        throw staleGeneration();
      }
      if (request.sourceGeneration() == snapshot.getSourceGeneration()) {
        throw generationConflict();
      }
      snapshot.replace(
          sourceScenarioId,
          request.sourceGeneration(),
          request.sourceRevision(),
          sortedJobs,
          now);
    }
    ScenarioCapacitySnapshot applied = snapshots.saveAndFlush(snapshot);
    receipts.saveAndFlush(
        ScenarioCapacityCommandReceipt.applied(
            idempotencyKey, applied, requestSha256, now));
    return responses.toResponse(applied, false);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderProblemException idempotencyConflict() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "PLANNING_CAPACITY_IDEMPOTENCY_CONFLICT",
        "Idempotency-Key already belongs to another capacity snapshot");
  }

  private static OrderProblemException revisionConflict() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "PLANNING_CAPACITY_REVISION_CONFLICT",
        "The same simulator revision contains different capacity facts");
  }

  private static OrderProblemException staleGeneration() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "PLANNING_CAPACITY_GENERATION_STALE",
        "A newer simulator capacity generation is already active");
  }

  private static OrderProblemException generationConflict() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "PLANNING_CAPACITY_GENERATION_CONFLICT",
        "The active simulator generation belongs to another capacity command");
  }
}
