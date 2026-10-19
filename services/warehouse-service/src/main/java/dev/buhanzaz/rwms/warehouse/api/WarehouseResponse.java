package dev.buhanzaz.rwms.warehouse.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Canonical public warehouse representation for the one RWMS installation. */
public record WarehouseResponse(
    UUID id,
    long version,
    String name,
    String city,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String timeZone,
    boolean active,
    String lifecycleState,
    Integer sortOrder,
    boolean representative,
    boolean production,
    boolean mainWarehouse,
    UUID representativeParentWarehouseId) {}
