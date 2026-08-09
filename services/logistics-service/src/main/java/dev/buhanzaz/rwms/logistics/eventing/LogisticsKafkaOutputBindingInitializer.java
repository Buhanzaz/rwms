package dev.buhanzaz.rwms.logistics.eventing;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

/**
 * Binds the exact primary and separate sanitized-DLT logistics outputs without enabling topic
 * auto-creation.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class LogisticsKafkaOutputBindingInitializer
    implements SmartInitializingSingleton, DisposableBean {
  private final BindingService bindingService;
  private final ApplicationContext context;
  private final List<String> boundDestinations = new ArrayList<>();

  /** Binds canonical primary outputs first and the separate sanitized-DLT set second. */
  @Override
  public void afterSingletonsInstantiated() {
    for (String destination : LogisticsTransportTopics.PRIMARY_OUTPUTS) {
      bind(destination);
    }
    for (String destination : LogisticsTransportTopics.SANITIZED_DLT_OUTPUTS) {
      bind(destination);
    }
  }

  /** Resolves and binds the one channel named by an exact canonical destination. */
  private void bind(String destination) {
    bindingService.bindProducer(context.getBean(destination, MessageChannel.class), destination);
    boundDestinations.add(destination);
  }

  /** Unbinds only destinations created by this initializer, in reverse creation order. */
  @Override
  public void destroy() {
    for (int index = boundDestinations.size() - 1; index >= 0; index--) {
      bindingService.unbindProducers(null, boundDestinations.get(index));
    }
    boundDestinations.clear();
  }
}
