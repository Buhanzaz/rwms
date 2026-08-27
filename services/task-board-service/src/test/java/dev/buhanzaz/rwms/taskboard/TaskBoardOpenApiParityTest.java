package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

class TaskBoardOpenApiParityTest {
  private static final String API_PACKAGE = "dev.buhanzaz.rwms.taskboard.api";
  private static final Set<String> OPENAPI_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  @Test
  void openApiInventoryMatchesEveryActiveControllerMapping() throws Exception {
    Set<Endpoint> controllerEndpoints = controllerEndpoints();
    Map<String, Object> document = openApiDocument();
    Set<Endpoint> openApiEndpoints = openApiEndpoints(document);

    assertThat(controllerEndpoints).isNotEmpty();
    assertThat(openApiEndpoints)
        .as("OpenAPI paths and HTTP methods must exactly match active task-board controllers")
        .containsExactlyInAnyOrderElementsOf(controllerEndpoints);
  }

  @Test
  void everySecuredOperationDocumentsUnauthorizedAndTypedSuccessResponse() throws Exception {
    Map<String, Object> document = openApiDocument();
    Map<String, Object> paths = child(document, "paths");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    assertThat(schemas).doesNotContainKeys("JsonObject", "JsonObjectList");

    for (var pathEntry : paths.entrySet()) {
      Map<String, Object> pathItem = map(pathEntry.getValue());
      for (var methodEntry : pathItem.entrySet()) {
        String method = methodEntry.getKey().toLowerCase(Locale.ROOT);
        if (!OPENAPI_METHODS.contains(method)) {
          continue;
        }
        String operationLabel = method.toUpperCase(Locale.ROOT) + " " + pathEntry.getKey();
        Map<String, Object> operation = map(methodEntry.getValue());
        Map<String, Object> responses = child(operation, "responses");
        assertThat(responses)
            .as("%s must document missing or invalid bearer tokens", operationLabel)
            .containsKey("401");

        var successResponses =
            responses.entrySet().stream()
                .filter(response -> response.getKey().matches("2\\d\\d"))
                .toList();
        assertThat(successResponses)
            .as("%s must document a success response", operationLabel)
            .isNotEmpty();
        for (var success : successResponses) {
          if ("204".equals(success.getKey())) {
            continue;
          }
          Map<String, Object> response = resolve(document, map(success.getValue()));
          Map<String, Object> content = child(response, "content");
          if (content.containsKey("text/event-stream")) {
            Map<String, Object> mediaType = child(content, "text/event-stream");
            Map<String, Object> streamSchema = child(mediaType, "schema");
            assertThat(streamSchema.get("type"))
                .as("%s %s event stream must be encoded as text", operationLabel, success.getKey())
                .isEqualTo("string");
            Map<String, Object> eventSchema =
                resolve(document, child(mediaType, "x-rwms-event-data-schema"));
            assertThat(eventSchema.get("type"))
                .as(
                    "%s %s event data must reference a concrete object schema",
                    operationLabel,
                    success.getKey())
                .isEqualTo("object");
            continue;
          }
          Map<String, Object> mediaType = child(content, "application/json");
          Map<String, Object> declaredSchema = child(mediaType, "schema");
          String schemaReference = String.valueOf(declaredSchema.get("$ref"));
          assertThat(schemaReference)
              .as(
                  "%s %s must reference a concrete transport schema",
                  operationLabel,
                  success.getKey())
              .startsWith("#/components/schemas/")
              .doesNotEndWith("/JsonObject")
              .doesNotEndWith("/JsonObjectList");
          Map<String, Object> schema = resolve(document, declaredSchema);
          assertThat(schema.get("type"))
              .as("%s %s transport schema must be typed", operationLabel, success.getKey())
              .isIn("object", "array");
        }
      }
    }

    Map<String, Object> apiProblem = child(schemas, "ApiProblem");
    assertThat(list(apiProblem.get("required")))
        .contains("code", "violations", "correlation");
  }

  @Test
  void queueCatalogSchemaOwnsTheGlobalTaskBoardStandard()
      throws Exception {
    Map<String, Object> document = openApiDocument();
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    Map<String, Object> paths = child(document, "paths");

    assertThat(schemas).doesNotContainKeys("QueueGroupBinding", "QueueGroupBindingRequest");

    Map<String, Object> queue = child(schemas, "WorkQueue");
    assertThat(child(queue, "properties")).doesNotContainKey("groupBindings");
    assertThat(list(queue.get("required"))).doesNotContain("groupBindings");

    Map<String, Object> queueDefinitionRequest = child(schemas, "QueueDefinitionRequest");
    assertThat(child(queueDefinitionRequest, "properties"))
        .containsKeys(
            "version",
            "name",
            "description",
            "type",
            "purpose",
            "sortOrder",
            "active",
            "hidden",
            "collapsed",
            "holdingPeriodMinutes",
            "notificationThreshold",
            "notifyWhenThresholdReached",
            "resultPhotoMinCount",
            "availableTaskLimit",
            "bindings")
        .doesNotContainKey("groupBindings");
    assertThat(schemas).containsKeys("DriverQueueRequest", "WorkerQueuePlanRequest");
    assertThat(schemas)
        .containsKey("QueueDefinitionOrderRequest")
        .doesNotContainKeys("WorkQueueRequest", "QueueOrderRequest", "QueueOrderItem");
    assertThat(child(paths, "/warehouses/{warehouseId}/work-queues"))
        .containsOnlyKeys("get", "parameters");
    assertThat(paths)
        .containsKeys(
            "/queue-definitions/order",
            "/warehouses/{warehouseId}/driver-queue",
            "/warehouses/{warehouseId}/work-queues/{queueId}/worker-plan",
            "/warehouses/{warehouseId}/task-board/entries/{entryId}/future-availability",
            "/warehouses/{warehouseId}/task-board/entries/{entryId}/reorder")
        .doesNotContainKeys(
            "/warehouses/{warehouseId}/work-queues/{id}",
            "/warehouses/{warehouseId}/work-queue-order");

    assertThat(list(child(schemas, "QueueBinding").get("required")))
        .contains(
            "order",
            "primary",
            "stopTaskOnTake",
            "participationPolicy",
            "notifyOnPrimaryTake");
    assertThat(list(child(schemas, "QueueBindingRequest").get("required")))
        .contains("order", "stopTaskOnTake", "participationPolicy", "notifyOnPrimaryTake");

    assertThat(list(child(schemas, "QueueDefinition").get("required")))
        .contains("availableTaskLimit");
    assertThat(list(child(schemas, "WorkQueue").get("required")))
        .contains("availableTaskLimit", "workerFeedEnabled");
    assertThat(list(child(schemas, "BoardColumn").get("required")))
        .contains("queueVersion", "availableTaskLimit", "workerFeedEnabled");
    assertThat(list(child(schemas, "ReorderBoardEntryRequest").get("required")))
        .contains("expectedEntryVersion", "expectedQueueVersion", "targetEntryId", "targetIndex");
    assertThat(list(child(schemas, "SetFutureTaskEntryAvailabilityRequest").get("required")))
        .containsExactly("expectedEntryVersion", "available");

    assertThat(child(child(schemas, "GroupMember"), "properties"))
        .doesNotContainKey("roleInGroup");
    assertThat(child(child(schemas, "GroupMemberRequest"), "properties"))
        .doesNotContainKey("roleInGroup");
  }

  @Test
  void taskBoardContractDoesNotExposeMaintenanceOwnedRepairComplexity() throws Exception {
    Map<String, Object> document = openApiDocument();
    Map<String, Object> paths = child(document, "paths");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");

    assertThat(paths)
        .doesNotContainKeys(
            "/internal/task-board/v1/warehouses/{warehouseId}/repair-complexity",
            "/warehouses/{warehouseId}/task-board/kpi-settings/repair-complexity");
    assertThat(schemas)
        .doesNotContainKeys(
            "RepairComplexityThresholds",
            "RepairComplexityThresholdsResponse",
            "SaveRepairComplexityThresholdsRequest");
    assertThat(child(child(schemas, "WarehouseKpiSettings"), "properties"))
        .doesNotContainKey("repairComplexity");
  }

  @Test
  void nativeWorkerSchemasSeparateRawRouteIdentityFromExecutionPackageOrdinal()
      throws Exception {
    Map<String, Object> document = openApiDocument();
    Map<String, Object> schemas = child(child(document, "components"), "schemas");

    Map<String, Object> feedEntry = child(schemas, "WorkerFeedEntry");
    assertThat(list(feedEntry.get("required")))
        .contains("routeIndex", "routeStepIndex", "routeStepCount");
    assertThat(child(feedEntry, "properties"))
        .containsKeys("routeIndex", "routeStepIndex", "routeStepCount");

    Map<String, Object> detail = child(schemas, "WorkerTaskDetail");
    assertThat(list(detail.get("required")))
        .contains("routeIndex", "routeStepIndex", "routeStepCount");
    Map<String, Object> detailProperties = child(detail, "properties");
    assertThat(detailProperties).containsKeys("routeIndex", "routeStepIndex", "routeStepCount");
    assertThat(child(detailProperties, "routeStepCount")).containsEntry("minimum", 1);
  }

  private Set<Endpoint> controllerEndpoints() throws ClassNotFoundException {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

    Set<Endpoint> endpoints = new LinkedHashSet<>();
    for (var component : scanner.findCandidateComponents(API_PACKAGE)) {
      Class<?> controller =
          ClassUtils.forName(component.getBeanClassName(), getClass().getClassLoader());
      RequestMapping controllerMapping =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      List<String> controllerPaths = paths(controllerMapping);

      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping methodMapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) {
          continue;
        }
        assertThat(methodMapping.method())
            .as("HTTP method must be explicit for %s#%s", controller.getName(), method.getName())
            .isNotEmpty();
        for (String controllerPath : controllerPaths) {
          for (String methodPath : paths(methodMapping)) {
            for (RequestMethod requestMethod : methodMapping.method()) {
              endpoints.add(
                  new Endpoint(
                      normalizePath(controllerPath + "/" + methodPath),
                      requestMethod.name().toLowerCase(Locale.ROOT)));
            }
          }
        }
      }
    }
    return endpoints;
  }

  private Map<String, Object> openApiDocument() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/task-board-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private Set<Endpoint> openApiEndpoints(Map<String, Object> document) {
    List<Map<String, Object>> servers = (List<Map<String, Object>>) document.get("servers");
    String serverPrefix = String.valueOf(servers.getFirst().get("url"));
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");
    Set<Endpoint> endpoints = new LinkedHashSet<>();
    for (var pathEntry : paths.entrySet()) {
      Map<String, Object> pathItem = (Map<String, Object>) pathEntry.getValue();
      for (String method : pathItem.keySet()) {
        String normalizedMethod = method.toLowerCase(Locale.ROOT);
        if (OPENAPI_METHODS.contains(normalizedMethod)) {
          endpoints.add(
              new Endpoint(
                  normalizePath(serverPrefix + "/" + pathEntry.getKey()), normalizedMethod));
        }
      }
    }
    return endpoints;
  }

  private Map<String, Object> resolve(Map<String, Object> document, Map<String, Object> value) {
    Object reference = value.get("$ref");
    if (reference == null) {
      return value;
    }
    Object resolved = document;
    for (String segment : reference.toString().substring(2).split("/")) {
      resolved = map(resolved).get(segment);
    }
    return map(resolved);
  }

  private Map<String, Object> child(Map<String, Object> value, String key) {
    return map(value.get(key));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private List<String> paths(RequestMapping mapping) {
    if (mapping == null) {
      return List.of("");
    }
    String[] declared = mapping.path().length == 0 ? mapping.value() : mapping.path();
    return declared.length == 0 ? List.of("") : Arrays.asList(declared);
  }

  private String normalizePath(String path) {
    String normalized = path.replaceAll("/{2,}", "/");
    if (normalized.length() > 1 && normalized.endsWith("/")) {
      return normalized.substring(0, normalized.length() - 1);
    }
    return normalized.startsWith("/") ? normalized : "/" + normalized;
  }

  private record Endpoint(String path, String method) {}
}
