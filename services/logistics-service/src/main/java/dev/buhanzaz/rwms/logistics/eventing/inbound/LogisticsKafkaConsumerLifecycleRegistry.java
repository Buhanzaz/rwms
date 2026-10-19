package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binder.kafka.KafkaListenerContainerCustomizer;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.stereotype.Component;

/** Keeps only this service's consumer containers for fail-closed database recovery. */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class LogisticsKafkaConsumerLifecycleRegistry implements KafkaListenerContainerCustomizer {
  private final Set<AbstractMessageListenerContainer<?, ?>> containers = ConcurrentHashMap.newKeySet();

  @Override
  public void configure(AbstractMessageListenerContainer<?, ?> container, String destinationName, String group) {
    if (LogisticsInboundTransportTopics.CONSUMER_GROUP.equals(group)) {
      containers.add(container);
    }
  }

  public boolean registered() {
    return !containers.isEmpty();
  }

  public boolean allRunning() {
    return registered() && containers.stream().allMatch(AbstractMessageListenerContainer::isRunning);
  }

  public void restartStopped() {
    containers.stream()
        .filter(container -> !container.isRunning())
        .forEach(AbstractMessageListenerContainer::start);
  }
}
