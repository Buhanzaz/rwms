package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Verifies the bounded model tool allow-list and its JSON schemas. */
class AssistantToolDefinitionsTest {
  @Test
  void exposesInteractiveSearchReferenceAndSelectionToolsWithBoundedSchemas() {
    AssistantToolDefinitions definitionsFactory = new AssistantToolDefinitions();
    var definitions = definitionsFactory.definitions(true);

    assertThat(definitions)
        .extracting(definition -> definition.name())
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG,
            AssistantToolDefinitions.REMOVE_SELECTED_CABINS);
    assertThat(definitionsFactory.definitions(false))
        .extracting(definition -> definition.name())
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    var search = definitions.get(1).parameters();
    var required = new ArrayList<String>();
    search.path("required").forEach(value -> required.add(value.asText()));
    assertThat(required).containsExactlyInAnyOrder("warehouseId", "groups");
    assertThat(search.path("additionalProperties").booleanValue()).isFalse();
    var group = search.path("properties").path("groups").path("items");
    assertThat(group.has("required")).isFalse();
    assertThat(group.path("properties").path("category").path("type").asText()).isEqualTo("string");
    assertThat(group.path("properties").path("categories").path("type").asText())
        .isEqualTo("array");
    assertThat(group.path("properties").path("categories").path("maxItems").intValue())
        .isEqualTo(3);
    assertThat(search.path("properties").path("totalQuantity").path("type").asText())
        .isEqualTo("integer");
    assertThat(search.path("properties").path("resultMode").path("enum"))
        .extracting(value -> value.asText())
        .containsExactly("APPEND", "REPLACE");
    assertThat(group.path("properties").path("characteristics").path("type").asText())
        .isEqualTo("string");
    assertThat(group.path("properties").path("characteristics").path("maxLength").intValue())
        .isEqualTo(2000);
    assertThat(group.path("properties").path("linoleum").path("type").asText())
        .isEqualTo("boolean");
    assertThat(definitions.getFirst().description())
        .contains("ambiguous", "ТВП to ДВП", "Facets do not prove that a combination is available");
    assertThat(definitions.get(1).description())
        .contains(
            "exact user quantity",
            "Новая",
            "multiple categories",
            "totalQuantity 10",
            "categories [\"Обычная\", \"ИТР\"]",
            "exceed 100",
            "exact returned characteristic",
            "linoleum=false",
            "six БК-1 plus six БК-2",
            "listing facets alone is not an answer",
            "resultMode defaults to REPLACE",
            "exact cabinType",
            "exact finish",
            "only compatible types",
            "six-metre");
    assertThat(definitions.get(2).description())
        .contains(
            "deterministic array order",
            "queues the rest",
            "not an independent branch",
            "SEARCH_MERGE");
    assertThat(definitions.get(3).description())
        .contains("without creating or renewing holds", "number or text", "linoleum");
    assertThat(definitions.getLast().description())
        .contains("current logistics-owned selection", "releases removed holds immediately");
  }
}
