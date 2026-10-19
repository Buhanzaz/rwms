package dev.buhanzaz.rwms.warehouse.eventing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Exposes the warehouse outbox backlog, its oldest pending age, and terminal recovery work.
 *
 * <p>The gauges are read-only views of the service-owned PostgreSQL outbox. They make a delivery
 * backlog visible without treating Kafka as storage or mutating rows during observation.
 */
@Component
public final class WarehouseEventingMetrics {
  private final MeterRegistry registry;
  private final JdbcTemplate jdbc;

  /** Registers lazy database-backed gauges in the service meter registry. */
  public WarehouseEventingMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
    this.registry = registry;
    this.jdbc = jdbc;

    countGauge(
        "rwms.warehouse.outbox.backlog",
        "select count(*) from outbox_event where status in ('PENDING','IN_FLIGHT')",
        "Warehouse outbox rows awaiting broker acknowledgement");
    Gauge.builder(
            "rwms.warehouse.outbox.oldest.age.seconds",
            this,
            ignored -> oldestPendingAgeSeconds())
        .description("Age of the oldest unpublished warehouse outbox row")
        .register(registry);
    countGauge(
        "rwms.warehouse.outbox.terminal",
        "select count(*) from outbox_event where status in ('DLT','QUARANTINED')",
        "Terminal warehouse outbox rows requiring reviewed recovery");
  }

  private void countGauge(String name, String sql, String description) {
    Gauge.builder(name, this, ignored -> count(sql)).description(description).register(registry);
  }

  private double count(String sql) {
    try {
      Long value = jdbc.queryForObject(sql, Long.class);
      return value == null ? 0 : value.doubleValue();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }

  private double oldestPendingAgeSeconds() {
    try {
      BigDecimal value =
          jdbc.queryForObject(
              """
              select coalesce(extract(epoch from (clock_timestamp()-min(created_at))),0)
              from outbox_event where status in ('PENDING','IN_FLIGHT')
              """,
              BigDecimal.class);
      return value == null ? 0 : value.doubleValue();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }
}
