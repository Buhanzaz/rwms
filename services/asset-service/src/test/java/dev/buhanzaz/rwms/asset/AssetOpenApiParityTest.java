package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Enforces strict canonical OpenAPI parsing and exact asset controller-path parity. */
class AssetOpenApiParityTest {
  private static final String ASSET_PACKAGE = "dev.buhanzaz.rwms.asset";
  private static final Set<String> OPENAPI_METHODS = Set.of("get", "post", "put", "delete");

  @Test
  void openApiInventoryExactlyMatchesAssetControllers() throws Exception {
    assertThat(openApiEndpoints(openApi()))
        .containsExactlyInAnyOrderElementsOf(controllerEndpoints());
  }

  @Test
  void contractIncludesPublicAndPrivateVersionedAssetSurfacesAndResolvableReferences()
      throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");

    assertThat(paths).containsKeys(
        "/api/asset/v1/rental-items",
        "/api/asset/v1/rental-items/available",
        "/api/asset/v1/rental-items/availability",
        "/api/asset/v1/rental-items/{id}/manual-notes",
        "/api/asset/v1/equipment/transfers",
        "/api/asset/v1/administrative-corrections",
        "/api/asset/v1/operations/outbox/{eventId}/requeue",
        "/api/asset/v1/classifiers",
        "/api/internal/asset/v1/maintenance/operation-leases",
        "/api/internal/asset/v1/maintenance/operation-leases/{id}/renew",
        "/api/internal/asset/v1/maintenance/operation-leases/{id}/release",
        "/api/internal/asset/v1/maintenance/cabin-characteristics",
        "/api/internal/asset/v1/maintenance/equipment-catalog",
        "/api/internal/asset/v1/maintenance/equipment-catalog/snapshots",
        "/api/internal/asset/v1/maintenance/furniture-custody",
        "/api/internal/asset/v1/maintenance/furniture-custody/{claimId}/return-to-stock",
        "/api/internal/asset/v1/maintenance/rental-items/{id}/snapshot",
        "/api/internal/asset/v1/maintenance/property-assets/{assetKind}/{assetId}/snapshot",
        "/api/internal/asset/v1/maintenance/property-dispositions/{decisionId}/prepare",
        "/api/internal/asset/v1/maintenance/property-dispositions/{decisionId}/apply",
        "/api/internal/asset/v1/maintenance/rental-items/{rentalItemId}/characteristics/{characteristicId}",
        "/api/internal/asset/v1/maintenance/rental-items/{id}/fenced-status",
        "/api/internal/asset/v1/logistics/rental-items/{id}/snapshot",
        "/api/internal/asset/v1/logistics/equipment-availability",
        "/api/internal/asset/v1/logistics/operation-leases",
        "/api/internal/asset/v1/logistics/return-equipment-receipts",
        "/api/internal/asset/v1/logistics/operation-leases/{id}/renew",
        "/api/internal/asset/v1/logistics/operation-leases/{id}/release",
        "/api/internal/asset/v1/logistics/rental-items/{id}/effects",
        "/api/internal/asset/v1/logistics/equipment-holds",
        "/api/internal/asset/v1/logistics/equipment-holds/{id}/renew",
        "/api/internal/asset/v1/logistics/equipment-holds/{id}/commit",
        "/api/internal/asset/v1/logistics/equipment-holds/{id}/release",
        "/api/internal/asset/v1/logistics/equipment-movement-reservations",
        "/api/internal/asset/v1/logistics/equipment-movement-reservations/{id}/release",
        "/api/internal/asset/v1/logistics/equipment-movement-reservations/execute",
        "/api/internal/asset/v1/logistics/orders/{orderId}/unit-candidates",
        "/api/internal/asset/v1/logistics/orders/{orderId}/units",
        "/api/internal/asset/v1/logistics/orders/{orderId}/units/release-all",
        "/api/internal/asset/v1/logistics/orders/{orderId}/units/{rentalItemId}/release",
        "/api/internal/asset/v1/logistics/orders/{orderId}/units/replace",
        "/api/internal/asset/v1/logistics/orders/{orderId}/equipment-reservations",
        "/api/internal/asset/v1/logistics/orders/{orderId}/equipment-movement-plan",
        "/api/internal/asset/v1/logistics/rental-items/{rentalItemId}/furniture-movement-plan",
        "/api/internal/asset/v1/logistics/cabin-catalog",
        "/api/internal/asset/v1/inventory/assets/{assetId}",
        "/api/internal/asset/v1/inventory/captures",
        "/api/internal/asset/v1/inventory/captures/{captureId}",
        "/api/internal/asset/v1/inventory/captures/{captureId}/members",
        "/api/internal/asset/v1/inventory/number-resolutions",
        "/api/internal/asset/v1/inventory/validations",
        "/api/internal/asset/v1/inventory/furniture-snapshots",
        "/api/internal/asset/v1/inventory/furniture-reconciliations/{inventoryId}",
        "/api/internal/asset/v1/inventory/outcomes/{inventoryId}/findings/{findingId}",
        "/api/internal/asset/v1/inventory/source-assets");
    assertThat(
            child(
                    child(
                        child(paths, "/api/asset/v1/rental-items/available"),
                        "get"),
                    "responses")
                .keySet())
        .containsExactlyInAnyOrder("200", "400", "401", "403");
    assertThat(
            child(
                    child(
                        child(paths, "/api/asset/v1/rental-items/availability"),
                        "post"),
                    "responses")
                .keySet())
        .containsExactlyInAnyOrder("200", "400", "401", "403");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> createRentalItem = child(schemas, "CreateRentalItemRequest");
    assertThat(list(createRentalItem.get("required")))
        .contains("warehouseId", "number", "linoleum");
    assertThat(child(child(createRentalItem, "properties"), "linoleum"))
        .containsEntry("type", "boolean");
    Map<String, Object> inventorySourceAsset =
        child(schemas, "InventorySourceAssetRequest");
    assertThat(list(inventorySourceAsset.get("required")))
        .containsExactly("inventoryId", "findingId", "warehouseId", "number", "linoleum");
    assertThat(child(child(inventorySourceAsset, "properties"), "linoleum"))
        .containsEntry("type", "boolean");
    assertThat(child(inventorySourceAsset, "properties").keySet())
        .contains(
            "rentalTypeId",
            "dimensionId",
            "finishingId",
            "characteristicIds",
            "rentalType",
            "dimensions",
            "finishing",
            "characteristics");
    assertThat(list(inventorySourceAsset.get("oneOf"))).hasSize(2);
    Map<String, Object> inventoryOutcomeRequest = child(schemas, "InventoryOutcomeRequest");
    assertThat(list(inventoryOutcomeRequest.get("required")))
        .containsExactly(
            "warehouseId",
            "assetId",
            "inventoryCompletedAt",
            "finalPlanVersion",
            "finalPlanSha256",
            "findingRevision",
            "desiredStatus",
            "passportObservation",
            "passportObservationSha256");
    Map<String, Object> passportObservation =
        child(schemas, "InventoryOutcomePassportObservation");
    assertThat(list(passportObservation.get("oneOf"))).hasSize(2);
    assertThat(passportObservation.toString())
        .contains(
            "ABSENT",
            "PRESENT",
            "rentalType",
            "dimensions",
            "finishing",
            "category",
            "characteristics",
            "linoleum");
    assertThat(list(child(schemas, "InventoryOutcomeStatus").get("enum")))
        .containsExactly("FREE", "REPAIR", "CAPITAL_REPAIR");
    assertThat(list(child(schemas, "InventoryOutcomeResponse").get("required")))
        .containsExactly(
            "inventoryId",
            "findingId",
            "assetId",
            "assetVersion",
            "status",
            "releasedOrderUnitReservationIds",
            "releasedOperationLeaseIds",
            "releasedPresentationHoldIds",
            "transferSuperseded");
    assertThat(list(child(schemas, "RentalItem").get("required")))
        .contains(
            "id",
            "version",
            "number",
            "status",
            "passport",
            "tags",
            "contents",
            "activeOrderReservation");
    assertThat(list(child(schemas, "ActiveOrderReservation").get("required")))
        .containsExactly(
            "reservationId", "orderId", "clientId", "tenantSnapshot", "reservedAt");
    assertThat(list(child(schemas, "EquipmentContent").get("required")))
        .containsExactly(
            "equipmentId", "equipmentName", "quantity", "locationKind");
    assertThat(list(child(schemas, "OrderActorRole").get("enum")))
        .containsExactly(
            "SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER", "VIEWER");
    assertThat(list(child(schemas, "ReserveOrderUnitRequest").get("required")))
        .containsExactly(
            "warehouseId",
            "rentalItemId",
            "clientId",
            "tenantSnapshot",
            "actorSubjectId",
            "actorRole");
    assertThat(list(child(schemas, "OrderUnitReservation").get("required")))
        .contains(
            "reservationId",
            "orderId",
            "rentalItemId",
            "warehouseId",
            "state",
            "replayed",
            "unit");
    Map<String, Object> cabinSearchGroup = child(schemas, "CabinSearchGroup");
    assertThat(list(cabinSearchGroup.get("required"))).containsExactly("quantity");
    assertThat(child(cabinSearchGroup, "properties").keySet())
        .containsExactly(
            "cabinType", "finish", "dimensions", "category", "characteristics", "linoleum", "quantity");
    assertThat(child(child(cabinSearchGroup, "properties"), "characteristics"))
        .containsEntry("maxLength", 2000)
        .containsEntry("type", List.of("string", "null"));
    assertThat(child(child(cabinSearchGroup, "properties"), "linoleum"))
        .containsEntry("type", List.of("boolean", "null"));
    assertThat(
            child(
                child(child(schemas, "CabinSearchRequest"), "properties"),
                "groups"))
        .containsEntry("maxItems", 20);
    assertThat(
            child(
                child(child(schemas, "CabinSearchRequest"), "properties"),
                "resultMode"))
        .containsEntry("default", "REPLACE");
    assertThat(
            child(
                child(child(schemas, "CabinSearchResponse"), "properties"),
                "groups"))
        .containsEntry("maxItems", 20);
    Map<String, Object> cabinSearch =
        child(paths, "/api/internal/asset/v1/logistics/cabin-searches");
    Map<String, Object> cabinSearchPost = child(cabinSearch, "post");
    assertThat(list(cabinSearchPost.get("parameters")))
        .singleElement()
        .satisfies(
            parameter ->
                assertThat(map(parameter).get("$ref"))
                    .isEqualTo("#/components/parameters/IdempotencyKey"));
    assertThat(child(cabinSearchPost, "responses").keySet())
        .containsExactlyInAnyOrder("200", "400", "403", "409");
    assertThat(child(child(cabinSearchPost, "responses"), "200").toString())
        .contains("Idempotency-Replayed", "true");
    Map<String, Object> cabinFacets =
        child(paths, "/api/internal/asset/v1/logistics/cabin-facets");
    List<Object> cabinFacetParameters = list(child(cabinFacets, "get").get("parameters"));
    assertThat(cabinFacetParameters).hasSize(2);
    Map<String, Object> holdScopeParameter = map(cabinFacetParameters.get(1));
    assertThat(holdScopeParameter)
        .containsEntry("name", "holdScopeId")
        .containsEntry("in", "query")
        .containsEntry("required", false);
    assertThat(child(holdScopeParameter, "schema"))
        .containsEntry("type", "string")
        .containsEntry("format", "uuid");
    assertThat(list(child(schemas, "CabinFacetResponse").get("required")))
        .contains("categories", "characteristics", "typeDimensions");
    assertThat(child(schemas, "CabinTypeDimensions").toString())
        .contains("cabinType", "dimensions", "uniqueItems");
    Map<String, Object> cabinCatalog =
        child(paths, "/api/internal/asset/v1/logistics/cabin-catalog");
    assertThat(child(cabinCatalog, "get").get("operationId"))
        .isEqualTo("readPresentationCabinCatalog");
    assertThat(list(child(cabinCatalog, "get").get("parameters")))
        .extracting(parameter -> map(parameter).getOrDefault("name", map(parameter).get("$ref")))
        .containsExactly(
            "#/components/parameters/WarehouseIdQuery", "query", "page", "size");
    assertThat(map(list(child(cabinCatalog, "get").get("parameters")).get(1)))
        .containsEntry("name", "query")
        .containsEntry("required", true);
    assertThat(list(child(schemas, "CabinCatalogPage").get("required")))
        .containsExactly("warehouseId", "content", "page", "size", "totalElements", "totalPages");
    assertThat(list(child(schemas, "AvailableCabin").get("required")))
        .contains("status", "contents");
    assertThat(list(child(schemas, "ReplacePresentationHoldsResponse").get("required")))
        .containsExactly("presentationId", "holds", "cabins");
    assertThat(list(child(schemas, "OrderEquipmentRequirement").get("required")))
        .containsExactly("equipmentId", "quantity");
    assertThat(list(child(schemas, "ReplaceOrderEquipmentReservationsRequest").get("required")))
        .containsExactly("warehouseId", "actorSubjectId", "actorRole", "units");
    assertThat(list(child(schemas, "OrderFurnitureMovementPlanRequest").get("required")))
        .containsExactly(
            "warehouseId",
            "rentalItemId",
            "replacementForRentalItemId",
            "requirements",
            "units");
    assertThat(list(child(schemas, "OrderFurnitureMovementPlan").get("required")))
        .containsExactly("orderId", "rentalItemId", "unitNumber", "lines");
    assertThat(list(child(schemas, "CabinFurnitureMovementPlanRequest").get("required")))
        .containsExactly("warehouseId", "requirements");
    assertThat(list(child(schemas, "CabinFurnitureRequirement").get("required")))
        .containsExactly("equipmentId", "quantity");
    assertThat(list(child(schemas, "CabinFurnitureMovementPlan").get("required")))
        .containsExactly("rentalItemId", "unitNumber", "lines");
    assertThat(list(child(schemas, "EquipmentTotals").get("required")))
        .contains("reservedQuantity", "availableQuantity");
    Map<String, Object> equipmentTransfer = child(paths, "/api/asset/v1/equipment/transfers");
    assertThat(child(equipmentTransfer, "post").get("operationId"))
        .isEqualTo("transferEquipmentWithinWarehouse");
    assertThat(child(equipmentTransfer, "post").get("description").toString())
        .contains("same warehouse", "logistics document");
    assertThat(list(child(schemas, "TransferEquipmentRequest").get("required")))
        .containsExactly(
            "equipmentId",
            "sourceWarehouseId",
            "sourceLocationKind",
            "sourceExpectedVersion",
            "targetWarehouseId",
            "targetLocationKind",
            "targetExpectedVersion",
            "quantity");
    assertThat(list(child(schemas, "TransferBalanceLocationKind").get("enum")))
        .containsExactly("STOCK", "CABIN_NON_RENTED", "CABIN_RENTED");
    Map<String, Object> correction = child(paths, "/api/asset/v1/administrative-corrections");
    assertThat(child(correction, "post").get("operationId"))
        .isEqualTo("createAdministrativeAssetCorrection");
    assertThat(child(correction, "post").get("description").toString())
        .contains("not a physical movement", "retrospective logistics document");
    assertThat(list(child(schemas, "AdministrativeAssetCorrection").get("required")))
        .containsExactly(
            "id",
            "assetKind",
            "assetId",
            "sourceWarehouseId",
            "targetWarehouseId",
            "quantity",
            "reason",
            "evidenceLink",
            "requestSha256",
            "appliedBy",
            "appliedAt");
    assertThat(child(child(schemas, "CreateCabinAdministrativeCorrectionRequest"), "properties")
            .get("evidenceLink")
            .toString())
        .contains("uri", "HTTPS", "non-empty host");
    assertThat(list(child(schemas, "MaintenanceStatusAction").get("enum")))
        .containsExactly(
            "QUEUE_FOR_REPAIR",
            "QUEUE_FOR_CAPITAL_REPAIR",
            "COMPLETE_EMPTY_ESTIMATE",
            "COMPLETE_EMPTY_REPAIR",
            "MARK_PENDING_ACCEPTANCE",
            "ACCEPT_REPAIR",
            "WRITE_OFF");
    Map<String, Object> maintenanceSnapshot = child(schemas, "MaintenanceRentalItemSnapshot");
    assertThat(list(maintenanceSnapshot.get("required")))
        .containsExactly("id", "version", "warehouseId", "status");
    assertThat(child(maintenanceSnapshot, "properties").keySet())
        .containsExactly("id", "version", "warehouseId", "status");
    assertThat(list(child(schemas, "MaintenanceCharacteristicApplication").get("required")))
        .containsExactly(
            "rentalItemId",
            "characteristicId",
            "added",
            "rentalItemVersion");
    Map<String, Object> ensureFurniturePath = child(
        paths, "/api/internal/asset/v1/maintenance/equipment-catalog");
    Map<String, Object> ensureFurnitureOperation = child(ensureFurniturePath, "post");
    assertThat(ensureFurnitureOperation.get("operationId"))
        .isEqualTo("createMaintenanceFurnitureEquipment");
    assertThat(child(ensureFurnitureOperation, "responses").keySet())
        .containsExactlyInAnyOrder("201", "400", "403", "409");
    assertThat(child(child(ensureFurnitureOperation, "responses"), "201").toString())
        .contains("Idempotency-Replayed", "true");
    assertThat(child(
            child(
                child(
                    child(child(ensureFurnitureOperation, "responses"), "201"),
                    "content"),
                "application/json"),
            "schema")
        .get("$ref"))
        .isEqualTo("#/components/schemas/MaintenanceFurnitureEquipment");
    assertThat(child(
            child(
                child(
                    child(ensureFurnitureOperation, "requestBody"),
                    "content"),
                "application/json"),
            "schema")
        .get("$ref"))
        .isEqualTo("#/components/schemas/EnsureMaintenanceFurnitureEquipmentRequest");
    Map<String, Object> ensureFurnitureRequest =
        child(schemas, "EnsureMaintenanceFurnitureEquipmentRequest");
    assertThat(ensureFurnitureRequest.get("additionalProperties")).isEqualTo(false);
    assertThat(list(ensureFurnitureRequest.get("required")))
        .containsExactly("externalReferenceId", "equipmentName");
    assertThat(child(ensureFurnitureRequest, "properties").keySet())
        .containsExactly(
            "externalReferenceId",
            "equipmentName",
            "expectedEquipmentVersion",
            "maximumPerCabin");
    Map<String, Object> maintenanceFurnitureEquipment =
        child(schemas, "MaintenanceFurnitureEquipment");
    assertThat(maintenanceFurnitureEquipment.get("additionalProperties")).isEqualTo(false);
    assertThat(list(maintenanceFurnitureEquipment.get("required")))
        .containsExactly(
            "externalReferenceId",
            "equipmentId",
            "equipmentName",
            "equipmentVersion",
            "maximumPerCabin");
    assertThat(child(maintenanceFurnitureEquipment, "properties").keySet())
        .containsExactly(
            "externalReferenceId",
            "equipmentId",
            "equipmentName",
            "equipmentVersion",
            "maximumPerCabin");
    Map<String, Object> pendingReturn = child(schemas, "MaintenanceFurniturePendingReturn");
    assertThat(list(pendingReturn.get("required")))
        .containsExactly("equipmentId", "expectedSourceBalanceVersion", "quantity");
    assertThat(child(pendingReturn, "properties").keySet())
        .containsExactly("equipmentId", "expectedSourceBalanceVersion", "quantity");
    Map<String, Object> maintenanceFencedStatus =
        child(schemas, "MaintenanceFencedStatusRequest");
    assertThat(list(maintenanceFencedStatus.get("required")))
        .contains("furniturePendingReturns");
    assertThat(child(maintenanceFencedStatus, "properties").keySet())
        .contains("furniturePendingReturns")
        .doesNotContain("estimateId", "furnitureLosses");
    assertThat(maintenanceFencedStatus.toString())
        .doesNotContain("RentalItemStatus", "status=");
    Map<String, Object> custodyClaim = child(schemas, "MaintenanceFurnitureCustodyClaim");
    assertThat(list(custodyClaim.get("required"))).contains(
        "custodyVersion", "sourceBalanceVersion", "unresolvedQuantity", "availableForDispositionQuantity");
    Map<String, Object> custodyReturn =
        child(schemas, "ReturnMaintenanceFurnitureCustodyToStockRequest");
    assertThat(list(custodyReturn.get("required"))).containsExactly(
        "expectedCustodyVersion", "expectedStockBalanceVersion", "quantity", "returnReferenceId");
    assertThat(list(child(schemas, "LogisticsLeaseOwnerType").get("enum")))
        .containsExactly(
            "LOGISTICS_RETURN",
            "LOGISTICS_SHIPMENT",
            "LOGISTICS_TRANSFER");
    assertThat(list(child(
        schemas, "LogisticsEquipmentMovementReservationOwnerType").get("enum")))
        .containsExactly(
            "LOGISTICS_EQUIPMENT_MOVEMENT", "MAINTENANCE_DISPOSITION_MOVEMENT");
    assertThat(list(child(
        schemas, "AcquireLogisticsEquipmentMovementReservationRequest").get("required")))
        .containsExactly(
            "movementId",
            "lineId",
            "purpose",
            "equipmentId",
            "sourceWarehouseId",
            "sourceLocationKind",
            "expectedSourceBalanceVersion",
            "quantity",
            "reservedUntil");
    assertThat(child(
            child(schemas, "AcquireLogisticsEquipmentMovementReservationRequest"),
            "properties")
        .keySet())
        .contains(
            "orderId",
            "targetRentalItemId",
            "units",
            "replacementSourceReservationId");
    assertThat(child(schemas, "ExecuteLogisticsEquipmentMovementReservationsRequest").toString())
        .doesNotContain("expectedTargetBalanceVersion");
    assertThat(list(child(
        schemas, "LogisticsEquipmentMovementReservation").get("required")))
        .contains("equipmentName", "sourceBalanceId", "executedAt");
    assertThat(list(child(schemas, "LogisticsRentalItemAction").get("enum")))
        .containsExactly(
            "RETURN_INTAKE",
            "RETURN_SETTLE_FREE",
            "RETURN_SETTLE_SHORTAGE",
            "SHIPMENT_CONFIRM",
            "TRANSFER_DEPART",
            "TRANSFER_ARRIVE");
    assertThat(child(schemas, "LogisticsRentalItemSnapshot").toString())
        .doesNotContain(
            "passport",
            "comment",
            "identityMatchKey",
            "equipmentCode",
            "locationKind");
    assertThat(child(schemas, "LogisticsFencedEffectRequest").toString())
        .doesNotContain("RentalItemStatus", "status=");
    assertThat(list(child(schemas, "TransferAssetStatus").get("enum")))
        .containsExactly("FREE", "REPAIR");
    assertThat(child(child(schemas, "LogisticsFencedEffectRequest"), "properties"))
        .containsKey("transferAssetStatus");
    assertThat(paths.keySet().stream().filter(path -> path.contains("/logistics/")).toList())
        .noneMatch(path -> path.contains("classifier") || path.contains("passport")
            || path.contains("manual-note") || path.contains("warehouse"));
    assertThat(list(child(schemas, "InventoryCaptureMember").get("required")))
        .contains("assetId", "version", "warehouseId", "status", "displayCanonicalNumber",
            "identityMatchKey", "passportSnapshot", "contentsSnapshot");
    Map<String, Object> currentInventoryAsset =
        child(schemas, "InventoryAssetCurrentSnapshot");
    assertThat(list(currentInventoryAsset.get("required")))
        .containsExactly(
            "assetId",
            "version",
            "warehouseId",
            "status",
            "displayCanonicalNumber",
            "identityMatchKey",
            "tenantSnapshot",
            "passportSnapshot",
            "contentsSnapshot");
    assertThat(child(currentInventoryAsset, "properties").keySet())
        .containsExactly(
            "assetId",
            "version",
            "warehouseId",
            "status",
            "displayCanonicalNumber",
            "identityMatchKey",
            "tenantSnapshot",
            "passportSnapshot",
            "contentsSnapshot");
    Map<String, Object> currentInventoryAssetRead = child(
        paths, "/api/internal/asset/v1/inventory/assets/{assetId}");
    assertThat(child(currentInventoryAssetRead, "get").get("operationId"))
        .isEqualTo("readCurrentInventoryAssetSnapshot");
    Map<String, Object> currentInventoryAssetReadSuccess = child(
        child(child(currentInventoryAssetRead, "get"), "responses"), "200");
    Map<String, Object> currentInventoryAssetReadBody = child(
        child(currentInventoryAssetReadSuccess, "content"), "application/json");
    assertThat(child(currentInventoryAssetReadBody, "schema").get("$ref"))
        .isEqualTo("#/components/schemas/InventoryAssetCurrentSnapshot");
    assertThat(list(child(schemas, "InventoryNumberResolutionRequest").get("required")))
        .containsExactly("warehouseId", "number");
    assertThat(child(child(schemas, "InventoryNumberResolutionRequest"), "properties").keySet())
        .containsExactly("warehouseId", "number");
    Map<String, Object> furnitureSnapshotRequest =
        child(schemas, "InventoryFurnitureSnapshotRequest");
    assertThat(list(furnitureSnapshotRequest.get("required")))
        .containsExactly("warehouseId", "assetIds");
    assertThat(child(furnitureSnapshotRequest, "properties").keySet())
        .containsExactly("warehouseId", "assetIds");
    assertThat(list(child(schemas, "InventoryFurnitureSnapshot").get("required")))
        .containsExactly("warehouseId", "snapshotSha256", "items");
    Map<String, Object> furnitureSnapshotItem = child(schemas, "InventoryFurnitureSnapshotItem");
    assertThat(list(furnitureSnapshotItem.get("required")))
        .containsExactly(
            "equipmentId",
            "catalogVersion",
            "equipmentName",
            "currentStockQuantity",
            "stockBalanceVersion",
            "cabins");
    assertThat(child(furnitureSnapshotItem, "properties").keySet()).containsExactly(
        "equipmentId",
        "catalogVersion",
        "equipmentName",
        "currentStockQuantity",
        "stockBalanceVersion",
        "cabins");
    assertThat(child(child(furnitureSnapshotItem, "properties"), "stockBalanceVersion").toString())
        .contains("ExpectedVersion", "null");
    assertThat(list(child(schemas, "InventoryFurnitureSnapshotCabin").get("required")))
        .containsExactly(
            "assetId", "assetVersion", "displayCanonicalNumber", "status", "currentQuantity");
    Map<String, Object> furnitureReconciliationRequest =
        child(schemas, "InventoryFurnitureReconciliationRequest");
    assertThat(list(furnitureReconciliationRequest.get("required")))
        .containsExactly("warehouseId", "expectedSnapshotSha256", "reviewSha256", "items");
    assertThat(list(child(schemas, "InventoryFurnitureReconciliationItem").get("required")))
        .containsExactly("equipmentId", "catalogVersion", "stockQuantity", "cabins");
    assertThat(list(child(schemas, "InventoryFurnitureReconciliationCabin").get("required")))
        .containsExactly("assetId", "quantity");
    Map<String, Object> furnitureSnapshotPath =
        child(paths, "/api/internal/asset/v1/inventory/furniture-snapshots");
    assertThat(child(furnitureSnapshotPath, "post").get("operationId"))
        .isEqualTo("readInventoryFurnitureSnapshot");
    Map<String, Object> furnitureReconciliationPath = child(
        paths, "/api/internal/asset/v1/inventory/furniture-reconciliations/{inventoryId}");
    assertThat(child(furnitureReconciliationPath, "put").get("operationId"))
        .isEqualTo("reconcileInventoryFurniture");
    assertThat(child(child(furnitureReconciliationPath, "put"), "responses").get("204").toString())
        .contains("Idempotency-Replayed", "true");
    assertThat(child(schemas, "InventorySourceAssetRequest").toString())
        .doesNotContain("expectedVersion", "leaseId", "fencingToken", "status");
    assertThat(paths.keySet().stream().filter(path -> path.contains("/inventory/")).toList())
        .noneMatch(path -> path.contains("hold") || path.contains("lease")
            || path.contains("fenced-status"));
    assertThat(child(child(child(paths,
        "/api/internal/asset/v1/inventory/source-assets"), "post"), "responses")
        .get("200").toString()).contains("Idempotency-Replayed", "true");
    assertAllLocalReferencesResolve(document, document);
  }

  private Set<Endpoint> controllerEndpoints() throws ClassNotFoundException {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    Set<Endpoint> endpoints = new LinkedHashSet<>();
    for (var component : scanner.findCandidateComponents(ASSET_PACKAGE)) {
      Class<?> controller = ClassUtils.forName(component.getBeanClassName(), getClass().getClassLoader());
      RequestMapping controllerMapping =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping methodMapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) continue;
        assertThat(methodMapping.method()).isNotEmpty();
        for (String controllerPath : paths(controllerMapping)) {
          for (String methodPath : paths(methodMapping)) {
            for (RequestMethod requestMethod : methodMapping.method()) {
              endpoints.add(new Endpoint(
                  normalizePath(controllerPath + "/" + methodPath),
                  requestMethod.name().toLowerCase(Locale.ROOT)));
            }
          }
        }
      }
    }
    return endpoints;
  }

  private Map<String, Object> openApi() throws Exception {
    Path contract = Path.of(System.getProperty("rwms.contracts.dir"), "openapi/asset-service.yaml");
    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml(new SafeConstructor(loaderOptions)).load(input);
    }
  }

  private Set<Endpoint> openApiEndpoints(Map<String, Object> document) {
    Set<Endpoint> endpoints = new LinkedHashSet<>();
    for (var path : child(document, "paths").entrySet()) {
      Map<String, Object> item = map(path.getValue());
      for (String method : item.keySet()) {
        if (OPENAPI_METHODS.contains(method.toLowerCase(Locale.ROOT))) {
          endpoints.add(new Endpoint(normalizePath(path.getKey()), method.toLowerCase(Locale.ROOT)));
        }
      }
    }
    return endpoints;
  }

  private void assertAllLocalReferencesResolve(Object node, Map<String, Object> root) {
    if (node instanceof Map<?, ?> map) {
      Object reference = map.get("$ref");
      if (reference instanceof String path && path.startsWith("#/")) {
        Object resolved = root;
        for (String segment : path.substring(2).split("/")) {
          resolved = map(resolved).get(segment.replace("~1", "/").replace("~0", "~"));
          assertThat(resolved).as("resolved %s", path).isNotNull();
        }
      }
      map.values().forEach(value -> assertAllLocalReferencesResolve(value, root));
    } else if (node instanceof Collection<?> collection) {
      collection.forEach(value -> assertAllLocalReferencesResolve(value, root));
    }
  }

  private static List<String> paths(RequestMapping mapping) {
    if (mapping == null) return List.of("");
    String[] declared = mapping.path().length == 0 ? mapping.value() : mapping.path();
    return declared.length == 0 ? List.of("") : Arrays.asList(declared);
  }

  private static String normalizePath(String value) {
    String normalized = value.replaceAll("/{2,}", "/");
    if (normalized.length() > 1 && normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    return normalized.startsWith("/") ? normalized : "/" + normalized;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> map, String name) {
    return (Map<String, Object>) map.get(name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  /** One normalized HTTP method/path pair used by the parity comparison. */
  private record Endpoint(String path, String method) {}
}
