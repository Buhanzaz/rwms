package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetNumberClaim;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSource;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetNumberClaimRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * Owns permanent inventory-finding source creation, number-claim fencing, and replay response
 * persistence.
 *
 * <p>The immutable source identity is registered before the number claim is locked. It never uses
 * ordinary idempotency retention: a durable completed source replays its persisted response.
 */
@Service
final class InventoryAssetSourceService {
  private final RentalItemRepository rentalItems;
  private final InventoryAssetSourceOperationRepository sourceOperations;
  private final InventoryAssetSourceRepository sources;
  private final InventoryAssetNumberClaimRepository numberClaims;
  private final InventoryAssetBoundaryRegistrar registrar;
  private final AssetEventStore events;
  private final WarehouseRegistryClient warehouses;
  private final CabinCompositionService cabinComposition;
  private final InventoryAssetProjectionService projections;
  private final InventoryAssetCodec codec;

  InventoryAssetSourceService(
      RentalItemRepository rentalItems,
      InventoryAssetSourceOperationRepository sourceOperations,
      InventoryAssetSourceRepository sources,
      InventoryAssetNumberClaimRepository numberClaims,
      InventoryAssetBoundaryRegistrar registrar,
      AssetEventStore events,
      WarehouseRegistryClient warehouses,
      CabinCompositionService cabinComposition,
      InventoryAssetProjectionService projections,
      InventoryAssetCodec codec) {
    this.rentalItems = rentalItems;
    this.sourceOperations = sourceOperations;
    this.sources = sources;
    this.numberClaims = numberClaims;
    this.registrar = registrar;
    this.events = events;
    this.warehouses = warehouses;
    this.cabinComposition = cabinComposition;
    this.projections = projections;
    this.codec = codec;
  }

  SourceAssetResult createSourceAsset(InventorySourceAssetRequest request) {
    String passportJson = codec.write(request.passport() == null ? Map.of() : request.passport());
    String tagsJson = codec.write(request.tags() == null ? List.of() : request.tags());
    CabinCompositionService.CabinSelection selection = sourceSelection(request);
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(request.category());
    RentalItem candidate =
        RentalItem.createFromInventory(
            request.warehouseId(),
            request.number(),
            selection.rentalTypeId(),
            selection.dimensionId(),
            selection.finishingId(),
            category.id(),
            category.name(),
            request.linoleum(),
            passportJson,
            tagsJson);
    String fingerprint = codec.canonicalHash(sourceFingerprint(request, candidate, selection));
    warehouses.requireIncoming(request.warehouseId());

    InventoryAssetSourceId sourceId =
        new InventoryAssetSourceId(request.inventoryId(), request.findingId());
    registerConcurrentSafe(() -> registrar.registerSourceOperation(sourceId, fingerprint));
    String registeredFingerprint =
        sourceOperations
            .findRequestFingerprintById(sourceId)
            .orElseThrow(() -> new IllegalStateException("Inventory source registration failed"));
    if (!registeredFingerprint.equals(fingerprint)) {
      throw new AssetConflictException("Inventory source identity is bound to another asset request");
    }
    registerConcurrentSafe(
        () ->
            registrar.claimNumber(
                candidate.getWarehouseId(), candidate.getIdentityMatchKey(), sourceId));
    InventoryAssetSourceOperation operation =
        sourceOperations
            .findByIdForUpdate(sourceId)
            .orElseThrow(() -> new IllegalStateException("Inventory source registration failed"));
    if (!operation.getRequestFingerprint().equals(fingerprint)) {
      throw new AssetConflictException("Inventory source identity is bound to another asset request");
    }
    InventoryAssetNumberClaim claim =
        numberClaims
            .findByWarehouseIdAndIdentityMatchKeyForUpdate(
                candidate.getWarehouseId(), candidate.getIdentityMatchKey())
            .orElseThrow(() -> new IllegalStateException("Inventory number claim registration failed"));
    if (!claim.belongsTo(sourceId)) {
      throw new AssetConflictException("Rental item number identity is already used in this warehouse");
    }
    InventoryAssetSource stored = sources.findById(sourceId).orElse(null);
    if (stored != null) {
      if (!stored.getRequestFingerprint().equals(fingerprint)) {
        throw new AssetConflictException(
            "Inventory source identity is bound to another asset request");
      }
      return new SourceAssetResult(
          codec.read(stored.getResponseBody(), InventorySourceAssetResponse.class), true);
    }
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKey(
        candidate.getWarehouseId(), candidate.getIdentityMatchKey())) {
      throw new AssetConflictException("Rental item number identity is already used in this warehouse");
    }

    RentalItem saved = rentalItems.saveAndFlush(candidate);
    cabinComposition.replaceRentalItemCharacteristics(saved.getId(), selection.characteristicIds());
    events.initialize(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        saved.getVersion(),
        AssetEventType.RENTAL_ITEM_CREATED,
        rentalFact(saved),
        rentalSnapshot(saved));
    InventorySourceAssetResponse response =
        new InventorySourceAssetResponse(
            request.inventoryId(), request.findingId(), projections.snapshot(saved));
    sources.saveAndFlush(
        InventoryAssetSource.complete(sourceId, fingerprint, saved.getId(), codec.write(response)));
    return new SourceAssetResult(response, false);
  }

  /**
   * A source asset accepts exactly one passport representation. UUIDs are canonical for every new
   * caller; the names branch is the supported manager compatibility format and is resolved by the
   * asset-owned catalog before the permanent source identity is registered.
   */
  private CabinCompositionService.CabinSelection sourceSelection(InventorySourceAssetRequest request) {
    boolean hasCanonicalValues =
        request.rentalTypeId() != null
            || request.dimensionId() != null
            || request.finishingId() != null
            || request.characteristicIds() != null;
    boolean hasLegacyValues =
        request.rentalType() != null
            || request.dimensions() != null
            || request.finishing() != null
            || request.characteristics() != null;
    if (hasCanonicalValues && hasLegacyValues) {
      throw new IllegalArgumentException(
          "Inventory source asset must use either catalog UUIDs or legacy passport names, not both");
    }
    if (hasCanonicalValues) {
      if (request.rentalTypeId() == null
          || request.dimensionId() == null
          || request.finishingId() == null
          || request.characteristicIds() == null) {
        throw new IllegalArgumentException("Canonical inventory source passport is incomplete");
      }
      return cabinComposition.requireSelection(
          request.rentalTypeId(),
          request.dimensionId(),
          request.finishingId(),
          request.characteristicIds());
    }
    if (!hasLegacyValues
        || request.rentalType() == null
        || request.rentalType().isBlank()
        || request.dimensions() == null
        || request.dimensions().isBlank()
        || request.finishing() == null
        || request.finishing().isBlank()) {
      throw new IllegalArgumentException("Legacy inventory source passport is incomplete");
    }
    return cabinComposition.requireLegacyInventorySelection(
        request.rentalType(),
        request.dimensions(),
        request.finishing(),
        request.characteristics());
  }

  private Map<String, ?> sourceFingerprint(
      InventorySourceAssetRequest request,
      RentalItem item,
      CabinCompositionService.CabinSelection selection) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("inventoryId", request.inventoryId());
    value.put("findingId", request.findingId());
    value.put("warehouseId", item.getWarehouseId());
    value.put("displayCanonicalNumber", item.getNumber());
    value.put("identityMatchKey", item.getIdentityMatchKey());
    value.put("rentalTypeId", item.getRentalTypeId());
    value.put("dimensionId", item.getDimensionId());
    value.put("finishingId", item.getFinishingId());
    value.put("category", item.getCategory());
    value.put("characteristicIds", selection.characteristicIds());
    value.put("linoleum", item.getLinoleum());
    value.put("passport", request.passport() == null ? Map.of() : request.passport());
    value.put("tags", request.tags() == null ? List.of() : request.tags());
    return value;
  }

  private static Map<String, ?> rentalFact(RentalItem item) {
    return Map.of(
        "rentalItemId", item.getId().toString(),
        "warehouseId", item.getWarehouseId().toString(),
        "status", item.getStatus().name(),
        "numberSha256", AssetChecksum.sha256(item.getNumber().getBytes(StandardCharsets.UTF_8)));
  }

  private Map<String, ?> rentalSnapshot(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    CabinCompositionService.CabinComposition composition =
        cabinComposition.compositionsFor(List.of(item)).get(item.getId());
    value.put("rentalItemId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("number", item.getNumber());
    value.put("identityMatchKey", item.getIdentityMatchKey());
    value.put("status", item.getStatus().name());
    value.put(
        "rentalTypeId",
        item.getRentalTypeId() == null ? null : item.getRentalTypeId().toString());
    value.put(
        "dimensionId", item.getDimensionId() == null ? null : item.getDimensionId().toString());
    value.put(
        "finishingId", item.getFinishingId() == null ? null : item.getFinishingId().toString());
    value.put("category", item.getCategory());
    value.put(
        "characteristicIds",
        composition == null
            ? List.of()
            : composition.characteristics().stream()
                .map(CabinCatalogValueResponse::id)
                .map(UUID::toString)
                .toList());
    value.put("linoleum", item.getLinoleum());
    value.put("generalComment", item.getGeneralComment());
    value.put(
        "passport", codec.read(item.getPassportJson(), new TypeReference<Map<String, Object>>() {}));
    value.put("tags", codec.read(item.getTagsJson(), new TypeReference<List<String>>() {}));
    return value;
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the same immutable key; the caller locks and validates it.
    }
  }

  /**
   * Source-asset response paired with whether it came from the permanent idempotency receipt rather
   * than a new inventory transition.
   */
  record SourceAssetResult(InventorySourceAssetResponse response, boolean replayed) {}
}
