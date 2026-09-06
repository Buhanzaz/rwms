package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsEventPayloadMapper;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exercises transfer cancellation publication through the production validator registration. */
class LogisticsTransferCancellationPublisherTest {
  private static final String TOPIC = "rwms.logistics.transfer.v1";
  private final ObjectMapper mapper = new ObjectMapper();
  private final StreamBridge broker = mock(StreamBridge.class);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(LogisticsPayloadSafetyValidatorsConfiguration.class);

  @Test
  void registeredValidatorLetsTheOutboxRelayPublishTheCurrentProducerPayload() {
    DomainEventEnvelopeV2<LogisticsEventPayload> envelope = cancellation();
    String body = mapper.writeValueAsString(envelope);
    LogisticsOutboxStore.Claim claim =
        new LogisticsOutboxStore.Claim(
            envelope.eventId(),
            envelope.aggregateType(),
            envelope.aggregateId(),
            envelope.aggregateVersion(),
            envelope.eventType(),
            TOPIC,
            body,
            LogisticsEventStore.sha256(body.getBytes(StandardCharsets.UTF_8)),
            0,
            UUID.randomUUID());
    LogisticsOutboxStore store = mock(LogisticsOutboxStore.class);
    LogisticsSanitizedDltPublisher deadLetters = mock(LogisticsSanitizedDltPublisher.class);
    LogisticsOutboxProperties properties = new LogisticsOutboxProperties();
    properties.setInstanceId("transfer-cancellation-test");
    properties.setLeaseDuration(Duration.ofSeconds(30));
    when(store.claim(properties.instanceId(), properties.leaseDuration()))
        .thenReturn(Optional.of(claim));
    when(store.hasValidEnvelope(claim)).thenReturn(true);
    when(store.published(claim.eventId(), claim.leaseToken())).thenReturn(true);
    when(broker.send(eq(TOPIC), any(Message.class))).thenReturn(true);

    contextRunner.run(
        context -> {
          var relay =
              new LogisticsKafkaOutboxRelay(store, properties, publisher(context), deadLetters);

          assertThat(relay.relayOne()).isTrue();

          @SuppressWarnings("unchecked")
          ArgumentCaptor<Message<byte[]>> sent = ArgumentCaptor.forClass(Message.class);
          verify(broker).send(eq(TOPIC), sent.capture());
          assertThat(sent.getValue().getPayload()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
          assertThat((byte[]) sent.getValue().getHeaders().get(KafkaHeaders.KEY))
              .isEqualTo(envelope.aggregateId().getBytes(StandardCharsets.UTF_8));
          assertThat(mapper.readTree(sent.getValue().getPayload()).at("/payload/state").asText())
              .isEqualTo("CANCELLING");
          verify(store).published(claim.eventId(), claim.leaseToken());
          verifyNoInteractions(deadLetters);
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingDocumentId",
        "invalidDocumentId",
        "wrongDocumentType",
        "wrongState",
        "missingDestination",
        "emptyLineCount",
        "extraField"
      })
  void registeredValidatorStillRejectsMalformedOrUnsafeCancellationPayloads(String mutation) {
    ObjectNode root = (ObjectNode) mapper.valueToTree(cancellation());
    ObjectNode payload = (ObjectNode) root.get("payload");
    switch (mutation) {
      case "missingDocumentId" -> payload.remove("documentId");
      case "invalidDocumentId" -> payload.put("documentId", "not-a-uuid");
      case "wrongDocumentType" -> payload.put("documentType", "SHIPMENT");
      case "wrongState" -> payload.put("state", "CANCELLED");
      case "missingDestination" -> payload.putNull("destinationWarehouseId");
      case "emptyLineCount" -> payload.put("lineCount", 0);
      case "extraField" -> payload.put("unexpectedDetail", "extra");
      default -> throw new IllegalArgumentException(mutation);
    }

    contextRunner.run(
        context -> {
          assertThatThrownBy(
                  () ->
                      publisher(context).publishSerializedV2(TOPIC, mapper.writeValueAsBytes(root)))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessage("event payload failed safety validation");
          verifyNoInteractions(broker);
        });
  }

  @Test
  void cancellationCannotBePublishedToAnotherAllowedAggregateTopic() {
    contextRunner.run(
        context -> {
          assertThatThrownBy(
                  () -> publisher(context).publish("rwms.logistics.shipment.v1", cancellation()))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("aggregate family");
          verifyNoInteractions(broker);
        });
  }

  private RwmsKafkaOutboundEventPublisher publisher(ApplicationContext context) {
    return new RwmsKafkaOutboundEventPublisher(
        broker,
        mapper,
        new RwmsKafkaProperties(true, LogisticsTransportTopics.PRIMARY_OUTPUTS),
        List.copyOf(context.getBeansOfType(RwmsKafkaPayloadSafetyValidator.class).values()));
  }

  private DomainEventEnvelopeV2<LogisticsEventPayload> cancellation() {
    UUID subjectId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 10),
            subjectId,
            correlationId);
    document.beginTransferCancellation();
    // The mapper receives these JPA-assigned values after the owner flushes the transition.
    ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(document, "version", 1L);
    LogisticsEventPayload payload =
        Mappers.getMapper(LogisticsEventPayloadMapper.class)
            .toPayload(new LogisticsEventSource(document, 1, null));
    Instant recordedAt = Instant.parse("2026-09-06T10:00:00Z");
    return new DomainEventEnvelopeV2<>(
        2,
        UUID.randomUUID(),
        LogisticsEventType.TRANSFER_CANCELLATION_STARTED.value(),
        1,
        recordedAt,
        recordedAt,
        "logistics-service",
        "TRANSFER",
        document.getId().toString(),
        document.getVersion(),
        new CorrelationContext(correlationId, null),
        new OpaqueActorReference(subjectId.toString(), "USER", null),
        payload);
  }
}
