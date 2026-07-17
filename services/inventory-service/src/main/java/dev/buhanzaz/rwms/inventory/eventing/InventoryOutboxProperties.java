package dev.buhanzaz.rwms.inventory.eventing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rwms.inventory.eventing.outbox")
public record InventoryOutboxProperties(String instanceId, Duration leaseDuration) {
  public InventoryOutboxProperties {
    if (instanceId == null || instanceId.isBlank()) instanceId = "inventory-service";
    if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
      leaseDuration = Duration.ofSeconds(30);
    }
  }
}
