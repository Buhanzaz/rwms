package dev.buhanzaz.rwms.logistics.eventing;

import java.util.ArrayList;
import java.util.List;
import dev.buhanzaz.rwms.logistics.eventing.inbound.LogisticsInboundTransportTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

/** Binds the three owned aggregate-family outputs without enabling topic auto-creation. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class LogisticsKafkaOutputBindingInitializer
    implements SmartInitializingSingleton, DisposableBean {
  private final BindingService bindingService;
  private final ApplicationContext context;
  private final List<String> boundDestinations = new ArrayList<>();

  @Override
  public void afterSingletonsInstantiated() {
    for (LogisticsAggregateType aggregateType : LogisticsAggregateType.values()) {
      bind(aggregateType.topic());
      bind(aggregateType.sanitizedDltTopic());
    }
    bind(LogisticsInboundTransportTopics.SANITIZED_DLT);
  }

  private void bind(String destination) {
    bindingService.bindProducer(context.getBean(destination, MessageChannel.class), destination);
    boundDestinations.add(destination);
  }

  @Override
  public void destroy() {
    for (int index = boundDestinations.size() - 1; index >= 0; index--) {
      bindingService.unbindProducers(null, boundDestinations.get(index));
    }
    boundDestinations.clear();
  }
}
