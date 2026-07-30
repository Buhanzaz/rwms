package dev.buhanzaz.rwms.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AssistantOpenApiContractTest {
  @Test
  @SuppressWarnings("unchecked")
  void canonicalContractDescribesConversationRestAndSseSurface() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi", "assistant-service.yaml");
    Map<String, Object> document = new Yaml().load(Files.newBufferedReader(contract));

    assertThat(document).containsEntry("openapi", "3.1.1");
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");
    assertThat(paths)
        .containsKeys(
            "/api/assistant/v1/conversations",
            "/api/assistant/v1/conversations/{conversationId}",
            "/api/assistant/v1/conversations/{conversationId}/turns");
    Map<String, Object> components = (Map<String, Object>) document.get("components");
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    Map<String, Object> cabinFacetWarehouse =
        (Map<String, Object>) schemas.get("CabinFacetWarehouse");
    assertThat((List<String>) cabinFacetWarehouse.get("required"))
        .containsExactly(
            "warehouseId", "name", "city", "cabinTypes", "finishes", "dimensions", "categories");
    assertThat((Map<String, Object>) cabinFacetWarehouse.get("properties"))
        .containsKeys(
            "warehouseId", "name", "city", "cabinTypes", "finishes", "dimensions", "categories")
        .doesNotContainKey("code");
    String serialized = Files.readString(contract);
    assertThat(serialized)
        .contains(
            "turn.started",
            "assistant.delta",
            "tool.started",
            "search.result",
            "tool.completed",
            "turn.completed",
            "turn.failed",
            "list_available_cabin_facets",
            "search_available_cabins",
            "request_search_merge_confirmation",
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
            "fresh cabin-search attempt",
            "technical failure",
            "enum: [FREE]");
    assertThat(serialized).doesNotContain("ALL_RENTABLE", "NEW_ONLY", "enum: [FREE, NEW]");
    Map<String, Object> cabinSearchToolResult =
        (Map<String, Object>) schemas.get("CabinSearchToolResult");
    assertThat((List<String>) cabinSearchToolResult.get("required"))
        .containsExactly("tool", "resultMode", "notices", "data");
    Map<String, Object> cabinSearchGroup = (Map<String, Object>) schemas.get("CabinSearchGroup");
    assertThat((Map<String, Object>) cabinSearchGroup.get("properties"))
        .containsKeys("category", "categories");
    Map<String, Object> message = (Map<String, Object>) schemas.get("Message");
    assertThat((List<String>) message.get("required"))
        .contains("searchNotices");
  }
}
