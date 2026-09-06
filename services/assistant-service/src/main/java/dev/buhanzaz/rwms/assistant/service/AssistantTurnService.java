package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.config.AssistantLlmProperties;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient.ChatMessage;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient.ProviderToolCall;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient.ToolCallDelta;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient.ToolDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs one asynchronous LLM-assisted turn, persists the server-authoritative outcome and streams
 * only safe SSE events.
 */
@Service
public class AssistantTurnService {
  private static final int MAX_TOOL_ROUNDS = 6;
  private static final String NEUTRAL_SEARCH_COMPLETION = "Подбор обновлён.";
  private static final Pattern AVAILABILITY_EXPLANATION =
      Pattern.compile(
          "(?iu)(?:не\\s*(?:найден[а-я]*|наш[её]л[а-я]*|доступ[а-я]*|хватает|заверш[а-я]*|"
              + "представлен[а-я]*|обнаруж[а-я]*|удал[а-я]*)|недоступ[а-я]*|отсутств[а-я]*|"
              + "техническ[а-я]*\\s+ошиб[а-я]*|ошибк[а-я]*|попробуйте\\s+позже|"
              + "уточните\\s+запрос|нет\\s+(?:свобод[а-я]*|вариант[а-я]*|результат[а-я]*|"
              + "данных|кабин[а-я]*|связ[а-я]*)|unavailable|not\\s+found|technical\\s+error|"
              + "try\\s+again)");
  private static final String OUTCOME_TOOL_REMINDER =
      """
      This user turn still has no authoritative outcome. Do not answer yet. For
      availability, call search_available_cabins or request exact clarification
      buttons. For a factual question, call lookup_cabin_catalog. For requested
      selection removal, call remove_selected_cabins. Listing facets alone is not
      an outcome. A tool failure is never evidence that cabins are unavailable.
      """;
  static final String SYSTEM_PROMPT =
      """
      You are the RWMS rental assistant. Answer only from this conversation and
      current tool results. You have no SQL, browser, hidden catalog, availability
      state or hold state. Never invent a facet, relation, cabin or availability.
      Do not request, repeat or infer client phone numbers or email addresses.

      Choose one authoritative outcome before replying. For availability or a
      request to show/select cabins, use list_available_cabin_facets as needed,
      then search_available_cabins. For factual questions about cabin numbers,
      types, finishes, dimensions, type-to-dimension relations, characteristics
      or linoleum, use lookup_cabin_catalog; it is read-only and creates no hold.
      For ambiguity, use request_cabin_clarifications or let the search service
      return exact persistent questions. For removal, use remove_selected_cabins.
      Facets are exact filter metadata, never proof of availability.

      Every availability group must have an exact current cabinType and finish
      before any result or hold. A finish such as ЛДСП, ОСБ or ДВП is not a cabin
      type: for “покажи 2 ЛДСП”, preserve finish ЛДСП and quantity 2, then ask
      which cabin type is needed. If several exact choices are needed, request
      them in deterministic order. Only the first question is shown and answered;
      the next one is shown only after that answer. Never create or continue two
      independent clarification branches. Use only current metadata options.

      Dimensions must be related to the selected exact type. If that type has one
      current size, omission may resolve to it. If it has several, ask with exact
      size buttons before searching. Interpret “6 метров” only through current
      type-dimension relations as an exact 6x2.4-equivalent facet. If type is
      missing, narrow the type buttons to compatible relations; auto-resolve only
      one compatible type and never apply a global six-metre guess. “Модуль” or
      “пост охраны” may be proposed only when the returned relations contain those
      exact related choices. Never manufacture a relation.

      Use exact returned category and characteristics values. Set linoleum=true
      only when linoleum is requested, false only when explicitly excluded, and
      otherwise omit it. Multiple requested types/combinations are separate
      groups. Use the user’s exact quantity (30 only for an explicit show-all).
      Do not add cabins or groups the user did not request. Omitted resultMode is
      REPLACE. APPEND is allowed only when the user explicitly chooses to add;
      otherwise ask a SEARCH_MERGE question with exact APPEND/REPLACE buttons when
      an active selection exists. APPEND must use at most one exact category and
      per-group quantities; never use shared-total/category capacity probes for
      APPEND.

      A successful search result contains only current held FREE cabins. Describe
      only those returned cabins. Structured notices are rendered by the panel;
      do not paraphrase CABINS_NOT_FOUND or CABINS_PARTIALLY_FOUND. If no cabins
      are returned, say only “Подбор обновлён.” After results, you may offer other
      search parameters only from the result’s filterSuggestions arrays; never
      invent a follow-up filter. Historical results do not prove current holds.
      remove_selected_cabins accepts only exact IDs/numbers already in the current
      authoritative selection; do not construct arbitrary replacement state.

      A failure code is a technical failure, not an empty result. Do not claim
      that cabins are absent because of LOGISTICS_UNAVAILABLE, TOOL_FAILED or
      TOOL_ARGUMENTS_INVALID. INQUIRY_ARCHIVED means the rental dialog is already
      complete; reply concisely and do not call another tool.
      """;
  private static final String ARCHIVED_INQUIRY_MESSAGE =
      "Этот диалог уже завершён: подборка больше не активна.";

  private final AssistantConversationService conversations;
  private final ChatCompletionClient provider;
  private final AssistantToolDefinitions definitions;
  private final AssistantToolExecutor tools;
  private final ObjectMapper mapper;
  private final AssistantLlmProperties llm;
  private final Executor executor;

  public AssistantTurnService(
      AssistantConversationService conversations,
      ChatCompletionClient provider,
      AssistantToolDefinitions definitions,
      AssistantToolExecutor tools,
      ObjectMapper mapper,
      AssistantLlmProperties llm,
      @Qualifier("assistantTurnExecutor") Executor executor) {
    this.conversations = conversations;
    this.provider = provider;
    this.definitions = definitions;
    this.tools = tools;
    this.mapper = mapper;
    this.llm = llm;
    this.executor = executor;
  }

  /**
   * Persists and validates the user input synchronously, then schedules provider work on the
   * bounded executor. Stale or out-of-order clarification answers therefore fail as an HTTP
   * conflict before an SSE stream is accepted.
   */
  public void stream(
      UUID ownerSubjectId,
      UUID conversationId,
      String message,
      String bearerToken,
      SseEmitter emitter) {
    stream(
        ownerSubjectId,
        conversationId,
        new AssistantApiModels.TurnRequest(message),
        bearerToken,
        emitter);
  }

  /**
   * Schedules free text or one exact persisted clarification answer. SSE termination cancels
   * unfinished work; terminal persistence is fenced against cancellation and retains completed effects.
   */
  public void stream(
      UUID ownerSubjectId,
      UUID conversationId,
      AssistantApiModels.TurnRequest request,
      String bearerToken,
      SseEmitter emitter) {
    AssistantConversationService.TurnStart started =
        request.clarificationAnswer() == null
            ? conversations.beginTurn(ownerSubjectId, conversationId, request.message())
            : conversations.beginTurn(ownerSubjectId, conversationId, request);
    TurnLifecycle lifecycle = new TurnLifecycle();
    FutureTask<Void> turn =
        new FutureTask<>(
            () -> {
              runTurn(ownerSubjectId, conversationId, started, bearerToken, emitter, lifecycle);
              return null;
            });
    Runnable cancel = () -> lifecycle.cancel(turn);
    emitter.onTimeout(cancel);
    emitter.onError(ignored -> cancel.run());
    emitter.onCompletion(cancel);
    executor.execute(turn);
  }

  private void runTurn(
      UUID ownerSubjectId,
      UUID conversationId,
      AssistantConversationService.TurnStart started,
      String bearerToken,
      SseEmitter emitter,
      TurnLifecycle lifecycle) {
    try {
      emit(
          emitter,
          new AssistantApiModels.TurnEvent(
              "turn.started", conversationId, started.userMessageId(), null, null, null, null));
      if (started.answeredClarification() != null) {
        emit(
            emitter,
            new AssistantApiModels.TurnEvent(
                "clarification.answered",
                conversationId,
                started.userMessageId(),
                null,
                null,
                null,
                null,
                started.answeredClarification()));
      }
      if (started.nextClarification() != null) {
        emit(
            emitter,
            new AssistantApiModels.TurnEvent(
                "clarification.requested",
                conversationId,
                null,
                started.nextClarificationToolCallId(),
                null,
                null,
                null,
                started.nextClarification()));
        completeParkedClarificationTurn(conversationId, emitter, lifecycle);
        return;
      }

      boolean hasActiveSearchResult =
          conversations.hasActiveSearchResult(ownerSubjectId, conversationId, bearerToken);
      List<ChatMessage> history =
          history(
              ownerSubjectId,
              conversationId,
              hasActiveSearchResult,
              started.rentalOrderId() != null);
      StringBuilder completeText = new StringBuilder();
      List<ToolDefinition> toolDefinitions = definitions.definitions(hasActiveSearchResult);
      OutcomeToolGuard outcome = new OutcomeToolGuard();
      boolean suppressAvailabilityExplanations = false;
      for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        lifecycle.requireActive();
        ToolCallCollector calls = new ToolCallCollector();
        FinishTracker finish = new FinishTracker();
        StringBuilder roundText = new StringBuilder();
        boolean toolCallRequired = outcome.required();
        List<ToolDefinition> roundToolDefinitions =
            toolCallRequired && round > 0
                ? toolDefinitions.stream()
                    .filter(
                        definition ->
                            !AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS.equals(
                                definition.name()))
                    .toList()
                : toolDefinitions;
        provider.stream(
            new ChatCompletionClient.ChatCompletionRequest(
                llm.model(), history, roundToolDefinitions, toolCallRequired),
            new ChatCompletionClient.ChatCompletionListener() {
              @Override
              public void onContent(String delta) {
                if (toolCallRequired || lifecycle.cancelled()) return;
                roundText.append(delta);
              }

              @Override
              public void onToolCallDelta(ToolCallDelta delta) {
                if (lifecycle.cancelled()) return;
                calls.append(delta);
              }

              @Override
              public void onFinish(String finishReason) {
                if (lifecycle.cancelled()) return;
                finish.reason = finishReason;
              }
            });
        lifecycle.requireActive();

        if ("tool_calls".equals(finish.reason)) {
          List<ProviderToolCall> requested = calls.build();
          if (requested.isEmpty()) {
            throw new AssistantProviderException("LLM provider returned incomplete tool calls");
          }
          history.add(ChatMessage.assistantToolCalls(requested));
          for (ProviderToolCall call : requested) {
            lifecycle.requireActive();
            AssistantToolExecutor.ToolExecution execution =
                tools.execute(
                    ownerSubjectId,
                    conversationId,
                    started.rentalInquiryId(),
                    started.userMessageId(),
                    call,
                    bearerToken,
                    event -> emit(emitter, event));
            suppressAvailabilityExplanations |= hasAvailabilityFeedback(execution.result());
            history.add(ChatMessage.tool(call.id(), serialize(execution.result())));
            if (isClarificationRequested(execution.result())) {
              completeParkedClarificationTurn(conversationId, emitter, lifecycle);
              return;
            }
            if (isInquiryArchived(execution.result())) {
              completeArchivedInquiryTurn(ownerSubjectId, conversationId, emitter, lifecycle);
              return;
            }
            outcome.record(call.name());
          }
          addOutcomeReminder(history, outcome);
          continue;
        }
        if (!"stop".equals(finish.reason)) {
          throw new AssistantProviderException("LLM provider did not complete the turn");
        }
        if (toolCallRequired) {
          addOutcomeReminder(history, outcome);
          continue;
        }
        completeText.append(roundText);
        if (completeText.isEmpty()) {
          throw new AssistantProviderException("LLM provider returned an empty assistant turn");
        }
        String assistantText =
            suppressAvailabilityExplanations
                ? withoutAvailabilityExplanations(completeText.toString())
                : completeText.toString();
        var assistant =
            lifecycle.finish(
                () ->
                    conversations.completeAssistantTurn(
                        ownerSubjectId, conversationId, assistantText));
        emit(
            emitter,
            new AssistantApiModels.TurnEvent(
                "assistant.delta", conversationId, null, null, assistantText, null, null));
        emit(
            emitter,
            new AssistantApiModels.TurnEvent(
                "turn.completed", conversationId, assistant.getId(), null, null, null, null));
        emitter.complete();
        return;
      }
      throw new AssistantProviderException("LLM provider exceeded the tool-call limit");
    } catch (AssistantNotFoundException failure) {
      fail(emitter, conversationId, "CONVERSATION_UNAVAILABLE", lifecycle);
    } catch (AssistantProviderException failure) {
      fail(emitter, conversationId, "PROVIDER_FAILED", lifecycle);
    } catch (RuntimeException failure) {
      fail(emitter, conversationId, "TURN_FAILED", lifecycle);
    }
  }

  private List<ChatMessage> history(
      UUID ownerSubjectId,
      UUID conversationId,
      boolean hasActiveSearchResult,
      boolean orderLinked) {
    List<ChatMessage> result = new ArrayList<>();
    result.add(
        ChatMessage.system(
            SYSTEM_PROMPT
                + "\n\n"
                + selectionContext(hasActiveSearchResult)
                + "\n\n"
                + orderContext(orderLinked)));
    for (AssistantConversationService.PromptMessage message :
        conversations.promptMessages(ownerSubjectId, conversationId)) {
      switch (message.role()) {
        case "user" -> {
          result.add(ChatMessage.user(message.content()));
          for (AssistantConversationService.PromptToolCall call : message.toolCalls()) {
            result.add(
                ChatMessage.assistantToolCalls(
                    List.of(
                        new ProviderToolCall(
                            call.providerCallId(), call.toolName(), serialize(call.arguments())))));
            result.add(ChatMessage.tool(call.providerCallId(), serialize(call.result())));
          }
        }
        case "assistant" -> {
          if (!message.toolCalls().isEmpty()) {
            throw new AssistantProviderException(
                "Persisted tool calls are not attached to a user turn");
          }
          result.add(ChatMessage.assistant(message.content()));
        }
        default -> throw new AssistantProviderException("Unsupported persisted message role");
      }
    }
    return result;
  }

  private static String selectionContext(boolean hasActiveSearchResult) {
    return hasActiveSearchResult
        ? "DYNAMIC SELECTION CONTEXT: There is an active, unexpired, unpublished cabin "
            + "selection in this conversation. It may be added to only after the manager "
            + "explicitly confirms that choice."
        : "DYNAMIC SELECTION CONTEXT: There is no active unexpired unpublished cabin selection. "
            + "Expired or historical search tool calls are not a current selection and must not "
            + "trigger a merge question.";
  }

  private static String orderContext(boolean orderLinked) {
    return orderLinked
        ? "DYNAMIC ORDER CONTEXT: This conversation amends one existing rental order. "
            + "Its client and any selected warehouse are immutable logistics-owned facts. "
            + "Use only the inquiry-scoped tools and never propose another warehouse."
        : "DYNAMIC ORDER CONTEXT: This conversation is not linked to an existing rental order.";
  }

  private static boolean isClarificationRequested(tools.jackson.databind.JsonNode result) {
    return AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS.equals(
            result.path("tool").asText())
        && result.path("data").path("questions").isArray()
        && !result.path("data").path("questions").isEmpty();
  }

  private static void completeParkedClarificationTurn(
      UUID conversationId, SseEmitter emitter, TurnLifecycle lifecycle) {
    lifecycle.finish(() -> null);
    emit(
        emitter,
        new AssistantApiModels.TurnEvent(
            "turn.completed", conversationId, null, null, null, null, null));
    emitter.complete();
  }

  private void completeArchivedInquiryTurn(
      UUID ownerSubjectId,
      UUID conversationId,
      SseEmitter emitter,
      TurnLifecycle lifecycle) {
    var assistant =
        lifecycle.finish(
            () -> {
              var completed =
                  conversations.completeAssistantTurn(
                      ownerSubjectId, conversationId, ARCHIVED_INQUIRY_MESSAGE);
              // Persist the final user-facing message before hiding the conversation from
              // active lists; future turns then fail beginTurn's archived=false predicate.
              conversations.archive(ownerSubjectId, conversationId);
              return completed;
            });
    emit(
        emitter,
        new AssistantApiModels.TurnEvent(
            "assistant.delta", conversationId, null, null, ARCHIVED_INQUIRY_MESSAGE, null, null));
    emit(
        emitter,
        new AssistantApiModels.TurnEvent(
            "turn.completed", conversationId, assistant.getId(), null, null, null, null));
    emitter.complete();
  }

  private static boolean isInquiryArchived(tools.jackson.databind.JsonNode result) {
    return "INQUIRY_ARCHIVED".equals(result.path("code").asText());
  }

  private static boolean hasAvailabilityFeedback(tools.jackson.databind.JsonNode result) {
    String code = result.path("code").asText();
    if (code.endsWith("_UNAVAILABLE")
        || code.endsWith("_FAILED")
        || "TOOL_ARGUMENTS_INVALID".equals(code)) {
      return true;
    }
    for (tools.jackson.databind.JsonNode notice : result.path("notices")) {
      String noticeCode = notice.path("code").asText();
      if ("CABINS_NOT_FOUND".equals(noticeCode) || "CABINS_PARTIALLY_FOUND".equals(noticeCode)) {
        return true;
      }
    }
    return false;
  }

  static String withoutAvailabilityExplanations(String text) {
    List<String> safeParagraphs = new ArrayList<>();
    for (String paragraph : text.split("\\R\\s*\\R")) {
      String candidate = paragraph.trim();
      if (!candidate.isEmpty() && !AVAILABILITY_EXPLANATION.matcher(candidate).find()) {
        safeParagraphs.add(candidate);
      }
    }
    return safeParagraphs.isEmpty()
        ? NEUTRAL_SEARCH_COMPLETION
        : String.join("\n\n", safeParagraphs);
  }

  private String serialize(tools.jackson.databind.JsonNode result) {
    return mapper.writeValueAsString(result);
  }

  private static void addOutcomeReminder(List<ChatMessage> history, OutcomeToolGuard outcome) {
    if (outcome.required()) history.add(ChatMessage.system(OUTCOME_TOOL_REMINDER));
  }

  private static void emit(SseEmitter emitter, AssistantApiModels.TurnEvent event) {
    try {
      emitter.send(SseEmitter.event().name(event.event()).data(event, MediaType.APPLICATION_JSON));
    } catch (IOException disconnected) {
      // The durable turn state remains correct even if a browser disconnects.
    }
  }

  private static void fail(
      SseEmitter emitter, UUID conversationId, String code, TurnLifecycle lifecycle) {
    if (!lifecycle.fail()) return;
    emit(
        emitter,
        new AssistantApiModels.TurnEvent(
            "turn.failed", conversationId, null, null, null, null, code));
    emitter.complete();
  }

  /** Fences cancellation against terminal persistence and emitter completion. */
  private static final class TurnLifecycle {
    private boolean cancelled;
    private boolean terminal;

    synchronized boolean cancelled() {
      return cancelled;
    }

    synchronized void requireActive() {
      if (cancelled) {
        throw new AssistantProviderException("Assistant turn was cancelled");
      }
    }

    synchronized <T> T finish(Supplier<T> completion) {
      requireActive();
      T result = completion.get();
      terminal = true;
      return result;
    }

    synchronized boolean fail() {
      if (terminal) return false;
      terminal = true;
      return true;
    }

    void cancel(FutureTask<Void> turn) {
      synchronized (this) {
        if (terminal || cancelled) return;
        cancelled = true;
      }
      turn.cancel(true);
    }
  }

  /** Captures the provider finish reason for one streamed round. */
  private static final class FinishTracker {
    private String reason;
  }

  /** Reassembles indexed streamed tool-call fragments into complete provider calls. */
  private static final class ToolCallCollector {
    private final Map<Integer, MutableToolCall> calls = new LinkedHashMap<>();

    void append(ToolCallDelta delta) {
      MutableToolCall call = calls.computeIfAbsent(delta.index(), ignored -> new MutableToolCall());
      if (delta.id() != null) call.id = delta.id();
      if (delta.name() != null) call.name = delta.name();
      if (delta.argumentsFragment() != null) call.arguments.append(delta.argumentsFragment());
    }

    List<ProviderToolCall> build() {
      return calls.entrySet().stream()
          .sorted(Comparator.comparingInt(Map.Entry::getKey))
          .map(
              entry -> {
                MutableToolCall value = entry.getValue();
                return new ProviderToolCall(value.id, value.name, value.arguments.toString());
              })
          .toList();
    }
  }

  /** Mutable fragment accumulator scoped to one provider tool-call index. */
  private static final class MutableToolCall {
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
  }

  /** Tracks whether a turn has reached one authoritative use-case outcome. */
  private static final class OutcomeToolGuard {
    private boolean required = true;

    boolean required() {
      return required;
    }

    void record(String toolName) {
      if (!AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS.equals(toolName)) {
        required = false;
      }
    }
  }
}
