package dev.buhanzaz.rwms.auth.eventing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.MessageChannel;

class AuthKafkaOutputBindingInitializerTest {

    @Test
    void bindsCanonicalOutputChannelsBeforePublicationAndUnbindsOnShutdown() {
        BindingService bindingService = mock(BindingService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        for (AuthAggregateType aggregateType : AuthAggregateType.values()) {
            for (String destination : aggregateType.outputDestinations()) {
                MessageChannel channel = mock(MessageChannel.class);
                when(applicationContext.getBean(destination, MessageChannel.class))
                        .thenReturn(channel);
            }
        }
        var initializer = new AuthKafkaOutputBindingInitializer(bindingService, applicationContext);

        initializer.afterSingletonsInstantiated();
        initializer.destroy();

        for (AuthAggregateType aggregateType : AuthAggregateType.values()) {
            for (String destination : aggregateType.outputDestinations()) {
                MessageChannel channel = applicationContext.getBean(destination, MessageChannel.class);
                verify(bindingService).bindProducer(channel, destination);
                verify(bindingService).unbindProducers(null, destination);
            }
        }
    }
}
