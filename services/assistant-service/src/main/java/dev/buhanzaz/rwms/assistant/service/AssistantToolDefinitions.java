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
  public static final String REQUEST_SEARCH_MERGE_CONFIRMATION =
      "request_search_merge_confirmation";

  public List<ChatCompletionClient.ToolDefinition> definitions() {
    return definitions(true);
  }

  /**
   * The merge-question tool is deliberately omitted when the current inquiry has no live
   * carousel. This keeps the model from asking about a selection that has already expired.
   */
  public List<ChatCompletionClient.ToolDefinition> definitions(boolean hasActiveSearchResult) {
    List<ChatCompletionClient.ToolDefinition> base =
        List.of(
            new ChatCompletionClient.ToolDefinition(
                LIST_AVAILABLE_CABIN_FACETS,
                "List bounded exact availability facets for the current rental inquiry. It takes no "
                    + "arguments. Use it before a search whenever a warehouse or requested cabin "
                    + "filter is blank, ambiguous, fuzzy, typo-like, or not already an exact facet "
                    + "value. Prefer a unique one-character correction (for example, ТВП to ДВП) "
                    + "and proceed with the exact returned value. Facets do not prove that a "
                    + "combination is available.",
                emptyObjectSchema()),
            new ChatCompletionClient.ToolDefinition(
                SEARCH_AVAILABLE_CABINS,
                "Search cabins for the current rental inquiry using one to five logical structured "
                    + "groups. "
                    + "warehouseId is required and must be an exact warehouse ID returned by "
                    + "list_available_cabin_facets. cabinType, finish, dimensions and category must "
                    + "use exact facet values; never transform dimensions. characteristics forwards "
                    + "only the characteristic text requested by the user: do not invent, normalize, "
                    + "or infer it. Set linoleum=true when the user requests linoleum and "
                    + "linoleum=false only when the user explicitly requests no linoleum; otherwise "
                    + "omit linoleum. Every result contains only FREE cabins. Omit category for "
                    + "default, all, show, free, available, \"все\", "
                    + "\"свободные\", or \"доступные\" requests. Set category to exact returned "
                    + "\"Новая\" whenever the user explicitly requests new cabins, including "
                    + "\"новые\", \"покажи новые\", and \"только новые\"; \"новая\" is a category, "
                    + "not a status. Preserve the applicable category, as well as filters and quantity, "
                    + "in follow-up and alternative searches. A group may use category for one exact category, "
                    + "or categories for exact OR category options. Do not put both fields in one group. "
                    + "When the user asks for multiple categories for one cabin type, use one logical group "
                    + "with categories so that one cabin-type result is returned. Use the exact user quantity, "
                    + "or quantity 30 only when the user explicitly asks to show all matching cabins. "
                    + "Every distinct requested combination or cabin type needs a separate group with "
                    + "its own requested quantity: six БК-1 plus six БК-2 means two groups with quantity 6. "
                    + "For one shared quantity distributed across several cabin types, first list facets, "
                    + "then create one logical group per exact cabin type, omit every group quantity, and "
                    + "set the one top-level totalQuantity. For example, ten cabins split by types and "
                    + "limited to Обычная or ИТР uses totalQuantity 10, a group for each exact type, and "
                    + "categories [\"Обычная\", \"ИТР\"] in each group. Do not use totalQuantity with "
                    + "per-group quantities. The sum of all explicit per-group quantities must not "
                    + "exceed 100. "
                    + "For a follow-up asking to show all, repeat the last applicable structured "
                    + "filters in a fresh search; listing facets alone is not an answer, and neither "
                    + "is quoting a historical search. resultMode defaults to REPLACE. Use APPEND "
                    + "only after the manager explicitly confirms that this new request should be "
                    + "added to the active unpublished selection; use REPLACE for a replacement. "
                    + "Requests to repeat, retry, refresh, recheck, "
                    + "show, or search for cabins always require a new call to this tool in the same "
                    + "turn. If an exact search is empty, do not answer yet: call this tool once more "
                    + "with alternative groups formed by removing one filter at a time, and label "
                    + "those results as broader alternatives.",
                searchSchema()));
    if (!hasActiveSearchResult) return base;
    return List.of(
        base.getFirst(),
        base.getLast(),
        new ChatCompletionClient.ToolDefinition(
            REQUEST_SEARCH_MERGE_CONFIRMATION,
            "Ask the manager whether a new cabin request must be added to the active, "
                + "unpublished selection or replace it. This tool has no logistics or "
                + "business-state effect. Use it only when an active selection exists and the "
                + "current request introduces an additional new group/type/set and does not "
                + "explicitly say add/append or replace. Do not use it for refresh, retry, "
                + "show-all, or a refinement of the current request. After this "
                + "tool, ask one concise question: \"Добавить к текущей подборке или заменить её?\". "
                + "After a later affirmative/add answer, make a fresh search_available_cabins "
                + "call with resultMode APPEND; after replace, use resultMode REPLACE.",
            emptyObjectSchema()));
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
    groupProperties
        .putObject("characteristics")
        .put("type", "string")
        .put("maxLength", 2000);
    groupProperties.putObject("linoleum").put("type", "boolean");
    groupProperties
        .putObject("quantity")
        .put("type", "integer")
        .put("minimum", 1)
        .put("maximum", 30);
    properties.putObject("warehouseId").put("type", "string").put("format", "uuid");
    return schema;
  }
}
