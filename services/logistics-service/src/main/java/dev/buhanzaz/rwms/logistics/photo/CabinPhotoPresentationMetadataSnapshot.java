package dev.buhanzaz.rwms.logistics.photo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable allowlisted cabin metadata frozen with a public photo presentation. Nullable catalog
 * values remain nullable, while characteristics always retain a bounded deterministic list shape.
 */
public record CabinPhotoPresentationMetadataSnapshot(
    String dimensions,
    String finishing,
    String category,
    List<String> characteristics,
    Boolean linoleum,
    Long pricingVersion,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Long monthlyPriceRubles) {
  private static final int MAXIMUM_TEXT_LENGTH = 255;
  private static final int MAXIMUM_CHARACTERISTICS = 100;

  /** Normalizes trusted catalog labels and rejects malformed dependency or persisted values. */
  public CabinPhotoPresentationMetadataSnapshot {
    if ((pricingVersion == null) != (monthlyPriceRubles == null)
        || (pricingVersion != null && (pricingVersion < 0 || monthlyPriceRubles < 0))) {
      throw new IllegalArgumentException("Rental price snapshot is invalid");
    }
    dimensions = normalizeNullable(dimensions, "dimensions");
    finishing = normalizeNullable(finishing, "finishing");
    category = normalizeNullable(category, "category");
    characteristics = normalizeCharacteristics(characteristics);
  }

  private static String normalizeNullable(String value, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > MAXIMUM_TEXT_LENGTH) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static List<String> normalizeCharacteristics(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    if (values.size() > MAXIMUM_CHARACTERISTICS) {
      throw new IllegalArgumentException("characteristics are invalid");
    }
    List<String> normalized = new ArrayList<>(values.size());
    for (String value : values) {
      String characteristic = normalizeCharacteristic(value);
      normalized.add(characteristic);
    }
    return List.copyOf(normalized);
  }

  private static String normalizeCharacteristic(String value) {
    String normalized = normalizeNullable(value, "characteristic");
    if (normalized == null) throw new IllegalArgumentException("characteristic is invalid");
    return normalized;
  }
}
