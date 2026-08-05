package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WarehouseOperationMarkRequest(
    @NotNull UUID operationId, @NotNull OffsetDateTime occurredAt) {}
