package dev.buhanzaz.rwms.asset.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration value for asset idempotency.
 */
@ConfigurationProperties("rwms.asset.idempotency")
public class AssetIdempotencyProperties {
  private Duration retention = Duration.ofDays(7);
  private Duration cleanupDelay = Duration.ofHours(1);
  private int cleanupBatchSize = 250;

  public Duration retention() { return retention; }
  public void setRetention(Duration retention) { this.retention = retention; }
  public Duration cleanupDelay() { return cleanupDelay; }
  public void setCleanupDelay(Duration cleanupDelay) { this.cleanupDelay = cleanupDelay; }
  public int cleanupBatchSize() { return cleanupBatchSize; }
  public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
}
