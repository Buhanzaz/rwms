package dev.buhanzaz.rwms.asset.eventing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;

class AssetKafkaConsumersTest {
  private final byte[] payload = "{\"comment\":\"private@example.test\"}".getBytes(StandardCharsets.UTF_8);

  @Test
  void validationFailureHandsOffOnceToTheSanitizedDlt() {
    AssetInboxProcessor processor = mock(AssetInboxProcessor.class);
    AssetSanitizedDltPublisher dlt = mock(AssetSanitizedDltPublisher.class);
    doThrow(new AssetEventValidationException("invalid"))
        .when(processor)
        .process(any(), eq(AssetAggregateType.RENTAL_ITEM));

    var consumer = new AssetKafkaConsumers().assetRentalItemInbound(processor, dlt);

    assertThatCode(() -> consumer.accept(MessageBuilder.withPayload(payload).build()))
        .doesNotThrowAnyException();
    verify(processor).process(payload, AssetAggregateType.RENTAL_ITEM);
    verify(dlt).publish(payload, "VALIDATION_REJECTED");
  }

  @Test
  void transientFailureUsesTheFullOnePlusThreeAttemptBudgetBeforeRecovery() {
    AssetInboxProcessor processor = mock(AssetInboxProcessor.class);
    AssetSanitizedDltPublisher dlt = mock(AssetSanitizedDltPublisher.class);
    when(processor.process(any(), eq(AssetAggregateType.RENTAL_ITEM)))
        .thenThrow(new IllegalStateException("temporary"))
        .thenThrow(new IllegalStateException("temporary"))
        .thenThrow(new IllegalStateException("temporary"))
        .thenReturn(AssetInboxProcessor.Outcome.PROCESSED);

    new AssetKafkaConsumers().assetRentalItemInbound(processor, dlt)
        .accept(MessageBuilder.withPayload(payload).build());

    verify(processor, times(4)).process(payload, AssetAggregateType.RENTAL_ITEM);
    verify(dlt, never()).publish(payload, "PROCESSING_FAILED");
  }

  @Test
  void exhaustedTransientFailureHandsOffOnceToTheSanitizedDlt() {
    AssetInboxProcessor processor = mock(AssetInboxProcessor.class);
    AssetSanitizedDltPublisher dlt = mock(AssetSanitizedDltPublisher.class);
    doThrow(new IllegalStateException("temporary"))
        .when(processor)
        .process(any(), eq(AssetAggregateType.RENTAL_ITEM));

    new AssetKafkaConsumers().assetRentalItemInbound(processor, dlt)
        .accept(MessageBuilder.withPayload(payload).build());

    verify(processor, times(4)).process(payload, AssetAggregateType.RENTAL_ITEM);
    verify(dlt).publish(payload, "PROCESSING_FAILED");
  }

  @Test
  void realtimeBindingUsesTheReceivedTopicAndNeverOwnsDlt() {
    AssetRealtimeInvalidationBroadcaster broadcaster =
        mock(AssetRealtimeInvalidationBroadcaster.class);
    var message =
        MessageBuilder.withPayload(payload)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, AssetAggregateType.RENTAL_ITEM.topic())
            .build();

    new AssetKafkaConsumers().assetRealtimeInvalidation(broadcaster).accept(message);

    verify(broadcaster).broadcast(payload, AssetAggregateType.RENTAL_ITEM);
  }
}
