package dev.buhanzaz.rwms.taskboard.eventing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;

class TaskBoardKafkaOutputBindingInitializerTest {
  @Test
  void bindsCanonicalOutputChannelsBeforePublicationAndUnbindsOnShutdown() {
    BindingService bindingService = mock(BindingService.class);
    ApplicationContext applicationContext = mock(ApplicationContext.class);
    for (TaskBoardAggregateType aggregateType : TaskBoardAggregateType.values()) {
      for (String destination : aggregateType.outputDestinations()) {
        MessageChannel channel = mock(MessageChannel.class);
        when(applicationContext.getBean(destination, MessageChannel.class)).thenReturn(channel);
      }
    }
    var initializer =
        new TaskBoardKafkaOutputBindingInitializer(bindingService, applicationContext);

    initializer.afterSingletonsInstantiated();
    initializer.destroy();

    for (TaskBoardAggregateType aggregateType : TaskBoardAggregateType.values()) {
      for (String destination : aggregateType.outputDestinations()) {
        MessageChannel channel = applicationContext.getBean(destination, MessageChannel.class);
        verify(bindingService).bindProducer(channel, destination);
        verify(bindingService).unbindProducers(null, destination);
      }
    }
  }
}
