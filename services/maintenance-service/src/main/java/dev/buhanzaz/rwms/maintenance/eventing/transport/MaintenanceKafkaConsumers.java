package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class MaintenanceKafkaConsumers {
  private static final long[] BACKOFF_MILLIS = {0, 1_000, 2_000, 4_000};

  @Bean
  CommonErrorHandler maintenanceFailClosedConsumerErrorHandler() {
    return new CommonContainerStoppingErrorHandler();
  }

  @Bean
  Consumer<Message<byte[]>> maintenanceInbound(
      MaintenanceInboundEnvelopeValidator validator,
      MaintenanceInboundStagingStore staging,
      MaintenanceInboxProcessor inbox,
      MaintenanceSanitizedDltPublisher deadLetters,
      MaintenanceRetryDelayer delayer) {
    return message -> consume(message, validator, staging, inbox, deadLetters, delayer);
  }

  void consume(
      Message<byte[]> message,
      MaintenanceInboundEnvelopeValidator validator,
      MaintenanceInboundStagingStore staging,
      MaintenanceInboxProcessor inbox,
      MaintenanceSanitizedDltPublisher deadLetters,
      MaintenanceRetryDelayer delayer) {
    byte[] raw = message.getPayload();
    Object header = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
    String sourceTopic = header instanceof String value ? value : null;
    if (sourceTopic == null) {
      deadLetters.publish(raw, "VALIDATION_REJECTED", null, null);
      return;
    }

    MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event;
    try {
      event = validator.validate(sourceTopic, message.getHeaders().get(KafkaHeaders.RECEIVED_KEY), raw);
    } catch (MaintenanceInboundValidationException exception) {
      deadLetters.publish(raw, "VALIDATION_REJECTED", sourceTopic, null);
      return;
    }

    for (int attempt = 0; attempt < BACKOFF_MILLIS.length; attempt++) {
      if (attempt > 0) {
        delayer.delay(BACKOFF_MILLIS[attempt]);
      }
      try {
        staging.stage(event);
        MaintenanceInboxProcessor.Outcome outcome = inbox.process(event);
        if (outcome == MaintenanceInboxProcessor.Outcome.VERSION_GAP
            || outcome == MaintenanceInboxProcessor.Outcome.BLOCKED) {
          staging.markDlt(event.eventId());
          deadLetters.publishHash(
              event.rawMessageSha256(),
              "VERSION_GAP",
              event.sourceTopic(),
              event.eventId());
        }
        return;
      } catch (MaintenanceEventIdentityConflictException exception) {
        staging.markDlt(event.eventId());
        deadLetters.publishHash(
            event.rawMessageSha256(), "EVENT_ID_CONFLICT", event.sourceTopic(), null);
        return;
      } catch (MaintenanceInboundValidationException exception) {
        staging.markDlt(event.eventId());
        deadLetters.publishHash(
            event.rawMessageSha256(), "VALIDATION_REJECTED", event.sourceTopic(), null);
        return;
      } catch (RuntimeException exception) {
        if (attempt == BACKOFF_MILLIS.length - 1) {
          staging.markDlt(event.eventId());
          deadLetters.publishHash(
              event.rawMessageSha256(),
              "PROCESSING_FAILED",
              event.sourceTopic(),
              event.eventId());
          return;
        }
      }
    }
  }
}
