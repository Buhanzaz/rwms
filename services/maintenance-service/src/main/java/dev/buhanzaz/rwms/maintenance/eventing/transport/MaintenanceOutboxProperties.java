package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Validated configuration for the maintenance eventing boundary. */
@ConfigurationProperties("rwms.maintenance.eventing.outbox")
public class MaintenanceOutboxProperties {
  private Duration relayDelay = Duration.ofSeconds(1);
  private Duration leaseDuration = Duration.ofSeconds(30);
  private String instanceId = "maintenance-service";

  public Duration relayDelay() {
    return relayDelay;
  }

  public void setRelayDelay(Duration relayDelay) {
    this.relayDelay = relayDelay;
  }

  public Duration leaseDuration() {
    return leaseDuration;
  }

  public void setLeaseDuration(Duration leaseDuration) {
    this.leaseDuration = leaseDuration;
  }

  public String instanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }
}
