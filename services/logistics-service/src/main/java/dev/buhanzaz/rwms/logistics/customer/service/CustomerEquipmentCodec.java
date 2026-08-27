package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinEquipmentSelection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Canonical JSON codec for the complete per-cabin CustomerApp furniture intent. */
@Component
public class CustomerEquipmentCodec {
  private final ObjectMapper json;

  /** Creates a codec with the service's fail-on-unknown Jackson configuration. */
  public CustomerEquipmentCodec(ObjectMapper json) {
    this.json = json;
  }

  /** Canonicalizes, validates and serializes a complete selection. */
  public String encode(
      List<CustomerCabinEquipmentSelection> requested, Set<UUID> selectedCabins) {
    List<CustomerCabinEquipmentSelection> normalized = normalize(requested, selectedCabins);
    try {
      return json.writeValueAsString(normalized);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Customer equipment selection could not be serialized", exception);
    }
  }

  /** Reads a previously canonicalized selection. */
  public List<CustomerCabinEquipmentSelection> decode(String value) {
    try {
      List<CustomerCabinEquipmentSelection> decoded =
          json.readValue(
              value == null ? "[]" : value,
              new TypeReference<List<CustomerCabinEquipmentSelection>>() {});
      return normalize(decoded, null);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored customer equipment selection is invalid", exception);
    }
  }

  /** Removes furniture belonging to cabins no longer present in the authoritative hold set. */
  public String retain(String value, Set<UUID> selectedCabins) {
    List<CustomerCabinEquipmentSelection> retained =
        decode(value).stream().filter(item -> selectedCabins.contains(item.cabinUnitId())).toList();
    return encode(retained, selectedCabins);
  }

  private static List<CustomerCabinEquipmentSelection> normalize(
      List<CustomerCabinEquipmentSelection> requested, Set<UUID> selectedCabins) {
    if (requested == null) throw new IllegalArgumentException("Equipment selections are required");
    if (requested.size() > 500) throw new IllegalArgumentException("Too many equipment selections");
    Map<SelectionKey, Long> quantities = new LinkedHashMap<>();
    for (CustomerCabinEquipmentSelection item : requested) {
      if (item == null
          || item.cabinUnitId() == null
          || item.inventoryItemId() == null
          || item.quantity() == null
          || item.quantity() < 1) {
        throw new IllegalArgumentException("Equipment selection is invalid");
      }
      if (selectedCabins != null && !selectedCabins.contains(item.cabinUnitId())) {
        throw new IllegalArgumentException("Equipment belongs to an unselected cabin");
      }
      SelectionKey key = new SelectionKey(item.cabinUnitId(), item.inventoryItemId());
      if (quantities.putIfAbsent(key, item.quantity()) != null) {
        throw new IllegalArgumentException("Equipment selection contains duplicate cabin positions");
      }
    }
    List<CustomerCabinEquipmentSelection> result = new ArrayList<>(quantities.size());
    quantities.entrySet().stream()
        .sorted(
            Comparator.comparing((Map.Entry<SelectionKey, Long> entry) -> entry.getKey().cabinId())
                .thenComparing(entry -> entry.getKey().equipmentId()))
        .forEach(
            entry ->
                result.add(
                    new CustomerCabinEquipmentSelection(
                        entry.getKey().cabinId(),
                        entry.getKey().equipmentId(),
                        entry.getValue())));
    return List.copyOf(result);
  }

  /** Unique identity for one cabin/equipment quantity. */
  private record SelectionKey(UUID cabinId, UUID equipmentId) {}
}
