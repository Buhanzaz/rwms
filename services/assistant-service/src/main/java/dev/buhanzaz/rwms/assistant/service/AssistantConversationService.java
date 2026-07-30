package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.domain.AssistantMessage;
import dev.buhanzaz.rwms.assistant.domain.AssistantMessageRole;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import dev.buhanzaz.rwms.assistant.mapper.AssistantResponseMapper;
import dev.buhanzaz.rwms.assistant.repository.AssistantConversationRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantMessageRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantToolCallRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AssistantConversationService {
  private final AssistantConversationRepository conversations;
  private final AssistantMessageRepository messages;
  private final AssistantToolCallRepository toolCalls;
  private final LogisticsClient logistics;
  private final AssistantResponseMapper mapper;

  public AssistantConversationService(
      AssistantConversationRepository conversations,
      AssistantMessageRepository messages,
      AssistantToolCallRepository toolCalls,
      LogisticsClient logistics,
      AssistantResponseMapper mapper) {
    this.conversations = conversations;
    this.messages = messages;
    this.toolCalls = toolCalls;
    this.logistics = logistics;
    this.mapper = mapper;
  }

  /**
   * A caller-supplied conversation UUID is the stable idempotency key. The
   * remote inquiry is deliberately created before local persistence.
   */
  @Transactional
  public AssistantApiModels.CreateConversationResponse create(
      UUID ownerSubjectId,
      AssistantApiModels.CreateConversationRequest request,
      String bearerToken) {
    UUID conversationId = request.conversationId() == null ? UUID.randomUUID() : request.conversationId();
    // A row does not exist on first use, so a transaction-scoped lock closes the
    // otherwise possible concurrent remote-create/local-insert race.
    conversations.acquireTransactionLock("assistant-conversation:" + conversationId);
    AssistantConversation existing = conversations.findById(conversationId).orElse(null);
    if (existing != null) {
      requireOwner(existing, ownerSubjectId);
      if (request.newClient() != null) {
        LogisticsClient.InquiryBootstrap replay =
            logistics.createRentalInquiry(
                conversationId, null, request.newClient(), bearerToken);
        if (!existing.getRentalInquiryId().equals(replay.rentalInquiryId())
            || !existing.getClientId().equals(replay.clientId())) {
          throw new AssistantConflictException("Conversation client is immutable");
        }
        return response(existing, replay.inquiryStatus());
      }
      assertExistingClientMatches(existing, request.clientId());
      return response(existing, null);
    }

    LogisticsClient.InquiryBootstrap inquiry =
        logistics.createRentalInquiry(
            conversationId, request.clientId(), request.newClient(), bearerToken);
    AssistantConversation conversation =
        AssistantConversation.create(
            conversationId,
            ownerSubjectId,
            inquiry.clientId(),
            inquiry.rentalInquiryId(),
            firstNonBlank(inquiry.clientType(), request.newClient() == null ? null : request.newClient().clientType()),
            inquiry.clientDisplayName());
    conversations.saveAndFlush(conversation);
    return response(conversation, inquiry.inquiryStatus());
  }

  @Transactional(readOnly = true)
  public List<AssistantApiModels.ConversationResponse> list(UUID ownerSubjectId) {
    return conversations.findByOwnerSubjectIdOrderByUpdatedAtDesc(ownerSubjectId).stream()
        .map(mapper::toConversationResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public AssistantApiModels.ConversationDetailResponse detail(UUID ownerSubjectId, UUID conversationId) {
    AssistantConversation conversation = owned(conversationId, ownerSubjectId);
    Map<UUID, List<JsonNode>> noticesByTurn = searchNoticesByTurn(conversationId);
    List<AssistantApiModels.MessageResponse> responseMessages =
        messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
            .map(
                message ->
                    withSearchNotices(
                        mapper.toMessageResponse(message),
                        message.getRole() == AssistantMessageRole.USER
                            ? noticesByTurn.get(message.getId())
                            : null))
            .toList();
    JsonNode lastSearchResult = mergedLastSearchResult(conversationId);
    return new AssistantApiModels.ConversationDetailResponse(
        mapper.toConversationResponse(conversation), responseMessages, lastSearchResult);
  }

  /**
   * This is intentionally based on the same recovered, expiry-checked carousel sent to the
   * browser, and requires that no client presentation exists for the inquiry. Historical tool
   * calls or a previously published client link must never make the model ask about a merge.
   */
  @Transactional(readOnly = true)
  public boolean hasActiveSearchResult(
      UUID ownerSubjectId, UUID conversationId, String bearerToken) {
    AssistantConversation conversation = owned(conversationId, ownerSubjectId);
    if (mergedLastSearchResult(conversationId) == null) return false;
    try {
      return logistics
          .findClientPresentation(conversation.getRentalInquiryId(), bearerToken)
          .isEmpty();
    } catch (AssistantUpstreamException ignored) {
      // Showing a merge question while the presentation state is unknown can produce a mixed
      // selection after the manager has already sent a client link. Fail closed for this prompt
      // affordance; an ordinary replacement search remains available.
      return false;
    }
  }

  @Transactional
  public void archive(UUID ownerSubjectId, UUID conversationId) {
    AssistantConversation conversation = owned(conversationId, ownerSubjectId);
    if (conversation.archive()) conversations.save(conversation);
  }

  @Transactional
  public TurnStart beginTurn(UUID ownerSubjectId, UUID conversationId, String message) {
    AssistantConversation conversation =
        conversations
            .findByIdAndOwnerSubjectIdAndArchivedFalse(conversationId, ownerSubjectId)
            .orElseThrow(() -> new AssistantNotFoundException("Conversation was not found"));
    AssistantMessage userMessage = messages.save(AssistantMessage.user(conversationId, message));
    conversation.recordActivity();
    conversations.save(conversation);
    return new TurnStart(
        conversation.getId(),
        conversation.getRentalInquiryId(),
        userMessage.getId(),
        userMessage.getContent());
  }

  @Transactional(readOnly = true)
  public List<PromptMessage> promptMessages(UUID ownerSubjectId, UUID conversationId) {
    AssistantConversation conversation =
        conversations
            .findByIdAndOwnerSubjectIdAndArchivedFalse(conversationId, ownerSubjectId)
            .orElseThrow(() -> new AssistantNotFoundException("Conversation was not found"));
    Map<UUID, List<AssistantToolCall>> replayableCallsByTurn = new LinkedHashMap<>();
    toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId()).stream()
        .filter(AssistantConversationService::isPromptReplayable)
        .forEach(
            call ->
                replayableCallsByTurn
                    .computeIfAbsent(call.getTurnMessageId(), ignored -> new ArrayList<>())
                    .add(call));
    return messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId()).stream()
        .map(
            message ->
                new PromptMessage(
                    message.getRole().name().toLowerCase(),
                    message.getContent(),
                    promptToolCalls(replayableCallsByTurn.get(message.getId()))))
        .toList();
  }

  @Transactional
  public AssistantMessage completeAssistantTurn(
      UUID ownerSubjectId, UUID conversationId, String assistantText) {
    AssistantConversation conversation =
        conversations
            .findByIdAndOwnerSubjectIdAndArchivedFalse(conversationId, ownerSubjectId)
            .orElseThrow(() -> new AssistantNotFoundException("Conversation was not found"));
    AssistantMessage assistantMessage =
        messages.save(AssistantMessage.assistant(conversationId, assistantText));
    conversation.recordActivity();
    conversations.save(conversation);
    return assistantMessage;
  }

  @Transactional
  public AssistantToolCall startToolCall(
      UUID ownerSubjectId,
      UUID conversationId,
      UUID turnMessageId,
      String providerCallId,
      String toolName,
      JsonNode arguments) {
    conversations
        .findByIdAndOwnerSubjectIdAndArchivedFalse(conversationId, ownerSubjectId)
        .orElseThrow(() -> new AssistantNotFoundException("Conversation was not found"));
    return toolCalls.save(
        AssistantToolCall.start(
            conversationId,
            turnMessageId,
            providerCallId,
            toolName,
            arguments));
  }

  @Transactional
  public void completeToolCall(UUID toolCallId, JsonNode result) {
    AssistantToolCall call =
        toolCalls
            .findById(toolCallId)
            .orElseThrow(() -> new AssistantNotFoundException("Tool call was not found"));
    call.complete(result);
    toolCalls.save(call);
  }

  @Transactional
  public void failToolCall(UUID toolCallId, String code, JsonNode result) {
    AssistantToolCall call =
        toolCalls
            .findById(toolCallId)
            .orElseThrow(() -> new AssistantNotFoundException("Tool call was not found"));
    call.fail(code, result);
    toolCalls.save(call);
  }

  @Transactional
  public boolean archiveFromRentalInquiry(UUID conversationId, UUID rentalInquiryId) {
    AssistantConversation conversation = conversations.findById(conversationId).orElse(null);
    if (conversation == null || !conversation.getRentalInquiryId().equals(rentalInquiryId)) {
      return false;
    }
    if (conversation.archive()) conversations.save(conversation);
    return true;
  }

  private AssistantConversation owned(UUID conversationId, UUID ownerSubjectId) {
    return conversations
        .findByIdAndOwnerSubjectId(conversationId, ownerSubjectId)
        .orElseThrow(() -> new AssistantNotFoundException("Conversation was not found"));
  }

  private static void requireOwner(AssistantConversation conversation, UUID ownerSubjectId) {
    if (!conversation.getOwnerSubjectId().equals(ownerSubjectId)) {
      // Return the same shape as a missing resource to avoid existence disclosure.
      throw new AssistantNotFoundException("Conversation was not found");
    }
  }

  private static void assertExistingClientMatches(
      AssistantConversation conversation, UUID clientId) {
    if (clientId == null || !conversation.getClientId().equals(clientId)) {
      throw new AssistantConflictException("Conversation client is immutable");
    }
  }

  private AssistantApiModels.CreateConversationResponse response(
      AssistantConversation conversation, String inquiryStatus) {
    AssistantApiModels.ConversationResponse response = mapper.toConversationResponse(conversation);
    return new AssistantApiModels.CreateConversationResponse(
        response,
        new AssistantApiModels.RentalInquirySummary(conversation.getRentalInquiryId(), inquiryStatus),
        new AssistantApiModels.ClientSummary(
            conversation.getClientId(),
            conversation.getClientType(),
            conversation.getClientDisplayName()));
  }

  private static String firstNonBlank(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) return preferred;
    return fallback;
  }

  private static boolean isPromptReplayable(AssistantToolCall call) {
    return (call.getStatus() == AssistantToolCallStatus.COMPLETED
            || call.getStatus() == AssistantToolCallStatus.FAILED)
        && call.getResultPayload() != null;
  }

  private static List<PromptToolCall> promptToolCalls(List<AssistantToolCall> calls) {
    if (calls == null || calls.isEmpty()) return List.of();
    return calls.stream()
        .sorted(
            java.util.Comparator.comparing(AssistantToolCall::getCreatedAt)
                .thenComparing(AssistantToolCall::getId))
        .map(
            call ->
                new PromptToolCall(
                    call.getProviderCallId(),
                    call.getToolName(),
                    call.getArgumentsPayload(),
                    call.getResultPayload()))
        .toList();
  }

  private Map<UUID, List<JsonNode>> searchNoticesByTurn(UUID conversationId) {
    Map<UUID, List<JsonNode>> noticesByTurn = new LinkedHashMap<>();
    toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
        .filter(
            call ->
                call.getStatus() == AssistantToolCallStatus.COMPLETED
                    && AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(call.getToolName())
                    && call.getResultPayload() != null)
        .forEach(
            call -> {
              JsonNode notices = call.getResultPayload().path("notices");
              if (!notices.isArray()) return;
              List<JsonNode> turnNotices =
                  noticesByTurn.computeIfAbsent(call.getTurnMessageId(), ignored -> new ArrayList<>());
              for (JsonNode notice : notices) {
                if (!notice.isObject() || containsJson(turnNotices, notice)) continue;
                turnNotices.add(notice.deepCopy());
              }
            });
    return noticesByTurn;
  }

  private static AssistantApiModels.MessageResponse withSearchNotices(
      AssistantApiModels.MessageResponse message, List<JsonNode> notices) {
    return new AssistantApiModels.MessageResponse(
        message.id(),
        message.role(),
        message.content(),
        message.createdAt(),
        notices == null ? List.of() : notices);
  }

  /**
   * Recover the active carousel exactly as the turn flow does: searches in one user turn form a
   * single exact-plus-alternatives result; later turns append only when the model explicitly used
   * APPEND. A stale or malformed result never revives historical cabin cards.
   */
  private JsonNode mergedLastSearchResult(UUID conversationId) {
    Map<UUID, List<AssistantToolCall>> searchesByTurn = new LinkedHashMap<>();
    toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
        .filter(
            call ->
                call.getStatus() == AssistantToolCallStatus.COMPLETED
                    && AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(call.getToolName())
                    && call.getResultPayload() != null)
        .forEach(
            call ->
                searchesByTurn
                    .computeIfAbsent(call.getTurnMessageId(), ignored -> new ArrayList<>())
                    .add(call));

    SearchAggregate active = null;
    for (List<AssistantToolCall> turnCalls : searchesByTurn.values()) {
      SearchAggregate turn =
          mergeSearchResults(turnCalls.stream().map(AssistantToolCall::getResultPayload).toList());
      if (turn == null) {
        // The latest replacement turn cannot be reconstructed safely. Do not fall back to a
        // previous selection which may no longer be held by the inquiry.
        active = null;
        continue;
      }
      if (turn.resultMode() == SearchResultMode.APPEND && active != null && active.isActive()) {
        active = mergeAggregates(active, turn, SearchResultMode.APPEND);
      } else {
        active = turn;
      }
    }
    return active == null || !active.isActive() ? null : active.toJson();
  }

  private static SearchAggregate mergeSearchResults(List<JsonNode> results) {
    if (results == null || results.isEmpty()) return null;
    SearchAggregate aggregate = null;
    for (JsonNode result : results) {
      SearchAggregate current = SearchAggregate.from(result);
      if (current == null) return null;
      aggregate =
          aggregate == null
              ? current
              : mergeAggregates(aggregate, current, aggregate.resultMode());
      if (aggregate == null) return null;
    }
    return aggregate;
  }

  private static SearchAggregate mergeAggregates(
      SearchAggregate first, SearchAggregate second, SearchResultMode resultMode) {
    if (!first.warehouseId().equals(second.warehouseId())) return null;
    Instant earliestExpiresAt =
        first.expiresAt().isBefore(second.expiresAt()) ? first.expiresAt() : second.expiresAt();
    return new SearchAggregate(
        first.warehouseId(),
        earliestExpiresAt,
        mergeGroups(first.groups(), second.groups()),
        mergeNotices(first.notices(), second.notices()),
        resultMode);
  }

  private static List<JsonNode> mergeGroups(List<JsonNode> first, List<JsonNode> second) {
    Map<String, JsonNode> groupsByFilter = new LinkedHashMap<>();
    for (JsonNode group : concat(first, second)) {
      if (!group.path("group").isObject() || !group.path("cabins").isArray()) continue;
      String key = searchGroupKey(group.path("group"));
      JsonNode existing = groupsByFilter.get(key);
      groupsByFilter.put(key, existing == null ? group.deepCopy() : mergeGroup(existing, group));
    }

    boolean anyCabins =
        groupsByFilter.values().stream().anyMatch(group -> !group.path("cabins").isEmpty());
    Set<String> includedCabinIds = new LinkedHashSet<>();
    List<JsonNode> merged = new ArrayList<>();
    for (JsonNode group : groupsByFilter.values()) {
      ObjectNode copy = (ObjectNode) group.deepCopy();
      ArrayNode uniqueCabins = JsonNodeFactory.instance.arrayNode();
      for (JsonNode cabin : group.path("cabins")) {
        String cabinId = cabin.path("id").asText(null);
        if (cabinId == null || includedCabinIds.add(cabinId)) uniqueCabins.add(cabin.deepCopy());
      }
      if (anyCabins && uniqueCabins.isEmpty()) continue;
      copy.set("cabins", uniqueCabins);
      merged.add(copy);
    }
    return List.copyOf(merged);
  }

  private static JsonNode mergeGroup(JsonNode first, JsonNode second) {
    ObjectNode merged = (ObjectNode) first.deepCopy();
    ObjectNode group = (ObjectNode) merged.path("group").deepCopy();
    int firstQuantity = group.path("quantity").isInt() ? group.path("quantity").intValue() : 0;
    int secondQuantity = second.path("group").path("quantity").isInt()
        ? second.path("group").path("quantity").intValue()
        : 0;
    if (firstQuantity > 0 && secondQuantity > 0) {
      group.put("quantity", firstQuantity + secondQuantity);
    }
    merged.set("group", group);
    ArrayNode cabins = merged.putArray("cabins");
    Set<String> includedCabinIds = new LinkedHashSet<>();
    for (JsonNode candidate : concat(first.path("cabins"), second.path("cabins"))) {
      String cabinId = candidate.path("id").asText(null);
      if (cabinId == null || includedCabinIds.add(cabinId)) cabins.add(candidate.deepCopy());
    }
    return merged;
  }

  private static List<JsonNode> mergeNotices(List<JsonNode> first, List<JsonNode> second) {
    List<JsonNode> merged = new ArrayList<>();
    for (JsonNode notice : concat(first, second)) {
      if (notice.isObject() && !containsJson(merged, notice)) merged.add(notice.deepCopy());
    }
    return List.copyOf(merged);
  }

  private static boolean containsJson(List<JsonNode> values, JsonNode candidate) {
    return values.stream().anyMatch(value -> value.equals(candidate));
  }

  private static List<JsonNode> concat(List<JsonNode> first, List<JsonNode> second) {
    List<JsonNode> result = new ArrayList<>(first.size() + second.size());
    result.addAll(first);
    result.addAll(second);
    return result;
  }

  private static List<JsonNode> concat(JsonNode first, JsonNode second) {
    List<JsonNode> result = new ArrayList<>();
    if (first.isArray()) first.forEach(result::add);
    if (second.isArray()) second.forEach(result::add);
    return result;
  }

  private static Instant parseSearchExpiry(JsonNode expiresAt) {
    if (!expiresAt.isTextual() || expiresAt.asText().isBlank()) return null;
    try {
      return OffsetDateTime.parse(expiresAt.asText()).toInstant();
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static String searchGroupKey(JsonNode group) {
    return String.join(
        "\u0000",
        group.path("cabinType").asText(""),
        group.path("finish").asText(""),
        group.path("dimensions").asText(""),
        group.path("category").asText(""),
        canonicalCategories(group.path("categories")),
        group.path("characteristics").asText(""),
        group.path("linoleum").asText(""));
  }

  private static String canonicalCategories(JsonNode categories) {
    if (!categories.isArray()) return "";
    List<String> values = new ArrayList<>();
    for (JsonNode category : categories) {
      if (category.isTextual()) values.add(category.asText());
    }
    values.sort(String::compareTo);
    return String.join("\u0001", values);
  }

  private enum SearchResultMode {
    APPEND,
    REPLACE;

    private static SearchResultMode from(JsonNode result) {
      JsonNode value = result.get("resultMode");
      if (value == null || value.isNull()) return REPLACE;
      if (!value.isTextual()) return null;
      try {
        return valueOf(value.asText());
      } catch (IllegalArgumentException invalid) {
        return null;
      }
    }
  }

  private record SearchAggregate(
      String warehouseId,
      Instant expiresAt,
      List<JsonNode> groups,
      List<JsonNode> notices,
      SearchResultMode resultMode) {
    private SearchAggregate {
      groups = groups.stream().map(JsonNode::deepCopy).toList();
      notices = notices.stream().map(JsonNode::deepCopy).toList();
    }

    private static SearchAggregate from(JsonNode result) {
      if (result == null
          || !AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(result.path("tool").asText())) {
        return null;
      }
      JsonNode data = result.path("data");
      String warehouseId = data.path("warehouseId").asText(null);
      Instant expiresAt = parseSearchExpiry(data.path("expiresAt"));
      SearchResultMode resultMode = SearchResultMode.from(result);
      if (warehouseId == null
          || warehouseId.isBlank()
          || expiresAt == null
          || !data.path("groups").isArray()
          || resultMode == null) {
        return null;
      }
      List<JsonNode> groups = new ArrayList<>();
      for (JsonNode group : data.path("groups")) {
        if (group.path("group").isObject() && group.path("cabins").isArray()) {
          groups.add(group.deepCopy());
        }
      }
      JsonNode noticesNode = result.path("notices");
      if (!noticesNode.isMissingNode() && !noticesNode.isArray()) return null;
      List<JsonNode> notices = new ArrayList<>();
      if (noticesNode.isArray()) {
        for (JsonNode notice : noticesNode) {
          if (notice.isObject() && !containsJson(notices, notice)) notices.add(notice.deepCopy());
        }
      }
      return new SearchAggregate(warehouseId, expiresAt, groups, notices, resultMode);
    }

    private boolean isActive() {
      return expiresAt.isAfter(Instant.now());
    }

    private JsonNode toJson() {
      ObjectNode data = JsonNodeFactory.instance.objectNode();
      data.put("warehouseId", warehouseId);
      data.put("expiresAt", expiresAt.toString());
      ArrayNode resultGroups = data.putArray("groups");
      groups.forEach(group -> resultGroups.add(group.deepCopy()));
      ObjectNode result = JsonNodeFactory.instance.objectNode();
      result.put("tool", AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
      result.put("resultMode", resultMode.name());
      ArrayNode resultNotices = result.putArray("notices");
      notices.forEach(notice -> resultNotices.add(notice.deepCopy()));
      result.set("data", data);
      return result;
    }
  }

  public record TurnStart(
      UUID conversationId, UUID rentalInquiryId, UUID userMessageId, String userMessage) {}

  public record PromptMessage(String role, String content, List<PromptToolCall> toolCalls) {
    public PromptMessage {
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
  }

  public record PromptToolCall(
      String providerCallId, String toolName, JsonNode arguments, JsonNode result) {
    public PromptToolCall {
      if (providerCallId == null
          || providerCallId.isBlank()
          || toolName == null
          || toolName.isBlank()
          || arguments == null
          || result == null) {
        throw new IllegalArgumentException("Persisted tool call data is incomplete");
      }
      arguments = arguments.deepCopy();
      result = result.deepCopy();
    }
  }
}
