package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityCommandReceipt;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionKind;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.mapper.WarehouseCapacitySnapshotResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityPriceZoneRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityRestrictionZoneRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityShiftRequest;
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
import tools.jackson.databind.ObjectMapper;

/** Owns atomic, idempotent replacement of simulator-only CustomerApp capacity facts. */
@Service
@RequiredArgsConstructor
public class WarehouseCapacitySnapshotService {
  private static final Comparator<PlanningCapacityJobRequest> JOB_ORDER =
      Comparator.comparing(PlanningCapacityJobRequest::deliveryDate)
          .thenComparing(PlanningCapacityJobRequest::windowStart)
          .thenComparing(PlanningCapacityJobRequest::sourceJobId);
  private static final Comparator<PlanningCapacityShiftRequest> SHIFT_ORDER =
      Comparator.comparing(PlanningCapacityShiftRequest::deliveryDate)
          .thenComparing(PlanningCapacityShiftRequest::shiftStart)
          .thenComparing(PlanningCapacityShiftRequest::sourceShiftId);
  private static final Comparator<PlanningCapacityPriceZoneRequest> PRICE_ZONE_ORDER =
      Comparator.comparing(PlanningCapacityPriceZoneRequest::sourceZoneId);
  private static final Comparator<PlanningCapacityRestrictionZoneRequest> RESTRICTION_ZONE_ORDER =
      Comparator.comparing(PlanningCapacityRestrictionZoneRequest::sourceZoneId);

  private final WarehouseCapacitySnapshotRepository snapshots;
  private final WarehouseCapacityCommandReceiptRepository receipts;
  private final WarehouseCapacitySnapshotResponseMapper responses;
  private final LogisticsTransactionLock transactionLock;
  private final CustomerDeliveryCapacityFence capacityFence;
  private final Clock clock;
  private final ObjectMapper json;

  /** Replaces one active warehouse projection while preserving exact command replay semantics. */
  @Transactional
  public PlanningCapacitySnapshotResponse replace(
      UUID warehouseId,
      UUID idempotencyKey,
      ReplacePlanningCapacitySnapshotRequest request) {
    List<PlanningCapacityJobRequest> sortedJobs = request.jobs().stream().sorted(JOB_ORDER).toList();
    List<PlanningCapacityShiftRequest> sortedShifts =
        request.shifts().stream().sorted(SHIFT_ORDER).toList();
    List<PlanningCapacityPriceZoneRequest> sortedPriceZones =
        request.priceZones().stream().sorted(PRICE_ZONE_ORDER).toList();
    List<PlanningCapacityRestrictionZoneRequest> sortedRestrictionZones =
        request.restrictionZones().stream().sorted(RESTRICTION_ZONE_ORDER).toList();
    String requestSha256 =
        WarehouseCapacityChecksum.sha256(
            warehouseId,
            request,
            sortedJobs,
            sortedShifts,
            sortedPriceZones,
            sortedRestrictionZones);
    List<WarehouseCapacityJob.Facts> jobFacts = sortedJobs.stream().map(this::jobFacts).toList();
    List<WarehouseCapacityShift.Facts> shiftFacts =
        sortedShifts.stream().map(WarehouseCapacitySnapshotService::shiftFacts).toList();
    List<WarehouseCapacityPriceZone.Facts> priceZoneFacts =
        sortedPriceZones.stream().map(this::priceZoneFacts).toList();
    List<WarehouseCapacityRestrictionZone.Facts> restrictionZoneFacts =
        sortedRestrictionZones.stream().map(this::restrictionZoneFacts).toList();
    transactionLock.acquire("customer-warehouse-capacity-command:" + idempotencyKey);
    WarehouseCapacityCommandReceipt accepted = receipts.findById(idempotencyKey).orElse(null);
    if (accepted != null) {
      if (!accepted.matchesRequest(requestSha256)) throw idempotencyConflict();
      return responses.toResponse(accepted, true);
    }
    capacityFence.acquireWarehouseCapacity(warehouseId);
    WarehouseCapacityCommandReceipt acceptedRevision =
        receipts
            .findFirstByWarehouseIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
                warehouseId,
                request.sourceGeneration(),
                request.sourceRevision())
            .orElse(null);
    OffsetDateTime now = now();
    if (acceptedRevision != null) {
      if (!acceptedRevision.matchesRequest(requestSha256)) throw revisionConflict();
      WarehouseCapacityCommandReceipt alias =
          receipts.saveAndFlush(
              WarehouseCapacityCommandReceipt.replayAlias(
                  idempotencyKey, acceptedRevision, now));
      return responses.toResponse(alias, true);
    }
    WarehouseCapacitySnapshot snapshot =
        snapshots.findByWarehouseIdForUpdate(warehouseId).orElse(null);
    if (snapshot == null) {
      snapshot =
          WarehouseCapacitySnapshot.create(
              warehouseId,
              request.sourceGeneration(),
              request.sourceRevision(),
              jobFacts,
              shiftFacts,
              priceZoneFacts,
              request.isochronePrice60Minutes(),
              request.isochronePrice120Minutes(),
              request.isochronePrice180Minutes(),
              request.isochronePrice240Minutes(),
              restrictionZoneFacts,
              now);
    } else {
      if (request.sourceGeneration() < snapshot.getSourceGeneration()) {
        throw staleGeneration();
      }
      if (request.sourceGeneration() == snapshot.getSourceGeneration()) {
        throw generationConflict();
      }
      snapshot.replace(
          request.sourceGeneration(),
          request.sourceRevision(),
          jobFacts,
          shiftFacts,
          priceZoneFacts,
          request.isochronePrice60Minutes(),
          request.isochronePrice120Minutes(),
          request.isochronePrice180Minutes(),
          request.isochronePrice240Minutes(),
          restrictionZoneFacts,
          now);
    }
    WarehouseCapacitySnapshot applied = snapshots.saveAndFlush(snapshot);
    receipts.saveAndFlush(
        WarehouseCapacityCommandReceipt.applied(
            idempotencyKey, applied, requestSha256, now));
    return responses.toResponse(applied, false);
  }

  private WarehouseCapacityJob.Facts jobFacts(PlanningCapacityJobRequest request) {
    return new WarehouseCapacityJob.Facts(
        request.sourceJobId(),
        WarehouseCapacityTaskType.valueOf(request.taskType().name()),
        request.deliveryDate(),
        request.latitude(),
        request.longitude(),
        request.cabinCount(),
        request.windowStart(),
        request.windowEnd(),
        request.serviceMinutes(),
        request.trailerAccessAllowed(),
        request.priority(),
        request.mandatory());
  }

  private static WarehouseCapacityShift.Facts shiftFacts(PlanningCapacityShiftRequest request) {
    return new WarehouseCapacityShift.Facts(
        request.sourceShiftId(),
        request.deliveryDate(),
        request.shiftStart(),
        request.shiftEnd(),
        request.breakMinutes(),
        request.cabinCapacity());
  }

  private WarehouseCapacityPriceZone.Facts priceZoneFacts(
      PlanningCapacityPriceZoneRequest request) {
    try {
      return new WarehouseCapacityPriceZone.Facts(
          request.sourceZoneId(),
          request.sourceZoneVersion(),
          request.deliveryPriceRubles(),
          request.pickupPriceRubles(),
          json.writeValueAsString(request.geometry()));
    } catch (Exception exception) {
      throw new IllegalArgumentException("Planning price-zone geometry cannot be serialized", exception);
    }
  }

  private WarehouseCapacityRestrictionZone.Facts restrictionZoneFacts(
      PlanningCapacityRestrictionZoneRequest request) {
    try {
      return new WarehouseCapacityRestrictionZone.Facts(
          request.sourceZoneId(),
          request.sourceZoneVersion(),
          WarehouseCapacityRestrictionKind.valueOf(request.kind().name()),
          json.writeValueAsString(request.geometry()));
    } catch (Exception exception) {
      throw new IllegalArgumentException(
          "Planning restriction-zone geometry cannot be serialized", exception);
    }
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
