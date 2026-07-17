package dev.buhanzaz.rwms.maintenance.eventing.transport;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceSanitizedDltPublisher {
  private final MaintenanceSanitizedDltStore store;

  public MaintenanceSanitizedDltPublisher(MaintenanceSanitizedDltStore store) {
    this.store = store;
  }

  public void publish(
      byte[] rejectedMessage, String failureCode, String sourceTopic, UUID sourceEventId) {
    publishHash(
        MaintenanceChecksum.sha256(rejectedMessage), failureCode, sourceTopic, sourceEventId);
  }

  public void publishHash(
      String messageSha256, String failureCode, String sourceTopic, UUID sourceEventId) {
    store.enqueue(
        messageSha256,
        failureCode,
        sourceTopic,
        sourceEventId,
        Map.of(
            "failureCode",
            failureCode,
            "messageSha256",
            messageSha256,
            "recordedAt",
            Instant.now().toString()));
  }
}
