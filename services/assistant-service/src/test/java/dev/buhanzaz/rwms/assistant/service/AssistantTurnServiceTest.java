package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.assistant.config.AssistantLlmProperties;
import dev.buhanzaz.rwms.assistant.domain.AssistantMessage;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient.ChatCompletionRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AssistantTurnServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void staleHistoryNeverPermitsTextBeforeACurrentSearchAttempt() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID currentUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    TextThenSearchProvider provider = new TextThenSearchProvider("Проверенный текущий результат.");
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(conversations.beginTurn(owner, conversationId, "покажи все"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId, inquiryId, currentUserMessageId, "покажи все"));
    when(conversations.promptMessages(owner, conversationId))
        .thenReturn(
            List.of(
                new AssistantConversationService.PromptMessage(
                    "user",
                    "Покажи 10 свободных БК-1 с ДВП в Санкт-Петербурге",
                    List.of(
                        toolCall(
                            "call_facets",
                            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
                            "{}",
                            "{\"tool\":\"list_available_cabin_facets\",\"data\":{\"warehouses\":[]}}"),
                        toolCall(
                            "call_search",
                            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                            "{\"warehouseId\":\"%s\",\"groups\":[{\"cabinType\":\"БК-1\",\"finish\":\"ДВП\",\"category\":\"Новая\",\"quantity\":10}]}"
                                .formatted(UUID.randomUUID()),
                            "{\"code\":\"LOGISTICS_UNAVAILABLE\"}"))),
                new AssistantConversationService.PromptMessage(
                    "assistant", "Точного совпадения нет.", List.of()),
                new AssistantConversationService.PromptMessage("user", "покажи все", List.of())));
    when(tools.execute(
            eq(owner),
            eq(conversationId),
            eq(inquiryId),
            eq(currentUserMessageId),
            any(),
            eq("current-user-bearer"),
            any()))
        .thenAnswer(
            invocation -> {
              ChatCompletionClient.ProviderToolCall call = invocation.getArgument(4);
              return new AssistantToolExecutor.ToolExecution(
                  call,
                  mapper.readTree(
                      """
                      {"tool":"search_available_cabins","data":{"groups":[{"cabins":[{"id":"current"}]}]}}
                      """),
                  UUID.randomUUID());
            });
    AssistantMessage completed = mock(AssistantMessage.class);
    when(completed.getId()).thenReturn(UUID.randomUUID());
    when(conversations.completeAssistantTurn(owner, conversationId, "Проверенный текущий результат."))
        .thenReturn(completed);

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, "покажи все", "current-user-bearer", new SseEmitter());

    assertThat(provider.requests).hasSize(3);
    assertReplayedHistory(provider.requests.getFirst());
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    assertThat(provider.requests.get(1).toolRequired()).isTrue();
    assertThat(provider.requests.get(1).tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    assertThat(provider.requests.get(2).toolRequired()).isFalse();
    verify(conversations)
        .completeAssistantTurn(owner, conversationId, "Проверенный текущий результат.");
  }

  @Test
  void instructsTheModelToRerunShowAllSearchesAndKeepExactMatchesDistinctFromAlternatives() {
    assertThat(AssistantTurnService.SYSTEM_PROMPT)
        .contains("show all")
        .contains("30, the tool maximum")
        .contains("MUST make one additional search_available_cabins call")
        .contains("removing exactly one")
        .contains("category \"Новая\"")
        .contains("one group's categories array")
        .contains("totalQuantity to 10")
        .contains("INQUIRY_ARCHIVED")
        .contains("all FREE categories")
        .contains("\"ТВП\" as returned facet \"ДВП\"")
        .contains("requires at least one new")
        .contains("Never claim current")
        .contains("availability from an earlier search result")
        .contains("Tool arguments must always use an exact returned facet")
        .contains("characteristic text exactly")
        .contains("linoleum to true")
        .contains("six БК-1 plus six БК-2")
        .contains("LOGISTICS_UNAVAILABLE")
        .contains("Do not mention this failure in final prose")
        .contains("claim that there are no cabins based on a failure code")
        .contains("request_search_merge_confirmation")
        .contains("CABINS_NOT_FOUND")
        .contains("resultMode APPEND")
        .contains("show all such cabins")
        .contains("describe only cabins")
        .contains("UI-only availability feedback")
        .contains("short neutral completion")
        .contains("обновлён.");
  }

  @Test
  void removesAvailabilityAndFailureExplanationsFromTheAssistantText() {
    String text =
        """
        В Санкт-Петербурге найдены 2 свободные бытовки с отделкой ДВП.

        **Отделки ЛДСП и ОСБ** в Санкт-Петербурге недоступны.

        Поиск ОСБ в Москве не завершён из-за технической ошибки. Попробуйте позже.
        """;

    assertThat(AssistantTurnService.withoutAvailabilityExplanations(text))
        .isEqualTo("В Санкт-Петербурге найдены 2 свободные бытовки с отделкой ДВП.");
    assertThat(AssistantTurnService.withoutAvailabilityExplanations("Не найдено."))
        .isEqualTo("Подбор обновлён.");
  }

  @Test
  void permitsOnlyTheSafeMergeClarificationInsteadOfAFreshSearchForAnActiveSelection() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID currentUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    ConfirmationThenTextProvider provider = new ConfirmationThenTextProvider();
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(conversations.beginTurn(owner, conversationId, "Покажи ещё БК-2"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId, inquiryId, currentUserMessageId, "Покажи ещё БК-2"));
    when(conversations.hasActiveSearchResult(owner, conversationId, "current-user-bearer"))
        .thenReturn(true);
    when(conversations.promptMessages(owner, conversationId))
        .thenReturn(
            List.of(
                new AssistantConversationService.PromptMessage(
                    "user", "Покажи БК-1", List.of())));
    when(tools.execute(
            eq(owner),
            eq(conversationId),
            eq(inquiryId),
            eq(currentUserMessageId),
            any(),
            eq("current-user-bearer"),
            any()))
        .thenAnswer(
            invocation -> {
              ChatCompletionClient.ProviderToolCall call = invocation.getArgument(4);
              return new AssistantToolExecutor.ToolExecution(
                  call,
                  mapper.readTree(
                      """
                      {"tool":"request_search_merge_confirmation","data":{"action":"ASK_ADD_OR_REPLACE"}}
                      """),
                  UUID.randomUUID());
            });
    AssistantMessage completed = mock(AssistantMessage.class);
    when(completed.getId()).thenReturn(UUID.randomUUID());
    when(
            conversations.completeAssistantTurn(
                owner, conversationId, "Добавить к текущей подборке или заменить её?"))
        .thenReturn(completed);

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, "Покажи ещё БК-2", "current-user-bearer", new SseEmitter());

    assertThat(provider.requests).hasSize(2);
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION);
    assertThat(provider.requests.getFirst().messages().getFirst().content())
        .contains("DYNAMIC SELECTION CONTEXT: There is an active");
    assertThat(provider.requests.get(1).toolRequired()).isFalse();
    verify(conversations)
        .completeAssistantTurn(owner, conversationId, "Добавить к текущей подборке или заменить её?");
  }

  @Test
  void rejectsTextOnlyFirstProviderResponseUntilAFreshSearchAttempt() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID currentUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    TextThenSearchProvider provider =
        new TextThenSearchProvider("Актуальный поиск не выполнен: сервис недоступен.");
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(conversations.beginTurn(owner, conversationId, "Покажи свободные бытовки"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId, inquiryId, currentUserMessageId, "Покажи свободные бытовки"));
    when(conversations.promptMessages(owner, conversationId))
        .thenReturn(
            List.of(
                new AssistantConversationService.PromptMessage(
                    "user", "Покажи свободные бытовки", List.of())));
    when(tools.execute(
            eq(owner),
            eq(conversationId),
            eq(inquiryId),
            eq(currentUserMessageId),
            any(),
            eq("current-user-bearer"),
            any()))
        .thenAnswer(
            invocation -> {
              ChatCompletionClient.ProviderToolCall call = invocation.getArgument(4);
              return new AssistantToolExecutor.ToolExecution(
                  call, mapper.readTree("{\"code\":\"LOGISTICS_UNAVAILABLE\"}"), UUID.randomUUID());
            });
    AssistantMessage completed = mock(AssistantMessage.class);
    when(completed.getId()).thenReturn(UUID.randomUUID());
    when(
            conversations.completeAssistantTurn(
                owner, conversationId, "Подбор обновлён."))
        .thenReturn(completed);

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, "Покажи свободные бытовки", "current-user-bearer", new SseEmitter());

    assertThat(provider.requests).hasSize(3);
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    assertThat(provider.requests.get(1).toolRequired()).isTrue();
    assertThat(provider.requests.get(1).tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    assertThat(provider.requests.get(2).toolRequired()).isFalse();
    verify(conversations)
        .completeAssistantTurn(owner, conversationId, "Подбор обновлён.");
  }

  @Test
  void facetOnlyFirstRoundForcesASearchOnlySecondRoundBeforeText() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID currentUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    FacetThenSearchProvider provider = new FacetThenSearchProvider();
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(conversations.beginTurn(owner, conversationId, "Покажи свободные БК-1"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId, inquiryId, currentUserMessageId, "Покажи свободные БК-1"));
    when(conversations.promptMessages(owner, conversationId))
        .thenReturn(
            List.of(
                new AssistantConversationService.PromptMessage(
                    "user", "Покажи свободные БК-1", List.of())));
    when(tools.execute(
            eq(owner),
            eq(conversationId),
            eq(inquiryId),
            eq(currentUserMessageId),
            any(),
            eq("current-user-bearer"),
            any()))
        .thenAnswer(
            invocation -> {
              ChatCompletionClient.ProviderToolCall call = invocation.getArgument(4);
              JsonNode result =
                  AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS.equals(call.name())
                      ? mapper.readTree(
                          """
                          {"tool":"list_available_cabin_facets","data":{"warehouses":[]}}
                          """)
                      : mapper.readTree(
                          """
                          {"tool":"search_available_cabins","data":{"groups":[{"cabins":[{"id":"current"}]}]}}
                          """);
              return new AssistantToolExecutor.ToolExecution(call, result, UUID.randomUUID());
            });
    AssistantMessage completed = mock(AssistantMessage.class);
    when(completed.getId()).thenReturn(UUID.randomUUID());
    when(conversations.completeAssistantTurn(owner, conversationId, "Найдены текущие варианты."))
        .thenReturn(completed);

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, "Покажи свободные БК-1", "current-user-bearer", new SseEmitter());

    assertThat(provider.requests).hasSize(3);
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    assertThat(provider.requests.get(1).toolRequired()).isTrue();
    assertThat(provider.requests.get(1).tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    assertThat(provider.requests.get(2).toolRequired()).isFalse();
    verify(conversations).completeAssistantTurn(owner, conversationId, "Найдены текущие варианты.");
  }

  @Test
  void doesNotAcceptTextOnlyAlternativesAfterAnEmptyExactSearch() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID currentUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    AlternativeSearchProvider provider = new AlternativeSearchProvider();
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(conversations.beginTurn(owner, conversationId, "Покажи две БК-1 с ТВП"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId, inquiryId, currentUserMessageId, "Покажи две БК-1 с ТВП"));
    when(conversations.promptMessages(owner, conversationId))
        .thenReturn(
            List.of(
                new AssistantConversationService.PromptMessage(
                    "user", "Покажи две БК-1 с ТВП", List.of())));
    when(tools.execute(
            eq(owner),
            eq(conversationId),
            eq(inquiryId),
            eq(currentUserMessageId),
            any(),
            eq("current-user-bearer"),
            any()))
        .thenAnswer(
            invocation -> {
              ChatCompletionClient.ProviderToolCall call = invocation.getArgument(4);
              JsonNode result =
                  call.id().equals("exact")
                      ? mapper.readTree(
                          """
                          {"tool":"search_available_cabins","data":{"groups":[]}}
                          """)
                      : mapper.readTree(
                          """
                          {"tool":"search_available_cabins","data":{"groups":[{"cabins":[{"id":"1"}]}]}}
                          """);
              return new AssistantToolExecutor.ToolExecution(call, result, UUID.randomUUID());
            });
    AssistantMessage completed = mock(AssistantMessage.class);
    when(completed.getId()).thenReturn(UUID.randomUUID());
    when(conversations.completeAssistantTurn(owner, conversationId, "Проверенные альтернативы."))
        .thenReturn(completed);

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(
            owner,
            conversationId,
            "Покажи две БК-1 с ТВП",
            "current-user-bearer",
            new SseEmitter());

    assertThat(provider.requests).hasSize(4);
    assertThat(provider.requests.get(1).toolRequired()).isTrue();
    assertThat(provider.requests.get(1).tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    assertThat(provider.requests.get(2).messages().getLast().content())
        .contains("must supply the alternatives");
    verify(conversations)
        .completeAssistantTurn(owner, conversationId, "Проверенные альтернативы.");
  }

  @Test
  void completesThenArchivesTheLocalConversationWhenLogisticsReportsAnArchivedInquiry() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID currentUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    ArchivedInquiryProvider provider = new ArchivedInquiryProvider();
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(conversations.beginTurn(owner, conversationId, "Покажи свободные бытовки"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId, inquiryId, currentUserMessageId, "Покажи свободные бытовки"));
    when(conversations.promptMessages(owner, conversationId))
        .thenReturn(
            List.of(
                new AssistantConversationService.PromptMessage(
                    "user", "Покажи свободные бытовки", List.of())));
    when(tools.execute(
            eq(owner),
            eq(conversationId),
            eq(inquiryId),
            eq(currentUserMessageId),
            any(),
            eq("current-user-bearer"),
            any()))
        .thenAnswer(
            invocation -> {
              ChatCompletionClient.ProviderToolCall call = invocation.getArgument(4);
              return new AssistantToolExecutor.ToolExecution(
                  call, mapper.readTree("{\"code\":\"INQUIRY_ARCHIVED\"}"), UUID.randomUUID());
            });
    AssistantMessage completed = mock(AssistantMessage.class);
    when(completed.getId()).thenReturn(UUID.randomUUID());
    when(
            conversations.completeAssistantTurn(
                owner, conversationId, "Этот диалог уже завершён: подборка больше не активна."))
        .thenReturn(completed);

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, "Покажи свободные бытовки", "current-user-bearer", new SseEmitter());

    assertThat(provider.requests).hasSize(1);
    InOrder ordered = inOrder(conversations);
    ordered
        .verify(conversations)
        .completeAssistantTurn(
            owner, conversationId, "Этот диалог уже завершён: подборка больше не активна.");
    ordered.verify(conversations).archive(owner, conversationId);
  }

  private AssistantConversationService.PromptToolCall toolCall(
      String id, String name, String arguments, String result) {
    return new AssistantConversationService.PromptToolCall(
        id, name, mapper.readTree(arguments), mapper.readTree(result));
  }

  private void assertReplayedHistory(ChatCompletionRequest request) {
    assertThat(request.messages())
        .extracting(ChatCompletionClient.ChatMessage::role)
        .containsExactly("system", "user", "assistant", "tool", "assistant", "tool", "assistant", "user");
    assertThat(request.messages().get(1).content())
        .isEqualTo("Покажи 10 свободных БК-1 с ДВП в Санкт-Петербурге");
    assertToolCall(
        request.messages().get(2),
        "call_facets",
        AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
        "{}");
    assertThat(request.messages().get(3).toolCallId()).isEqualTo("call_facets");
    assertThat(mapper.readTree(request.messages().get(3).content()).path("tool").asText())
        .isEqualTo(AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS);
    assertToolCall(
        request.messages().get(4),
        "call_search",
        AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
        null);
    assertThat(request.messages().get(5).toolCallId()).isEqualTo("call_search");
    assertThat(mapper.readTree(request.messages().get(5).content()).path("code").asText())
        .isEqualTo("LOGISTICS_UNAVAILABLE");
    assertThat(request.messages().get(6).content()).isEqualTo("Точного совпадения нет.");
    assertThat(request.messages().get(7).content()).isEqualTo("покажи все");
  }

  private static void assertToolCall(
      ChatCompletionClient.ChatMessage message, String id, String name, String arguments) {
    assertThat(message.content()).isNull();
    assertThat(message.toolCalls())
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.id()).isEqualTo(id);
              assertThat(call.name()).isEqualTo(name);
              if (arguments != null) assertThat(call.arguments()).isEqualTo(arguments);
            });
  }

  private static AssistantLlmProperties properties() {
    return new AssistantLlmProperties(
        "http://llm.example.test/v1",
        "test-api-key",
        "test-model",
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        false);
  }

  private static void toolCall(
      ChatCompletionClient.ChatCompletionListener listener,
      String id,
      String name,
      String arguments) {
    listener.onToolCallDelta(new ChatCompletionClient.ToolCallDelta(0, id, name, arguments));
    listener.onFinish("tool_calls");
  }

  private static final class TextThenSearchProvider implements ChatCompletionClient {
    private final List<ChatCompletionRequest> requests = new ArrayList<>();
    private final String finalText;

    private TextThenSearchProvider(String finalText) {
      this.finalText = finalText;
    }

    @Override
    public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
      requests.add(request);
      switch (requests.size()) {
        case 1 -> {
          listener.onContent("Непроверенный текст до текущего поиска.");
          listener.onFinish("stop");
        }
        case 2 ->
            toolCall(
                listener,
                "current-search",
                AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                "{\"groups\":[{\"quantity\":1}]}");
        case 3 -> {
          listener.onContent(finalText);
          listener.onFinish("stop");
        }
        default -> throw new AssertionError("Unexpected provider round");
      }
    }
  }

  private static final class ConfirmationThenTextProvider implements ChatCompletionClient {
    private final List<ChatCompletionRequest> requests = new ArrayList<>();

    @Override
    public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
      requests.add(request);
      switch (requests.size()) {
        case 1 ->
            toolCall(
                listener,
                "merge-confirmation",
                AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION,
                "{}");
        case 2 -> {
          listener.onContent("Добавить к текущей подборке или заменить её?");
          listener.onFinish("stop");
        }
        default -> throw new AssertionError("Unexpected provider round");
      }
    }
  }

  private static final class FacetThenSearchProvider implements ChatCompletionClient {
    private final List<ChatCompletionRequest> requests = new ArrayList<>();

    @Override
    public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
      requests.add(request);
      switch (requests.size()) {
        case 1 -> {
          listener.onContent("Непроверенный текст вместе с фасетами.");
          toolCall(
              listener,
              "facets",
              AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
              "{}");
        }
        case 2 -> {
          listener.onContent("Непроверенный текст до поиска.");
          toolCall(
              listener,
              "current-search",
              AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
              "{\"groups\":[{\"quantity\":1}]}");
        }
        case 3 -> {
          listener.onContent("Найдены текущие варианты.");
          listener.onFinish("stop");
        }
        default -> throw new AssertionError("Unexpected provider round");
      }
    }
  }

  private static final class ArchivedInquiryProvider implements ChatCompletionClient {
    private final List<ChatCompletionRequest> requests = new ArrayList<>();

    @Override
    public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
      requests.add(request);
      if (requests.size() != 1) throw new AssertionError("Archived inquiry must finish immediately");
      toolCall(
          listener,
          "archived-search",
          AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
          "{\"groups\":[{\"quantity\":1}]}");
    }
  }

  private static final class AlternativeSearchProvider implements ChatCompletionClient {
    private final List<ChatCompletionRequest> requests = new ArrayList<>();

    @Override
    public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
      requests.add(request);
      switch (requests.size()) {
        case 1 -> {
          listener.onContent("Служебный черновик перед поиском.");
          toolCall(
              listener,
              "exact",
              AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
              "{\"groups\":[{\"quantity\":2}]}");
        }
        case 2 -> {
          listener.onContent("Непроверенная текстовая альтернатива.");
          listener.onFinish("stop");
        }
        case 3 ->
            toolCall(
                listener,
                "alternative",
                AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                "{\"groups\":[{\"quantity\":2}]}");
        case 4 -> {
          listener.onContent("Проверенные альтернативы.");
          listener.onFinish("stop");
        }
        default -> throw new AssertionError("Unexpected provider round");
      }
    }

  }
}
