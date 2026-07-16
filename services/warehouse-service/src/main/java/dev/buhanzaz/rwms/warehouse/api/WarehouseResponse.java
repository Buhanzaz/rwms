package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

public record WarehouseResponse(
    UUID id,
    long version,
    String code,
    String name,
    String city,
    String address,
    String timeZone,
    boolean active,
    Integer sortOrder) {}
