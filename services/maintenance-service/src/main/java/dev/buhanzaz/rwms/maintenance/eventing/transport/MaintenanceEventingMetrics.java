package dev.buhanzaz.rwms.maintenance.eventing.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Exposes low-cardinality maintenance outbox backlog, age, and terminal recovery gauges.
 *
 * <p>Each gauge is a read-only view of the service-owned PostgreSQL outbox. Database failures
 * produce {@link Double#NaN} so a scrape cannot fabricate a healthy zero or mutate recovery state.
 */
@Component
public final class MaintenanceEventingMetrics {
  private final MeterRegistry registry;
  private final JdbcTemplate jdbc;

  /** Registers three lazily evaluated database-backed gauges without event-identity tags. */
  public MaintenanceEventingMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
    this.registry = registry;
    this.jdbc = jdbc;

    countGauge(
        "rwms.maintenance.outbox.backlog",
        "select count(*) from outbox_event where status in ('PENDING','IN_FLIGHT')",
        "Maintenance outbox rows awaiting broker acknowledgement");
    Gauge.builder(
            "rwms.maintenance.outbox.oldest.age.seconds",
            this,
            ignored -> oldestPendingAgeSeconds())
        .description("Age of the oldest unpublished maintenance outbox row")
        .register(registry);
    countGauge(
        "rwms.maintenance.outbox.terminal",
        "select count(*) from outbox_event where status in ('DLT','QUARANTINED')",
        "Terminal maintenance outbox rows requiring reviewed recovery");
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
