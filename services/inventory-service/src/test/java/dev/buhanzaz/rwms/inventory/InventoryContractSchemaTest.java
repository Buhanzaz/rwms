package dev.buhanzaz.rwms.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class InventoryContractSchemaTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchemaFactory SCHEMAS =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final Set<String> METHODS = Set.of("get", "post", "put", "delete");

  @Test
  void openApiContainsOnlyTheApprovedOperationsAndExactErrors() throws Exception {
    Map<String, Object> document = yaml("openapi/inventory-service.yaml");
    Map<String, Object> paths = child(document, "paths");

    assertThat(operations(paths))
        .containsExactlyInAnyOrder(
            "GET /api/inventory/v1/sessions",
            "POST /api/inventory/v1/sessions",
            "GET /api/inventory/v1/sessions/active",
            "GET /api/inventory/v1/return-estimates/{estimateId}/inspection",
            "GET /api/inventory/v1/sessions/{inventoryId}",
            "POST /api/inventory/v1/sessions/{inventoryId}/refresh",
            "GET /api/inventory/v1/sessions/{inventoryId}/findings",
            "POST /api/inventory/v1/sessions/{inventoryId}/registry-review",
            "GET /api/inventory/v1/sessions/{inventoryId}/statistics-preview",
            "GET /api/inventory/v1/sessions/{inventoryId}/cabin-disposition-review",
            "POST /api/inventory/v1/sessions/{inventoryId}/cabin-disposition-review/returns/confirm",
            "POST /api/inventory/v1/sessions/{inventoryId}/cabin-disposition-review/shipments/confirm",
            "GET /api/inventory/v1/planning-settings/{warehouseId}",
            "PUT /api/inventory/v1/planning-settings/{warehouseId}",
            "POST /api/inventory/v1/sessions/{inventoryId}/final-plan/prepare",
            "GET /api/inventory/v1/sessions/{inventoryId}/final-plan",
            "PUT /api/inventory/v1/sessions/{inventoryId}/final-plan",
            "POST /api/inventory/v1/sessions/{inventoryId}/number-resolutions",
            "POST /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/assets",
            "PUT /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/inspection",
            "PUT /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/conflict-resolution",
            "POST /api/inventory/v1/sessions/{inventoryId}/furniture-review/start",
            "GET /api/inventory/v1/sessions/{inventoryId}/furniture-review",
            "PUT /api/inventory/v1/sessions/{inventoryId}/furniture-review",
            "POST /api/inventory/v1/sessions/{inventoryId}/completion-preview",
            "POST /api/inventory/v1/sessions/{inventoryId}/complete",
            "POST /api/inventory/v1/sessions/{inventoryId}/cancel",
            "POST /api/inventory/v1/sessions/{inventoryId}/publications",
            "POST /api/inventory/v1/sessions/{inventoryId}/outcome/recalculate",
            "POST /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/publication/retry",
            "POST /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/publication/close",
            "POST /api/inventory/v1/operations/outbox/{eventId}/requeue",
            "POST /api/inventory/v1/operations/dead-letters/{dltId}/requeue",
            "GET /api/inventory/v1/statistics/sessions",
            "GET /api/inventory/v1/statistics/summary");

    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    assertThat(enumValues(child(schemas, "ProblemDetail"), "code"))
        .containsExactlyInAnyOrder(
            "INVENTORY_VALIDATION_FAILED",
            "INVENTORY_NOT_FOUND",
            "INVENTORY_VERSION_CONFLICT",
            "INVENTORY_IDEMPOTENCY_CONFLICT",
            "INVENTORY_ACTIVE_SESSION_CONFLICT",
            "INVENTORY_NUMBER_CONFLICT",
            "INVENTORY_ACKNOWLEDGEMENT_STALE",
            "INVENTORY_FINAL_PLAN_INCOMPLETE",
            "INVENTORY_MEDIA_NOT_READY",
            "INVENTORY_PUBLICATION_CONFLICT",
            "INVENTORY_DEPENDENCY_UNAVAILABLE",
            "INVENTORY_FORBIDDEN");
    assertThat(required(schemas, "FrozenStatistics"))
        .contains(
            "roundingAdjustmentMinor",
            "normativeMinutes",
            "durationSeconds",
            "unexpectedExistingCount");
    assertThat(enumValues(child(schemas, "ExpectedItemSnapshot"), "status"))
        .doesNotContain("NEW")
        .contains("FREE", "BOOKED", "WAREHOUSE");
    assertThat(stringList(child(schemas, "RentalItemStatus").get("enum")))
        .doesNotContain("NEW")
        .contains("FREE", "RENTED", "IN_TRANSFER");
    assertThat(stringList(child(schemas, "InspectionSource").get("enum")))
        .containsExactly("INVENTORY", "LOGISTICS_RETURN");
    assertThat(stringList(child(schemas, "InventoryCabinDispositionKind").get("enum")))
        .containsExactly("LOCAL", "SHIPMENT", "WRITE_OFF", "PRESERVE");
    assertThat(child(schemas, "FrozenStatistics").get("additionalProperties")).isEqualTo(false);
    assertThat(
            stringList(
                child(
                        child(
                            child(child(document, "components"), "parameters"), "FindingSort"),
                        "schema")
                    .get("enum")))
        .containsExactly(
            "createdAt,asc",
            "createdAt,desc",
            "displayCanonicalNumber,asc",
            "displayCanonicalNumber,desc");
    assertAllLocalReferencesResolve(document, document);
  }

  @Test
  void eventingRecoveryContractRequiresVersionedReviewAndReturnsStableReceipt() throws Exception {
    Map<String, Object> document = yaml("openapi/inventory-service.yaml");
    Map<String, Object> paths = child(document, "paths");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");

    assertThat(
            child(
                    child(paths, "/api/inventory/v1/operations/outbox/{eventId}/requeue"),
                    "post")
                .get("description")
                .toString())
        .contains("SYSTEM_ADMIN", "WMS_ADMIN", "expectedReviewVersion", "aggregate");
    assertThat(
            child(
                    child(
                        paths,
                        "/api/inventory/v1/operations/dead-letters/{dltId}/requeue"),
                    "post")
                .get("description")
                .toString())
        .contains("FAILED", "checksum", "cannot be overridden");
    assertThat(required(schemas, "EventingRecoveryRequest"))
        .containsExactly("expectedReviewVersion", "reason");
    assertThat(required(schemas, "EventingRecoveryReceipt"))
        .containsExactly("recordKind", "recordId", "status", "reviewVersion", "reviewedAt");
  }

  @Test
  void refreshContractCarriesTheManageFenceAndIdempotentSessionResponse() throws Exception {
    Map<String, Object> document = yaml("openapi/inventory-service.yaml");
    Map<String, Object> operation =
        child(
            child(
                child(document, "paths"),
                "/api/inventory/v1/sessions/{inventoryId}/refresh"),
            "post");

    assertThat(operation.get("operationId")).isEqualTo("refreshInventorySession");
    assertThat(operation.get("description").toString())
        .contains("USER", "rwms.write", "MANAGE", "retained");
    assertThat((List<?>) operation.get("parameters"))
        .anySatisfy(
            parameter ->
                assertThat(map(parameter).get("$ref"))
                    .isEqualTo("#/components/parameters/IdempotencyKey"));
    assertThat(
            child(
                    child(child(child(operation, "requestBody"), "content"), "application/json"),
                    "schema")
                .get("$ref"))
        .isEqualTo("#/components/schemas/RefreshSessionRequest");
    assertThat(child(child(operation, "responses"), "200").get("$ref"))
        .isEqualTo("#/components/responses/SessionDetailIdempotentResponse");

    Map<String, Object> request =
        child(child(child(document, "components"), "schemas"), "RefreshSessionRequest");
    assertThat(request.get("additionalProperties")).isEqualTo(false);
    assertThat(stringList(request.get("required"))).containsExactly("expectedSessionRevision");
    assertThat(
            child(child(request, "properties"), "expectedSessionRevision").get("minimum"))
        .isEqualTo(0);
  }

  @Test
  void planningSettingsAreHolidayOnlyAndRetainTheRevisionFence() throws Exception {
    Map<String, Object> document = yaml("openapi/inventory-service.yaml");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> request = child(schemas, "PlanningSettingsUpdateRequest");
    Map<String, Object> response = child(schemas, "PlanningSettings");

    assertThat(required(schemas, "PlanningSettingsUpdateRequest"))
        .containsExactly("expectedSettingsRevision", "holidays");
    assertThat(child(request, "properties").keySet())
        .containsExactly("expectedSettingsRevision", "holidays");
    assertThat(required(schemas, "PlanningSettings"))
        .containsExactly("warehouseId", "settingsRevision", "updatedAt", "holidays");
    assertThat(child(response, "properties").keySet())
        .containsExactly("warehouseId", "settingsRevision", "updatedAt", "holidays");
    assertThat(child(response, "properties"))
        .doesNotContainKeys("movementDailyCapacity", "repairDailyCapacity", "workingWeekdays");

    JsonSchema update = openApiSchema("PlanningSettingsUpdateRequest");
    assertThat(update.validate(JSON.readTree("{\"expectedSettingsRevision\":0,\"holidays\":[]}")))
        .isEmpty();
    assertThat(
            update.validate(
                JSON.readTree(
                    "{\"expectedSettingsRevision\":0,\"holidays\":[],\"workingWeekdays\":[\"MONDAY\"]}")))
        .isNotEmpty();

    JsonSchema settings = openApiSchema("PlanningSettings");
    assertThat(
            settings.validate(
                JSON.readTree(
                    "{\"warehouseId\":\"00000000-0000-0000-0000-000000000701\",\"settingsRevision\":0,\"updatedAt\":null,\"holidays\":[]}")))
        .isEmpty();
  }

  @Test
  void activeSessionContractSupportsConditionalReadsForBothStates() throws Exception {
    Map<String, Object> document = yaml("openapi/inventory-service.yaml");
    Map<String, Object> active =
        child(child(document, "paths"), "/api/inventory/v1/sessions/active");
    Map<String, Object> get = child(active, "get");

    assertThat((List<?>) get.get("parameters"))
        .anySatisfy(
            parameter ->
                assertThat(map(parameter).get("$ref"))
                    .isEqualTo("#/components/parameters/IfNoneMatch"));

    Map<String, Object> responses = child(get, "responses");
    for (String status : List.of("200", "204", "304")) {
      Map<String, Object> headers = child(child(responses, status), "headers");
      assertThat(child(headers, "ETag").get("$ref"))
          .isEqualTo("#/components/headers/ETag");
    }
  }

  @Test
  void inspectionObservationsAndPlanConditionalsValidateRealRequests() throws Exception {
    JsonSchema request = openApiSchema("SaveInspectionRequest");
    JsonNode ready =
        JSON.readTree(
            """
            {
              "expectedSessionRevision":2,
              "expectedFindingRevision":3,
              "inspection":"READY",
              "comment":"Осмотр завершён",
              "passportObservation":{"presence":"EXPLICIT_EMPTY","value":{}},
              "equipmentObservation":{"presence":"ABSENT","value":null},
              "media":[],
              "coverMediaId":null,
              "planSelection":null
            }
            """);
    assertThat(request.validate(ready)).isEmpty();

    ObjectNode wrongPassport = ready.deepCopy();
    wrongPassport.set(
        "passportObservation",
        JSON.readTree("{\"presence\":\"PRESENT\",\"value\":[]}"));
    assertThat(request.validate(wrongPassport)).isNotEmpty();
    ObjectNode wrongEquipment = ready.deepCopy();
    wrongEquipment.set(
        "equipmentObservation",
        JSON.readTree("{\"presence\":\"PRESENT\",\"value\":{\"x\":1}}"));
    assertThat(request.validate(wrongEquipment)).isNotEmpty();

    ObjectNode readyWithPlan = ready.deepCopy();
    readyWithPlan.set("planSelection", validAutoPlan());
    assertThat(request.validate(readyWithPlan)).isNotEmpty();
    ObjectNode stagedWithoutPlan = ready.deepCopy();
    stagedWithoutPlan.put("inspection", "WORK_STAGED");
    assertThat(request.validate(stagedWithoutPlan)).isNotEmpty();
    stagedWithoutPlan.set("planSelection", validAutoPlan());
    assertThat(request.validate(stagedWithoutPlan)).isEmpty();
    ObjectNode automaticWithDate = stagedWithoutPlan.deepCopy();
    ((ObjectNode) automaticWithDate.required("planSelection"))
        .put("logisticsScheduledDate", "2026-08-12");
    assertThat(request.validate(automaticWithDate)).isNotEmpty();
    ObjectNode fixedWithoutDate = stagedWithoutPlan.deepCopy();
    ((ObjectNode) fixedWithoutDate.required("planSelection"))
        .put("logisticsPlanningMode", "FIXED_DATE");
    assertThat(request.validate(fixedWithoutDate)).isNotEmpty();

    ObjectNode inboundAutomatic = stagedWithoutPlan.deepCopy();
    ((ObjectNode) inboundAutomatic.required("planSelection"))
        .put("movementToRepair", true)
        .put("logisticsPlanningMode", "AUTO");
    assertThat(request.validate(inboundAutomatic)).isEmpty();
    ObjectNode inboundFixedDate = inboundAutomatic.deepCopy();
    ((ObjectNode) inboundFixedDate.required("planSelection"))
        .put("logisticsPlanningMode", "FIXED_DATE")
        .put("logisticsScheduledDate", "2026-08-12");
    assertThat(request.validate(inboundFixedDate)).isEmpty();
    ObjectNode missingMovement = stagedWithoutPlan.deepCopy();
    ((ObjectNode) missingMovement.required("planSelection")).remove("movementToRepair");
    assertThat(request.validate(missingMovement)).isNotEmpty();
  }

  @Test
  void planLineRoutingSourceIsExplicitAndOnlyManualLinesMaySetIt() throws Exception {
    ObjectNode catalog = (ObjectNode) validAutoPlan().required("lines").get(0);
    JsonSchema catalogSchema = openApiSchema("CatalogInventoryPlanLineInput");
    assertThat(catalogSchema.validate(catalog)).isEmpty();
    ObjectNode catalogWithRoute = catalog.deepCopy();
    catalogWithRoute.put("routingCatalogNodeId", "00000000-0000-0000-0000-000000000732");
    assertThat(catalogSchema.validate(catalogWithRoute)).isNotEmpty();

    ObjectNode manual = catalog.deepCopy();
    manual.put("aggregationKind", "MANUAL");
    manual.putNull("catalogNodeId");
    manual.put("routingCatalogNodeId", "00000000-0000-0000-0000-000000000732");
    manual.put("description", "Manual work");
    manual.put("type", "WORK");
    manual.put("unit", "h");
    manual.put("unitPriceMinor", 100);
    manual.put("normativeMinutes", "30");
    JsonSchema manualSchema = openApiSchema("ManualInventoryPlanLineInput");
    assertThat(manualSchema.validate(manual)).isEmpty();
    ObjectNode manualWithoutRoute = manual.deepCopy();
    manualWithoutRoute.putNull("routingCatalogNodeId");
    assertThat(manualSchema.validate(manualWithoutRoute)).isNotEmpty();
  }

  @Test
  void authoritativeOutcomeRecoveryRequiresEveryImmutableFence() throws Exception {
    JsonSchema request = openApiSchema("RecalculateInventoryOutcomeRequest");
    ObjectNode valid =
        (ObjectNode)
            JSON.readTree(
                """
                {"expectedSessionRevision":7,"finalPlanVersion":2,
                 "finalPlanSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
    assertThat(request.validate(valid)).isEmpty();
    for (String required :
        List.of("expectedSessionRevision", "finalPlanVersion", "finalPlanSha256")) {
      ObjectNode missing = valid.deepCopy();
      missing.remove(required);
      assertThat(request.validate(missing)).isNotEmpty();
    }
    ObjectNode extra = valid.deepCopy();
    extra.put("force", true);
    assertThat(request.validate(extra)).isNotEmpty();
  }

  @Test
  void publicationSessionDetailAndStatisticsSchemasRejectImpossibleCombinations()
      throws Exception {
    JsonSchema publish = openApiSchema("PublishFindingsRequest");
    JsonNode all =
        JSON.readTree("{\"expectedSessionRevision\":1,\"allEligible\":true,\"findings\":[]}");
    assertThat(publish.validate(all)).isEmpty();
    ObjectNode allWithSelection = all.deepCopy();
    allWithSelection.set(
        "findings",
        JSON.readTree(
            "[{\"findingId\":\"00000000-0000-0000-0000-000000000711\",\"expectedPublicationRevision\":0}]"));
    assertThat(publish.validate(allWithSelection)).isNotEmpty();
    ObjectNode selectedEmpty = all.deepCopy();
    selectedEmpty.put("allEligible", false);
    assertThat(publish.validate(selectedEmpty)).isNotEmpty();
    selectedEmpty.set("findings", allWithSelection.required("findings"));
    assertThat(publish.validate(selectedEmpty)).isEmpty();

    JsonSchema summary = openApiSchema("StatisticsSummary");
    JsonNode nested =
        JSON.readTree(
            """
            {"sessionCount":0,"statistics":{
              "expectedCount":0,"inspectedCount":0,"missingCount":0,"readyCount":0,
              "withWorkCount":0,"addedCount":0,"unexpectedExistingCount":0,
              "conflictCount":0,"workLineCount":0,"materialLineCount":0,
              "workTotalMinor":0,"materialTotalMinor":0,"grandTotalMinor":0,
              "roundingAdjustmentMinor":0,"normativeMinutes":"0","durationSeconds":0,
              "aggregateLines":[]}}
            """);
    assertThat(summary.validate(nested)).isEmpty();
    ObjectNode flattened = (ObjectNode) nested.required("statistics").deepCopy();
    flattened.put("sessionCount", 0);
    assertThat(summary.validate(flattened)).isNotEmpty();

    JsonSchema detail = openApiSchema("SessionDetail");
    JsonNode validDetail =
        JSON.readTree(
            """
            {"id":"00000000-0000-0000-0000-000000000721","sessionRevision":0,
             "warehouseId":"00000000-0000-0000-0000-000000000722","warehouseVersion":1,
             "warehouseTimeZone":"Europe/Moscow","businessDate":"2026-07-17",
             "author":{"id":"00000000-0000-0000-0000-000000000723","displayName":"Inventory operator"},
             "lifecycle":"ACTIVE","reviewStage":"CABINS",
             "furnitureReconciliationState":"NOT_REQUIRED",
             "expectedCount":0,"findingCount":0,"inspectedCount":0,
             "startedAt":"2026-07-17T12:00:00Z","terminalAt":null,
             "publicationState":"NOT_REQUESTED",
             "membershipMovements":[{
               "id":"00000000-0000-0000-0000-000000000724","type":"ARRIVED",
               "assetId":"00000000-0000-0000-0000-000000000725",
               "displayCanonicalNumber":"БЫТ-101","origin":"EXPECTED",
               "fromWarehouseId":null,
               "toWarehouseId":"00000000-0000-0000-0000-000000000722",
               "status":"WAREHOUSE","tenantSnapshot":null,
               "occurredAt":"2026-07-17T12:30:00Z"
             }],
             "statistics":null,"cancellation":null}
            """);
    assertThat(detail.validate(validDetail)).isEmpty();
    ObjectNode missingMovements = validDetail.deepCopy();
    missingMovements.remove("membershipMovements");
    assertThat(detail.validate(missingMovements)).isNotEmpty();
    ObjectNode invalidMovement = validDetail.deepCopy();
    ((ObjectNode) invalidMovement.required("membershipMovements").get(0))
        .put("type", "UNCHANGED");
    assertThat(detail.validate(invalidMovement)).isNotEmpty();
  }

  @Test
  void matchedPublicationIntentRetainsMaintenanceEvidenceWithoutATarget() throws Exception {
    JsonSchema publication = openApiSchema("PublicationIntent");
    JsonNode matched =
        JSON.readTree(
            """
            {
              "id":"00000000-0000-0000-0000-000000000761",
              "inventoryId":"00000000-0000-0000-0000-000000000762",
              "findingId":"00000000-0000-0000-0000-000000000763",
              "publicationRevision":2,"state":"SUCCEEDED","sourceRevision":1,
              "attemptCount":1,"finalPlanVersion":1,
              "targetKind":null,"targetId":null,"maintenanceEstimateId":null,
              "maintenanceRepairId":null,"desiredAssetStatus":"REPAIR",
              "effectiveAssetVersion":8,
              "assetOutcomeResult":{"assetVersion":8,"status":"REPAIR"},
              "maintenanceOutcome":"MATCHED",
              "maintenanceResult":{
                "source":{"inventoryId":"00000000-0000-0000-0000-000000000762"},
                "outcome":"MATCHED","targetKind":null,"targetId":null,
                "estimateId":null,"repairId":null,"successor":null,"delta":{"lines":[]}
              },
              "failureCode":null
            }
            """);

    assertThat(publication.validate(matched)).isEmpty();

    ObjectNode matchedWithTarget = (ObjectNode) matched.deepCopy();
    matchedWithTarget.put("targetKind", "REPAIR");
    matchedWithTarget.put("targetId", "00000000-0000-0000-0000-000000000764");
    matchedWithTarget.put("maintenanceRepairId", "00000000-0000-0000-0000-000000000764");
    assertThat(publication.validate(matchedWithTarget)).isNotEmpty();
  }

  @Test
  void findingReadProjectionIsStrictTypedAndRejectsUnsafeOrInexactPlanValues()
      throws Exception {
    JsonSchema finding = openApiSchema("Finding");
    JsonNode staged =
        JSON.readTree(
            """
            {
              "id":"00000000-0000-0000-0000-000000000731",
              "inventoryId":"00000000-0000-0000-0000-000000000732",
              "findingRevision":3,"origin":"EXPECTED","inspection":"WORK_STAGED",
              "inspectionSource":"INVENTORY",
              "preserveOperationalState":false,
              "reconciliation":"MATCHED",
              "assetId":"00000000-0000-0000-0000-000000000733","assetVersion":7,
              "displayCanonicalNumber":"AA-01","identityMatchKey":"AA01",
              "passportObservation":{"presence":"ABSENT","value":null},
              "equipmentObservation":{"presence":"ABSENT","value":null},
              "mutationState":"IDLE",
              "planFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "comment":"Требуется ремонт",
              "expectedSnapshot":{
                "assetId":"00000000-0000-0000-0000-000000000733","assetVersion":7,
                "warehouseId":"00000000-0000-0000-0000-000000000732",
                "status":"WAREHOUSE","displayCanonicalNumber":"AA-01","tenantSnapshot":null,
                "passportSnapshot":{"serial":"SAFE"},"contentsSnapshot":[{"name":"safe"}]
              },
              "inspectionBaseline":{
                "assetId":"00000000-0000-0000-0000-000000000733","assetVersion":7,
                "warehouseId":"00000000-0000-0000-0000-000000000732",
                "status":"WAREHOUSE","displayCanonicalNumber":"AA-01","tenantSnapshot":null,
                "passportSnapshot":{"serial":"SAFE"},"contentsSnapshot":[{"name":"safe"}],
                "repairsSnapshot":[]
              },
              "currentSnapshot":{
                "assetId":"00000000-0000-0000-0000-000000000733","assetVersion":7,
                "warehouseId":"00000000-0000-0000-0000-000000000732",
                "status":"WAREHOUSE","displayCanonicalNumber":"AA-01","tenantSnapshot":null,
                "passportSnapshot":{"serial":"SAFE"},"contentsSnapshot":[{"name":"safe"}],
                "repairsSnapshot":[]
              },
              "conflicts":[],
              "conflictResolution":null,
              "frozenPlan":{
                "mode":"MANUAL","catalogVersionId":"00000000-0000-0000-0000-000000000734",
                "fingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "priority":3,"coverMediaId":null,
                "movementToRepair":false,
                "forceCapitalRepair":false,
                "logisticsPlanningMode":null,"logisticsScheduledDate":null,
                "lines":[{
                  "id":"00000000-0000-0000-0000-000000000736",
                  "sourceKind":"MANUAL","lineType":"WORK","catalogVersionId":null,
                  "catalogNodeId":null,
                  "routingQueueId":"00000000-0000-0000-0000-000000000735",
                  "routingQueueName":"Repair","routingQueueType":"MAINTENANCE",
                  "description":"Work","normalizedDescription":"work",
                  "unit":"HOUR","quantity":"1.25","unitPriceMinor":1234,
                  "normativeMinutes":"2.5","groupComment":null,"mediaReferences":[]
                }],
                "stages":[{
                  "id":"00000000-0000-0000-0000-000000000737",
                  "order":0,"kind":"REPAIR_WORK",
                  "catalogNodeId":"00000000-0000-0000-0000-000000000738",
                  "catalogNodeName":"Repair work",
                  "routingQueueId":"00000000-0000-0000-0000-000000000735",
                  "routingQueueName":"Repair","routingQueueType":"MAINTENANCE",
                  "photoRequired":true,
                  "normativeDurationMinutes":150
                }]
              },
              "coverMediaId":null,"media":[],"publication":null
            }
            """);
    assertThat(finding.validate(staged)).isEmpty();
    ObjectNode legacyMembershipChange = staged.deepCopy();
    legacyMembershipChange.put("membershipChange", "UNCHANGED");
    assertThat(finding.validate(legacyMembershipChange)).isNotEmpty();

    ObjectNode ready = staged.deepCopy();
    ready.put("origin", "UNEXPECTED_EXISTING");
    ready.put("inspection", "READY");
    ready.put("inspectionSource", "INVENTORY");
    ready.putNull("planFingerprintSha256");
    ready.putNull("expectedSnapshot");
    ready.putNull("frozenPlan");
    assertThat(finding.validate(ready)).isEmpty();

    ObjectNode rawPassport = staged.deepCopy();
    ((ObjectNode) rawPassport.required("expectedSnapshot")).put("passportSnapshot", "opaque");
    assertThat(finding.validate(rawPassport)).isNotEmpty();
    ObjectNode rawContents = staged.deepCopy();
    ((ObjectNode) rawContents.required("expectedSnapshot")).put("contentsSnapshot", "opaque");
    assertThat(finding.validate(rawContents)).isNotEmpty();

    ObjectNode extraPlanField = staged.deepCopy();
    ((ObjectNode) extraPlanField.required("frozenPlan")).put("sourceSnapshot", "forbidden");
    assertThat(finding.validate(extraPlanField)).isNotEmpty();
    ObjectNode numericQuantity = staged.deepCopy();
    ((ObjectNode) numericQuantity.required("frozenPlan").required("lines").get(0))
        .put("quantity", 1.25);
    assertThat(finding.validate(numericQuantity)).isNotEmpty();
    ObjectNode missingLineMedia = staged.deepCopy();
    ((ObjectNode) missingLineMedia.required("frozenPlan").required("lines").get(0))
        .remove("mediaReferences");
    assertThat(finding.validate(missingLineMedia)).isNotEmpty();
    ObjectNode excessiveScale = staged.deepCopy();
    ((ObjectNode) excessiveScale.required("frozenPlan").required("lines").get(0))
        .put("normativeMinutes", "2.5000");
    assertThat(finding.validate(excessiveScale)).isNotEmpty();
    ObjectNode negativeMinor = staged.deepCopy();
    ((ObjectNode) negativeMinor.required("frozenPlan").required("lines").get(0))
        .put("unitPriceMinor", -1);
    assertThat(finding.validate(negativeMinor)).isNotEmpty();
    ObjectNode legacyStageMovementFlag = staged.deepCopy();
    ((ObjectNode) legacyStageMovementFlag.required("frozenPlan").required("stages").get(0))
        .put("movementRequired", false);
    assertThat(finding.validate(legacyStageMovementFlag)).isNotEmpty();
    ObjectNode staleNonInboundPlanning = staged.deepCopy();
    ((ObjectNode) staleNonInboundPlanning.required("frozenPlan"))
        .put("logisticsPlanningMode", "AUTO");
    assertThat(finding.validate(staleNonInboundPlanning)).isNotEmpty();
    ObjectNode inboundFixedFrozenPlan = staged.deepCopy();
    ((ObjectNode) inboundFixedFrozenPlan.required("frozenPlan"))
        .put("movementToRepair", true)
        .put("logisticsPlanningMode", "FIXED_DATE")
        .put("logisticsScheduledDate", "2026-08-12");
    assertThat(finding.validate(inboundFixedFrozenPlan)).isEmpty();
    ObjectNode ambiguousRepairDestination = inboundFixedFrozenPlan.deepCopy();
    ((ObjectNode) ambiguousRepairDestination.required("frozenPlan"))
        .put("forceCapitalRepair", true);
    assertThat(finding.validate(ambiguousRepairDestination)).isNotEmpty();

    JsonSchema validatedFinding = openApiSchema("ValidatedFinding");
    JsonNode validated =
        JSON.readTree(
            """
            {
              "findingId":"00000000-0000-0000-0000-000000000731",
              "currentSnapshot":null,
              "conflicts":[]
            }
            """);
    assertThat(validatedFinding.validate(validated)).isEmpty();
    ObjectNode legacyValidated = validated.deepCopy();
    legacyValidated.put("membershipChange", "DEPARTED_OR_MOVED");
    assertThat(validatedFinding.validate(legacyValidated)).isNotEmpty();
  }

  @Test
  void asyncApiBindsApprovedAggregateAndDependencyTopicsAndOwnerProof() throws Exception {
    Map<String, Object> document = yaml("events/inventory-events.yaml");
    Map<String, Object> channels = child(document, "channels");

    assertThat(channels.keySet())
        .containsExactlyInAnyOrder(
            "sessionFacts",
            "publicationFacts",
            "mediaFacts",
            "rentalItemFacts",
            "sanitizedDlt");
    assertThat(child(channels, "sessionFacts").get("address"))
        .isEqualTo("rwms.inventory.session.v1");
    assertThat(child(channels, "publicationFacts").get("address"))
        .isEqualTo("rwms.inventory.publication.v1");
    assertThat(child(channels, "rentalItemFacts").get("address"))
        .isEqualTo("rwms.asset.rental-item.v1");
    String text = Files.readString(contract("events/inventory-events.yaml"));
    assertThat(text)
        .contains(
            "inventory.finding.owner-proof.v1",
            "aggregateId and",
            "payload.ownerId",
            "rwms.inventory.dlt.v1")
        .doesNotContain(
            "protocol: amqp",
            "rwms.domain.v1",
            "rwms.logistics",
            "media.processing.request.v1",
            "VERSION_GAP");
    assertAllLocalReferencesResolve(document, document);
  }

  @Test
  void ownerProofIsStrictAndRejectsCrossBoundaryInformation() throws Exception {
    JsonNode valid =
        JSON.readTree(
            """
            {
              "envelopeVersion":2,
              "eventId":"00000000-0000-0000-0000-000000000701",
              "eventType":"inventory.finding.owner-proof.v1",
              "eventVersion":1,
              "occurredAt":null,
              "recordedAt":"2026-07-17T12:00:00Z",
              "producer":"inventory-service",
              "aggregateType":"FINDING",
              "aggregateId":"00000000-0000-0000-0000-000000000702",
              "aggregateVersion":1,
              "correlation":{"correlationId":"00000000-0000-0000-0000-000000000703","causationId":null},
              "actorRef":null,
              "payload":{
                "ownerType":"INVENTORY_FINDING",
                "ownerId":"00000000-0000-0000-0000-000000000702",
                "warehouseId":"00000000-0000-0000-0000-000000000704",
                "ownerRevision":0,
                "active":true
              }
            }
            """);

    assertThat(schema().validate(valid)).isEmpty();

    ObjectNode inactive = valid.deepCopy();
    inactive.put("aggregateVersion", 4);
    ((ObjectNode) inactive.required("payload")).put("ownerRevision", 1).put("active", false);
    assertThat(schema().validate(inactive)).isEmpty();

    ObjectNode leaking = valid.deepCopy();
    ((ObjectNode) leaking.required("payload")).put("displayCanonicalNumber", "AB-12");
    assertThat(schema().validate(leaking)).isNotEmpty();

    ObjectNode wrongFamily = valid.deepCopy();
    wrongFamily.put("aggregateType", "SESSION");
    assertThat(schema().validate(wrongFamily)).isNotEmpty();
  }

  @Test
  void schemaDeclaresEverySanitizedFactAndProhibitedField() throws Exception {
    JsonNode root =
        JSON.readTree(
            Files.readString(contract("events/inventory/inventory-events-v1.schema.json")));
    assertThat(root.required("x-rwms-topics").toString())
        .contains("rwms.inventory.session.v1", "rwms.inventory.publication.v1");
    assertThat(root.required("x-rwms-prohibitedPayloadFields").toString())
        .contains(
            "login",
            "displayName",
            "email",
            "passport",
            "mediaUrl",
            "objectKey",
            "rawError",
            "jwt",
            "secret");
    assertThat(root.required("properties").required("eventType").required("enum").size())
        .isEqualTo(15);
    assertThat(root.required("properties").required("aggregateVersion").required("minimum").asInt())
        .isZero();
    assertThat(root.required("properties").required("aggregateType").required("enum").toString())
        .contains("SESSION", "FINDING", "PUBLICATION")
        .doesNotContain("INVENTORY_SESSION", "INVENTORY_FINDING", "INVENTORY_PUBLICATION");
  }

  @Test
  void finalPlanPublicationAcceptsTheCurrentReceiptButRejectsPrivateOrUnknownFields() throws Exception {
    ObjectNode event = (ObjectNode) JSON.readTree(Files.readString(
        contract("events/inventory/fixtures/publication-succeeded-final-plan.json")));
    assertThat(schema().validate(event)).isEmpty();

    ObjectNode payload = (ObjectNode) event.required("payload");
    ObjectNode result = (ObjectNode) payload.required("maintenanceResult");
    result.put("displayName", "private value");
    assertThat(schema().validate(event)).isNotEmpty();
    result.remove("displayName");
    ((ObjectNode) result.required("source")).put("strategy", "UNKNOWN");
    assertThat(schema().validate(event)).isNotEmpty();

    payload.remove(List.of("finalPlanVersion", "finalPlanSha256", "targetKind", "targetId",
        "maintenanceEstimateId", "maintenanceOutcome", "maintenanceResult"));
    ((ObjectNode) payload.required("sourceReference")).remove("finalPlanVersion");
    assertThat(schema().validate(event)).isEmpty();
  }

  private ObjectNode validAutoPlan() throws Exception {
    return (ObjectNode)
        JSON.readTree(
            """
            {"mode":"AUTO","priority":3,"coverMediaId":null,
              "movementToRepair":false,
              "logisticsPlanningMode":null,"logisticsScheduledDate":null,"lines":[{
              "aggregationKind":"CATALOG",
              "catalogNodeId":"00000000-0000-0000-0000-000000000731",
              "routingCatalogNodeId":null,
              "description":null,"type":null,"unit":null,"quantity":"1",
              "unitPriceMinor":null,"normativeMinutes":null,"groupComment":null,
              "mediaReferences":[]}],"stages":[]}
            """);
  }

  private JsonSchema openApiSchema(String name) throws Exception {
    ObjectNode document = JSON.valueToTree(yaml("openapi/inventory-service.yaml"));
    document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    document.put("$ref", "#/components/schemas/" + name);
    return SCHEMAS.getSchema(document);
  }

  private JsonSchema schema() throws Exception {
    return SCHEMAS.getSchema(
        JSON.readTree(
            Files.readString(contract("events/inventory/inventory-events-v1.schema.json"))));
  }

  private Map<String, Object> yaml(String relative) throws Exception {
    try (var input = Files.newInputStream(contract(relative))) {
      return new Yaml().load(input);
    }
  }

  private Path contract(String relative) {
    return Path.of(System.getProperty("rwms.contracts.dir"), relative);
  }

  private Set<String> operations(Map<String, Object> paths) {
    Set<String> result = new LinkedHashSet<>();
    paths.forEach(
        (path, item) ->
            map(item).keySet().stream()
                .filter(METHODS::contains)
                .forEach(method -> result.add(method.toUpperCase() + " " + path)));
    return result;
  }

  private List<String> enumValues(Map<String, Object> schema, String property) {
    return stringList(child(child(schema, "properties"), property).get("enum"));
  }

  private List<String> required(Map<String, Object> schemas, String name) {
    return stringList(child(schemas, name).get("required"));
  }

  @SuppressWarnings("unchecked")
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> value, String key) {
    return (Map<String, Object>) value.get(key);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Object value) {
    return (List<String>) value;
  }
}
