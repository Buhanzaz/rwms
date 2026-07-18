package dev.buhanzaz.rwms.logistics.eventing.inbound;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restarts a stopped source-fact consumer only after local PostgreSQL becomes healthy. */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class LogisticsKafkaConsumerRecoveryMonitor {
  private final JdbcTemplate jdbc;
  private final LogisticsKafkaConsumerLifecycleRegistry consumers;

  public LogisticsKafkaConsumerRecoveryMonitor(
      JdbcTemplate jdbc, LogisticsKafkaConsumerLifecycleRegistry consumers) {
    this.jdbc = jdbc;
    this.consumers = consumers;
  }

  @Scheduled(
      fixedDelayString = "${rwms.logistics.eventing.consumer-recovery-delay:5s}",
      initialDelayString = "${rwms.logistics.eventing.consumer-recovery-initial-delay:5s}")
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
      // Fail closed. A later bounded tick repeats only the health probe.
    }
  }
}
