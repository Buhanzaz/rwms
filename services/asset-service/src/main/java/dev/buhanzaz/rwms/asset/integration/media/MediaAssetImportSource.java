package dev.buhanzaz.rwms.asset.integration.media;

import java.util.UUID;

public record MediaAssetImportSource(UUID sourceRowId, String publicUrl) {
  private static final String PUBLIC_URL_PATTERN =
      "^https://disk\\.yandex\\.ru/d/[A-Za-z0-9_-]{14}$";

  public MediaAssetImportSource {
    if (sourceRowId == null
        || publicUrl == null
        || !publicUrl.matches(PUBLIC_URL_PATTERN)) {
      throw new IllegalArgumentException("Media import source is invalid");
    }
  }
}
