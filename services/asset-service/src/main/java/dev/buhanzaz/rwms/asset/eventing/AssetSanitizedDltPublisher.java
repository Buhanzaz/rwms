package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AssetSanitizedDltPublisher {
  private final AssetSanitizedDltStore store;

  public AssetSanitizedDltPublisher(AssetSanitizedDltStore store) {
    this.store = store;
  }

  public void publish(byte[] rejectedMessage, String failureCode) {
    String hash = AssetChecksum.sha256(rejectedMessage);
    store.enqueue(hash, failureCode, Map.of("failureCode", failureCode,
        "messageSha256", hash, "recordedAt", Instant.now().toString()));
  }

  public void publishHash(String messageSha256, String failureCode) {
    store.enqueue(messageSha256, failureCode, Map.of("failureCode", failureCode,
        "messageSha256", messageSha256, "recordedAt", Instant.now().toString()));
  }
}
