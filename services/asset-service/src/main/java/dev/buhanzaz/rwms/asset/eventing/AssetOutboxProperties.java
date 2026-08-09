package dev.buhanzaz.rwms.asset.eventing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration value for the asset transactional-outbox relay lease.
 */
@ConfigurationProperties("rwms.asset.eventing.outbox")
public class AssetOutboxProperties {
  private Duration relayDelay = Duration.ofSeconds(1);
  private Duration leaseDuration = Duration.ofSeconds(30);
  private String instanceId = "asset-service";

  public Duration relayDelay() { return relayDelay; }
  public void setRelayDelay(Duration relayDelay) { this.relayDelay = relayDelay; }
  public Duration leaseDuration() { return leaseDuration; }
  public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
  public String instanceId() { return instanceId; }
  public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
}
