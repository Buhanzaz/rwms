package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSource;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
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
 * Owns permanent inventory-finding proposal identity, replay response persistence, and completed
 * source materialization.
 *
 * <p>An isolated proposal deliberately does not claim its number or create warehouse state. It
 * never uses ordinary idempotency retention: the source operation retains the same proposed UUID
 * and response before and after completed-inventory materialization.
 */
@Service
final class InventoryAssetSourceService {
  private final RentalItemRepository rentalItems;
  private final InventoryAssetSourceOperationRepository sourceOperations;
  private final InventoryAssetSourceRepository sources;
  private final InventoryAssetBoundaryRegistrar registrar;
  private final AssetEventStore events;
  private final WarehouseRegistryClient warehouses;
  private final CabinCompositionService cabinComposition;
  private final InventoryAssetCodec codec;

  InventoryAssetSourceService(
      RentalItemRepository rentalItems,
      InventoryAssetSourceOperationRepository sourceOperations,
      InventoryAssetSourceRepository sources,
      InventoryAssetBoundaryRegistrar registrar,
      AssetEventStore events,
      WarehouseRegistryClient warehouses,
      CabinCompositionService cabinComposition,
      InventoryAssetCodec codec) {
    this.rentalItems = rentalItems;
    this.sourceOperations = sourceOperations;
    this.sources = sources;
    this.registrar = registrar;
    this.events = events;
    this.warehouses = warehouses;
    this.cabinComposition = cabinComposition;
    this.codec = codec;
  }

  SourceAssetResult createSourceAsset(InventorySourceAssetRequest request) {
    String passportJson = codec.write(request.passport() == null ? Map.of() : request.passport());
    String tagsJson = codec.write(request.tags() == null ? List.of() : request.tags());
    CabinCompositionService.CabinSelection selection = sourceSelection(request);
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(request.category());
    UUID proposedAssetId = UUID.randomUUID();
    RentalItem candidate =
        RentalItem.materializeInventorySource(
            proposedAssetId,
            request.warehouseId(),
            request.number(),
            RentalItemStatus.FREE,
            selection.rentalTypeId(),
            selection.dimensionId(),
            selection.finishingId(),
            category.id(),
            category.name(),
            request.linoleum(),
            passportJson,
            tagsJson);
    SourcePlan sourcePlan = sourcePlan(candidate, selection);
    String fingerprint = codec.canonicalHash(sourceFingerprint(request, candidate, selection));
    warehouses.requireIncoming(request.warehouseId());

    InventoryAssetSourceId sourceId =
        new InventoryAssetSourceId(request.inventoryId(), request.findingId());
    InventorySourceAssetResponse proposedResponse =
        new InventorySourceAssetResponse(
            request.inventoryId(), request.findingId(), sourceSnapshot(candidate));
    registerConcurrentSafe(
        () ->
            registrar.registerSourceOperation(
                sourceId,
                fingerprint,
                proposedAssetId,
                codec.write(sourcePlan),
                codec.write(proposedResponse)));
    String registeredFingerprint =
        sourceOperations
            .findRequestFingerprintById(sourceId)
            .orElseThrow(() -> new IllegalStateException("Inventory source registration failed"));
    if (!registeredFingerprint.equals(fingerprint)) {
      throw new AssetConflictException("Inventory source identity is bound to another asset request");
    }
    InventoryAssetSourceOperation operation =
        sourceOperations
            .findByIdForUpdate(sourceId)
            .orElseThrow(() -> new IllegalStateException("Inventory source registration failed"));
    if (!operation.getRequestFingerprint().equals(fingerprint)) {
      throw new AssetConflictException("Inventory source identity is bound to another asset request");
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
    boolean replayed = !proposedAssetId.equals(operation.getReservedRentalItemId());
    if (!operation.hasProposal()) {
      operation.bindProposal(
          proposedAssetId, codec.write(sourcePlan), codec.write(proposedResponse));
      sourceOperations.saveAndFlush(operation);
      replayed = false;
    }
    return new SourceAssetResult(
        codec.read(operation.getProposalResponse(), InventorySourceAssetResponse.class), replayed);
  }

  /**
   * Returns the isolated proposal projection for a private inventory read, if still
   * unmaterialized.
   */
  InventoryAssetCurrentSnapshot currentProposal(UUID assetId) {
    InventoryAssetSourceOperation operation =
        sourceOperations.findByReservedRentalItemId(assetId).orElse(null);
    if (operation == null || rentalItems.existsById(assetId) || !operation.hasProposal()) return null;
    SourcePlan plan = plan(operation);
    return new InventoryAssetCurrentSnapshot(
        assetId,
        0,
        plan.warehouseId(),
        RentalItemStatus.FREE,
        plan.number(),
        plan.identityMatchKey(),
        null,
        plan.passportSnapshot(),
        List.of());
  }

  /** Returns an unsaved proposal shape used only by inventory's selected furniture snapshot. */
  List<RentalItem> pendingRentalItems(List<UUID> assetIds) {
    if (assetIds.isEmpty()) return List.of();
    return sourceOperations.findAllByReservedRentalItemIdIn(assetIds).stream()
        .filter(InventoryAssetSourceOperation::hasProposal)
        .filter(operation -> !rentalItems.existsById(operation.getReservedRentalItemId()))
        .map(operation -> proposalItem(operation, RentalItemStatus.FREE, null))
        .toList();
  }

  /**
   * Locks and materializes the exact reserved source when its completed finding outcome first
   * becomes authoritative. A present observation replaces the proposal passport; an absent one
   * retains the inspected source proposal.
   */
  RentalItem materializeForOutcome(
      UUID inventoryId,
      UUID findingId,
      UUID assetId,
      RentalItemStatus status,
      MaterializationPassport passport) {
    InventoryAssetSourceId sourceId = new InventoryAssetSourceId(inventoryId, findingId);
    InventoryAssetSourceOperation operation =
        sourceOperations.findByIdForUpdate(sourceId).orElse(null);
    if (operation == null) return null;
    if (!operation.hasProposal() || !assetId.equals(operation.getReservedRentalItemId())) {
      throw new AssetConflictException("Inventory source outcome does not match its reserved asset");
    }
    InventoryAssetSource completed = sources.findById(sourceId).orElse(null);
    if (completed != null) {
      if (!assetId.equals(completed.getRentalItemId())) {
        throw new AssetConflictException("Inventory source is bound to another rental item");
      }
      return rentalItems.findByIdForUpdate(assetId).orElseThrow(
          () -> new IllegalStateException("Completed inventory source rental item is missing"));
    }
    if (rentalItems.existsById(assetId)) {
      throw new AssetConflictException("Reserved inventory source asset identity is already used");
    }
    RentalItem created = proposalItem(operation, status, passport);
    int inserted;
    try {
      inserted =
          rentalItems.insertInventorySource(
              created.getId(),
              created.getWarehouseId(),
              created.getNumber(),
              created.getIdentityMatchKey(),
              created.getStatus().name(),
              created.getRentalTypeId(),
              created.getDimensionId(),
              created.getFinishingId(),
              created.getCategoryId(),
              created.getCategory(),
              created.getLinoleum(),
              created.getPassportJson(),
              created.getTagsJson());
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException(
          "Rental item number or reserved identity is already used in this warehouse");
    }
    if (inserted != 1) {
      throw new IllegalStateException("Inventory source rental item was not inserted");
    }
    RentalItem saved =
        rentalItems.findByIdForUpdate(assetId).orElseThrow(
            () -> new IllegalStateException("Inventory source rental item was not materialized"));
    SourcePlan plan = plan(operation);
    List<UUID> characteristicIds =
        passport == null ? plan.characteristicIds() : passport.characteristicIds();
    cabinComposition.replaceRentalItemCharacteristics(saved.getId(), characteristicIds);
    events.initialize(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        saved.getVersion(),
        AssetEventType.RENTAL_ITEM_CREATED,
        rentalFact(saved),
        rentalSnapshot(saved));
    sources.saveAndFlush(
        InventoryAssetSource.complete(
            sourceId,
            operation.getRequestFingerprint(),
            saved.getId(),
            operation.getProposalResponse()));
    return saved;
  }

  /**
   * Locks this inventory's proposal identities, validates every supplied source outcome, and
   * filters already-real non-source candidates from the atomic source work. Unsupplied proposals
   * remain isolated because only inventory-service owns the active completed final plan.
   */
  List<InventorySourceOutcomeCandidate> prepareSourceOutcomes(
      UUID inventoryId, List<InventorySourceOutcomeCandidate> candidates) {
    Map<UUID, InventorySourceOutcomeCandidate> byFinding = new LinkedHashMap<>();
    for (InventorySourceOutcomeCandidate candidate : candidates) {
      if (candidate == null
          || candidate.findingId() == null
          || candidate.outcome() == null
          || candidate.outcome().assetId() == null
          || byFinding.putIfAbsent(candidate.findingId(), candidate) != null) {
        throw new IllegalArgumentException("Inventory source outcomes must have unique findings");
      }
      if (candidate.outcome().desiredStatus() != InventoryOutcomeStatus.FREE
          && candidate.outcome().desiredStatus() != InventoryOutcomeStatus.REPAIR
          && candidate.outcome().desiredStatus() != InventoryOutcomeStatus.CAPITAL_REPAIR) {
        throw new IllegalArgumentException(
            "Inventory source outcomes require a local non-rented final status");
      }
    }
    Map<UUID, InventoryAssetSourceOperation> operations = new LinkedHashMap<>();
    for (InventoryAssetSourceOperation operation :
        sourceOperations.findAllByInventoryIdForUpdate(inventoryId)) {
      operations.put(operation.getId().getFindingId(), operation);
    }
    List<InventorySourceOutcomeCandidate> applicable = new java.util.ArrayList<>();
    for (InventorySourceOutcomeCandidate candidate : candidates) {
      InventoryAssetSourceOperation operation = operations.get(candidate.findingId());
      if (operation == null) {
        RentalItem existing = rentalItems.findById(candidate.outcome().assetId()).orElse(null);
        if (existing != null) {
          if (!candidate.outcome().warehouseId().equals(existing.getWarehouseId())) {
            throw new AssetConflictException(
                "Inventory source outcome asset belongs to another warehouse");
          }
          continue;
        }
        throw new AssetConflictException("Inventory source outcome has no reserved proposal");
      }
      if (!candidate.outcome().assetId().equals(operation.getReservedRentalItemId())) {
        throw new AssetConflictException("Inventory source outcome does not match its proposal");
      }
      if (!operation.hasProposal()) {
        InventoryAssetSource completed =
            sources
                .findById(operation.getId())
                .orElseThrow(
                    () -> new AssetConflictException("Inventory source proposal is incomplete"));
        if (!candidate.outcome().assetId().equals(completed.getRentalItemId())
            || !rentalItems.existsById(completed.getRentalItemId())) {
          throw new AssetConflictException("Completed inventory source asset is missing");
        }
      }
      applicable.add(candidate);
    }
    return List.copyOf(applicable);
  }

  private RentalItem proposalItem(
      InventoryAssetSourceOperation operation,
      RentalItemStatus status,
      MaterializationPassport passport) {
    SourcePlan plan = plan(operation);
    return RentalItem.materializeInventorySource(
        operation.getReservedRentalItemId(),
        plan.warehouseId(),
        plan.number(),
        status,
        passport == null ? plan.rentalTypeId() : passport.rentalTypeId(),
        passport == null ? plan.dimensionId() : passport.dimensionId(),
        passport == null ? plan.finishingId() : passport.finishingId(),
        passport == null ? plan.categoryId() : passport.categoryId(),
        passport == null ? plan.category() : passport.category(),
        passport == null ? plan.linoleum() : passport.linoleum(),
        plan.passportJson(),
        plan.tagsJson());
  }

  private SourcePlan plan(InventoryAssetSourceOperation operation) {
    return codec.read(operation.getSourcePlan(), SourcePlan.class);
  }

  private SourcePlan sourcePlan(
      RentalItem item, CabinCompositionService.CabinSelection selection) {
    CabinCompositionService.CabinComposition composition = cabinComposition.compositionFor(selection);
    Map<String, Object> passportSnapshot = new LinkedHashMap<>();
    passportSnapshot.put("rentalType", composition.rentalType().name());
    passportSnapshot.put("dimensions", composition.dimensions().name());
    passportSnapshot.put("finishing", composition.finishing().name());
    passportSnapshot.put("category", item.getCategory());
    passportSnapshot.put(
        "characteristics",
        composition.characteristics().stream().map(CabinCatalogValueResponse::name).toList());
    passportSnapshot.put("linoleum", item.getLinoleum());
    passportSnapshot.put(
        "passport", codec.read(item.getPassportJson(), new TypeReference<Map<String, Object>>() {}));
    passportSnapshot.put(
        "tags", codec.read(item.getTagsJson(), new TypeReference<List<String>>() {}));
    passportSnapshot.put("tenant", null);
    return new SourcePlan(
        item.getWarehouseId(),
        item.getNumber(),
        item.getIdentityMatchKey(),
        item.getRentalTypeId(),
        item.getDimensionId(),
        item.getFinishingId(),
        item.getCategoryId(),
        item.getCategory(),
        selection.characteristicIds(),
        item.getLinoleum(),
        item.getPassportJson(),
        item.getTagsJson(),
        java.util.Collections.unmodifiableMap(passportSnapshot));
  }

  private static InventoryAssetSnapshot sourceSnapshot(RentalItem item) {
    return new InventoryAssetSnapshot(
        item.getId(),
        item.getVersion(),
        item.getWarehouseId(),
        item.getStatus(),
        item.getNumber(),
        item.getIdentityMatchKey(),
        null);
  }

  /**
   * A source asset accepts exactly one passport representation. UUIDs are canonical for every new
   * caller; the names branch is the supported manager compatibility format and is resolved by the
   * asset-owned catalog before the permanent source identity is registered.
   */
  private CabinCompositionService.CabinSelection sourceSelection(
      InventorySourceAssetRequest request) {
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

  /** Durable material required to materialize or synthesize one isolated inventory proposal. */
  private record SourcePlan(
      UUID warehouseId,
      String number,
      String identityMatchKey,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      List<UUID> characteristicIds,
      Boolean linoleum,
      String passportJson,
      String tagsJson,
      Map<String, Object> passportSnapshot) {
    private SourcePlan {
      characteristicIds = List.copyOf(characteristicIds);
      passportSnapshot =
          java.util.Collections.unmodifiableMap(new LinkedHashMap<>(passportSnapshot));
    }
  }

  /** Resolved final passport used only when completed inventory observed a present passport. */
  record MaterializationPassport(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      List<UUID> characteristicIds,
      Boolean linoleum) {
    MaterializationPassport {
      characteristicIds = List.copyOf(characteristicIds);
    }
  }
}
