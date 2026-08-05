package dev.buhanzaz.rwms.asset.eventing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Storage/backlog signals for the append-only asset evidence store.
 *
 * <p>Domain events, snapshots and movements are deliberately not age-deleted: replay and property
 * audit depend on them. These gauges make capacity and archive decisions observable instead of
 * introducing an unsafe blind cleanup job.
 */
@Component
public class AssetEventingMetrics {
  private final MeterRegistry registry;
  private final JdbcTemplate jdbc;

  public AssetEventingMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
    this.registry = registry;
    this.jdbc = jdbc;

    countGauge(
        "rwms.asset.event_store.rows",
        "select count(*) from domain_event",
        "Append-only asset domain event rows");
    countGauge(
        "rwms.asset.snapshot.rows",
        "select count(*) from aggregate_snapshot",
        "Append-only asset aggregate snapshot rows");
    countGauge(
        "rwms.asset.equipment_movement.rows",
        "select count(*) from equipment_movement",
        "Immutable equipment movement rows");
    countGauge(
        "rwms.asset.outbox.rows",
        "select count(*) from outbox_event",
        "All retained asset outbox rows");
    countGauge(
        "rwms.asset.outbox.backlog",
        "select count(*) from outbox_event where status in ('PENDING','IN_FLIGHT')",
        "Asset outbox rows awaiting broker acknowledgement");
    decimalGauge(
        "rwms.asset.outbox.oldest.age.seconds",
        """
        select coalesce(extract(epoch from (clock_timestamp()-min(created_at))),0)
        from outbox_event where status in ('PENDING','IN_FLIGHT')
        """,
        "Age of the oldest unpublished asset outbox row");
    countGauge(
        "rwms.asset.outbox.terminal",
        "select count(*) from outbox_event where status in ('DLT','QUARANTINED')",
        "Terminal asset outbox rows requiring reviewed recovery");
    countGauge(
        "rwms.asset.version_gap.open",
        "select count(*) from version_gap_quarantine where status='OPEN'",
        "Open asset inbound aggregate version gaps");

    storageGauge("domain_event");
    storageGauge("aggregate_snapshot");
    storageGauge("outbox_event");
    storageGauge("equipment_movement");
    storageGauge("equipment_movement_ledger");
  }

  private void countGauge(String name, String sql, String description) {
    Gauge.builder(name, this, ignored -> count(sql)).description(description).register(registry);
  }

  private void decimalGauge(String name, String sql, String description) {
    Gauge.builder(name, this, ignored -> decimal(sql)).description(description).register(registry);
  }

  private void storageGauge(String table) {
    Gauge.builder(
            "rwms.asset.storage.bytes",
            this,
            ignored -> decimal("select pg_total_relation_size('public." + table + "')"))
        .tag("table", table)
        .description("PostgreSQL table plus index bytes retained by the asset evidence store")
        .register(registry);
  }

  private double count(String sql) {
    try {
      Long value = jdbc.queryForObject(sql, Long.class);
      return value == null ? 0 : value.doubleValue();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }

  private double decimal(String sql) {
    try {
      BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
      return value == null ? 0 : value.doubleValue();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }
}
