package dev.buhanzaz.rwms.taskboard.eventing;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Validated relay cadence, lease duration, and instance identity for task-board outbox claims. */
@ConfigurationProperties("rwms.task-board.eventing.outbox")
public class TaskBoardOutboxProperties {
  private Duration relayDelay = Duration.ofSeconds(1);
  private Duration leaseDuration = Duration.ofSeconds(30);
  private String instanceId = "task-board-" + UUID.randomUUID();

  public Duration relayDelay() { return relayDelay; }
  public void setRelayDelay(Duration relayDelay) { this.relayDelay = relayDelay; }
  public Duration leaseDuration() { return leaseDuration; }
  public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
  public String instanceId() { return instanceId; }
  public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
}
