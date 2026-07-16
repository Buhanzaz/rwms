package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

public record InternalWarehouseExistenceResponse(UUID id, long version, boolean active) {}
