package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delegates every selection read or mutation to logistics-service and keeps assistant history from
 * reviving cabins whose asset-owned holds were released or expired.
 */
@Service
public class AssistantSelectionService {
  private static final int MAX_SELECTED_CABINS = 100;

  private final LogisticsClient logistics;
  private final ObjectMapper mapper;

  public AssistantSelectionService(LogisticsClient logistics, ObjectMapper mapper) {
    this.logistics = logistics;
    this.mapper = mapper;
  }

  /**
   * Returns null for an empty authoritative selection, which is the public detail representation.
   */
  public AssistantApiModels.CabinSelectionResponse current(
      UUID rentalInquiryId, String bearerToken) {
    LogisticsClient.CabinSelection selection =
        logistics.readCabinSelection(rentalInquiryId, bearerToken);
    validateAuthoritativeSelection(rentalInquiryId, selection);
    return selection.rentalItemIds().isEmpty() ? null : response(selection);
  }

  /**
   * Replaces the held selection using the caller key and returns the resulting authoritative state.
   */
  public AssistantApiModels.CabinSelectionResponse replace(
      UUID rentalInquiryId,
      UUID idempotencyKey,
      AssistantApiModels.CabinSelectionRequest request,
      String bearerToken) {
    LogisticsClient.RentalInquiryContext inquiry =
        requireActiveInquiry(rentalInquiryId, bearerToken);
    requireFixedWarehouse(inquiry.warehouseId(), request.warehouseId());
    List<UUID> ids = uniqueIds(request.rentalItemIds());
    LogisticsClient.CabinSelection selection =
        logistics.replaceCabinSelection(
            rentalInquiryId, idempotencyKey, request.warehouseId(), ids, bearerToken);
    validateAuthoritativeSelection(rentalInquiryId, selection);
    boolean requestedEmpty = ids.isEmpty();
    boolean returnedEmpty = selection.rentalItemIds().isEmpty();
    if (requestedEmpty) {
      if (!returnedEmpty
          || !selection.items().isEmpty()
          || selection.warehouseId() != null
          || selection.expiresAt() != null) {
        throw new AssistantUpstreamException("Logistics returned another cabin selection");
      }
    } else if (returnedEmpty) {
      throw new AssistantUpstreamException("Logistics returned another cabin selection");
    } else if (!ids.equals(selection.rentalItemIds())) {
      throw new AssistantUpstreamException("Logistics returned another cabin selection");
    } else if (!request.warehouseId().equals(selection.warehouseId())) {
      throw new AssistantUpstreamException("Logistics returned another selection warehouse");
    }
    return response(selection);
  }

  /**
   * Removes only exact current IDs or unique exact current numbers. The remaining list, never an
   * arbitrary model-provided replacement, is sent to logistics for immediate release.
   */
  public JsonNode remove(
      UUID rentalInquiryId,
      UUID idempotencyKey,
      List<UUID> rentalItemIds,
      List<String> numbers,
      String bearerToken) {
    LogisticsClient.RentalInquiryContext inquiry =
        requireActiveInquiry(rentalInquiryId, bearerToken);
    LogisticsClient.CabinSelection current =
        logistics.readCabinSelection(rentalInquiryId, bearerToken);
    if (current.rentalItemIds().isEmpty() || current.warehouseId() == null) {
      throw new IllegalArgumentException("The conversation has no selected cabins");
    }
    requireFixedWarehouse(inquiry.warehouseId(), current.warehouseId());
    Set<UUID> requestedIds = new LinkedHashSet<>(uniqueIdsAllowEmpty(rentalItemIds));
    Map<String, UUID> uniqueIdsByNumber = new LinkedHashMap<>();
    Set<String> ambiguousNumbers = new LinkedHashSet<>();
    for (JsonNode item : current.items()) {
      String number = item.path("number").asText(null);
      if (number == null) continue;
      UUID id = UUID.fromString(item.path("id").asText());
      UUID previous = uniqueIdsByNumber.putIfAbsent(number, id);
      if (previous != null && !previous.equals(id)) ambiguousNumbers.add(number);
    }
    if (numbers != null) {
      if (numbers.size() > MAX_SELECTED_CABINS) {
        throw new IllegalArgumentException("Too many cabin numbers were requested");
      }
      Set<String> uniqueNumbers = new LinkedHashSet<>();
      for (String number : numbers) {
        if (number == null
            || number.isBlank()
            || number.length() > 128
            || !uniqueNumbers.add(number)) {
          throw new IllegalArgumentException("Cabin numbers must be unique exact values");
        }
        UUID resolved = uniqueIdsByNumber.get(number);
        if (resolved == null || ambiguousNumbers.contains(number)) {
          throw new IllegalArgumentException(
              "Cabin number is not a unique current selection value");
        }
        requestedIds.add(resolved);
      }
    }
    if (requestedIds.isEmpty()
        || !new LinkedHashSet<>(current.rentalItemIds()).containsAll(requestedIds)) {
      throw new IllegalArgumentException("Only exact currently selected cabins can be removed");
    }
    List<UUID> retained =
        current.rentalItemIds().stream().filter(id -> !requestedIds.contains(id)).toList();
    LogisticsClient.CabinSelection result =
        logistics.replaceCabinSelection(
            rentalInquiryId, idempotencyKey, current.warehouseId(), retained, bearerToken);
    ObjectNode normalized = JsonNodeFactory.instance.objectNode();
    normalized.put("tool", AssistantToolDefinitions.REMOVE_SELECTED_CABINS);
    ObjectNode data = mapper.valueToTree(response(result));
    ArrayNode removedIds = data.putArray("removedRentalItemIds");
    requestedIds.forEach(id -> removedIds.add(id.toString()));
    normalized.set("data", data);
    return normalized;
  }

  private LogisticsClient.RentalInquiryContext requireActiveInquiry(
      UUID rentalInquiryId, String bearerToken) {
    LogisticsClient.RentalInquiryContext inquiry =
        logistics.readRentalInquiryContext(rentalInquiryId, bearerToken);
    if (!"ACTIVE".equals(inquiry.state())) throw new AssistantInquiryArchivedException();
    return inquiry;
  }

  private static void requireFixedWarehouse(UUID fixedWarehouseId, UUID requestedWarehouseId) {
    if (fixedWarehouseId != null && !fixedWarehouseId.equals(requestedWarehouseId)) {
      throw new AssistantConflictException(
          "The rental inquiry is already fixed to another warehouse");
    }
  }

  /** Filters a recovered carousel against current held IDs and authoritative expiry. */
  public JsonNode filterRecoveredSearch(
      JsonNode recovered, AssistantApiModels.CabinSelectionResponse selection) {
    if (recovered == null || selection == null || selection.expiresAt() == null) return null;
    if (!recovered.isObject() || !recovered.path("data").isObject()) return null;
    JsonNode data = recovered.path("data");
    if (!data.path("groups").isArray() || selection.warehouseId() == null) return null;
    if (!selection.warehouseId().toString().equals(data.path("warehouseId").asText())) return null;
    Set<String> heldIds =
        selection.rentalItemIds().stream()
            .map(UUID::toString)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    ObjectNode result = (ObjectNode) recovered.deepCopy();
    ObjectNode filteredData = (ObjectNode) result.path("data").deepCopy();
    ArrayNode filteredGroups = JsonNodeFactory.instance.arrayNode();
    Set<String> includedIds = new LinkedHashSet<>();
    for (JsonNode group : data.path("groups")) {
      if (!group.isObject() || !group.path("cabins").isArray()) continue;
      ObjectNode filteredGroup = (ObjectNode) group.deepCopy();
      ArrayNode cabins = JsonNodeFactory.instance.arrayNode();
      for (JsonNode cabin : group.path("cabins")) {
        String id = cabin.path("id").asText(null);
        if (id != null && heldIds.contains(id) && includedIds.add(id)) {
          cabins.add(cabin.deepCopy());
        }
      }
      if (!cabins.isEmpty()) {
        filteredGroup.set("cabins", cabins);
        filteredGroups.add(filteredGroup);
      }
    }
    if (filteredGroups.isEmpty()) return null;
    filteredData.set("groups", filteredGroups);
    filteredData.put("expiresAt", selection.expiresAt().toString());
    result.set("data", filteredData);
    return result;
  }

  private static AssistantApiModels.CabinSelectionResponse response(
      LogisticsClient.CabinSelection selection) {
    return new AssistantApiModels.CabinSelectionResponse(
        selection.inquiryId(),
        selection.warehouseId(),
        selection.expiresAt(),
        selection.rentalItemIds(),
        selection.items());
  }

  private static void validateAuthoritativeSelection(
      UUID requestedInquiryId, LogisticsClient.CabinSelection selection) {
    if (requestedInquiryId == null
        || selection == null
        || !requestedInquiryId.equals(selection.inquiryId())) {
      throw invalidSelection();
    }
    List<UUID> ids = selection.rentalItemIds();
    List<JsonNode> items = selection.items();
    if (ids == null || items == null || ids.size() != items.size()) {
      throw invalidSelection();
    }
    UUID warehouseId = selection.warehouseId();
    if (ids.isEmpty()) {
      if (warehouseId != null || selection.expiresAt() != null) {
        throw invalidSelection();
      }
      return;
    }
    if (warehouseId == null) {
      throw invalidSelection();
    }
    Set<UUID> uniqueIds = new LinkedHashSet<>();
    for (int index = 0; index < ids.size(); index++) {
      UUID expectedId = ids.get(index);
      JsonNode item = items.get(index);
      if (expectedId == null
          || !uniqueIds.add(expectedId)
          || !expectedId.equals(selectionItemUuid(item, "id"))) {
        throw invalidSelection();
      }
      if (!warehouseId.equals(selectionItemUuid(item, "warehouseId"))) {
        throw invalidSelection();
      }
    }
  }

  private static UUID selectionItemUuid(JsonNode item, String field) {
    if (item == null || !item.isObject()) {
      throw invalidSelection();
    }
    JsonNode value = item.get(field);
    if (value == null || !value.isTextual()) {
      throw invalidSelection();
    }
    String raw = value.asText(null);
    if (raw == null) {
      throw invalidSelection();
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException invalid) {
      throw new AssistantUpstreamException(
          "Logistics returned an invalid cabin selection", invalid);
    }
  }

  private static AssistantUpstreamException invalidSelection() {
    return new AssistantUpstreamException("Logistics returned an invalid cabin selection");
  }

  private static List<UUID> uniqueIds(List<UUID> values) {
    List<UUID> result = uniqueIdsAllowEmpty(values);
    if (values == null) throw new IllegalArgumentException("rentalItemIds is required");
    return result;
  }

  private static List<UUID> uniqueIdsAllowEmpty(List<UUID> values) {
    if (values == null) return List.of();
    if (values.size() > MAX_SELECTED_CABINS) {
      throw new IllegalArgumentException("Too many cabins were selected");
    }
    Set<UUID> unique = new LinkedHashSet<>();
    for (UUID value : values) {
      if (value == null || !unique.add(value)) {
        throw new IllegalArgumentException("rentalItemIds must be unique and non-null");
      }
    }
    return List.copyOf(unique);
  }
}
