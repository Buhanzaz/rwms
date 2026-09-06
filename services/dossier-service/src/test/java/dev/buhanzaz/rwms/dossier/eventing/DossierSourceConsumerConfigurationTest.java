package dev.buhanzaz.rwms.dossier.eventing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.buhanzaz.rwms.dossier.service.DossierInboxProcessor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.ObjectMapper;

class DossierSourceConsumerConfigurationTest {
  @Test
  void consumesTheRealProducerByteArrayKeyWithoutBinderRetryMultiplication() {
    UUID cabinId = UUID.fromString("20000000-0000-0000-0000-000000000001");
    byte[] body = asset(cabinId).getBytes(StandardCharsets.UTF_8);
    var message =
        MessageBuilder.withPayload(body)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, "rwms.asset.rental-item.v1")
            .setHeader(KafkaHeaders.RECEIVED_PARTITION, 2)
            .setHeader(KafkaHeaders.OFFSET, 19L)
            .setHeader(KafkaHeaders.RECEIVED_KEY, cabinId.toString().getBytes(StandardCharsets.UTF_8))
            .build();
    DossierInboxProcessor inbox = mock(DossierInboxProcessor.class);
    DossierDeadLetterService deadLetters = mock(DossierDeadLetterService.class);
    DossierRetryDelayer delayer = mock(DossierRetryDelayer.class);

    new DossierSourceConsumerConfiguration()
        .consume(
            message,
            new DossierEnvelopeValidator(new ObjectMapper(), new DossierProducerSchemaValidator()),
            inbox,
            deadLetters,
            delayer);

    ArgumentCaptor<DossierValidatedEvent> event =
        ArgumentCaptor.forClass(DossierValidatedEvent.class);
    verify(inbox).process(event.capture());
    org.assertj.core.api.Assertions.assertThat(event.getValue().aggregateId()).isEqualTo(cabinId);
    verifyNoInteractions(deadLetters, delayer);
  }

  @Test
  void transferCancellationIsRoutedThroughRealValidationToTheInboxWithoutDlt() {
    UUID transferId = UUID.randomUUID();
    var message =
        MessageBuilder.withPayload(
                transferCancellation(transferId).getBytes(StandardCharsets.UTF_8))
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, "rwms.logistics.transfer.v1")
            .setHeader(KafkaHeaders.RECEIVED_PARTITION, 0)
            .setHeader(KafkaHeaders.OFFSET, 20L)
            .setHeader(
                KafkaHeaders.RECEIVED_KEY, transferId.toString().getBytes(StandardCharsets.UTF_8))
            .build();
    DossierInboxProcessor inbox = mock(DossierInboxProcessor.class);
    DossierDeadLetterService deadLetters = mock(DossierDeadLetterService.class);
    DossierRetryDelayer delayer = mock(DossierRetryDelayer.class);

    new DossierSourceConsumerConfiguration()
        .consume(
            message,
            new DossierEnvelopeValidator(new ObjectMapper(), new DossierProducerSchemaValidator()),
            inbox,
            deadLetters,
            delayer);

    ArgumentCaptor<DossierValidatedEvent> event =
        ArgumentCaptor.forClass(DossierValidatedEvent.class);
    verify(inbox).process(event.capture());
    org.assertj.core.api.Assertions.assertThat(event.getValue().eventType())
        .isEqualTo("logistics.transfer.cancellation-started.v1");
    org.assertj.core.api.Assertions.assertThat(event.getValue().aggregateId())
        .isEqualTo(transferId);
    org.assertj.core.api.Assertions.assertThat(event.getValue().subjectCapable()).isFalse();
    verifyNoInteractions(deadLetters, delayer);
  }

  @Test
  void malformedTransferCancellationGoesToValidationDltWithoutInboxOrRetry() {
    UUID transferId = UUID.randomUUID();
    byte[] body =
        transferCancellation(transferId)
            .replace("\"state\":\"CANCELLING\"", "\"state\":\"CANCELLED\"")
            .getBytes(StandardCharsets.UTF_8);
    String topic = "rwms.logistics.transfer.v1";
    String key = transferId.toString();
    var message =
        MessageBuilder.withPayload(body)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, topic)
            .setHeader(KafkaHeaders.RECEIVED_PARTITION, 0)
            .setHeader(KafkaHeaders.OFFSET, 20L)
            .setHeader(KafkaHeaders.RECEIVED_KEY, key)
            .build();
    DossierInboxProcessor inbox = mock(DossierInboxProcessor.class);
    DossierDeadLetterService deadLetters = mock(DossierDeadLetterService.class);
    DossierRetryDelayer delayer = mock(DossierRetryDelayer.class);

    new DossierSourceConsumerConfiguration()
        .consume(
            message,
            new DossierEnvelopeValidator(new ObjectMapper(), new DossierProducerSchemaValidator()),
            inbox,
            deadLetters,
            delayer);

    verify(deadLetters).validationFailure(topic, 0, 20L, key, body, "SOURCE_PAYLOAD_REJECTED");
    verifyNoInteractions(inbox, delayer);
  }

  @Test
  void preservesKafkaOffsetsAboveTheIntegerRange() {
    UUID cabinId = UUID.fromString("20000000-0000-0000-0000-000000000001");
    long largeOffset = (long) Integer.MAX_VALUE + 42L;
    var message =
        MessageBuilder.withPayload(asset(cabinId).getBytes(StandardCharsets.UTF_8))
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, "rwms.asset.rental-item.v1")
            .setHeader(KafkaHeaders.RECEIVED_PARTITION, 2)
            .setHeader(KafkaHeaders.OFFSET, largeOffset)
            .setHeader(KafkaHeaders.RECEIVED_KEY, cabinId.toString())
            .build();
    DossierInboxProcessor inbox = mock(DossierInboxProcessor.class);

    new DossierSourceConsumerConfiguration()
        .consume(
            message,
            new DossierEnvelopeValidator(new ObjectMapper(), new DossierProducerSchemaValidator()),
            inbox,
            mock(DossierDeadLetterService.class),
            mock(DossierRetryDelayer.class));

    ArgumentCaptor<DossierValidatedEvent> event =
        ArgumentCaptor.forClass(DossierValidatedEvent.class);
    verify(inbox).process(event.capture());
    org.assertj.core.api.Assertions.assertThat(event.getValue().offset()).isEqualTo(largeOffset);
  }

  @Test
  void finalJpaFailureEscapesSoKafkaCannotCommitTheSourceOffset() {
    UUID cabinId = UUID.fromString("20000000-0000-0000-0000-000000000001");
    byte[] body = asset(cabinId).getBytes(StandardCharsets.UTF_8);
    var message =
        MessageBuilder.withPayload(body)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, "rwms.asset.rental-item.v1")
            .setHeader(KafkaHeaders.RECEIVED_PARTITION, 2)
            .setHeader(KafkaHeaders.OFFSET, 19L)
            .setHeader(KafkaHeaders.RECEIVED_KEY, cabinId.toString().getBytes(StandardCharsets.UTF_8))
            .build();
    DossierInboxProcessor inbox = mock(DossierInboxProcessor.class);
    DossierDeadLetterService deadLetters = mock(DossierDeadLetterService.class);
    DossierRetryDelayer delayer = mock(DossierRetryDelayer.class);
    doThrow(new IllegalStateException("database unavailable")).when(inbox).process(org.mockito.ArgumentMatchers.any());
    doThrow(new IllegalStateException("database unavailable"))
        .when(inbox)
        .deadLetterAfterRetries(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new DossierSourceConsumerConfiguration()
                    .consume(
                        message,
                        new DossierEnvelopeValidator(
                            new ObjectMapper(), new DossierProducerSchemaValidator()),
                        inbox,
                        deadLetters,
                        delayer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database unavailable");

    verify(inbox, times(4)).process(org.mockito.ArgumentMatchers.any());
    verify(inbox)
        .deadLetterAfterRetries(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private static String transferCancellation(UUID transferId) {
    return """
        {"envelopeVersion":2,"eventId":"10000000-0000-0000-0000-000000000016","eventType":"logistics.transfer.cancellation-started.v1","eventVersion":1,"occurredAt":"2026-09-06T10:00:00Z","recordedAt":"2026-09-06T10:00:00Z","producer":"logistics-service","aggregateType":"TRANSFER","aggregateId":"%s","aggregateVersion":1,"correlation":{"correlationId":"50000000-0000-0000-0000-000000000001","causationId":null},"actorRef":null,"payload":{"documentId":"%s","documentType":"TRANSFER","state":"CANCELLING","warehouseId":"30000000-0000-0000-0000-000000000001","destinationWarehouseId":"30000000-0000-0000-0000-000000000002","lineCount":1,"resultCode":null}}
        """
        .formatted(transferId, transferId);
  }

  private static String asset(UUID cabinId) {
    return """
        {"envelopeVersion":2,"eventId":"10000000-0000-0000-0000-000000000001","eventType":"asset.rental-item.created.v1","eventVersion":1,"occurredAt":null,"recordedAt":"2026-07-18T00:00:00Z","producer":"asset-service","aggregateType":"RENTAL_ITEM","aggregateId":"%s","aggregateVersion":0,"correlation":{"correlationId":"50000000-0000-0000-0000-000000000001","causationId":null},"actorRef":null,"payload":{"rentalItemId":"%s","warehouseId":"30000000-0000-0000-0000-000000000001","status":"AVAILABLE","numberSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}
        """
        .formatted(cabinId, cabinId);
  }
}
