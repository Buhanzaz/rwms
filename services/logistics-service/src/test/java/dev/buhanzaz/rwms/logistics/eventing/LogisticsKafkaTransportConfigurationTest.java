package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;

/** Verifies the exact logistics primary/DLT topic authority, channels, and binding order. */
class LogisticsKafkaTransportConfigurationTest {

  @Test
  void topicAuthorityMatchesDomainMappingsAndKeepsDltBindingsSeparate() {
    assertThat(LogisticsTransportTopics.PRIMARY_OUTPUTS)
        .containsExactly(
            LogisticsAggregateType.RETURN.topic(),
            LogisticsAggregateType.SHIPMENT.topic(),
            LogisticsAggregateType.TRANSFER.topic(),
            "rwms.logistics.rental-inquiry.events.v1");
    assertThat(LogisticsTransportTopics.SANITIZED_DLT_OUTPUTS)
        .containsExactly(
            LogisticsAggregateType.RETURN.sanitizedDltTopic(),
            LogisticsAggregateType.SHIPMENT.sanitizedDltTopic(),
            LogisticsAggregateType.TRANSFER.sanitizedDltTopic(),
            "rwms.logistics.inbound.v1.dlt")
        .doesNotContainAnyElementsOf(LogisticsTransportTopics.PRIMARY_OUTPUTS);
  }

  @Test
  void outputChannelsExistOnlyForTheExactEnabledBindingSet() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withUserConfiguration(LogisticsKafkaOutputChannelsConfiguration.class);

    runner.run(context -> assertThat(context.getBeansOfType(MessageChannel.class)).isEmpty());
    runner
        .withPropertyValues("rwms.platform.kafka.enabled=true")
        .run(
            context ->
                assertThat(context.getBeansOfType(MessageChannel.class).keySet())
                    .containsExactlyInAnyOrderElementsOf(allOutputs()));
  }

  @Test
  void bindingInitializerBindsPrimaryThenDltAndUnbindsInReverseOrder() {
    BindingService bindingService = mock(BindingService.class);
    ApplicationContext context = mock(ApplicationContext.class);
    Map<String, MessageChannel> channels = new LinkedHashMap<>();
    for (String destination : allOutputs()) {
      MessageChannel channel = mock(MessageChannel.class);
      channels.put(destination, channel);
      when(context.getBean(destination, MessageChannel.class)).thenReturn(channel);
    }
    LogisticsKafkaOutputBindingInitializer initializer =
        new LogisticsKafkaOutputBindingInitializer(bindingService, context);

    initializer.afterSingletonsInstantiated();
    InOrder bindingOrder = inOrder(bindingService);
    for (Map.Entry<String, MessageChannel> output : channels.entrySet()) {
      bindingOrder.verify(bindingService).bindProducer(output.getValue(), output.getKey());
    }

    clearInvocations(bindingService);
    initializer.destroy();
    InOrder unbindingOrder = inOrder(bindingService);
    for (int index = allOutputs().size() - 1; index >= 0; index--) {
      unbindingOrder.verify(bindingService).unbindProducers(null, allOutputs().get(index));
    }
  }

  private static java.util.List<String> allOutputs() {
    return java.util.stream.Stream.concat(
            LogisticsTransportTopics.PRIMARY_OUTPUTS.stream(),
            LogisticsTransportTopics.SANITIZED_DLT_OUTPUTS.stream())
        .toList();
  }
}
