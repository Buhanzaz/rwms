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
        "/api/internal/asset/v1/maintenance/rental-items/{id}/fenced-status",

        "/api/internal/asset/v1/inventory/captures",
        "/api/internal/asset/v1/inventory/captures/{captureId}",
        "/api/internal/asset/v1/inventory/captures/{captureId}/members",
        "/api/internal/asset/v1/inventory/number-resolutions",
        "/api/internal/asset/v1/inventory/validations",
        "/api/internal/asset/v1/inventory/source-assets");
    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    assertThat(list(child(schemas, "RentalItem").get("required")))
        .contains("id", "version", "number", "status", "passport", "tags", "contents");
    assertThat(list(child(schemas, "MaintenanceStatusAction").get("enum")))
        .containsExactly(
            "QUEUE_FOR_REPAIR",
            "COMPLETE_EMPTY_ESTIMATE",
            "MARK_PENDING_ACCEPTANCE",
            "ACCEPT_REPAIR",
            "WRITE_OFF");
    assertThat(child(schemas, "MaintenanceFencedStatusRequest").toString())
        .doesNotContain("RentalItemStatus", "status=");

    assertThat(list(child(schemas, "InventoryCaptureMember").get("required")))
        .contains("assetId", "version", "warehouseId", "status", "displayCanonicalNumber",
            "identityMatchKey", "passportSnapshot", "contentsSnapshot");
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
