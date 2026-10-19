package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

class LogisticsSanitizedDltRelayTest {
  @Test
  void invalidSafeBodyNeverReachesTheBroker() {
    LogisticsSanitizedDltStore store = mock(LogisticsSanitizedDltStore.class);
    StreamBridge streamBridge = mock(StreamBridge.class);
    LogisticsSanitizedDltStore.Claim claim =
        new LogisticsSanitizedDltStore.Claim(
            UUID.randomUUID(),
            LogisticsAggregateType.RETURN.sanitizedDltTopic(),
            "{}",
            "not-a-checksum",
            0,
            UUID.randomUUID());
    when(store.claim("test-relay", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));

    assertThat(relay(store, streamBridge).relayOne()).isFalse();

    verify(store).failed(claim);
    verify(streamBridge, never()).send(anyString(), any(Message.class));
  }

  @Test
  void acknowledgedSafeBodyIsMarkedPublished() {
    LogisticsSanitizedDltStore store = mock(LogisticsSanitizedDltStore.class);
    StreamBridge streamBridge = mock(StreamBridge.class);
    String body = "{}";
    LogisticsSanitizedDltStore.Claim claim =
        new LogisticsSanitizedDltStore.Claim(
            UUID.randomUUID(),
            LogisticsAggregateType.RETURN.sanitizedDltTopic(),
            body,
            LogisticsEventStore.sha256(body.getBytes(StandardCharsets.UTF_8)),
            0,
            UUID.randomUUID());
    when(store.claim("test-relay", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
    when(streamBridge.send(org.mockito.ArgumentMatchers.eq(claim.destination()), any(Message.class)))
        .thenReturn(true);
    when(store.published(claim)).thenReturn(true);

    assertThat(relay(store, streamBridge).relayOne()).isTrue();

    verify(store).published(claim);
  }

  private LogisticsSanitizedDltRelay relay(
      LogisticsSanitizedDltStore store, StreamBridge streamBridge) {
    LogisticsOutboxProperties properties = new LogisticsOutboxProperties();
    properties.setInstanceId("test-relay");
    properties.setLeaseDuration(Duration.ofSeconds(30));
    return new LogisticsSanitizedDltRelay(store, properties, streamBridge);
  }
}
