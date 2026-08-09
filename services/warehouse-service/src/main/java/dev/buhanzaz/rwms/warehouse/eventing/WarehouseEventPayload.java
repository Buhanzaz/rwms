package dev.buhanzaz.rwms.warehouse.eventing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Sanitized warehouse snapshot carried by the canonical warehouse event family. */
public record WarehouseEventPayload(
    UUID warehouseId,
    String timeZone,
    boolean active,
    Integer sortOrder,
    @JsonInclude(JsonInclude.Include.NON_NULL) TimeZoneDecision timeZoneDecision) {
  /** Effective-dated timezone decision included only when the decision changes. */
  public record TimeZoneDecision(String timeZone, OffsetDateTime effectiveFrom) {}
}
