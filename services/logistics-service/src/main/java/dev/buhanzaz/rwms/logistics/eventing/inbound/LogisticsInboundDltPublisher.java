package dev.buhanzaz.rwms.logistics.eventing.inbound;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsSanitizedDltStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Emits only a deterministic hash-only summary for failed source input. */
@Component
@RequiredArgsConstructor
public class LogisticsInboundDltPublisher {
  private final LogisticsSanitizedDltStore store;

  public void publish(byte[] raw, String failureCode, String sourceTopic, UUID sourceEventId) {
    store.enqueueInbound(LogisticsEventStore.sha256(raw), failureCode, sourceTopic, sourceEventId);
  }

  public void publishHash(
      String messageSha256, String failureCode, String sourceTopic, UUID sourceEventId) {
    store.enqueueInbound(messageSha256, failureCode, sourceTopic, sourceEventId);
  }
}
