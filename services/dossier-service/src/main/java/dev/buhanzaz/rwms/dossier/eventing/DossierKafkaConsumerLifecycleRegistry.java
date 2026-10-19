package dev.buhanzaz.rwms.dossier.eventing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binder.kafka.KafkaListenerContainerCustomizer;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Keeps only dossier projection consumers that may be restarted after database recovery. */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class DossierKafkaConsumerLifecycleRegistry implements KafkaListenerContainerCustomizer {
  private final Set<AbstractMessageListenerContainer<?, ?>> containers =
      ConcurrentHashMap.newKeySet();
  private final CommonErrorHandler failClosedHandler;

  public DossierKafkaConsumerLifecycleRegistry(
      @Qualifier("dossierFailClosedConsumerErrorHandler") CommonErrorHandler failClosedHandler) {
    this.failClosedHandler = failClosedHandler;
  }

  @Override
  public void configure(
      AbstractMessageListenerContainer<?, ?> container, String destinationName, String group) {
    if (DossierSourceTopics.CONSUMER_GROUP.equals(group)) {
      container.setCommonErrorHandler(failClosedHandler);
      containers.add(container);
    }
  }

  public boolean registered() {
    return !containers.isEmpty();
  }

  public boolean allRunning() {
    return registered()
        && containers.stream().allMatch(AbstractMessageListenerContainer::isRunning);
  }

  public void restartStopped() {
    containers.stream()
        .filter(container -> !container.isRunning())
        .forEach(AbstractMessageListenerContainer::start);
  }
}
