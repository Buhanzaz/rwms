package dev.buhanzaz.rwms.inventory.eventing;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

@Configuration(proxyBeanMethods = false)
class InventoryMediaConsumerConfiguration {
  @Bean
  Consumer<Message<byte[]>> inventoryMediaFacts(
      InventoryMediaInboxProcessor processor,
      InventoryMediaRetryStore retries,
      InventoryDeadLetterStore deadLetters) {
    return message -> {
      byte[] bytes = message.getPayload();
      byte[] recordKey = recordKey(message);
      if (recordKey == null) {
        deadLetters.record(
            "VALIDATION_REJECTED",
            InventoryEventChecksum.sha256(bytes),
            "rwms.media.media.v1",
            null);
        return;
      }
      try {
        processor.initial(bytes, recordKey);
      } catch (RuntimeException exception) {
        if (!retries.scheduleInitial(bytes, recordKey)) {
          String hash =
              InventoryEventChecksum.sha256(bytes);
          deadLetters.record("VALIDATION_REJECTED", hash, "rwms.media.media.v1", null);
        }
      }
    };
  }

  private static byte[] recordKey(Message<byte[]> message) {
    Object value = message.getHeaders().get(KafkaHeaders.RECEIVED_KEY);
    if (value instanceof byte[] bytes) return bytes;
    if (value instanceof String text) return text.getBytes(StandardCharsets.UTF_8);
    return null;
  }
}
