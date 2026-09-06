package dev.buhanzaz.rwms.inventory.eventing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lease duration and finite attempt budget shared by inventory outbox and sanitized DLT relays.
 */
@ConfigurationProperties("rwms.inventory.eventing.outbox")
public record InventoryOutboxProperties(String instanceId, Duration leaseDuration, int maxAttempts) {
  public InventoryOutboxProperties {
    if (instanceId == null || instanceId.isBlank()) instanceId = "inventory-service";
    if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
      leaseDuration = Duration.ofSeconds(30);
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("Inventory outbox maxAttempts must be positive");
    }
  }
}
