package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Idempotent proof that an owning service performed a warehouse-bound operation.
 *
 * @param operationId immutable operation identity, scoped by warehouse and authenticated source
 * @param occurredAt actual operation instant, not later than the warehouse database clock
 */
public record WarehouseOperationMarkRequest(
    @NotNull UUID operationId, @NotNull OffsetDateTime occurredAt) {}
