package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.order.api.OrderController;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LogisticsContractFoundationTest {
  private static final String FORBIDDEN_PUBLIC_PATH_PATTERN =
      ".*(?:compan(?:y|ies)|candidates?|reservations?|" + "contents(?:\\{[^}]+})?transfers?).*";
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void publicDraftCreationAndReadEndpointsAreAlreadyCanonicalized() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");

    assertThat(paths)
        .containsKeys(
            "/api/logistics/v1/returns",
            "/api/logistics/v1/returns/{documentId}",
            "/api/logistics/v1/returns/{documentId}/register",
            "/api/logistics/v1/returns/{documentId}/start-estimates",
            "/api/logistics/v1/historical-rental-movements",
            "/api/logistics/v1/historical-rental-movements/{documentId}",
            "/api/logistics/v1/shipments",
            "/api/logistics/v1/shipments/{documentId}",
            "/api/logistics/v1/shipments/{documentId}/furniture-readiness",
            "/api/logistics/v1/shipments/{documentId}/furniture-tasks",
            "/api/logistics/v1/transfers",
            "/api/logistics/v1/transfers/{documentId}",
            "/api/logistics/v1/transfers/{documentId}/furniture-readiness",
            "/api/logistics/v1/equipment-movement-tasks",
            "/api/logistics/v1/equipment-movement-tasks/{taskId}",
            "/api/logistics/v1/driver-tasks",
            "/api/logistics/v1/driver-tasks/{taskId}",
            "/api/logistics/v1/driver-tasks/{taskId}/claim",
            "/api/logistics/v1/driver-tasks/{taskId}/promote",
            "/api/logistics/v1/admin/warehouse-operation-marks/{operationId}/recovery",
            "/api/internal/logistics/v1/planning/requests",
            "/api/internal/logistics/v1/planning/assignments",
            "/api/internal/logistics/v1/planning/capacity-snapshots/{scenarioId}",
            "/api/internal/logistics/v1/maintenance/return-arrivals/{rentalItemId}",
            "/api/internal/logistics/v1/maintenance/driver-tasks",
            "/api/internal/logistics/v1/maintenance/driver-tasks/repairs/{repairId}",
            "/api/internal/logistics/v1/maintenance/driver-tasks/repairs/{repairId}/cancel",
            "/api/logistics/v1/driver-board",
            "/api/logistics/v1/driver-board/tasks/{externalTaskId}/move",
            "/api/logistics/v1/driver-board/capital-repairs/{repairId}/promote",
            "/api/logistics/v1/driver-board/capital-repairs/{repairId}/schedule",
            "/api/logistics/v1/orders",
            "/api/logistics/v1/orders/{orderId}",
            "/api/logistics/v1/orders/{orderId}/save",
            "/api/logistics/v1/orders/{orderId}/rental-terms/extend",
            "/api/logistics/v1/orders/{orderId}/shipments",
            "/api/logistics/v1/orders/{orderId}/available-units",
            "/api/logistics/v1/orders/{orderId}/units/{unitId}",
            "/api/logistics/v1/orders/{orderId}/units/{unitId}/replace",
            "/api/logistics/v1/orders/{orderId}/units/{unitId}/desired-equipment",
            "/api/logistics/v1/clients",
            "/api/logistics/v1/clients/{clientId}",
            "/api/logistics/v1/clients/{clientId}/orders",
            "/api/logistics/v1/manual-booking-drafts/{draftId}/holds",
            "/api/logistics/v1/rental-inquiries",
            "/api/logistics/v1/rental-inquiries/{inquiryId}",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-facets",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-selection",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-catalog",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-availability",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/client-presentation",
            "/api/logistics/v1/settings/rental",
            "/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings",
            "/api/logistics/v1/cabins/{cabinId}/photo-presentations",
            "/api/logistics/public/v1/client-presentations/{token}",
            "/api/logistics/public/v1/cabin-photo-presentations/{token}",
            "/api/logistics/public/v1/cabin-photo-presentations/{token}/media/{mediaId}/{generation}/{variant}",
            "/api/logistics/public/v1/client-presentations/{token}/bookings",
            "/api/logistics/public/v1/client-presentations/{token}/bookings/{bookingId}",
            "/api/logistics/public/v1/client-presentations/{token}/media/{cabinId}/{mediaId}/{generation}/{variant}",
            "/api/logistics/v1/{documentType}/{documentId}/reconcile");
    assertThat(paths)
        .doesNotContainKeys(
            "/api/logistics/v1/orders/{orderId}/warehouse",
            "/api/logistics/v1/orders/{orderId}/units",
            "/api/logistics/v1/orders/{orderId}/rental-terms");
    assertThat(child(child(document, "components"), "schemas"))
        .containsKeys(
            "CreateReturnRequest",
            "HistoricalRentalMovementKind",
            "CreateHistoricalRentalMovementRequest",
            "ReturnEstimateLineRequest",
            "StartReturnEstimatesRequest",
            "CreateShipmentRequest",
            "ShipmentFurnitureTaskResult",
            "ShipmentFurnitureReadiness",
            "CreateTransferRequest",
            "TransferFurnitureReadiness",
            "CreateEquipmentMovementTaskRequest",
            "EquipmentMovementTask",
            "EquipmentMovementTaskLine",
            "CreateDriverTaskRequest",
            "DriverTask",
            "WarehouseOperationMarkRecoveryRequest",
            "WarehouseOperationMarkRecoveryResponse",
            "MaintenanceDriverTaskCompensation",
            "MaintenanceDriverTaskCompensationOutcome",
            "DriverBoard",
            "DriverBoardCard",
            "DriverBoardRepairPlaceCard",
            "CapitalRepairCard",
            "RepairPlaceStageState",
            "ScheduleCapitalRepairRequest",
            "CreateOrderRequest",
            "OrderDetail",
            "OrderUnit",
            "OrderRentalTerm",
            "OrderRentalTermExtensionInput",
            "ExtendOrderRentalTermsRequest",
            "CreateOrderRentalShipmentRequest",
            "OrderMovement",
            "OrderMovementCabin",
            "OrderDesiredEquipment",
            "SetOrderUnitDesiredEquipmentRequest",
            "OrderHistoryEvent",
            "CreateRentalInquiryRequest",
            "RentalInquiry",
            "CabinFacets",
            "CabinSearchRequest",
            "CabinSearchResponse",
            "CabinTypeDimensionRelation",
            "ReplaceCabinSelectionRequest",
            "CabinSelectionResponse",
            "CabinCatalogPage",
            "PublishClientPresentationRequest",
            "ManualBookingDraftHoldsRequest",
            "ManualBookingDraftHolds",
            "ClientPresentation",
            "PublicClientPresentation",
            "PresentationBooking",
            "RentalSettings",
            "ShipmentTaskSettings",
            "UpdateShipmentTaskSettingsRequest",
            "CreateCabinPhotoPresentationRequest",
            "CabinPhotoPresentation",
            "CabinPhotoPresentationPhoto",
            "PublicCabinPhotoPresentation",
            "LogisticsDocument",
            "LogisticsLine",
            "ReconcileRequest");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> publicPhotoPresentation =
        child(schemas, "PublicCabinPhotoPresentation");
    assertThat((List<String>) publicPhotoPresentation.get("required"))
        .containsExactly(
            "id",
            "cabinNumber",
            "dimensions",
            "finishing",
            "category",
            "characteristics",
            "linoleum",
            "createdAt",
            "photos");
    assertThat(child(publicPhotoPresentation, "properties"))
        .doesNotContainKeys(
            "warehouseId",
            "status",
            "rentalType",
            "passport",
            "createdBySubjectId",
            "rentalItemVersion");
    Map<String, Object> logisticsDocument = child(schemas, "LogisticsDocument");
    assertThat((List<String>) logisticsDocument.get("required"))
        .contains("scheduledDate")
        .doesNotContain("scheduledTime", "scheduledAt");
    assertThat(child(logisticsDocument, "properties"))
        .doesNotContainKeys("scheduledTime", "scheduledAt");
    assertThat((List<String>) logisticsDocument.get("required"))
        .contains("historicalRentalImport");
    Map<String, Object> historicalMovement =
        child(
            child(document, "paths"),
            "/api/logistics/v1/historical-rental-movements");
    Map<String, Object> historicalMovementPost = child(historicalMovement, "post");
    assertThat(historicalMovementPost.get("operationId"))
        .isEqualTo("createHistoricalRentalMovement");
    Map<String, Object> historicalMovementUpdate =
        child(
            child(child(document, "paths"),
                "/api/logistics/v1/historical-rental-movements/{documentId}"),
            "put");
    assertThat(historicalMovementUpdate.get("operationId"))
        .isEqualTo("updateHistoricalRentalShipment");
    Map<String, Object> historicalMovementRequest =
        child(schemas, "CreateHistoricalRentalMovementRequest");
    assertThat(historicalMovementRequest.get("required"))
        .isEqualTo(
            List.of(
                "warehouseId",
                "rentalItemId",
                "expectedRentalItemVersion",
                "clientId",
                "kind",
                "occurredOn"));
    assertThat(child(historicalMovementRequest, "properties"))
        .containsOnlyKeys(
            "warehouseId",
            "rentalItemId",
            "expectedRentalItemVersion",
            "clientId",
            "kind",
            "occurredOn");
    Map<String, Object> historicalShipmentUpdate =
        child(schemas, "UpdateHistoricalRentalShipmentRequest");
    assertThat(historicalShipmentUpdate.get("required"))
        .isEqualTo(List.of("expectedVersion", "rentalItemId", "clientId", "occurredOn"));
    assertThat(child(historicalShipmentUpdate, "properties"))
        .containsOnlyKeys("expectedVersion", "rentalItemId", "clientId", "occurredOn")
        .doesNotContainKeys("driverSnapshot", "driverWorkerId", "scheduledDate");
    assertThat(child(child(child(schemas, "CabinSearchRequest"), "properties"), "groups"))
        .containsEntry("maxItems", 20);
    assertThat(child(child(child(schemas, "CabinSearchResponse"), "properties"), "groups"))
        .containsEntry("maxItems", 20);
    assertThat(child(child(child(schemas, "CabinSearchRequest"), "properties"), "resultMode"))
        .containsEntry("default", "REPLACE")
        .containsEntry("enum", List.of("APPEND", "REPLACE"));
    assertThat(child(child(schemas, "CabinFacetWarehouse"), "properties"))
        .containsKeys("characteristics", "typeDimensions");
    Map<String, Object> selectionItems =
        child(
            child(child(child(schemas, "CabinSelectionResponse"), "properties"), "items"), "items");
    List<?> selectionItemBranches = (List<?>) selectionItems.get("allOf");
    assertThat(selectionItemBranches).hasSize(2);
    assertThat(child(child(objectMap(selectionItemBranches.get(1)), "properties"), "status"))
        .containsEntry("const", "FREE");
    Map<String, Object> idempotencyKey = child(child(document, "components"), "parameters");
    assertThat(idempotencyKey.get("IdempotencyKey"))
        .isInstanceOfSatisfying(
            Map.class,
            parameter -> {
              assertThat(parameter.get("in")).isEqualTo("header");
              assertThat(parameter.get("required")).isEqualTo(true);
            });

    Map<String, Object> cabinSearch =
        child(
            child(
                child(document, "paths"),
                "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches"),
            "post");
    assertThat((List<?>) cabinSearch.get("parameters"))
        .anySatisfy(
            parameter ->
                assertThat(parameter)
                    .isEqualTo(Map.of("$ref", "#/components/parameters/IdempotencyKey")));
    Map<String, Object> cabinSearchResponses = child(cabinSearch, "responses");
    assertThat(cabinSearchResponses).containsKeys("200", "409", "503");
    assertThat(child(child(child(cabinSearchResponses, "200"), "headers"), "Idempotency-Replayed"))
        .containsEntry("required", false);
  }

  @Test
  void maintenanceReturnArrivalContractExposesOnlyPhysicalArrivalIdentity() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> endpoint =
        child(
            child(document, "paths"),
            "/api/internal/logistics/v1/maintenance/return-arrivals/{rentalItemId}");
    Map<String, Object> get = child(endpoint, "get");
    assertThat(get.get("operationId")).isEqualTo("getMaintenanceReturnArrival");
    assertThat(child(get, "responses")).containsKeys("200", "401", "403", "404");

    Map<String, Object> schema =
        child(child(child(document, "components"), "schemas"), "MaintenanceReturnArrival");
    assertThat(child(schema, "properties"))
        .containsOnlyKeys("warehouseId", "rentalItemId", "returnDocumentId", "arrivedAt");
    assertThat(schema.get("required"))
        .isEqualTo(List.of("warehouseId", "rentalItemId", "returnDocumentId", "arrivedAt"));
  }

  @Test
  void customerAppContractFreezesOwnedCartSlotsAndCheckoutBoundary() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");

    assertThat(paths)
        .containsKeys(
            "/api/logistics/customer/v1/profile",
            "/api/logistics/customer/v1/warehouses",
            "/api/logistics/customer/v1/inquiries",
            "/api/logistics/customer/v1/inquiries/{inquiryId}",
            "/api/logistics/customer/v1/inquiries/{inquiryId}/facets",
            "/api/logistics/customer/v1/inquiries/{inquiryId}/cabins",
            "/api/logistics/customer/v1/inquiries/{inquiryId}/selection",
            "/api/logistics/customer/v1/inquiries/{inquiryId}/equipment",
            "/api/logistics/customer/v1/inquiries/{inquiryId}/cart",
            "/api/logistics/customer/v1/inquiries/{inquiryId}/cabins/{cabinId}/photos/{mediaId}",
            "/api/logistics/customer/v1/delivery-slots/search",
            "/api/logistics/customer/v1/delivery-slots/{slotId}/hold",
            "/api/logistics/customer/v1/inquiries/{inquiryId}/checkout",
            "/api/logistics/customer/v1/bookings");
    assertThat(schemas)
        .containsKeys(
            "CreateCustomerProfileRequest",
            "CustomerProfile",
            "CustomerWarehouse",
            "CreateCustomerInquiryRequest",
            "CustomerInquiry",
            "CustomerCabin",
            "CustomerCabinPhoto",
            "CustomerCabinPage",
            "ReplaceCustomerCabinsRequest",
            "CustomerCabinSelection",
            "CustomerEquipmentAvailability",
            "CustomerCabinEquipmentSelection",
            "ReplaceCustomerEquipmentRequest",
            "CustomerEquipmentSelection",
            "CustomerCart",
            "CustomerDeliverySlotSearchRequest",
            "CustomerDeliverySlot",
            "HoldCustomerDeliverySlotRequest",
            "HeldCustomerDeliverySlot",
            "CustomerCheckoutRequest",
            "CustomerBooking");

    Map<String, Object> customerWarehouse = child(schemas, "CustomerWarehouse");
    assertThat(customerWarehouse.get("required"))
        .isEqualTo(
            List.of(
                "id",
                "name",
                "city",
                "address",
                "timezone",
                "depotLatitude",
                "depotLongitude"));
    assertThat(child(customerWarehouse, "properties"))
        .containsKeys("depotLatitude", "depotLongitude");

    Map<String, Object> createInquiry =
        child(child(paths, "/api/logistics/customer/v1/inquiries"), "post");
    Map<String, Object> replaceSelection =
        child(
            child(paths, "/api/logistics/customer/v1/inquiries/{inquiryId}/selection"),
            "put");
    Map<String, Object> checkout =
        child(
            child(paths, "/api/logistics/customer/v1/inquiries/{inquiryId}/checkout"),
            "post");
    for (Map<String, Object> operation : List.of(createInquiry, replaceSelection, checkout)) {
      assertThat((List<?>) operation.get("parameters"))
          .anySatisfy(
              parameter ->
                  assertThat(parameter)
                      .isEqualTo(Map.of("$ref", "#/components/parameters/IdempotencyKey")));
      assertThat(child(operation, "responses")).containsKeys("409", "503");
    }
    assertThat(child(createInquiry, "responses")).containsKey("201");
    assertThat(child(replaceSelection, "responses")).containsKey("200");
    assertThat(child(checkout, "responses")).containsKey("200");
    Map<String, Object> slotSearch =
        child(child(paths, "/api/logistics/customer/v1/delivery-slots/search"), "post");
    assertThat(child(slotSearch, "responses")).containsKey("422");

    Map<String, Object> hold =
        child(child(paths, "/api/logistics/customer/v1/delivery-slots/{slotId}/hold"), "post");
    assertThat((List<?>) hold.get("parameters"))
        .anySatisfy(
            parameter ->
                assertThat(parameter)
                    .isEqualTo(
                        Map.of(
                            "$ref",
                            "#/components/parameters/ExpectedCustomerDeliverySlotVersion")));
    assertThat(child(hold, "responses"))
        .containsKeys("200", "400", "401", "403", "404", "409", "422", "503");
    assertThat(
            child(
                child(child(child(hold, "requestBody"), "content"), "application/json"),
                "schema"))
        .isEqualTo(Map.of("$ref", "#/components/schemas/HoldCustomerDeliverySlotRequest"));

    Map<String, Object> cabinList =
        child(child(paths, "/api/logistics/customer/v1/inquiries/{inquiryId}/cabins"), "get");
    assertThat(cabinList.get("description").toString())
        .contains("FREE")
        .contains("no dossier/passport");
    Map<String, Object> cabinProperties = child(child(schemas, "CustomerCabin"), "properties");
    assertThat(cabinProperties)
        .containsKeys(
            "accountingNo",
            "type",
            "finish",
            "dimensions",
            "category",
            "linoleum",
            "characteristics",
            "facts",
            "photos")
        .doesNotContainKeys("passportUrl", "dossierUrl", "warehouseId", "status");

    Map<String, Object> deliverySlot = child(schemas, "CustomerDeliverySlot");
    assertThat(child(deliverySlot, "properties"))
        .containsKeys(
            "date",
            "start",
            "end",
            "travelZoneHours",
            "capacityRemaining",
            "expiresAt",
            "state");
    assertThat(child(child(deliverySlot, "properties"), "state"))
        .containsEntry(
            "enum", List.of("OFFERED", "HELD", "CHECKOUT_PENDING", "CONFIRMED", "RELEASED"));
    Map<String, Object> checkoutRequest = child(schemas, "CustomerCheckoutRequest");
    assertThat(checkoutRequest.get("required"))
        .isEqualTo(List.of("expectedVersion", "slotId", "slotVersion", "rentalMonths"));
    assertThat(child(child(checkoutRequest, "properties"), "rentalMonths"))
        .containsEntry("minimum", 1)
        .containsEntry("maximum", 120);

    Map<String, Object> photo =
        child(
            child(
                paths,
                "/api/logistics/customer/v1/inquiries/{inquiryId}/cabins/{cabinId}/photos/{mediaId}"),
            "get");
    assertThat(child(child(child(photo, "responses"), "200"), "content"))
        .containsKey("image/*");
    assertThat(child(child(child(photo, "responses"), "200"), "headers"))
        .containsKey("X-Content-Type-Options");
  }

  @Test
  void orderUnitReplacementRouteMatchesTheCanonicalExplicitReplacePath() throws Exception {
    String path = "/api/logistics/v1/orders/{orderId}/units/{unitId}/replace";
    Map<String, Object> operation = child(child(child(openApi(), "paths"), path), "post");
    assertThat(operation).containsEntry("operationId", "replaceOrderUnit");

    var controllerMethod =
        java.util.Arrays.stream(OrderController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("replaceUnit"))
            .findFirst()
            .orElseThrow();
    String controllerPrefix = OrderController.class.getAnnotation(RequestMapping.class).value()[0];
    String methodPath = controllerMethod.getAnnotation(PostMapping.class).value()[0];
    assertThat(controllerPrefix + methodPath).isEqualTo(path);
  }

  @Test
  void equipmentMovementDurationIsRequiredAndPositiveInCommandsAndResponses() throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");

    for (String schemaName :
        List.of("CreateEquipmentMovementTaskRequest", "EquipmentMovementTask")) {
      Map<String, Object> schema = child(schemas, schemaName);
      assertThat((List<?>) schema.get("required")).anyMatch("plannedDurationMinutes"::equals);
      assertThat(child(child(schema, "properties"), "plannedDurationMinutes"))
          .containsEntry("type", "integer")
          .containsEntry("minimum", 1);
    }
  }

  @Test
  void publicOpenApiExposesExactlyTheCanonicalLogisticsOperations() throws Exception {
    Map<String, Object> paths = child(openApi(), "paths");

    assertThat(publicOperationIds(paths))
        .containsExactlyInAnyOrder(
            "listReturns",
            "createReturn",
            "getReturn",
            "registerReturn",
            "acceptUndamagedReturn",
            "startReturnEstimates",
            "createHistoricalRentalMovement",
            "updateHistoricalRentalShipment",
            "getCustomerProfile",
            "createCustomerProfile",
            "listCustomerWarehouses",
            "createCustomerInquiry",
            "getCustomerInquiry",
            "getCustomerCabinFacets",
            "listCustomerCabins",
            "getCustomerCabinSelection",
            "replaceCustomerCabinSelection",
            "listCustomerEquipment",
            "replaceCustomerEquipment",
            "getCustomerCart",
            "getCustomerCabinPhoto",
            "searchCustomerDeliverySlots",
            "holdCustomerDeliverySlot",
            "checkoutCustomerInquiry",
            "listCustomerBookings",
            "listShipments",
            "createShipment",
            "getShipment",
            "replaceShipmentPlan",
            "getShipmentFurnitureReadiness",
            "createShipmentFurnitureTasks",
            "confirmShipmentPreparation",
            "cancelShipment",
            "listTransfers",
            "createTransfer",
            "getTransfer",
            "getTransferFurnitureReadiness",
            "departTransferLine",
            "getTransferArrivalPreflight",
            "arriveTransferLine",
            "cancelTransfer",
            "createCabinFurnitureTask",
            "createEquipmentMovementTask",
            "getEquipmentMovementTask",
            "cancelEquipmentMovementTask",
            "listDriverTasks",
            "createDriverTask",
            "getMaintenanceReturnArrival",
            "createMaintenanceDriverTask",
            "createMaintenanceEquipmentMovementTask",
            "getMaintenanceEquipmentMovementTask",
            "applyAuthoritativeCompletedInventoryOutcome",
            "getMaintenanceDriverTaskCompensation",
            "cancelMaintenanceDriverTaskCompensation",
            "getDriverTask",
            "claimFutureDriverTask",
            "promoteDriverTask",
            "recoverLogisticsWarehouseOperationMark",
            "getRoutePlanningRequests",
            "getRoutePlanningAssignmentStatuses",
            "applyRoutePlanningAssignments",
            "replaceRoutePlanningCapacitySnapshot",
            "getDriverBoard",
            "moveDriverBoardTask",
            "returnDriverTaskToCapitalRepairs",
            "promoteCapitalRepairToDriverCurrent",
            "scheduleCapitalRepairOnDriverBoard",
            "listOrders",
            "createOrder",
            "getOrder",
            "updateOrder",
            "cancelOrder",
            "saveOrder",
            "extendOrderRentalTerms",
            "createOrderRentalShipment",
            "searchOrderClients",
            "createOrderClient",
            "getOrderClient",
            "listOrderClientOrders",
            "listOrderUnitCandidates",
            "removeOrderUnit",
            "replaceOrderUnit",
            "setOrderUnitDesiredEquipment",
            "getOrderHistory",
            "getManualBookingDraftHolds",
            "replaceManualBookingDraftHolds",
            "createRentalInquiry",
            "listRentalInquiriesForOrder",
            "getRentalInquiry",
            "getRentalInquiryCabinFacets",
            "searchRentalInquiryCabins",
            "getRentalInquiryCabinSelection",
            "replaceRentalInquiryCabinSelection",
            "searchRentalInquiryCabinCatalog",
            "checkRentalInquiryCabinAvailability",
            "getRentalInquiryClientPresentation",
            "publishRentalInquiryClientPresentation",
            "revokeRentalInquiryClientPresentation",
            "listRentalBookingAlerts",
            "actOnRentalBookingAlert",
            "getRentalSettings",
            "updateRentalSettings",
            "getShipmentTaskSettings",
            "updateShipmentTaskSettings",
            "createCabinPhotoPresentation",
            "getPublicClientPresentation",
            "getPublicCabinPhotoPresentation",
            "getPublicCabinPhotoPresentationMedia",
            "confirmPublicClientPresentation",
            "getPublicClientPresentationBooking",
            "getPublicClientPresentationMedia",
            "reconcileDocument");
    assertThat(paths.keySet())
        .allSatisfy(
            path -> assertThat(normalizedPath(path)).doesNotMatch(FORBIDDEN_PUBLIC_PATH_PATTERN));
  }

  @Test
  void maintenanceCompensationContractFreezesTheGuardedCancellationSemantics() throws Exception {
    Map<String, Object> paths = child(openApi(), "paths");
    Map<String, Object> endpoint =
        child(
            paths, "/api/internal/logistics/v1/maintenance/driver-tasks/repairs/{repairId}/cancel");
    Map<String, Object> post = child(endpoint, "post");

    assertThat(post.get("operationId")).isEqualTo("cancelMaintenanceDriverTaskCompensation");
    assertThat(String.valueOf(post.get("description")))
        .contains("atomic")
        .contains("WAITING-only")
        .contains("DELIVER_TO_REPAIR")
        .contains("RESERVED")
        .contains("CAPITAL_TO_PRODUCTION")
        .contains("RECONCILIATION_REQUIRED");
    assertThat(child(post, "responses")).containsKeys("200", "400", "401", "403");
  }

  @Test
  void driverDetailContractFreezesExactWorkerIdentityAuthorization() throws Exception {
    Map<String, Object> paths = child(openApi(), "paths");
    Map<String, Object> endpoint = child(paths, "/api/logistics/v1/driver-tasks/{taskId}");
    Map<String, Object> get = child(endpoint, "get");

    assertThat(String.valueOf(get.get("description")))
        .contains("WORKER")
        .contains("driver.tasks")
        .contains("worker_id")
        .contains("plannedDriverWorkerId")
        .contains("sub is not")
        .contains("worker.tasks")
        .contains("UNASSIGNED")
        .contains("WAREHOUSE_DRIVERS");
  }

  @Test
  void rentalSettingsExposeIndependentChatAndPresentationHoldDurations() throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> settings = child(schemas, "RentalSettings");
    Map<String, Object> settingsProperties = child(settings, "properties");
    Map<String, Object> update = child(schemas, "UpdateRentalSettingsRequest");
    Map<String, Object> updateProperties = child(update, "properties");

    List<String> settingsRequired =
        ((List<?>) settings.get("required")).stream().map(String.class::cast).toList();
    List<String> updateRequired =
        ((List<?>) update.get("required")).stream().map(String.class::cast).toList();

    assertThat(settingsRequired)
        .contains(
            "chatSelectionHoldMinutes",
            "manualBookingHoldMinutes",
            "presentationHoldMinutes",
            "draftReservationHoldMinutes");
    assertThat(updateRequired)
        .contains(
            "expectedVersion",
            "chatSelectionHoldMinutes",
            "manualBookingHoldMinutes",
            "presentationHoldMinutes",
            "draftReservationHoldMinutes");
    assertThat(child(settingsProperties, "chatSelectionHoldMinutes"))
        .containsEntry("minimum", 1)
        .containsEntry("maximum", 1440);
    assertThat(child(settingsProperties, "manualBookingHoldMinutes"))
        .containsEntry("minimum", 5)
        .containsEntry("maximum", 1440);
    assertThat(child(settingsProperties, "presentationHoldMinutes"))
        .containsEntry("minimum", 5)
        .containsEntry("maximum", 1440);
    assertThat(child(settingsProperties, "draftReservationHoldMinutes"))
        .containsEntry("minimum", 1440)
        .containsEntry("maximum", 14400);
    assertThat(updateProperties)
        .containsKeys(
            "expectedVersion",
            "chatSelectionHoldMinutes",
            "manualBookingHoldMinutes",
            "presentationHoldMinutes",
            "draftReservationHoldMinutes");
  }

  @Test
  void shipmentTaskSettingsAreWarehouseScopedVersionFencedAndBounded() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> endpoint =
        child(paths, "/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings");
    Map<String, Object> get = child(endpoint, "get");
    Map<String, Object> put = child(endpoint, "put");
    Map<String, Object> response = child(schemas, "ShipmentTaskSettings");
    Map<String, Object> update = child(schemas, "UpdateShipmentTaskSettingsRequest");

    assertThat(endpoint.get("parameters"))
        .isEqualTo(
            List.of(
                Map.of(
                    "name",
                    "warehouseId",
                    "in",
                    "path",
                    "required",
                    true,
                    "schema",
                    Map.of("type", "string", "format", "uuid"))));
    assertThat(get.get("operationId")).isEqualTo("getShipmentTaskSettings");
    assertThat(child(get, "responses")).containsKeys("200", "401", "403");
    assertThat(put.get("operationId")).isEqualTo("updateShipmentTaskSettings");
    assertThat(child(put, "responses")).containsKeys("200", "400", "401", "403", "409");
    assertThat(child(child(child(put, "requestBody"), "content"), "application/json").get("schema"))
        .isEqualTo(Map.of("$ref", "#/components/schemas/UpdateShipmentTaskSettingsRequest"));
    assertThat(response.get("required"))
        .isEqualTo(
            List.of(
                "warehouseId", "version", "maxCabinsPerShipmentTask", "updatedBy", "updatedAt"));
    assertThat(child(response, "properties"))
        .containsOnlyKeys(
            "warehouseId", "version", "maxCabinsPerShipmentTask", "updatedBy", "updatedAt");
    assertThat(child(child(response, "properties"), "maxCabinsPerShipmentTask"))
        .containsEntry("minimum", 1)
        .containsEntry("maximum", 100);
    assertThat(update.get("required"))
        .isEqualTo(List.of("expectedVersion", "maxCabinsPerShipmentTask"));
    assertThat(child(child(update, "properties"), "expectedVersion")).containsEntry("minimum", 0);
    assertThat(child(child(update, "properties"), "maxCabinsPerShipmentTask"))
        .containsEntry("minimum", 1)
        .containsEntry("maximum", 100);
    assertThat(child(child(schemas, "DriverBoardCard"), "properties")).containsKey("priority");
  }

  @Test
  void transferFurnitureReadinessIsAReadOnlyCanonicalProjection() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");
    Map<String, Object> endpoint =
        child(paths, "/api/logistics/v1/transfers/{documentId}/furniture-readiness");
    Map<String, Object> operation = child(endpoint, "get");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");

    assertThat(endpoint).containsOnlyKeys("parameters", "get");
    assertThat(operation.get("operationId")).isEqualTo("getTransferFurnitureReadiness");
    assertThat(child(operation, "responses")).containsKeys("200", "401", "403", "404");
    assertThat(
            child(child(child(child(operation, "responses"), "200"), "content"), "application/json")
                .get("schema"))
        .isEqualTo(Map.of("$ref", "#/components/schemas/TransferFurnitureReadiness"));
    assertThat(child(schemas, "TransferFurnitureReadinessState").get("enum"))
        .isEqualTo(List.of("NOT_REQUIRED", "READY", "AWAITING_TASK_COMPLETION", "BLOCKED"));
    assertThat(child(schemas, "TransferFurnitureReadiness").get("required"))
        .isEqualTo(List.of("transferId", "transferVersion", "state", "tasks"));
    assertThat(child(schemas, "TransferFurnitureTaskStatus").get("required"))
        .isEqualTo(
            List.of(
                "rentalItemId",
                "unitNumber",
                "taskId",
                "externalTaskId",
                "taskBoardTaskId",
                "taskState",
                "lineCount"));
  }

  @Test
  void returnRegistrationIsAnAsynchronousVersionedCommand() throws Exception {
    Map<String, Object> paths = child(openApi(), "paths");
    Map<String, Object> register = child(paths, "/api/logistics/v1/returns/{documentId}/register");
    Map<String, Object> post = child(register, "post");
    Map<String, Object> responses = child(post, "responses");

    assertThat(responses).containsKey("202");
    assertThat(register.get("parameters"))
        .isInstanceOfSatisfying(
            java.util.List.class,
            parameters ->
                assertThat(parameters)
                    .anySatisfy(
                        value ->
                            assertThat(value)
                                .isInstanceOfSatisfying(
                                    Map.class,
                                    parameter ->
                                        assertThat(parameter.get("$ref"))
                                            .isEqualTo(
                                                "#/components/parameters/ExpectedVersion"))));
  }

  @Test
  void returnInspectionCommandsRequireLineOwnedPhotosAndDeferFurnitureSelectionToEstimates()
      throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> acceptanceLine = child(schemas, "ReturnMediaLineRequest");
    Map<String, Object> acceptanceProperties = child(acceptanceLine, "properties");
    Map<String, Object> estimateLine = child(schemas, "ReturnEstimateLineRequest");
    Map<String, Object> estimateProperties = child(estimateLine, "properties");

    assertThat(acceptanceLine.get("required"))
        .isEqualTo(List.of("lineId", "references", "equipmentConfirmed"));
    assertThat(child(acceptanceProperties, "references").get("minItems")).isEqualTo(1);
    assertThat(child(acceptanceProperties, "equipmentConfirmed"))
        .containsEntry("type", "boolean")
        .containsEntry("const", true);
    assertThat(estimateLine.get("required")).isEqualTo(List.of("lineId", "references"));
    assertThat(child(estimateProperties, "references").get("minItems")).isEqualTo(1);
    assertThat(estimateProperties).doesNotContainKey("shortages");
    assertThat(schemas).doesNotContainKey("ReturnShortageLineRequest");
  }

  @Test
  void plannerAssignmentStatusContractExposesOnlyRouteOwnershipFacts() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> endpoint =
        child(child(document, "paths"), "/api/internal/logistics/v1/planning/assignments");
    assertThat(child(endpoint, "get").get("operationId"))
        .isEqualTo("getRoutePlanningAssignmentStatuses");

    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> status = child(schemas, "PlanningAssignmentStatus");
    assertThat(child(status, "properties"))
        .containsOnlyKeys(
            "orderId",
            "documentId",
            "scheduledDate",
            "unitIds",
            "driverAudienceMode",
            "driverWorkerId",
            "driverName",
            "taskState")
        .doesNotContainKeys("address", "clientName", "contactPhone");
    assertThat(child(child(status, "properties"), "driverAudienceMode").get("enum"))
        .isEqualTo(List.of("ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"));
  }

  @Test
  void plannerCapacityContractCarriesOnlyAnonymousDeliveryFacts() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> endpoint =
        child(
            child(document, "paths"),
            "/api/internal/logistics/v1/planning/capacity-snapshots/{scenarioId}");
    Map<String, Object> put = child(endpoint, "put");
    assertThat(put.get("operationId")).isEqualTo("replaceRoutePlanningCapacitySnapshot");
    assertThat((List<?>) put.get("parameters"))
        .anySatisfy(
            parameter ->
                assertThat(parameter)
                    .isEqualTo(Map.of("$ref", "#/components/parameters/IdempotencyKey")));

    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> job = child(schemas, "PlanningCapacityJob");
    assertThat(child(job, "properties"))
        .containsOnlyKeys(
            "sourceJobId",
            "deliveryDate",
            "latitude",
            "longitude",
            "cabinCount",
            "windowStart",
            "windowEnd",
            "serviceMinutes")
        .doesNotContainKeys("orderId", "clientName", "cabinId", "driverWorkerId");
    assertThat(child(schemas, "ReplacePlanningCapacitySnapshotRequest").get("required"))
        .isEqualTo(List.of("warehouseId", "sourceGeneration", "sourceRevision", "jobs"));
    Map<String, Object> dateOptionProperties =
        child(child(schemas, "PlanningDateOption"), "properties");
    for (String property : List.of("windowStart", "windowEnd", "travelZoneHours")) {
      assertThat(child(dateOptionProperties, property))
          .containsEntry(
              "type",
              property.equals("travelZoneHours")
                  ? List.of("integer", "null")
                  : List.of("string", "null"))
          .doesNotContainKey("nullable");
    }
  }

  @Test
  void schedulingUsesDatesForReturnsShipmentsAndTransfers() throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> returnPickup = child(schemas, "ReturnPickupRequest");
    Map<String, Object> shipmentPlan = child(schemas, "ShipmentPlanRequest");
    Map<String, Object> createTransfer = child(schemas, "CreateTransferRequest");
    Map<String, Object> cabinFurnitureTask = child(schemas, "CreateCabinFurnitureTaskRequest");
    Map<String, Object> document = child(schemas, "LogisticsDocument");

    assertThat(returnPickup.get("required")).isEqualTo(List.of("driverSnapshot", "scheduledDate"));
    assertThat(child(child(returnPickup, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(returnPickup, "properties"))
        .doesNotContainKeys("scheduledTime", "scheduledAt");
    assertThat(shipmentPlan.get("required")).isEqualTo(List.of("driverSnapshot", "scheduledDate"));
    assertThat(child(child(shipmentPlan, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(shipmentPlan, "properties"))
        .doesNotContainKeys("scheduledTime", "scheduledAt");
    List<String> transferRequired =
        ((List<?>) createTransfer.get("required")).stream().map(String.class::cast).toList();
    assertThat(transferRequired).contains("scheduledDate", "furnitureReplacements");
    assertThat(child(createTransfer, "properties"))
        .doesNotContainKeys("scheduledTime", "scheduledAt", "driverSnapshot", "driverWorkerId");
    assertThat(child(child(createTransfer, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(cabinFurnitureTask.get("required"))
        .isEqualTo(List.of("warehouseId", "scheduledDate", "contents"));
    assertThat(child(child(cabinFurnitureTask, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(child(document, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(document, "properties")).doesNotContainKeys("scheduledTime", "scheduledAt");
    assertThat(child(child(schemas, "CreateOrderRentalShipmentRequest"), "properties"))
        .containsKey("warehouseDriverPool")
        .doesNotContainKey("scheduledTime");
    assertThat(child(child(schemas, "DriverTripDetails"), "properties"))
        .doesNotContainKey("scheduledTime");
    assertThat(child(child(schemas, "OrderMovement"), "properties"))
        .doesNotContainKey("scheduledTime");
    assertThat(child(child(schemas, "MoveDriverBoardTaskRequest"), "properties"))
        .doesNotContainKey("targetDriverAudience");
  }

  @Test
  void orderCreationCannotAssignAnArbitraryManagerAndUnitsKeepTheAssetShape() throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> createOrder = child(schemas, "CreateOrderRequest");
    assertThat(child(createOrder, "properties"))
        .containsOnlyKeys("clientId", "newClient", "contactPhone", "comment")
        .doesNotContainKey("managerId");
    Map<String, Object> updateOrder = child(schemas, "UpdateOrderRequest");
    assertThat(child(updateOrder, "properties"))
        .containsOnlyKeys("expectedVersion", "clientId", "contactPhone", "comment")
        .doesNotContainKeys(
            "desiredDeliveryWindows",
            "deliveryAddress",
            "latitude",
            "longitude",
            "additionalContacts");
    Map<String, Object> confirmation = child(schemas, "ConfirmClientPresentationRequest");
    assertThat(child(confirmation, "properties"))
        .containsOnlyKeys(
            "selections",
            "desiredDeliveryWindows",
            "rentalMonths",
            "deliveryAddress",
            "latitude",
            "longitude",
            "additionalContacts");
    assertThat(confirmation.get("required")).isEqualTo(List.of("selections"));
    Map<String, Object> desiredWindows =
        child(child(confirmation, "properties"), "desiredDeliveryWindows");
    assertThat(desiredWindows.get("minItems")).isEqualTo(1);
    assertThat(desiredWindows.get("maxItems")).isEqualTo(4);
    assertThat(desiredWindows.get("uniqueItems")).isEqualTo(true);
    Map<String, Object> desiredWindowInput = child(schemas, "DesiredDeliveryWindowInput");
    Map<String, Object> desiredWindow = child(schemas, "DesiredDeliveryWindow");
    assertThat(child(desiredWindowInput, "properties")).containsOnlyKeys("startDate", "endDate");
    assertThat(child(desiredWindow, "properties")).containsOnlyKeys("startDate", "endDate");
    assertThat(desiredWindowInput.get("required")).isEqualTo(List.of("startDate", "endDate"));
    assertThat(desiredWindow.get("required")).isEqualTo(List.of("startDate", "endDate"));
    Map<String, Object> permissions = child(schemas, "OrderPermissions");
    List<String> requiredPermissions =
        ((List<?>) permissions.get("required")).stream().map(String.class::cast).toList();
    assertThat(requiredPermissions)
        .contains("canEdit", "canReplaceUnits", "canExtendRentalTerms", "canViewOtherManagers");
    assertThat(child(child(createOrder, "properties"), "contactPhone").get("pattern"))
        .isEqualTo("^(?:\\+|8)[0-9() .-]{6,31}$");
    assertThat(child(child(updateOrder, "properties"), "contactPhone").get("pattern"))
        .isEqualTo("^(?:\\+|8)[0-9() .-]{6,31}$");
    assertThat(child(child(schemas, "OrderSummary"), "properties"))
        .extractingByKey("contactPhone")
        .isInstanceOfSatisfying(
            Map.class,
            phone -> assertThat(phone.get("pattern")).isEqualTo("^\\+[1-9][0-9]{6,14}$"));
    assertThat(child(child(schemas, "OrderDetail"), "properties"))
        .extractingByKey("contactPhone")
        .isInstanceOfSatisfying(
            Map.class,
            phone -> assertThat(phone.get("pattern")).isEqualTo("^\\+[1-9][0-9]{6,14}$"));
    assertThat(child(schemas, "ClientType").get("enum"))
        .isEqualTo(List.of("INDIVIDUAL", "LEGAL_ENTITY"));
    Map<String, Object> createClient = child(schemas, "CreateClientRequest");
    assertThat(child(createClient, "properties"))
        .containsKeys("contactPerson", "email", "comment", "source")
        .doesNotContainKeys(
            "responsibleManagerId", "responsibleManagerDisplayName", "electronicDocuments");
    List<?> createClientContactRules = (List<?>) createClient.get("allOf");
    assertThat(
            child(
                    child(
                        child(objectMap(createClientContactRules.getFirst()), "if"), "properties"),
                    "clientType")
                .get("enum"))
        .isEqualTo(List.of("LEGAL_ENTITY"));
    assertThat(child(child(createClient, "properties"), "phone").get("pattern"))
        .isEqualTo("^(?:\\+|8)[0-9() .-]{6,31}$");
    assertThat(child(child(schemas, "OrderClient"), "properties"))
        .containsKeys(
            "contactPerson",
            "responsibleManagerId",
            "responsibleManagerDisplayName",
            "comment",
            "source");
    assertThat(child(child(child(schemas, "OrderClient"), "properties"), "phone").get("pattern"))
        .isEqualTo("^\\+[1-9][0-9]{6,14}$");
    Map<String, Object> paths = child(openApi(), "paths");
    Map<String, Object> createOrderResponses =
        child(child(child(paths, "/api/logistics/v1/orders"), "post"), "responses");
    Map<String, Object> createClientResponses =
        child(child(child(paths, "/api/logistics/v1/clients"), "post"), "responses");
    assertThat(createOrderResponses).containsKeys("200", "201");
    assertThat(createClientResponses).containsKeys("200", "201");

    Map<String, Object> orderUnit = child(schemas, "OrderUnit");
    assertThat(child(orderUnit, "properties"))
        .containsOnlyKeys(
            "reservationId", "added", "reservationState", "unit", "desiredContents", "rentalTerm");
    Map<String, Object> history = child(schemas, "OrderHistoryEvent");
    assertThat(child(history, "properties"))
        .containsKeys("actorSubjectId", "occurredAt")
        .doesNotContainKeys("actor", "timestamp");
  }

  @Test
  void eventSchemaIsSanitizedAndUsesOnlyAggregateFamilyTopics() throws Exception {
    JsonNode schema = objectMapper.readTree(Files.readString(eventSchemaPath()));

    assertThat(strings(schema.get("x-rwms-topics")))
        .containsExactlyInAnyOrder(
            "rwms.logistics.return.v1", "rwms.logistics.shipment.v1", "rwms.logistics.transfer.v1");
    assertThat(schema.at("/properties/producer/const").stringValue())
        .isEqualTo("logistics-service");
    assertThat(strings(schema.at("/properties/aggregateType/enum")))
        .containsExactlyInAnyOrder("RETURN", "SHIPMENT", "TRANSFER");
    assertThat(fieldNames(schema.at("/$defs/logisticsFact/properties")))
        .containsExactlyInAnyOrder(
            "documentId",
            "documentType",
            "state",
            "warehouseId",
            "destinationWarehouseId",
            "lineCount",
            "resultCode")
        .doesNotContain("partySnapshot", "tenantSnapshot", "driverSnapshot", "mediaId", "passport");
  }

  @Test
  void rentalInquiryBookingFactHasAnExactCanonicalV2ContractAndConversationKey() throws Exception {
    JsonNode schema = objectMapper.readTree(Files.readString(rentalInquiryEventSchemaPath()));
    assertThat(schema.path("additionalProperties").booleanValue()).isFalse();
    assertThat(strings(schema.get("required")))
        .containsExactlyInAnyOrder(
            "envelopeVersion",
            "eventId",
            "eventType",
            "eventVersion",
            "occurredAt",
            "recordedAt",
            "producer",
            "aggregateType",
            "aggregateId",
            "aggregateVersion",
            "correlation",
            "actorRef",
            "payload");
    assertThat(fieldNames(schema.get("properties")))
        .containsExactlyInAnyOrder(
            "envelopeVersion",
            "eventId",
            "eventType",
            "eventVersion",
            "occurredAt",
            "recordedAt",
            "producer",
            "aggregateType",
            "aggregateId",
            "aggregateVersion",
            "correlation",
            "actorRef",
            "payload")
        .doesNotContain("rentalInquiryId");
    assertThat(schema.at("/properties/envelopeVersion/const").intValue()).isEqualTo(2);
    assertThat(schema.at("/properties/eventVersion/const").intValue()).isEqualTo(1);
    assertThat(schema.at("/properties/producer/const").stringValue())
        .isEqualTo("logistics-service");
    assertThat(schema.at("/properties/aggregateType/const").stringValue())
        .isEqualTo("RENTAL_INQUIRY");
    assertThat(fieldNames(schema.at("/$defs/bookedPayload/properties")))
        .containsExactlyInAnyOrder("conversationId", "orderId");
    assertThat(strings(schema.at("/$defs/bookedPayload/required")))
        .containsExactlyInAnyOrder("conversationId", "orderId");
    assertThat(schema.at("/$defs/bookedPayload/additionalProperties").booleanValue()).isFalse();
    assertThat(fieldNames(schema.at("/$defs/correlationContext/properties")))
        .containsExactlyInAnyOrder("correlationId", "causationId");
    assertThat(strings(schema.at("/$defs/correlationContext/required")))
        .containsExactlyInAnyOrder("correlationId", "causationId");
    assertThat(schema.at("/$defs/correlationContext/additionalProperties").booleanValue())
        .isFalse();
    assertThat(fieldNames(schema.at("/$defs/actorReference/properties")))
        .containsExactlyInAnyOrder("subjectId", "principalType", "profileRevision");
    assertThat(strings(schema.at("/$defs/actorReference/required")))
        .containsExactlyInAnyOrder("subjectId", "principalType", "profileRevision");
    assertThat(schema.at("/$defs/actorReference/additionalProperties").booleanValue()).isFalse();
    assertThat(schema.at("/$defs/actorReference/properties/principalType/const").stringValue())
        .isEqualTo("USER");
    assertThat(schema.at("/$defs/actorReference/properties/profileRevision/type").stringValue())
        .isEqualTo("null");

    Map<String, Object> contract = eventContract();
    Map<String, Object> channel = child(child(contract, "channels"), "rentalInquiryFacts");
    assertThat(channel.get("address")).isEqualTo("rwms.logistics.rental-inquiry.events.v1");
    Map<String, Object> message =
        child(child(child(contract, "components"), "messages"), "RentalInquiryBookedV1");
    assertThat(message)
        .containsEntry("name", "logistics.rental-inquiry.booked.v1")
        .containsEntry("x-rwms-record-key", "conversationId");
  }

  @Test
  void inboundContractAllowsOnlyDeclaredSourceFactsAndAHarmlessDlt() throws Exception {
    Map<String, Object> document = eventContract();
    Map<String, Object> channels = child(document, "channels");
    assertThat(channels)
        .containsKeys(
            "assetRentalItemFacts",
            "assetOperationLeaseFacts",
            "assetEquipmentAllocationHoldFacts",
            "taskBoardFacts",
            "maintenanceEstimateFacts",
            "mediaFacts",
            "inboundSanitizedDlt");

    Map<String, Object> operations = child(document, "operations");
    assertThat(operations)
        .containsKeys(
            "consumeRentalItemFacts",
            "consumeOperationLeaseFacts",
            "consumeEquipmentAllocationHoldFacts",
            "consumeBoardTaskFacts",
            "consumeMaintenanceEstimateFacts",
            "consumeMediaFacts",
            "publishInboundSanitizedConsumerFailure");

    Map<String, Object> messages = child(child(document, "components"), "messages");
    Map<String, Object> dlt = child(messages, "InboundSanitizedDltV1");
    Map<String, Object> payload = child(dlt, "payload");
    assertThat(child(payload, "properties"))
        .containsOnlyKeys(
            "failureCode", "messageSha256", "sourceTopic", "sourceEventId", "recordedAt");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> openApi() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  private Path eventSchemaPath() {
    return Path.of(
        System.getProperty("rwms.contracts.dir"),
        "events/logistics/logistics-events-v1.schema.json");
  }

  private Path rentalInquiryEventSchemaPath() {
    return Path.of(
        System.getProperty("rwms.contracts.dir"),
        "events/logistics/rental-inquiry-events-v1.schema.json");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> eventContract() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "events/logistics-events.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  private static Set<String> strings(JsonNode node) {
    java.util.HashSet<String> values = new java.util.HashSet<>();
    node.forEach(value -> values.add(value.stringValue()));
    return values;
  }

  private static Set<String> fieldNames(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }

  @SuppressWarnings("unchecked")
  private static List<String> publicOperationIds(Map<String, Object> paths) {
    List<String> operationIds = new ArrayList<>();
    paths.values().stream()
        .map(path -> (Map<String, Object>) path)
        .forEach(
            path ->
                path.forEach(
                    (method, operation) -> {
                      if (HTTP_METHODS.contains(method)) {
                        operationIds.add(
                            (String) ((Map<String, Object>) operation).get("operationId"));
                      }
                    }));
    return operationIds;
  }

  private static String normalizedPath(String path) {
    return path.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace("/", "");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> map, String name) {
    return (Map<String, Object>) map.get(name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return (Map<String, Object>) value;
  }
}
