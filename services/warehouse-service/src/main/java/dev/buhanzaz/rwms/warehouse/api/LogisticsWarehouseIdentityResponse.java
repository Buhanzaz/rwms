package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

public record LogisticsWarehouseIdentityResponse(
    UUID id,
    long version,
    boolean active,
    String name,
    String city,
    String timeZone) {}
