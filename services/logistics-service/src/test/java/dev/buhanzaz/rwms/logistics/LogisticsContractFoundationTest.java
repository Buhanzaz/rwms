package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LogisticsContractFoundationTest {
  private static final String FORBIDDEN_PUBLIC_PATH_PATTERN =
      ".*(?:compan(?:y|ies)|candidates?|reservations?|"
          + "contents(?:\\{[^}]+})?transfers?).*";
  private static final Set<String> HTTP_METHODS =
      Set.of(
          "get", "put", "post", "delete", "options", "head", "patch", "trace");

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
            "/api/logistics/v1/driver-tasks/{taskId}/promote",
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
            "/api/logistics/v1/orders/{orderId}/rental-terms",
            "/api/logistics/v1/orders/{orderId}/rental-terms/extend",
            "/api/logistics/v1/orders/{orderId}/shipments",
            "/api/logistics/v1/orders/{orderId}/available-units",
            "/api/logistics/v1/orders/{orderId}/units",
            "/api/logistics/v1/orders/{orderId}/units/{unitId}/desired-equipment",
            "/api/logistics/v1/clients",
            "/api/logistics/v1/manual-booking-drafts/{draftId}/holds",
            "/api/logistics/v1/rental-inquiries",
            "/api/logistics/v1/rental-inquiries/{inquiryId}",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-facets",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-availability",
            "/api/logistics/v1/rental-inquiries/{inquiryId}/client-presentation",
            "/api/logistics/v1/settings/rental",
            "/api/logistics/public/v1/client-presentations/{token}",
            "/api/logistics/public/v1/client-presentations/{token}/bookings",
            "/api/logistics/public/v1/client-presentations/{token}/bookings/{bookingId}",
            "/api/logistics/public/v1/client-presentations/{token}/media/{cabinId}/{mediaId}/{generation}/{variant}",
            "/api/logistics/v1/{documentType}/{documentId}/reconcile");
    assertThat(child(child(document, "components"), "schemas"))
        .containsKeys(
            "CreateReturnRequest",
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
            "OrderRentalTermInput",
            "SetOrderRentalTermsRequest",
            "OrderRentalTermExtensionInput",
            "ExtendOrderRentalTermsRequest",
            "CreateOrderRentalShipmentRequest",
            "OrderDesiredEquipment",
            "SetOrderUnitDesiredEquipmentRequest",
            "OrderHistoryEvent",
            "CreateRentalInquiryRequest",
            "RentalInquiry",
            "CabinFacets",
            "CabinSearchRequest",
            "CabinSearchResponse",
            "PublishClientPresentationRequest",
            "ManualBookingDraftHoldsRequest",
            "ManualBookingDraftHolds",
            "ClientPresentation",
            "PublicClientPresentation",
            "PresentationBooking",
            "RentalSettings",
            "LogisticsDocument",
            "LogisticsLine",
            "ReconcileRequest");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
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
    Map<String, Object> idempotencyKey =
        child(child(document, "components"), "parameters");
    assertThat(idempotencyKey.get("IdempotencyKey"))
        .isInstanceOfSatisfying(
            Map.class,
            parameter -> {
              assertThat(parameter.get("in")).isEqualTo("header");
              assertThat(parameter.get("required")).isEqualTo(true);
            });
  }

  @Test
  void equipmentMovementDurationIsRequiredAndPositiveInCommandsAndResponses() throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");

    for (String schemaName :
        List.of("CreateEquipmentMovementTaskRequest", "EquipmentMovementTask")) {
      Map<String, Object> schema = child(schemas, schemaName);
      assertThat((List<?>) schema.get("required"))
          .anyMatch("plannedDurationMinutes"::equals);
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
            "requestReturnEstimate",
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
            "createMaintenanceDriverTask",
            "getMaintenanceDriverTaskCompensation",
            "cancelMaintenanceDriverTaskCompensation",
            "getDriverTask",
            "promoteDriverTask",
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
            "setOrderRentalTerms",
            "extendOrderRentalTerms",
            "createOrderRentalShipment",
            "searchOrderClients",
            "createOrderClient",
            "selectOrderWarehouse",
            "listOrderUnitCandidates",
            "addOrderUnit",
            "removeOrderUnit",
            "setOrderUnitDesiredEquipment",
            "getOrderHistory",
            "getManualBookingDraftHolds",
            "replaceManualBookingDraftHolds",
            "createRentalInquiry",
            "getRentalInquiry",
            "getRentalInquiryCabinFacets",
            "searchRentalInquiryCabins",
            "checkRentalInquiryCabinAvailability",
            "getRentalInquiryClientPresentation",
            "publishRentalInquiryClientPresentation",
            "revokeRentalInquiryClientPresentation",
            "listRentalBookingAlerts",
            "actOnRentalBookingAlert",
            "getRentalSettings",
            "updateRentalSettings",
            "getPublicClientPresentation",
            "confirmPublicClientPresentation",
            "getPublicClientPresentationBooking",
            "getPublicClientPresentationMedia",
            "reconcileDocument");
    assertThat(paths.keySet())
        .allSatisfy(
            path ->
                assertThat(normalizedPath(path))
                    .doesNotMatch(FORBIDDEN_PUBLIC_PATH_PATTERN));
  }

  @Test
  void maintenanceCompensationContractFreezesTheGuardedCancellationSemantics() throws Exception {
    Map<String, Object> paths = child(openApi(), "paths");
    Map<String, Object> endpoint =
        child(paths, "/api/internal/logistics/v1/maintenance/driver-tasks/repairs/{repairId}/cancel");
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
  void rentalSettingsExposeIndependentChatAndPresentationHoldDurations()
      throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> settings = child(schemas, "RentalSettings");
    Map<String, Object> settingsProperties = child(settings, "properties");
    Map<String, Object> update = child(schemas, "UpdateRentalSettingsRequest");
    Map<String, Object> updateProperties = child(update, "properties");

    List<String> settingsRequired =
        ((List<?>) settings.get("required")).stream()
            .map(String.class::cast)
            .toList();
    List<String> updateRequired =
        ((List<?>) update.get("required")).stream()
            .map(String.class::cast)
            .toList();

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
            child(
                    child(
                        child(child(operation, "responses"), "200"),
                        "content"),
                    "application/json")
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
                                            .isEqualTo("#/components/parameters/ExpectedVersion"))));
  }

  @Test
  void returnInspectionCommandsRequireLineOwnedPhotosAndExplicitEquipmentConfirmation()
      throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> acceptanceLine = child(schemas, "ReturnMediaLineRequest");
    Map<String, Object> acceptanceProperties = child(acceptanceLine, "properties");
    Map<String, Object> estimateLine = child(schemas, "ReturnShortageLineRequest");
    Map<String, Object> estimateProperties = child(estimateLine, "properties");

    assertThat(acceptanceLine.get("required"))
        .isEqualTo(List.of("lineId", "references", "equipmentConfirmed"));
    assertThat(child(acceptanceProperties, "references").get("minItems")).isEqualTo(1);
    assertThat(child(acceptanceProperties, "equipmentConfirmed"))
        .containsEntry("type", "boolean")
        .containsEntry("const", true);
    assertThat(estimateLine.get("required"))
        .isEqualTo(List.of("lineId", "references", "shortages"));
    assertThat(child(estimateProperties, "references").get("minItems")).isEqualTo(1);
  }

  @Test
  void schedulingUsesDatesForReturnsShipmentsAndTransfers() throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> returnPickup = child(schemas, "ReturnPickupRequest");
    Map<String, Object> shipmentPlan = child(schemas, "ShipmentPlanRequest");
    Map<String, Object> createTransfer = child(schemas, "CreateTransferRequest");
    Map<String, Object> cabinFurnitureTask = child(schemas, "CreateCabinFurnitureTaskRequest");
    Map<String, Object> document = child(schemas, "LogisticsDocument");

    assertThat(returnPickup.get("required"))
        .isEqualTo(List.of("driverSnapshot", "scheduledDate"));
    assertThat(child(child(returnPickup, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(returnPickup, "properties")).doesNotContainKey("scheduledAt");
    assertThat(shipmentPlan.get("required"))
        .isEqualTo(List.of("driverSnapshot", "scheduledDate"));
    assertThat(child(child(shipmentPlan, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(shipmentPlan, "properties")).doesNotContainKey("scheduledAt");
    List<String> transferRequired =
        ((List<?>) createTransfer.get("required")).stream().map(String.class::cast).toList();
    assertThat(transferRequired).contains("scheduledDate", "furnitureReplacements");
    assertThat(child(createTransfer, "properties")).doesNotContainKey("scheduledAt");
    assertThat(child(child(createTransfer, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(cabinFurnitureTask.get("required"))
        .isEqualTo(List.of("warehouseId", "scheduledDate", "contents"));
    assertThat(child(child(cabinFurnitureTask, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(child(document, "properties"), "scheduledDate").get("format"))
        .isEqualTo("date");
    assertThat(child(child(document, "properties"), "scheduledAt").get("format"))
        .isEqualTo("date-time");
  }

  @Test
  void orderCreationCannotAssignAnArbitraryManagerAndUnitsKeepTheAssetShape()
      throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> createOrder = child(schemas, "CreateOrderRequest");
    assertThat(child(createOrder, "properties"))
        .containsOnlyKeys("clientId", "newClient")
        .doesNotContainKey("managerId");
    assertThat(child(schemas, "ClientType").get("enum"))
        .isEqualTo(List.of("INDIVIDUAL", "LEGAL_ENTITY"));
    Map<String, Object> paths = child(openApi(), "paths");
    Map<String, Object> createOrderResponses =
        child(child(child(paths, "/api/logistics/v1/orders"), "post"), "responses");
    Map<String, Object> addUnitResponses =
        child(
            child(child(paths, "/api/logistics/v1/orders/{orderId}/units"), "post"),
            "responses");
    Map<String, Object> createClientResponses =
        child(child(child(paths, "/api/logistics/v1/clients"), "post"), "responses");
    assertThat(createOrderResponses).containsKeys("200", "201");
    assertThat(addUnitResponses).containsKeys("200", "201");
    assertThat(createClientResponses).containsKeys("200", "201");

    Map<String, Object> orderUnit = child(schemas, "OrderUnit");
    assertThat(child(orderUnit, "properties"))
        .containsOnlyKeys("reservationId", "added", "unit", "desiredContents", "rentalTerm");
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
            "rwms.logistics.return.v1",
            "rwms.logistics.shipment.v1",
            "rwms.logistics.transfer.v1");
    assertThat(schema.at("/properties/producer/const").stringValue()).isEqualTo("logistics-service");
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
  void rentalInquiryBookingFactHasADeclaredMinimalContractAndConversationKey() throws Exception {
    JsonNode schema =
        objectMapper.readTree(Files.readString(rentalInquiryEventSchemaPath()));
    assertThat(strings(schema.get("required")))
        .containsExactlyInAnyOrder(
            "eventId",
            "eventType",
            "occurredAt",
            "rentalInquiryId",
            "conversationId",
            "orderId");
    assertThat(fieldNames(schema.get("properties")))
        .containsExactlyInAnyOrder(
            "eventId",
            "eventType",
            "occurredAt",
            "rentalInquiryId",
            "conversationId",
            "orderId");

    Map<String, Object> contract = eventContract();
    Map<String, Object> channel = child(child(contract, "channels"), "rentalInquiryFacts");
    assertThat(channel.get("address"))
        .isEqualTo("rwms.logistics.rental-inquiry.events.v1");
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
    Path contract = Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  private Path eventSchemaPath() {
    return Path.of(
        System.getProperty("rwms.contracts.dir"), "events/logistics/logistics-events-v1.schema.json");
  }

  private Path rentalInquiryEventSchemaPath() {
    return Path.of(
        System.getProperty("rwms.contracts.dir"),
        "events/logistics/rental-inquiry-events-v1.schema.json");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> eventContract() throws Exception {
    Path contract = Path.of(System.getProperty("rwms.contracts.dir"), "events/logistics-events.yaml");
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
                            (String)
                                ((Map<String, Object>) operation).get("operationId"));
                      }
                    }));
    return operationIds;
  }

  private static String normalizedPath(String path) {
    return path
        .toLowerCase(Locale.ROOT)
        .replace("-", "")
        .replace("_", "")
        .replace("/", "");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> map, String name) {
    return (Map<String, Object>) map.get(name);
  }

}
