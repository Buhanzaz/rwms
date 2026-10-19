package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogisticsKafkaOutboxRelayTest {
  private static final String TOPIC = "rwms.logistics.return.v1";

  @Test
  void checksumMismatchIsQuarantinedBeforeBrokerInteraction() {
    LogisticsOutboxStore store = mock(LogisticsOutboxStore.class);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    LogisticsSanitizedDltPublisher deadLetters = mock(LogisticsSanitizedDltPublisher.class);
    LogisticsOutboxStore.Claim claim = claim("{}", "not-a-checksum", TOPIC);
    when(store.claim("test-relay", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));

    assertThat(relay(store, publisher, deadLetters).relayOne()).isFalse();

    verify(store).quarantine(claim, "CHECKSUM_MISMATCH");
    verify(deadLetters).validationRejected(claim);
    verify(publisher, never()).publishSerializedV2(TOPIC, "{}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void transientBrokerFailureUsesBoundedRetryStateBeforeAcknowledgement() {
    LogisticsOutboxStore store = mock(LogisticsOutboxStore.class);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    LogisticsSanitizedDltPublisher deadLetters = mock(LogisticsSanitizedDltPublisher.class);
    LogisticsOutboxStore.Claim claim = claim("{}", checksum("{}"), TOPIC);
    when(store.claim("test-relay", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
    when(store.hasValidEnvelope(claim)).thenReturn(true);
    doThrow(new IllegalStateException("broker unavailable"))
        .when(publisher)
        .publishSerializedV2(TOPIC, "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(relay(store, publisher, deadLetters).relayOne()).isFalse();

    verify(store).transientFailure(claim);
    verify(store, never()).published(claim.eventId(), claim.leaseToken());
  }

  @Test
  void unsupportedDestinationMovesToDltWithoutPublishing() {
    LogisticsOutboxStore store = mock(LogisticsOutboxStore.class);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    LogisticsSanitizedDltPublisher deadLetters = mock(LogisticsSanitizedDltPublisher.class);
    LogisticsOutboxStore.Claim claim = claim("{}", checksum("{}"), "rwms.forged.v1");
    when(store.claim("test-relay", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
    when(store.hasValidEnvelope(claim)).thenReturn(true);

    assertThat(relay(store, publisher, deadLetters).relayOne()).isFalse();

    verify(store).validationFailure(claim);
    verify(deadLetters).validationRejected(claim);
    verify(publisher, never()).publishSerializedV2("rwms.forged.v1", "{}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void exhaustedBrokerRetriesCreateOnlySanitizedProcessingDltMetadata() {
    LogisticsOutboxStore store = mock(LogisticsOutboxStore.class);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    LogisticsSanitizedDltPublisher deadLetters = mock(LogisticsSanitizedDltPublisher.class);
    LogisticsOutboxStore.Claim claim = claim("{}", checksum("{}"), TOPIC, 3);
    when(store.claim("test-relay", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
    when(store.hasValidEnvelope(claim)).thenReturn(true);
    doThrow(new IllegalStateException("broker unavailable"))
        .when(publisher)
        .publishSerializedV2(TOPIC, "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(relay(store, publisher, deadLetters).relayOne()).isFalse();

    verify(store).transientFailure(claim);
    verify(deadLetters).processingFailed(claim);
  }

  private LogisticsKafkaOutboxRelay relay(
      LogisticsOutboxStore store,
      RwmsKafkaOutboundEventPublisher publisher,
      LogisticsSanitizedDltPublisher deadLetters) {
    LogisticsOutboxProperties properties = new LogisticsOutboxProperties();
    properties.setInstanceId("test-relay");
    properties.setLeaseDuration(Duration.ofSeconds(30));
    return new LogisticsKafkaOutboxRelay(store, properties, publisher, deadLetters);
  }

  private LogisticsOutboxStore.Claim claim(String body, String checksum, String topic) {
    return claim(body, checksum, topic, 0);
  }

  private LogisticsOutboxStore.Claim claim(
      String body, String checksum, String topic, int attemptCount) {
    return new LogisticsOutboxStore.Claim(
        UUID.randomUUID(),
        LogisticsAggregateType.RETURN.name(),
        UUID.randomUUID().toString(),
        0,
        LogisticsEventType.RETURN_CREATED.value(),
        topic,
        body,
        checksum,
        attemptCount,
        UUID.randomUUID());
  }

  private String checksum(String value) {
    return LogisticsEventStore.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
