package dev.buhanzaz.rwms.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.assistant.api.AssistantConversationController;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Strictly parses and verifies the canonical public assistant contract. */
class AssistantOpenApiContractTest {
  private static final Set<String> OPENAPI_METHODS = Set.of("get", "post", "put", "delete");

  @Test
  @SuppressWarnings("unchecked")
  void canonicalContractDescribesConversationRestAndSseSurface() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi", "assistant-service.yaml");
    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    Map<String, Object> document =
        new Yaml(new SafeConstructor(loaderOptions)).load(Files.newBufferedReader(contract));

    assertThat(document).containsEntry("openapi", "3.1.1");
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");
    assertThat(paths)
        .containsKeys(
            "/api/assistant/v1/conversations",
            "/api/assistant/v1/conversations/{conversationId}",
            "/api/assistant/v1/conversations/{conversationId}/turns",
            "/api/assistant/v1/conversations/{conversationId}/selection");
    Map<String, Object> conversationsPath =
        (Map<String, Object>) paths.get("/api/assistant/v1/conversations");
    Map<String, Object> listOperation = (Map<String, Object>) conversationsPath.get("get");
    assertThat((List<Map<String, Object>>) listOperation.get("parameters"))
        .singleElement()
        .satisfies(
            parameter -> {
              assertThat(parameter)
                  .containsEntry("name", "rentalOrderId")
                  .containsEntry("in", "query")
                  .containsEntry("required", false);
              assertThat((Map<String, Object>) parameter.get("schema"))
                  .containsEntry("type", "string")
                  .containsEntry("format", "uuid");
            });
    Map<String, Object> components = (Map<String, Object>) document.get("components");
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    Map<String, Object> cabinFacetWarehouse =
        (Map<String, Object>) schemas.get("CabinFacetWarehouse");
    assertThat((List<String>) cabinFacetWarehouse.get("required"))
        .containsExactly(
            "warehouseId",
            "name",
            "city",
            "cabinTypes",
            "finishes",
            "dimensions",
            "categories",
            "characteristics",
            "typeDimensions");
    assertThat((Map<String, Object>) cabinFacetWarehouse.get("properties"))
        .containsKeys(
            "warehouseId",
            "name",
            "city",
            "cabinTypes",
            "finishes",
            "dimensions",
            "categories",
            "characteristics",
            "typeDimensions")
        .doesNotContainKey("code");
    String serialized = Files.readString(contract);
    assertThat(serialized)
        .contains(
            "turn.started",
            "assistant.delta",
            "tool.started",
            "search.result",
            "clarification.requested",
            "clarification.answered",
            "selection.updated",
            "tool.completed",
            "turn.completed",
            "turn.failed",
            "list_available_cabin_facets",
            "search_available_cabins",
            "request_cabin_clarifications",
            "lookup_cabin_catalog",
            "remove_selected_cabins",
            "INQUIRY_ARCHIVED",
            "CABINS_NOT_FOUND",
            "CABINS_PARTIALLY_FOUND",
            "resultMode",
            "searchNotices",
            "APPEND",
            "REPLACE",
            "categories",
            "category",
            "characteristics",
            "linoleum",
            "filterSuggestions",
            "ClarificationQuestion",
            "sequenceNumber",
            "QUEUED",
            "rentalOrderId",
            "one active conversation linked to that order",
            "CabinSelection",
            "LEGAL_ENTITY",
            "enum: [FREE]");
    assertThat(serialized).doesNotContain("request_search_merge_confirmation", "SOLE_PROPRIETOR");
    assertThat(serialized).doesNotContain("ALL_RENTABLE", "NEW_ONLY", "enum: [FREE, NEW]");
    Map<String, Object> cabinSearchToolResult =
        (Map<String, Object>) schemas.get("CabinSearchToolResult");
    assertThat((List<String>) cabinSearchToolResult.get("required"))
        .containsExactly("tool", "resultMode", "filterSuggestions", "notices", "data");
    Map<String, Object> cabinSearchGroup = (Map<String, Object>) schemas.get("CabinSearchGroup");
    assertThat((Map<String, Object>) cabinSearchGroup.get("properties"))
        .containsKeys("category", "categories");
    Map<String, Object> message = (Map<String, Object>) schemas.get("Message");
    assertThat((List<String>) message.get("required")).contains("searchNotices");
    Map<String, Object> conversation = (Map<String, Object>) schemas.get("Conversation");
    assertThat((List<String>) conversation.get("required")).contains("rentalOrderId");
    assertThat((Map<String, Object>) conversation.get("properties")).containsKey("rentalOrderId");
    Map<String, Object> createConversation =
        (Map<String, Object>) schemas.get("CreateConversationRequest");
    assertThat((Map<String, Object>) createConversation.get("properties"))
        .containsKey("rentalOrderId");
    assertThat((List<Map<String, Object>>) createConversation.get("allOf"))
        .singleElement()
        .satisfies(
            rule -> {
              assertThat((Map<String, Object>) rule.get("if"))
                  .containsEntry("required", List.of("rentalOrderId"));
              assertThat((Map<String, Object>) rule.get("then"))
                  .containsEntry("required", List.of("clientId"));
            });
    Map<String, Object> clarification = (Map<String, Object>) schemas.get("ClarificationQuestion");
    assertThat((List<String>) clarification.get("required")).contains("sequenceNumber");
    assertThat(
            (List<String>)
                ((Map<String, Object>)
                        ((Map<String, Object>) clarification.get("properties")).get("status"))
                    .get("enum"))
        .containsExactly("QUEUED", "PENDING", "ANSWERED", "SUPERSEDED");
    Map<String, Object> selection = (Map<String, Object>) schemas.get("CabinSelection");
    Map<String, Object> selectionItems =
        (Map<String, Object>) ((Map<String, Object>) selection.get("properties")).get("items");
    assertThat((Map<String, Object>) selectionItems.get("items"))
        .containsEntry("$ref", "#/components/schemas/AvailableCabin");
    Map<String, Object> removeSelection =
        (Map<String, Object>) schemas.get("RemoveSelectedCabinsToolResult");
    Map<String, Object> removeData =
        (Map<String, Object>) ((Map<String, Object>) removeSelection.get("properties")).get("data");
    Map<String, Object> removeItems =
        (Map<String, Object>) ((Map<String, Object>) removeData.get("properties")).get("items");
    assertThat((Map<String, Object>) removeItems.get("items"))
        .containsEntry("$ref", "#/components/schemas/AvailableCabin");
    Map<String, Object> newClient = (Map<String, Object>) schemas.get("NewClient");
    Map<String, Object> newClientProperties = (Map<String, Object>) newClient.get("properties");
    assertThat(((Map<String, Object>) newClientProperties.get("clientType")).get("enum"))
        .isEqualTo(List.of("INDIVIDUAL", "LEGAL_ENTITY"));
    assertThat(
            (Map<String, Object>) ((Map<String, Object>) newClient.get("properties")).get("phone"))
        .containsEntry("pattern", "^(?:\\+|8)[0-9() .-]{6,31}$");
    assertThat(openApiEndpoints(document))
        .containsExactlyInAnyOrderElementsOf(controllerEndpoints());
    assertAllLocalReferencesResolve(document, document);
  }

  private static Set<Endpoint> controllerEndpoints() {
    RequestMapping controllerMapping =
        AnnotatedElementUtils.findMergedAnnotation(
            AssistantConversationController.class, RequestMapping.class);
    Set<Endpoint> endpoints = new LinkedHashSet<>();
    for (Method method : AssistantConversationController.class.getDeclaredMethods()) {
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
    return endpoints;
  }

  private static Set<Endpoint> openApiEndpoints(Map<String, Object> document) {
    Set<Endpoint> endpoints = new LinkedHashSet<>();
    Map<String, Object> paths = map(document.get("paths"));
    for (Map.Entry<String, Object> path : paths.entrySet()) {
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

  private static void assertAllLocalReferencesResolve(Object node, Map<String, Object> root) {
    if (node instanceof Map<?, ?> object) {
      Object reference = object.get("$ref");
      if (reference instanceof String path && path.startsWith("#/")) {
        Object resolved = root;
        for (String segment : path.substring(2).split("/")) {
          resolved = map(resolved).get(segment.replace("~1", "/").replace("~0", "~"));
          assertThat(resolved).as("resolved %s", path).isNotNull();
        }
      }
      object.values().forEach(value -> assertAllLocalReferencesResolve(value, root));
    } else if (node instanceof Collection<?> values) {
      values.forEach(value -> assertAllLocalReferencesResolve(value, root));
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
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  /** One normalized HTTP method/path pair used by the assistant parity comparison. */
  private record Endpoint(String path, String method) {}
}
