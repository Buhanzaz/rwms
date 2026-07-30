package dev.buhanzaz.rwms.asset.integration.media;

import java.util.UUID;

public record MediaAssetImportBinding(UUID sourceRowId, UUID cabinId) {
  public MediaAssetImportBinding {
    if (sourceRowId == null || cabinId == null) {
      throw new IllegalArgumentException("Media import binding is invalid");
    }
  }
}
