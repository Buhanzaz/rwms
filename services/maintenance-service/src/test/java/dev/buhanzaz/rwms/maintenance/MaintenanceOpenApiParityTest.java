package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceCatalogController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceEstimateController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceRepairController;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
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
  void allEighteenPathsAndTwentyFourOperationsExactlyMatchTheApprovedAcceptanceMatrix()
      throws Exception {
    Map<String, Object> document = openApi();
    assertThat(child(document, "paths")).hasSize(18);
    assertThat(openApiOperationCount(document)).isEqualTo(24);
    assertThat(controllerOperations()).hasSize(24);

    for (OperationSpec expected : OPERATIONS) {
      assertOpenApiOperation(document, expected);
      assertControllerOperation(expected);
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
        assertProblemResponse(document, expected.id(), responses, statusCode, null);
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
    assertPage(document, "WriteOffProjectionPage", WriteOffProjection.class);
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
          if (allowsNull(document, child(properties, property))) {
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

  @Test
  void everyPublishedEnumUsesItsExactJacksonWireValue() throws Exception {
    Map<String, Object> document = openApi();
    assertEnum(document, CatalogVersionState.class, "CatalogLifecycle");
    assertEnum(document, EstimateState.class, "EstimateLifecycle");
    assertEnum(document, RepairOrigin.class, "RepairOrigin");
    assertEnum(document, RepairKind.class, "RepairKind");
    assertEnum(document, RepairExecutionState.class, "RepairExecutionState");
    assertEnum(document, RepairAcceptanceState.class, "RepairAcceptanceState");
    assertEnum(document, RepairStageKind.class, "RepairStageKind");
    assertEnum(document, RepairStageState.class, "RepairStageState");
    assertEnum(document, CatalogNodeType.class, "CatalogNodeType");
    assertEnum(document, CatalogLinkType.class, "CatalogLinkType");
    assertEnum(document, DeliveryState.class, "DeliveryState");
    assertEnum(document, LeaseReconciliationState.class, "LeaseReconciliationState");
    assertEnum(document, GenerationState.class, "GenerationState");
    assertPropertyEnum(document, ActorType.class, "ActorSnapshot", "actorType");
  }

  @Test
  void mockMvcExecutesEveryControllerBindingStatusBodyAndRequiredHeader() throws Exception {
    MaintenanceApplicationService service = serviceFixture();
    MaintenanceAuthorizer authorizer = mock(MaintenanceAuthorizer.class);
    when(authorizer.subjectId(null)).thenReturn(ID);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new MaintenanceCatalogController(service, authorizer),
            new MaintenanceEstimateController(service, authorizer),
            new MaintenanceRepairController(service, authorizer))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .addFilters(new CorrelationIdFilter())
        .build();
    Map<String, Object> document = openApi();

    for (OperationSpec operation : OPERATIONS) {
      MockHttpServletRequestBuilder request = request(
          HttpMethod.valueOf(operation.httpMethod()), operation.path().replace("{id}", ID.toString()));
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
    List<ParameterSpec> idempotency = List.of(requiredHeader("Idempotency-Key"));
    List<OperationSpec> result = new ArrayList<>();
    result.add(op("GET", "/api/maintenance/v1/catalog/versions", "listCatalogVersions",
        MaintenanceCatalogController.class, "versions",
        append(warehousePage, query("lifecycle", false, "$CatalogLifecycle", null)),
        null, null, "200", "CatalogVersionPage", false, "401", "403"));
    result.add(op("GET", "/api/maintenance/v1/catalog/versions/{id}/nodes", "listCatalogNodes",
        MaintenanceCatalogController.class, "nodes", catalogId,
        null, null, "200", "[CatalogNode]", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/catalog/versions/{id}/nodes", "replaceDraftCatalogNodes",
        MaintenanceCatalogController.class, "replaceNodes", catalogId,
        ReplaceCatalogNodesRequest.class, "ReplaceCatalogNodesRequest", "200", "CatalogVersion", false,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("GET", "/api/maintenance/v1/catalog/versions/{id}/links", "listCatalogLinks",
        MaintenanceCatalogController.class, "links", catalogId,
        null, null, "200", "[CatalogLink]", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/catalog/versions/{id}/links", "replaceDraftCatalogLinks",
        MaintenanceCatalogController.class, "replaceLinks", catalogId,
        ReplaceCatalogLinksRequest.class, "ReplaceCatalogLinksRequest", "200", "CatalogVersion", false,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/catalog/imports", "importCatalogVersion",
        MaintenanceCatalogController.class, "importCatalog", idempotency,
        ImportCatalogRequest.class, "CatalogImportRequest", "201", "CatalogVersion", true,
        "400", "401", "403", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/catalog/versions/{id}/activate", "activateCatalogVersion",
        MaintenanceCatalogController.class, "activate", append(catalogId, requiredHeader("Idempotency-Key")),
        VersionCommand.class, "ExpectedVersionRequest", "200", "CatalogVersion", true,
        "400", "401", "403", "404", "409", "422"));

    result.add(op("GET", "/api/maintenance/v1/estimates", "listEstimates",
        MaintenanceEstimateController.class, "list",
        append(warehousePage,
            query("lifecycle", false, "$EstimateLifecycle", null),
            query("rentalItemId", false, "uuid", null)),
        null, null, "200", "EstimatePage", false, "401", "403"));
    result.add(op("POST", "/api/maintenance/v1/estimates", "createEstimate",
        MaintenanceEstimateController.class, "create", idempotency,
        CreateEstimateRequest.class, "CreateEstimateRequest", "201", "Estimate", true,
        "400", "401", "403", "409", "422"));
    result.add(op("GET", "/api/maintenance/v1/estimates/{id}", "getEstimate",
        MaintenanceEstimateController.class, "get", estimateId,
        null, null, "200", "Estimate", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/estimates/{id}", "replaceDraftEstimate",
        MaintenanceEstimateController.class, "replace", estimateId,
        UpdateEstimateRequest.class, "ReplaceEstimateRequest", "200", "Estimate", false,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/estimates/{id}/complete", "completeEstimate",
        MaintenanceEstimateController.class, "complete", append(estimateId, requiredHeader("Idempotency-Key")),
        CompleteEstimateRequest.class, "ExpectedVersionRequest", "200", "EstimateCommandResult", true,
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
            query("rentalItemId", false, "uuid", null)),
        null, null, "200", "RepairPage", false, "401", "403"));
    result.add(op("POST", "/api/maintenance/v1/repairs/direct", "createDirectRepair",
        MaintenanceRepairController.class, "direct", idempotency,
        CreateDirectRepairRequest.class, "CreateDirectRepairRequest", "201", "Repair", true,
        "400", "401", "403", "409", "422"));
    result.add(op("GET", "/api/maintenance/v1/repairs/{id}", "getRepair",
        MaintenanceRepairController.class, "get", repairId,
        null, null, "200", "Repair", false, "401", "403", "404"));
    result.add(op("GET", "/api/maintenance/v1/repairs/{id}/plan", "getRepairPlan",
        MaintenanceRepairController.class, "plan", repairId,
        null, null, "200", "RepairPlan", false, "401", "403", "404"));
    result.add(op("PUT", "/api/maintenance/v1/repairs/{id}/plan", "replaceDraftRepairPlan",
        MaintenanceRepairController.class, "replacePlan", repairId,
        UpdateRepairPlanRequest.class, "ReplaceRepairPlanRequest", "200", "Repair", false,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/repairs/{id}/plan", "queueRepairPlan",
        MaintenanceRepairController.class, "queuePlan", append(repairId, requiredHeader("Idempotency-Key")),
        VersionCommand.class, "ExpectedVersionRequest", "200", "RepairCommandResult", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("POST", "/api/maintenance/v1/repairs/{id}/reworks", "createRework",
        MaintenanceRepairController.class, "rework", append(repairId, requiredHeader("Idempotency-Key")),
        CreateReworkRequest.class, "CreateReworkRequest", "201", "Repair", true,
        "400", "401", "403", "404", "409", "422"));
    result.add(op("POST", "/api/maintenance/v1/repairs/{id}/accept", "acceptRepair",
        MaintenanceRepairController.class, "accept", append(repairId, requiredHeader("Idempotency-Key")),
        RepairDecisionRequest.class, "RepairDecisionRequest", "200", "RepairCommandResult", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("POST", "/api/maintenance/v1/repairs/{id}/write-off", "writeOffRepair",
        MaintenanceRepairController.class, "writeOff", append(repairId, requiredHeader("Idempotency-Key")),
        WriteOffRepairRequest.class, "WriteOffRepairRequest", "200", "RepairCommandResult", true,
        "400", "401", "403", "404", "409", "422", "503"));
    result.add(op("GET", "/api/maintenance/v1/acceptance", "listAcceptanceProjection",
        MaintenanceRepairController.class, "acceptance",
        append(warehousePage, query("state", false, "$RepairAcceptanceState", null)),
        null, null, "200", "AcceptanceProjectionPage", false, "401", "403"));
    result.add(op("GET", "/api/maintenance/v1/write-offs", "listWriteOffProjection",
        MaintenanceRepairController.class, "writeOffs", warehousePage,
        null, null, "200", "WriteOffProjectionPage", false, "401", "403"));
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
    if (itemType == AcceptanceProjection.class) return "AcceptanceProjectionPage";
    if (itemType == WriteOffProjection.class) return "WriteOffProjectionPage";
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
      assertThat(candidates).as(path + " oneOf").hasSize(1);
      assertJsonMatchesSchema(document, node, candidates.getFirst(), path);
      return;
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
        node.properties().forEach(entry -> assertJsonMatchesSchema(
            document, entry.getValue(), child(properties, entry.getKey()), path + "." + entry.getKey()));
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
      case "catalogVersion", "replaceCatalogNodes", "replaceCatalogLinks" ->
          sample(CatalogVersionResponse.class, "catalogVersion");
      case "catalogNodes" -> List.of(sample(CatalogNodeResponse.class, "catalogNode"));
      case "catalogLinks" -> List.of(sample(CatalogLinkResponse.class, "catalogLink"));
      case "importCatalog", "activateCatalog" -> createResult(CatalogVersionResponse.class);
      case "estimates" -> List.of(sample(EstimateResponse.class, "estimate"));
      case "estimate", "updateEstimate" -> sample(EstimateResponse.class, "estimate");
      case "createEstimate" -> createResult(EstimateResponse.class);
      case "completeEstimate", "amendEstimate" -> createResult(EstimateCommandResult.class);
      case "repairs" -> List.of(sample(RepairResponse.class, "repair"));
      case "repair", "updateRepairPlan" -> sample(RepairResponse.class, "repair");
      case "repairPlan" -> sample(RepairPlanResponse.class, "repairPlan");
      case "createDirectRepair", "createRework" -> createResult(RepairResponse.class);
      case "queueRepair", "accept", "writeOff" -> createResult(RepairCommandResult.class);
      case "acceptance" -> List.of(sample(AcceptanceProjection.class, "acceptance"));
      case "writeOffs" -> List.of(sample(WriteOffProjection.class, "writeOff"));
      default -> invocation.callRealMethod();
    });
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
    if (raw == String.class) return stringSample(name);
    if (raw == boolean.class || raw == Boolean.class) return true;
    if (raw == int.class || raw == Integer.class) return 1;
    if (raw == long.class || raw == Long.class) return 1L;
    if (raw == LocalDate.class) return LocalDate.of(2026, 7, 16);
    if (raw == OffsetDateTime.class) return OffsetDateTime.parse("2026-07-16T12:00:00Z");
    if (raw.isEnum()) return raw.getEnumConstants()[0];
    if (raw.isRecord()) return canonicalConstructor(raw).newInstance(sampleArguments(raw));
    throw new AssertionError("No JSON fixture for " + type);
  }

  private static Object[] sampleArguments(Class<?> recordType) throws Exception {
    RecordComponent[] components = recordType.getRecordComponents();
    Object[] arguments = new Object[components.length];
    for (int index = 0; index < components.length; index++) {
      arguments[index] = sample(components[index].getGenericType(), components[index].getName());
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
    if (name.toLowerCase(Locale.ROOT).contains("sha256")) return "a".repeat(64);
    if (Set.of("unitPrice", "lineTotal", "total").contains(name)) return "12.00";
    if ("quantity".equals(name)) return "2.5";
    if ("code".equals(name)) return "CODE";
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
    if ("integer".equals(parameter.wireType())) return parameter.defaultValue() == null
        ? "1" : parameter.defaultValue();
    throw new AssertionError("No request value for " + parameter);
  }

  private static Map<Class<?>, String> schemaMappings() {
    Map<Class<?>, String> values = new LinkedHashMap<>();
    values.put(ActorSnapshot.class, "ActorSnapshot");
    values.put(MediaReferenceInput.class, "MediaReference");
    values.put(CatalogCounts.class, "CatalogCounts");
    values.put(CatalogValidationReport.class, "CatalogValidationReport");
    values.put(CatalogVersionResponse.class, "CatalogVersion");
    values.put(RoutingSnapshot.class, "RoutingSnapshot");
    values.put(OpaqueCatalogReference.class, "OpaqueCatalogReference");
    values.put(CatalogNodeInput.class, "CatalogNodeInput");
    values.put(CatalogNodeResponse.class, "CatalogNode");
    values.put(CatalogLinkInput.class, "CatalogLinkInput");
    values.put(CatalogLinkResponse.class, "CatalogLink");
    values.put(ReplaceCatalogNodesRequest.class, "ReplaceCatalogNodesRequest");
    values.put(ReplaceCatalogLinksRequest.class, "ReplaceCatalogLinksRequest");
    values.put(ImportCatalogRequest.class, "CatalogImportRequest");
    values.put(VersionCommand.class, "ExpectedVersionRequest");
    values.put(CompleteEstimateRequest.class, "ExpectedVersionRequest");
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
    values.put(RepairStageResponse.class, "RepairStage");
    values.put(RepairPlanResponse.class, "RepairPlan");
    values.put(RepairResponse.class, "Repair");
    values.put(CreateDirectRepairRequest.class, "CreateDirectRepairRequest");
    values.put(UpdateRepairPlanRequest.class, "ReplaceRepairPlanRequest");
    values.put(CreateReworkRequest.class, "CreateReworkRequest");
    values.put(RepairDecisionRequest.class, "RepairDecisionRequest");
    values.put(WriteOffRepairRequest.class, "WriteOffRepairRequest");
    values.put(EstimateCommandResult.class, "EstimateCommandResult");
    values.put(RepairCommandResult.class, "RepairCommandResult");
    values.put(AcceptanceProjection.class, "AcceptanceProjection");
    values.put(WriteOffProjection.class, "WriteOffProjection");
    values.put(CatalogVersionState.class, "CatalogLifecycle");
    values.put(EstimateState.class, "EstimateLifecycle");
    values.put(RepairExecutionState.class, "RepairExecutionState");
    values.put(RepairAcceptanceState.class, "RepairAcceptanceState");
    return Map.copyOf(values);
  }

  private static Map<Class<?>, String> requestSchemaMappings() {
    Map<Class<?>, String> values = new LinkedHashMap<>();
    values.put(MediaReferenceInput.class, "MediaReference");
    values.put(RoutingSnapshot.class, "RoutingSnapshot");
    values.put(OpaqueCatalogReference.class, "OpaqueCatalogReference");
    values.put(CatalogNodeInput.class, "CatalogNodeInput");
    values.put(CatalogLinkInput.class, "CatalogLinkInput");
    values.put(ImportCatalogRequest.class, "CatalogImportRequest");
    values.put(ReplaceCatalogNodesRequest.class, "ReplaceCatalogNodesRequest");
    values.put(ReplaceCatalogLinksRequest.class, "ReplaceCatalogLinksRequest");
    values.put(VersionCommand.class, "ExpectedVersionRequest");
    values.put(CompleteEstimateRequest.class, "ExpectedVersionRequest");
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
        bodyType, bodySchema, successStatus, responseSchema, idempotent, List.of(errors));
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
        MaintenanceRepairController.class)) {
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
      String responseSchema,
      boolean idempotent,
      List<String> errors) {
    String id() {
      return httpMethod + " " + path;
    }

    Set<String> allStatuses() {
      Set<String> result = new LinkedHashSet<>();
      result.add(successStatus);
      result.addAll(errors);
      return result;
    }
  }
}
