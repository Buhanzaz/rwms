package dev.buhanzaz.rwms.inventory.fixture;

import dev.buhanzaz.rwms.asset.domain.fixture.SharedAssetModel;

public final class UnsafeInventoryDependency {
  private final SharedAssetModel asset;

  public UnsafeInventoryDependency(SharedAssetModel asset) {
    this.asset = asset;
  }

  public SharedAssetModel asset() {
    return asset;
  }
}
