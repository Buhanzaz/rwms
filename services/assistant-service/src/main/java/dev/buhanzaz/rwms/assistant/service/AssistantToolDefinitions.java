package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.integration.ChatCompletionClient;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The only model tools exposed by this service. */
@Component
public class AssistantToolDefinitions {
  public static final String LIST_AVAILABLE_CABIN_FACETS = "list_available_cabin_facets";
  public static final String SEARCH_AVAILABLE_CABINS = "search_available_cabins";
  public static final String REQUEST_CABIN_CLARIFICATIONS = "request_cabin_clarifications";
  public static final String LOOKUP_CABIN_CATALOG = "lookup_cabin_catalog";
  public static final String REMOVE_SELECTED_CABINS = "remove_selected_cabins";

  public List<ChatCompletionClient.ToolDefinition> definitions() {
    return definitions(true);
  }

  /** Removes the selection-mutation tool when logistics reports no live held selection. */
  public List<ChatCompletionClient.ToolDefinition> definitions(boolean hasActiveSearchResult) {
    List<ChatCompletionClient.ToolDefinition> base =
        List.of(
            new ChatCompletionClient.ToolDefinition(
                LIST_AVAILABLE_CABIN_FACETS,
                "List bounded exact availability facets for the current rental inquiry. It takes no"
                    + " arguments. Use it before a search whenever a warehouse or requested cabin"
                    + " filter is blank, ambiguous, fuzzy, typo-like, or not already an exact facet"
                    + " value. Prefer a unique one-character correction (for example, ТВП to ДВП)"
                    + " and proceed with the exact returned value. Facets do not prove that a"
                    + " combination is available.",
                emptyObjectSchema()),
            new ChatCompletionClient.ToolDefinition(
                SEARCH_AVAILABLE_CABINS,
                "Search cabins for the current rental inquiry using one to five logical structured"
                    + " groups. warehouseId is required and must be an exact warehouse ID returned"
                    + " by list_available_cabin_facets. Every group must resolve both exact"
                    + " cabinType and exact finish before any availability result or hold is"
                    + " created. When either is missing the service creates exact interactive"
                    + " choices instead of searching. dimensions must belong to the selected type."
                    + " A missing sole related dimension is resolved by the service; several"
                    + " related dimensions become buttons. The only supported approximate dimension"
                    + " is an unambiguous six-metre request, resolved through current"
                    + " type-dimension relations to an exact 6x2.4-equivalent facet; when type is"
                    + " missing, only compatible types may be offered. characteristics must use an"
                    + " exact returned characteristic. Set linoleum=true when the user requests"
                    + " linoleum and linoleum=false only when the user explicitly requests no"
                    + " linoleum; otherwise omit linoleum. Every result contains only FREE cabins."
                    + " Omit category for default, all, show, free, available, \"все\","
                    + " \"свободные\", or \"доступные\" requests. Set category to exact returned"
                    + " \"Новая\" whenever the user explicitly requests new cabins, including"
                    + " \"новые\", \"покажи новые\", and \"только новые\"; \"новая\" is a category,"
                    + " not a status. Preserve the applicable category, as well as filters and"
                    + " quantity, in follow-up searches. A group may use category for one exact"
                    + " category, or categories for exact OR category options. Do not put both"
                    + " fields in one group. When the user asks for multiple categories for one"
                    + " cabin type, use one logical group with categories so that one cabin-type"
                    + " result is returned. Use the exact user quantity, or quantity 30 only when"
                    + " the user explicitly asks to show all matching cabins. Every distinct"
                    + " requested combination or cabin type needs a separate group with its own"
                    + " requested quantity: six БК-1 plus six БК-2 means two groups with quantity"
                    + " 6. For one shared quantity distributed across several cabin types, first"
                    + " list facets, then create one logical group per exact cabin type, omit every"
                    + " group quantity, and set the one top-level totalQuantity. For example, ten"
                    + " cabins split by types and limited to Обычная or ИТР uses totalQuantity 10,"
                    + " a group for each exact type, and categories [\"Обычная\", \"ИТР\"] in each"
                    + " group. Do not use totalQuantity with per-group quantities. The sum of all"
                    + " explicit per-group quantities must not exceed 100. For a follow-up asking"
                    + " to show all, repeat the last applicable structured filters in a fresh"
                    + " search; listing facets alone is not an answer, and neither is quoting a"
                    + " historical search. resultMode defaults to REPLACE. Use APPEND only after"
                    + " the manager explicitly confirms that this new request should be added to"
                    + " the active unpublished selection; use REPLACE for a replacement. APPEND"
                    + " requires at most one exact category and an explicit quantity in every"
                    + " group; never use categories or totalQuantity with APPEND. Requests to"
                    + " repeat, retry, refresh, recheck, show, or search for cabins always require"
                    + " a new call to this tool in the same turn. Never remove type or finish to"
                    + " broaden an empty result. Explain the exact result and offer only filter"
                    + " suggestions returned by the service.",
                searchSchema()),
            new ChatCompletionClient.ToolDefinition(
                REQUEST_CABIN_CLARIFICATIONS,
                "Create one to five button questions in deterministic array order. Use exact "
                    + "current facet values only. The service exposes only the first question, "
                    + "queues the rest, and requires each answer in order before provider work "
                    + "continues. branchKey is stable history metadata, not an independent branch. "
                    + "DIMENSIONS options must be related to "
                    + "the supplied exact cabinType. SEARCH_MERGE options are exactly APPEND and "
                    + "REPLACE. The service persists and validates every option.",
                clarificationSchema()),
            new ChatCompletionClient.ToolDefinition(
                LOOKUP_CABIN_CATALOG,
                "Read cabin facts without creating or renewing holds. It returns current exact"
                    + " facets, type-dimension relations and, when query is supplied, a bounded"
                    + " first catalog page searchable by exact cabin number or text. Use this for"
                    + " reference questions about types, finishes, dimensions, characteristics,"
                    + " linoleum and relations; do not call availability search for an"
                    + " informational question.",
                catalogLookupSchema()));
    if (!hasActiveSearchResult) return base;
    List<ChatCompletionClient.ToolDefinition> withRemoval = new java.util.ArrayList<>(base);
    withRemoval.add(
        new ChatCompletionClient.ToolDefinition(
            REMOVE_SELECTED_CABINS,
            "Remove cabins only from the current logistics-owned selection by exact persisted UUID"
                + " or unique exact cabin number. The service derives the retained IDs itself and"
                + " releases removed holds immediately; this tool cannot supply arbitrary"
                + " replacement state.",
            removeSelectionSchema()));
    return List.copyOf(withRemoval);
  }

  private static ObjectNode emptyObjectSchema() {
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    return schema;
  }

  private static ObjectNode searchSchema() {
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    schema.putArray("required").add("warehouseId").add("groups");
    schema.put("additionalProperties", false);
    ObjectNode properties = schema.putObject("properties");
    ObjectNode resultMode = properties.putObject("resultMode");
    resultMode.put("type", "string");
    resultMode.putArray("enum").add("APPEND").add("REPLACE");
    properties
        .putObject("totalQuantity")
        .put("type", "integer")
        .put("minimum", 1)
        .put("maximum", 30);
    ObjectNode groups = properties.putObject("groups");
    groups.put("type", "array");
    groups.put("minItems", 1);
    groups.put("maxItems", 5);
    ObjectNode group = groups.putObject("items");
    group.put("type", "object");
    group.put("additionalProperties", false);
    ObjectNode groupProperties = group.putObject("properties");
    groupProperties.putObject("cabinType").put("type", "string").put("maxLength", 255);
    groupProperties.putObject("finish").put("type", "string").put("maxLength", 255);
    groupProperties.putObject("dimensions").put("type", "string").put("maxLength", 255);
    groupProperties.putObject("category").put("type", "string").put("maxLength", 255);
    ObjectNode categories = groupProperties.putObject("categories");
    categories.put("type", "array");
    categories.put("minItems", 1);
    categories.put("maxItems", 3);
    categories.put("uniqueItems", true);
    categories.putObject("items").put("type", "string").put("maxLength", 255);
    groupProperties.putObject("characteristics").put("type", "string").put("maxLength", 2000);
    groupProperties.putObject("linoleum").put("type", "boolean");
    groupProperties
        .putObject("quantity")
        .put("type", "integer")
        .put("minimum", 1)
        .put("maximum", 30);
    properties.putObject("warehouseId").put("type", "string").put("format", "uuid");
    return schema;
  }

  private static ObjectNode clarificationSchema() {
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.putArray("required").add("warehouseId").add("questions");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("warehouseId").put("type", "string").put("format", "uuid");
    ObjectNode questions = properties.putObject("questions");
    questions.put("type", "array");
    questions.put("minItems", 1);
    questions.put("maxItems", 5);
    ObjectNode question = questions.putObject("items");
    question.put("type", "object");
    question.put("additionalProperties", false);
    question.putArray("required").add("branchKey").add("kind").add("prompt").add("options");
    ObjectNode questionProperties = question.putObject("properties");
    questionProperties.putObject("branchKey").put("type", "string").put("maxLength", 255);
    ObjectNode kind = questionProperties.putObject("kind");
    kind.put("type", "string");
    kind.putArray("enum")
        .add("CABIN_TYPE")
        .add("FINISH")
        .add("DIMENSIONS")
        .add("CATEGORY")
        .add("SEARCH_MERGE");
    questionProperties.putObject("prompt").put("type", "string").put("maxLength", 2000);
    questionProperties.putObject("cabinType").put("type", "string").put("maxLength", 255);
    ObjectNode options = questionProperties.putObject("options");
    options.put("type", "array");
    options.put("minItems", 2);
    options.put("maxItems", 30);
    options.put("uniqueItems", true);
    options.putObject("items").put("type", "string").put("maxLength", 255);
    return schema;
  }

  private static ObjectNode catalogLookupSchema() {
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.putArray("required").add("warehouseId");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("warehouseId").put("type", "string").put("format", "uuid");
    properties.putObject("query").put("type", "string").put("maxLength", 255);
    return schema;
  }

  private static ObjectNode removeSelectionSchema() {
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ObjectNode properties = schema.putObject("properties");
    ObjectNode ids = properties.putObject("rentalItemIds");
    ids.put("type", "array");
    ids.put("minItems", 1);
    ids.put("maxItems", 100);
    ids.put("uniqueItems", true);
    ids.putObject("items").put("type", "string").put("format", "uuid");
    ObjectNode numbers = properties.putObject("numbers");
    numbers.put("type", "array");
    numbers.put("minItems", 1);
    numbers.put("maxItems", 100);
    numbers.put("uniqueItems", true);
    numbers.putObject("items").put("type", "string").put("maxLength", 128);
    var anyOf = schema.putArray("anyOf");
    anyOf.addObject().putArray("required").add("rentalItemIds");
    anyOf.addObject().putArray("required").add("numbers");
    return schema;
  }
}
