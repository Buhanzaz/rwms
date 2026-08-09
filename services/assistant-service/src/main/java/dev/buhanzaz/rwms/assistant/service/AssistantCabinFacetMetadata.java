package dev.buhanzaz.rwms.assistant.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates and indexes one logistics-owned warehouse facet projection without treating facets as
 * availability proof.
 */
public final class AssistantCabinFacetMetadata {
  private final UUID warehouseId;
  private final List<String> cabinTypes;
  private final List<String> finishes;
  private final List<String> dimensions;
  private final List<String> categories;
  private final List<String> characteristics;
  private final Map<String, List<String>> dimensionsByType;
  private final ObjectNode warehouseProjection;

  private AssistantCabinFacetMetadata(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories,
      List<String> characteristics,
      Map<String, List<String>> dimensionsByType,
      ObjectNode warehouseProjection) {
    this.warehouseId = warehouseId;
    this.cabinTypes = List.copyOf(cabinTypes);
    this.finishes = List.copyOf(finishes);
    this.dimensions = List.copyOf(dimensions);
    this.categories = List.copyOf(categories);
    this.characteristics = List.copyOf(characteristics);
    this.dimensionsByType = Map.copyOf(dimensionsByType);
    this.warehouseProjection = warehouseProjection.deepCopy();
  }

  /** Selects one exact warehouse and rejects malformed or contradictory upstream facet metadata. */
  public static AssistantCabinFacetMetadata from(JsonNode response, UUID warehouseId) {
    if (response == null || !response.isObject() || warehouseId == null) {
      throw new AssistantUpstreamException("Logistics returned invalid cabin facets");
    }
    JsonNode warehouses = response.path("warehouses");
    if (!warehouses.isArray()) {
      throw new AssistantUpstreamException("Logistics returned invalid cabin facets");
    }
    JsonNode selected = null;
    for (JsonNode warehouse : warehouses) {
      if (warehouseId.toString().equals(warehouse.path("warehouseId").asText())) {
        if (selected != null) {
          throw new AssistantUpstreamException("Logistics returned duplicate warehouse facets");
        }
        selected = warehouse;
      }
    }
    if (selected == null) {
      throw new IllegalArgumentException("warehouseId is not an exact available warehouse");
    }
    List<String> cabinTypes = exactValues(selected, "cabinTypes");
    List<String> finishes = exactValues(selected, "finishes");
    List<String> dimensions = exactValues(selected, "dimensions");
    List<String> categories = exactValues(selected, "categories");
    List<String> characteristics = exactValues(selected, "characteristics");
    Map<String, List<String>> dimensionsByType = new LinkedHashMap<>();
    JsonNode relations = selected.path("typeDimensions");
    if (!relations.isArray()) {
      throw new AssistantUpstreamException("Logistics returned invalid type-dimension facets");
    }
    for (JsonNode relation : relations) {
      String cabinType = requiredExactText(relation.path("cabinType"));
      List<String> relatedDimensions = exactValues(relation, "dimensions");
      if (!cabinTypes.contains(cabinType)
          || !dimensions.containsAll(relatedDimensions)
          || dimensionsByType.putIfAbsent(cabinType, relatedDimensions) != null) {
        throw new AssistantUpstreamException("Logistics returned invalid type-dimension facets");
      }
    }
    return new AssistantCabinFacetMetadata(
        warehouseId,
        cabinTypes,
        finishes,
        dimensions,
        categories,
        characteristics,
        dimensionsByType,
        (ObjectNode) selected);
  }

  public UUID warehouseId() {
    return warehouseId;
  }

  public List<String> cabinTypes() {
    return cabinTypes;
  }

  public List<String> finishes() {
    return finishes;
  }

  public List<String> dimensions() {
    return dimensions;
  }

  public List<String> categories() {
    return categories;
  }

  public List<String> characteristics() {
    return characteristics;
  }

  /** Returns only dimensions that the authoritative facet response relates to this exact type. */
  public List<String> dimensionsFor(String cabinType) {
    return dimensionsByType.getOrDefault(cabinType, List.of());
  }

  /** Returns exact types whose authoritative dimension relation accepts the requested size. */
  public List<String> cabinTypesCompatibleWith(String requestedDimension) {
    if (requestedDimension == null) return cabinTypes;
    return cabinTypes.stream()
        .filter(
            cabinType ->
                dimensionsFor(cabinType).stream()
                    .anyMatch(
                        dimension ->
                            dimension.equals(requestedDimension)
                                || (isSixMetreRequest(requestedDimension)
                                    && isSixByTwoFour(dimension))))
        .toList();
  }

  /** Resolves the supported six-metre shorthand only inside one already selected exact type. */
  public String resolveDimension(String cabinType, String requested) {
    List<String> related = dimensionsFor(cabinType);
    if (requested == null) return related.size() == 1 ? related.getFirst() : null;
    if (related.contains(requested)) return requested;
    if (!isSixMetreRequest(requested)) {
      throw new IllegalArgumentException("dimensions is not related to the selected cabin type");
    }
    List<String> candidates = related.stream()
        .filter(AssistantCabinFacetMetadata::isSixByTwoFour)
        .toList();
    if (candidates.size() != 1) {
      throw new IllegalArgumentException("Six-metre dimensions are ambiguous for this cabin type");
    }
    return candidates.getFirst();
  }

  /** Builds exact post-result filters that the model may suggest without inventing values. */
  public ObjectNode filterSuggestions() {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    addValues(result, "cabinTypes", cabinTypes);
    addValues(result, "finishes", finishes);
    addValues(result, "dimensions", dimensions);
    addValues(result, "categories", categories);
    addValues(result, "characteristics", characteristics);
    return result;
  }

  /** Projects only the validated requested warehouse for a scoped informational tool result. */
  public ObjectNode scopedFacetProjection() {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.putArray("warehouses").add(warehouseProjection.deepCopy());
    return result;
  }

  private static List<String> exactValues(JsonNode object, String field) {
    JsonNode values = object.path(field);
    if (!values.isArray()) {
      throw new AssistantUpstreamException("Logistics returned invalid cabin facets");
    }
    Set<String> unique = new LinkedHashSet<>();
    for (JsonNode value : values) {
      String text = requiredExactText(value);
      if (!unique.add(text)) {
        throw new AssistantUpstreamException("Logistics returned duplicate cabin facets");
      }
    }
    return List.copyOf(unique);
  }

  private static String requiredExactText(JsonNode value) {
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 255) {
      throw new AssistantUpstreamException("Logistics returned invalid cabin facets");
    }
    return value.asText();
  }

  private static boolean isSixMetreRequest(String value) {
    String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace(',', '.');
    return normalized.matches("6(?:\\.0+)?\\s*(?:м|метр|метра|метров)");
  }

  private static boolean isSixByTwoFour(String value) {
    String normalized =
        value.toLowerCase(java.util.Locale.ROOT)
            .replace('х', 'x')
            .replace('×', 'x')
            .replace(',', '.')
            .replaceAll("\\s+", "");
    String[] parts = normalized.split("x");
    if (parts.length != 2) return false;
    try {
      double first = Double.parseDouble(parts[0]);
      double second = Double.parseDouble(parts[1]);
      return (first == 6.0 && second == 2.4) || (first == 2.4 && second == 6.0);
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static void addValues(ObjectNode target, String field, List<String> values) {
    ArrayNode array = target.putArray(field);
    values.forEach(array::add);
  }
}
