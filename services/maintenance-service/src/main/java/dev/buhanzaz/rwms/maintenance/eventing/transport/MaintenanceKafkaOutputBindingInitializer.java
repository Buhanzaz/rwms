package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class MaintenanceKafkaOutputBindingInitializer
    implements SmartInitializingSingleton, DisposableBean {
  private final BindingService bindings;
  private final ApplicationContext context;
  private final Set<String> destinations = new LinkedHashSet<>();

  public MaintenanceKafkaOutputBindingInitializer(
      BindingService bindings, ApplicationContext context) {
    this.bindings = bindings;
    this.context = context;
  }

  @Override
  public void afterSingletonsInstantiated() {
    MaintenanceTransportTopics.OUTPUTS.forEach(
        topic -> {
          bindings.bindProducer(context.getBean(topic, MessageChannel.class), topic);
          destinations.add(topic);
        });
  }

  @Override
  public void destroy() {
    destinations.forEach(topic -> bindings.unbindProducers(null, topic));
    destinations.clear();
  }
}
