package dev.buhanzaz.rwms.gateway.fixture;

import dev.buhanzaz.rwms.asset.domain.fixture.SharedAssetModel;

/** Unsafe gateway fixture that depends directly on an owning service's Java model. */
public final class UnsafeGatewayServiceDependency {
  private final SharedAssetModel asset;

  public UnsafeGatewayServiceDependency(SharedAssetModel asset) {
    this.asset = asset;
  }

  public SharedAssetModel asset() {
    return asset;
  }
}
