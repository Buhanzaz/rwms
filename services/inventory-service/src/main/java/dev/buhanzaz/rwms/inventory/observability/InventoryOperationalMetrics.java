package dev.buhanzaz.rwms.inventory.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality operational gauges read from the inventory service's authoritative database.
 *
 * <p>The suppliers intentionally query only when a registry scrapes a gauge. This lets the
 * application start while Flyway is still applying the schema and makes a transient database
 * outage visible as a missing ({@link Double#NaN}) sample instead of an exception from the
 * metrics endpoint. Query failures are logged with per-metric rate limiting so a sustained
 * infrastructure failure remains diagnosable without emitting one stack trace per scrape.
 */
@Component
public final class InventoryOperationalMetrics {
  private static final Logger log = LoggerFactory.getLogger(InventoryOperationalMetrics.class);
  private static final long FAILURE_LOG_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

  private final MeterRegistry registry;
  private final EntityManager entityManager;
  private final AtomicBoolean registered = new AtomicBoolean();
  private final ConcurrentMap<String, Long> lastFailureLogMillis = new ConcurrentHashMap<>();
  private final Set<String> unavailableMetrics = ConcurrentHashMap.newKeySet();

  public InventoryOperationalMetrics(MeterRegistry registry, EntityManager entityManager) {
    this.registry = registry;
    this.entityManager = entityManager;
  }

  @PostConstruct
  void register() {
    if (!registered.compareAndSet(false, true)) {
      return;
    }

    registerCountGauge(
        "rwms.inventory.outbox.backlog",
        "Inventory outbox records awaiting broker acknowledgement.",
        """
        select count(*)
          from outbox_event
         where status in ('PENDING', 'IN_FLIGHT')
        """);
    registerAgeGauge(
        "rwms.inventory.outbox.oldest.age.seconds",
        "Age in seconds of the oldest inventory outbox record awaiting broker acknowledgement.",
        """
        select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
          from outbox_event
         where status in ('PENDING', 'IN_FLIGHT')
        """);

    registerCountGauge(
        "rwms.inventory.inbox.retry.current",
        "Inbound inventory facts currently queued for retry.",
        "select count(*) from inbox_message where status = 'RETRY'");
    registerCountGauge(
        "rwms.inventory.inbox.quarantined.current",
        "Inbound inventory facts quarantined for operator reconciliation.",
        "select count(*) from inbox_message where status = 'QUARANTINED'");
    registerCountGauge(
        "rwms.inventory.inbox.dlt.current",
        "Inbound inventory facts that exhausted processing into the dead-letter path.",
        "select count(*) from inbox_message where status = 'DLT'");
    registerCountGauge(
        "rwms.inventory.version_gap.open",
        "Open inbound aggregate version gaps requiring reconciliation.",
        "select count(*) from version_gap_quarantine where status = 'OPEN'");

    registerCountGauge(
        "rwms.inventory.publication.pending.current",
        "Unsettled publication intents, including ready, submitted, and retryable intents.",
        """
        select count(*)
          from inventory_publication_intent
         where state in ('READY', 'PENDING', 'TRANSIENT_FAILED')
        """);
    registerCountGauge(
        "rwms.inventory.publication.in_flight.current",
        "Publication intents whose external hand-off is awaiting durable settlement.",
        "select count(*) from inventory_publication_intent where state = 'PENDING'");
    registerCountGauge(
        "rwms.inventory.publication.failed.current",
        "Publication intents in retryable or blocked failure states.",
        """
        select count(*)
          from inventory_publication_intent
         where state in ('TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerAgeGauge(
        "rwms.inventory.publication.oldest.unresolved.age.seconds",
        "Age in seconds of the oldest publication intent not settled or operator-closed.",
        """
        select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
          from inventory_publication_intent
         where state in ('READY', 'PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);

    registerCountGauge(
        "rwms.inventory.logistics.plan.unresolved.current",
        "Completed-inventory plan logistics effects awaiting successful owner settlement.",
        """
        select count(*)
          from inventory_plan_logistics_effect
         where state in ('READY', 'PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerCountGauge(
        "rwms.inventory.logistics.plan.failed.current",
        "Completed-inventory plan logistics effects in retryable or blocked failure states.",
        """
        select count(*)
          from inventory_plan_logistics_effect
         where state in ('TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerAgeGauge(
        "rwms.inventory.logistics.plan.oldest.unresolved.age.seconds",
        "Age of the oldest completed-inventory plan logistics effect awaiting settlement.",
        """
        select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
          from inventory_plan_logistics_effect
         where state in ('READY', 'PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);

    registerCountGauge(
        "rwms.inventory.furniture.reconciliation.unresolved.current",
        "Furniture reconciliation intents not yet successfully reconciled.",
        """
        select count(*)
          from inventory_furniture_reconciliation_intent
         where state in ('PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerCountGauge(
        "rwms.inventory.furniture.reconciliation.failed.current",
        "Furniture reconciliation intents in retryable or blocked failure states.",
        """
        select count(*)
          from inventory_furniture_reconciliation_intent
         where state in ('TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerAgeGauge(
        "rwms.inventory.furniture.reconciliation.oldest.unresolved.age.seconds",
        "Age in seconds of the oldest furniture reconciliation intent not successfully reconciled.",
        """
        select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
          from inventory_furniture_reconciliation_intent
         where state in ('PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);

    registerCountGauge(
        "rwms.inventory.furniture.loss.unresolved.current",
        "Inventory furniture shortages whose maintenance LOSS decision is not yet recorded.",
        """
        select count(*)
          from inventory_furniture_loss_intent
         where state in ('PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerCountGauge(
        "rwms.inventory.furniture.loss.failed.current",
        "Inventory furniture shortage deliveries in retryable or blocked failure states.",
        """
        select count(*)
          from inventory_furniture_loss_intent
         where state in ('TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerAgeGauge(
        "rwms.inventory.furniture.loss.oldest.unresolved.age.seconds",
        "Age of the oldest furniture shortage without a recorded maintenance decision.",
        """
        select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
          from inventory_furniture_loss_intent
         where state in ('PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);

    registerCountGauge(
        "rwms.inventory.cabin.writeoff.unresolved.current",
        "Missing-cabin write-off proposals awaiting maintenance settlement.",
        """
        select count(*)
          from inventory_cabin_write_off_intent
         where state in ('PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerCountGauge(
        "rwms.inventory.cabin.writeoff.failed.current",
        "Missing-cabin write-off proposals in retryable or blocked failure states.",
        """
        select count(*)
          from inventory_cabin_write_off_intent
         where state in ('TRANSIENT_FAILED', 'BLOCKED')
        """);
    registerAgeGauge(
        "rwms.inventory.cabin.writeoff.oldest.unresolved.age.seconds",
        "Age of the oldest missing-cabin write-off proposal awaiting settlement.",
        """
        select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
          from inventory_cabin_write_off_intent
         where state in ('PENDING', 'TRANSIENT_FAILED', 'BLOCKED')
        """);

    registerCountGauge(
        "rwms.inventory.dlt.backlog",
        "Sanitized inventory DLT records awaiting broker acknowledgement or manual recovery.",
        """
        select count(*)
          from sanitized_dead_letter
         where status in ('PENDING', 'IN_FLIGHT', 'FAILED')
        """);
  }

  private void registerCountGauge(String name, String description, String sql) {
    Gauge.builder(name, this, ignored -> count(name, sql)).description(description).register(registry);
  }

  private void registerAgeGauge(String name, String description, String sql) {
    Gauge.builder(name, this, ignored -> decimal(name, sql)).description(description).register(registry);
  }

  private double count(String metricName, String sql) {
    Number value = queryNumber(metricName, sql);
    return value == null ? Double.NaN : value.doubleValue();
  }

  private double decimal(String metricName, String sql) {
    Number value = queryNumber(metricName, sql);
    return value == null ? Double.NaN : value.doubleValue();
  }

  private Number queryNumber(String metricName, String sql) {
    try {
      Object value = entityManager.createNativeQuery(sql).getSingleResult();
      databaseQueryRecovered(metricName);
      if (value == null) {
        return 0L;
      }
      if (value instanceof Number number) {
        return number;
      }
      throw new IllegalStateException(
          "Inventory operational metric query returned a non-numeric value [metric="
              + metricName
              + "]");
    } catch (PersistenceException | DataAccessException exception) {
      databaseQueryFailed(metricName, exception);
      return null;
    }
  }

  private void databaseQueryFailed(String metricName, RuntimeException exception) {
    unavailableMetrics.add(metricName);
    long now = System.currentTimeMillis();
    Long previous = lastFailureLogMillis.get(metricName);
    if (previous == null || now - previous >= FAILURE_LOG_INTERVAL_MILLIS) {
      lastFailureLogMillis.put(metricName, now);
      log.warn(
          "Inventory operational metric database query failed [metric={}, failureType={}]",
          metricName,
          exception.getClass().getSimpleName(),
          exception);
    }
  }

  private void databaseQueryRecovered(String metricName) {
    if (unavailableMetrics.remove(metricName)) {
      log.info("Inventory operational metric database query recovered [metric={}]", metricName);
    }
  }
}
