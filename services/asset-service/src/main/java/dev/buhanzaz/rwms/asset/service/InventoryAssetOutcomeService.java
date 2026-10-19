package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeRequest;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeShipmentContent;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetOutcomeReceipt;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetOutcomeWatermark;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.mapper.InventoryAssetOutcomeResponseMapper;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetOutcomeReceiptRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetOutcomeWatermarkRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.InventoryRentalContentsService.Content;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Applies final completed-inventory truth to one found cabin and retains permanent replay and
 * ordering evidence.
 *
 * <p>The inventory service remains the saga owner. This component owns only asset state: it
 * serializes the cabin, resolves observed passport names against the active catalogue, ends live
 * asset-owned bindings without deleting history, updates passport/status/transfer state, emits
 * ordinary asset facts, and advances the per-cabin inventory watermark in the same transaction. A
 * RENTED outcome also replaces the cabin's exact active-catalog contents without reading or
 * changing STOCK. Explicit passport-only outcomes retain every operational field and binding while
 * using the same ordering and replay fences.
 */
@Service
final class InventoryAssetOutcomeService {
  private static final String MAINTENANCE_REPAIR_LEASE_OWNER = "MAINTENANCE_REPAIR";
  private static final Set<String> OBSERVATION_FIELDS = Set.of("presence", "value");
  private static final Set<String> PASSPORT_FIELDS =
      Set.of(
          "rentalType",
          "dimensions",
          "finishing",
          "category",
          "characteristics",
          "linoleum");
  private final InventoryAssetOutcomeReceiptRepository receipts;
  private final InventoryAssetOutcomeWatermarkRepository watermarks;
  private final RentalItemRepository rentalItems;
  private final OrderUnitReservationRepository orderReservations;
  private final PresentationUnitHoldRepository presentationHolds;
  private final AssetLeaseService leases;
  private final AssetEquipmentLedgerService equipmentLedger;
  private final InventoryRentalContentsService rentalContents;
  private final AssetRentalProjectionService projections;
  private final CabinCompositionService cabinComposition;
  private final AssetEventStore events;
  private final InventoryAssetCodec codec;
  private final InventoryAssetOutcomeResponseMapper responses;
  private final InventoryAssetSourceService sources;

  InventoryAssetOutcomeService(
      InventoryAssetOutcomeReceiptRepository receipts,
      InventoryAssetOutcomeWatermarkRepository watermarks,
      RentalItemRepository rentalItems,
      OrderUnitReservationRepository orderReservations,
      PresentationUnitHoldRepository presentationHolds,
      AssetLeaseService leases,
      AssetEquipmentLedgerService equipmentLedger,
      InventoryRentalContentsService rentalContents,
      AssetRentalProjectionService projections,
      CabinCompositionService cabinComposition,
      AssetEventStore events,
      InventoryAssetCodec codec,
      InventoryAssetOutcomeResponseMapper responses,
      InventoryAssetSourceService sources) {
    this.receipts = receipts;
    this.watermarks = watermarks;
    this.rentalItems = rentalItems;
    this.orderReservations = orderReservations;
    this.presentationHolds = presentationHolds;
    this.leases = leases;
    this.equipmentLedger = equipmentLedger;
    this.rentalContents = rentalContents;
    this.projections = projections;
    this.cabinComposition = cabinComposition;
    this.events = events;
    this.codec = codec;
    this.responses = responses;
    this.sources = sources;
  }

  /**
   * Applies or reasserts one outcome. Exact idempotency-key replay returns the stored response;
   * another key for the same latest final-plan source deliberately re-runs recovery.
   */
  OutcomeResult apply(
      UUID actorSubjectId,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryOutcomeRequest request) {
    return apply(actorSubjectId, inventoryId, findingId, idempotencyKey, request, false);
  }

  OutcomeResult applyWithSourceMaterialization(
      UUID actorSubjectId,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryOutcomeRequest request) {
    return apply(actorSubjectId, inventoryId, findingId, idempotencyKey, request, true);
  }

  private OutcomeResult apply(
      UUID actorSubjectId,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryOutcomeRequest request,
      boolean allowSourceMaterialization) {
    if (actorSubjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException(
          "Inventory outcome actor and idempotency key are required");
    }
    NormalizedOutcomePlan plan = normalize(inventoryId, findingId, request);
    if (allowSourceMaterialization && plan.preserveOperationalState()) {
      throw new IllegalArgumentException(
          "Inventory source outcomes cannot preserve operational state");
    }
    String requestSha256 = codec.canonicalHash(plan.requestFingerprint());
    receipts.acquireTransactionLock("inventory-outcome-idempotency:" + idempotencyKey);
    InventoryAssetOutcomeReceipt replay = receipts.findById(idempotencyKey).orElse(null);
    if (replay != null) {
      if (!replay.getRequestSha256().equals(requestSha256)) {
        throw new AssetConflictException(
            "Inventory outcome idempotency key is bound to another request");
      }
      return new OutcomeResult(responses.toResponse(replay), true);
    }

    leases.lockRentalItemAndLease(plan.assetId());
    ResolvedPassport passport = resolvePassport(plan.passport());
    RentalItem asset = rentalItems.findByIdForUpdate(plan.assetId()).orElse(null);
    if (asset == null && allowSourceMaterialization) {
      asset =
          sources.materializeForOutcome(
              inventoryId,
              findingId,
              plan.assetId(),
              plan.desiredStatus(),
              materializationPassport(passport));
    }
    if (asset == null) throw new AssetNotFoundException("Rental item was not found");
    if (!plan.preserveOperationalState()
        && !plan.warehouseId().equals(asset.getWarehouseId())) {
      throw new AssetConflictException(
          "Inventory outcome cabin belongs to another warehouse");
    }
    if (asset.getStatus().isTerminalDispositionStatus()) {
      throw new AssetConflictException(
          "Lost or written-off cabin cannot be replaced by inventory outcome");
    }
    assertOperationalStateFence(plan, asset);

    InventoryAssetOutcomeWatermark watermark =
        watermarks.findByAssetIdForUpdate(plan.assetId()).orElse(null);
    assertLatest(plan, watermark);

    List<UUID> releasedLeaseIds = List.of();
    List<UUID> releasedOrderReservationIds = List.of();
    List<UUID> releasedPresentationHoldIds = List.of();
    boolean transferSuperseded = false;
    if (plan.preserveOperationalState()) {
      asset = applyPassport(asset, passport);
    } else {
      releasedLeaseIds =
          leases.releaseForCompletedInventory(
              plan.assetId(), retainedLeaseOwnerTypes(plan.desiredStatus()));
      List<OrderUnitReservation> activeOrderReservations =
          orderReservations.findAllByRentalItemIdAndStateForUpdate(
              plan.assetId(), OrderUnitReservationState.ACTIVE);
      for (OrderUnitReservation reservation : activeOrderReservations) {
        reservation.release(actorSubjectId, "SYSTEM_ADMIN");
      }
      orderReservations.saveAllAndFlush(activeOrderReservations);
      releasedOrderReservationIds =
          activeOrderReservations.stream()
              .map(OrderUnitReservation::getId)
              .sorted(Comparator.comparing(UUID::toString))
              .toList();

      OffsetDateTime endedAt = now();
      List<PresentationUnitHold> activePresentationHolds =
          presentationHolds.findAllByRentalItemIdAndStateForUpdate(
              plan.assetId(), PresentationUnitHoldState.ACTIVE);
      for (PresentationUnitHold hold : activePresentationHolds) {
        hold.release(endedAt);
      }
      presentationHolds.saveAllAndFlush(activePresentationHolds);
      releasedPresentationHoldIds =
          activePresentationHolds.stream()
              .map(PresentationUnitHold::getId)
              .sorted(Comparator.comparing(UUID::toString))
              .toList();

      RentalItemStatus previous = asset.getStatus();
      transferSuperseded =
          previous == RentalItemStatus.IN_TRANSFER || asset.getTransferOriginStatus() != null;
      asset = applyPassport(asset, passport);
      long expectedVersion = asset.getVersion();
      boolean statusChanged = asset.applyCompletedInventoryOutcome(plan.desiredStatus());
      if (statusChanged) {
        asset = rentalItems.saveAndFlush(asset);
      }
      if (plan.desiredStatus() == RentalItemStatus.RENTED) {
        rentalContents.replace(plan.warehouseId(), plan.assetId(), plan.shipmentContents());
      }
      if (statusChanged) {
        events.append(
            AssetAggregateType.RENTAL_ITEM,
            asset.getId(),
            expectedVersion,
            AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
            projections.fact(asset),
            projections.snapshot(asset));
        if (plan.desiredStatus() != RentalItemStatus.RENTED) {
          equipmentLedger.reclassifyCabinBalances(asset, previous);
        }
      }
    }

    if (watermark == null) {
      watermark =
          InventoryAssetOutcomeWatermark.register(
              plan.assetId(),
              plan.inventoryId(),
              plan.findingId(),
              plan.warehouseId(),
              plan.inventoryCompletedAt(),
              plan.finalPlanVersion(),
              plan.finalPlanSha256(),
              plan.findingRevision(),
              plan.desiredStatus(),
              plan.preserveOperationalState(),
              plan.passport().sha256(),
              plan.shipmentContentsSha256());
    } else {
      watermark.replace(
          plan.inventoryId(),
          plan.findingId(),
          plan.warehouseId(),
          plan.inventoryCompletedAt(),
          plan.finalPlanVersion(),
          plan.finalPlanSha256(),
          plan.findingRevision(),
          plan.desiredStatus(),
          plan.preserveOperationalState(),
          plan.passport().sha256(),
          plan.shipmentContentsSha256());
    }
    watermarks.saveAndFlush(watermark);

    InventoryAssetOutcomeReceipt receipt =
        receipts.saveAndFlush(
            InventoryAssetOutcomeReceipt.record(
                idempotencyKey,
                requestSha256,
                plan.inventoryId(),
                plan.findingId(),
                plan.assetId(),
                plan.warehouseId(),
                plan.inventoryCompletedAt(),
                plan.finalPlanVersion(),
                plan.finalPlanSha256(),
                plan.findingRevision(),
                plan.desiredStatus(),
                plan.preserveOperationalState(),
                asset.getVersion(),
                asset.getStatus(),
                releasedLeaseIds,
                releasedOrderReservationIds,
                releasedPresentationHoldIds,
                transferSuperseded));
    return new OutcomeResult(responses.toResponse(receipt), false);
  }

  private static void assertLatest(
      NormalizedOutcomePlan plan, InventoryAssetOutcomeWatermark watermark) {
    if (watermark == null) return;
    int ordering = plan.inventoryCompletedAt().compareTo(watermark.getInventoryCompletedAt());
    if (ordering < 0) {
      throw new AssetConflictException(
          "An older completed inventory cannot replace the current cabin outcome");
    }
    if (ordering == 0
        && !sameSourceOrCorrection(plan, watermark)) {
      throw new AssetConflictException(
          "Equal-time inventory outcome conflicts with the accepted final-plan source");
    }
  }

  /**
   * Identifies a deliberate new-key reassertion or higher-plan correction of the current finding.
   *
   * <p>An equal completion time permits only the exact immutable source, its one-time legacy
   * adoption, or a strictly higher plan version of the same inventory and finding. Equal and lower
   * plan-version drift remains fenced.
   */
  private static boolean sameSourceOrCorrection(
      NormalizedOutcomePlan plan, InventoryAssetOutcomeWatermark watermark) {
    return watermark != null
        && (sameOrLegacySource(plan, watermark)
            || watermark.isNewerPlanRevisionOfSameCompletedFinding(
                plan.inventoryId(),
                plan.findingId(),
                plan.inventoryCompletedAt(),
                plan.finalPlanVersion()));
  }

  /**
   * Retains only maintenance repair custody while physical inventory still routes the cabin to a
   * repair state. Free outcomes release every active lease, including obsolete repair custody.
   */
  private static Set<String> retainedLeaseOwnerTypes(RentalItemStatus desiredStatus) {
    if (desiredStatus == RentalItemStatus.REPAIR
        || desiredStatus == RentalItemStatus.CAPITAL_REPAIR) {
      return Set.of(MAINTENANCE_REPAIR_LEASE_OWNER);
    }
    return Set.of();
  }

  /** Prevents an observed older cabin version from replacing a later rental or transfer. */
  private static void assertOperationalStateFence(
      NormalizedOutcomePlan plan, RentalItem asset) {
    if (plan.preserveOperationalState()
        || plan.expectedAssetVersion() == null
        || plan.desiredStatus() == RentalItemStatus.RENTED) {
      return;
    }
    boolean protectedOperationalState =
        asset.getStatus() == RentalItemStatus.RENTED
            || asset.getStatus() == RentalItemStatus.IN_TRANSFER;
    if (protectedOperationalState && asset.getVersion() != plan.expectedAssetVersion()) {
      throw new InventoryOperationalStateChangedException(
          "Cabin entered rental or transfer after the inventory observation");
    }
  }

  private static boolean sameOrLegacySource(
      NormalizedOutcomePlan plan, InventoryAssetOutcomeWatermark watermark) {
    return watermark.isSameSource(
            plan.inventoryId(),
            plan.findingId(),
            plan.warehouseId(),
            plan.inventoryCompletedAt(),
            plan.finalPlanVersion(),
            plan.finalPlanSha256(),
            plan.findingRevision(),
            plan.desiredStatus(),
            plan.preserveOperationalState(),
            plan.passport().sha256(),
            plan.shipmentContentsSha256())
        || watermark.isLegacySameSource(
            plan.inventoryId(),
            plan.findingId(),
            plan.warehouseId(),
            plan.inventoryCompletedAt(),
            plan.finalPlanVersion(),
            plan.finalPlanSha256(),
            plan.findingRevision(),
            plan.desiredStatus());
  }

  private NormalizedOutcomePlan normalize(
      UUID inventoryId, UUID findingId, InventoryOutcomeRequest request) {
    if (inventoryId == null || findingId == null || request == null) {
      throw new IllegalArgumentException("Inventory outcome identity is required");
    }
    RentalItemStatus desiredStatus =
        request.desiredStatus() == null
            ? null
            : RentalItemStatus.valueOf(request.desiredStatus().name());
    boolean preserveOperationalState = Boolean.TRUE.equals(request.preserveOperationalState());
    if (preserveOperationalState != (desiredStatus == null)) {
      throw new IllegalArgumentException(
          "desiredStatus must be null only when preserveOperationalState is true");
    }
    Long expectedAssetVersion = request.expectedAssetVersion();
    if (expectedAssetVersion != null && expectedAssetVersion < 0) {
      throw new IllegalArgumentException("expectedAssetVersion must not be negative");
    }
    if (preserveOperationalState && expectedAssetVersion != null) {
      throw new IllegalArgumentException(
          "expectedAssetVersion must be omitted when preserveOperationalState is true");
    }
    PassportObservationPlan passport = normalizePassport(request);
    List<Content> shipmentContents =
        normalizeShipmentContents(desiredStatus, request.shipmentContents());
    UUID warehouseId = Objects.requireNonNull(request.warehouseId(), "warehouseId");
    UUID assetId = Objects.requireNonNull(request.assetId(), "assetId");
    OffsetDateTime completedAt = normalizedTime(request.inventoryCompletedAt());
    long finalPlanVersion = requirePositive(request.finalPlanVersion(), "finalPlanVersion");
    String finalPlanSha256 = requireSha256(request.finalPlanSha256());
    long findingRevision = requirePositive(request.findingRevision(), "findingRevision");
    String shipmentContentsSha256 =
        shipmentContents == null ? null : codec.canonicalHash(shipmentContents);
    Object requestFingerprint =
        preserveOperationalState
            ? new PreserveOutcomePlan(
                inventoryId,
                findingId,
                warehouseId,
                assetId,
                completedAt,
                finalPlanVersion,
                finalPlanSha256,
                findingRevision,
                passport,
                true)
            : expectedAssetVersion == null
                ? new OutcomePlan(
                    inventoryId,
                    findingId,
                    warehouseId,
                    assetId,
                    completedAt,
                    finalPlanVersion,
                    finalPlanSha256,
                    findingRevision,
                    desiredStatus,
                    passport,
                    shipmentContents,
                    shipmentContentsSha256)
                : new GuardedOutcomePlan(
                    inventoryId,
                    findingId,
                    warehouseId,
                    assetId,
                    completedAt,
                    finalPlanVersion,
                    finalPlanSha256,
                    findingRevision,
                    desiredStatus,
                    passport,
                    shipmentContents,
                    shipmentContentsSha256,
                    expectedAssetVersion);
    return new NormalizedOutcomePlan(
        inventoryId,
        findingId,
        warehouseId,
        assetId,
        completedAt,
        finalPlanVersion,
        finalPlanSha256,
        findingRevision,
        desiredStatus,
        passport,
        shipmentContents,
        shipmentContentsSha256,
        expectedAssetVersion,
        preserveOperationalState,
        requestFingerprint);
  }

  private static List<Content> normalizeShipmentContents(
      RentalItemStatus desiredStatus, List<InventoryOutcomeShipmentContent> contents) {
    if (desiredStatus != RentalItemStatus.RENTED) {
      if (contents != null) {
        throw new IllegalArgumentException(
            "shipmentContents must be null unless desiredStatus is RENTED");
      }
      return null;
    }
    if (contents == null || contents.size() > 100) {
      throw new IllegalArgumentException("RENTED inventory outcome requires shipmentContents");
    }
    Set<UUID> equipmentIds = new HashSet<>();
    List<Content> normalized = new ArrayList<>(contents.size());
    for (InventoryOutcomeShipmentContent content : contents) {
      if (content == null
          || content.equipmentId() == null
          || content.catalogVersion() == null
          || content.catalogVersion() < 0
          || content.quantity() == null
          || content.quantity() < 1
          || !equipmentIds.add(content.equipmentId())) {
        throw new IllegalArgumentException(
            "shipmentContents require unique equipment and positive quantities");
      }
      normalized.add(
          new Content(content.equipmentId(), content.catalogVersion(), content.quantity()));
    }
    normalized.sort(Comparator.comparing(value -> value.equipmentId().toString()));
    return List.copyOf(normalized);
  }

  private PassportObservationPlan normalizePassport(InventoryOutcomeRequest request) {
    JsonNode observation = Objects.requireNonNull(request.passportObservation(), "passportObservation");
    String observationSha256 = requireSha256(
        request.passportObservationSha256(), "passportObservationSha256");
    if (!observationSha256.equals(codec.canonicalJsonHash(observation))) {
      throw new IllegalArgumentException("passportObservationSha256 does not match the observation");
    }
    if (!observation.isObject()
        || !observation.path("presence").isTextual()
        || !observation.has("value")
        || !new HashSet<>(observation.propertyNames()).equals(OBSERVATION_FIELDS)) {
      throw new IllegalArgumentException("Inventory passport observation is invalid");
    }
    String presence = observation.path("presence").asText();
    JsonNode value = observation.get("value");
    if ("ABSENT".equals(presence)) {
      if (value == null || !value.isNull()) {
        throw new IllegalArgumentException("ABSENT passport observation must have a null value");
      }
      return PassportObservationPlan.absent(observationSha256);
    }
    if (!"PRESENT".equals(presence) || value == null || !value.isObject()) {
      throw new IllegalArgumentException("Inventory passport observation must be ABSENT or PRESENT");
    }
    if (!PASSPORT_FIELDS.containsAll(new HashSet<>(value.propertyNames()))) {
      throw new IllegalArgumentException("Inventory passport observation has unsupported fields");
    }
    return PassportObservationPlan.present(
        requiredText(value, "rentalType"),
        requiredText(value, "dimensions"),
        requiredText(value, "finishing"),
        requiredText(value, "category"),
        characteristicValues(value.get("characteristics")),
        value.path("linoleum").isBoolean() ? value.path("linoleum").booleanValue() : null,
        observationSha256);
  }

  private ResolvedPassport resolvePassport(PassportObservationPlan passport) {
    if (!passport.present()) return null;
    CabinCompositionService.CabinSelection selection =
        cabinComposition.requireInventoryOutcomeSelection(
            passport.rentalType(),
            passport.dimensions(),
            passport.finishing(),
            passport.characteristics());
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(passport.category());
    return new ResolvedPassport(selection, category, passport.linoleum());
  }

  private RentalItem applyPassport(RentalItem asset, ResolvedPassport passport) {
    if (passport == null) return asset;
    long expectedVersion = asset.getVersion();
    CabinCompositionService.CabinSelection selection = passport.selection();
    CabinCompositionService.CategorySelection category = passport.category();
    boolean passportChanged =
        asset.changePassport(
            selection.rentalTypeId(),
            selection.dimensionId(),
            selection.finishingId(),
            category.id(),
            category.name(),
            passport.linoleum(),
            asset.getPassportJson(),
            asset.getTagsJson());
    boolean characteristicsChanged =
        cabinComposition.replaceRentalItemCharacteristics(
            asset.getId(), selection.characteristicIds());
    if (!passportChanged && !characteristicsChanged) return asset;
    if (characteristicsChanged && !passportChanged) asset.touchActivity();
    RentalItem saved = rentalItems.saveAndFlush(asset);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        expectedVersion,
        AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED,
        projections.fact(saved),
        projections.snapshot(saved));
    return saved;
  }

  private static InventoryAssetSourceService.MaterializationPassport materializationPassport(
      ResolvedPassport passport) {
    if (passport == null) return null;
    CabinCompositionService.CabinSelection selection = passport.selection();
    CabinCompositionService.CategorySelection category = passport.category();
    return new InventoryAssetSourceService.MaterializationPassport(
        selection.rentalTypeId(),
        selection.dimensionId(),
        selection.finishingId(),
        category.id(),
        category.name(),
        selection.characteristicIds(),
        passport.linoleum());
  }

  private static String requiredText(JsonNode value, String field) {
    JsonNode node = value.get(field);
    if (node == null || !node.isTextual()) {
      throw new IllegalArgumentException(field + " is required in PRESENT passport observation");
    }
    String normalized = node.asText().trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty() || normalized.length() > 255) {
      throw new IllegalArgumentException(field + " must contain 1 to 255 characters");
    }
    return normalized;
  }

  private static List<String> characteristicValues(JsonNode value) {
    if (value == null || value.isNull()) return List.of();
    if (value.isTextual()) return List.of(value.asText());
    if (!value.isArray() || value.size() > 100) {
      throw new IllegalArgumentException("characteristics must be a string or an array");
    }
    List<String> result = new ArrayList<>(value.size());
    value.forEach(
        entry -> {
          if (!entry.isTextual()) {
            throw new IllegalArgumentException("characteristics must contain only strings");
          }
          result.add(entry.asText());
        });
    return List.copyOf(result);
  }

  private static long requirePositive(Long value, String name) {
    if (value == null || value < 1) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static String requireSha256(String value) {
    return requireSha256(value, "finalPlanSha256");
  }

  private static String requireSha256(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  private static OffsetDateTime normalizedTime(OffsetDateTime value) {
    if (value == null) throw new IllegalArgumentException("inventoryCompletedAt is required");
    return value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Transaction result distinguishing an exact receipt replay from an applied recovery. */
  record OutcomeResult(InventoryOutcomeResponse response, boolean replayed) {}

  /** Canonical resource and final-plan material used for ordering and idempotency hashing. */
  private record OutcomePlan(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      PassportObservationPlan passport,
      List<Content> shipmentContents,
      String shipmentContentsSha256) {}

  /** New normal-outcome identity whose version fence cannot collide with a legacy request. */
  private record GuardedOutcomePlan(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      PassportObservationPlan passport,
      List<Content> shipmentContents,
      String shipmentContentsSha256,
      long expectedAssetVersion) {}

  /** Canonical request identity for a passport-only outcome; legacy fingerprints stay unchanged. */
  private record PreserveOutcomePlan(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      PassportObservationPlan passport,
      boolean preserveOperationalState) {}

  /** Validated runtime plan paired with its mode-specific durable request fingerprint. */
  private record NormalizedOutcomePlan(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      PassportObservationPlan passport,
      List<Content> shipmentContents,
      String shipmentContentsSha256,
      Long expectedAssetVersion,
      boolean preserveOperationalState,
      Object requestFingerprint) {}

  /** Frozen and validated inventory-side passport observation included in request identity. */
  private record PassportObservationPlan(
      boolean present,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      List<String> characteristics,
      Boolean linoleum,
      String sha256) {
    private static PassportObservationPlan absent(String sha256) {
      return new PassportObservationPlan(
          false, null, null, null, null, List.of(), null, sha256);
    }

    private static PassportObservationPlan present(
        String rentalType,
        String dimensions,
        String finishing,
        String category,
        List<String> characteristics,
        Boolean linoleum,
        String sha256) {
      return new PassportObservationPlan(
          true,
          rentalType,
          dimensions,
          finishing,
          category,
          List.copyOf(characteristics),
          linoleum,
          sha256);
    }
  }

  /** Active catalogue IDs and nullable linoleum resolved before any asset binding is released. */
  private record ResolvedPassport(
      CabinCompositionService.CabinSelection selection,
      CabinCompositionService.CategorySelection category,
      Boolean linoleum) {}
}
