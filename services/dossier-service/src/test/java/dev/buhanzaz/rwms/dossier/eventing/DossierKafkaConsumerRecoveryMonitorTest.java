package dev.buhanzaz.rwms.dossier.eventing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;

class DossierKafkaConsumerRecoveryMonitorTest {
  @Test
  void bindsTheFailClosedHandlerOnlyToTheDossierConsumerGroup() {
    CommonErrorHandler handler = mock(CommonErrorHandler.class);
    DossierKafkaConsumerLifecycleRegistry consumers =
        new DossierKafkaConsumerLifecycleRegistry(handler);
    @SuppressWarnings("unchecked")
    AbstractMessageListenerContainer<Object, Object> dossier =
        mock(AbstractMessageListenerContainer.class);
    @SuppressWarnings("unchecked")
    AbstractMessageListenerContainer<Object, Object> foreign =
        mock(AbstractMessageListenerContainer.class);

    consumers.configure(dossier, "dossier-input", DossierSourceTopics.CONSUMER_GROUP);
    consumers.configure(foreign, "other-input", "other-group");

    verify(dossier).setCommonErrorHandler(handler);
    verify(foreign, never()).setCommonErrorHandler(handler);
  }

  @Test
  void restartsStoppedProjectionConsumersOnlyAfterJpaProbeSucceeds() {
    DossierDatabaseHealthProbe database = mock(DossierDatabaseHealthProbe.class);
    DossierKafkaConsumerLifecycleRegistry consumers =
        mock(DossierKafkaConsumerLifecycleRegistry.class);
    when(consumers.registered()).thenReturn(true);
    when(consumers.allRunning()).thenReturn(false);
    when(database.healthy()).thenReturn(true);

    new DossierKafkaConsumerRecoveryMonitor(database, consumers)
        .restartWhenDatabaseIsHealthy();

    verify(consumers).restartStopped();
  }

  @Test
  void leavesConsumersStoppedWhileJpaProbeFails() {
    DossierDatabaseHealthProbe database = mock(DossierDatabaseHealthProbe.class);
    DossierKafkaConsumerLifecycleRegistry consumers =
        mock(DossierKafkaConsumerLifecycleRegistry.class);
    when(consumers.registered()).thenReturn(true);
    when(consumers.allRunning()).thenReturn(false);
    when(database.healthy()).thenThrow(new IllegalStateException("database unavailable"));

    new DossierKafkaConsumerRecoveryMonitor(database, consumers)
        .restartWhenDatabaseIsHealthy();

    verify(consumers, never()).restartStopped();
  }
}
