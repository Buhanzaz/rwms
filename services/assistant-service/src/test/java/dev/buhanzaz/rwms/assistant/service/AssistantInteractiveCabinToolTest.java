package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationKind;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Focused hard-gate, independent-choice, append and read-only reference tool coverage. */
class AssistantInteractiveCabinToolTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void finishOnlyRequestCreatesTypeButtonsAndNeverSearchesOrHolds() {
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID toolCallId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны"),
                List.of("ЛДСП", "ОСБ"),
                List.of("6x2.4"),
                List.of(
                    relation("Модуль", "6x2.4"),
                    relation("Пост охраны", "6x2.4"))));
    when(clarifications.create(
            eq(conversationId), eq(messageId), eq(toolCallId), eq(warehouseId), any()))
        .thenReturn(List.of(question("finish:ldsp", "CABIN_TYPE", "PENDING")));
    AssistantCabinSearchTool tool = searchTool(logistics, clarifications, selections);

    JsonNode result =
        tool.search(
            conversationId,
            inquiryId,
            messageId,
            toolCallId,
            mapper.readTree(
                """
                {"warehouseId":"%s","groups":[{"finish":"ЛДСП","quantity":2}]}
                """
                    .formatted(warehouseId)),
            "bearer",
            ignored -> {});

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AssistantClarificationService.QuestionDraft>> drafts =
        ArgumentCaptor.forClass(List.class);
    verify(clarifications)
        .create(eq(conversationId), eq(messageId), eq(toolCallId), eq(warehouseId), drafts.capture());
    assertThat(drafts.getValue())
        .singleElement()
        .satisfies(
            draft -> {
              assertThat(draft.kind()).isEqualTo(AssistantClarificationKind.CABIN_TYPE);
              assertThat(draft.prompt()).contains("ЛДСП");
              assertThat(draft.options())
                  .extracting(AssistantClarificationService.OptionDraft::value)
                  .containsExactly("Модуль", "Пост охраны");
            });
    assertThat(result.path("tool").asText())
        .isEqualTo(AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS);
    verify(logistics, never()).searchAvailableCabins(any(), any(), any(), any());
    verifyNoInteractions(selections);
  }

  @Test
  void selectedTypeWithSeveralRelatedSizesCreatesOnlyItsExactSizeButtons() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны"),
                List.of("ЛДСП"),
                List.of("4x2.4", "6x2.4", "3x2.4"),
                List.of(
                    relation("Модуль", "4x2.4", "6x2.4"),
                    relation("Пост охраны", "3x2.4"))));
    when(clarifications.create(any(), any(), any(), eq(warehouseId), any()))
        .thenReturn(List.of(question("module:size", "DIMENSIONS", "PENDING")));

    searchTool(logistics, clarifications, selections)
        .search(
            UUID.randomUUID(),
            inquiryId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            mapper.readTree(
                """
                {"warehouseId":"%s","groups":[
                  {"cabinType":"Модуль","finish":"ЛДСП","quantity":1}
                ]}
                """
                    .formatted(warehouseId)),
            "bearer",
            ignored -> {});

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AssistantClarificationService.QuestionDraft>> drafts =
        ArgumentCaptor.forClass(List.class);
    verify(clarifications).create(any(), any(), any(), eq(warehouseId), drafts.capture());
    assertThat(drafts.getValue().getFirst().kind())
        .isEqualTo(AssistantClarificationKind.DIMENSIONS);
    assertThat(drafts.getValue().getFirst().options())
        .extracting(AssistantClarificationService.OptionDraft::value)
        .containsExactly("4x2.4", "6x2.4")
        .doesNotContain("3x2.4");
    verify(logistics, never()).searchAvailableCabins(any(), any(), any(), any());
  }

  @Test
  void missingTypeOffersOnlyTypesRelatedToTheRequestedSixMetreDimension() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны", "Прорабская"),
                List.of("ЛДСП"),
                List.of("6x2.4", "3x2.4"),
                List.of(
                    relation("Модуль", "6x2.4"),
                    relation("Пост охраны", "6x2.4"),
                    relation("Прорабская", "3x2.4"))));
    when(clarifications.create(any(), any(), any(), eq(warehouseId), any()))
        .thenReturn(List.of(question("six:type", "CABIN_TYPE", "PENDING")));

    searchTool(logistics, clarifications, selections)
        .search(
            UUID.randomUUID(),
            inquiryId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            mapper.readTree(
                """
                {"warehouseId":"%s","groups":[
                  {"finish":"ЛДСП","dimensions":"6 метров","quantity":1}
                ]}
                """
                    .formatted(warehouseId)),
            "bearer",
            ignored -> {});

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AssistantClarificationService.QuestionDraft>> drafts =
        ArgumentCaptor.forClass(List.class);
    verify(clarifications).create(any(), any(), any(), eq(warehouseId), drafts.capture());
    assertThat(drafts.getValue().getFirst().options())
        .extracting(AssistantClarificationService.OptionDraft::value)
        .containsExactly("Модуль", "Пост охраны")
        .doesNotContain("Прорабская");
    verify(logistics, never()).searchAvailableCabins(any(), any(), any(), any());
  }

  @Test
  void missingTypeAutoResolvesTheOnlyTypeRelatedToSixMetres() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны"),
                List.of("ЛДСП"),
                List.of("6x2.4", "3x2.4"),
                List.of(
                    relation("Модуль", "6x2.4"),
                    relation("Пост охраны", "3x2.4"))));
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), any(), eq("bearer")))
        .thenAnswer(
            invocation ->
                searchResponse(
                    warehouseId, invocation.getArgument(2), List.of(UUID.randomUUID())));

    searchTool(logistics, clarifications, selections)
        .search(
            UUID.randomUUID(),
            inquiryId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            mapper.readTree(
                """
                {"warehouseId":"%s","groups":[
                  {"finish":"ЛДСП","dimensions":"6 метров","quantity":1}
                ]}
                """
                    .formatted(warehouseId)),
            "bearer",
            ignored -> {});

    ArgumentCaptor<LogisticsClient.CabinSearch> request =
        ArgumentCaptor.forClass(LogisticsClient.CabinSearch.class);
    verify(logistics).searchAvailableCabins(eq(inquiryId), any(), request.capture(), eq("bearer"));
    assertThat(request.getValue().groups().getFirst().cabinType()).isEqualTo("Модуль");
    assertThat(request.getValue().groups().getFirst().dimensions()).isEqualTo("6x2.4");
    verifyNoInteractions(clarifications);
  }

  @Test
  void sixMetresResolvesOnlyInsideCompatibleTypeAndSuggestionsComeFromFacets() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны"),
                List.of("ЛДСП"),
                List.of("4x2.4", "6x2.4", "3x2.4"),
                List.of(
                    relation("Модуль", "4x2.4", "6x2.4"),
                    relation("Пост охраны", "3x2.4"))));
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), any(), eq("bearer")))
        .thenAnswer(
            invocation ->
                searchResponse(
                    warehouseId,
                    invocation.getArgument(2),
                    List.of(UUID.randomUUID())));

    JsonNode result =
        searchTool(logistics, clarifications, selections)
            .search(
                UUID.randomUUID(),
                inquiryId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                mapper.readTree(
                    """
                    {"warehouseId":"%s","groups":[
                      {"cabinType":"Модуль","finish":"ЛДСП","dimensions":"6 метров","quantity":1}
                    ]}
                    """
                        .formatted(warehouseId)),
                "bearer",
                ignored -> {});

    ArgumentCaptor<LogisticsClient.CabinSearch> request =
        ArgumentCaptor.forClass(LogisticsClient.CabinSearch.class);
    verify(logistics).searchAvailableCabins(eq(inquiryId), any(), request.capture(), eq("bearer"));
    assertThat(request.getValue().groups().getFirst().dimensions()).isEqualTo("6x2.4");
    assertThat(result.path("filterSuggestions").path("cabinTypes"))
        .extracting(JsonNode::asText)
        .containsExactly("Модуль", "Пост охраны");
    assertThat(result.path("filterSuggestions").path("characteristics"))
        .extracting(JsonNode::asText)
        .containsExactly("Электрика", "Линолеум");

    assertThatThrownBy(
            () ->
                searchTool(logistics, clarifications, selections)
                    .search(
                        UUID.randomUUID(),
                        inquiryId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        mapper.readTree(
                            """
                            {"warehouseId":"%s","groups":[
                              {"cabinType":"Пост охраны","finish":"ЛДСП","dimensions":"6 метров","quantity":1}
                            ]}
                            """
                                .formatted(warehouseId)),
                        "bearer",
                        ignored -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void explicitClarificationCannotHideAnAuthoritativeChoice() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны", "Прорабская"),
                List.of("ЛДСП"),
                List.of("6x2.4"),
                List.of(
                    relation("Модуль", "6x2.4"),
                    relation("Пост охраны", "6x2.4"),
                    relation("Прорабская", "6x2.4"))));
    AssistantCabinSearchTool tool = searchTool(logistics, clarifications, selections);

    assertThatThrownBy(
            () ->
                tool.requestClarifications(
                    UUID.randomUUID(),
                    inquiryId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    mapper.readTree(
                        """
                        {"warehouseId":"%s","questions":[{
                          "branchKey":"type","kind":"CABIN_TYPE","prompt":"Выберите тип",
                          "options":["Модуль","Пост охраны"]
                        }]}
                        """
                            .formatted(warehouseId)),
                    "bearer",
                    ignored -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("every exact current choice");
    verifyNoInteractions(clarifications);
  }

  @Test
  void explicitClarificationRejectsDuplicateOptionsEvenWhenItsSetLooksComplete() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны"),
                List.of("ЛДСП"),
                List.of("6x2.4"),
                List.of(
                    relation("Модуль", "6x2.4"),
                    relation("Пост охраны", "6x2.4"))));

    assertThatThrownBy(
            () ->
                searchTool(logistics, clarifications, selections)
                    .requestClarifications(
                        UUID.randomUUID(),
                        inquiryId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        mapper.readTree(
                            """
                            {"warehouseId":"%s","questions":[{
                              "branchKey":"type","kind":"CABIN_TYPE","prompt":"Выберите тип",
                              "options":["Модуль","Пост охраны","Модуль"]
                            }]}
                            """
                                .formatted(warehouseId)),
                        "bearer",
                        ignored -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(clarifications);
  }

  @Test
  void exactAppendUsesOneSearchAndPreservesPriorSelectionWithoutProbeExtras() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID oldId = UUID.randomUUID();
    UUID addedId = UUID.randomUUID();
    Set<UUID> authoritative = new LinkedHashSet<>(List.of(oldId));
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль"),
                List.of("ОСБ"),
                List.of("6x2.4"),
                List.of(relation("Модуль", "6x2.4"))));
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), any(), eq("bearer")))
        .thenAnswer(
            invocation -> {
              LogisticsClient.CabinSearch request = invocation.getArgument(2);
              assertThat(request.resultMode()).isEqualTo(LogisticsClient.SearchResultMode.APPEND);
              authoritative.add(addedId);
              return searchResponse(warehouseId, request, List.of(addedId));
            });

    JsonNode result =
        searchTool(logistics, clarifications, selections)
            .search(
                UUID.randomUUID(),
                inquiryId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                mapper.readTree(
                    """
                    {"warehouseId":"%s","resultMode":"APPEND","groups":[
                      {"cabinType":"Модуль","finish":"ОСБ","dimensions":"6x2.4","quantity":1}
                    ]}
                    """
                        .formatted(warehouseId)),
                "bearer",
                ignored -> {});

    verify(logistics).searchAvailableCabins(eq(inquiryId), any(), any(), eq("bearer"));
    verify(selections, never()).replace(any(), any(), any(), any());
    assertThat(authoritative).containsExactly(oldId, addedId);
    assertThat(result.path("data").path("groups").get(0).path("cabins"))
        .extracting(cabin -> UUID.fromString(cabin.path("id").asText()))
        .containsExactly(addedId);
  }

  @Test
  void appendRejectsSharedAllocationBeforeAnyMutatingProbe() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
    AssistantSelectionService selections = mock(AssistantSelectionService.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль", "Пост охраны"),
                List.of("ЛДСП"),
                List.of("6x2.4"),
                List.of(
                    relation("Модуль", "6x2.4"),
                    relation("Пост охраны", "6x2.4"))));

    assertThatThrownBy(
            () ->
                searchTool(logistics, clarifications, selections)
                    .search(
                        UUID.randomUUID(),
                        inquiryId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        mapper.readTree(
                            """
                            {"warehouseId":"%s","resultMode":"APPEND","totalQuantity":2,"groups":[
                              {"cabinType":"Модуль","finish":"ЛДСП","dimensions":"6x2.4"},
                              {"cabinType":"Пост охраны","finish":"ЛДСП","dimensions":"6x2.4"}
                            ]}
                            """
                                .formatted(warehouseId)),
                        "bearer",
                        ignored -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit quantity");
    verify(logistics, never()).searchAvailableCabins(any(), any(), any(), any());
  }

  @Test
  void referenceLookupReturnsLinoleumFactsWithoutAvailabilitySearchOrHold() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль"),
                List.of("ЛДСП"),
                List.of("6x2.4"),
                List.of(relation("Модуль", "6x2.4"))));
    when(logistics.lookupCabinCatalog(inquiryId, warehouseId, "Линолеум", 0, 20, "bearer"))
        .thenReturn(
            mapper.readTree(
                """
                {"warehouseId":"%s","content":[{"id":"%s","warehouseId":"%s","number":"CAB-7","linoleum":true}],
                 "page":0,"size":20,"totalElements":1,"totalPages":1}
                """
                    .formatted(warehouseId, UUID.randomUUID(), warehouseId)));
    AssistantCabinReferenceTool tool = new AssistantCabinReferenceTool(logistics);

    JsonNode result =
        tool.execute(
            inquiryId,
            mapper.readTree(
                """
                {"warehouseId":"%s","query":"Линолеум"}
                """
                    .formatted(warehouseId)),
            "bearer");

    assertThat(result.path("tool").asText())
        .isEqualTo(AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    assertThat(result.path("data").path("catalog").path("content").get(0).path("linoleum").asBoolean())
        .isTrue();
    verify(logistics, never()).searchAvailableCabins(any(), any(), any(), any());
  }

  @Test
  void referenceLookupRejectsMalformedCatalogPageWithoutAvailabilitySearch() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID duplicatedId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer"))
        .thenReturn(
            facets(
                warehouseId,
                List.of("Модуль"),
                List.of("ЛДСП"),
                List.of("6x2.4"),
                List.of(relation("Модуль", "6x2.4"))));
    when(logistics.lookupCabinCatalog(inquiryId, warehouseId, "CAB-7", 0, 20, "bearer"))
        .thenReturn(
            mapper.readTree(
                """
                {"warehouseId":"%s","content":[
                  {"id":"%s","warehouseId":"%s","number":"CAB-7"},
                  {"id":"%s","warehouseId":"%s","number":"CAB-7"}],
                 "page":0,"size":20,"totalElements":2,"totalPages":1}
                """
                    .formatted(
                        warehouseId,
                        duplicatedId,
                        warehouseId,
                        duplicatedId,
                        UUID.randomUUID())));

    assertThatThrownBy(
            () ->
                new AssistantCabinReferenceTool(logistics)
                    .execute(
                        inquiryId,
                        mapper.readTree(
                            """
                            {"warehouseId":"%s","query":"CAB-7"}
                            """
                                .formatted(warehouseId)),
                        "bearer"))
        .isInstanceOf(AssistantUpstreamException.class)
        .hasMessage("Logistics returned an invalid cabin catalog page");
    verify(logistics, never()).searchAvailableCabins(any(), any(), any(), any());
  }

  @Test
  void exactNumberLookupReturnsOnlyTheRequestedWarehouseWithoutAvailabilitySearch() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID unrelatedWarehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    ObjectNode facetResponse =
        (ObjectNode)
            facets(
                warehouseId,
                List.of("Модуль"),
                List.of("ЛДСП"),
                List.of("6x2.4"),
                List.of(relation("Модуль", "6x2.4")));
    JsonNode unrelated =
        facets(
                unrelatedWarehouseId,
                List.of("Пост охраны"),
                List.of("ОСБ"),
                List.of("3x2.4"),
                List.of(relation("Пост охраны", "3x2.4")))
            .path("warehouses")
            .get(0);
    ((ArrayNode) facetResponse.path("warehouses")).add(unrelated.deepCopy());
    when(logistics.listAvailableCabinFacets(inquiryId, "bearer")).thenReturn(facetResponse);
    when(logistics.lookupCabinCatalog(inquiryId, warehouseId, "CAB-0042", 0, 20, "bearer"))
        .thenReturn(
            mapper.readTree(
                """
                {"warehouseId":"%s","content":[{"id":"%s","warehouseId":"%s","number":"CAB-0042"}],
                 "page":0,"size":20,"totalElements":1,"totalPages":1}
                """
                    .formatted(warehouseId, UUID.randomUUID(), warehouseId)));

    JsonNode result =
        new AssistantCabinReferenceTool(logistics)
            .execute(
                inquiryId,
                mapper.readTree(
                    """
                    {"warehouseId":"%s","query":"CAB-0042"}
                    """
                        .formatted(warehouseId)),
                "bearer");

    assertThat(result.path("data").path("catalog").path("content").get(0).path("number").asText())
        .isEqualTo("CAB-0042");
    assertThat(result.path("data").path("facets").path("warehouses"))
        .singleElement()
        .satisfies(
            warehouse ->
                assertThat(warehouse.path("warehouseId").asText())
                    .isEqualTo(warehouseId.toString()));
    verify(logistics, never()).searchAvailableCabins(any(), any(), any(), any());
  }

  private AssistantCabinSearchTool searchTool(
      LogisticsClient logistics,
      AssistantClarificationService clarifications,
      AssistantSelectionService selections) {
    return new AssistantCabinSearchTool(logistics, clarifications, selections, mapper);
  }

  private JsonNode facets(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<TypeRelation> typeRelations) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode warehouse = root.putArray("warehouses").addObject();
    warehouse.put("warehouseId", warehouseId.toString());
    warehouse.put("name", "Склад");
    warehouse.put("city", "Москва");
    cabinTypes.forEach(warehouse.putArray("cabinTypes")::add);
    finishes.forEach(warehouse.putArray("finishes")::add);
    dimensions.forEach(warehouse.putArray("dimensions")::add);
    List.of("Новая", "Обычная").forEach(warehouse.putArray("categories")::add);
    List.of("Электрика", "Линолеум")
        .forEach(warehouse.putArray("characteristics")::add);
    ArrayNode relations = warehouse.putArray("typeDimensions");
    for (TypeRelation relation : typeRelations) {
      ObjectNode value = relations.addObject();
      value.put("cabinType", relation.cabinType());
      relation.dimensions().forEach(value.putArray("dimensions")::add);
    }
    return root;
  }

  private static TypeRelation relation(String cabinType, String... dimensions) {
    return new TypeRelation(cabinType, List.of(dimensions));
  }

  private JsonNode searchResponse(
      UUID warehouseId, LogisticsClient.CabinSearch search, List<UUID> cabinIds) {
    ObjectNode response = mapper.createObjectNode();
    response.put("warehouseId", warehouseId.toString());
    response.put("expiresAt", "2030-07-27T12:10:00Z");
    ArrayNode groups = response.putArray("groups");
    for (int index = 0; index < search.groups().size(); index++) {
      LogisticsClient.CabinSearchGroup requested = search.groups().get(index);
      ObjectNode group = groups.addObject();
      ObjectNode criteria = group.putObject("group");
      criteria.put("cabinType", requested.cabinType());
      criteria.put("finish", requested.finish());
      criteria.put("dimensions", requested.dimensions());
      if (requested.category() != null) criteria.put("category", requested.category());
      if (requested.characteristics() != null) {
        criteria.put("characteristics", requested.characteristics());
      }
      if (requested.linoleum() != null) criteria.put("linoleum", requested.linoleum());
      criteria.put("quantity", requested.quantity());
      ArrayNode cabins = group.putArray("cabins");
      if (index == 0) {
        cabinIds.forEach(
            id ->
                cabins.addObject()
                    .put("id", id.toString())
                    .put("status", "FREE"));
      }
    }
    return response;
  }

  private static AssistantApiModels.ClarificationQuestionResponse question(
      String branch, String kind, String status) {
    UUID optionOne = UUID.randomUUID();
    UUID optionTwo = UUID.randomUUID();
    return new AssistantApiModels.ClarificationQuestionResponse(
        UUID.randomUUID(),
        branch,
        kind,
        "Выберите вариант",
        status,
        List.of(
            new AssistantApiModels.ClarificationOptionResponse(optionOne, "Первый", "Первый"),
            new AssistantApiModels.ClarificationOptionResponse(optionTwo, "Второй", "Второй")),
        null,
        OffsetDateTime.parse("2026-08-09T12:00:00Z"),
        null);
  }

  /** Exact relation fixture mirroring logistics facet metadata. */
  private record TypeRelation(String cabinType, List<String> dimensions) {
    private TypeRelation {
      dimensions = List.copyOf(dimensions);
    }
  }
}
