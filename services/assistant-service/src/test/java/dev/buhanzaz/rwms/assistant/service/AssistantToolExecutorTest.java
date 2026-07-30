package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class AssistantToolExecutorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void forwardsRequestedGroupFiltersAndPreservesFreeCabinStatusInTheToolResult() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID toolCallId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), eq("current-user-bearer")))
        .thenReturn(
            mapper.readTree(
                """
                {"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
                  {"group":{"category":"Новая","quantity":1},
                   "cabins":[{"id":"%s","status":"FREE"}]}
                ]}
                """.formatted(warehouseId, UUID.randomUUID())));
    ArrayList<AssistantApiModels.TurnEvent> events = new ArrayList<>();

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "provider-call-1",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","groups":[
                      {"cabinType":"БК-1","category":"Новая","characteristics":"с верандой","linoleum":false,"quantity":1}
                    ]}
                    """.formatted(warehouseId)),
                "current-user-bearer",
                events::add);

    ArgumentCaptor<LogisticsClient.CabinSearch> search =
        ArgumentCaptor.forClass(LogisticsClient.CabinSearch.class);
    verify(logistics)
        .searchAvailableCabins(eq(inquiryId), search.capture(), eq("current-user-bearer"));
    assertThat(search.getValue().warehouseId()).isEqualTo(warehouseId);
    assertThat(search.getValue().groups())
        .singleElement()
        .satisfies(
            group -> {
              assertThat(group.cabinType()).isEqualTo("БК-1");
              assertThat(group.category()).isEqualTo("Новая");
              assertThat(group.characteristics()).isEqualTo("с верандой");
              assertThat(group.linoleum()).isFalse();
            });
    assertThat(
            execution
                .result()
                .path("data")
                .path("groups")
                .get(0)
                .path("cabins")
                .get(0)
                .path("status")
                .asText())
        .isEqualTo("FREE");
    assertThat(events).extracting(AssistantApiModels.TurnEvent::event)
        .containsExactly("tool.started", "search.result", "tool.completed");
    verify(conversations).completeToolCall(toolCallId, execution.result());
  }

  @Test
  void rejectsTheRemovedStatusConditionInSearchGroups() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    UUID toolCallId = UUID.randomUUID();
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    ArrayList<AssistantApiModels.TurnEvent> events = new ArrayList<>();

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "provider-call-2",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","groups":[{"condition":"removed","quantity":1}]}
                    """.formatted(UUID.randomUUID())),
                "current-user-bearer",
                events::add);

    assertThat(execution.result().path("code").asText()).isEqualTo("TOOL_ARGUMENTS_INVALID");
    verifyNoInteractions(logistics);
    verify(conversations).failToolCall(toolCallId, "TOOL_ARGUMENTS_INVALID", execution.result());
    assertThat(events)
        .singleElement()
        .satisfies(event -> assertThat(event.code()).isEqualTo("TOOL_ARGUMENTS_INVALID"));
  }

  @Test
  void rejectsWarehouseBusinessCodeInToolArguments() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    UUID toolCallId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "provider-call-code",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","code":"MSK-1","groups":[{"quantity":1}]}
                    """.formatted(warehouseId)),
                "current-user-bearer",
                ignored -> {});

    assertThat(execution.result().path("code").asText()).isEqualTo("TOOL_ARGUMENTS_INVALID");
    verifyNoInteractions(logistics);
    verify(conversations).failToolCall(toolCallId, "TOOL_ARGUMENTS_INVALID", execution.result());
  }

  @Test
  void rejectsLegacyWarehouseBusinessCodeBeforeEmissionAndPersistence() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    UUID toolCallId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    when(logistics.listAvailableCabinFacets(inquiryId, "current-user-bearer"))
        .thenReturn(
            mapper.readTree(
                """
                {"warehouses":[{"warehouseId":"%s","code":"MSK-1","name":"Moscow","city":"Moscow","cabinTypes":["6m"],"finishes":["basic"],"dimensions":["6x2.4"],"categories":["Новая"]}]}
                """.formatted(warehouseId)));
    ArrayList<AssistantApiModels.TurnEvent> events = new ArrayList<>();

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "provider-call-facets",
                    AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
                    "{}"),
                "current-user-bearer",
                events::add);

    assertThat(execution.result().path("code").asText()).isEqualTo("LOGISTICS_UNAVAILABLE");
    assertThat(execution.result().toString()).doesNotContain("MSK-1");
    verify(conversations)
        .failToolCall(toolCallId, "LOGISTICS_UNAVAILABLE", execution.result());
    verify(conversations, never()).completeToolCall(any(), any());
    assertThat(events.getLast().code()).isEqualTo("LOGISTICS_UNAVAILABLE");
  }

  @Test
  void preservesSeparateDynamicCategoryGroupsAndAllowsAllFreeCategories() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(UUID.randomUUID());
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), eq("current-user-bearer")))
        .thenReturn(mapper.readTree("{\"groups\":[]}"));

    executor(conversations, logistics)
        .execute(
            ownerId,
            conversationId,
            inquiryId,
            turnMessageId,
            new ChatCompletionClient.ProviderToolCall(
                "provider-call-3",
                AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                """
                {"warehouseId":"%s","groups":[
                  {"category":"ИТР","quantity":2},
                  {"category":"Обычная","quantity":2},
                  {"quantity":30}
                ]}
                """.formatted(warehouseId)),
            "current-user-bearer",
            ignored -> {});

    ArgumentCaptor<LogisticsClient.CabinSearch> search =
        ArgumentCaptor.forClass(LogisticsClient.CabinSearch.class);
    verify(logistics)
        .searchAvailableCabins(eq(inquiryId), search.capture(), eq("current-user-bearer"));
    assertThat(search.getValue().groups())
        .extracting(LogisticsClient.CabinSearchGroup::category)
        .containsExactly("ИТР", "Обычная", null);
  }

  @Test
  void allocatesOneSharedTotalAcrossTypesAndMergesExactCategoryResults() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(UUID.randomUUID());
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    AtomicInteger calls = new AtomicInteger();
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), eq("current-user-bearer")))
        .thenAnswer(
            invocation -> {
              LogisticsClient.CabinSearch request = invocation.getArgument(1);
              return switch (calls.getAndIncrement()) {
                case 0 -> cabinSearchResponse(warehouseId, request, List.of(4, 4));
                case 1 -> cabinSearchResponse(warehouseId, request, List.of(8, 4));
                case 2 ->
                    cabinSearchResponse(
                        warehouseId,
                        request,
                        request.groups().stream()
                            .map(LogisticsClient.CabinSearchGroup::quantity)
                            .toList());
                default -> throw new AssertionError("Unexpected logistics search");
              };
            });

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "shared-total",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","totalQuantity":10,"groups":[
                      {"cabinType":"БК-1","categories":["Обычная","ИТР"]},
                      {"cabinType":"БК-2","categories":["Обычная","ИТР"]}
                    ]}
                    """.formatted(warehouseId)),
                "current-user-bearer",
                ignored -> {});

    ArgumentCaptor<LogisticsClient.CabinSearch> requests =
        ArgumentCaptor.forClass(LogisticsClient.CabinSearch.class);
    verify(logistics, times(3))
        .searchAvailableCabins(eq(inquiryId), requests.capture(), eq("current-user-bearer"));
    List<LogisticsClient.CabinSearch> searches = requests.getAllValues();
    assertBoundedProbe(searches.get(0), "БК-1");
    assertBoundedProbe(searches.get(1), "БК-2");
    assertThat(searches.get(2).groups())
        .extracting(LogisticsClient.CabinSearchGroup::category)
        .containsExactly("Обычная", "ИТР", "Обычная", "ИТР");
    assertThat(totalRequested(searches.get(2))).isEqualTo(10);
    assertThat(totalRequested(searches.get(2))).isLessThanOrEqualTo(100);

    JsonNode groups = execution.result().path("data").path("groups");
    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).path("group").path("cabinType").asText()).isEqualTo("БК-1");
    assertThat(groups.get(1).path("group").path("cabinType").asText()).isEqualTo("БК-2");
    assertThat(groups.get(0).path("group").has("category")).isFalse();
    assertThat(groups.get(1).path("group").has("category")).isFalse();
    assertThat(groups.get(0).path("group").path("categories"))
        .extracting(JsonNode::asText)
        .containsExactly("Обычная", "ИТР");
    assertThat(groups.get(1).path("group").path("categories"))
        .extracting(JsonNode::asText)
        .containsExactly("Обычная", "ИТР");
    assertThat(groups.get(0).path("cabins")).hasSize(5);
    assertThat(groups.get(1).path("cabins")).hasSize(5);
  }

  @Test
  void returnsTheAvailableSubsetWhenSharedTotalCapacityIsInsufficient() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(UUID.randomUUID());
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    AtomicInteger calls = new AtomicInteger();
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), eq("current-user-bearer")))
        .thenAnswer(
            invocation -> {
              LogisticsClient.CabinSearch request = invocation.getArgument(1);
              return switch (calls.getAndIncrement()) {
                case 0 -> cabinSearchResponse(warehouseId, request, List.of(1, 1));
                case 1 -> cabinSearchResponse(warehouseId, request, List.of(2, 2));
                case 2 ->
                    cabinSearchResponse(
                        warehouseId,
                        request,
                        request.groups().stream()
                            .map(LogisticsClient.CabinSearchGroup::quantity)
                            .toList());
                default -> throw new AssertionError("Unexpected logistics search");
              };
            });

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "insufficient-total",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","totalQuantity":10,"groups":[
                      {"cabinType":"БК-1","categories":["Обычная","ИТР"]},
                      {"cabinType":"БК-2","categories":["Обычная","ИТР"]}
                    ]}
                    """.formatted(warehouseId)),
                "current-user-bearer",
                ignored -> {});

    ArgumentCaptor<LogisticsClient.CabinSearch> requests =
        ArgumentCaptor.forClass(LogisticsClient.CabinSearch.class);
    verify(logistics, times(3))
        .searchAvailableCabins(eq(inquiryId), requests.capture(), eq("current-user-bearer"));
    assertThat(totalRequested(requests.getAllValues().getLast())).isEqualTo(6);
    JsonNode groups = execution.result().path("data").path("groups");
    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).path("cabins")).hasSize(2);
    assertThat(groups.get(1).path("cabins")).hasSize(4);
    JsonNode notice = execution.result().path("notices").get(0);
    assertThat(notice.path("code").asText()).isEqualTo("CABINS_PARTIALLY_FOUND");
    assertThat(notice.path("requestedQuantity").intValue()).isEqualTo(10);
    assertThat(notice.path("foundQuantity").intValue()).isEqualTo(6);
    notice.path("groups").forEach(group -> assertThat(group.path("quantity").intValue()).isPositive());
  }

  @Test
  void emitsStructuredExactGapAndAppendModeWithoutParsingAssistantText() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID toolCallId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), eq("current-user-bearer")))
        .thenAnswer(
            invocation ->
                cabinSearchResponse(
                    warehouseId, invocation.getArgument(1), List.of(0)));

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "exact-miss",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","resultMode":"APPEND","groups":[
                      {"cabinType":"БК-1","finish":"ДВП","quantity":2}
                    ]}
                    """.formatted(warehouseId)),
                "current-user-bearer",
                ignored -> {});

    assertThat(execution.result().path("resultMode").asText()).isEqualTo("APPEND");
    JsonNode notice = execution.result().path("notices").get(0);
    assertThat(notice.path("code").asText()).isEqualTo("CABINS_NOT_FOUND");
    assertThat(notice.path("requestedQuantity").intValue()).isEqualTo(2);
    assertThat(notice.path("foundQuantity").intValue()).isZero();
    assertThat(notice.path("groups").get(0).path("finish").asText()).isEqualTo("ДВП");
    assertThat(notice.path("groups").get(0).path("quantity").intValue()).isEqualTo(2);
    verify(conversations).completeToolCall(toolCallId, execution.result());
  }

  @Test
  void completesMergeConfirmationWithoutCallingLogistics() {
    UUID toolCallId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ChatCompletionClient.ProviderToolCall(
                    "merge-question", AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION, "{}"),
                "current-user-bearer",
                ignored -> {});

    assertThat(execution.result().path("tool").asText())
        .isEqualTo(AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION);
    assertThat(execution.result().path("data").path("action").asText())
        .isEqualTo("ASK_ADD_OR_REPLACE");
    verifyNoInteractions(logistics);
    verify(conversations).completeToolCall(toolCallId, execution.result());
  }

  @Test
  void rejectsMixedSharedAndPerGroupQuantitiesBeforeCallingLogistics() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    UUID toolCallId = UUID.randomUUID();
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "invalid-mode",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","totalQuantity":10,"groups":[{"quantity":10}]}
                    """.formatted(UUID.randomUUID())),
                "current-user-bearer",
                ignored -> {});

    assertThat(execution.result().path("code").asText()).isEqualTo("TOOL_ARGUMENTS_INVALID");
    verifyNoInteractions(logistics);
    verify(conversations).failToolCall(toolCallId, "TOOL_ARGUMENTS_INVALID", execution.result());
  }

  @Test
  void mapsArchivedInquiryToASafeTerminalToolCode() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID turnMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    LogisticsClient logistics = mock(LogisticsClient.class);
    AssistantToolCall persistedCall = mock(AssistantToolCall.class);
    UUID toolCallId = UUID.randomUUID();
    when(persistedCall.getId()).thenReturn(toolCallId);
    when(conversations.startToolCall(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(persistedCall);
    when(logistics.searchAvailableCabins(eq(inquiryId), any(), eq("current-user-bearer")))
        .thenThrow(new AssistantInquiryArchivedException());

    AssistantToolExecutor.ToolExecution execution =
        executor(conversations, logistics)
            .execute(
                ownerId,
                conversationId,
                inquiryId,
                turnMessageId,
                new ChatCompletionClient.ProviderToolCall(
                    "archived-inquiry",
                    AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                    """
                    {"warehouseId":"%s","groups":[{"quantity":1}]}
                    """.formatted(UUID.randomUUID())),
                "current-user-bearer",
                ignored -> {});

    assertThat(execution.result().path("code").asText()).isEqualTo("INQUIRY_ARCHIVED");
    verify(conversations).failToolCall(toolCallId, "INQUIRY_ARCHIVED", execution.result());
  }

  private JsonNode cabinSearchResponse(
      UUID warehouseId, LogisticsClient.CabinSearch search, List<Integer> availablePerGroup) {
    if (search.groups().size() != availablePerGroup.size()) {
      throw new AssertionError("Expected one availability count for every exact group");
    }
    ObjectNode response = mapper.createObjectNode();
    response.put("warehouseId", warehouseId.toString());
    response.put("expiresAt", "2030-07-27T12:10:00Z");
    ArrayNode groups = response.putArray("groups");
    for (int groupIndex = 0; groupIndex < search.groups().size(); groupIndex++) {
      LogisticsClient.CabinSearchGroup group = search.groups().get(groupIndex);
      ObjectNode result = groups.addObject();
      result.set("group", exactGroup(group));
      ArrayNode cabins = result.putArray("cabins");
      for (int cabinIndex = 0; cabinIndex < availablePerGroup.get(groupIndex); cabinIndex++) {
        ObjectNode cabin = cabins.addObject();
        cabin.put("id", group.cabinType() + ":" + group.category() + ":" + cabinIndex);
        cabin.put("status", "FREE");
      }
    }
    return response;
  }

  private static ObjectNode exactGroup(LogisticsClient.CabinSearchGroup group) {
    ObjectNode result = new ObjectMapper().createObjectNode();
    putIfPresent(result, "cabinType", group.cabinType());
    putIfPresent(result, "finish", group.finish());
    putIfPresent(result, "dimensions", group.dimensions());
    putIfPresent(result, "category", group.category());
    putIfPresent(result, "characteristics", group.characteristics());
    if (group.linoleum() != null) result.put("linoleum", group.linoleum());
    result.put("quantity", group.quantity());
    return result;
  }

  private static void putIfPresent(ObjectNode target, String field, String value) {
    if (value != null) target.put(field, value);
  }

  private static void assertBoundedProbe(LogisticsClient.CabinSearch search, String cabinType) {
    assertThat(search.groups()).hasSizeLessThanOrEqualTo(3);
    assertThat(search.groups())
        .extracting(LogisticsClient.CabinSearchGroup::cabinType)
        .containsOnly(cabinType);
    assertThat(totalRequested(search)).isLessThanOrEqualTo(90);
    assertThat(search.groups())
        .extracting(LogisticsClient.CabinSearchGroup::quantity)
        .containsOnly(10);
  }

  private static int totalRequested(LogisticsClient.CabinSearch search) {
    return search.groups().stream().mapToInt(LogisticsClient.CabinSearchGroup::quantity).sum();
  }

  private AssistantToolExecutor executor(
      AssistantConversationService conversations, LogisticsClient logistics) {
    return new AssistantToolExecutor(conversations, logistics, mapper);
  }
}
