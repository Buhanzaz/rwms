package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.ORDER;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Reads asset-owned cabin and furniture pricing references through private service credentials.
 * This boundary validates complete identities and wire facts; logistics alone owns the prices.
 */
final class LogisticsAssetPricingDependencyClient {
  private static final String ASSET_CLIENT = "logistics-asset";
  private static final String ASSET_SCOPE = "asset.logistics";

  private final LogisticsOAuthHttpTransport transport;
  private final String assetBase;

  LogisticsAssetPricingDependencyClient(LogisticsOAuthHttpTransport transport, String assetBase) {
    this.transport = transport;
    this.assetBase = assetBase;
  }

  EquipmentPricingCatalog readEquipmentPricingCatalog() {
    EquipmentPricingCatalogResponse response =
        transport.get(
            assetBase + "/equipment-pricing-catalog",
            EquipmentPricingCatalogResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty furniture pricing catalog",
            DEFAULT);
    var identities = new HashSet<UUID>();
    if (response.items() == null
        || response.items().stream()
            .anyMatch(
                value ->
                    value == null
                        || value.id() == null
                        || !identities.add(value.id())
                        || value.name() == null
                        || value.name().isBlank()
                        || value.name().length() > 255
                        || value.active() == null)) {
      throw malformed("Asset-service returned an invalid furniture pricing catalog");
    }
    return new EquipmentPricingCatalog(
        response.items().stream()
            .map(
                value -> new EquipmentPricingCatalogValue(value.id(), value.name(), value.active()))
            .toList());
  }

  CabinPricingCatalog readCabinPricingCatalog() {
    CabinPricingCatalogResponse response =
        transport.get(
            assetBase + "/cabin-pricing-catalog",
            CabinPricingCatalogResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty pricing catalog",
            DEFAULT);
    var types = pricingCatalogValues(response.types());
    var categories = pricingCatalogValues(response.categories());
    var identities = new HashSet<UUID>();
    types.forEach(value -> identities.add(value.id()));
    if (categories.stream().anyMatch(value -> !identities.add(value.id()))) {
      throw malformed("Asset-service returned duplicate pricing catalog identities");
    }
    return new CabinPricingCatalog(types, categories);
  }

  CabinPricingReferences readCabinPricingReferences(UUID warehouseId, List<UUID> rentalItemIds) {
    CabinPricingReferencesResponse response =
        transport.postWithoutIdempotency(
            assetBase + "/cabin-pricing-references",
            new CabinIdsRequest(warehouseId, rentalItemIds),
            CabinPricingReferencesResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned empty pricing references",
            ORDER);
    var received = new HashSet<UUID>();
    if (!warehouseId.equals(response.warehouseId())
        || response.cabins() == null
        || response.cabins().stream()
            .anyMatch(
                value ->
                    value == null
                        || value.rentalItemId() == null
                        || !received.add(value.rentalItemId())
                        || value.rentalItemVersion() == null
                        || value.rentalItemVersion() < 0
                        || value.rentalTypeId() == null
                        || value.categoryId() == null)
        || !received.equals(new HashSet<>(rentalItemIds))) {
      throw malformed("Asset-service returned invalid or incomplete pricing references");
    }
    return new CabinPricingReferences(
        warehouseId,
        response.cabins().stream()
            .map(
                value ->
                    new CabinPricingReference(
                        value.rentalItemId(),
                        value.rentalItemVersion(),
                        value.rentalTypeId(),
                        value.categoryId()))
            .toList());
  }

  private static List<CabinPricingCatalogValue> pricingCatalogValues(
      List<CabinPricingCatalogValueResponse> values) {
    var identities = new HashSet<UUID>();
    if (values == null
        || values.stream()
            .anyMatch(
                value ->
                    value == null
                        || value.id() == null
                        || !identities.add(value.id())
                        || value.name() == null
                        || value.name().isBlank()
                        || value.active() == null)) {
      throw malformed("Asset-service returned an invalid pricing catalog");
    }
    return values.stream()
        .map(value -> new CabinPricingCatalogValue(value.id(), value.name(), value.active()))
        .toList();
  }

  /** Nullable flag detects incomplete wire data instead of inventing an inactive catalog value. */
  private record CabinPricingCatalogValueResponse(UUID id, String name, Boolean active) {}

  /** Nullable activity rejects incomplete furniture wire facts without inventing defaults. */
  private record EquipmentPricingCatalogValueResponse(UUID id, String name, Boolean active) {}

  /** Only the asset-owned furniture category is exposed for monthly unit tariffs. */
  private record EquipmentPricingCatalogResponse(
      List<EquipmentPricingCatalogValueResponse> items) {}

  /** Complete asset-owned taxonomy returned independently of current cabin usage. */
  private record CabinPricingCatalogResponse(
      List<CabinPricingCatalogValueResponse> types,
      List<CabinPricingCatalogValueResponse> categories) {}

  /** Nullable wire version rejects a missing version rather than silently reading it as zero. */
  private record CabinPricingReferenceResponse(
      UUID rentalItemId, Long rentalItemVersion, UUID rentalTypeId, UUID categoryId) {}

  /** Warehouse-scoped complete result for the requested cabin set. */
  private record CabinPricingReferencesResponse(
      UUID warehouseId, List<CabinPricingReferenceResponse> cabins) {}

  /** Warehouse-scoped read request, never a reservation or pricing mutation. */
  private record CabinIdsRequest(UUID warehouseId, List<UUID> rentalItemIds) {}
}
