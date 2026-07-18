package dev.buhanzaz.rwms.logistics.eventing;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rwms.logistics.eventing.outbox")
public class LogisticsOutboxProperties {
  private Duration relayDelay = Duration.ofSeconds(1);
  private Duration leaseDuration = Duration.ofSeconds(30);
  private String instanceId = "logistics-" + UUID.randomUUID();

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
