package dev.buhanzaz.rwms.assistant.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;

/** Proves Boot Kafka auto-configuration registers the stable booking listener without a broker. */
class AssistantKafkaListenerConfigurationTest {
  private final ApplicationContextRunner contexts =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
          .withUserConfiguration(BookingConsumerConfiguration.class)
          .withBean(KafkaAdmin.class, () -> mock(KafkaAdmin.class))
          .withPropertyValues(
              "rwms.assistant.kafka.enabled=true",
              "rwms.assistant.kafka.rental-inquiry-topic=rwms.logistics.rental-inquiry.events.v1",
              "spring.kafka.bootstrap-servers=127.0.0.1:9092",
              "spring.kafka.listener.auto-startup=false");

  @Test
  void registersTheBookingConsumerWithItsStableListenerId() {
    contexts.run(
        context -> {
          assertThat(context).hasNotFailed();
          KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
          assertThat(registry.getListenerContainer("assistantRentalInquiryBookedListener"))
              .isNotNull();
        });
  }

  /** Supplies consumer collaborators while Boot Kafka remains responsible for registration. */
  @Configuration(proxyBeanMethods = false)
  @Import(RentalInquiryBookedKafkaConsumer.class)
  static class BookingConsumerConfiguration {
    @Bean
    RentalInquiryBookedEventParser parser() {
      return mock(RentalInquiryBookedEventParser.class);
    }

    @Bean
    RentalInquiryArchiveService archive() {
      return mock(RentalInquiryArchiveService.class);
    }

    @Bean
    AssistantEventDeadLetterService deadLetters() {
      return mock(AssistantEventDeadLetterService.class);
    }

    @Bean
    AssistantRetryDelayer delayer() {
      return mock(AssistantRetryDelayer.class);
    }
  }
}
