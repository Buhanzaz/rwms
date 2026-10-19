package dev.buhanzaz.rwms.maintenance.eventing.transport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restarts a fail-closed stopped consumer only after its own PostgreSQL database is healthy. */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class MaintenanceKafkaConsumerRecoveryMonitor {
  private final JdbcTemplate jdbc;
  private final MaintenanceKafkaConsumerLifecycleRegistry consumers;

  public MaintenanceKafkaConsumerRecoveryMonitor(
      JdbcTemplate jdbc, MaintenanceKafkaConsumerLifecycleRegistry consumers) {
    this.jdbc = jdbc;
    this.consumers = consumers;
  }

  @Scheduled(
      fixedDelayString = "${rwms.maintenance.eventing.consumer-recovery-delay:5s}",
      initialDelayString = "${rwms.maintenance.eventing.consumer-recovery-initial-delay:5s}")
  public void restartWhenDatabaseIsHealthy() {
    if (!consumers.registered() || consumers.allRunning()) {
      return;
    }
    try {
      Integer healthy = jdbc.queryForObject("select 1", Integer.class);
      if (Integer.valueOf(1).equals(healthy)) {
        consumers.restartStopped();
      }
    } catch (RuntimeException databaseUnavailable) {
      // Fail closed. The next bounded monitor tick retries only the health check.
    }
  }
}
