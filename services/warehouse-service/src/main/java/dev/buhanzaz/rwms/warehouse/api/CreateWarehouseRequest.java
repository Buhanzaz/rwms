package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a warehouse in the canonical global directory.
 *
 * <p>The service canonicalizes the display name and rejects a duplicate normalized name. The
 * request is paired with a caller-scoped {@code Idempotency-Key} header at the HTTP boundary.
 *
 * @param name display name; whitespace is normalized before uniqueness is evaluated
 * @param city human-readable city
 * @param address optional human-readable address
 * @param timeZone canonical IANA timezone identifier
 * @param sortOrder optional non-negative directory ordering value
 */
public record CreateWarehouseRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String city,
    @Size(max = 1000) String address,
    @NotBlank @Size(max = 64) String timeZone,
    @Min(0) Integer sortOrder) {}
