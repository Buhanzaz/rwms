package dev.buhanzaz.rwms.warehouse;

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

class WarehouseOpenApiParityTest {
  private static final String API_PACKAGE = "dev.buhanzaz.rwms.warehouse.api";
  private static final Set<String> OPENAPI_METHODS = Set.of("get", "post", "put");

  @Test
  void openApiInventoryExactlyMatchesWarehouseControllers() throws Exception {
    assertThat(openApiEndpoints(openApi()))
        .containsExactlyInAnyOrderElementsOf(controllerEndpoints());
  }

  @Test
  void publicAndInternalContractKeepsItsStrictLifecycleAndExistenceShapes() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");
    assertThat(paths)
        .containsKeys(
            "/api/warehouse/v1/warehouses",
            "/api/warehouse/v1/warehouses/{id}",
            "/api/warehouse/v1/warehouses/{servedWarehouseId}/support-links",
            "/api/warehouse/v1/warehouses/{id}/draining",
            "/api/warehouse/v1/warehouses/{id}/inactivation",
            "/api/warehouse/v1/warehouses/{id}/time-zone-changes",
            "/api/warehouse/v1/admin/outbox-events/{eventId}/recovery",
            "/api/internal/warehouse/v1/warehouses/{id}/existence",
            "/api/internal/warehouse/v1/warehouses/{id}/identity",
            "/api/internal/warehouse/v1/warehouses/asset/{id}/existence",
            "/api/internal/warehouse/v1/warehouses/inventory/{id}/metadata",
            "/api/internal/warehouse/v1/warehouses/logistics/{id}/identity",
            "/api/internal/warehouse/v1/warehouses/logistics/{servedWarehouseId}/support-links",
            "/api/internal/warehouse/v1/warehouses/{id}/time-zone",
            "/api/internal/warehouse/v1/warehouses/{id}/operation-marks",
            "/api/internal/warehouse/v1/warehouses/{id}/admission",
            "/api/internal/warehouse/v1/warehouses/{id}/lifecycle-readiness",
            "/api/internal/warehouse/v1/lifecycle/readiness-work");
    Map<String, Object> create = child(child(paths, "/api/warehouse/v1/warehouses"), "post");
    assertThat(list(create.get("parameters")).getFirst())
        .isInstanceOfSatisfying(
            Map.class,
            parameter ->
                assertThat(((Map<?, ?>) parameter).get("$ref"))
                    .isEqualTo("#/components/parameters/IdempotencyKey"));
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    assertThat(list(child(schemas, "Warehouse").get("required")))
        .containsExactlyInAnyOrder(
            "id",
            "version",
            "name",
            "city",
            "address",
            "latitude",
            "longitude",
            "timeZone",
            "active",
            "lifecycleState",
            "sortOrder",
            "representative");
    assertThat(list(child(schemas, "CreateWarehouseRequest").get("required")))
        .doesNotContain("representative", "latitude", "longitude");
    assertThat(
            child(
                    child(child(schemas, "CreateWarehouseRequest"), "properties"),
                    "representative")
                .get("default"))
        .isEqualTo(false);
    assertThat(list(child(schemas, "ReplaceWarehouseRequest").get("required")))
        .containsExactlyInAnyOrder("expectedVersion", "name", "city", "timeZone");
    assertThat(child(child(schemas, "ReplaceWarehouseRequest"), "properties"))
        .doesNotContainKey("active");
    assertThat(
            child(
                    child(child(schemas, "ReplaceWarehouseRequest"), "properties"),
                    "representative")
                .get("default"))
        .isEqualTo(false);
    assertThat(list(child(schemas, "InternalWarehouseExistence").get("required")))
        .containsExactlyInAnyOrder("id", "version", "active");
    assertThat(child(schemas, "InternalWarehouseExistence").get("additionalProperties"))
        .isEqualTo(false);
    assertThat(list(child(schemas, "InventoryWarehouseMetadata").get("required")))
        .containsExactlyInAnyOrder("id", "version", "active", "timeZone");
    assertThat(child(schemas, "InventoryWarehouseMetadata").get("additionalProperties"))
        .isEqualTo(false);
    assertThat(
            child(child(child(schemas, "InventoryWarehouseMetadata"), "properties"), "active")
                .get("const"))
        .isEqualTo(true);
    assertThat(list(child(schemas, "LogisticsWarehouseIdentity").get("required")))
        .containsExactlyInAnyOrder(
            "id",
            "version",
            "active",
            "name",
            "city",
            "address",
            "latitude",
            "longitude",
            "timeZone",
            "representative");
    assertThat(child(schemas, "LogisticsWarehouseIdentity").get("additionalProperties"))
        .isEqualTo(false);
    assertThat(child(child(schemas, "LogisticsWarehouseIdentity"), "properties"))
        .containsKeys(
            "id",
            "version",
            "active",
            "name",
            "city",
            "address",
            "latitude",
            "longitude",
            "timeZone",
            "representative");
    assertThat(list(child(schemas, "ReplaceWarehouseSupportLinksRequest").get("required")))
        .containsExactlyInAnyOrder("expectedVersion", "links");
    assertThat(list(child(schemas, "WarehouseSupportLinks").get("required")))
        .containsExactlyInAnyOrder("servedWarehouseId", "warehouseVersion", "links");
    assertThat(list(child(schemas, "WarehouseSupportLink").get("required")))
        .contains(
            "id",
            "version",
            "supportWarehouseId",
            "servedWarehouseId",
            "allowDrivers",
            "allowDirectFulfillment");
    assertThat(list(child(schemas, "Weekday").get("enum")))
        .containsExactly(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    assertThat(list(child(schemas, "ScheduleWarehouseTimeZoneRequest").get("required")))
        .containsExactlyInAnyOrder("expectedVersion", "timeZone", "effectiveFrom");
    assertThat(list(child(schemas, "WarehouseTimeZoneChange").get("required")))
        .containsExactlyInAnyOrder("warehouseId", "warehouseVersion", "timeZone", "effectiveFrom");
    assertThat(list(child(schemas, "WarehouseTimeZoneAt").get("required")))
        .containsExactlyInAnyOrder("warehouseId", "timeZone", "effectiveFrom");
    assertThat(list(child(schemas, "WarehouseOperationMarkRequest").get("required")))
        .containsExactlyInAnyOrder("operationId", "occurredAt");
    assertThat(list(child(schemas, "WarehouseOutboxRecoveryRequest").get("required")))
        .containsExactlyInAnyOrder("expectedReviewVersion", "reason");
    assertThat(list(child(schemas, "WarehouseOutboxStatus").get("enum")))
        .containsExactly("PENDING", "IN_FLIGHT", "PUBLISHED", "DLT", "QUARANTINED");
    assertThat(list(child(schemas, "WarehouseOutboxRecovery").get("required")))
        .containsExactlyInAnyOrder(
            "eventId",
            "aggregateType",
            "aggregateId",
            "aggregateVersion",
            "status",
            "attemptCount",
            "reviewVersion",
            "lastErrorCode",
            "reviewedBySubjectId",
            "recoveryReason",
            "recoveredAt");
    assertThat(list(child(schemas, "WarehouseLifecycleTransitionRequest").get("required")))
        .containsExactly("expectedVersion");
    assertThat(list(child(schemas, "WarehouseLifecycleReadinessRequest").get("required")))
        .containsExactly("expectedVersion");
    assertThat(list(child(schemas, "WarehouseLifecycleState").get("enum")))
        .containsExactly("ACTIVE", "DRAINING", "INACTIVE");
    assertThat(list(child(schemas, "WarehouseOperationDirection").get("enum")))
        .containsExactly("INCOMING", "OUTGOING");
    assertThat(list(child(schemas, "WarehouseOperationAdmission").get("required")))
        .containsExactlyInAnyOrder(
            "warehouseId", "warehouseVersion", "lifecycleState", "direction", "admitted");
    assertThat(list(child(schemas, "WarehouseLifecycleReadinessConfirmation").get("required")))
        .containsExactlyInAnyOrder(
            "warehouseId", "warehouseVersion", "lifecycleState", "readinessOwner", "confirmedAt");
    assertThat(list(child(schemas, "WarehouseLifecycleReadinessWork").get("required")))
        .containsExactlyInAnyOrder("warehouseId", "warehouseVersion", "lifecycleState");
    assertThat(list(child(schemas, "WarehouseLifecycleReadinessWorkPage").get("required")))
        .containsExactlyInAnyOrder("items", "nextAfter");
    assertThat(child(paths, "/api/warehouse/v1/warehouses/{id}")).doesNotContainKey("delete");
    assertAllLocalReferencesResolve(document, document);
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
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping methodMapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) continue;
        assertThat(methodMapping.method()).isNotEmpty();
        for (String controllerPath : paths(controllerMapping)) {
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

  private Map<String, Object> openApi() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/warehouse-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private Set<Endpoint> openApiEndpoints(Map<String, Object> document) {
    Set<Endpoint> endpoints = new LinkedHashSet<>();
    for (var path : child(document, "paths").entrySet()) {
      Map<String, Object> item = map(path.getValue());
      for (String method : item.keySet()) {
        if (OPENAPI_METHODS.contains(method.toLowerCase(Locale.ROOT))) {
          endpoints.add(
              new Endpoint(normalizePath(path.getKey()), method.toLowerCase(Locale.ROOT)));
        }
      }
    }
    return endpoints;
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

  private static List<String> paths(RequestMapping mapping) {
    if (mapping == null) return List.of("");
    String[] declared = mapping.path().length == 0 ? mapping.value() : mapping.path();
    return declared.length == 0 ? List.of("") : Arrays.asList(declared);
  }

  private static String normalizePath(String value) {
    String normalized = value.replaceAll("/{2,}", "/");
    if (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
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
