package dev.buhanzaz.rwms.asset.integration.media;

import java.util.UUID;

/**
 * Private asset integration type for media asset import binding.
 */
public record MediaAssetImportBinding(UUID sourceRowId, UUID cabinId) {
  public MediaAssetImportBinding {
    if (sourceRowId == null || cabinId == null) {
      throw new IllegalArgumentException("Media import binding is invalid");
    }
  }
}
