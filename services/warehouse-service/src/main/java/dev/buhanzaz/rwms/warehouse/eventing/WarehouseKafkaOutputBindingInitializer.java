package dev.buhanzaz.rwms.warehouse.eventing;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class WarehouseKafkaOutputBindingInitializer
    implements SmartInitializingSingleton, DisposableBean {
  private final BindingService bindingService;
  private final ApplicationContext context;
  private boolean bound;

  public WarehouseKafkaOutputBindingInitializer(BindingService bindingService, ApplicationContext context) {
    this.bindingService = bindingService;
    this.context = context;
  }

  @Override
  public void afterSingletonsInstantiated() {
    String destination = WarehouseAggregateType.WAREHOUSE.topic();
    bindingService.bindProducer(context.getBean(destination, MessageChannel.class), destination);
    bound = true;
  }

  @Override
  public void destroy() {
    if (bound) bindingService.unbindProducers(null, WarehouseAggregateType.WAREHOUSE.topic());
    bound = false;
  }
}
