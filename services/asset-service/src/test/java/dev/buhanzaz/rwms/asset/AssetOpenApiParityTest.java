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
import org.yaml.snakeyaml.Yaml;

class AssetOpenApiParityTest {
  private static final String API_PACKAGE = "dev.buhanzaz.rwms.asset.api";
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
        "/api/asset/v1/rental-items/{id}/manual-notes",
        "/api/asset/v1/equipment/transfers",
        "/api/asset/v1/equipment/dispositions",
        "/api/asset/v1/classifiers",
        "/api/internal/asset/v1/equipment-holds",
        "/api/internal/asset/v1/equipment-holds/{id}/commit",
        "/api/internal/asset/v1/operation-leases",
        "/api/internal/asset/v1/rental-items/{id}/fenced-status",
        "/api/internal/asset/v1/maintenance/operation-leases",
        "/api/internal/asset/v1/maintenance/operation-leases/{id}/renew",
        "/api/internal/asset/v1/maintenance/operation-leases/{id}/release",
        "/api/internal/asset/v1/maintenance/cabin-characteristics",
        "/api/internal/asset/v1/maintenance/equipment-catalog",
        "/api/internal/asset/v1/maintenance/rental-items/{id}/snapshot",
        "/api/internal/asset/v1/maintenance/rental-items/{rentalItemId}/characteristics/{characteristicId}",
        "/api/internal/asset/v1/maintenance/rental-items/{id}/fenced-status",
        "/api/internal/asset/v1/logistics/rental-items/{id}/snapshot",
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
        "/api/internal/asset/v1/logistics/orders/{orderId}/equipment-reservations",
        "/api/internal/asset/v1/logistics/orders/{orderId}/equipment-movement-plan",
        "/api/internal/asset/v1/logistics/rental-items/{rentalItemId}/furniture-movement-plan",
        "/api/internal/asset/v1/inventory/assets/{assetId}",
        "/api/internal/asset/v1/inventory/captures",
        "/api/internal/asset/v1/inventory/captures/{captureId}",
        "/api/internal/asset/v1/inventory/captures/{captureId}/members",
        "/api/internal/asset/v1/inventory/number-resolutions",
        "/api/internal/asset/v1/inventory/validations",
        "/api/internal/asset/v1/inventory/source-assets");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> createRentalItem = child(schemas, "CreateRentalItemRequest");
    assertThat(list(createRentalItem.get("required")))
        .contains("warehouseId", "number", "linoleum");
    assertThat(child(child(createRentalItem, "properties"), "linoleum"))
        .containsEntry("type", "boolean");
    Map<String, Object> inventorySourceAsset =
        child(schemas, "InventorySourceAssetRequest");
    assertThat(list(inventorySourceAsset.get("required")))
        .contains("inventoryId", "findingId", "warehouseId", "number", "linoleum");
    assertThat(child(child(inventorySourceAsset, "properties"), "linoleum"))
        .containsEntry("type", "boolean");
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
                child(child(schemas, "CabinSearchResponse"), "properties"),
                "groups"))
        .containsEntry("maxItems", 20);
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
        .contains("categories");
    assertThat(list(child(schemas, "AvailableCabin").get("required")))
        .contains("status");
    assertThat(list(child(schemas, "OrderEquipmentRequirement").get("required")))
        .containsExactly("equipmentId", "quantity");
    assertThat(list(child(schemas, "ReplaceOrderEquipmentReservationsRequest").get("required")))
        .containsExactly("warehouseId", "actorSubjectId", "actorRole", "requirements");
    assertThat(list(child(schemas, "OrderFurnitureMovementPlanRequest").get("required")))
        .containsExactly("warehouseId", "rentalItemId", "requirements", "orderRequirements");
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
        .containsExactly("equipmentName");
    assertThat(child(ensureFurnitureRequest, "properties").keySet())
        .containsExactly("equipmentName");
    Map<String, Object> maintenanceFurnitureEquipment =
        child(schemas, "MaintenanceFurnitureEquipment");
    assertThat(maintenanceFurnitureEquipment.get("additionalProperties")).isEqualTo(false);
    assertThat(list(maintenanceFurnitureEquipment.get("required")))
        .containsExactly("equipmentId", "equipmentName");
    assertThat(child(maintenanceFurnitureEquipment, "properties").keySet())
        .containsExactly("equipmentId", "equipmentName");
    Map<String, Object> furnitureLoss = child(schemas, "MaintenanceFurnitureLoss");
    assertThat(list(furnitureLoss.get("required")))
        .containsExactly("equipmentId", "quantity");
    assertThat(child(furnitureLoss, "properties").keySet())
        .containsExactly("equipmentId", "quantity");
    Map<String, Object> maintenanceFencedStatus =
        child(schemas, "MaintenanceFencedStatusRequest");
    assertThat(list(maintenanceFencedStatus.get("required")))
        .contains("furnitureLosses");
    assertThat(child(maintenanceFencedStatus, "properties").keySet())
        .contains("estimateId", "furnitureLosses");
    assertThat(maintenanceFencedStatus.toString())
        .doesNotContain("RentalItemStatus", "status=");
    assertThat(list(child(schemas, "LogisticsLeaseOwnerType").get("enum")))
        .containsExactly(
            "LOGISTICS_RETURN",
            "LOGISTICS_SHIPMENT",
            "LOGISTICS_TRANSFER");
    assertThat(list(child(
        schemas, "LogisticsEquipmentMovementReservationOwnerType").get("enum")))
        .containsExactly("LOGISTICS_EQUIPMENT_MOVEMENT");
    assertThat(list(child(
        schemas, "AcquireLogisticsEquipmentMovementReservationRequest").get("required")))
        .containsExactly(
            "movementId",
            "lineId",
            "equipmentId",
            "sourceWarehouseId",
            "sourceLocationKind",
            "expectedSourceBalanceVersion",
            "quantity",
            "reservedUntil");
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
            "number",
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
    for (var component : scanner.findCandidateComponents(API_PACKAGE)) {
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
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
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

  private record Endpoint(String path, String method) {}
}
