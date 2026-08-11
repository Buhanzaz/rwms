package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationKind;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.nio.charset.StandardCharsets;
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

/**
 * Owns the availability-search tool workflow, including authoritative facet validation,
 * clarification creation, bounded allocation and display-safe result normalization.
 */
@Component
public class AssistantCabinSearchTool {
  private static final int MAX_LOGICAL_GROUPS = 5;
  private static final int MAX_EXACT_GROUPS = 20;
  private static final int MAX_QUANTITY = 30;
  private static final Set<String> SEARCH_ARGUMENT_FIELDS =
      Set.of("groups", "warehouseId", "totalQuantity", "resultMode");
  private static final Set<String> CLARIFICATION_ARGUMENT_FIELDS =
      Set.of("warehouseId", "questions");
  private static final Set<String> CLARIFICATION_QUESTION_FIELDS =
      Set.of("branchKey", "kind", "prompt", "cabinType", "options");
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
  private final LogisticsClient logistics;
  private final AssistantClarificationService clarifications;
  private final AssistantSelectionService selections;
  private final ObjectMapper mapper;

  public AssistantCabinSearchTool(
      LogisticsClient logistics,
      AssistantClarificationService clarifications,
      AssistantSelectionService selections,
      ObjectMapper mapper) {
    this.logistics = logistics;
    this.clarifications = clarifications;
    this.selections = selections;
    this.mapper = mapper;
  }

  /**
   * Validates a provider availability-search argument object without changing holds or persistent
   * clarification state.
   */
  public void validateSearch(JsonNode arguments) {
    requireObjectFields(arguments, SEARCH_ARGUMENT_FIELDS, Set.of("groups", "warehouseId"));
    parseSearch(arguments);
  }

  /** Validates an explicit ordered-choice request against its structural limits. */
  public void validateClarifications(JsonNode arguments) {
    requireObjectFields(
        arguments, CLARIFICATION_ARGUMENT_FIELDS, Set.of("warehouseId", "questions"));
    requiredUuid(arguments.get("warehouseId"), "warehouseId");
    parseQuestionArguments(arguments);
  }

  /**
   * Executes an availability request only after type, finish, size and optional filters have been
   * reconciled with the current warehouse facets. Missing or ambiguous exact criteria create
   * persistent questions and never acquire a hold.
   */
  public JsonNode search(
      UUID conversationId,
      UUID rentalInquiryId,
      UUID turnMessageId,
      UUID toolCallId,
      JsonNode arguments,
      String bearerToken,
      Consumer<AssistantApiModels.TurnEvent> events) {
    return search(
        conversationId,
        rentalInquiryId,
        turnMessageId,
        toolCallId,
        arguments,
        null,
        bearerToken,
        events);
  }

  /** Executes a search after enforcing the current logistics-owned warehouse, when fixed. */
  public JsonNode search(
      UUID conversationId,
      UUID rentalInquiryId,
      UUID turnMessageId,
      UUID toolCallId,
      JsonNode arguments,
      UUID fixedWarehouseId,
      String bearerToken,
      Consumer<AssistantApiModels.TurnEvent> events) {
    LogicalCabinSearch search = parseSearch(arguments);
    requireFixedWarehouse(fixedWarehouseId, search.warehouseId());
    JsonNode facetResult = logistics.listAvailableCabinFacets(rentalInquiryId, bearerToken);
    SearchPreparation preparation = prepareSearch(search, facetResult);
    if (!preparation.questions().isEmpty()) {
      List<AssistantApiModels.ClarificationQuestionResponse> created =
          clarifications.create(
              conversationId,
              turnMessageId,
              toolCallId,
              search.warehouseId(),
              preparation.questions());
      emitQuestions(conversationId, toolCallId, created, events);
      return clarificationResult(created);
    }
    if (preparation.search().resultMode() == SearchResultMode.APPEND
        && preparation.search().requiresAllocation()) {
      throw new IllegalArgumentException(
          "APPEND requires exact categories and an explicit quantity for every group");
    }
    return normalizeCabinSearchResult(
        preparation.search(),
        executeCabinSearch(toolCallId, rentalInquiryId, preparation.search(), bearerToken),
        preparation.metadata().filterSuggestions());
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

  private SearchPreparation prepareSearch(LogicalCabinSearch search, JsonNode facetResult) {
    AssistantCabinFacetMetadata metadata =
        AssistantCabinFacetMetadata.from(facetResult, search.warehouseId());
    List<LogicalCabinSearchGroup> resolved = new ArrayList<>();
    List<AssistantClarificationService.QuestionDraft> questions = new ArrayList<>();
    for (int index = 0; index < search.groups().size(); index++) {
      LogicalCabinSearchGroup group = search.groups().get(index);
      validateExactOptional(group.cabinType(), metadata.cabinTypes(), "cabinType");
      validateExactOptional(group.finish(), metadata.finishes(), "finish");
      validateExactOptional(group.category(), metadata.categories(), "category");
      if (group.categories() != null) {
        for (String category : group.categories()) {
          validateExactOptional(category, metadata.categories(), "categories");
        }
      }
      validateExactOptional(group.characteristics(), metadata.characteristics(), "characteristics");
      String cabinType = group.cabinType();
      if (cabinType == null) {
        List<String> compatibleTypes = metadata.cabinTypesCompatibleWith(group.dimensions());
        if (compatibleTypes.isEmpty()) {
          throw new IllegalArgumentException("dimensions is not related to any current cabin type");
        }
        if (compatibleTypes.size() == 1) {
          cabinType = compatibleTypes.getFirst();
        } else {
          questions.add(
              question(
                  branchKey(index, group, AssistantClarificationKind.CABIN_TYPE),
                  AssistantClarificationKind.CABIN_TYPE,
                  group.finish() == null
                      ? "Выберите тип бытовки."
                      : "Для отделки «" + group.finish() + "» выберите тип бытовки.",
                  null,
                  compatibleTypes));
          continue;
        }
      }
      String finish = group.finish();
      if (finish == null) {
        if (metadata.finishes().size() == 1) {
          finish = metadata.finishes().getFirst();
        } else {
          questions.add(
              question(
                  branchKey(index, group, AssistantClarificationKind.FINISH),
                  AssistantClarificationKind.FINISH,
                  "Для типа «" + cabinType + "» выберите отделку.",
                  cabinType,
                  metadata.finishes()));
          continue;
        }
      }
      List<String> relatedDimensions = metadata.dimensionsFor(cabinType);
      if (relatedDimensions.isEmpty()) {
        throw new IllegalArgumentException("The selected cabin type has no current dimensions");
      }
      String dimensions = metadata.resolveDimension(cabinType, group.dimensions());
      if (dimensions == null) {
        questions.add(
            question(
                branchKey(index, group, AssistantClarificationKind.DIMENSIONS),
                AssistantClarificationKind.DIMENSIONS,
                "Для типа «" + cabinType + "» выберите размер.",
                cabinType,
                relatedDimensions));
        continue;
      }
      resolved.add(
          new LogicalCabinSearchGroup(
              cabinType,
              finish,
              dimensions,
              group.category(),
              group.categories(),
              group.characteristics(),
              group.linoleum(),
              group.quantity()));
    }
    if (!questions.isEmpty()) {
      return new SearchPreparation(search, metadata, questions);
    }
    LogicalCabinSearch exact =
        new LogicalCabinSearch(
            resolved, search.warehouseId(), search.totalQuantity(), search.resultMode());
    exact.validateAllocationMode();
    return new SearchPreparation(exact, metadata, List.of());
  }

  /**
   * Creates one ordered batch of button questions after verifying every option against current
   * warehouse metadata (or the fixed append/replace pair).
   */
  public JsonNode requestClarifications(
      UUID conversationId,
      UUID rentalInquiryId,
      UUID turnMessageId,
      UUID toolCallId,
      JsonNode arguments,
      String bearerToken,
      Consumer<AssistantApiModels.TurnEvent> events) {
    return requestClarifications(
        conversationId,
        rentalInquiryId,
        turnMessageId,
        toolCallId,
        arguments,
        null,
        bearerToken,
        events);
  }

  /** Creates an ordered batch after enforcing the current logistics-owned warehouse. */
  public JsonNode requestClarifications(
      UUID conversationId,
      UUID rentalInquiryId,
      UUID turnMessageId,
      UUID toolCallId,
      JsonNode arguments,
      UUID fixedWarehouseId,
      String bearerToken,
      Consumer<AssistantApiModels.TurnEvent> events) {
    UUID warehouseId = requiredUuid(arguments.get("warehouseId"), "warehouseId");
    requireFixedWarehouse(fixedWarehouseId, warehouseId);
    AssistantCabinFacetMetadata metadata =
        AssistantCabinFacetMetadata.from(
            logistics.listAvailableCabinFacets(rentalInquiryId, bearerToken), warehouseId);
    List<RequestedQuestion> requested = parseQuestionArguments(arguments);
    List<AssistantClarificationService.QuestionDraft> drafts = new ArrayList<>();
    for (RequestedQuestion question : requested) {
      List<String> allowed =
          switch (question.kind()) {
            case CABIN_TYPE -> metadata.cabinTypes();
            case FINISH -> metadata.finishes();
            case CATEGORY -> metadata.categories();
            case DIMENSIONS -> {
              if (question.cabinType() == null
                  || !metadata.cabinTypes().contains(question.cabinType())) {
                throw new IllegalArgumentException(
                    "DIMENSIONS clarification requires an exact cabinType");
              }
              yield metadata.dimensionsFor(question.cabinType());
            }
            case SEARCH_MERGE -> {
              if (selections.current(rentalInquiryId, bearerToken) == null) {
                throw new IllegalArgumentException("There is no current selection to merge");
              }
              yield List.of("APPEND", "REPLACE");
            }
          };
      Set<String> requestedOptions = new LinkedHashSet<>(question.options());
      if (allowed.isEmpty()
          || requestedOptions.size() != question.options().size()
          || allowed.size() != question.options().size()
          || !new LinkedHashSet<>(allowed).equals(requestedOptions)) {
        throw new IllegalArgumentException(
            "Clarification options must contain every exact current choice");
      }
      drafts.add(
          question(
              question.branchKey(),
              question.kind(),
              question.prompt(),
              question.cabinType(),
              allowed));
    }
    List<AssistantApiModels.ClarificationQuestionResponse> created =
        clarifications.create(conversationId, turnMessageId, toolCallId, warehouseId, drafts);
    emitQuestions(conversationId, toolCallId, created, events);
    return clarificationResult(created);
  }

  private static void emitQuestions(
      UUID conversationId,
      UUID toolCallId,
      List<AssistantApiModels.ClarificationQuestionResponse> questions,
      Consumer<AssistantApiModels.TurnEvent> events) {
    questions.forEach(
        question ->
            events.accept(
                new AssistantApiModels.TurnEvent(
                    "clarification.requested",
                    conversationId,
                    null,
                    toolCallId,
                    null,
                    null,
                    null,
                    question)));
  }

  private static List<RequestedQuestion> parseQuestionArguments(JsonNode arguments) {
    JsonNode questions = arguments.get("questions");
    if (questions == null || !questions.isArray() || questions.isEmpty() || questions.size() > 5) {
      throw new IllegalArgumentException("questions must contain one to five entries");
    }
    List<RequestedQuestion> result = new ArrayList<>();
    for (JsonNode question : questions) {
      requireObjectFields(
          question,
          CLARIFICATION_QUESTION_FIELDS,
          Set.of("branchKey", "kind", "prompt", "options"));
      String kindValue = optionalText(question.get("kind"), "kind", 32);
      AssistantClarificationKind kind;
      try {
        kind = AssistantClarificationKind.valueOf(kindValue);
      } catch (RuntimeException invalid) {
        throw new IllegalArgumentException("Clarification kind is invalid", invalid);
      }
      List<String> options = optionalTextList(question.get("options"), "options", 30, 255);
      if (options.size() < 2) {
        throw new IllegalArgumentException("A clarification requires at least two options");
      }
      result.add(
          new RequestedQuestion(
              optionalText(question.get("branchKey"), "branchKey", 255),
              kind,
              optionalText(question.get("prompt"), "prompt", 2000),
              optionalText(question.get("cabinType"), "cabinType", 255),
              options));
    }
    return List.copyOf(result);
  }

  private static AssistantClarificationService.QuestionDraft question(
      String branchKey,
      AssistantClarificationKind kind,
      String prompt,
      String cabinType,
      List<String> values) {
    if (values == null || values.size() < 2) {
      throw new IllegalArgumentException("At least two exact choices are required");
    }
    return new AssistantClarificationService.QuestionDraft(
        branchKey,
        kind,
        prompt,
        cabinType,
        values.stream()
            .map(value -> new AssistantClarificationService.OptionDraft(value, value))
            .toList());
  }

  private static String branchKey(
      int index, LogicalCabinSearchGroup group, AssistantClarificationKind kind) {
    String canonical =
        index
            + "\u001f"
            + kind
            + "\u001f"
            + String.valueOf(group.cabinType())
            + "\u001f"
            + String.valueOf(group.finish())
            + "\u001f"
            + String.valueOf(group.dimensions())
            + "\u001f"
            + String.valueOf(group.quantity());
    UUID identity = UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    return "search:" + identity + ':' + kind.name().toLowerCase(Locale.ROOT);
  }

  private static void validateExactOptional(String value, List<String> allowed, String field) {
    if (value != null && !allowed.contains(value)) {
      throw new IllegalArgumentException(field + " must be an exact current facet");
    }
  }

  private static void requireFixedWarehouse(UUID fixedWarehouseId, UUID requestedWarehouseId) {
    if (fixedWarehouseId != null && !fixedWarehouseId.equals(requestedWarehouseId)) {
      throw new AssistantConflictException(
          "The rental inquiry is already fixed to another warehouse");
    }
  }

  private static List<String> optionalTextList(
      JsonNode value, String field, int maximumItems, int maximumLength) {
    if (value == null) return List.of();
    if (!value.isArray() || value.isEmpty() || value.size() > maximumItems) {
      throw new IllegalArgumentException(field + " has an invalid size");
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (JsonNode candidate : value) {
      String text = optionalText(candidate, field, maximumLength);
      if (text == null || text.isBlank() || !result.add(text)) {
        throw new IllegalArgumentException(field + " must contain unique nonblank values");
      }
    }
    return List.copyOf(result);
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
   * Keeps the LLM-facing logical grouping separate from the logistics contract, which deliberately
   * accepts one exact nullable category per physical group.
   */
  private CabinSearchExecution executeCabinSearch(
      UUID toolCallId, UUID rentalInquiryId, LogicalCabinSearch search, String bearerToken) {
    if (!search.requiresAllocation()) {
      JsonNode raw =
          logistics.searchAvailableCabins(
              rentalInquiryId,
              AssistantSearchIdempotencyKeys.direct(toolCallId),
              search.exactSearch(),
              bearerToken);
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
              rentalInquiryId,
              AssistantSearchIdempotencyKeys.probe(toolCallId, logicalIndex),
              exactSearch(search.warehouseId(), logicalProbe, SearchResultMode.APPEND),
              bearerToken);
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
            ? allocateSharedTotal(
                search.groups().size(), probes, capacities, search.totalQuantity())
            : allocateExplicitQuantities(search.groups(), probes, capacities);

    if (sum(allocations) == 0) {
      // A replacement with no matches still releases the previous authoritative selection.
      // The explicit empty PUT also cleans up any probe hold if upstream behavior changes.
      selections.replace(
          rentalInquiryId,
          AssistantSearchIdempotencyKeys.finalSelection(toolCallId),
          new AssistantApiModels.CabinSelectionRequest(search.warehouseId(), List.of()),
          bearerToken);
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
            rentalInquiryId,
            AssistantSearchIdempotencyKeys.finalSelection(toolCallId),
            exactSearch(search.warehouseId(), finalGroups, search.resultMode()),
            bearerToken);
    // The logistics inquiry owns the resulting short holds. Return only the
    // cabins from the final bounded selection, never transient probe cabins.
    List<JsonNode> exactResults = exactGroupResults(finalResult, finalGroups);
    return new CabinSearchExecution(
        mergePhysicalGroups(finalResult, finalGroups, search.groups()),
        logicalFoundCounts(exactResults, finalGroups, search.groups().size()));
  }

  /**
   * The non-allocation request is one logical group per logistics group. Keep its existing tolerant
   * projection behavior, while deriving structured availability notices from the cabins that
   * logistics did return.
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
      UUID warehouseId, List<PhysicalCabinSearchGroup> groups, SearchResultMode resultMode) {
    int requestedCabins = groups.stream().mapToInt(PhysicalCabinSearchGroup::quantity).sum();
    if (requestedCabins > 100) {
      throw new IllegalArgumentException("A cabin search cannot hold more than 100 cabins");
    }
    return new LogisticsClient.CabinSearch(
        groups.stream().map(PhysicalCabinSearchGroup::exactGroup).toList(),
        warehouseId,
        LogisticsClient.SearchResultMode.valueOf(resultMode.name()));
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

  private static JsonNode normalizeCabinSearchResult(
      LogicalCabinSearch search, CabinSearchExecution execution, JsonNode filterSuggestions) {
    if (execution.data() == null) {
      throw new AssistantUpstreamException("Logistics returned no tool result");
    }
    JsonNode data = execution.data().deepCopy();
    AssistantToolResultSanitizer.rejectContactFields(data);
    ObjectNode normalized = JsonNodeFactory.instance.objectNode();
    normalized.put("tool", AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS);
    normalized.put("resultMode", search.resultMode().name());
    normalized.set("filterSuggestions", filterSuggestions.deepCopy());
    normalized.set("notices", cabinSearchNotices(search, execution.foundByLogicalGroup()));
    normalized.set("data", data);
    return normalized;
  }

  /** Structured, display-safe availability gaps. The panel never has to infer them from prose. */
  private static ArrayNode cabinSearchNotices(
      LogicalCabinSearch search, int[] foundByLogicalGroup) {
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
      String code, List<LogicalCabinSearchGroup> groups, int requestedQuantity, int foundQuantity) {
    ObjectNode notice = JsonNodeFactory.instance.objectNode();
    notice.put("code", code);
    ArrayNode criteria = notice.putArray("groups");
    for (int index = 0; index < groups.size(); index++) {
      criteria.add(
          logicalNoticeGroup(
              groups.get(index),
              materializedNoticeGroupQuantity(groups, requestedQuantity, index)));
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

  private JsonNode clarificationResult(
      List<AssistantApiModels.ClarificationQuestionResponse> questions) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.set("questions", mapper.valueToTree(questions));
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("tool", AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS);
    result.set("data", data);
    return result;
  }

  /** Parsed search plus authoritative facets and any questions that prevent a mutating call. */
  private record SearchPreparation(
      LogicalCabinSearch search,
      AssistantCabinFacetMetadata metadata,
      List<AssistantClarificationService.QuestionDraft> questions) {
    private SearchPreparation {
      questions = List.copyOf(questions);
    }
  }

  /** Structurally valid provider request for one question in an ordered batch. */
  private record RequestedQuestion(
      String branchKey,
      AssistantClarificationKind kind,
      String prompt,
      String cabinType,
      List<String> options) {
    private RequestedQuestion {
      options = List.copyOf(options);
    }
  }

  /** Logical user request before exact category expansion. */
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
        throw new IllegalArgumentException(
            "Logical groups expand to more than twenty exact groups");
      }
      if (!sharedTotal && groups.stream().mapToInt(group -> group.quantity()).sum() > 100) {
        throw new IllegalArgumentException("The requested cabin total cannot exceed 100");
      }
    }

    boolean hasSharedTotal() {
      return totalQuantity != null;
    }

    boolean requiresAllocation() {
      return hasSharedTotal()
          || groups.stream().anyMatch(LogicalCabinSearchGroup::hasMultipleCategories);
    }

    LogisticsClient.CabinSearch exactSearch() {
      if (requiresAllocation()) {
        throw new IllegalStateException("An allocation search requires a capacity probe");
      }
      return AssistantCabinSearchTool.exactSearch(warehouseId, exactGroups(), resultMode);
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

  /** One requested cabin group before optional category expansion. */
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

  /** One exact group accepted by the logistics search contract. */
  private record PhysicalCabinSearchGroup(
      int logicalGroupIndex, LogicalCabinSearchGroup logicalGroup, String category, int quantity) {
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

  /** Raw logistics result paired with per-logical-group availability counts. */
  private record CabinSearchExecution(JsonNode data, int[] foundByLogicalGroup) {
    private CabinSearchExecution {
      data = data == null ? null : data.deepCopy();
      foundByLogicalGroup = foundByLogicalGroup == null ? new int[0] : foundByLogicalGroup.clone();
    }
  }

  /** Selection mutation requested by the provider; omission resolves to replacement. */
  private enum SearchResultMode {
    APPEND,
    REPLACE
  }
}
