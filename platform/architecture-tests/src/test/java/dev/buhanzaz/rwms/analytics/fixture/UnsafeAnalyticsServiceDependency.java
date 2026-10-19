package dev.buhanzaz.rwms.analytics.fixture;

import dev.buhanzaz.rwms.asset.domain.fixture.SharedAssetModel;

/** Unsafe projection fixture that directly reuses another service's Java business model. */
public final class UnsafeAnalyticsServiceDependency {
  private final SharedAssetModel asset;

  public UnsafeAnalyticsServiceDependency(SharedAssetModel asset) {
    this.asset = asset;
  }

  public SharedAssetModel asset() {
    return asset;
  }
}
