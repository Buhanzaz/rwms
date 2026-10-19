package dev.buhanzaz.rwms.asset.integration.media;

import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Disabled local implementation of the asset media-import boundary.
 */
final class DisabledMediaAssetImportClient implements MediaAssetImportClient {
  @Override
  public MediaAssetImportJob preflight(
      UUID assetImportId,
      UUID warehouseId,
      List<MediaAssetImportSource> sources,
      UUID idempotencyKey) {
    throw unavailable();
  }

  @Override
  public MediaAssetImportJob get(UUID jobId) {
    throw unavailable();
  }

  @Override
  public MediaAssetImportJob activate(
      UUID jobId,
      List<MediaAssetImportBinding> bindings,
      UUID idempotencyKey) {
    throw unavailable();
  }

  @Override
  public MediaAssetImportJob replacePreflightSources(
      UUID jobId,
      List<MediaAssetImportSource> sources,
      UUID idempotencyKey) {
    throw unavailable();
  }

  @Override
  public MediaAssetImportJob retry(UUID jobId, UUID idempotencyKey) {
    throw unavailable();
  }

  private static AssetDependencyException unavailable() {
    return new AssetDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Media asset import client is disabled");
  }
}
