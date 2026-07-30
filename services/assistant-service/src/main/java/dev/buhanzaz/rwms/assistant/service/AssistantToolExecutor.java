package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AssistantToolExecutor {
  private static final Set<String> LIST_ARGUMENT_FIELDS = Set.of();
  private static final int MAX_LOGICAL_GROUPS = 5;
  private static final int MAX_EXACT_GROUPS = 20;
  private static final int MAX_QUANTITY = 30;
  private static final Set<String> SEARCH_ARGUMENT_FIELDS =
      Set.of("groups", "warehouseId", "totalQuantity", "resultMode");
  private static final Set<String> GROUP_FIELDS =
      Set.of(
          "cabinType",
          "finish",
          "dimensions",
          "category",
          "categories",
          "characteristics",
          "linoleum",
          "quantity");
  private static final Set<String> PROHIBITED_RESULT_FIELDS =
      Set.of(
          "phone",
          "email",
          "contact",
          "contacts",
          "client",
          "clientid",
          "clientname",
          "newclient");

  private final AssistantConversationService conversations;
  private final LogisticsClient logistics;
  private final ObjectMapper mapper;

  public AssistantToolExecutor(
      AssistantConversationService conversations, LogisticsClient logistics, ObjectMapper mapper) {
    this.conversations = conversations;
    this.logistics = logistics;
    this.mapper = mapper;
  }

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
      JsonNode raw =
          switch (requested.name()) {
            case AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS ->
                logistics.listAvailableCabinFacets(rentalInquiryId, bearerToken);
            case AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS ->
                normalizeCabinSearchResult(
                    parseSearch(arguments),
                    executeCabinSearch(rentalInquiryId, parseSearch(arguments), bearerToken));
            case AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION ->
                searchMergeConfirmationResult();
            default -> throw new IllegalArgumentException("Tool is not allowed");
          };
      JsonNode result =
          AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(requested.name())
                  || AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION.equals(requested.name())
              ? raw
              : normalizeResult(requested.name(), raw);
      conversations.completeToolCall(record.getId(), result);
      if (AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(requested.name())) {
        events.accept(
            new AssistantApiModels.TurnEvent(
                "search.result", conversationId, null, record.getId(), null, result, null));
      }
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

  private static void validateTool(String name, JsonNode arguments) {
    if (AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS.equals(name)) {
      requireObjectFields(arguments, LIST_ARGUMENT_FIELDS, Set.of());
      return;
    }
    if (AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS.equals(name)) {
      requireObjectFields(arguments, SEARCH_ARGUMENT_FIELDS, Set.of("groups", "warehouseId"));
      parseSearch(arguments);
      return;
    }
    if (AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION.equals(name)) {
      requireObjectFields(arguments, LIST_ARGUMENT_FIELDS, Set.of());
      return;
    }
    throw new IllegalArgumentException("Tool is not allowed");
  }

  private static LogicalCabinSearch parseSearch(JsonNode arguments) {
    JsonNode groups = arguments.get("groups");
    if (groups == null
        || !groups.isArray()
        || groups.isEmpty()
        || groups.size() > MAX_LOGICAL_GROUPS) {
      throw new IllegalArgumentException("groups must contain one to five entries");
    }
    List<LogicalCabinSearchGroup> parsed = new ArrayList<>();
    for (JsonNode group : groups) {
      requireObjectFields(group, GROUP_FIELDS, Set.of());
      String category = optionalText(group.get("category"), "category", 255);
      List<String> categories = optionalCategories(group.get("categories"));
      if (category != null && categories != null) {
        throw new IllegalArgumentException("category and categories cannot be used together");
      }
      parsed.add(
          new LogicalCabinSearchGroup(
              optionalText(group.get("cabinType"), "cabinType", 255),
              optionalText(group.get("finish"), "finish", 255),
              optionalText(group.get("dimensions"), "dimensions", 255),
              category,
              categories,
              optionalText(group.get("characteristics"), "characteristics", 2000),
              optionalBoolean(group.get("linoleum"), "linoleum"),
              optionalQuantity(group.get("quantity"), "quantity")));
    }
    UUID warehouseId = requiredUuid(arguments.get("warehouseId"), "warehouseId");
    Integer totalQuantity = optionalQuantity(arguments.get("totalQuantity"), "totalQuantity");
    SearchResultMode resultMode = optionalResultMode(arguments.get("resultMode"));
    LogicalCabinSearch search =
        new LogicalCabinSearch(parsed, warehouseId, totalQuantity, resultMode);
    search.validateAllocationMode();
    return search;
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

  private static String optionalText(JsonNode value, String field, int maximumLength) {
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
    String text = value.asText();
    if (text.length() > maximumLength) {
      throw new IllegalArgumentException(field + " is too long");
    }
    return text;
  }

  private static List<String> optionalCategories(JsonNode value) {
    if (value == null) return null;
    if (value.isNull() || !value.isArray() || value.isEmpty() || value.size() > 3) {
      throw new IllegalArgumentException("categories must contain one to three exact values");
    }
    LinkedHashSet<String> categories = new LinkedHashSet<>();
    for (JsonNode category : value) {
      String exact = optionalText(category, "categories", 255);
      if (exact == null || !categories.add(exact)) {
        throw new IllegalArgumentException("categories must contain unique exact values");
      }
    }
    return List.copyOf(categories);
  }

  private static Integer optionalQuantity(JsonNode value, String field) {
    if (value == null) return null;
    if (value.isNull()
        || !value.isInt()
        || value.intValue() < 1
        || value.intValue() > MAX_QUANTITY) {
      throw new IllegalArgumentException(field + " must be an integer from 1 to 30");
    }
    return value.intValue();
  }

  private static SearchResultMode optionalResultMode(JsonNode value) {
    if (value == null || value.isNull()) return SearchResultMode.REPLACE;
    if (!value.isTextual()) {
      throw new IllegalArgumentException("resultMode must be APPEND or REPLACE");
    }
    try {
      return SearchResultMode.valueOf(value.asText());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("resultMode must be APPEND or REPLACE", invalid);
    }
  }

  private static Boolean optionalBoolean(JsonNode value, String field) {
    if (value == null || value.isNull()) return null;
    if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
    return value.booleanValue();
  }

  private static UUID requiredUuid(JsonNode value, String field) {
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a UUID");
    }
    try {
      return UUID.fromString(value.asText());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be a UUID", invalid);
    }
  }

  /**
   * Keeps the LLM-facing logical grouping separate from the logistics contract,
   * which deliberately accepts one exact nullable category per physical group.
   */
  private CabinSearchExecution executeCabinSearch(
      UUID rentalInquiryId, LogicalCabinSearch search, String bearerToken) {
    if (!search.requiresAllocation()) {
      JsonNode raw =
          logistics.searchAvailableCabins(rentalInquiryId, search.exactSearch(), bearerToken);
      return new CabinSearchExecution(raw, directLogicalFoundCounts(raw, search.groups().size()));
    }

    List<PhysicalCabinSearchGroup> probes = search.probeGroups();
    int[] capacities = new int[probes.size()];
    JsonNode lastProbe = null;
    for (int logicalIndex = 0; logicalIndex < search.groups().size(); logicalIndex++) {
      List<PhysicalCabinSearchGroup> logicalProbe = new ArrayList<>();
      List<Integer> physicalIndexes = new ArrayList<>();
      for (int physicalIndex = 0; physicalIndex < probes.size(); physicalIndex++) {
        if (probes.get(physicalIndex).logicalGroupIndex() == logicalIndex) {
          logicalProbe.add(probes.get(physicalIndex));
          physicalIndexes.add(physicalIndex);
        }
      }
      // A probe contains at most three exact categories and each group is no
      // larger than the requested logical/shared total (at most 30). This caps
      // temporary inquiry-scoped probe holds at 90 cabins; asset-service expires
      // them at the configured chat-selection deadline.
      lastProbe =
          logistics.searchAvailableCabins(
              rentalInquiryId, exactSearch(search.warehouseId(), logicalProbe), bearerToken);
      List<JsonNode> logicalResults = exactGroupResults(lastProbe, logicalProbe);
      for (int localIndex = 0; localIndex < logicalResults.size(); localIndex++) {
        capacities[physicalIndexes.get(localIndex)] =
            Math.min(
                logicalResults.get(localIndex).path("cabins").size(),
                logicalProbe.get(localIndex).quantity());
      }
    }
    int[] allocations =
        search.hasSharedTotal()
            ? allocateSharedTotal(search.groups().size(), probes, capacities, search.totalQuantity())
            : allocateExplicitQuantities(search.groups(), probes, capacities);

    if (sum(allocations) == 0) {
      // A probe with no cabins creates no cabin hold. Returning an empty success
      // shape avoids a meaningless second request with a made-up positive quantity.
      return new CabinSearchExecution(
          emptyLogicalGroups(lastProbe), new int[search.groups().size()]);
    }

    List<PhysicalCabinSearchGroup> finalGroups = new ArrayList<>();
    for (int index = 0; index < probes.size(); index++) {
      if (allocations[index] > 0) {
        finalGroups.add(probes.get(index).withQuantity(allocations[index]));
      }
    }
    JsonNode finalResult =
        logistics.searchAvailableCabins(
            rentalInquiryId, exactSearch(search.warehouseId(), finalGroups), bearerToken);
    // The logistics inquiry owns the resulting short holds. Return only the
    // cabins from the final bounded selection, never transient probe cabins.
    List<JsonNode> exactResults = exactGroupResults(finalResult, finalGroups);
    return new CabinSearchExecution(
        mergePhysicalGroups(finalResult, finalGroups, search.groups()),
        logicalFoundCounts(exactResults, finalGroups, search.groups().size()));
  }

  /**
   * The non-allocation request is one logical group per logistics group. Keep its existing
   * tolerant projection behavior, while deriving structured availability notices from the cabins
   * that logistics did return.
   */
  private static int[] directLogicalFoundCounts(JsonNode raw, int logicalGroupCount) {
    int[] counts = new int[logicalGroupCount];
    JsonNode groups = raw == null ? null : raw.path("groups");
    if (!groups.isArray()) return counts;
    Set<String> includedCabinIds = new LinkedHashSet<>();
    for (int index = 0; index < groups.size() && index < logicalGroupCount; index++) {
      for (JsonNode cabin : groups.get(index).path("cabins")) {
        String cabinId = cabin.path("id").asText(null);
        if (cabinId == null || includedCabinIds.add(cabinId)) counts[index]++;
      }
    }
    return counts;
  }

  private static int[] logicalFoundCounts(
      List<JsonNode> exactResults,
      List<PhysicalCabinSearchGroup> physicalGroups,
      int logicalGroupCount) {
    int[] counts = new int[logicalGroupCount];
    Set<String> includedCabinIds = new LinkedHashSet<>();
    for (int physicalIndex = 0; physicalIndex < physicalGroups.size(); physicalIndex++) {
      int logicalIndex = physicalGroups.get(physicalIndex).logicalGroupIndex();
      for (JsonNode cabin : exactResults.get(physicalIndex).path("cabins")) {
        String cabinId = cabin.path("id").asText(null);
        if (cabinId == null || includedCabinIds.add(cabinId)) counts[logicalIndex]++;
      }
    }
    return counts;
  }

  private static LogisticsClient.CabinSearch exactSearch(
      UUID warehouseId, List<PhysicalCabinSearchGroup> groups) {
    int requestedCabins = groups.stream().mapToInt(PhysicalCabinSearchGroup::quantity).sum();
    if (requestedCabins > 100) {
      throw new IllegalArgumentException("A cabin search cannot hold more than 100 cabins");
    }
    return new LogisticsClient.CabinSearch(
        groups.stream().map(PhysicalCabinSearchGroup::exactGroup).toList(), warehouseId);
  }

  private static List<JsonNode> exactGroupResults(
      JsonNode raw, List<PhysicalCabinSearchGroup> expectedGroups) {
    if (raw == null || !raw.isObject()) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin search response");
    }
    JsonNode groups = raw.path("groups");
    if (!groups.isArray() || groups.size() != expectedGroups.size()) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin search response");
    }
    List<JsonNode> results = new ArrayList<>();
    for (int index = 0; index < expectedGroups.size(); index++) {
      JsonNode result = groups.get(index);
      PhysicalCabinSearchGroup expected = expectedGroups.get(index);
      if (result == null
          || !result.isObject()
          || !result.path("cabins").isArray()
          || !matchesExactGroup(result.path("group"), expected.exactGroup())
          || result.path("cabins").size() > expected.quantity()) {
        throw new AssistantUpstreamException("Logistics returned an invalid cabin search response");
      }
      results.add(result);
    }
    return results;
  }

  private static boolean matchesExactGroup(
      JsonNode result, LogisticsClient.CabinSearchGroup expected) {
    return result.isObject()
        && matchesNullableText(result.get("cabinType"), expected.cabinType())
        && matchesNullableText(result.get("finish"), expected.finish())
        && matchesNullableText(result.get("dimensions"), expected.dimensions())
        && matchesNullableText(result.get("category"), expected.category())
        && matchesNullableText(result.get("characteristics"), expected.characteristics())
        && matchesNullableBoolean(result.get("linoleum"), expected.linoleum())
        && result.path("quantity").isInt()
        && result.path("quantity").intValue() == expected.quantity();
  }

  private static boolean matchesNullableText(JsonNode actual, String expected) {
    if (expected == null) return actual == null || actual.isNull();
    return actual != null && actual.isTextual() && expected.equals(actual.asText());
  }

  private static boolean matchesNullableBoolean(JsonNode actual, Boolean expected) {
    if (expected == null) return actual == null || actual.isNull();
    return actual != null && actual.isBoolean() && expected.equals(actual.booleanValue());
  }

  /** Gives each requested cabin type one turn before any type receives a second cabin. */
  private static int[] allocateSharedTotal(
      int logicalGroupCount,
      List<PhysicalCabinSearchGroup> physicalGroups,
      int[] capacities,
      int totalQuantity) {
    int[] allocations = new int[physicalGroups.size()];
    List<List<Integer>> indexesByLogicalGroup =
        indexesByLogicalGroup(logicalGroupCount, physicalGroups);
    int[] categoryCursors = new int[logicalGroupCount];
    int remaining = totalQuantity;
    while (remaining > 0) {
      boolean allocatedInRound = false;
      for (int logicalIndex = 0;
          logicalIndex < logicalGroupCount && remaining > 0;
          logicalIndex++) {
        int physicalIndex =
            nextAvailable(
                indexesByLogicalGroup.get(logicalIndex),
                categoryCursors,
                logicalIndex,
                capacities,
                allocations);
        if (physicalIndex < 0) continue;
        allocations[physicalIndex]++;
        remaining--;
        allocatedInRound = true;
      }
      if (!allocatedInRound) break;
    }
    return allocations;
  }

  private static int[] allocateExplicitQuantities(
      List<LogicalCabinSearchGroup> logicalGroups,
      List<PhysicalCabinSearchGroup> physicalGroups,
      int[] capacities) {
    int[] allocations = new int[physicalGroups.size()];
    List<List<Integer>> indexesByLogicalGroup =
        indexesByLogicalGroup(logicalGroups.size(), physicalGroups);
    int[] categoryCursors = new int[logicalGroups.size()];
    for (int logicalIndex = 0; logicalIndex < logicalGroups.size(); logicalIndex++) {
      int remaining = logicalGroups.get(logicalIndex).quantity();
      while (remaining > 0) {
        int physicalIndex =
            nextAvailable(
                indexesByLogicalGroup.get(logicalIndex),
                categoryCursors,
                logicalIndex,
                capacities,
                allocations);
        if (physicalIndex < 0) break;
        allocations[physicalIndex]++;
        remaining--;
      }
    }
    return allocations;
  }

  private static List<List<Integer>> indexesByLogicalGroup(
      int logicalGroupCount, List<PhysicalCabinSearchGroup> physicalGroups) {
    List<List<Integer>> indexes = new ArrayList<>();
    for (int index = 0; index < logicalGroupCount; index++) indexes.add(new ArrayList<>());
    for (int index = 0; index < physicalGroups.size(); index++) {
      indexes.get(physicalGroups.get(index).logicalGroupIndex()).add(index);
    }
    return indexes;
  }

  private static int nextAvailable(
      List<Integer> candidates,
      int[] categoryCursors,
      int logicalGroupIndex,
      int[] capacities,
      int[] allocations) {
    if (candidates.isEmpty()) return -1;
    int start = categoryCursors[logicalGroupIndex] % candidates.size();
    for (int offset = 0; offset < candidates.size(); offset++) {
      int candidatePosition = (start + offset) % candidates.size();
      int physicalIndex = candidates.get(candidatePosition);
      if (allocations[physicalIndex] >= capacities[physicalIndex]) continue;
      categoryCursors[logicalGroupIndex] = (candidatePosition + 1) % candidates.size();
      return physicalIndex;
    }
    return -1;
  }

  private static int sum(int[] values) {
    int sum = 0;
    for (int value : values) sum += value;
    return sum;
  }

  /** Reassembles exact-category results into the LLM's original logical groups. */
  private static JsonNode mergePhysicalGroups(
      JsonNode raw,
      List<PhysicalCabinSearchGroup> physicalGroups,
      List<LogicalCabinSearchGroup> logicalGroups) {
    List<JsonNode> exactResults = exactGroupResults(raw, physicalGroups);
    ObjectNode merged = (ObjectNode) raw.deepCopy();
    ArrayNode mergedGroups = JsonNodeFactory.instance.arrayNode();
    Set<String> includedCabinIds = new LinkedHashSet<>();
    for (int logicalIndex = 0; logicalIndex < logicalGroups.size(); logicalIndex++) {
      ArrayNode cabins = JsonNodeFactory.instance.arrayNode();
      for (int physicalIndex = 0; physicalIndex < physicalGroups.size(); physicalIndex++) {
        if (physicalGroups.get(physicalIndex).logicalGroupIndex() != logicalIndex) continue;
        for (JsonNode cabin : exactResults.get(physicalIndex).path("cabins")) {
          String cabinId = cabin.path("id").asText(null);
          if (cabinId == null || includedCabinIds.add(cabinId)) cabins.add(cabin.deepCopy());
        }
      }
      if (cabins.isEmpty()) continue;
      ObjectNode result = mergedGroups.addObject();
      int requestedQuantity =
          logicalGroups.get(logicalIndex).quantity() == null
              ? cabins.size()
              : logicalGroups.get(logicalIndex).quantity();
      result.set("group", logicalResultGroup(logicalGroups.get(logicalIndex), requestedQuantity));
      result.set("cabins", cabins);
    }
    merged.set("groups", mergedGroups);
    return merged;
  }

  private static JsonNode emptyLogicalGroups(JsonNode raw) {
    if (raw == null || !raw.isObject()) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin search response");
    }
    ObjectNode empty = (ObjectNode) raw.deepCopy();
    // An empty array is canonical and avoids emitting a group with quantity 0.
    empty.set("groups", JsonNodeFactory.instance.arrayNode());
    return empty;
  }

  private static ObjectNode logicalResultGroup(LogicalCabinSearchGroup group, int quantity) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    putIfPresent(result, "cabinType", group.cabinType());
    putIfPresent(result, "finish", group.finish());
    putIfPresent(result, "dimensions", group.dimensions());
    if (group.category() != null) {
      result.put("category", group.category());
    } else if (group.categories() != null) {
      if (group.categories().size() == 1) {
        result.put("category", group.categories().getFirst());
      } else {
        ArrayNode categories = result.putArray("categories");
        group.categories().forEach(categories::add);
      }
    }
    putIfPresent(result, "characteristics", group.characteristics());
    if (group.linoleum() != null) result.put("linoleum", group.linoleum());
    result.put("quantity", quantity);
    return result;
  }

  private static void putIfPresent(ObjectNode target, String field, String value) {
    if (value != null) target.put(field, value);
  }

  private static JsonNode normalizeResult(String tool, JsonNode raw) {
    if (raw == null) throw new AssistantUpstreamException("Logistics returned no tool result");
    JsonNode validated = raw.deepCopy();
    rejectContactFields(validated);
    rejectWarehouseBusinessCode(tool, validated);
    ObjectNode normalized = JsonNodeFactory.instance.objectNode();
    normalized.put("tool", tool);
    normalized.set("data", validated);
    return normalized;
  }

  private static JsonNode normalizeCabinSearchResult(
      LogicalCabinSearch search, CabinSearchExecution execution) {
    if (execution.data() == null) {
      throw new AssistantUpstreamException("Logistics returned no tool result");
    }
    JsonNode data = execution.data().deepCopy();
    rejectContactFields(data);
    ObjectNode normalized = JsonNodeFactory.instance.objectNode();
    normalized.put("tool", AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    normalized.put("resultMode", search.resultMode().name());
    normalized.set("notices", cabinSearchNotices(search, execution.foundByLogicalGroup()));
    normalized.set("data", data);
    return normalized;
  }

  /** Structured, display-safe availability gaps. The panel never has to infer them from prose. */
  private static ArrayNode cabinSearchNotices(LogicalCabinSearch search, int[] foundByLogicalGroup) {
    ArrayNode notices = JsonNodeFactory.instance.arrayNode();
    if (search.hasSharedTotal()) {
      int found = Math.min(search.totalQuantity(), sum(foundByLogicalGroup));
      if (found < search.totalQuantity()) {
        notices.add(
            cabinSearchNotice(
                found == 0 ? "CABINS_NOT_FOUND" : "CABINS_PARTIALLY_FOUND",
                search.groups(),
                search.totalQuantity(),
                found));
      }
      return notices;
    }
    for (int index = 0; index < search.groups().size(); index++) {
      LogicalCabinSearchGroup group = search.groups().get(index);
      int found = Math.min(group.quantity(), foundByLogicalGroup[index]);
      if (found >= group.quantity()) continue;
      notices.add(
          cabinSearchNotice(
              found == 0 ? "CABINS_NOT_FOUND" : "CABINS_PARTIALLY_FOUND",
              List.of(group),
              group.quantity(),
              found));
    }
    return notices;
  }

  private static ObjectNode cabinSearchNotice(
      String code,
      List<LogicalCabinSearchGroup> groups,
      int requestedQuantity,
      int foundQuantity) {
    ObjectNode notice = JsonNodeFactory.instance.objectNode();
    notice.put("code", code);
    ArrayNode criteria = notice.putArray("groups");
    for (int index = 0; index < groups.size(); index++) {
      criteria.add(
          logicalNoticeGroup(
              groups.get(index), materializedNoticeGroupQuantity(groups, requestedQuantity, index)));
    }
    notice.put("requestedQuantity", requestedQuantity);
    notice.put("foundQuantity", foundQuantity);
    return notice;
  }

  /**
   * Shared-total requests have no per-group quantity in the tool arguments. The public result
   * nevertheless carries a positive quantity for every logical criterion so clients can render a
   * typed notice without interpreting omitted values. When there are more types than requested
   * cabins, each type still receives one display unit; requestedQuantity remains the authoritative
   * shared total at the notice level.
   */
  private static int materializedNoticeGroupQuantity(
      List<LogicalCabinSearchGroup> groups, int requestedQuantity, int index) {
    Integer explicitQuantity = groups.get(index).quantity();
    if (explicitQuantity != null) return explicitQuantity;
    int base = requestedQuantity / groups.size();
    int remainder = requestedQuantity % groups.size();
    return Math.max(1, base + (index < remainder ? 1 : 0));
  }

  private static ObjectNode logicalNoticeGroup(LogicalCabinSearchGroup group, int quantity) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    putIfPresent(result, "cabinType", group.cabinType());
    putIfPresent(result, "finish", group.finish());
    putIfPresent(result, "dimensions", group.dimensions());
    if (group.category() != null) {
      result.put("category", group.category());
    }
    if (group.categories() != null) {
      ArrayNode categories = result.putArray("categories");
      group.categories().forEach(categories::add);
    }
    putIfPresent(result, "characteristics", group.characteristics());
    if (group.linoleum() != null) result.put("linoleum", group.linoleum());
    result.put("quantity", quantity);
    return result;
  }

  private static JsonNode searchMergeConfirmationResult() {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("action", "ASK_ADD_OR_REPLACE");
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("tool", AssistantToolDefinitions.REQUEST_SEARCH_MERGE_CONFIRMATION);
    result.set("data", data);
    return result;
  }

  /** Warehouse identity is UUID-only; an upstream legacy field is a contract violation. */
  private static void rejectWarehouseBusinessCode(String tool, JsonNode result) {
    if (!AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS.equals(tool)) return;
    JsonNode warehouses = result.path("warehouses");
    if (!warehouses.isArray()) return;
    for (JsonNode warehouse : warehouses) {
      if (warehouse.isObject() && warehouse.has("code")) {
        throw new AssistantUpstreamException("Logistics returned an obsolete warehouse field");
      }
    }
  }

  private static void rejectContactFields(JsonNode value) {
    if (value.isObject()) {
      value
          .properties()
          .forEach(
              entry -> {
                if (isProhibitedResultField(entry.getKey())) {
                  throw new AssistantUpstreamException("Logistics returned prohibited tool data");
                }
                rejectContactFields(entry.getValue());
              });
    } else if (value.isArray()) {
      value.forEach(AssistantToolExecutor::rejectContactFields);
    }
  }

  private static boolean isProhibitedResultField(String field) {
    String normalized = field.toLowerCase(Locale.ROOT);
    return PROHIBITED_RESULT_FIELDS.contains(normalized)
        || normalized.contains("phone")
        || normalized.contains("email")
        || normalized.contains("contact");
  }

  private static JsonNode failureCode(RuntimeException failure) {
    if (failure instanceof IllegalArgumentException) return failure("TOOL_ARGUMENTS_INVALID");
    if (failure instanceof AssistantInquiryArchivedException) return failure("INQUIRY_ARCHIVED");
    if (failure instanceof AssistantUpstreamException) return failure("LOGISTICS_UNAVAILABLE");
    return failure("TOOL_FAILED");
  }

  private static JsonNode failure(String code) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("code", code);
    return value;
  }

  private record LogicalCabinSearch(
      List<LogicalCabinSearchGroup> groups,
      UUID warehouseId,
      Integer totalQuantity,
      SearchResultMode resultMode) {
    private LogicalCabinSearch {
      groups = List.copyOf(groups);
    }

    void validateAllocationMode() {
      boolean sharedTotal = totalQuantity != null;
      for (LogicalCabinSearchGroup group : groups) {
        if (sharedTotal == (group.quantity() != null)) {
          throw new IllegalArgumentException(
              "Use either totalQuantity or a quantity for every logical group");
        }
      }
      if (physicalGroupCount() > MAX_EXACT_GROUPS) {
        throw new IllegalArgumentException("Logical groups expand to more than twenty exact groups");
      }
      if (!sharedTotal && groups.stream().mapToInt(group -> group.quantity()).sum() > 100) {
        throw new IllegalArgumentException("The requested cabin total cannot exceed 100");
      }
    }

    boolean hasSharedTotal() {
      return totalQuantity != null;
    }

    boolean requiresAllocation() {
      return hasSharedTotal() || groups.stream().anyMatch(LogicalCabinSearchGroup::hasMultipleCategories);
    }

    LogisticsClient.CabinSearch exactSearch() {
      if (requiresAllocation()) {
        throw new IllegalStateException("An allocation search requires a capacity probe");
      }
      return AssistantToolExecutor.exactSearch(warehouseId, exactGroups());
    }

    List<PhysicalCabinSearchGroup> probeGroups() {
      List<PhysicalCabinSearchGroup> result = new ArrayList<>();
      for (int logicalIndex = 0; logicalIndex < groups.size(); logicalIndex++) {
        LogicalCabinSearchGroup group = groups.get(logicalIndex);
        int probeQuantity = hasSharedTotal() ? totalQuantity : group.quantity();
        for (String category : group.exactCategoryOptions()) {
          result.add(new PhysicalCabinSearchGroup(logicalIndex, group, category, probeQuantity));
        }
      }
      return List.copyOf(result);
    }

    private List<PhysicalCabinSearchGroup> exactGroups() {
      List<PhysicalCabinSearchGroup> result = new ArrayList<>();
      for (int logicalIndex = 0; logicalIndex < groups.size(); logicalIndex++) {
        LogicalCabinSearchGroup group = groups.get(logicalIndex);
        for (String category : group.exactCategoryOptions()) {
          result.add(new PhysicalCabinSearchGroup(logicalIndex, group, category, group.quantity()));
        }
      }
      return List.copyOf(result);
    }

    private int physicalGroupCount() {
      return groups.stream().mapToInt(LogicalCabinSearchGroup::exactCategoryOptionCount).sum();
    }
  }

  private record LogicalCabinSearchGroup(
      String cabinType,
      String finish,
      String dimensions,
      String category,
      List<String> categories,
      String characteristics,
      Boolean linoleum,
      Integer quantity) {
    private LogicalCabinSearchGroup {
      categories = categories == null ? null : List.copyOf(categories);
    }

    boolean hasMultipleCategories() {
      return categories != null && categories.size() > 1;
    }

    int exactCategoryOptionCount() {
      return categories == null ? 1 : categories.size();
    }

    List<String> exactCategoryOptions() {
      if (categories != null) return categories;
      return java.util.Collections.singletonList(category);
    }
  }

  private record PhysicalCabinSearchGroup(
      int logicalGroupIndex,
      LogicalCabinSearchGroup logicalGroup,
      String category,
      int quantity) {
    LogisticsClient.CabinSearchGroup exactGroup() {
      return new LogisticsClient.CabinSearchGroup(
          logicalGroup.cabinType(),
          logicalGroup.finish(),
          logicalGroup.dimensions(),
          category,
          logicalGroup.characteristics(),
          logicalGroup.linoleum(),
          quantity);
    }

    PhysicalCabinSearchGroup withQuantity(int requestedQuantity) {
      return new PhysicalCabinSearchGroup(
          logicalGroupIndex, logicalGroup, category, requestedQuantity);
    }
  }

  private record CabinSearchExecution(JsonNode data, int[] foundByLogicalGroup) {
    private CabinSearchExecution {
      data = data == null ? null : data.deepCopy();
      foundByLogicalGroup = foundByLogicalGroup == null ? new int[0] : foundByLogicalGroup.clone();
    }
  }

  private enum SearchResultMode {
    APPEND,
    REPLACE
  }

  public record ToolExecution(
      ChatCompletionClient.ProviderToolCall providerCall, JsonNode result, UUID toolCallId) {}
}
