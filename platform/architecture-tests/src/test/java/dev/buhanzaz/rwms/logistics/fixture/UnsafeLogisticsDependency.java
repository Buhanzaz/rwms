package dev.buhanzaz.rwms.logistics.fixture;

import dev.buhanzaz.rwms.asset.domain.fixture.SharedAssetModel;

public final class UnsafeLogisticsDependency {
  private final SharedAssetModel asset;

  public UnsafeLogisticsDependency(SharedAssetModel asset) {
    this.asset = asset;
  }

  public SharedAssetModel asset() {
    return asset;
  }
}
