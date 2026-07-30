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
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

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
  private static final String ALTERNATIVE_SEARCH_REMINDER =
      """
      The immediately preceding exact cabin search returned no cabins. Do not
      answer or ask whether alternatives are wanted. Call search_available_cabins
      now with broader alternative groups made by removing one requested filter
      at a time. The tool result, not a textual guess, must supply the alternatives.
      """;
  private static final String FRESH_SEARCH_REMINDER =
      """
      This current user turn still has no fresh cabin-search attempt. Do not
      answer yet. Call search_available_cabins now. A tool failure is a current
      technical failure that must not be mentioned in final prose; it is never
      evidence that no cabins are available.
      """;
  static final String SYSTEM_PROMPT =
      """
      You are the RWMS rental assistant. Answer only from the current conversation
      and tool results. You have no database, SQL, web browsing, embeddings, or
      hidden deterministic parser. Every user turn in this cabin-selection chat
      must make a fresh search_available_cabins attempt before you return text.
      The only clarification exception is a successful
      request_search_merge_confirmation call while the dynamic selection context
      says that an unexpired unpublished selection exists. That tool asks whether
      to add to the current selection or replace it; it is not a search. The
      next user answer still requires a fresh cabin search. The model, not the
      browser, decides whether a request is explicitly add/append or replace.
      On the first provider round, use the supplied tools to learn exact facets
      when needed, then make the required current search. Do not invent
      availability. Do not request, repeat, or infer client phone numbers or
      email addresses. Keep answers concise: describe only cabins returned by
      tools, or use a neutral completion when no cabins were returned.

      Treat a cabin-facet result only as a bounded list of exact valid filter
      values. It never proves that a cabin type, finish, or dimension combination
      is available. Only a cabin-search result proves current availability.
      The service has no local parser. You may interpret natural language or an
      apparent typo only after facets provide one reasonably unambiguous exact
      value; use that exact value in a search and tell the user which value you
      interpreted. Prefer a unique one-character correction and continue instead
      of asking; for example, interpret requested "ТВП" as returned facet "ДВП"
      when no "ТВП" facet exists. Ask only when multiple facet values are equally
      plausible. Tool arguments must always use an exact returned facet value;
      never invent a value.

      A normal search must contain only groups and units the user explicitly
      requested. Preserve every applicable prior filter and use the exact user
      quantity. Every search result contains only FREE cabins. cabinType, finish,
      dimensions, and category are base exact facets and must use exact values
      returned by facets. characteristics forwards the user's requested
      characteristic text exactly; do not invent, normalize, or infer it.
      Set linoleum to true when the user requests linoleum and false only when
      the user explicitly requests no linoleum; otherwise omit it. The category
      is an independent exact facet: use category "Новая" whenever the user
      explicitly requests new cabins, including "новые", "покажи новые", or
      "только новые"; the word "только" is not required. "Новая" is a
      category, never a cabin status. For default, "all", "show", "free",
      "available", "все", "свободные", or "доступные" requests, omit category
      so all FREE categories are searched. Preserve the applicable category,
      as well as filters and quantity, in every follow-up, show-all refresh, and
      broader alternative search. Do not add extra groups or units. Every
      distinct requested combination or type needs a separate logical group
      with its own requested quantity: for example, six БК-1 plus six БК-2 means
      two groups, each with quantity 6. When the user explicitly requests
      multiple exact categories for one type, put those exact facet values in
      that one group's categories array, not in separate logical groups. When a
      user asks for one total split/divided across cabin types (for example,
      "Покажи 10 свободных бытовок в СПБ, подели по типам, категории только
      Обычная или ИТР"), list facets first, create one logical group for every
      exact applicable cabin type, put categories ["Обычная", "ИТР"] in each
      group, omit every group quantity, and set totalQuantity to 10. The
      categories array is exact OR filtering; never use category and categories
      together. Do not use totalQuantity with per-group quantities. The sum of
      explicit per-group quantities must not exceed 100. Every request to show, repeat,
      retry, refresh, recheck, or search for cabins requires at least one new
      search_available_cabins call in that same turn. Never claim current
      availability from an earlier search result; use history only to recover
      the applicable structured filters. When the user explicitly asks to show
      all/everything available (including a follow-up such as "show all" or
      "покажи все") without changing filters, that asks to expand the last
      applicable search, not to discard its filters: refresh/check facets, then
      call search_available_cabins again with the same exact filters and quantity
      30, the tool maximum. If the search returns fewer cabins, show every cabin
      returned instead of replying with only a count.

      An empty exact search is the only exception to the rule against additional
      groups. Do not answer immediately and do not merely name possible
      alternatives. You MUST make one additional search_available_cabins call
      for real alternatives. Derive each alternative by removing exactly one of
      the empty group's requested filters while preserving the warehouse and all
      remaining filters; never replace a filter with a guessed value. Preserve
      the requested quantity, except that an explicit show-all request uses
      quantity 30 for alternatives too. For example, if cabinType plus finish is
      empty, search once by the same cabinType without finish and once by the
      same finish without cabinType. In the final prose, describe only cabins
      actually returned by tools; do not mention that an exact group was absent
      or unavailable, a requested quantity was incomplete, alternatives, a
      technical failure, an error, or a retry. The panel renders those outcomes
      separately from structured tool notices. Never present alternatives as an
      exact match or silently substitute them. Use at most three tool-call rounds
      in a turn so that a final answer can still be produced.

      A search result can contain structured notices. They are authoritative
      UI-only availability feedback and are rendered after your response. Do
      not repeat, paraphrase, summarise, or otherwise refer to a
      CABINS_NOT_FOUND or CABINS_PARTIALLY_FOUND notice in final prose. If no
      cabins were returned, use a short neutral completion such as "Подбор
      обновлён." without explaining why. Do not describe a technical failure in
      final prose either; the panel handles it separately.

      When the dynamic selection context says an active unpublished selection
      exists, use request_search_merge_confirmation only when the latest user
      request introduces an additional new cabin group, type, or set and does
      not explicitly say add/append or replace. Do not ask that question for a
      normal refresh, retry, recheck, "show all such cabins", or a refinement of
      the currently displayed request; those must make a fresh REPLACE search
      directly unless the user explicitly asks to append. After a merge
      clarification, ask exactly whether to add to the current selection or
      replace it and do not run a cabin search in that clarification turn. After
      a later add/yes answer, make the fresh search with resultMode APPEND.
      After a later replace/no answer, make it with resultMode REPLACE. If there
      is no active selection, that confirmation tool is unavailable and you must
      not ask about old history.

      A tool result with a failure code, including LOGISTICS_UNAVAILABLE,
      TOOL_FAILED, or TOOL_ARGUMENTS_INVALID, is a technical failure, not an
      empty search result. Do not mention this failure in final prose and never
      claim that there are no cabins based on a failure code.
      INQUIRY_ARCHIVED is different: it means the rental dialog was already
      completed, not that availability failed. Give a concise completion message
      and do not offer or attempt another cabin search.
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

  public void stream(
      UUID ownerSubjectId,
      UUID conversationId,
      String message,
      String bearerToken,
      SseEmitter emitter) {
    executor.execute(
        () -> runTurn(ownerSubjectId, conversationId, message, bearerToken, emitter));
  }

  private void runTurn(
      UUID ownerSubjectId,
      UUID conversationId,
      String message,
      String bearerToken,
      SseEmitter emitter) {
    try {
      AssistantConversationService.TurnStart started =
          conversations.beginTurn(ownerSubjectId, conversationId, message);
      emit(
          emitter,
          new AssistantApiModels.TurnEvent(
              "turn.started",
              conversationId,
              started.userMessageId(),
              null,
              null,
              null,
              null));

      boolean hasActiveSearchResult =
          conversations.hasActiveSearchResult(ownerSubjectId, conversationId, bearerToken);
      List<ChatMessage> history = history(ownerSubjectId, conversationId, hasActiveSearchResult);
      StringBuilder completeText = new StringBuilder();
      List<ToolDefinition> toolDefinitions = definitions.definitions(hasActiveSearchResult);
      FreshSearchGuard freshSearch = new FreshSearchGuard();
      AlternativeSearchGuard alternativeSearch = new AlternativeSearchGuard();
      boolean suppressAvailabilityExplanations = false;
      for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        ToolCallCollector calls = new ToolCallCollector();
        FinishTracker finish = new FinishTracker();
        StringBuilder roundText = new StringBuilder();
        boolean toolCallRequired = freshSearch.required() || alternativeSearch.required();
        List<ToolDefinition> roundToolDefinitions =
            toolCallRequired && round > 0
                ? toolDefinitions.stream()
                    .filter(
                        definition ->
                            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(
                                definition.name()))
                    .toList()
                : toolDefinitions;
        provider.stream(
            new ChatCompletionClient.ChatCompletionRequest(
                llm.model(), history, roundToolDefinitions, toolCallRequired),
            new ChatCompletionClient.ChatCompletionListener() {
              @Override
              public void onContent(String delta) {
                if (toolCallRequired) return;
                roundText.append(delta);
              }

              @Override
              public void onToolCallDelta(ToolCallDelta delta) {
                calls.append(delta);
              }

              @Override
              public void onFinish(String finishReason) {
                finish.reason = finishReason;
              }
            });

        if ("tool_calls".equals(finish.reason)) {
          List<ProviderToolCall> requested = calls.build();
          if (requested.isEmpty()) {
            throw new AssistantProviderException("LLM provider returned incomplete tool calls");
          }
          history.add(ChatMessage.assistantToolCalls(requested));
          for (ProviderToolCall call : requested) {
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
            if (isInquiryArchived(execution.result())) {
              completeArchivedInquiryTurn(ownerSubjectId, conversationId, emitter);
              return;
            }
            freshSearch.record(call.name(), execution.result());
            alternativeSearch.record(call.name(), execution.result());
          }
          addSearchReminder(history, freshSearch, alternativeSearch);
          continue;
        }
        if (!"stop".equals(finish.reason)) {
          throw new AssistantProviderException("LLM provider did not complete the turn");
        }
        if (toolCallRequired) {
          addSearchReminder(history, freshSearch, alternativeSearch);
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
        emit(
            emitter,
            new AssistantApiModels.TurnEvent(
                "assistant.delta",
                conversationId,
                null,
                null,
                assistantText,
                null,
                null));
        var assistant =
            conversations.completeAssistantTurn(ownerSubjectId, conversationId, assistantText);
        emit(
            emitter,
            new AssistantApiModels.TurnEvent(
                "turn.completed",
                conversationId,
                assistant.getId(),
                null,
                null,
                null,
                null));
        emitter.complete();
        return;
      }
      throw new AssistantProviderException("LLM provider exceeded the tool-call limit");
    } catch (AssistantNotFoundException failure) {
      fail(emitter, conversationId, "CONVERSATION_UNAVAILABLE");
    } catch (AssistantProviderException failure) {
      fail(emitter, conversationId, "PROVIDER_FAILED");
    } catch (RuntimeException failure) {
      fail(emitter, conversationId, "TURN_FAILED");
    }
  }

  private List<ChatMessage> history(
      UUID ownerSubjectId, UUID conversationId, boolean hasActiveSearchResult) {
    List<ChatMessage> result = new ArrayList<>();
    result.add(ChatMessage.system(SYSTEM_PROMPT + "\n\n" + selectionContext(hasActiveSearchResult)));
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
            throw new AssistantProviderException("Persisted tool calls are not attached to a user turn");
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

  private void completeArchivedInquiryTurn(
      UUID ownerSubjectId, UUID conversationId, SseEmitter emitter) {
    emit(
        emitter,
        new AssistantApiModels.TurnEvent(
            "assistant.delta",
            conversationId,
            null,
            null,
            ARCHIVED_INQUIRY_MESSAGE,
            null,
            null));
    var assistant =
        conversations.completeAssistantTurn(ownerSubjectId, conversationId, ARCHIVED_INQUIRY_MESSAGE);
    // Persist the final user-facing message before hiding the conversation from
    // active lists; future turns then fail beginTurn's archived=false predicate.
    conversations.archive(ownerSubjectId, conversationId);
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
      if ("CABINS_NOT_FOUND".equals(noticeCode)
          || "CABINS_PARTIALLY_FOUND".equals(noticeCode)) {
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

  private static void addSearchReminder(
      List<ChatMessage> history,
      FreshSearchGuard freshSearch,
      AlternativeSearchGuard alternativeSearch) {
    if (alternativeSearch.required()) {
      history.add(ChatMessage.system(ALTERNATIVE_SEARCH_REMINDER));
    } else if (freshSearch.required()) {
      history.add(ChatMessage.system(FRESH_SEARCH_REMINDER));
    }
  }

  private static void emit(SseEmitter emitter, AssistantApiModels.TurnEvent event) {
    try {
      emitter.send(SseEmitter.event().name(event.event()).data(event, MediaType.APPLICATION_JSON));
    } catch (IOException disconnected) {
      // The durable turn state remains correct even if a browser disconnects.
    }
  }

  private static void fail(SseEmitter emitter, UUID conversationId, String code) {
    emit(
        emitter,
        new AssistantApiModels.TurnEvent(
            "turn.failed", conversationId, null, null, null, null, code));
    emitter.complete();
  }

  private static final class FinishTracker {
    private String reason;
  }

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

  private static final class MutableToolCall {
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
  }

  private static final class FreshSearchGuard {
    private boolean required = true;

    boolean required() {
      return required;
    }

    void record(String toolName, tools.jackson.databind.JsonNode result) {
      if (AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(toolName)) {
        required = false;
        return;
      }
      if (AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION.equals(toolName)
          && AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION.equals(
              result.path("tool").asText())) {
        required = false;
      }
    }
  }

  private static final class AlternativeSearchGuard {
    private boolean required;
    private boolean alternativeAttempted;

    boolean required() {
      return required;
    }

    void record(String toolName, tools.jackson.databind.JsonNode result) {
      if (!AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(toolName)) return;
      if (required) {
        required = false;
        alternativeAttempted = true;
        return;
      }
      if (!alternativeAttempted && isEmptyCabinSearch(result)) {
        required = true;
      }
    }

    private static boolean isEmptyCabinSearch(tools.jackson.databind.JsonNode result) {
      tools.jackson.databind.JsonNode groups = result.path("data").path("groups");
      if (!groups.isArray()) return false;
      for (tools.jackson.databind.JsonNode group : groups) {
        tools.jackson.databind.JsonNode cabins = group.path("cabins");
        if (!cabins.isArray() || !cabins.isEmpty()) return false;
      }
      return true;
    }
  }
}
