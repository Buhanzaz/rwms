package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.DependsOn;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

/** Creates and verifies every allowed dynamic task-board output binding before relay work starts. */
@Component
@DependsOn("taskBoardProductionSafetyValidator")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class TaskBoardKafkaOutputBindingInitializer implements SmartInitializingSingleton, DisposableBean {
  private final BindingService bindingService;
  private final ApplicationContext context;
  private final List<String> boundDestinations = new ArrayList<>();

  @Override
  public void afterSingletonsInstantiated() {
    for (var type : TaskBoardAggregateType.values()) type.outputDestinations().forEach(this::bind);
  }

  private void bind(String destination) {
    bindingService.bindProducer(context.getBean(destination, MessageChannel.class), destination);
    boundDestinations.add(destination);
  }

  @Override
  public void destroy() {
    for (int i = boundDestinations.size() - 1; i >= 0; i--)
      bindingService.unbindProducers(null, boundDestinations.get(i));
    boundDestinations.clear();
  }
}
