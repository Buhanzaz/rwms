package dev.buhanzaz.rwms.asset.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cleanup component for expired asset idempotency cleanup records.
 */
@Component
public class AssetIdempotencyCleanup {
  private final AssetIdempotencyStore store;

  public AssetIdempotencyCleanup(AssetIdempotencyStore store) { this.store = store; }

  @Scheduled(fixedDelayString = "${rwms.asset.idempotency.cleanup-delay:1h}")
  public void cleanExpired() { store.cleanupExpired(); }
}
