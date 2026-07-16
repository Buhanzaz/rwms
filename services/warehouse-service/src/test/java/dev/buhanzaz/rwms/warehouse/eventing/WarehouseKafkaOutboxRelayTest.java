package dev.buhanzaz.rwms.warehouse.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import dev.buhanzaz.rwms.warehouse.service.WarehouseChecksum;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseKafkaOutboxRelayTest {
  private static final String TOPIC = "rwms.warehouse.warehouse.v1";

  @Test
  void checksumMismatchIsQuarantinedBeforeAnyBrokerInteraction() {
    WarehouseKafkaOutboxStore store = mock(WarehouseKafkaOutboxStore.class);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    WarehouseKafkaOutboxStore.Claim claim = claim("{}", "not-a-checksum", TOPIC);
    when(store.claim("test-relay", java.time.Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
    WarehouseKafkaOutboxRelay relay = relay(store, publisher);

    assertThat(relay.relayOne()).isFalse();
    verify(store).quarantine(claim, "CHECKSUM_MISMATCH");
    verify(publisher, never()).publishSerializedV2(TOPIC, "{}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void transientBrokerFailureUsesBoundedRetryStateRatherThanPublishingAcknowledge() {
    WarehouseKafkaOutboxStore store = mock(WarehouseKafkaOutboxStore.class);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    WarehouseKafkaOutboxStore.Claim claim = claim("{}", WarehouseChecksum.sha256("{}".getBytes(StandardCharsets.UTF_8)), TOPIC);
    when(store.claim("test-relay", java.time.Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
    when(store.hasValidEnvelope(claim)).thenReturn(true);
    doThrow(new IllegalStateException("broker unavailable"))
        .when(publisher)
        .publishSerializedV2(TOPIC, "{}".getBytes(StandardCharsets.UTF_8));
    WarehouseKafkaOutboxRelay relay = relay(store, publisher);

    assertThat(relay.relayOne()).isFalse();
    verify(store).transientFailure(claim);
    verify(store, never()).published(claim.eventId(), claim.leaseToken());
  }

  @Test
  void unsupportedDestinationIsMovedToDurableDltWithoutRetrying() {
    WarehouseKafkaOutboxStore store = mock(WarehouseKafkaOutboxStore.class);
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    WarehouseKafkaOutboxStore.Claim claim = claim("{}", WarehouseChecksum.sha256("{}".getBytes(StandardCharsets.UTF_8)), "rwms.forged.v1");
    when(store.claim("test-relay", java.time.Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
    when(store.hasValidEnvelope(claim)).thenReturn(true);
    WarehouseKafkaOutboxRelay relay = relay(store, publisher);

    assertThat(relay.relayOne()).isFalse();
    verify(store).validationFailure(claim);
    verify(publisher, never()).publishSerializedV2("rwms.forged.v1", "{}".getBytes(StandardCharsets.UTF_8));
  }

  private WarehouseKafkaOutboxRelay relay(
      WarehouseKafkaOutboxStore store, RwmsKafkaOutboundEventPublisher publisher) {
    WarehouseOutboxProperties properties = new WarehouseOutboxProperties();
    properties.setInstanceId("test-relay");
    return new WarehouseKafkaOutboxRelay(store, properties, publisher);
  }

  private WarehouseKafkaOutboxStore.Claim claim(String body, String checksum, String topic) {
    return new WarehouseKafkaOutboxStore.Claim(
        UUID.randomUUID(),
        "WAREHOUSE",
        UUID.randomUUID().toString(),
        0,
        WarehouseEventType.CREATED.value(),
        topic,
        body,
        checksum,
        0,
        UUID.randomUUID());
  }
}
