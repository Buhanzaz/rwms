package dev.buhanzaz.rwms.warehouse.eventing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WarehouseEventPayload(
    UUID warehouseId,
    String timeZone,
    boolean active,
    Integer sortOrder,
    @JsonInclude(JsonInclude.Include.NON_NULL) TimeZoneDecision timeZoneDecision) {
  public record TimeZoneDecision(String timeZone, OffsetDateTime effectiveFrom) {}
}
