package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferCabinAllocationView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferCabinGroupRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferCabinGroupView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurniturePerCabinView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReplacementRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureTotalView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLooseFurnitureView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferResourceIntentView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferResourceRepositionRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.TransferPlan;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanDraft;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanSnapshot;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.domain.TransferReservationReadiness;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.repository.TransferPlanRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Persists and materializes the planning layer of the existing transfer aggregate. It deliberately
 * exposes {@code NOT_RESERVED} until asset-service supplies a durable cross-process reservation.
 */
@Service
@RequiredArgsConstructor
class TransferPlanService {
  private static final String LEGACY_DETAIL = "LEGACY_DEPARTURE_LEASED_AT_START";

  private final TransferPlanRepository plans;

  /** Creates a planning projection without creating physical document lines or external effects. */
  TransferPlan createDraft(
      LogisticsDocument document, LocalDate scheduledDate, TransferPlanRequest request) {
    requireEditableTransfer(document);
    TransferPlanDraft draft = toDraft(request);
    validateSchedule(scheduledDate, draft);
    return plans.saveAndFlush(TransferPlan.draft(document, draft));
  }

  /** Replaces a complete draft after deleting its prior ordered child rows in a separate flush. */
  TransferPlan replaceDraft(
      LogisticsDocument document, LocalDate scheduledDate, TransferPlanRequest request) {
    requireEditableTransfer(document);
    TransferPlan plan = requiredForUpdate(document.getId());
    TransferPlanDraft draft = toDraft(request);
    validateSchedule(scheduledDate, draft);
    plan.clearCargoForReplacement();
    plans.saveAndFlush(plan);
    plan.replace(draft);
    return plans.saveAndFlush(plan);
  }

  /** Freezes structurally complete requirements while retaining explicit NOT_RESERVED readiness. */
  TransferPlan confirm(LogisticsDocument document) {
    requireEditableTransfer(document);
    TransferPlan plan = requiredForUpdate(document.getId());
    validateSchedule(document.getScheduledDate(), planDraft(plan.snapshot()));
    plan.confirm();
    return plans.saveAndFlush(plan);
  }

  /** Returns a plan view; old pre-planning transfers are represented as legacy-compatible. */
  TransferPlanView view(LogisticsDocument document, int legacyLineCount) {
    TransferPlan plan = plans.findByDocument_Id(document.getId()).orElse(null);
    if (plan == null) return legacyView(document, legacyLineCount);
    return view(document, plan.snapshot());
  }

  /** Returns the current persisted plan and fails for old concrete-line transfers. */
  TransferPlan required(UUID documentId) {
    return plans.findByDocument_Id(documentId).orElseThrow(LogisticsNotFoundException::new);
  }

  /** Ordered allocated cabins used to create the unchanged physical transfer lines at confirmation. */
  List<TransferPlanSnapshot.Allocation> allocatedCabins(TransferPlan plan) {
    return plan.snapshot().cabinGroups().stream()
        .flatMap(group -> group.allocatedCabins().stream())
        .toList();
  }

  /**
   * Reuses the existing complete-composition preparation workflow for every allocated cabin. Loose
   * furniture is intentionally excluded because it is independent cargo, not cabin composition.
   */
  List<TransferFurnitureReplacementRequest> furnitureReplacements(TransferPlan plan) {
    List<TransferFurnitureReplacementRequest> replacements = new ArrayList<>();
    for (TransferPlanSnapshot.CargoGroup group : plan.snapshot().cabinGroups()) {
      if (group.furniturePerCabin().isEmpty()) continue;
      List<CabinFurnitureRequirement> contents =
          group.furniturePerCabin().stream()
              .map(
                  item ->
                      new CabinFurnitureRequirement(
                          item.furnitureCatalogItemId(), item.quantityPerCabin()))
              .toList();
      for (TransferPlanSnapshot.Allocation allocation : group.allocatedCabins()) {
        replacements.add(new TransferFurnitureReplacementRequest(allocation.assetId(), contents));
      }
    }
    return List.copyOf(replacements);
  }

  /**
   * Preserves pre-V67 and original-client departure behavior, but fences every planned transfer
   * until asset-service has durably reserved its cargo.
   */
  void requireDepartureAllowed(LogisticsDocument document) {
    TransferPlan plan = plans.findByDocument_Id(document.getId()).orElse(null);
    if (plan == null) return;
    if (!plan.isDepartureReady()) {
      String detail = readinessDetail(plan.snapshot());
      throw new LogisticsConflictException(
          "TRANSFER_RESERVATION_NOT_READY: "
              + (detail == null ? "TRANSFER_WORKFLOW_NOT_READY" : detail));
    }
  }

  /** Event payload count for a planned transfer before physical document lines exist. */
  int auditLineCount(TransferPlan plan) {
    return plan.auditLineCount();
  }

  private TransferPlan requiredForUpdate(UUID documentId) {
    return plans
        .findForUpdateByDocumentId(documentId)
        .orElseThrow(LogisticsNotFoundException::new);
  }

  private static TransferPlanView view(
      LogisticsDocument document, TransferPlanSnapshot snapshot) {
    List<TransferCabinGroupView> groups =
        snapshot.cabinGroups().stream().map(TransferPlanService::groupView).toList();
    List<TransferLooseFurnitureView> loose =
        snapshot.looseFurniture().stream()
            .map(
                item ->
                    new TransferLooseFurnitureView(
                        item.furnitureCatalogItemId(), item.quantity()))
            .toList();
    return new TransferPlanView(
        document.getId(),
        document.getVersion(),
        document.getState(),
        snapshot.planId(),
        snapshot.planVersion(),
        snapshot.state(),
        snapshot.reservationReadiness(),
        readinessDetail(snapshot),
        false,
        document.getScheduledDate(),
        snapshot.plannedDepartureAt(),
        snapshot.plannedArrivalAt(),
        snapshot.logisticsComment(),
        snapshot.tripDriverId(),
        snapshot.tripVehicleId(),
        intentView(snapshot.driverReposition()),
        intentView(snapshot.vehicleReposition()),
        groups,
        loose,
        snapshot.totalCabinCount(),
        furnitureTotals(snapshot));
  }

  private static TransferPlanView legacyView(LogisticsDocument document, int lineCount) {
    return new TransferPlanView(
        document.getId(),
        document.getVersion(),
        document.getState(),
        null,
        null,
        TransferPlanState.CONFIRMED,
        TransferReservationReadiness.NOT_RESERVED,
        LEGACY_DETAIL,
        true,
        document.getScheduledDate(),
        null,
        null,
        null,
        document.getDriverWorkerId(),
        null,
        intentView(null),
        intentView(null),
        List.of(),
        List.of(),
        lineCount,
        List.of());
  }

  private static String readinessDetail(TransferPlanSnapshot snapshot) {
    return switch (snapshot.reservationReadiness()) {
      case RESERVED -> null;
      case NOT_RESERVED -> "CARGO_NOT_RESERVED";
      case RESERVING -> "RESERVATION_IN_PROGRESS";
      case FAILED ->
          snapshot.workflowFailureCode() == null
              ? "RESERVATION_FAILED"
              : snapshot.workflowFailureCode();
      case RELEASING -> "RESERVATION_RELEASE_IN_PROGRESS";
      case RELEASED -> "RESERVATIONS_RELEASED";
    };
  }

  private static TransferCabinGroupView groupView(TransferPlanSnapshot.CargoGroup group) {
    return new TransferCabinGroupView(
        group.groupId(),
        group.position(),
        group.rentalTypeId(),
        group.dimensionId(),
        group.finishingId(),
        group.characteristicIds(),
        group.linoleum(),
        group.quantity(),
        group.furniturePerCabin().stream()
            .map(
                item ->
                    new TransferFurniturePerCabinView(
                        item.furnitureCatalogItemId(),
                        item.quantityPerCabin(),
                        item.totalQuantity()))
            .toList(),
        group.allocatedCabins().stream()
            .map(item -> new TransferCabinAllocationView(item.assetId(), item.assetVersion()))
            .toList());
  }

  private static List<TransferFurnitureTotalView> furnitureTotals(
      TransferPlanSnapshot snapshot) {
    Map<UUID, Long> cabinTotals = new LinkedHashMap<>();
    for (TransferPlanSnapshot.CargoGroup group : snapshot.cabinGroups()) {
      for (TransferPlanSnapshot.Furniture item : group.furniturePerCabin()) {
        cabinTotals.merge(item.furnitureCatalogItemId(), item.totalQuantity(), Math::addExact);
      }
    }
    Map<UUID, Long> looseTotals = new LinkedHashMap<>();
    for (TransferPlanSnapshot.LooseFurniture item : snapshot.looseFurniture()) {
      looseTotals.merge(item.furnitureCatalogItemId(), item.quantity(), Math::addExact);
    }
    java.util.Set<UUID> catalogIds = new java.util.HashSet<>(cabinTotals.keySet());
    catalogIds.addAll(looseTotals.keySet());
    return catalogIds.stream()
        .sorted(Comparator.comparing(UUID::toString))
        .map(
            catalogId -> {
              long cabin = cabinTotals.getOrDefault(catalogId, 0L);
              long loose = looseTotals.getOrDefault(catalogId, 0L);
              return new TransferFurnitureTotalView(
                  catalogId, cabin, loose, Math.addExact(cabin, loose));
            })
        .toList();
  }

  private static TransferResourceIntentView intentView(
      TransferPlanSnapshot.ResourceIntent intent) {
    return intent == null
        ? new TransferResourceIntentView(null, TransferResourceRepositionMode.NONE, null)
        : new TransferResourceIntentView(intent.resourceId(), intent.mode(), intent.until());
  }

  private static TransferPlanDraft toDraft(TransferPlanRequest request) {
    if (request == null) throw new IllegalArgumentException("Transfer plan is required");
    return new TransferPlanDraft(
        request.plannedDepartureAt(),
        request.plannedArrivalAt(),
        request.logisticsComment(),
        request.tripDriverId(),
        request.tripVehicleId(),
        intent(request.driverReposition()),
        intent(request.vehicleReposition()),
        request.cabinGroups().stream().map(TransferPlanService::groupDraft).toList(),
        request.looseFurniture().stream()
            .map(
                item ->
                    new TransferPlanDraft.LooseFurniture(
                        item.furnitureCatalogItemId(), item.quantity()))
            .toList());
  }

  private static TransferPlanDraft.CargoGroup groupDraft(TransferCabinGroupRequest group) {
    return new TransferPlanDraft.CargoGroup(
        group.rentalTypeId(),
        group.dimensionId(),
        group.finishingId(),
        group.characteristicIds(),
        group.linoleum(),
        group.quantity(),
        group.furniturePerCabin().stream()
            .map(
                item ->
                    new TransferPlanDraft.Furniture(
                        item.furnitureCatalogItemId(), item.quantityPerCabin()))
            .toList(),
        group.allocatedCabins().stream()
            .map(
                item ->
                    new TransferPlanDraft.Allocation(item.assetId(), item.assetVersion()))
            .toList());
  }

  private static TransferPlanDraft.ResourceIntent intent(
      TransferResourceRepositionRequest request) {
    return request == null
        ? new TransferPlanDraft.ResourceIntent(
            null, TransferResourceRepositionMode.NONE, null)
        : new TransferPlanDraft.ResourceIntent(request.resourceId(), request.mode(), request.until());
  }

  private static TransferPlanDraft planDraft(TransferPlanSnapshot snapshot) {
    return new TransferPlanDraft(
        snapshot.plannedDepartureAt(),
        snapshot.plannedArrivalAt(),
        snapshot.logisticsComment(),
        snapshot.tripDriverId(),
        snapshot.tripVehicleId(),
        new TransferPlanDraft.ResourceIntent(
            snapshot.driverReposition().resourceId(),
            snapshot.driverReposition().mode(),
            snapshot.driverReposition().until()),
        new TransferPlanDraft.ResourceIntent(
            snapshot.vehicleReposition().resourceId(),
            snapshot.vehicleReposition().mode(),
            snapshot.vehicleReposition().until()),
        snapshot.cabinGroups().stream()
            .map(
                group ->
                    new TransferPlanDraft.CargoGroup(
                        group.rentalTypeId(),
                        group.dimensionId(),
                        group.finishingId(),
                        group.characteristicIds(),
                        group.linoleum(),
                        group.quantity(),
                        group.furniturePerCabin().stream()
                            .map(
                                item ->
                                    new TransferPlanDraft.Furniture(
                                        item.furnitureCatalogItemId(),
                                        item.quantityPerCabin()))
                            .toList(),
                        group.allocatedCabins().stream()
                            .map(
                                item ->
                                    new TransferPlanDraft.Allocation(
                                        item.assetId(), item.assetVersion()))
                            .toList()))
            .toList(),
        snapshot.looseFurniture().stream()
            .map(
                item ->
                    new TransferPlanDraft.LooseFurniture(
                        item.furnitureCatalogItemId(), item.quantity()))
            .toList());
  }

  private static void validateSchedule(LocalDate scheduledDate, TransferPlanDraft draft) {
    if (scheduledDate == null) throw new IllegalArgumentException("Transfer date is required");
    if (draft.plannedDepartureAt() != null
        && !scheduledDate.equals(draft.plannedDepartureAt().toLocalDate())) {
      throw new IllegalArgumentException("Planned departure must use the transfer schedule date");
    }
  }

  private static void requireEditableTransfer(LogisticsDocument document) {
    if (document == null
        || document.getDocumentType() != LogisticsDocumentType.TRANSFER
        || document.getState() != LogisticsDocumentState.DRAFT) {
      throw new LogisticsConflictException("Only a transfer draft has editable planning detail");
    }
  }
}
