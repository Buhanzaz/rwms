package dev.buhanzaz.rwms.warehouse.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Generic least-privilege warehouse metadata required by an owning internal workflow. */
public record InternalWarehouseIdentityResponse(
    UUID id,
    long version,
    boolean active,
    String name,
    String city,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String timeZone) {}
