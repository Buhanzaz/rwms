package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.config.AssistantLlmProperties;
import dev.buhanzaz.rwms.assistant.domain.AssistantMessage;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient.ChatCompletionRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Covers guarded model turns, prompt policy, tool sequencing and structured SSE events. */
class AssistantTurnServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void staleHistoryNeverPermitsTextBeforeACurrentSearchAttempt() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID rentalOrderId = UUID.randomUUID();
    UUID currentUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    TextThenSearchProvider provider = new TextThenSearchProvider("Проверенный текущий результат.");
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(conversations.beginTurn(owner, conversationId, "покажи все"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId,
                inquiryId,
                rentalOrderId,
                currentUserMessageId,
                "покажи все",
                null,
                null,
                null));
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
    when(conversations.completeAssistantTurn(
            owner, conversationId, "Проверенный текущий результат."))
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
    assertThat(provider.requests.getFirst().messages().getFirst().content())
        .contains("amends one existing rental order", "never propose another warehouse");
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    assertThat(provider.requests.get(1).toolRequired()).isTrue();
    assertThat(provider.requests.get(1).tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    assertThat(provider.requests.get(2).toolRequired()).isFalse();
    verify(conversations)
        .completeAssistantTurn(owner, conversationId, "Проверенный текущий результат.");
  }

  @Test
  void instructsTheModelToUseExactInteractiveSearchAndReadOnlyReferenceFacts() {
    String prompt = AssistantTurnService.SYSTEM_PROMPT.replaceAll("\\s+", " ");
    assertThat(prompt)
        .contains("exact current cabinType and finish")
        .contains("покажи 2 ЛДСП")
        .contains("deterministic order")
        .contains("next one is shown only after that answer")
        .contains("Never create or continue two independent clarification branches")
        .contains("If it has several, ask with exact size buttons")
        .contains("6x2.4-equivalent")
        .contains("narrow the type buttons to compatible relations")
        .contains("Модуль")
        .contains("пост охраны")
        .contains("type-dimension relations")
        .contains("lookup_cabin_catalog")
        .contains("read-only and creates no hold")
        .contains("linoleum=true")
        .contains("filterSuggestions")
        .contains("remove_selected_cabins")
        .contains("INQUIRY_ARCHIVED")
        .contains("LOGISTICS_UNAVAILABLE")
        .contains("CABINS_NOT_FOUND")
        .contains("APPEND must use at most one exact category")
        .contains("обновлён.");
  }

  @Test
  void buttonAnswerEmitsItsStructuredClarificationBeforeContinuingTheTurn() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID userMessageId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();
    UUID optionId = UUID.randomUUID();
    AssistantApiModels.ClarificationQuestionResponse answeredQuestion =
        new AssistantApiModels.ClarificationQuestionResponse(
            questionId,
            "search:ldsp:type",
            "CABIN_TYPE",
            "Какой тип бытовки нужен для ЛДСП?",
            "ANSWERED",
            List.of(
                new AssistantApiModels.ClarificationOptionResponse(optionId, "Модуль", "Модуль"),
                new AssistantApiModels.ClarificationOptionResponse(
                    UUID.randomUUID(), "Пост охраны", "Пост охраны")),
            optionId,
            OffsetDateTime.parse("2026-08-09T12:00:00Z"),
            OffsetDateTime.parse("2026-08-09T12:01:00Z"));
    AssistantApiModels.TurnRequest request =
        new AssistantApiModels.TurnRequest(
            null, new AssistantApiModels.ClarificationAnswerRequest(questionId, optionId));
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    when(conversations.beginTurn(owner, conversationId, request))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId,
                inquiryId,
                userMessageId,
                "Ответ на уточнение: Модуль",
                answeredQuestion));
    when(conversations.promptMessages(owner, conversationId)).thenReturn(List.of());
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(tools.execute(
            eq(owner),
            eq(conversationId),
            eq(inquiryId),
            eq(userMessageId),
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
    when(conversations.completeAssistantTurn(
            owner, conversationId, "Этот диалог уже завершён: подборка больше не активна."))
        .thenReturn(completed);
    CapturingEmitter emitter = new CapturingEmitter();
    ArchivedInquiryProvider provider = new ArchivedInquiryProvider();

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, request, "current-user-bearer", emitter);

    assertThat(emitter.events)
        .extracting(AssistantApiModels.TurnEvent::event)
        .startsWith("turn.started", "clarification.answered");
    assertThat(emitter.events.get(1).clarification()).isEqualTo(answeredQuestion);
    assertThat(provider.requests).hasSize(1);
  }

  @Test
  void intermediateAnswerActivatesOnlyTheNextQuestionWithoutCallingTheProvider() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID userMessageId = UUID.randomUUID();
    UUID toolCallId = UUID.randomUUID();
    AssistantApiModels.ClarificationQuestionResponse answered =
        question(1, "ANSWERED", UUID.randomUUID());
    AssistantApiModels.ClarificationQuestionResponse next = question(2, "PENDING", null);
    AssistantApiModels.TurnRequest request =
        new AssistantApiModels.TurnRequest(
            null,
            new AssistantApiModels.ClarificationAnswerRequest(
                answered.id(), answered.answeredOptionId()));
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    when(conversations.beginTurn(owner, conversationId, request))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                conversationId,
                inquiryId,
                UUID.randomUUID(),
                userMessageId,
                "Ответ на уточнение",
                answered,
                next,
                toolCallId));
    CapturingEmitter emitter = new CapturingEmitter();
    ChatCompletionClient provider =
        (ignoredRequest, ignoredListener) -> {
          throw new AssertionError("Provider must stay parked until the final answer");
        };

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            mock(AssistantToolExecutor.class),
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, request, "current-user-bearer", emitter);

    assertThat(emitter.events)
        .extracting(AssistantApiModels.TurnEvent::event)
        .containsExactly(
            "turn.started", "clarification.answered", "clarification.requested", "turn.completed");
    assertThat(emitter.events.get(2).clarification()).isEqualTo(next);
    assertThat(emitter.events.get(2).toolCallId()).isEqualTo(toolCallId);
    assertThat(emitter.events.getLast().messageId()).isNull();
    verify(conversations, never()).hasActiveSearchResult(any(), any(), any());
    verify(conversations, never()).completeAssistantTurn(any(), any(), any());
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

  @ParameterizedTest
  @EnumSource(CancellationSignal.class)
  void emitterTerminationCancelsTheProviderAndReleasesTheWorkerForTheNextTurn(
      CancellationSignal cancellationSignal) throws Exception {
    UUID owner = UUID.randomUUID();
    UUID firstConversationId = UUID.randomUUID();
    UUID secondConversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID secondUserMessageId = UUID.randomUUID();
    AssistantConversationService conversations = mock(AssistantConversationService.class);
    when(conversations.beginTurn(owner, firstConversationId, "first"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                firstConversationId, inquiryId, UUID.randomUUID(), "first"));
    when(conversations.beginTurn(owner, secondConversationId, "second"))
        .thenReturn(
            new AssistantConversationService.TurnStart(
                secondConversationId, inquiryId, secondUserMessageId, "second"));
    when(conversations.promptMessages(owner, firstConversationId)).thenReturn(List.of());
    when(conversations.promptMessages(owner, secondConversationId)).thenReturn(List.of());
    AssistantMessage completed = mock(AssistantMessage.class);
    when(completed.getId()).thenReturn(UUID.randomUUID());
    when(conversations.completeAssistantTurn(owner, secondConversationId, "done"))
        .thenReturn(completed);
    AssistantToolExecutor tools = mock(AssistantToolExecutor.class);
    when(tools.execute(
            eq(owner),
            eq(secondConversationId),
            eq(inquiryId),
            eq(secondUserMessageId),
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
    CountDownLatch firstProviderEntered = new CountDownLatch(1);
    CountDownLatch firstProviderReleased = new CountDownLatch(1);
    AtomicInteger providerCalls = new AtomicInteger();
    ChatCompletionClient provider =
        (ignoredRequest, listener) -> {
          if (providerCalls.incrementAndGet() == 1) {
            firstProviderEntered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
              firstProviderReleased.countDown();
              throw new AssistantProviderException("provider interrupted", interrupted);
            }
          }
          if (providerCalls.get() == 2) {
            toolCall(
                listener,
                "second-search",
                AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                "{}");
          } else {
            listener.onContent("done");
            listener.onFinish("stop");
          }
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AssistantTurnService service =
          new AssistantTurnService(
              conversations,
              provider,
              new AssistantToolDefinitions(),
              tools,
              mapper,
              properties(),
              executor);
      CapturingEmitter firstEmitter = new CapturingEmitter();
      CapturingEmitter secondEmitter = new CapturingEmitter();

      service.stream(owner, firstConversationId, "first", "current-user-bearer", firstEmitter);
      assertThat(firstProviderEntered.await(1, TimeUnit.SECONDS)).isTrue();
      firstEmitter.cancel(cancellationSignal);
      assertThat(firstProviderReleased.await(1, TimeUnit.SECONDS)).isTrue();
      service.stream(owner, secondConversationId, "second", "current-user-bearer", secondEmitter);

      awaitAtMost(Duration.ofSeconds(1), () -> providerCalls.get() == 3);
      awaitAtMost(
          Duration.ofSeconds(1),
          () ->
              secondEmitter.events.stream()
                  .anyMatch(event -> event.event().startsWith("turn.") && !"turn.started".equals(event.event())));
      assertThat(secondEmitter.events)
          .withFailMessage("Second turn events: %s", secondEmitter.events)
          .anyMatch(event -> "turn.completed".equals(event.event()));
      assertThat(firstEmitter.events)
          .anySatisfy(
              event -> {
                assertThat(event.event()).isEqualTo("turn.failed");
                assertThat(event.code()).isEqualTo("PROVIDER_FAILED");
              })
          .noneMatch(event -> "turn.completed".equals(event.event()));
      assertThat(providerCalls).hasValue(3);
      verify(conversations, never())
          .completeAssistantTurn(eq(owner), eq(firstConversationId), any());
    } finally {
      executor.shutdownNow();
    }
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
                new AssistantConversationService.PromptMessage("user", "Покажи БК-1", List.of())));
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
                      {"tool":"request_cabin_clarifications","data":{"questions":[{}]}}
                      """),
                  UUID.randomUUID());
            });
    CapturingEmitter emitter = new CapturingEmitter();

    new AssistantTurnService(
            conversations,
            provider,
            new AssistantToolDefinitions(),
            tools,
            mapper,
            properties(),
            Runnable::run)
        .stream(owner, conversationId, "Покажи ещё БК-2", "current-user-bearer", emitter);

    assertThat(provider.requests).hasSize(1);
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG,
            AssistantToolDefinitions.REMOVE_SELECTED_CABINS);
    assertThat(provider.requests.getFirst().messages().getFirst().content())
        .contains("DYNAMIC SELECTION CONTEXT: There is an active");
    assertThat(emitter.events.getLast().event()).isEqualTo("turn.completed");
    assertThat(emitter.events.getLast().messageId()).isNull();
    verify(conversations, never()).completeAssistantTurn(any(), any(), any());
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
    when(conversations.completeAssistantTurn(owner, conversationId, "Подбор обновлён."))
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
            "Покажи свободные бытовки",
            "current-user-bearer",
            new SseEmitter());

    assertThat(provider.requests).hasSize(3);
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    assertThat(provider.requests.get(1).toolRequired()).isTrue();
    assertThat(provider.requests.get(1).tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    assertThat(provider.requests.get(2).toolRequired()).isFalse();
    verify(conversations).completeAssistantTurn(owner, conversationId, "Подбор обновлён.");
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
        .stream(
            owner,
            conversationId,
            "Покажи свободные БК-1",
            "current-user-bearer",
            new SseEmitter());

    assertThat(provider.requests).hasSize(3);
    assertThat(provider.requests.getFirst().toolRequired()).isTrue();
    assertThat(provider.requests.getFirst().tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    assertThat(provider.requests.get(1).toolRequired()).isTrue();
    assertThat(provider.requests.get(1).tools())
        .extracting(ChatCompletionClient.ToolDefinition::name)
        .containsExactly(
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
            AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    assertThat(provider.requests.get(2).toolRequired()).isFalse();
    verify(conversations).completeAssistantTurn(owner, conversationId, "Найдены текущие варианты.");
  }

  @Test
  void acceptsNeutralCompletionAfterOneAuthoritativeEmptySearch() {
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
    when(conversations.completeAssistantTurn(owner, conversationId, "Подбор обновлён."))
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

    assertThat(provider.requests).hasSize(2);
    assertThat(provider.requests.get(1).toolRequired()).isFalse();
    verify(conversations).completeAssistantTurn(owner, conversationId, "Подбор обновлён.");
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
    when(conversations.completeAssistantTurn(
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
        .stream(
            owner,
            conversationId,
            "Покажи свободные бытовки",
            "current-user-bearer",
            new SseEmitter());

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
        .containsExactly(
            "system", "user", "assistant", "tool", "assistant", "tool", "assistant", "user");
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
        Duration.ofSeconds(1),
        false);
  }

  private static void awaitAtMost(Duration timeout, CheckedCondition condition) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (condition.matches()) return;
      Thread.sleep(10);
    }
    assertThat(condition.matches()).isTrue();
  }

  @FunctionalInterface
  private interface CheckedCondition {
    boolean matches() throws Exception;
  }

  private enum CancellationSignal {
    TIMEOUT,
    ERROR,
    COMPLETION
  }

  private static AssistantApiModels.ClarificationQuestionResponse question(
      int sequenceNumber, String status, UUID answeredOptionId) {
    UUID firstOptionId = answeredOptionId == null ? UUID.randomUUID() : answeredOptionId;
    return new AssistantApiModels.ClarificationQuestionResponse(
        UUID.randomUUID(),
        "ordered:" + sequenceNumber,
        sequenceNumber,
        "CABIN_TYPE",
        "Уточнение " + sequenceNumber,
        status,
        List.of(
            new AssistantApiModels.ClarificationOptionResponse(firstOptionId, "Модуль", "Модуль"),
            new AssistantApiModels.ClarificationOptionResponse(
                UUID.randomUUID(), "Пост охраны", "Пост охраны")),
        answeredOptionId,
        OffsetDateTime.parse("2026-08-09T12:00:00Z").plusMinutes(sequenceNumber),
        answeredOptionId == null
            ? null
            : OffsetDateTime.parse("2026-08-09T12:10:00Z").plusMinutes(sequenceNumber));
  }

  private static void toolCall(
      ChatCompletionClient.ChatCompletionListener listener,
      String id,
      String name,
      String arguments) {
    listener.onToolCallDelta(new ChatCompletionClient.ToolCallDelta(0, id, name, arguments));
    listener.onFinish("tool_calls");
  }

  /** Provider fixture that first violates the guard, then searches and finally returns text. */
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

  /** Provider fixture that asks a merge question before returning its user-facing prompt. */
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
                AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
                "{}");
        case 2 -> {
          listener.onContent("Добавить к текущей подборке или заменить её?");
          listener.onFinish("stop");
        }
        default -> throw new AssertionError("Unexpected provider round");
      }
    }
  }

  /** Provider fixture proving that facets alone do not satisfy the outcome guard. */
  private static final class FacetThenSearchProvider implements ChatCompletionClient {
    private final List<ChatCompletionRequest> requests = new ArrayList<>();

    @Override
    public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
      requests.add(request);
      switch (requests.size()) {
        case 1 -> {
          listener.onContent("Непроверенный текст вместе с фасетами.");
          toolCall(listener, "facets", AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS, "{}");
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

  /** Provider fixture issuing the single call that reports a terminal archived inquiry. */
  private static final class ArchivedInquiryProvider implements ChatCompletionClient {
    private final List<ChatCompletionRequest> requests = new ArrayList<>();

    @Override
    public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
      requests.add(request);
      if (requests.size() != 1)
        throw new AssertionError("Archived inquiry must finish immediately");
      toolCall(
          listener,
          "archived-search",
          AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
          "{\"groups\":[{\"quantity\":1}]}");
    }
  }

  /** Provider fixture returning neutral prose after one authoritative empty search. */
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
          listener.onContent("Подбор обновлён.");
          listener.onFinish("stop");
        }
        default -> throw new AssertionError("Unexpected provider round");
      }
    }
  }

  /** Captures structured event payloads without starting an HTTP response. */
  private static final class CapturingEmitter extends SseEmitter {
    private final List<AssistantApiModels.TurnEvent> events = new CopyOnWriteArrayList<>();
    private Runnable timeout;
    private Consumer<Throwable> error;
    private Runnable completion;

    @Override
    public void onTimeout(Runnable callback) {
      this.timeout = callback;
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
      this.error = callback;
    }

    @Override
    public void onCompletion(Runnable callback) {
      this.completion = callback;
    }

    @Override
    public void send(SseEventBuilder builder) {
      builder.build().stream()
          .map(
              org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
                      .DataWithMediaType
                  ::getData)
          .filter(AssistantApiModels.TurnEvent.class::isInstance)
          .map(AssistantApiModels.TurnEvent.class::cast)
          .forEach(events::add);
    }

    @Override
    public void complete() {
      if (completion != null) completion.run();
    }

    void cancel(CancellationSignal signal) {
      switch (signal) {
        case TIMEOUT -> timeout.run();
        case ERROR -> error.accept(new IllegalStateException("client disconnected"));
        case COMPLETION -> completion.run();
      }
    }
  }
}
