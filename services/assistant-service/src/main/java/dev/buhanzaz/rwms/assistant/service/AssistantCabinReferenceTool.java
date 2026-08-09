package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns read-only cabin reference lookups by number or text and exposes current type, finish,
 * characteristic and type-to-dimension relations without acquiring or renewing a hold.
 */
@Component
public class AssistantCabinReferenceTool {
  private static final Set<String> ARGUMENT_FIELDS = Set.of("warehouseId", "query");
  private static final int PAGE_SIZE = 20;

  private final LogisticsClient logistics;

  public AssistantCabinReferenceTool(LogisticsClient logistics) {
    this.logistics = logistics;
  }

  /** Validates a bounded informational request without contacting logistics-service. */
  public void validate(JsonNode arguments) {
    requireObjectFields(arguments, ARGUMENT_FIELDS, Set.of("warehouseId"));
    requiredUuid(arguments.get("warehouseId"), "warehouseId");
    String query = optionalText(arguments.get("query"), "query", 255);
    if (query != null && query.isBlank()) {
      throw new IllegalArgumentException("query must be nonblank when supplied");
    }
  }

  /**
   * Returns current reference facets and, when query is supplied, one bounded catalog page. This
   * path is deliberately separate from availability search and therefore creates no hold.
   */
  public JsonNode execute(UUID rentalInquiryId, JsonNode arguments, String bearerToken) {
    UUID warehouseId = requiredUuid(arguments.get("warehouseId"), "warehouseId");
    JsonNode facets = logistics.listAvailableCabinFacets(rentalInquiryId, bearerToken);
    AssistantCabinFacetMetadata metadata = AssistantCabinFacetMetadata.from(facets, warehouseId);
    String query = optionalText(arguments.get("query"), "query", 255);
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    ObjectNode scopedFacets = metadata.scopedFacetProjection();
    AssistantToolResultSanitizer.rejectWarehouseBusinessCode(scopedFacets);
    data.set("facets", scopedFacets);
    if (query != null) {
      JsonNode catalog =
          logistics.lookupCabinCatalog(
              rentalInquiryId, warehouseId, query, 0, PAGE_SIZE, bearerToken);
      validateCatalogPage(catalog, warehouseId);
      data.set("catalog", catalog.deepCopy());
    }
    AssistantToolResultSanitizer.rejectContactFields(data);
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("tool", AssistantToolDefinitions.LOOKUP_CABIN_CATALOG);
    result.set("data", data);
    return result;
  }

  private static void validateCatalogPage(JsonNode page, UUID warehouseId) {
    if (page == null
        || !page.isObject()
        || !warehouseId.toString().equals(page.path("warehouseId").asText())
        || !page.path("content").isArray()
        || !page.path("page").isInt()
        || page.path("page").intValue() != 0
        || !page.path("size").isInt()
        || page.path("size").intValue() != PAGE_SIZE
        || !page.path("totalElements").canConvertToLong()
        || page.path("totalElements").longValue() < 0
        || !page.path("totalPages").isInt()
        || page.path("totalPages").intValue() < 0) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin catalog page");
    }
    JsonNode content = page.path("content");
    long totalElements = page.path("totalElements").longValue();
    long expectedTotalPages =
        totalElements / PAGE_SIZE + (totalElements % PAGE_SIZE == 0 ? 0 : 1);
    long expectedContentSize = Math.min(PAGE_SIZE, totalElements);
    if (expectedTotalPages > Integer.MAX_VALUE
        || page.path("totalPages").intValue() != expectedTotalPages
        || content.size() != expectedContentSize) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin catalog page");
    }
    Set<UUID> itemIds = new LinkedHashSet<>();
    for (JsonNode item : content) {
      UUID itemId = catalogItemId(item);
      if (itemId == null
          || !warehouseId.toString().equals(item.path("warehouseId").asText())
          || !itemIds.add(itemId)) {
        throw new AssistantUpstreamException("Logistics returned an invalid cabin catalog page");
      }
    }
  }

  private static UUID catalogItemId(JsonNode item) {
    if (item == null || !item.isObject() || !item.path("id").isTextual()) return null;
    try {
      return UUID.fromString(item.path("id").asText());
    } catch (IllegalArgumentException invalid) {
      return null;
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

  private static String optionalText(JsonNode value, String field, int maximumLength) {
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
    String text = value.asText();
    if (text.length() > maximumLength) {
      throw new IllegalArgumentException(field + " is too long");
    }
    return text;
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
}
