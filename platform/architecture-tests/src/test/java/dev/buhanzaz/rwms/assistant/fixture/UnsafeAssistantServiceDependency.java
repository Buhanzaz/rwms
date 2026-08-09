package dev.buhanzaz.rwms.assistant.fixture;

import dev.buhanzaz.rwms.asset.domain.fixture.SharedAssetModel;

/** Unsafe fixture that directly reuses another service's Java business model. */
public final class UnsafeAssistantServiceDependency {
  private final SharedAssetModel asset;

  public UnsafeAssistantServiceDependency(SharedAssetModel asset) {
    this.asset = asset;
  }

  public SharedAssetModel asset() {
    return asset;
  }
}
