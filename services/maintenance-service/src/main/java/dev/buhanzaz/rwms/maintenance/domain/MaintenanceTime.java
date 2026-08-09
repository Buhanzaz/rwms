package dev.buhanzaz.rwms.maintenance.domain;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Normalizes maintenance timestamps to UTC and PostgreSQL microsecond precision. */
final class MaintenanceTime {
  private MaintenanceTime() {}

  static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  static OffsetDateTime postgresPrecision(OffsetDateTime value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }
}
