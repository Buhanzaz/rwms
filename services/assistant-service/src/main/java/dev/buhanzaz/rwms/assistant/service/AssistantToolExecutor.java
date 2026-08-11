package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audits and dispatches the documented assistant tool allow-list while cohesive collaborators own
 * search, reference and selection behavior.
 */
@Component
public class AssistantToolExecutor {
  private static final Set<String> EMPTY_ARGUMENT_FIELDS = Set.of();
  private static final Set<String> REMOVE_ARGUMENT_FIELDS = Set.of("rentalItemIds", "numbers");

  private final AssistantConversationService conversations;
  private final LogisticsClient logistics;
  private final AssistantCabinSearchTool cabinSearch;
  private final AssistantCabinReferenceTool cabinReference;
  private final AssistantSelectionService selections;
  private final ObjectMapper mapper;

  public AssistantToolExecutor(
      AssistantConversationService conversations,
      LogisticsClient logistics,
      AssistantCabinSearchTool cabinSearch,
      AssistantCabinReferenceTool cabinReference,
      AssistantSelectionService selections,
      ObjectMapper mapper) {
    this.conversations = conversations;
    this.logistics = logistics;
    this.cabinSearch = cabinSearch;
    this.cabinReference = cabinReference;
    this.selections = selections;
    this.mapper = mapper;
  }

  /**
   * Converts an untrusted provider tool request into an audited allow-listed invocation. A tool row
   * is persisted before the upstream call, and all validation or upstream failures are stored and
   * emitted as safe provider context rather than escaping as raw data.
   */
  public ToolExecution execute(
      UUID ownerSubjectId,
      UUID conversationId,
      UUID rentalInquiryId,
      UUID turnMessageId,
      ChatCompletionClient.ProviderToolCall requested,
      String bearerToken,
      Consumer<AssistantApiModels.TurnEvent> events) {
    JsonNode arguments;
    try {
      arguments = mapper.readTree(requested.arguments());
      validateTool(requested.name(), arguments);
    } catch (RuntimeException invalid) {
      return persistRejectedCall(ownerSubjectId, conversationId, turnMessageId, requested, events);
    }

    AssistantToolCall record =
        conversations.startToolCall(
            ownerSubjectId,
            conversationId,
            turnMessageId,
            requested.id(),
            requested.name(),
            arguments);
    events.accept(
        new AssistantApiModels.TurnEvent(
            "tool.started", conversationId, null, record.getId(), null, null, null));
    try {
      LogisticsClient.RentalInquiryContext inquiry =
          logistics.readRentalInquiryContext(rentalInquiryId, bearerToken);
      if (!"ACTIVE".equals(inquiry.state())) {
        throw new AssistantInquiryArchivedException();
      }
      JsonNode result =
          dispatch(
              requested.name(),
              conversationId,
              rentalInquiryId,
              turnMessageId,
              record.getId(),
              arguments,
              inquiry.warehouseId(),
              bearerToken,
              events);
      conversations.completeToolCall(record.getId(), result);
      emitDomainEvent(requested.name(), conversationId, record.getId(), result, events);
      events.accept(
          new AssistantApiModels.TurnEvent(
              "tool.completed", conversationId, null, record.getId(), null, result, null));
      return new ToolExecution(requested, result, record.getId());
    } catch (RuntimeException failure) {
      JsonNode safeFailure = failureCode(failure);
      conversations.failToolCall(record.getId(), safeFailure.path("code").asText(), safeFailure);
      events.accept(
          new AssistantApiModels.TurnEvent(
              "tool.completed",
              conversationId,
              null,
              record.getId(),
              null,
              safeFailure,
              safeFailure.path("code").asText()));
      return new ToolExecution(requested, safeFailure, record.getId());
    }
  }

  private ToolExecution persistRejectedCall(
      UUID ownerSubjectId,
      UUID conversationId,
      UUID turnMessageId,
      ChatCompletionClient.ProviderToolCall requested,
      Consumer<AssistantApiModels.TurnEvent> events) {
    JsonNode safeFailure = failure("TOOL_ARGUMENTS_INVALID");
    AssistantToolCall record =
        conversations.startToolCall(
            ownerSubjectId,
            conversationId,
            turnMessageId,
            requested.id(),
            requested.name(),
            JsonNodeFactory.instance.objectNode());
    conversations.failToolCall(record.getId(), "TOOL_ARGUMENTS_INVALID", safeFailure);
    events.accept(
        new AssistantApiModels.TurnEvent(
            "tool.completed",
            conversationId,
            null,
            record.getId(),
            null,
            safeFailure,
            "TOOL_ARGUMENTS_INVALID"));
    return new ToolExecution(requested, safeFailure, record.getId());
  }

  private JsonNode dispatch(
      String toolName,
      UUID conversationId,
      UUID rentalInquiryId,
      UUID turnMessageId,
      UUID toolCallId,
      JsonNode arguments,
      UUID fixedWarehouseId,
      String bearerToken,
      Consumer<AssistantApiModels.TurnEvent> events) {
    return switch (toolName) {
      case AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS ->
          normalizeFacets(
              logistics.listAvailableCabinFacets(rentalInquiryId, bearerToken), fixedWarehouseId);
      case AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS ->
          cabinSearch.search(
              conversationId,
              rentalInquiryId,
              turnMessageId,
              toolCallId,
              arguments,
              fixedWarehouseId,
              bearerToken,
              events);
      case AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS ->
          cabinSearch.requestClarifications(
              conversationId,
              rentalInquiryId,
              turnMessageId,
              toolCallId,
              arguments,
              fixedWarehouseId,
              bearerToken,
              events);
      case AssistantToolDefinitions.LOOKUP_CABIN_CATALOG ->
          cabinReference.execute(rentalInquiryId, arguments, fixedWarehouseId, bearerToken);
      case AssistantToolDefinitions.REMOVE_SELECTED_CABINS ->
          selections.remove(
              rentalInquiryId,
              toolCallId,
              optionalUuidList(arguments.get("rentalItemIds")),
              optionalTextList(arguments.get("numbers"), "numbers", 100, 128),
              bearerToken);
      default -> throw new IllegalArgumentException("Tool is not allowed");
    };
  }

  private void validateTool(String name, JsonNode arguments) {
    switch (name) {
      case AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS ->
          requireObjectFields(arguments, EMPTY_ARGUMENT_FIELDS, Set.of());
      case AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS ->
          cabinSearch.validateSearch(arguments);
      case AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS ->
          cabinSearch.validateClarifications(arguments);
      case AssistantToolDefinitions.LOOKUP_CABIN_CATALOG -> cabinReference.validate(arguments);
      case AssistantToolDefinitions.REMOVE_SELECTED_CABINS -> validateRemoval(arguments);
      default -> throw new IllegalArgumentException("Tool is not allowed");
    }
  }

  private static void validateRemoval(JsonNode arguments) {
    requireObjectFields(arguments, REMOVE_ARGUMENT_FIELDS, Set.of());
    List<UUID> ids = optionalUuidList(arguments.get("rentalItemIds"));
    List<String> numbers = optionalTextList(arguments.get("numbers"), "numbers", 100, 128);
    if (ids.isEmpty() && numbers.isEmpty()) {
      throw new IllegalArgumentException("At least one exact selected cabin is required");
    }
  }

  private static JsonNode normalizeFacets(JsonNode raw, UUID fixedWarehouseId) {
    if (raw == null) throw new AssistantUpstreamException("Logistics returned no tool result");
    JsonNode validated =
        fixedWarehouseId == null
            ? raw.deepCopy()
            : AssistantCabinFacetMetadata.from(raw, fixedWarehouseId).scopedFacetProjection();
    AssistantToolResultSanitizer.rejectContactFields(validated);
    AssistantToolResultSanitizer.rejectWarehouseBusinessCode(validated);
    ObjectNode normalized = JsonNodeFactory.instance.objectNode();
    normalized.put("tool", AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS);
    normalized.set("data", validated);
    return normalized;
  }

  private static void emitDomainEvent(
      String toolName,
      UUID conversationId,
      UUID toolCallId,
      JsonNode result,
      Consumer<AssistantApiModels.TurnEvent> events) {
    if (AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(toolName)
        && AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(result.path("tool").asText())) {
      events.accept(
          new AssistantApiModels.TurnEvent(
              "search.result", conversationId, null, toolCallId, null, result, null));
    }
    if (AssistantToolDefinitions.REMOVE_SELECTED_CABINS.equals(toolName)) {
      events.accept(
          new AssistantApiModels.TurnEvent(
              "selection.updated", conversationId, null, toolCallId, null, result, null));
    }
  }

  private static void requireObjectFields(
      JsonNode value, Set<String> allowedFields, Set<String> requiredFields) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Tool arguments must be an object");
    }
    Set<String> actual = new HashSet<>();
    actual.addAll(value.propertyNames());
    if (!allowedFields.containsAll(actual) || !actual.containsAll(requiredFields)) {
      throw new IllegalArgumentException("Tool arguments contain unsupported fields");
    }
  }

  private static List<UUID> optionalUuidList(JsonNode value) {
    if (value == null) return List.of();
    if (!value.isArray() || value.isEmpty() || value.size() > 100) {
      throw new IllegalArgumentException("rentalItemIds must contain one to one hundred UUIDs");
    }
    LinkedHashSet<UUID> result = new LinkedHashSet<>();
    for (JsonNode candidate : value) {
      if (!candidate.isTextual()) {
        throw new IllegalArgumentException("rentalItemIds must contain UUIDs");
      }
      UUID id;
      try {
        id = UUID.fromString(candidate.asText());
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("rentalItemIds must contain UUIDs", invalid);
      }
      if (!result.add(id)) {
        throw new IllegalArgumentException("rentalItemIds must be unique");
      }
    }
    return List.copyOf(result);
  }

  private static List<String> optionalTextList(
      JsonNode value, String field, int maximumItems, int maximumLength) {
    if (value == null) return List.of();
    if (!value.isArray() || value.isEmpty() || value.size() > maximumItems) {
      throw new IllegalArgumentException(field + " has an invalid size");
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (JsonNode candidate : value) {
      if (!candidate.isTextual()
          || candidate.asText().isBlank()
          || candidate.asText().length() > maximumLength
          || !result.add(candidate.asText())) {
        throw new IllegalArgumentException(field + " must contain unique nonblank values");
      }
    }
    return List.copyOf(result);
  }

  private static JsonNode failureCode(RuntimeException failure) {
    if (failure instanceof IllegalArgumentException
        || failure instanceof AssistantConflictException) {
      return failure("TOOL_ARGUMENTS_INVALID");
    }
    if (failure instanceof AssistantInquiryArchivedException) return failure("INQUIRY_ARCHIVED");
    if (failure instanceof AssistantUpstreamException) return failure("LOGISTICS_UNAVAILABLE");
    return failure("TOOL_FAILED");
  }

  private static JsonNode failure(String code) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("code", code);
    return value;
  }

  /** Provider request, safe result and persistent audit row identity for one dispatched call. */
  public record ToolExecution(
      ChatCompletionClient.ProviderToolCall providerCall, JsonNode result, UUID toolCallId) {}
}
