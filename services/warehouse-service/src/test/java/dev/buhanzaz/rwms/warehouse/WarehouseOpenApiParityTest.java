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
  private static final Set<String> OPENAPI_METHODS = Set.of("get", "post", "put", "delete");

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
            "/api/internal/warehouse/v1/warehouses/{id}/existence",
            "/api/internal/warehouse/v1/warehouses/asset/{id}/existence",
            "/api/internal/warehouse/v1/warehouses/inventory/{id}/metadata");
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
            "id", "version", "code", "name", "city", "address", "timeZone", "active", "sortOrder");
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
