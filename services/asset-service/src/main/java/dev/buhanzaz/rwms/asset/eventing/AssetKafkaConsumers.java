package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
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
public class AssetKafkaConsumers {
  private static final long[] BACKOFF_MILLIS = {0, 1_000, 2_000, 4_000};

  @Bean
  CommonErrorHandler assetFailClosedConsumerErrorHandler() {
    return new CommonContainerStoppingErrorHandler();
  }

  @Bean
  Consumer<Message<byte[]>> assetInbound(AssetInboxProcessor processor, AssetSanitizedDltPublisher dlt) {
    return message -> {
      Object topic = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
      if (!(topic instanceof String sourceTopic)) {
        dlt.publish(message.getPayload(), "VALIDATION_REJECTED");
        return;
      }
      try {
        processWithBoundedRetry(message.getPayload(), AssetAggregateType.requireTopic(sourceTopic), processor, dlt);
      } catch (IllegalArgumentException exception) {
        dlt.publish(message.getPayload(), "VALIDATION_REJECTED");
      }
    };
  }

  @Bean
  Consumer<Message<byte[]>> assetRealtimeInvalidation(
      AssetRealtimeInvalidationBroadcaster broadcaster) {
    return message -> {
      Object topic = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
      if (!(topic instanceof String sourceTopic)) return;
      try {
        broadcaster.broadcast(
            message.getPayload(), AssetAggregateType.requireTopic(sourceTopic));
      } catch (AssetEventValidationException | IllegalArgumentException ignored) {
        // The durable inbox binding owns validation/DLT. Realtime delivery is only a cache hint
        // and must never stop the replica-specific broadcast consumer.
      }
    };
  }

  // Kept package-visible for focused retry tests. Runtime traffic always enters
  // through the multiplexed assetInbound function above.
  Consumer<Message<byte[]>> assetRentalItemInbound(AssetInboxProcessor processor, AssetSanitizedDltPublisher dlt) {
    return consumer(AssetAggregateType.RENTAL_ITEM, processor, dlt);
  }

  private Consumer<Message<byte[]>> consumer(
      AssetAggregateType type, AssetInboxProcessor processor, AssetSanitizedDltPublisher dlt) {
    return message -> processWithBoundedRetry(message.getPayload(), type, processor, dlt);
  }

  private void processWithBoundedRetry(
      byte[] payload, AssetAggregateType type, AssetInboxProcessor processor, AssetSanitizedDltPublisher dlt) {
    for (int attempt = 0; attempt < BACKOFF_MILLIS.length; attempt++) {
      if (attempt > 0) pause(BACKOFF_MILLIS[attempt]);
      try {
        processor.process(payload, type);
        return;
      } catch (AssetEventValidationException exception) {
        dlt.publish(payload, "VALIDATION_REJECTED");
        return;
      } catch (RuntimeException exception) {
        if (attempt == BACKOFF_MILLIS.length - 1) {
          dlt.publish(payload, "PROCESSING_FAILED");
          return;
        }
      }
    }
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Asset Kafka retry was interrupted", exception);
    }
  }
}
