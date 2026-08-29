package dev.buhanzaz.rwms.warehouse.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Creates a warehouse in the canonical global directory.
 *
 * <p>The service canonicalizes the display name and rejects a duplicate normalized name. The
 * request is paired with a caller-scoped {@code Idempotency-Key} header at the HTTP boundary.
 *
 * @param name display name; whitespace is normalized before uniqueness is evaluated
 * @param city human-readable city
 * @param address optional human-readable address
 * @param latitude optional WGS84 latitude; requires longitude
 * @param longitude optional WGS84 longitude; requires latitude
 * @param timeZone canonical IANA timezone identifier
 * @param sortOrder optional non-negative directory ordering value
 * @param representative whether the warehouse has the representative characteristic
 */
public record CreateWarehouseRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String city,
    @Size(max = 1000) String address,
    @DecimalMin("-90.000000") @DecimalMax("90.000000") @Digits(integer = 2, fraction = 6)
        BigDecimal latitude,
    @DecimalMin("-180.000000") @DecimalMax("180.000000") @Digits(integer = 3, fraction = 6)
        BigDecimal longitude,
    @NotBlank @Size(max = 64) String timeZone,
    @Min(0) Integer sortOrder,
    @JsonProperty(defaultValue = "false")
        @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
        Boolean representative) {

  /** Normalizes the transport default to a non-null value for application code. */
  public CreateWarehouseRequest {
    representative = Boolean.TRUE.equals(representative);
  }

  /**
   * Preserves source compatibility for callers that predate the representative characteristic.
   *
   * @param name display name
   * @param city human-readable city
   * @param address optional human-readable address
   * @param timeZone canonical IANA timezone identifier
   * @param sortOrder optional non-negative directory ordering value
   */
  public CreateWarehouseRequest(
      String name, String city, String address, String timeZone, Integer sortOrder) {
    this(name, city, address, null, null, timeZone, sortOrder, false);
  }

  /**
   * Preserves source compatibility for representative-aware callers that predate coordinates.
   *
   * @param name display name
   * @param city human-readable city
   * @param address optional human-readable address
   * @param timeZone canonical IANA timezone identifier
   * @param sortOrder optional non-negative directory ordering value
   * @param representative whether the warehouse has the representative characteristic
   */
  public CreateWarehouseRequest(
      String name,
      String city,
      String address,
      String timeZone,
      Integer sortOrder,
      Boolean representative) {
    this(name, city, address, null, null, timeZone, sortOrder, representative);
  }
}
