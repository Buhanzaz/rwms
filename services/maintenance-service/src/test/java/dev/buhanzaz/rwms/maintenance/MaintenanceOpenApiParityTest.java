package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkApiModels.*;
import static dev.buhanzaz.rwms.maintenance.api.WarehouseOperationMarkRecoveryApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceCatalogController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceHistoricalShipmentController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentClosureOutcome;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentRentalItemStatus;
import dev.buhanzaz.rwms.maintenance.api.EstimateCreationWindowSettingsController;
import dev.buhanzaz.rwms.maintenance.api.EstimateCreationWindowSettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceEstimateController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceInventoryController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceLogisticsController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceRepairController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceRepairPlaceLogisticsController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceSettingsController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceTransferRepairController;
import dev.buhanzaz.rwms.maintenance.api.WarehouseOperationMarkRecoveryController;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionController;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionInventoryController;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.ApprovePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionLine;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionLineInput;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionPlan;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionPlanInput;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateCabinPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateEquipmentPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryLossDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryCabinWriteOffRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreatePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateResult;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionDecisionResponse;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionPage;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionRepairChainEntry;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RecoverPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RejectPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.WriteOffRepairRequest;
import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetEffectState;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.api.LogisticsCapitalRepairResponse;
import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceProjectionAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceProjectionResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairComplexitySettingsController;
import dev.buhanzaz.rwms.maintenance.api.RepairComplexitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ImportRepairComplexitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.api.RepairComplexityColorsController;
import dev.buhanzaz.rwms.maintenance.api.RepairComplexityColorsResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairCapacitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairCapacitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.api.ReplaceEstimateCreationWindowSettingsRequest;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexityColorsRequest;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceController;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceProjectionResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceTransitionRequest;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.EstimateCreationWindowSettingsService;
import dev.buhanzaz.rwms.maintenance.service.FurnitureEquipmentLinkReviewService;
import dev.buhanzaz.rwms.maintenance.service.InventoryAuthoritativeOutcomeService;
import dev.buhanzaz.rwms.maintenance.service.InventoryMaintenanceService;
import dev.buhanzaz.rwms.maintenance.service.InventoryPublicationReconciliationService;
import dev.buhanzaz.rwms.maintenance.service.HistoricalShipmentRepairClosureService;
import dev.buhanzaz.rwms.maintenance.service.LogisticsReturnShortageService;
import dev.buhanzaz.rwms.maintenance.service.RepairCapacitySettingsService;
import dev.buhanzaz.rwms.maintenance.service.RepairComplexityColorsService;
import dev.buhanzaz.rwms.maintenance.service.RepairComplexitySettingsService;
import dev.buhanzaz.rwms.maintenance.service.RepairPlaceService;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkRecoveryService;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkStore;
import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exact executable acceptance for the approved Stage 6 HTTP boundary. */
class MaintenanceOpenApiParityTest {
  private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000006");
  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
  private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
  private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete");
  private static final Map<Class<?>, String> SCHEMAS = schemaMappings();
  private static final List<OperationSpec> OPERATIONS = canonicalOperations();

  @Test
  void allFiftyNinePathsAndSeventyOneOperationsExactlyMatchTheApprovedAcceptanceMatrix()
      throws Exception {
    Map<String, Object> document = openApi();
    assertThat(child(document, "paths")).hasSize(59);
    assertThat(openApiOperationCount(document)).isEqualTo(71);
    assertThat(controllerOperations()).hasSize(71);

    for (OperationSpec expected : OPERATIONS) {
      assertOpenApiOperation(document, expected);
      assertControllerOperation(expected);
    }
  }

  @Test
  void inventoryBoundaryIsPrivateAndDoesNotExposeGenericMaintenanceMutations() throws Exception {
    Map<String, Object> document = openApi();
    Set<String> inventoryPaths = child(document, "paths").keySet().stream()
        .filter(path -> path.contains("/inventory"))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    assertThat(inventoryPaths).containsExactly(
        "/api/internal/maintenance/v1/inventory/dispositions",
        "/api/internal/maintenance/v1/inventory/cabin-write-offs",
        "/api/internal/maintenance/v1/inventory/plans",
        "/api/internal/maintenance/v1/inventory/repair-snapshots",
        "/api/internal/maintenance/v1/inventory/sources/{inventoryId}/findings/{findingId}",
        "/api/internal/maintenance/v1/inventory/reconciliations/preflight",
        "/api/internal/maintenance/v1/inventory/reconciliations/{inventoryId}/findings/{findingId}",
        "/api/internal/maintenance/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/no-work");
    assertThat(inventoryPaths)
        .allMatch(path -> path.startsWith("/api/internal/maintenance/v1/inventory"))
        .noneMatch(path -> path.startsWith("/api/maintenance/"))
        .noneMatch(path -> Set.of("lease", "hold", "fence", "mutation").stream()
            .anyMatch(path::contains));
    assertThat(child(document, "paths").keySet().stream()
        .filter(path -> path.startsWith("/api/maintenance/")))
        .noneMatch(path -> path.contains("/internal/"));
  }

  @Test
  void logisticsBoundaryIsPrivateAndDoesNotExposeRepairOrAssetMutation() throws Exception {
    Map<String, Object> document = openApi();
    Set<String> logisticsPaths = child(document, "paths").keySet().stream()
        .filter(path -> path.contains("/logistics"))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    assertThat(logisticsPaths).containsExactly(
        "/api/internal/maintenance/v1/logistics/returns/{returnId}/lines/{lineId}/estimate-source",
        "/api/internal/maintenance/v1/logistics/historical-shipments/{shipmentId}/close",
        "/api/internal/maintenance/v1/logistics/transfers/{transferId}/lines/{lineId}/prepare-departure",
        "/api/internal/maintenance/v1/logistics/transfers/{transferId}/lines/{lineId}/arrival-preflight",
        "/api/internal/maintenance/v1/logistics/transfers/{transferId}/lines/{lineId}/complete-arrival",
        "/api/internal/maintenance/v1/logistics/repair-places/{warehouseId}",
        "/api/internal/maintenance/v1/logistics/repairs/capital",
        "/api/internal/maintenance/v1/logistics/repairs/capital/{repairId}",
        "/api/internal/maintenance/v1/logistics/repair-places/{warehouseId}/allocations/{repairId}/reserve",
        "/api/internal/maintenance/v1/logistics/repair-places/{warehouseId}/allocations/{repairId}/occupy",
        "/api/internal/maintenance/v1/logistics/repair-places/{warehouseId}/allocations/{repairId}/ready-to-release",
        "/api/internal/maintenance/v1/logistics/repair-places/{warehouseId}/allocations/{repairId}/release");
    assertThat(logisticsPaths)
        .allMatch(path -> path.startsWith("/api/internal/maintenance/v1/logistics"))
        .noneMatch(path -> path.startsWith("/api/maintenance/"))
        .noneMatch(path -> Set.of("/estimates", "/leases", "/holds", "/fences", "/tasks")
            .stream()
            .anyMatch(path::contains));
  }

  @Test
  void inventoryRevisionAndRepairStageSchemasKeepTheirExactOwnership() throws Exception {
    Map<String, Object> document = openApi();
    for (String schemaName : List.of(
        "FreezeInventoryPlanRequest", "FrozenInventoryPlan",
        "UpsertInventoryRepairRequest", "InventorySourceReference")) {
      assertThat(child(child(schema(document, schemaName), "properties"), "sourceRevision"))
          .containsEntry("minimum", 1);
    }
    assertThat(child(schema(document, "RepairStage"), "properties"))
        .containsKeys("taskSync", "completedAt");
    assertThat(stringList(schema(document, "RepairStage").get("required")))
        .contains("taskSync", "completedAt");
    assertThat(child(schema(document, "UpsertInventoryRepairRequest"), "properties"))
        .containsOnlyKeys(
            "warehouseId", "sourceRevision", "rentalItemId", "rentalItemVersion",
            "dispatchDate", "planFingerprint", "snapshot");
    Map<String, Object> assetIds = child(
        child(schema(document, "InventoryRepairSnapshotRequest"), "properties"), "assetIds");
    assertThat(assetIds)
        .containsEntry("minItems", 1)
        .containsEntry("maxItems", 5000)
        .containsEntry("uniqueItems", true);
  }

  @Test
  void inventoryPublicationPreflightFindingsMayBeEmptyButRemainRequiredAndBounded()
      throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> requestSchema =
        schema(document, "InventoryPublicationPreflightRequest");
    Map<String, Object> findings = child(child(requestSchema, "properties"), "findings");
    assertThat(stringList(requestSchema.get("required"))).contains("findings");
    assertThat(findings)
        .containsEntry("minItems", 0)
        .containsEntry("maxItems", 5000);

    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      var validator = validatorFactory.getValidator();
      InventoryPublicationPreflightRequest empty = new InventoryPublicationPreflightRequest(
          ID, ID, 1L, "a".repeat(64), List.of());
      assertThat(validator.validate(empty)).isEmpty();

      InventoryPublicationPreflightRequest missing = new InventoryPublicationPreflightRequest(
          ID, ID, 1L, "a".repeat(64), null);
      assertThat(validator.validate(missing))
          .extracting(violation -> violation.getPropertyPath().toString())
          .contains("findings");

      InventoryPublicationFindingInput finding = (InventoryPublicationFindingInput)
          sample(InventoryPublicationFindingInput.class, "inventoryPublicationFindingInput");
      InventoryPublicationPreflightRequest oversized = new InventoryPublicationPreflightRequest(
          ID, ID, 1L, "a".repeat(64), java.util.Collections.nCopies(5001, finding));
      assertThat(validator.validate(oversized))
          .extracting(violation -> violation.getPropertyPath().toString())
          .contains("findings");
    }
  }

  @Test
  void workCatalogDurationsHaveAConditionalMinimumOfOneMinute() throws Exception {
    Map<String, Object> document = openApi();

    for (String schemaName : List.of("CatalogNodeInput", "CatalogNode", "CatalogNodeSnapshot")) {
      Map<String, Object> catalogSchema = schema(document, schemaName);
      assertThat(catalogSchema.get("allOf").toString())
          .contains("nodeType", "WORK", "durationMinutes", "minimum=1");
    }
  }

  @Test
  void everyOperationDeclaresBearerSecurityAndCanonicalProblemResponses() throws Exception {
    Map<String, Object> document = openApi();
    assertThat(document.get("security"))
        .isEqualTo(List.of(Map.of("bearerJwt", List.of())));
    Map<String, Object> scheme = child(child(document, "components"), "securitySchemes");
    assertThat(child(scheme, "bearerJwt"))
        .containsEntry("type", "http")
        .containsEntry("scheme", "bearer")
        .containsEntry("bearerFormat", "JWT");

    for (OperationSpec expected : OPERATIONS) {
      Map<String, Object> operation = openApiOperation(document, expected);
      assertThat(operation).as(expected.id()).doesNotContainKey("security");
      Map<String, Object> responses = child(operation, "responses");
      assertThat(responses.keySet())
          .as(expected.id() + " status declarations")
          .containsExactlyInAnyOrderElementsOf(expected.allStatuses());
      assertProblemResponse(document, expected.id(), responses, "401", "Unauthorized");
      assertProblemResponse(document, expected.id(), responses, "403", "Forbidden");
      for (String statusCode : expected.errors()) {
        if ("304".equals(statusCode)) {
          assertNotModifiedResponse(document, expected.id(), responses);
        } else {
          assertProblemResponse(document, expected.id(), responses, statusCode, null);
        }
      }
    }
  }

  @Test
  void canonicalProblemJsonMatchesTheSharedTechnicalContractAndSecurityCodes() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> problem = schema(document, "ProblemDetail");
    Set<String> actualWireFields = Arrays.stream(ApiProblem.class.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    // The shared technical contract is authoritative: correlation is nested and violations are stable.
    assertThat(child(problem, "properties").keySet())
        .containsExactlyInAnyOrderElementsOf(actualWireFields);
    assertThat(child(child(problem, "properties"), "correlation"))
        .containsEntry("$ref", "#/components/schemas/CorrelationContext");
    assertThat(child(child(problem, "properties"), "violations"))
        .containsEntry("type", "array");
    assertThat(enumValues(child(child(problem, "properties"), "code")))
        .contains("MAINTENANCE_UNAUTHORIZED", "MAINTENANCE_FORBIDDEN");
  }

  @Test
  void everyResponseDeclaresCorrelationAndIdempotentReplayHeaders() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> components = child(document, "components");
    Map<String, Object> headers = child(components, "headers");
    assertThat(child(headers, "X-Correlation-Id").get("schema"))
        .isEqualTo(Map.of("type", "string", "format", "uuid"));
    assertThat(child(headers, "Idempotency-Replayed").get("schema"))
        .isEqualTo(Map.of("type", "string", "const", "true"));
    assertThat(child(headers, "ETag").get("schema"))
        .isEqualTo(Map.of("type", "string", "minLength", 1, "maxLength", 512));

    for (OperationSpec expected : OPERATIONS) {
      Map<String, Object> response = child(
          child(openApiOperation(document, expected), "responses"), expected.successStatus());
      assertResponseHeader(response, "X-Correlation-Id", "#/components/headers/X-Correlation-Id");
      if (expected.idempotent()) {
        assertResponseHeader(
            response, "Idempotency-Replayed", "#/components/headers/Idempotency-Replayed");
      } else {
        assertThat(mapOrEmpty(response.get("headers")))
            .as(expected.id())
            .doesNotContainKey("Idempotency-Replayed");
      }
      if (expected.errors().contains("304")) {
        assertResponseHeader(response, "ETag", "#/components/headers/ETag");
      }
    }
    for (Map.Entry<String, Object> entry : child(components, "responses").entrySet()) {
      assertResponseHeader(map(entry.getValue()), "X-Correlation-Id", "#/components/headers/X-Correlation-Id");
    }
  }

  @Test
  void everyNestedRequestAndResponseRecordSerializesAsItsCanonicalSchema() throws Exception {
    Map<String, Object> document = openApi();
    for (Map.Entry<Class<?>, String> entry : SCHEMAS.entrySet()) {
      Class<?> recordType = entry.getKey();
      if (!recordType.isRecord()) continue;
      String schemaName = entry.getValue();
      JsonNode serialized = MAPPER.valueToTree(sample(recordType, recordType.getSimpleName()));
      assertRecordProperties(recordType, schema(document, schemaName));
      assertJsonMatchesSchema(document, serialized, schema(document, schemaName), schemaName);
      assertNullablePropertiesRoundTrip(document, recordType, schemaName);
    }

    assertPage(document, "CatalogVersionPage", CatalogVersionResponse.class);
    assertPage(document, "EstimatePage", EstimateResponse.class);
    assertPage(document, "RepairPage", RepairResponse.class);
    assertPage(document, "AcceptanceProjectionPage", AcceptanceProjection.class);
  }

  @Test
  void everyRequiredRequestPropertyRejectsMissingAndUsesExactNullability() throws Exception {
    Map<String, Object> document = openApi();
    var softly = new org.assertj.core.api.SoftAssertions();
    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      var validator = validatorFactory.getValidator();
      for (Map.Entry<Class<?>, String> request : requestSchemaMappings().entrySet()) {
        Class<?> recordType = request.getKey();
        Map<String, Object> requestSchema = schema(document, request.getValue());
        Map<String, Object> properties = child(requestSchema, "properties");
        Object fixture = sample(recordType, recordType.getSimpleName());
        ObjectNode full = (ObjectNode) MAPPER.valueToTree(fixture);
        for (String property : stringList(requestSchema.get("required"))) {
          String field = recordType.getSimpleName() + "." + property;
          ObjectNode missing = full.deepCopy();
          missing.remove(property);
          softly.assertThat(rejectedByJacksonOrValidation(
                  MAPPER.writeValueAsBytes(missing), recordType, validator))
              .as(field + " missing property")
              .isTrue();

          ObjectNode explicitNull = full.deepCopy();
          explicitNull.putNull(property);
          boolean nullRejected = rejectedByJacksonOrValidation(
              MAPPER.writeValueAsBytes(explicitNull), recordType, validator);
          if ("logisticsScheduledDate".equals(property)
              && logisticsPlanningMode(fixture)
                  == RepairLogisticsPlanningMode.FIXED_DATE) {
            softly.assertThat(nullRejected)
                .as(field + " conditionally required")
                .isTrue();
          } else if (allowsNull(document, child(properties, property))) {
            softly.assertThat(nullRejected).as(field + " explicit null").isFalse();
          } else {
            softly.assertThat(nullRejected).as(field + " explicit null").isTrue();
          }
        }
      }
    }
    softly.assertAll();
  }

  private static boolean rejectedByJacksonOrValidation(
      byte[] json, Class<?> recordType, jakarta.validation.Validator validator) {
    try {
      Object value = MAPPER.readValue(json, recordType);
      return !validator.validate(value).isEmpty();
    } catch (RuntimeException exception) {
      return true;
    }
  }

  private static RepairLogisticsPlanningMode logisticsPlanningMode(
      Object value) {
    if (value == null || !value.getClass().isRecord()) {
      return null;
    }
    return Arrays.stream(value.getClass().getRecordComponents())
        .filter(
            component ->
                "logisticsPlanningMode"
                    .equals(component.getName()))
        .findFirst()
        .map(
            component -> {
              try {
                return (RepairLogisticsPlanningMode)
                    component.getAccessor().invoke(value);
              } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
              }
            })
        .orElse(null);
  }

  @Test
  void everyPublishedEnumUsesItsExactJacksonWireValue() throws Exception {
    Map<String, Object> document = openApi();
    assertEnum(document, CatalogVersionState.class, "CatalogLifecycle");
    assertEnum(document, EstimateState.class, "EstimateLifecycle");
    assertEnum(document, RepairOrigin.class, "RepairOrigin");
    assertEnum(document, RepairKind.class, "RepairKind");
    assertEnum(document, RepairExecutionState.class, "RepairExecutionState");
    assertEnum(document, RepairAcceptanceState.class, "RepairAcceptanceState");
    assertEnum(document, PropertyDispositionAssetKind.class, "PropertyDispositionAssetKind");
    assertEnum(document, PropertyDispositionKind.class, "PropertyDispositionKind");
    assertEnum(document, PropertyDispositionSource.class, "PropertyDispositionSource");
    assertEnum(document, PropertyDispositionState.class, "PropertyDispositionState");
    assertEnum(
        document,
        PropertyDispositionAssetEffectState.class,
        "PropertyDispositionAssetEffectState");
    assertEnum(document, PropertyDispositionContentsMode.class, "CabinContentsDispositionMode");
    assertEnum(document, RepairReclassificationState.class, "RepairReclassificationState");
    assertEnum(document, RepairPlaceAllocationState.class, "RepairPlaceAllocationState");
    assertEnum(document, RepairStageKind.class, "RepairStageKind");
    assertEnum(document, RepairStageState.class, "RepairStageState");
    assertEnum(document, CatalogNodeType.class, "CatalogNodeType");
    assertEnum(document, CatalogLinkType.class, "CatalogLinkType");
    assertEnum(document, CatalogLinkAnchor.class, "CatalogLinkAnchor");
    assertEnum(document, FurnitureEquipmentLinkState.class, "FurnitureEquipmentLinkState");
    assertEnum(
        document,
        FurnitureEquipmentLinkReviewAction.class,
        "FurnitureEquipmentLinkReviewAction");
    assertEnum(document, DeliveryState.class, "DeliveryState");
    assertEnum(document, LeaseReconciliationState.class, "LeaseReconciliationState");
    assertEnum(document, GenerationState.class, "GenerationState");
    assertEnum(
        document,
        RepairLogisticsPlanningMode.class,
        "RepairLogisticsPlanningMode");
    assertEnum(document, InventoryPlanMode.class, "InventoryPlanMode");
    assertEnum(document, InventoryPlanLineKind.class, "InventoryPlanLineKind");
    assertEnum(document, InventoryPlanLineType.class, "InventoryPlanLineType");
    assertEnum(
        document, InventoryPublicationTargetKind.class, "InventoryPublicationTargetKind");
    assertEnum(document, InventoryPublicationStrategy.class, "InventoryPublicationStrategy");
    assertEnum(document, InventoryPublicationOutcome.class, "InventoryPublicationOutcome");
    assertEnum(
        document,
        InventoryPublicationSuccessorState.class,
        "InventoryPublicationSuccessorState");
    assertEnum(
        document,
        InventoryPublicationTerminalFact.class,
        "InventoryPublicationTerminalFact");
    assertEnum(
        document,
        InventoryPublicationDeltaDisposition.class,
        "InventoryPublicationDeltaDisposition");
    assertEnum(document, EstimateLineType.class, "EstimateLineType");
    assertPropertyEnum(document, ActorType.class, "ActorSnapshot", "actorType");
  }

  @Test
  void mockMvcExecutesEveryControllerBindingStatusBodyAndRequiredHeader() throws Exception {
    MaintenanceApplicationService service = serviceFixture();
    InventoryMaintenanceService inventory = inventoryFixture();
    InventoryPublicationReconciliationService publications = publicationsFixture();
    InventoryAuthoritativeOutcomeService authoritativeOutcomes =
        authoritativeOutcomesFixture();
    LogisticsReturnShortageService logistics = logisticsFixture();
    HistoricalShipmentRepairClosureService historicalShipmentClosures =
        mock(HistoricalShipmentRepairClosureService.class);
    RepairCapacitySettingsService settings = settingsFixture();
    EstimateCreationWindowSettingsService creationWindow = creationWindowFixture();
    RepairComplexitySettingsService complexitySettings = complexitySettingsFixture();
    RepairComplexityColorsService colors = colorsFixture();
    RepairPlaceService repairPlaces = repairPlacesFixture();
    PropertyDispositionApplicationService dispositions = dispositionFixture();
    WarehouseOperationMarkRecoveryService operationMarkRecovery =
        warehouseOperationMarkRecoveryFixture();
    FurnitureEquipmentLinkReviewService furnitureLinks = furnitureLinkReviewFixture();
    MaintenanceAuthorizer authorizer = mock(MaintenanceAuthorizer.class);
    when(authorizer.subjectId(null)).thenReturn(ID);
    when(historicalShipmentClosures.close(any(), any(), any()))
        .thenReturn(
            new HistoricalShipmentRepairClosureService.CloseResult(
                new HistoricalShipmentRepairClosureResponse(
                    ID,
                    ID,
                    ID,
                    1L,
                    HistoricalShipmentRentalItemStatus.FREE,
                    List.of(),
                    HistoricalShipmentClosureOutcome.NOT_REQUIRED), true));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new MaintenanceCatalogController(service, authorizer),
            new FurnitureEquipmentLinkController(furnitureLinks, authorizer),
            new MaintenanceEstimateController(service, logistics, authorizer),
            new MaintenanceRepairController(service, authorizer, dispositions),
            new MaintenanceInventoryController(
                inventory, publications, authoritativeOutcomes, service, authorizer),
            new PropertyDispositionController(dispositions, authorizer),
            new PropertyDispositionInventoryController(dispositions, authorizer),
            new WarehouseOperationMarkRecoveryController(operationMarkRecovery, authorizer),
            new MaintenanceLogisticsController(logistics, authorizer),
            new MaintenanceHistoricalShipmentController(historicalShipmentClosures, authorizer),
            new MaintenanceTransferRepairController(service, authorizer),
            new MaintenanceRepairPlaceLogisticsController(
                repairPlaces, service, authorizer),
            new MaintenanceSettingsController(settings, authorizer),
            new EstimateCreationWindowSettingsController(creationWindow, authorizer),
            new RepairComplexitySettingsController(complexitySettings, authorizer),
            new RepairPlaceController(repairPlaces, authorizer),
            new RepairComplexityColorsController(colors, authorizer))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .addFilters(new CorrelationIdFilter())
        .build();
    Map<String, Object> document = openApi();

    for (OperationSpec operation : OPERATIONS) {
      MockHttpServletRequestBuilder request = request(
          HttpMethod.valueOf(operation.httpMethod()), operation.path()
              .replace("{id}", ID.toString())
              .replace("{inventoryId}", ID.toString())
              .replace("{findingId}", ID.toString())
              .replace("{returnId}", ID.toString())
              .replace("{shipmentId}", ID.toString())
              .replace("{transferId}", ID.toString())
              .replace("{repairId}", ID.toString())
              .replace("{decisionId}", ID.toString())
              .replace("{operationId}", ID.toString())
              .replace("{nodeId}", ID.toString())
              .replace("{lineId}", ID.toString())
              .replace("{warehouseId}", ID.toString()));
      for (ParameterSpec parameter : operation.parameters()) {
        String value = valueFor(parameter);
        if ("query".equals(parameter.location())) request.param(parameter.name(), value);
        if ("header".equals(parameter.location())) request.header(parameter.name(), value);
      }
      if (operation.bodyType() != null) {
        request.contentType(MediaType.APPLICATION_JSON)
            .content(MAPPER.writeValueAsBytes(sample(operation.bodyType(), operation.bodyType().getSimpleName())));
      }
      var result = mvc.perform(request)
          .andExpect(status().is(Integer.parseInt(operation.successStatus())))
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(header().exists(CorrelationIdFilter.HEADER_NAME));
      if (operation.idempotent()) {
        result.andExpect(header().string("Idempotency-Replayed", "true"));
      } else {
        result.andExpect(header().doesNotExist("Idempotency-Replayed"));
      }
      JsonNode body = MAPPER.readTree(result.andReturn().getResponse().getContentAsByteArray());
      assertJsonMatchesSchema(
          document,
          body,
          successSchema(openApiOperation(document, operation), operation.successStatus()),
          operation.id());
    }
  }

  @Test
  void allLocalReferencesResolveAndSafetyExtensionsRemainExact() throws Exception {
    Map<String, Object> document = openApi();
    assertAllLocalReferencesResolve(document, document);
    assertThat(child(child(document, "components"), "schemas").toString())
        .contains("RECONCILIATION_REQUIRED", "PENDING_GENERATION", "WRITTEN_OFF");
  }

  static List<OperationSpec> canonicalOperations() {
    List<ParameterSpec> warehousePage = List.of(
        query("warehouseId", true, "uuid", null),
        query("page", false, "integer", "0"),
        query("size", false, "integer", "50"));
    List<ParameterSpec> catalogId = List.of(
        path("id"), query("warehouseId", true, "uuid", null));
    List<ParameterSpec> estimateId = List.of(
        path("id"), query("warehouseId", true, "uuid", null));
    List<ParameterSpec> repairId = List.of(
        path("id"), query("warehouseId", true, "uuid", null));
    List<ParameterSpec> dispositionId = List.of(
        path("decisionId"), query("warehouseId", true, "uuid", null));
    List<ParameterSpec> repairCapacityWarehouse = List.of(path("warehouseId"));
    List<ParameterSpec> idempotency = List.of(requiredHeader("Idempotency-Key"));
    List<OperationSpec> result = new ArrayList<>();
    result.add(op("GET", "/api/maintenance/v1/catalog/versions", "listCatalogVersions",
        MaintenanceCatalogController.class, "versions",
        append(warehousePage,
            query("lifecycle", false, "$CatalogLifecycle", null),
            optionalHeader("If-None-Match")),
        null, null, "200", "CatalogVersionPage", false, "304", "401", "403"));
    result.add(op("PUT", "/api/maintenance/v1/catalog/versions/{id}", "replaceCatalog",
        MaintenanceCatalogController.class, "replaceCatalog", catalogId,
        ChangeCatalogRequest.class, "ReplaceCatalogRequest", "200", "CatalogVersion", false,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("GET", "/api/maintenance/v1/catalog/versions/{id}/nodes", "listCatalogNodes",
        MaintenanceCatalogController.class, "nodes", catalogId,
        null, null, "200", "[CatalogNode]", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/catalog/versions/{id}/nodes", "replaceCatalogNodes",
        MaintenanceCatalogController.class, "replaceNodes", catalogId,
        ReplaceCatalogNodesRequest.class, "ReplaceCatalogNodesRequest", "200", "CatalogVersion", false,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("GET", "/api/maintenance/v1/catalog/versions/{id}/links", "listCatalogLinks",
        MaintenanceCatalogController.class, "links", catalogId,
        null, null, "200", "[CatalogLink]", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/catalog/versions/{id}/links", "replaceCatalogLinks",
        MaintenanceCatalogController.class, "replaceLinks", catalogId,
        ReplaceCatalogLinksRequest.class, "ReplaceCatalogLinksRequest", "200", "CatalogVersion", false,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/catalog/versions", "createCatalogVersion",
        MaintenanceCatalogController.class, "createCatalog", idempotency,
        CreateCatalogRequest.class, "CreateCatalogRequest", "201", "CatalogVersion", true,
        "400", "401", "403", "409"));
    result.add(op("POST", "/api/maintenance/v1/catalog/versions/{id}/fork", "forkCatalogVersion",
        MaintenanceCatalogController.class, "fork", append(catalogId, requiredHeader("Idempotency-Key")),
        VersionCommand.class, "ExpectedVersionRequest", "201", "CatalogVersion", true,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/catalog/versions/{id}/activate", "activateCatalogVersion",
        MaintenanceCatalogController.class, "activate", append(catalogId, requiredHeader("Idempotency-Key")),
        VersionCommand.class, "ExpectedVersionRequest", "200", "CatalogVersion", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("GET", "/api/maintenance/v1/catalog/furniture-equipment-links",
        "listFurnitureEquipmentLinks", FurnitureEquipmentLinkController.class, "list",
        append(warehousePage,
            query("state", false, "$FurnitureEquipmentLinkState", null)),
        null, null, "200", "FurnitureEquipmentLinkPage", false,
        "400", "401", "403"));
    result.add(op("POST",
        "/api/maintenance/v1/catalog/furniture-equipment-links/{nodeId}/review",
        "reviewFurnitureEquipmentLink", FurnitureEquipmentLinkController.class, "review",
        List.of(path("nodeId"), query("warehouseId", true, "uuid", null)),
        FurnitureEquipmentLinkReviewRequest.class, "FurnitureEquipmentLinkReviewRequest",
        "200", "FurnitureEquipmentLink", true,
        "400", "401", "403", "404", "409"));

    result.add(op("POST", "/api/internal/maintenance/v1/inventory/plans",
        "freezeInventoryRepairPlan", MaintenanceInventoryController.class, "freezePlan",
        List.of(), FreezeInventoryPlanRequest.class, "FreezeInventoryPlanRequest",
        "200", "FrozenInventoryPlan", true, "400", "401", "403", "409", "422"));
    result.add(opWithAlternateSuccess(
        "POST",
        "/api/internal/maintenance/v1/inventory/dispositions",
        "createInventoryLossDisposition",
        PropertyDispositionInventoryController.class,
        "createLoss",
        idempotency,
        CreateInventoryLossDispositionRequest.class,
        "CreateInventoryLossDispositionRequest",
        "200",
        "201",
        "PropertyDispositionDecision",
        true,
        "400", "401", "403", "409"));
    result.add(opWithAlternateSuccess(
        "POST",
        "/api/internal/maintenance/v1/inventory/cabin-write-offs",
        "createInventoryCabinWriteOff",
        PropertyDispositionInventoryController.class,
        "createCabinWriteOff",
        idempotency,
        CreateInventoryCabinWriteOffRequest.class,
        "CreateInventoryCabinWriteOffRequest",
        "200",
        "201",
        "PropertyDispositionDecision",
        true,
        "400", "401", "403", "409"));
    result.add(op("POST", "/api/internal/maintenance/v1/inventory/repair-snapshots",
        "getInventoryRepairSnapshots", MaintenanceInventoryController.class, "repairSnapshots",
        List.of(), InventoryRepairSnapshotRequest.class, "InventoryRepairSnapshotRequest",
        "200", "InventoryRepairSnapshots", false, "400", "401", "403"));
    result.add(op("PUT",
        "/api/internal/maintenance/v1/inventory/sources/{inventoryId}/findings/{findingId}",
        "upsertInventoryRepairSource", MaintenanceInventoryController.class, "upsertRepair",
        List.of(path("inventoryId"), path("findingId")),
        UpsertInventoryRepairRequest.class, "UpsertInventoryRepairRequest",
        "200", "InventoryRepairUpsertResult", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("POST", "/api/internal/maintenance/v1/inventory/reconciliations/preflight",
        "preflightInventoryPublicationReconciliation",
        MaintenanceInventoryController.class,
        "preflightPublication",
        List.of(),
        InventoryPublicationPreflightRequest.class,
        "InventoryPublicationPreflightRequest",
        "200",
        "InventoryPublicationPreflightResponse",
        false,
        "400", "401", "403", "409", "422", "503"));
    result.add(op("PUT",
        "/api/internal/maintenance/v1/inventory/reconciliations/{inventoryId}/findings/{findingId}",
        "applyInventoryPublicationReconciliation",
        MaintenanceInventoryController.class,
        "applyPublication",
        List.of(path("inventoryId"), path("findingId"), requiredHeader("Idempotency-Key")),
        InventoryPublicationApplyRequest.class,
        "InventoryPublicationApplyRequest",
        "200",
        "InventoryPublicationApplyResult",
        true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("PUT",
        "/api/internal/maintenance/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/no-work",
        "applyInventoryNoWorkOutcome",
        MaintenanceInventoryController.class,
        "applyNoWorkOutcome",
        List.of(path("inventoryId"), path("findingId"), requiredHeader("Idempotency-Key")),
        InventoryNoWorkOutcomeRequest.class,
        "InventoryNoWorkOutcomeRequest",
        "200",
        "InventoryNoWorkOutcomeResult",
        true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("PUT",
        "/api/internal/maintenance/v1/logistics/returns/{returnId}/lines/{lineId}/estimate-source",
        "upsertLogisticsReturnEstimateSource", MaintenanceLogisticsController.class, "upsert",
        List.of(path("returnId"), path("lineId")),
        UpsertLogisticsReturnEstimateSourceRequest.class,
        "UpsertLogisticsReturnEstimateSourceRequest",
        "200", "ReturnEstimateSource", true,
        "400", "401", "403", "409", "422"));
    result.add(op("GET",
        "/api/internal/maintenance/v1/logistics/returns/{returnId}/lines/{lineId}/estimate-source",
        "getLogisticsReturnEstimateSource", MaintenanceLogisticsController.class, "get",
        List.of(path("returnId"), path("lineId")),
        null, null, "200", "ReturnEstimateSource", false,
        "401", "403", "404"));
    result.add(op("POST",
        "/api/internal/maintenance/v1/logistics/historical-shipments/{shipmentId}/close",
        "closeHistoricalShipmentMaintenance", MaintenanceHistoricalShipmentController.class, "close",
        List.of(path("shipmentId"), requiredHeader("Idempotency-Key")),
        HistoricalShipmentRepairClosureRequest.class, "HistoricalShipmentRepairClosureRequest",
        "200", "HistoricalShipmentRepairClosureResponse", true,
        "400", "401", "403", "409", "503"));
    result.add(op("POST",
        "/api/internal/maintenance/v1/logistics/transfers/{transferId}/lines/{lineId}/prepare-departure",
        "prepareRepairTransferDeparture",
        MaintenanceTransferRepairController.class,
        "prepareDeparture",
        List.of(path("transferId"), path("lineId"), requiredHeader("Idempotency-Key")),
        TransferRepairRequest.class,
        "TransferRepairRequest",
        "200",
        "PrepareTransferRepairResult",
        true,
        "400", "401", "403", "404", "409", "503"));
    result.add(op("POST",
        "/api/internal/maintenance/v1/logistics/transfers/{transferId}/lines/{lineId}/arrival-preflight",
        "preflightRepairTransferArrival",
        MaintenanceTransferRepairController.class,
        "arrivalPreflight",
        List.of(path("transferId"), path("lineId")),
        TransferRepairRequest.class,
        "TransferRepairRequest",
        "200",
        "TransferRepairArrivalPreflight",
        false,
        "400", "401", "403", "404", "409", "503"));
    result.add(op("POST",
        "/api/internal/maintenance/v1/logistics/transfers/{transferId}/lines/{lineId}/complete-arrival",
        "completeRepairTransferArrival",
        MaintenanceTransferRepairController.class,
        "completeArrival",
        List.of(path("transferId"), path("lineId"), requiredHeader("Idempotency-Key")),
        CompleteTransferRepairRequest.class,
        "CompleteTransferRepairRequest",
        "200",
        "CompleteTransferRepairResult",
        true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(
        op(
            "GET",
            "/api/internal/maintenance/v1/logistics/repair-places/{warehouseId}",
            "getLogisticsRepairPlaceProjection",
            MaintenanceRepairPlaceLogisticsController.class,
            "projection",
            repairCapacityWarehouse,
            null,
            null,
            "200",
            "LogisticsRepairPlaceProjection",
            false,
            "401",
            "403"));
    result.add(
        op(
            "GET",
            "/api/internal/maintenance/v1/logistics/repairs/capital",
            "listLogisticsCapitalRepairs",
            MaintenanceRepairPlaceLogisticsController.class,
            "capital",
            warehousePage,
            null,
            null,
            "200",
            "LogisticsCapitalRepairPage",
            false,
            "401",
            "403"));
    result.add(
        op(
            "GET",
            "/api/internal/maintenance/v1/logistics/repairs/capital/{repairId}",
            "getLogisticsCapitalRepair",
            MaintenanceRepairPlaceLogisticsController.class,
            "capitalRepair",
            List.of(path("repairId")),
            null,
            null,
            "200",
            "LogisticsCapitalRepair",
            false,
            "401",
            "403",
            "404"));
    for (String transition : List.of("reserve", "occupy", "ready-to-release", "release")) {
      String operationId =
          switch (transition) {
            case "reserve" -> "reserveRepairPlace";
            case "occupy" -> "occupyRepairPlace";
            case "ready-to-release" -> "markRepairPlaceReadyToRelease";
            case "release" -> "releaseRepairPlace";
            default -> throw new IllegalStateException();
          };
      String methodName =
          switch (transition) {
            case "reserve" -> "reserve";
            case "occupy" -> "occupy";
            case "ready-to-release" -> "readyToRelease";
            case "release" -> "release";
            default -> throw new IllegalStateException();
          };
      result.add(
          op(
              "POST",
              "/api/internal/maintenance/v1/logistics/repair-places/{warehouseId}/allocations/{repairId}/"
                  + transition,
              operationId,
              MaintenanceRepairPlaceLogisticsController.class,
              methodName,
              List.of(
                  path("warehouseId"),
                  path("repairId"),
                  requiredHeader("Idempotency-Key")),
              RepairPlaceTransitionRequest.class,
              "RepairPlaceTransitionRequest",
              "200",
              "LogisticsRepairPlaceAllocation",
              true,
              "400",
              "401",
              "403",
              "404",
              "409"));
    }

    result.add(op("GET", "/api/maintenance/v1/estimates", "listEstimates",
        MaintenanceEstimateController.class, "list",
        append(warehousePage,
            query("lifecycle", false, "$EstimateLifecycle", null),
            query("rentalItemId", false, "uuid", null),
            optionalHeader("If-None-Match")),
        null, null, "200", "EstimatePage", false, "304", "401", "403"));
    result.add(op("POST", "/api/maintenance/v1/estimates", "createEstimate",
        MaintenanceEstimateController.class, "create", idempotency,
        CreateEstimateRequest.class, "CreateEstimateRequest", "201", "Estimate", true,
        "400", "401", "403", "409", "422"));
    result.add(op("GET", "/api/maintenance/v1/estimates/return-sources",
        "listReturnEstimateSources", MaintenanceEstimateController.class, "returnSources",
        List.of(query("warehouseId", true, "uuid", null), query("returnId", true, "uuid", null)),
        null, null, "200", "[ReturnEstimateSource]", false, "401", "403"));
    result.add(op("GET", "/api/maintenance/v1/estimates/{id}", "getEstimate",
        MaintenanceEstimateController.class, "get", estimateId,
        null, null, "200", "Estimate", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/estimates/{id}", "replaceDraftEstimate",
        MaintenanceEstimateController.class, "replace", estimateId,
        UpdateEstimateRequest.class, "ReplaceEstimateRequest", "200", "Estimate", false,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/estimates/{id}/complete", "completeEstimate",
        MaintenanceEstimateController.class, "complete", append(estimateId, requiredHeader("Idempotency-Key")),
        CompleteEstimateRequest.class, "CompleteEstimateRequest", "200", "EstimateCommandResult", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("POST", "/api/maintenance/v1/estimates/{id}/amendments", "amendCompletedEstimate",
        MaintenanceEstimateController.class, "amend", append(estimateId, requiredHeader("Idempotency-Key")),
        AmendEstimateRequest.class, "AmendEstimateRequest", "201", "EstimateCommandResult", true,
        "400", "401", "403", "404", "409", "422", "503"));

    result.add(op("GET", "/api/maintenance/v1/repairs", "listRepairs",
        MaintenanceRepairController.class, "list",
        append(warehousePage,
            query("executionState", false, "$RepairExecutionState", null),
            query("acceptanceState", false, "$RepairAcceptanceState", null),
            query("rentalItemId", false, "uuid", null),
            query("estimateId", false, "uuid", null),
            query("repairIds", false, "array", null),
            optionalHeader("If-None-Match")),
        null, null, "200", "RepairPage", false, "304", "401", "403"));
    result.add(op("GET", "/api/maintenance/v1/repairs/capital", "listActiveCapitalRepairs",
        MaintenanceRepairController.class, "capital", warehousePage,
        null, null, "200", "RepairPage", false, "401", "403"));
    result.add(op("POST", "/api/maintenance/v1/repairs/direct", "createDirectRepair",
        MaintenanceRepairController.class, "direct", idempotency,
        CreateDirectRepairRequest.class, "CreateDirectRepairRequest", "201", "Repair", true,
        "400", "401", "403", "409", "422"));
    result.add(op("GET", "/api/maintenance/v1/repairs/{id}", "getRepair",
        MaintenanceRepairController.class, "get", repairId,
        null, null, "200", "Repair", false, "401", "403", "404"));
    result.add(op("GET", "/api/maintenance/v1/repairs/{id}/worker-evidence",
        "listRepairWorkerEvidence",
        MaintenanceRepairController.class, "workerEvidence", repairId,
        null, null, "200", "[RepairWorkerEvidence]", false, "401", "403", "404"));
    result.add(op("GET", "/api/maintenance/v1/repairs/{id}/rework-candidates",
        "listReworkCandidates",
        MaintenanceRepairController.class, "reworkCandidates", repairId,
        null, null, "200", "ReworkCandidates", false, "401", "403", "404"));
    result.add(op("GET", "/api/maintenance/v1/repairs/{id}/plan", "getRepairPlan",
        MaintenanceRepairController.class, "plan", repairId,
        null, null, "200", "RepairPlan", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/repairs/{id}/plan", "replaceDraftRepairPlan",
        MaintenanceRepairController.class, "replacePlan", repairId,
        UpdateRepairPlanRequest.class, "ReplaceRepairPlanRequest", "200", "Repair", false,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/repairs/{id}/plan", "queueRepairPlan",
        MaintenanceRepairController.class, "queuePlan", append(repairId, requiredHeader("Idempotency-Key")),
        QueueRepairRequest.class, "PriorityVersionRequest", "200", "RepairCommandResult", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op(
        "POST",
        "/api/maintenance/v1/repairs/{id}/inbound-delivery/retry",
        "retryQuarantinedInboundDelivery",
        MaintenanceRepairController.class,
        "retryInboundDelivery",
        append(repairId, requiredHeader("Idempotency-Key")),
        RetryInboundDeliveryRequest.class,
        "RetryInboundDeliveryRequest",
        "200",
        "RepairCommandResult",
        true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("POST", "/api/maintenance/v1/repairs/{id}/reworks", "createRework",
        MaintenanceRepairController.class, "rework", append(repairId, requiredHeader("Idempotency-Key")),
        CreateReworkRequest.class, "CreateReworkRequest", "201", "Repair", true,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/repairs/{id}/accept", "acceptRepair",
        MaintenanceRepairController.class, "accept", append(repairId, requiredHeader("Idempotency-Key")),
        RepairDecisionRequest.class, "RepairDecisionRequest", "200", "RepairCommandResult", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(opWithAlternateSuccess(
        "POST", "/api/maintenance/v1/repairs/{id}/write-off", "writeOffRepair",
        MaintenanceRepairController.class, "writeOff", append(repairId, requiredHeader("Idempotency-Key")),
        WriteOffRepairRequest.class, "WriteOffRepairRequest", "200", "201", "PropertyDispositionDecision", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("GET", "/api/maintenance/v1/acceptance", "listAcceptanceProjection",
        MaintenanceRepairController.class, "acceptance",
        append(
            warehousePage,
            query("state", false, "$RepairAcceptanceState", null),
            query("repairId", false, "uuid", null)),
        null, null, "200", "AcceptanceProjectionPage", false, "401", "403"));
    result.add(op("GET", "/api/maintenance/v1/write-offs", "listWriteOffProjection",
        PropertyDispositionController.class, "writeOffs",
        append(warehousePage, query("state", false, "$PropertyDispositionState", null)),
        null, null, "200", "PropertyDispositionPage", false, "401", "403"));
    result.add(op("GET", "/api/maintenance/v1/losses", "listLossProjection",
        PropertyDispositionController.class, "losses",
        append(warehousePage, query("state", false, "$PropertyDispositionState", null)),
        null, null, "200", "PropertyDispositionPage", false, "401", "403"));
    result.add(opWithAlternateSuccess(
        "POST", "/api/maintenance/v1/dispositions", "createPropertyDisposition",
        PropertyDispositionController.class, "create", idempotency,
        CreatePropertyDispositionRequest.class, "CreatePropertyDispositionRequest",
        "200", "201", "PropertyDispositionDecision", true,
        "400", "401", "403", "404", "409", "503"));
    result.add(op("GET", "/api/maintenance/v1/dispositions/{decisionId}", "getPropertyDisposition",
        PropertyDispositionController.class, "get", dispositionId,
        null, null, "200", "PropertyDispositionDecision", false, "401", "403", "404"));
    result.add(op("POST", "/api/maintenance/v1/dispositions/{decisionId}/approve",
        "approvePropertyDisposition", PropertyDispositionController.class, "approve",
        dispositionId,
        ApprovePropertyDispositionRequest.class, "ApprovePropertyDispositionRequest",
        "200", "PropertyDispositionDecision", false,
        "400", "401", "403", "404", "409", "503"));
    result.add(op("POST", "/api/maintenance/v1/dispositions/{decisionId}/reject",
        "rejectPropertyDisposition", PropertyDispositionController.class, "reject",
        dispositionId,
        RejectPropertyDispositionRequest.class, "RejectPropertyDispositionRequest",
        "200", "PropertyDispositionDecision", false,
        "400", "401", "403", "404", "409"));
    result.add(op("POST", "/api/maintenance/v1/dispositions/{decisionId}/recovery",
        "recoverPropertyDisposition", PropertyDispositionController.class, "recover",
        dispositionId,
        RecoverPropertyDispositionRequest.class, "RecoverPropertyDispositionRequest",
        "200", "PropertyDispositionDecision", false,
        "400", "401", "403", "404", "409", "503"));
    result.add(op("POST", "/api/maintenance/v1/warehouse-operation-marks/{operationId}/recovery",
        "recoverWarehouseOperationMark", WarehouseOperationMarkRecoveryController.class, "recover",
        List.of(path("operationId"), query("warehouseId", true, "uuid", null)),
        WarehouseOperationMarkRecoveryRequest.class, "WarehouseOperationMarkRecoveryRequest",
        "200", "WarehouseOperationMarkRecoveryResponse", true,
        "400", "401", "403", "404", "409"));
    result.add(op("GET",
        "/api/maintenance/v1/settings/repair-capacity/{warehouseId}",
        "getRepairCapacitySettings", MaintenanceSettingsController.class, "get",
        repairCapacityWarehouse, null, null, "200", "RepairCapacitySettings", false,
        "401", "403"));
    result.add(op("PUT",
        "/api/maintenance/v1/settings/repair-capacity/{warehouseId}",
        "replaceRepairCapacitySettings", MaintenanceSettingsController.class, "replace",
        repairCapacityWarehouse,
        ReplaceRepairCapacitySettingsRequest.class, "ReplaceRepairCapacitySettingsRequest",
        "200", "RepairCapacitySettings", false,
        "400", "401", "403", "409"));
    result.add(op("GET",
        "/api/maintenance/v1/settings/estimate-creation-window/{warehouseId}",
        "getEstimateCreationWindowSettings", EstimateCreationWindowSettingsController.class, "get",
        repairCapacityWarehouse, null, null, "200", "EstimateCreationWindowSettings", false,
        "401", "403"));
    result.add(op("PUT",
        "/api/maintenance/v1/settings/estimate-creation-window/{warehouseId}",
        "replaceEstimateCreationWindowSettings",
        EstimateCreationWindowSettingsController.class, "replace",
        repairCapacityWarehouse,
        ReplaceEstimateCreationWindowSettingsRequest.class,
        "ReplaceEstimateCreationWindowSettingsRequest",
        "200", "EstimateCreationWindowSettings", false,
        "400", "401", "403", "409"));
    result.add(op("GET",
        "/api/maintenance/v1/settings/repair-complexity/{warehouseId}",
        "getRepairComplexitySettings", RepairComplexitySettingsController.class, "get",
        repairCapacityWarehouse, null, null, "200", "RepairComplexitySettings", false,
        "401", "403"));
    result.add(op("PUT",
        "/api/maintenance/v1/settings/repair-complexity/{warehouseId}",
        "replaceRepairComplexitySettings", RepairComplexitySettingsController.class, "replace",
        repairCapacityWarehouse,
        ReplaceRepairComplexitySettingsRequest.class,
        "ReplaceRepairComplexitySettingsRequest",
        "200", "RepairComplexitySettings", false,
        "400", "401", "403", "409"));
    result.add(op("POST",
        "/api/maintenance/v1/settings/repair-complexity/{warehouseId}/task-board-import",
        "importRepairComplexitySettings", RepairComplexitySettingsController.class, "importOnce",
        repairCapacityWarehouse,
        ImportRepairComplexitySettingsRequest.class,
        "ImportRepairComplexitySettingsRequest",
        "200", "RepairComplexitySettings", false,
        "400", "401", "403", "409"));
    result.add(op("GET",
        "/api/maintenance/v1/repair-places/{warehouseId}",
        "getRepairPlaceProjection", RepairPlaceController.class, "projection",
        repairCapacityWarehouse, null, null, "200", "RepairPlaceProjection", false,
        "401", "403"));
    result.add(op("GET",
        "/api/maintenance/v1/settings/repair-complexity-colors",
        "getRepairComplexityColors", RepairComplexityColorsController.class, "get",
        List.of(query("warehouseId", true, "uuid", null)),
        null, null, "200", "RepairComplexityColors", false,
        "401", "403"));
    result.add(op("PUT",
        "/api/maintenance/v1/settings/repair-complexity-colors",
        "replaceRepairComplexityColors", RepairComplexityColorsController.class, "replace",
        List.of(query("warehouseId", true, "uuid", null)),
        ReplaceRepairComplexityColorsRequest.class, "ReplaceRepairComplexityColorsRequest",
        "200", "RepairComplexityColors", false,
        "400", "401", "403", "409"));
    return List.copyOf(result);
  }

  private static void assertOpenApiOperation(
      Map<String, Object> document, OperationSpec expected) {
    Map<String, Object> operation = openApiOperation(document, expected);
    assertThat(operation.get("operationId")).as(expected.id()).isEqualTo(expected.operationId());
    assertThat(openApiParameters(document, expected.path(), operation))
        .as(expected.id() + " parameters")
        .containsExactlyElementsOf(expected.parameters());

    Object body = operation.get("requestBody");
    if (expected.bodySchema() == null) {
      assertThat(body).as(expected.id()).isNull();
    } else {
      Map<String, Object> requestBody = map(body);
      assertThat(requestBody.get("required")).as(expected.id()).isEqualTo(true);
      assertThat(child(requestBody, "content")).containsOnlyKeys(JSON);
      assertThat(schemaDescriptor(child(child(requestBody, "content"), JSON).get("schema")))
          .isEqualTo(expected.bodySchema());
    }

    Map<String, Object> responses = child(operation, "responses");
    Map<String, Object> success = child(responses, expected.successStatus());
    assertThat(child(success, "content")).as(expected.id()).containsOnlyKeys(JSON);
    assertThat(schemaDescriptor(child(child(success, "content"), JSON).get("schema")))
        .isEqualTo(expected.responseSchema());
  }

  private static void assertControllerOperation(OperationSpec expected) {
    Method method = Arrays.stream(expected.controller().getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(expected.controllerMethod()))
        .findFirst()
        .orElseThrow();
    RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(
        expected.controller(), RequestMapping.class);
    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    assertThat(mapping).isNotNull();
    assertThat(mapping.method()).hasSize(1);
    assertThat(mapping.method()[0].name()).isEqualTo(expected.httpMethod());
    assertThat(normalize(firstPath(root) + "/" + firstPath(mapping))).isEqualTo(expected.path());
    assertThat(controllerParameters(method))
        .as(expected.id() + " controller binding")
        .containsExactlyElementsOf(expected.parameters());
    assertControllerBody(method, expected);
    assertThat(controllerResponseSchema(method.getGenericReturnType()))
        .as(expected.id() + " response wrapper")
        .isEqualTo(expected.responseSchema());
  }

  private static void assertControllerBody(Method method, OperationSpec expected) {
    List<Parameter> bodies = Arrays.stream(method.getParameters())
        .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
        .toList();
    if (expected.bodyType() == null) {
      assertThat(bodies).as(expected.id()).isEmpty();
      return;
    }
    assertThat(bodies).as(expected.id()).hasSize(1);
    Parameter body = bodies.getFirst();
    assertThat(body.getType()).isEqualTo(expected.bodyType());
    assertThat(body.isAnnotationPresent(Valid.class)).isTrue();
    assertThat(body.getAnnotation(RequestBody.class).required()).isTrue();
  }

  private static List<ParameterSpec> controllerParameters(Method method) {
    List<ParameterSpec> result = new ArrayList<>();
    for (Parameter parameter : method.getParameters()) {
      if (parameter.getType() == Jwt.class
          && parameter.isAnnotationPresent(AuthenticationPrincipal.class)) continue;
      if (parameter.isAnnotationPresent(RequestBody.class)) continue;
      PathVariable path = parameter.getAnnotation(PathVariable.class);
      RequestParam query = parameter.getAnnotation(RequestParam.class);
      RequestHeader header = parameter.getAnnotation(RequestHeader.class);
      if (path != null) {
        result.add(new ParameterSpec(annotationName(path.name(), path.value(), parameter),
            "path", path.required(), javaWireType(parameter.getType()), null));
      } else if (query != null) {
        String defaultValue = defaultValue(query.defaultValue());
        result.add(new ParameterSpec(annotationName(query.name(), query.value(), parameter),
            "query", query.required() && defaultValue == null,
            javaWireType(parameter.getType()), defaultValue));
      } else if (header != null) {
        String defaultValue = defaultValue(header.defaultValue());
        result.add(new ParameterSpec(annotationName(header.name(), header.value(), parameter),
            "header", header.required() && defaultValue == null,
            javaWireType(parameter.getType()), defaultValue));
      } else {
        throw new AssertionError("Unclassified controller parameter: " + parameter);
      }
    }
    return result;
  }

  private static String annotationName(String name, String value, Parameter parameter) {
    String declared = name.isBlank() ? value : name;
    return declared.isBlank() ? parameter.getName() : declared;
  }

  private static String defaultValue(String value) {
    return ValueConstants.DEFAULT_NONE.equals(value) ? null : value;
  }

  private static String javaWireType(Class<?> type) {
    if (type == UUID.class) return "uuid";
    if (type == String.class) return "string";
    if (Set.class.isAssignableFrom(type)) return "array";
    if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
      return "integer";
    }
    String schema = SCHEMAS.get(type);
    if (schema != null) return "$" + schema;
    throw new AssertionError("No wire parameter type for " + type.getName());
  }

  private static List<ParameterSpec> openApiParameters(
      Map<String, Object> document, String path, Map<String, Object> operation) {
    List<Object> declared = new ArrayList<>();
    Object pathParameters = child(child(document, "paths"), path).get("parameters");
    if (pathParameters instanceof List<?> values) declared.addAll(values);
    Object operationParameters = operation.get("parameters");
    if (operationParameters instanceof List<?> values) declared.addAll(values);
    return declared.stream().map(value -> {
      Map<String, Object> parameter = resolve(document, map(value));
      Map<String, Object> parameterSchema = map(parameter.get("schema"));
      Object defaultValue = parameterSchema.get("default");
      return new ParameterSpec(
          String.valueOf(parameter.get("name")),
          String.valueOf(parameter.get("in")),
          Boolean.TRUE.equals(parameter.get("required")),
          openApiWireType(parameterSchema),
          defaultValue == null ? null : String.valueOf(defaultValue));
    }).toList();
  }

  private static String openApiWireType(Map<String, Object> schema) {
    Object ref = schema.get("$ref");
    if (ref != null) return "$" + String.valueOf(ref).substring(String.valueOf(ref).lastIndexOf('/') + 1);
    if ("string".equals(schema.get("type")) && "uuid".equals(schema.get("format"))) return "uuid";
    return String.valueOf(schema.get("type"));
  }

  private static String controllerResponseSchema(Type declared) {
    if (declared instanceof ParameterizedType parameterized) {
      Class<?> raw = (Class<?>) parameterized.getRawType();
      Type argument = parameterized.getActualTypeArguments()[0];
      if (raw == ResponseEntity.class) return controllerResponseSchema(argument);
      if (raw == List.class) return "[" + controllerResponseSchema(argument) + "]";
      if (raw == PageResponse.class) return pageSchema((Class<?>) argument);
    }
    if (declared instanceof Class<?> type) {
      String schema = SCHEMAS.get(type);
      if (schema != null) return schema;
    }
    throw new AssertionError("No response schema for " + declared);
  }

  private static String pageSchema(Class<?> itemType) {
    if (itemType == CatalogVersionResponse.class) return "CatalogVersionPage";
    if (itemType == EstimateResponse.class) return "EstimatePage";
    if (itemType == RepairResponse.class) return "RepairPage";
    if (itemType == LogisticsCapitalRepairResponse.class) {
      return "LogisticsCapitalRepairPage";
    }
    if (itemType == AcceptanceProjection.class) return "AcceptanceProjectionPage";
    if (itemType == FurnitureEquipmentLinkResponse.class) {
      return "FurnitureEquipmentLinkPage";
    }
    throw new AssertionError("No page schema for " + itemType.getName());
  }

  private static void assertProblemResponse(
      Map<String, Object> document,
      String operationId,
      Map<String, Object> responses,
      String statusCode,
      String responseName) {
    Map<String, Object> declared = child(responses, statusCode);
    if (responseName != null) {
      assertThat(declared.get("$ref"))
          .as(operationId + " " + statusCode)
          .isEqualTo("#/components/responses/" + responseName);
    }
    Map<String, Object> response = resolve(document, declared);
    assertThat(child(response, "content")).containsOnlyKeys(PROBLEM_JSON);
    assertThat(schemaDescriptor(child(child(response, "content"), PROBLEM_JSON).get("schema")))
        .isEqualTo("ProblemDetail");
  }

  private static void assertNotModifiedResponse(
      Map<String, Object> document,
      String operationId,
      Map<String, Object> responses) {
    Map<String, Object> response = child(responses, "304");
    assertThat(response).as(operationId + " 304").doesNotContainKey("content");
    assertResponseHeader(response, "X-Correlation-Id", "#/components/headers/X-Correlation-Id");
    assertResponseHeader(response, "ETag", "#/components/headers/ETag");
  }

  private static void assertResponseHeader(
      Map<String, Object> response, String headerName, String reference) {
    assertThat(child(response, "headers").get(headerName))
        .as(headerName)
        .isEqualTo(Map.of("$ref", reference));
  }

  private static void assertRecordProperties(Class<?> recordType, Map<String, Object> schema) {
    Set<String> recordProperties = Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    assertThat(child(schema, "properties").keySet())
        .as(recordType.getSimpleName())
        .containsExactlyInAnyOrderElementsOf(recordProperties);
    assertThat(schema.get("additionalProperties")).isEqualTo(false);
    Set<String> required = new LinkedHashSet<>(stringList(schema.get("required")));
    Set<String> requiredByJava = new LinkedHashSet<>();
    for (RecordComponent component : recordType.getRecordComponents()) {
      if (component.getType().isPrimitive() || hasRequiredValidation(component)) {
        requiredByJava.add(component.getName());
      }
    }
    assertThat(required)
        .as(recordType.getSimpleName() + " required properties")
        .containsAll(requiredByJava);
  }

  private static boolean hasRequiredValidation(RecordComponent component) {
    return Arrays.stream(component.getAnnotations())
        .map(annotation -> annotation.annotationType().getSimpleName())
        .anyMatch(name -> Set.of("NotNull", "NotBlank", "NotEmpty").contains(name));
  }

  private static void assertNullablePropertiesRoundTrip(
      Map<String, Object> document, Class<?> recordType, String schemaName) throws Exception {
    Map<String, Object> properties = child(schema(document, schemaName), "properties");
    RecordComponent[] components = recordType.getRecordComponents();
    for (int index = 0; index < components.length; index++) {
      RecordComponent component = components[index];
      if (component.getType().isPrimitive()) continue;
      if (!allowsNull(document, child(properties, component.getName()))) continue;
      Object[] arguments = sampleArguments(recordType);
      arguments[index] = null;
      Object nullable = canonicalConstructor(recordType).newInstance(arguments);
      JsonNode serialized = MAPPER.valueToTree(nullable);
      assertThat(serialized.has(component.getName())).isTrue();
      assertThat(serialized.get(component.getName()).isNull()).isTrue();
      assertJsonMatchesSchema(document, serialized, schema(document, schemaName), schemaName);
    }
  }

  private static boolean allowsNull(Map<String, Object> document, Map<String, Object> schema) {
    Map<String, Object> resolved = resolve(document, schema);
    if ("null".equals(resolved.get("type"))) return true;
    Object oneOf = resolved.get("oneOf");
    return oneOf instanceof List<?> values
        && values.stream().map(MaintenanceOpenApiParityTest::map)
            .map(value -> resolve(document, value))
            .anyMatch(value -> "null".equals(value.get("type")));
  }

  private static void assertPage(
      Map<String, Object> document, String schemaName, Class<?> itemType) throws Exception {
    Object item = sample(itemType, itemType.getSimpleName());
    JsonNode node = MAPPER.valueToTree(new PageResponse<>(List.of(item), 0, 50, 1));
    assertJsonMatchesSchema(document, node, schema(document, schemaName), schemaName);
  }

  private static void assertEnum(
      Map<String, Object> document, Class<? extends Enum<?>> type, String schemaName) throws Exception {
    List<String> serialized = new ArrayList<>();
    for (Enum<?> value : type.getEnumConstants()) {
      serialized.add(MAPPER.readTree(MAPPER.writeValueAsBytes(value)).stringValue());
    }
    assertThat(serialized).containsExactlyElementsOf(enumValues(schema(document, schemaName)));
  }

  private static void assertPropertyEnum(
      Map<String, Object> document,
      Class<? extends Enum<?>> type,
      String schemaName,
      String property) throws Exception {
    Map<String, Object> propertySchema = child(child(schema(document, schemaName), "properties"), property);
    List<String> serialized = new ArrayList<>();
    for (Enum<?> value : type.getEnumConstants()) {
      serialized.add(MAPPER.readTree(MAPPER.writeValueAsBytes(value)).stringValue());
    }
    assertThat(serialized).containsExactlyElementsOf(enumValues(propertySchema));
  }

  private static void assertJsonMatchesSchema(
      Map<String, Object> document, JsonNode node, Map<String, Object> declared, String path) {
    Map<String, Object> schema = resolve(document, declared);
    Object oneOf = schema.get("oneOf");
    if (oneOf instanceof List<?> alternatives) {
      List<Map<String, Object>> candidates = alternatives.stream()
          .map(MaintenanceOpenApiParityTest::map)
          .map(candidate -> resolve(document, candidate))
          .filter(candidate -> node.isNull() == "null".equals(candidate.get("type")))
          .toList();
      if (candidates.size() > 1 && node.isObject()) {
        candidates = candidates.stream()
            .filter(candidate -> matchesOneOfDiscriminators(document, node, candidate))
            .toList();
      }
      assertThat(candidates).as(path + " oneOf").hasSize(1);
      if (schema.containsKey("type")) {
        Map<String, Object> base = new LinkedHashMap<>(schema);
        base.remove("oneOf");
        assertJsonMatchesSchema(document, node, base, path);
      }
      assertJsonMatchesSchema(document, node, candidates.getFirst(), path);
      return;
    }
    if (schema.containsKey("const")) {
      assertThat(node).as(path).isEqualTo(MAPPER.valueToTree(schema.get("const")));
      if (!schema.containsKey("type")) return;
    }
    Object enumDeclaration = schema.get("enum");
    if (enumDeclaration instanceof List<?> values) {
      assertThat(node.isTextual()).as(path).isTrue();
      assertThat(stringList(values)).as(path).contains(node.stringValue());
      return;
    }
    String type = String.valueOf(schema.get("type"));
    switch (type) {
      case "object" -> {
        assertThat(node.isObject()).as(path).isTrue();
        Map<String, Object> properties = child(schema, "properties");
        Set<String> actual = new LinkedHashSet<>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
          assertThat(actual).as(path).containsOnlyElementsOf(properties.keySet());
        }
        assertThat(actual).as(path + " required")
            .containsAll(stringList(schema.get("required")));
        boolean closedShape = schema.containsKey("additionalProperties") || schema.containsKey("required");
        Iterable<Map.Entry<String, JsonNode>> propertiesToValidate = closedShape
            ? node.properties()::iterator
            : properties.entrySet().stream()
                .filter(entry -> node.has(entry.getKey()))
                .map(entry -> Map.entry(entry.getKey(), node.get(entry.getKey())))
                .toList();
        propertiesToValidate.forEach(entry -> {
          Map<String, Object> propertySchema = map(properties.get(entry.getKey()));
          assertThat(propertySchema)
              .as(path + " missing schema property " + entry.getKey())
              .isNotNull();
          assertJsonMatchesSchema(document, entry.getValue(), propertySchema, path + "." + entry.getKey());
        });
      }
      case "array" -> {
        assertThat(node.isArray()).as(path).isTrue();
        Map<String, Object> items = map(schema.get("items"));
        for (int index = 0; index < node.size(); index++) {
          assertJsonMatchesSchema(document, node.get(index), items, path + "[" + index + "]");
        }
      }
      case "string" -> {
        assertThat(node.isTextual()).as(path).isTrue();
        validateStringFormat(node.stringValue(), schema, path);
      }
      case "integer" -> assertThat(node.isIntegralNumber()).as(path).isTrue();
      case "boolean" -> assertThat(node.isBoolean()).as(path).isTrue();
      case "null" -> assertThat(node.isNull()).as(path).isTrue();
      default -> throw new AssertionError(path + " has unsupported schema type " + schema);
    }
  }

  private static boolean matchesOneOfDiscriminators(
      Map<String, Object> document, JsonNode node, Map<String, Object> candidate) {
    Object properties = candidate.get("properties");
    if (!(properties instanceof Map<?, ?>)) return true;
    for (Map.Entry<String, Object> entry : map(properties).entrySet()) {
      Map<String, Object> property = resolve(document, map(entry.getValue()));
      JsonNode value = node.get(entry.getKey());
      if (property.containsKey("const")
          && (value == null || !MAPPER.valueToTree(property.get("const")).equals(value))) {
        return false;
      }
      List<String> values = stringList(property.get("enum"));
      if (!values.isEmpty()
          && (value == null || !value.isTextual() || !values.contains(value.stringValue()))) {
        return false;
      }
    }
    return true;
  }

  private static void validateStringFormat(
      String value, Map<String, Object> schema, String path) {
    String format = (String) schema.get("format");
    if ("uuid".equals(format)) UUID.fromString(value);
    if ("date".equals(format)) LocalDate.parse(value);
    if ("date-time".equals(format)) OffsetDateTime.parse(value);
    Object pattern = schema.get("pattern");
    if (pattern != null) assertThat(Pattern.matches(String.valueOf(pattern), value)).as(path).isTrue();
  }

  private static MaintenanceApplicationService serviceFixture() {
    return mock(MaintenanceApplicationService.class, invocation -> switch (invocation.getMethod().getName()) {
      case "catalogVersions" -> List.of(sample(CatalogVersionResponse.class, "catalogVersion"));
      case "catalogVersion", "changeCatalog", "replaceCatalogNodes", "replaceCatalogLinks" ->
          sample(CatalogVersionResponse.class, "catalogVersion");
      case "catalogNodes" -> List.of(sample(CatalogNodeResponse.class, "catalogNode"));
      case "catalogLinks" -> List.of(sample(CatalogLinkResponse.class, "catalogLink"));
      case "createCatalog", "forkCatalog", "activateCatalog" ->
          createResult(CatalogVersionResponse.class);
      case "estimates" -> List.of(sample(EstimateResponse.class, "estimate"));
      case "estimate", "updateEstimate" -> sample(EstimateResponse.class, "estimate");
      case "createEstimate" -> createResult(EstimateResponse.class);
      case "completeEstimate", "amendEstimate" -> createResult(EstimateCommandResult.class);
      case "repairs" ->
          new PageResponse<>(List.of(sample(RepairResponse.class, "repair")), 0, 50, 1);
      case "activeCapitalRepairs" -> List.of(sample(RepairResponse.class, "repair"));
      case "repair", "activeCapitalRepair", "updateRepairPlan" ->
          sample(RepairResponse.class, "repair");
      case "repairWorkerEvidence" ->
          List.of(sample(RepairWorkerEvidenceResponse.class, "repairWorkerEvidence"));
      case "reworkCandidates" ->
          sample(ReworkCandidatesResponse.class, "reworkCandidates");
      case "repairPlan" -> sample(RepairPlanResponse.class, "repairPlan");
      case "createDirectRepair", "createRework" -> createResult(RepairResponse.class);
      case "queueRepair", "retryInboundDelivery", "accept", "writeOff" ->
          createResult(RepairCommandResult.class);
      case "prepareTransferDeparture" -> createResult(PrepareTransferRepairResponse.class);
      case "transferArrivalPreflight" ->
          sample(TransferRepairArrivalPreflightResponse.class, "transferArrivalPreflight");
      case "completeTransferArrival" -> createResult(CompleteTransferRepairResponse.class);
      case "activateQueuedRepairAfterDelivery" -> null;
      case "acceptance" ->
          new PageResponse<>(
              List.of(sample(AcceptanceProjection.class, "acceptance")), 0, 50, 1);
      default -> invocation.callRealMethod();
    });
  }

  private static InventoryMaintenanceService inventoryFixture() throws Exception {
    InventoryMaintenanceService inventory = mock(InventoryMaintenanceService.class);
    FrozenInventoryPlanResponse frozen = (FrozenInventoryPlanResponse) sample(
        FrozenInventoryPlanResponse.class, "frozenInventoryPlan");
    InventorySourceReference source = (InventorySourceReference) sample(
        InventorySourceReference.class, "inventorySource");
    DeliverySnapshot delivery = (DeliverySnapshot) sample(DeliverySnapshot.class, "delivery");
    when(inventory.freeze(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new InventoryMaintenanceService.FreezeResult(frozen, true));
    when(inventory.repairSnapshots(org.mockito.ArgumentMatchers.any()))
        .thenReturn((InventoryRepairSnapshotsResponse) sample(
            InventoryRepairSnapshotsResponse.class, "inventoryRepairSnapshots"));
    when(inventory.upsert(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(new InventoryMaintenanceService.UpsertResult(ID, source, delivery, true));
    return inventory;
  }

  private static PropertyDispositionApplicationService dispositionFixture() throws Exception {
    PropertyDispositionApplicationService service = mock(PropertyDispositionApplicationService.class);
    PropertyDispositionDecisionResponse decision =
        (PropertyDispositionDecisionResponse)
            sample(PropertyDispositionDecisionResponse.class, "propertyDispositionDecision");
    PropertyDispositionPage page =
        (PropertyDispositionPage) sample(PropertyDispositionPage.class, "propertyDispositionPage");
    CreateResult created = new CreateResult(decision, true);
    when(service.createManual(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(created);
    when(service.createRepairWriteOff(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(created);
    when(service.createInventoryLoss(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(created);
    when(service.createInventoryCabinWriteOff(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(created);
    when(service.get(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(decision);
    when(service.approve(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(decision);
    when(service.reject(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(decision);
    when(service.recover(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(decision);
    when(service.list(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(page);
    return service;
  }

  private static WarehouseOperationMarkRecoveryService warehouseOperationMarkRecoveryFixture() {
    WarehouseOperationMarkRecoveryService service =
        mock(WarehouseOperationMarkRecoveryService.class);
    when(service.recover(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(new WarehouseOperationMarkStore.RecoveryResult(
            ID,
            ID,
            "PENDING",
            0,
            1,
            "HTTP_503",
            ID,
            "Reviewed recovery",
            OffsetDateTime.parse("2026-08-05T00:00:00Z"),
            true));
    return service;
  }

  private static FurnitureEquipmentLinkReviewService furnitureLinkReviewFixture()
      throws Exception {
    FurnitureEquipmentLinkReviewService service = mock(FurnitureEquipmentLinkReviewService.class);
    FurnitureEquipmentLinkResponse response = (FurnitureEquipmentLinkResponse) sample(
        FurnitureEquipmentLinkResponse.class, "furnitureEquipmentLink");
    when(service.list(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(new PageResponse<>(List.of(response), 0, 50, 1));
    when(service.review(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(new FurnitureEquipmentLinkReviewService.ReviewedLink(response, true));
    return service;
  }

  private static InventoryPublicationReconciliationService publicationsFixture() throws Exception {
    InventoryPublicationReconciliationService publications =
        mock(InventoryPublicationReconciliationService.class);
    InventoryPublicationPreflightResponse preflight =
        (InventoryPublicationPreflightResponse)
            sample(
                InventoryPublicationPreflightResponse.class,
                "inventoryPublicationPreflightResponse");
    InventoryPublicationApplyResult apply =
        (InventoryPublicationApplyResult)
            sample(InventoryPublicationApplyResult.class, "inventoryPublicationApplyResult");
    when(publications.preflight(org.mockito.ArgumentMatchers.any())).thenReturn(preflight);
    when(
            publications.apply(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(new InventoryPublicationReconciliationService.PublicationResult(apply, true));
    return publications;
  }

  private static InventoryAuthoritativeOutcomeService authoritativeOutcomesFixture()
      throws Exception {
    InventoryAuthoritativeOutcomeService authoritativeOutcomes =
        mock(InventoryAuthoritativeOutcomeService.class);
    InventoryNoWorkOutcomeResult response =
        (InventoryNoWorkOutcomeResult)
            sample(InventoryNoWorkOutcomeResult.class, "inventoryNoWorkOutcomeResult");
    when(
            authoritativeOutcomes.applyNoWork(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);
    return authoritativeOutcomes;
  }

  private static LogisticsReturnShortageService logisticsFixture() throws Exception {
    LogisticsReturnShortageService logistics = mock(LogisticsReturnShortageService.class);
    ReturnEstimateSource response =
        (ReturnEstimateSource) sample(ReturnEstimateSource.class, "returnEstimateSource");
    when(logistics.upsert(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(new LogisticsReturnShortageService.UpsertResult(response, true));
    when(logistics.get(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);
    when(logistics.list(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(response));
    return logistics;
  }

  private static RepairCapacitySettingsService settingsFixture() throws Exception {
    RepairCapacitySettingsService settings = mock(RepairCapacitySettingsService.class);
    RepairCapacitySettingsResponse response = (RepairCapacitySettingsResponse) sample(
        RepairCapacitySettingsResponse.class, "repairCapacitySettings");
    when(settings.get(org.mockito.ArgumentMatchers.any())).thenReturn(response);
    when(settings.replace(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);
    return settings;
  }

  private static EstimateCreationWindowSettingsService creationWindowFixture()
      throws Exception {
    EstimateCreationWindowSettingsService settings =
        mock(EstimateCreationWindowSettingsService.class);
    EstimateCreationWindowSettingsResponse response =
        (EstimateCreationWindowSettingsResponse)
            sample(EstimateCreationWindowSettingsResponse.class, "estimateCreationWindowSettings");
    when(settings.get(org.mockito.ArgumentMatchers.any())).thenReturn(response);
    when(settings.replace(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);
    return settings;
  }

  private static RepairComplexityColorsService colorsFixture() throws Exception {
    RepairComplexityColorsService colors = mock(RepairComplexityColorsService.class);
    RepairComplexityColorsResponse response = (RepairComplexityColorsResponse) sample(
        RepairComplexityColorsResponse.class, "repairComplexityColors");
    when(colors.get()).thenReturn(response);
    when(colors.replace(org.mockito.ArgumentMatchers.any())).thenReturn(response);
    return colors;
  }

  private static RepairComplexitySettingsService complexitySettingsFixture() throws Exception {
    RepairComplexitySettingsService settings = mock(RepairComplexitySettingsService.class);
    RepairComplexitySettingsResponse response =
        (RepairComplexitySettingsResponse)
            sample(RepairComplexitySettingsResponse.class, "repairComplexitySettings");
    when(settings.get(org.mockito.ArgumentMatchers.any())).thenReturn(response);
    when(settings.replace(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);
    when(settings.importOnce(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);
    return settings;
  }

  private static RepairPlaceService repairPlacesFixture() throws Exception {
    RepairPlaceService service = mock(RepairPlaceService.class);
    RepairPlaceProjectionResponse projection =
        (RepairPlaceProjectionResponse)
            sample(RepairPlaceProjectionResponse.class, "repairPlaceProjection");
    LogisticsRepairPlaceProjectionResponse logisticsProjection =
        (LogisticsRepairPlaceProjectionResponse)
            sample(
                LogisticsRepairPlaceProjectionResponse.class,
                "logisticsRepairPlaceProjection");
    LogisticsRepairPlaceAllocationResponse allocation =
        (LogisticsRepairPlaceAllocationResponse)
            sample(
                LogisticsRepairPlaceAllocationResponse.class,
                "logisticsRepairPlaceAllocation");
    when(service.projection(org.mockito.ArgumentMatchers.any())).thenReturn(projection);
    when(service.logisticsProjection(org.mockito.ArgumentMatchers.any()))
        .thenReturn(logisticsProjection);
    when(service.reserve(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RepairPlaceService.TransitionResult(allocation, true));
    when(service.occupy(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RepairPlaceService.TransitionResult(allocation, true));
    when(service.readyToRelease(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RepairPlaceService.TransitionResult(allocation, true));
    when(service.release(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RepairPlaceService.TransitionResult(allocation, true));
    return service;
  }

  private static MaintenanceApplicationService.CreateResult<?> createResult(Class<?> type)
      throws Exception {
    return new MaintenanceApplicationService.CreateResult<>(sample(type, type.getSimpleName()), true);
  }

  private static Object sample(Type type, String name) throws Exception {
    if (type instanceof ParameterizedType parameterized) {
      if (parameterized.getRawType() == List.class) {
        return List.of(sample(parameterized.getActualTypeArguments()[0], name));
      }
      type = parameterized.getRawType();
    }
    Class<?> raw = (Class<?>) type;
    if (raw == UUID.class) return ID;
    if (raw == JsonNode.class) {
      return MAPPER.valueToTree(sample(FrozenInventoryPlanSnapshot.class, "publicationSnapshot"));
    }
    if (raw == String.class) return stringSample(name);
    if (raw == boolean.class || raw == Boolean.class) return true;
    if (raw == int.class || raw == Integer.class) return 1;
    if (raw == long.class || raw == Long.class) return 1L;
    if (raw == LocalDate.class) return LocalDate.of(2026, 7, 16);
    if (raw == Instant.class) return Instant.parse("2026-07-16T12:00:00Z");
    if (raw == OffsetDateTime.class) return OffsetDateTime.parse("2026-07-16T12:00:00Z");
    if (raw.isEnum()) return raw.getEnumConstants()[0];
    if (raw == ReworkLineInput.class) {
      return sample(AddedReworkLineInput.class, name);
    }
    if (raw == CreatePropertyDispositionRequest.class) {
      return sample(CreateCabinPropertyDispositionRequest.class, name);
    }
    if (raw.isRecord()) return canonicalConstructor(raw).newInstance(sampleArguments(raw));
    throw new AssertionError("No JSON fixture for " + type);
  }

  private static Object[] sampleArguments(Class<?> recordType) throws Exception {
    RecordComponent[] components = recordType.getRecordComponents();
    Object[] arguments = new Object[components.length];
    for (int index = 0; index < components.length; index++) {
      arguments[index] = sample(components[index].getGenericType(), components[index].getName());
      if (recordType == InventoryPlanLineSnapshot.class
          && "quantity".equals(components[index].getName())) {
        arguments[index] = "2.500000";
      }
      if ((recordType == TransferRepairRequest.class
              || recordType == CompleteTransferRepairRequest.class)
          && "targetWarehouseId".equals(components[index].getName())) {
        arguments[index] = UUID.fromString("20000000-0000-0000-0000-000000000006");
      }
      if (recordType == CatalogNodeSnapshot.class) {
        if ("nodeType".equals(components[index].getName())) {
          arguments[index] = CatalogNodeType.WORK;
        }
        if ("characteristic".equals(components[index].getName())) {
          arguments[index] = null;
        }
      }
      if (recordType == CreateCabinPropertyDispositionRequest.class
          && "assetKind".equals(components[index].getName())) {
        arguments[index] = PropertyDispositionAssetKind.CABIN;
      }
      if (recordType == CreateEquipmentPropertyDispositionRequest.class
          && "assetKind".equals(components[index].getName())) {
        arguments[index] = PropertyDispositionAssetKind.EQUIPMENT;
      }
      if (recordType == PrepareTransferRepairResponse.class
          && "assetStatus".equals(components[index].getName())) {
        arguments[index] = "FREE";
      }
      if (recordType == InventoryNoWorkOutcomeRequest.class
          && "desiredStatus".equals(components[index].getName())) {
        arguments[index] = "FREE";
      }
      if (recordType == WarehouseOperationMarkRecoveryResponse.class
          && "state".equals(components[index].getName())) {
        arguments[index] = "PENDING";
      }
      if (recordType == RepairComplexitySnapshot.class) {
        if ("name".equals(components[index].getName())) {
          arguments[index] = "Лёгкий ремонт";
        }
        if ("color".equals(components[index].getName())) {
          arguments[index] = "#336699";
        }
        if ("plannedMinutes".equals(components[index].getName())) {
          arguments[index] = "45.000";
        }
      }
      if (recordType == ReplaceRepairComplexitySettingsRequest.class
          || recordType == ImportRepairComplexitySettingsRequest.class) {
        if ("lightBoundaryMinutes".equals(components[index].getName())) {
          arguments[index] = 60;
        }
        if ("mediumBoundaryMinutes".equals(components[index].getName())) {
          arguments[index] = 180;
        }
        if ("complexBoundaryMinutes".equals(components[index].getName())) {
          arguments[index] = 360;
        }
      }
      if ("movementToRepair".equals(components[index].getName())) {
        arguments[index] = false;
      }
      if ("logisticsPlanningMode".equals(components[index].getName())
          || "logisticsScheduledDate".equals(components[index].getName())) {
        arguments[index] = null;
      }
      if (recordType == RetryInboundDeliveryRequest.class
          && "logisticsPlanningMode".equals(components[index].getName())) {
        arguments[index] = RepairLogisticsPlanningMode.AUTO;
      }
    }
    return arguments;
  }

  @SuppressWarnings("unchecked")
  private static Constructor<Object> canonicalConstructor(Class<?> recordType) {
    Class<?>[] types = Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getType)
        .toArray(Class<?>[]::new);
    try {
      return (Constructor<Object>) recordType.getDeclaredConstructor(types);
    } catch (NoSuchMethodException exception) {
      throw new AssertionError(exception);
    }
  }

  private static String stringSample(String name) {
    if (name.toLowerCase(Locale.ROOT).contains("sha256")
        || name.toLowerCase(Locale.ROOT).contains("fingerprint")) return "a".repeat(64);
    if (Set.of("unitPrice", "lineTotal", "total").contains(name)) return "12.00";
    if ("displayColor".equals(name) || name.endsWith("Color")) return "#336699";
    if ("quantity".equals(name)) return "2.5";
    if ("normativeMinutes".equals(name)) return "45.000";
    if (name.toLowerCase(Locale.ROOT).contains("code")) return "CODE";
    if ("actorId".equals(name)) return ID.toString();
    return "value";
  }

  private static String valueFor(ParameterSpec parameter) {
    if (parameter.wireType().startsWith("$")) {
      Class<?> type = SCHEMAS.entrySet().stream()
          .filter(entry -> entry.getValue().equals(parameter.wireType().substring(1)))
          .map(Map.Entry::getKey)
          .filter(Class::isEnum)
          .findFirst()
          .orElseThrow();
      return ((Enum<?>) type.getEnumConstants()[0]).name();
    }
    if ("uuid".equals(parameter.wireType())) return ID.toString();
    if ("array".equals(parameter.wireType())) return ID.toString();
    if ("integer".equals(parameter.wireType())) return parameter.defaultValue() == null
        ? "1" : parameter.defaultValue();
    if ("string".equals(parameter.wireType())) return "W/\"cached\"";
    throw new AssertionError("No request value for " + parameter);
  }

  private static Map<Class<?>, String> schemaMappings() {
    Map<Class<?>, String> values = new LinkedHashMap<>();
    values.put(ActorSnapshot.class, "ActorSnapshot");
    values.put(CabinContentsDispositionLineInput.class, "CabinContentsDispositionLineInput");
    values.put(CabinContentsDispositionPlanInput.class, "CabinContentsDispositionPlanInput");
    values.put(CabinContentsDispositionLine.class, "CabinContentsDispositionLine");
    values.put(CabinContentsDispositionPlan.class, "CabinContentsDispositionPlan");
    values.put(CreateCabinPropertyDispositionRequest.class, "CreateCabinPropertyDispositionRequest");
    values.put(
        CreateEquipmentPropertyDispositionRequest.class,
        "CreateEquipmentPropertyDispositionRequest");
    values.put(CreateInventoryLossDispositionRequest.class, "CreateInventoryLossDispositionRequest");
    values.put(
        CreateInventoryCabinWriteOffRequest.class,
        "CreateInventoryCabinWriteOffRequest");
    values.put(ApprovePropertyDispositionRequest.class, "ApprovePropertyDispositionRequest");
    values.put(RejectPropertyDispositionRequest.class, "RejectPropertyDispositionRequest");
    values.put(RecoverPropertyDispositionRequest.class, "RecoverPropertyDispositionRequest");
    values.put(
        WarehouseOperationMarkRecoveryRequest.class,
        "WarehouseOperationMarkRecoveryRequest");
    values.put(
        WarehouseOperationMarkRecoveryResponse.class,
        "WarehouseOperationMarkRecoveryResponse");
    values.put(
        FurnitureEquipmentLinkReviewRequest.class,
        "FurnitureEquipmentLinkReviewRequest");
    values.put(FurnitureEquipmentLinkResponse.class, "FurnitureEquipmentLink");
    values.put(WriteOffRepairRequest.class, "WriteOffRepairRequest");
    values.put(PropertyDispositionRepairChainEntry.class, "PropertyDispositionRepairChainEntry");
    values.put(PropertyDispositionDecisionResponse.class, "PropertyDispositionDecision");
    values.put(PropertyDispositionPage.class, "PropertyDispositionPage");
    values.put(
        UpsertLogisticsReturnEstimateSourceRequest.class,
        "UpsertLogisticsReturnEstimateSourceRequest");
    values.put(
        HistoricalShipmentRepairClosureRequest.class,
        "HistoricalShipmentRepairClosureRequest");
    values.put(ReturnEstimateSource.class, "ReturnEstimateSource");
    values.put(TransferRepairRequest.class, "TransferRepairRequest");
    values.put(PrepareTransferRepairResponse.class, "PrepareTransferRepairResult");
    values.put(
        TransferRepairArrivalPreflightResponse.class, "TransferRepairArrivalPreflight");
    values.put(CompleteTransferRepairRequest.class, "CompleteTransferRepairRequest");
    values.put(CompleteTransferRepairResponse.class, "CompleteTransferRepairResult");
    values.put(MediaReferenceInput.class, "MediaReference");
    values.put(CatalogCounts.class, "CatalogCounts");
    values.put(CatalogValidationReport.class, "CatalogValidationReport");
    values.put(CatalogVersionResponse.class, "CatalogVersion");
    values.put(CatalogRoutingInput.class, "CatalogRoutingInput");
    values.put(RoutingSnapshot.class, "RoutingSnapshot");
    values.put(CatalogNodeInput.class, "CatalogNodeInput");
    values.put(CatalogNodeResponse.class, "CatalogNode");
    values.put(CatalogLinkInput.class, "CatalogLinkInput");
    values.put(CatalogLinkResponse.class, "CatalogLink");
    values.put(ReplaceCatalogNodesRequest.class, "ReplaceCatalogNodesRequest");
    values.put(ReplaceCatalogLinksRequest.class, "ReplaceCatalogLinksRequest");
    values.put(CreateCatalogRequest.class, "CreateCatalogRequest");
    values.put(VersionCommand.class, "ExpectedVersionRequest");
    values.put(CompleteEstimateRequest.class, "CompleteEstimateRequest");
    values.put(QueueRepairRequest.class, "PriorityVersionRequest");
    values.put(RetryInboundDeliveryRequest.class, "RetryInboundDeliveryRequest");
    values.put(
        ReplaceRepairCapacitySettingsRequest.class, "ReplaceRepairCapacitySettingsRequest");
    values.put(RepairCapacitySettingsResponse.class, "RepairCapacitySettings");
    values.put(
        EstimateCreationWindowSettingsResponse.class, "EstimateCreationWindowSettings");
    values.put(
        ReplaceRepairComplexitySettingsRequest.class,
        "ReplaceRepairComplexitySettingsRequest");
    values.put(
        ImportRepairComplexitySettingsRequest.class,
        "ImportRepairComplexitySettingsRequest");
    values.put(RepairComplexitySettingsResponse.class, "RepairComplexitySettings");
    values.put(RepairPlaceTransitionRequest.class, "RepairPlaceTransitionRequest");
    values.put(
        HistoricalShipmentRepairClosureResponse.class,
        "HistoricalShipmentRepairClosureResponse");
    values.put(RepairPlaceAllocationResponse.class, "RepairPlaceAllocation");
    values.put(RepairPlaceProjectionResponse.class, "RepairPlaceProjection");
    values.put(
        LogisticsRepairPlaceAllocationResponse.class,
        "LogisticsRepairPlaceAllocation");
    values.put(
        LogisticsRepairPlaceProjectionAllocationResponse.class,
        "LogisticsRepairPlaceProjectionAllocation");
    values.put(
        LogisticsRepairPlaceProjectionResponse.class,
        "LogisticsRepairPlaceProjection");
    values.put(LogisticsCapitalRepairResponse.class, "LogisticsCapitalRepair");
    values.put(
        ReplaceRepairComplexityColorsRequest.class, "ReplaceRepairComplexityColorsRequest");
    values.put(RepairComplexityColorsResponse.class, "RepairComplexityColors");
    values.put(CatalogNodeSnapshot.class, "CatalogNodeSnapshot");
    values.put(EstimateLineInput.class, "EstimateLineInput");
    values.put(EstimateLineResponse.class, "EstimateLine");
    values.put(PlanStageInput.class, "PlanStageInput");
    values.put(CreateEstimateRequest.class, "CreateEstimateRequest");
    values.put(UpdateEstimateRequest.class, "ReplaceEstimateRequest");
    values.put(AmendEstimateRequest.class, "AmendEstimateRequest");
    values.put(EstimateRevisionResponse.class, "EstimateRevision");
    values.put(EstimateResponse.class, "Estimate");
    values.put(DeliverySnapshot.class, "DeliverySnapshot");
    values.put(LeaseSnapshot.class, "LeaseSnapshot");
    values.put(TaskSyncSnapshot.class, "TaskSyncSnapshot");
    values.put(TaskEvidenceResponse.class, "TaskEvidence");
    values.put(RepairWorkerEvidenceResponse.class, "RepairWorkerEvidence");
    values.put(RepairStageResponse.class, "RepairStage");
    values.put(RepairPlanResponse.class, "RepairPlan");
    values.put(InventorySourceReference.class, "InventorySourceReference");
    values.put(RepairResponse.class, "Repair");
    values.put(RepairComplexitySnapshot.class, "RepairComplexitySnapshot");
    values.put(InventoryPlanLineInput.class, "InventoryPlanLineInput");
    values.put(InventoryPlanStageSelection.class, "InventoryPlanStageSelection");
    values.put(FreezeInventoryPlanRequest.class, "FreezeInventoryPlanRequest");
    values.put(InventoryPlanLineSnapshot.class, "InventoryPlanLineSnapshot");
    values.put(InventoryPlanStageSnapshot.class, "InventoryPlanStageSnapshot");
    values.put(FrozenInventoryPlanSnapshot.class, "FrozenInventoryPlanSnapshot");
    values.put(FrozenInventoryPlanResponse.class, "FrozenInventoryPlan");
    values.put(UpsertInventoryRepairRequest.class, "UpsertInventoryRepairRequest");
    values.put(InventoryRepairUpsertResponse.class, "InventoryRepairUpsertResult");
    values.put(InventoryPublicationFindingInput.class, "InventoryPublicationFindingInput");
    values.put(
        InventoryPublicationPreflightRequest.class,
        "InventoryPublicationPreflightRequest");
    values.put(
        InventoryPublicationPlanSummary.class,
        "InventoryPublicationPlanSummary");
    values.put(InventoryPublicationCandidate.class, "InventoryPublicationCandidate");
    values.put(
        InventoryPublicationPreflightFinding.class,
        "InventoryPublicationPreflightFinding");
    values.put(
        InventoryPublicationPreflightResponse.class,
        "InventoryPublicationPreflightResponse");
    values.put(InventoryPublicationApplyRequest.class, "InventoryPublicationApplyRequest");
    values.put(
        InventoryPublicationSourceReference.class,
        "InventoryPublicationSourceReference");
    values.put(InventoryPublicationDeltaLine.class, "InventoryPublicationDeltaLine");
    values.put(InventoryPublicationDelta.class, "InventoryPublicationDelta");
    values.put(
        InventoryPublicationSuccessorStatus.class,
        "InventoryPublicationSuccessorStatus");
    values.put(InventoryPublicationApplyResult.class, "InventoryPublicationApplyResult");
    values.put(InventoryNoWorkOutcomeRequest.class, "InventoryNoWorkOutcomeRequest");
    values.put(InventoryNoWorkOutcomeResult.class, "InventoryNoWorkOutcomeResult");
    values.put(InventoryRepairSnapshotRequest.class, "InventoryRepairSnapshotRequest");
    values.put(InventoryRepairFact.class, "InventoryRepairFact");
    values.put(InventoryRepairSnapshot.class, "InventoryRepairSnapshot");
    values.put(InventoryRepairSnapshotsResponse.class, "InventoryRepairSnapshots");
    values.put(CreateDirectRepairRequest.class, "CreateDirectRepairRequest");
    values.put(UpdateRepairPlanRequest.class, "ReplaceRepairPlanRequest");
    values.put(CreateReworkRequest.class, "CreateReworkRequest");
    values.put(ReworkCandidateLine.class, "ReworkCandidateLine");
    values.put(ReworkCandidatesResponse.class, "ReworkCandidates");
    values.put(RepairDecisionRequest.class, "RepairDecisionRequest");
    values.put(EstimateCommandResult.class, "EstimateCommandResult");
    values.put(RepairCommandResult.class, "RepairCommandResult");
    values.put(AcceptanceProjection.class, "AcceptanceProjection");
    values.put(CatalogVersionState.class, "CatalogLifecycle");
    values.put(EstimateState.class, "EstimateLifecycle");
    values.put(RepairExecutionState.class, "RepairExecutionState");
    values.put(RepairAcceptanceState.class, "RepairAcceptanceState");
    values.put(PropertyDispositionAssetKind.class, "PropertyDispositionAssetKind");
    values.put(PropertyDispositionKind.class, "PropertyDispositionKind");
    values.put(PropertyDispositionSource.class, "PropertyDispositionSource");
    values.put(PropertyDispositionState.class, "PropertyDispositionState");
    values.put(FurnitureEquipmentLinkState.class, "FurnitureEquipmentLinkState");
    values.put(
        FurnitureEquipmentLinkReviewAction.class,
        "FurnitureEquipmentLinkReviewAction");
    values.put(
        PropertyDispositionAssetEffectState.class,
        "PropertyDispositionAssetEffectState");
    values.put(PropertyDispositionContentsMode.class, "CabinContentsDispositionMode");
    values.put(InventoryPlanMode.class, "InventoryPlanMode");
    values.put(InventoryPlanLineKind.class, "InventoryPlanLineKind");
    values.put(InventoryPlanLineType.class, "InventoryPlanLineType");
    values.put(
        InventoryPublicationTargetKind.class,
        "InventoryPublicationTargetKind");
    values.put(
        InventoryPublicationStrategy.class,
        "InventoryPublicationStrategy");
    values.put(
        InventoryPublicationOutcome.class,
        "InventoryPublicationOutcome");
    values.put(
        InventoryPublicationSuccessorState.class,
        "InventoryPublicationSuccessorState");
    values.put(
        InventoryPublicationTerminalFact.class,
        "InventoryPublicationTerminalFact");
    values.put(
        InventoryPublicationDeltaDisposition.class,
        "InventoryPublicationDeltaDisposition");
    values.put(EstimateLineType.class, "EstimateLineType");
    values.put(RepairComplexity.class, "RepairComplexity");
    values.put(RepairReclassificationState.class, "RepairReclassificationState");
    values.put(RepairPlaceAllocationState.class, "RepairPlaceAllocationState");
    values.put(
        RepairLogisticsPlanningMode.class,
        "RepairLogisticsPlanningMode");
    return Map.copyOf(values);
  }

  private static Map<Class<?>, String> requestSchemaMappings() {
    Map<Class<?>, String> values = new LinkedHashMap<>();
    values.put(CabinContentsDispositionLineInput.class, "CabinContentsDispositionLineInput");
    values.put(CabinContentsDispositionPlanInput.class, "CabinContentsDispositionPlanInput");
    values.put(CreateCabinPropertyDispositionRequest.class, "CreateCabinPropertyDispositionRequest");
    values.put(
        CreateEquipmentPropertyDispositionRequest.class,
        "CreateEquipmentPropertyDispositionRequest");
    values.put(CreateInventoryLossDispositionRequest.class, "CreateInventoryLossDispositionRequest");
    values.put(
        CreateInventoryCabinWriteOffRequest.class,
        "CreateInventoryCabinWriteOffRequest");
    values.put(ApprovePropertyDispositionRequest.class, "ApprovePropertyDispositionRequest");
    values.put(RejectPropertyDispositionRequest.class, "RejectPropertyDispositionRequest");
    values.put(RecoverPropertyDispositionRequest.class, "RecoverPropertyDispositionRequest");
    values.put(
        FurnitureEquipmentLinkReviewRequest.class,
        "FurnitureEquipmentLinkReviewRequest");
    values.put(WriteOffRepairRequest.class, "WriteOffRepairRequest");
    values.put(
        UpsertLogisticsReturnEstimateSourceRequest.class,
        "UpsertLogisticsReturnEstimateSourceRequest");
    values.put(TransferRepairRequest.class, "TransferRepairRequest");
    values.put(CompleteTransferRepairRequest.class, "CompleteTransferRepairRequest");
    values.put(MediaReferenceInput.class, "MediaReference");
    values.put(CatalogRoutingInput.class, "CatalogRoutingInput");
    values.put(RoutingSnapshot.class, "RoutingSnapshot");
    values.put(CatalogNodeInput.class, "CatalogNodeInput");
    values.put(CatalogLinkInput.class, "CatalogLinkInput");
    values.put(CreateCatalogRequest.class, "CreateCatalogRequest");
    values.put(ReplaceCatalogNodesRequest.class, "ReplaceCatalogNodesRequest");
    values.put(ReplaceCatalogLinksRequest.class, "ReplaceCatalogLinksRequest");
    values.put(VersionCommand.class, "ExpectedVersionRequest");
    values.put(CompleteEstimateRequest.class, "CompleteEstimateRequest");
    values.put(QueueRepairRequest.class, "PriorityVersionRequest");
    values.put(RetryInboundDeliveryRequest.class, "RetryInboundDeliveryRequest");
    values.put(
        ReplaceRepairCapacitySettingsRequest.class, "ReplaceRepairCapacitySettingsRequest");
    values.put(
        ReplaceRepairComplexitySettingsRequest.class,
        "ReplaceRepairComplexitySettingsRequest");
    values.put(
        ImportRepairComplexitySettingsRequest.class,
        "ImportRepairComplexitySettingsRequest");
    values.put(RepairPlaceTransitionRequest.class, "RepairPlaceTransitionRequest");
    values.put(
        ReplaceRepairComplexityColorsRequest.class, "ReplaceRepairComplexityColorsRequest");
    values.put(CatalogNodeSnapshot.class, "CatalogNodeSnapshot");
    values.put(EstimateLineInput.class, "EstimateLineInput");
    values.put(PlanStageInput.class, "PlanStageInput");
    values.put(CreateEstimateRequest.class, "CreateEstimateRequest");
    values.put(UpdateEstimateRequest.class, "ReplaceEstimateRequest");
    values.put(AmendEstimateRequest.class, "AmendEstimateRequest");
    values.put(CreateDirectRepairRequest.class, "CreateDirectRepairRequest");
    values.put(UpdateRepairPlanRequest.class, "ReplaceRepairPlanRequest");
    values.put(CreateReworkRequest.class, "CreateReworkRequest");
    values.put(RepairDecisionRequest.class, "RepairDecisionRequest");
    values.put(WriteOffRepairRequest.class, "WriteOffRepairRequest");
    values.put(InventoryPlanLineInput.class, "InventoryPlanLineInput");
    values.put(InventoryPlanStageSelection.class, "InventoryPlanStageSelection");
    values.put(FreezeInventoryPlanRequest.class, "FreezeInventoryPlanRequest");
    values.put(InventoryPlanLineSnapshot.class, "InventoryPlanLineSnapshot");
    values.put(InventoryPlanStageSnapshot.class, "InventoryPlanStageSnapshot");
    values.put(FrozenInventoryPlanSnapshot.class, "FrozenInventoryPlanSnapshot");
    values.put(UpsertInventoryRepairRequest.class, "UpsertInventoryRepairRequest");
    values.put(InventoryPublicationFindingInput.class, "InventoryPublicationFindingInput");
    values.put(
        InventoryPublicationPreflightRequest.class,
        "InventoryPublicationPreflightRequest");
    values.put(InventoryPublicationApplyRequest.class, "InventoryPublicationApplyRequest");
    values.put(InventoryRepairSnapshotRequest.class, "InventoryRepairSnapshotRequest");
    return Map.copyOf(values);
  }

  private static OperationSpec op(
      String method,
      String path,
      String operationId,
      Class<?> controller,
      String controllerMethod,
      List<ParameterSpec> parameters,
      Class<?> bodyType,
      String bodySchema,
      String successStatus,
      String responseSchema,
      boolean idempotent,
      String... errors) {
    return new OperationSpec(
        method, path, operationId, controller, controllerMethod, List.copyOf(parameters),
        bodyType, bodySchema, successStatus, List.of(), responseSchema, idempotent, List.of(errors));
  }

  private static OperationSpec opWithAlternateSuccess(
      String method,
      String path,
      String operationId,
      Class<?> controller,
      String controllerMethod,
      List<ParameterSpec> parameters,
      Class<?> bodyType,
      String bodySchema,
      String successStatus,
      String alternateSuccessStatus,
      String responseSchema,
      boolean idempotent,
      String... errors) {
    return new OperationSpec(
        method,
        path,
        operationId,
        controller,
        controllerMethod,
        List.copyOf(parameters),
        bodyType,
        bodySchema,
        successStatus,
        List.of(alternateSuccessStatus),
        responseSchema,
        idempotent,
        List.of(errors));
  }

  private static ParameterSpec path(String name) {
    return new ParameterSpec(name, "path", true, "uuid", null);
  }

  private static ParameterSpec query(
      String name, boolean required, String wireType, String defaultValue) {
    return new ParameterSpec(name, "query", required, wireType, defaultValue);
  }

  private static ParameterSpec requiredHeader(String name) {
    return new ParameterSpec(name, "header", true, "uuid", null);
  }

  private static ParameterSpec optionalHeader(String name) {
    return new ParameterSpec(name, "header", false, "string", null);
  }

  @SafeVarargs
  private static <T> List<T> append(List<T> values, T... extra) {
    List<T> result = new ArrayList<>(values);
    result.addAll(List.of(extra));
    return List.copyOf(result);
  }

  private static Map<String, Object> openApi() throws Exception {
    Path path = Path.of(System.getProperty("rwms.contracts.dir"), "openapi/maintenance-service.yaml");
    try (InputStream input = Files.newInputStream(path)) {
      return new Yaml().load(input);
    }
  }

  private static int openApiOperationCount(Map<String, Object> document) {
    return child(document, "paths").values().stream()
        .map(MaintenanceOpenApiParityTest::map)
        .mapToInt(path -> (int) path.keySet().stream()
            .filter(method -> HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT)))
            .count())
        .sum();
  }

  private static Set<String> controllerOperations() {
    Set<String> operations = new LinkedHashSet<>();
    for (Class<?> controller : List.of(
        MaintenanceCatalogController.class,
        MaintenanceEstimateController.class,
        MaintenanceRepairController.class,
        MaintenanceInventoryController.class,
        MaintenanceLogisticsController.class,
        MaintenanceHistoricalShipmentController.class,
        MaintenanceTransferRepairController.class,
        MaintenanceRepairPlaceLogisticsController.class,
        MaintenanceSettingsController.class,
        EstimateCreationWindowSettingsController.class,
        RepairComplexitySettingsController.class,
        RepairPlaceController.class,
        RepairComplexityColorsController.class,
        PropertyDispositionController.class,
        PropertyDispositionInventoryController.class,
        WarehouseOperationMarkRecoveryController.class,
        FurnitureEquipmentLinkController.class)) {
      RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) continue;
        operations.add(mapping.method()[0].name() + " "
            + normalize(firstPath(root) + "/" + firstPath(mapping)));
      }
    }
    return operations;
  }

  private static Map<String, Object> openApiOperation(
      Map<String, Object> document, OperationSpec expected) {
    return child(child(child(document, "paths"), expected.path()),
        expected.httpMethod().toLowerCase(Locale.ROOT));
  }

  private static Map<String, Object> successSchema(
      Map<String, Object> operation, String successStatus) {
    return child(map(child(child(child(operation, "responses"), successStatus), "content")
        .get(JSON)), "schema");
  }

  private static Map<String, Object> schema(Map<String, Object> document, String name) {
    return child(child(child(document, "components"), "schemas"), name);
  }

  private static Map<String, Object> resolve(
      Map<String, Object> document, Map<String, Object> value) {
    Object reference = value.get("$ref");
    if (!(reference instanceof String path) || !path.startsWith("#/")) return value;
    Object resolved = document;
    for (String segment : path.substring(2).split("/")) {
      resolved = map(resolved).get(segment.replace("~1", "/").replace("~0", "~"));
    }
    return map(resolved);
  }

  private static String schemaDescriptor(Object declared) {
    Map<String, Object> schema = map(declared);
    if (schema.containsKey("$ref")) {
      String ref = String.valueOf(schema.get("$ref"));
      return ref.substring(ref.lastIndexOf('/') + 1);
    }
    if ("array".equals(schema.get("type"))) {
      return "[" + schemaDescriptor(schema.get("items")) + "]";
    }
    throw new AssertionError("Unsupported operation schema " + schema);
  }

  private static List<String> enumValues(Map<String, Object> schema) {
    return stringList(schema.get("enum"));
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> values)) return List.of();
    return values.stream().map(String::valueOf).toList();
  }

  private static String firstPath(RequestMapping mapping) {
    if (mapping == null) return "";
    String[] declared = mapping.path().length == 0 ? mapping.value() : mapping.path();
    return declared.length == 0 ? "" : declared[0];
  }

  private static String normalize(String value) {
    String normalized = value.replaceAll("/{2,}", "/");
    if (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.startsWith("/") ? normalized : "/" + normalized;
  }

  private static void assertAllLocalReferencesResolve(
      Object node, Map<String, Object> document) {
    if (node instanceof Map<?, ?> values) {
      Object reference = values.get("$ref");
      if (reference instanceof String path && path.startsWith("#/")) {
        assertThat(resolve(document, map(values))).as("resolved " + path).isNotNull();
      }
      values.values().forEach(value -> assertAllLocalReferencesResolve(value, document));
    } else if (node instanceof Collection<?> values) {
      values.forEach(value -> assertAllLocalReferencesResolve(value, document));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> value, String key) {
    Object child = value.get(key);
    assertThat(child).as("missing map key " + key).isInstanceOf(Map.class);
    return (Map<String, Object>) child;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> mapOrEmpty(Object value) {
    return value instanceof Map<?, ?> ? map(value) : Map.of();
  }

  record ParameterSpec(
      String name, String location, boolean required, String wireType, String defaultValue) {}

  record OperationSpec(
      String httpMethod,
      String path,
      String operationId,
      Class<?> controller,
      String controllerMethod,
      List<ParameterSpec> parameters,
      Class<?> bodyType,
      String bodySchema,
      String successStatus,
      List<String> alternateSuccessStatuses,
      String responseSchema,
      boolean idempotent,
      List<String> errors) {
    String id() {
      return httpMethod + " " + path;
    }

    Set<String> allStatuses() {
      Set<String> result = new LinkedHashSet<>();
      result.add(successStatus);
      result.addAll(alternateSuccessStatuses);
      result.addAll(errors);
      return result;
    }
  }
}
