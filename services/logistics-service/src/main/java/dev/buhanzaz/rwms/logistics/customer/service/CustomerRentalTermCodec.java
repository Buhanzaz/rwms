package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinRentalTerm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Canonical JSON codec for CustomerApp's complete per-cabin initial rental durations. */
@Component
public class CustomerRentalTermCodec {
  private final ObjectMapper json;

  /** Creates a codec with the service's fail-on-unknown Jackson configuration. */
  public CustomerRentalTermCodec(ObjectMapper json) {
    this.json = json;
  }

  /** Canonicalizes, validates and serializes a complete rental-term set. */
  public String encode(List<CustomerCabinRentalTerm> requested, Set<UUID> selectedCabins) {
    List<CustomerCabinRentalTerm> normalized = normalize(requested, selectedCabins);
    try {
      return json.writeValueAsString(normalized);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Customer rental terms could not be serialized", exception);
    }
  }

  /** Reads a previously canonicalized rental-term set. */
  public List<CustomerCabinRentalTerm> decode(String value) {
    try {
      List<CustomerCabinRentalTerm> decoded =
          json.readValue(
              value == null ? "[]" : value,
              new TypeReference<List<CustomerCabinRentalTerm>>() {});
      return normalize(decoded, null);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored customer rental terms are invalid", exception);
    }
  }

  /**
   * Removes terms for released cabins and initializes every newly selected cabin to one month.
   */
  public String retain(String value, Set<UUID> selectedCabins) {
    return encode(completeWithDefaults(value, selectedCabins), selectedCabins);
  }

  /**
   * Returns one term per selected cabin, preserving stored values and filling absent values with the
   * same one-month default displayed by CustomerApp.
   */
  public List<CustomerCabinRentalTerm> completeWithDefaults(
      String value, Set<UUID> selectedCabins) {
    if (selectedCabins == null) throw new IllegalArgumentException("Selected cabins are required");
    Map<UUID, Long> monthsByCabin = new LinkedHashMap<>();
    decode(value).stream()
        .filter(item -> selectedCabins.contains(item.cabinUnitId()))
        .forEach(item -> monthsByCabin.put(item.cabinUnitId(), item.rentalMonths()));
    selectedCabins.forEach(cabinId -> monthsByCabin.putIfAbsent(cabinId, 1L));
    return normalize(
        monthsByCabin.entrySet().stream()
            .map(entry -> new CustomerCabinRentalTerm(entry.getKey(), entry.getValue()))
            .toList(),
        selectedCabins);
  }

  private static List<CustomerCabinRentalTerm> normalize(
      List<CustomerCabinRentalTerm> requested, Set<UUID> selectedCabins) {
    if (requested == null) throw new IllegalArgumentException("Rental terms are required");
    if (requested.size() > 100) throw new IllegalArgumentException("Too many rental terms");
    Map<UUID, Long> monthsByCabin = new LinkedHashMap<>();
    for (CustomerCabinRentalTerm term : requested) {
      if (term == null
          || term.cabinUnitId() == null
          || term.rentalMonths() == null
          || term.rentalMonths() < 1
          || term.rentalMonths() > 120) {
        throw new IllegalArgumentException("Rental term is invalid");
      }
      if (selectedCabins != null && !selectedCabins.contains(term.cabinUnitId())) {
        throw new IllegalArgumentException("Rental term belongs to an unselected cabin");
      }
      if (monthsByCabin.putIfAbsent(term.cabinUnitId(), term.rentalMonths()) != null) {
        throw new IllegalArgumentException("Rental term contains a duplicate cabin");
      }
    }
    List<CustomerCabinRentalTerm> result = new ArrayList<>(monthsByCabin.size());
    monthsByCabin.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
        .forEach(entry -> result.add(new CustomerCabinRentalTerm(entry.getKey(), entry.getValue())));
    return List.copyOf(result);
  }
}
