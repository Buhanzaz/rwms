package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

public record WarehouseResponse(
    UUID id,
    long version,
    String name,
    String city,
    String address,
    String timeZone,
    boolean active,
    String lifecycleState,
    Integer sortOrder) {}
