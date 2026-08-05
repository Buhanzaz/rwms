package dev.buhanzaz.rwms.taskboard.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative, as-of warehouse timezone boundary.
 *
 * <p>The warehouse-service owns timezone history. Task-board may keep only a short-lived cache
 * of these immutable facts and must never infer past calendar zones from current metadata.
 */
public interface WarehouseTimeZoneGateway {
  Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

  TimeZoneDecision timeZoneAt(UUID warehouseId, Instant at);

  /** Returns contiguous zone segments that cover the requested non-empty interval. */
  List<TimeZoneSegment> timeline(UUID warehouseId, Instant fromInclusive, Instant toExclusive);

  /** Invalidates only transient local cache entries for one warehouse. */
  void invalidate(UUID warehouseId);

  record TimeZoneDecision(ZoneId timeZone, Instant effectiveFrom) {}

  record TimeZoneSegment(ZoneId timeZone, Instant fromInclusive, Instant toExclusive) {
    public TimeZoneSegment {
      if (timeZone == null || fromInclusive == null || toExclusive == null) {
        throw new IllegalArgumentException("Warehouse timezone segment is incomplete");
      }
      if (!fromInclusive.isBefore(toExclusive)) {
        throw new IllegalArgumentException("Warehouse timezone segment must have positive duration");
      }
    }
  }
}
