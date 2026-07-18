package dev.buhanzaz.rwms.dossier.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restarts fail-closed source consumers only after a successful local JPA health probe. */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class DossierKafkaConsumerRecoveryMonitor {
  private final DossierDatabaseHealthProbe database;
  private final DossierKafkaConsumerLifecycleRegistry consumers;

  public DossierKafkaConsumerRecoveryMonitor(
      DossierDatabaseHealthProbe database, DossierKafkaConsumerLifecycleRegistry consumers) {
    this.database = database;
    this.consumers = consumers;
  }

  @Scheduled(
      fixedDelayString = "${rwms.dossier.eventing.consumer-recovery-delay:5s}",
      initialDelayString = "${rwms.dossier.eventing.consumer-recovery-initial-delay:5s}")
  public void restartWhenDatabaseIsHealthy() {
    if (!consumers.registered() || consumers.allRunning()) return;
    try {
      if (database.healthy()) consumers.restartStopped();
    } catch (RuntimeException databaseUnavailable) {
      // Fail closed. A later bounded tick repeats only the read-only JPA probe.
    }
  }
}
