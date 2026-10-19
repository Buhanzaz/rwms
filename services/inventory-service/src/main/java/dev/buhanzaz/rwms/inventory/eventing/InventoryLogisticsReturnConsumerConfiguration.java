package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.service.InventoryLogisticsReturnInboxProcessor;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/** Declares the inventory consumer binding for logistics return facts. */
@Configuration(proxyBeanMethods = false)
class InventoryLogisticsReturnConsumerConfiguration {
  @Bean
  Consumer<Message<byte[]>> inventoryLogisticsReturnFacts(
      InventoryLogisticsReturnInboxProcessor processor,
      InventoryLogisticsReturnRetryStore retries,
      InventoryDeadLetterStore deadLetters) {
    return message -> consume(
        InventoryLogisticsReturnInboxStore.Source.LOGISTICS_RETURN,
        message,
        processor,
        retries,
        deadLetters);
  }

  @Bean
  Consumer<Message<byte[]>> inventoryMaintenanceEstimateFacts(
      InventoryLogisticsReturnInboxProcessor processor,
      InventoryLogisticsReturnRetryStore retries,
      InventoryDeadLetterStore deadLetters) {
    return message -> consume(
        InventoryLogisticsReturnInboxStore.Source.MAINTENANCE_ESTIMATE,
        message,
        processor,
        retries,
        deadLetters);
  }

  private static void consume(
      InventoryLogisticsReturnInboxStore.Source source,
      Message<byte[]> message,
      InventoryLogisticsReturnInboxProcessor processor,
      InventoryLogisticsReturnRetryStore retries,
      InventoryDeadLetterStore deadLetters) {
    byte[] bytes = message.getPayload();
    byte[] recordKey = recordKey(message);
    if (recordKey == null) {
      deadLetters.record(
          "VALIDATION_REJECTED", InventoryEventChecksum.sha256(bytes), source.topic(), null);
      return;
    }
    try {
      processor.initial(source, bytes, recordKey);
    } catch (RuntimeException exception) {
      if (!retries.scheduleInitial(source, bytes, recordKey)) {
        deadLetters.record(
            "VALIDATION_REJECTED", InventoryEventChecksum.sha256(bytes), source.topic(), null);
      }
    }
  }

  private static byte[] recordKey(Message<byte[]> message) {
    Object value = message.getHeaders().get(KafkaHeaders.RECEIVED_KEY);
    if (value instanceof byte[] bytes) return bytes;
    if (value instanceof String text) return text.getBytes(StandardCharsets.UTF_8);
    return null;
  }
}
