package dev.buhanzaz.rwms.asset.integration.media;

import java.util.List;
import java.util.UUID;

public interface MediaAssetImportClient {
  int MAX_SOURCES = 500;

  MediaAssetImportJob preflight(
      UUID assetImportId,
      UUID warehouseId,
      List<MediaAssetImportSource> sources,
      UUID idempotencyKey);

  MediaAssetImportJob get(UUID jobId);

  MediaAssetImportJob activate(
      UUID jobId,
      List<MediaAssetImportBinding> bindings,
      UUID idempotencyKey);

  MediaAssetImportJob replacePreflightSources(
      UUID jobId,
      List<MediaAssetImportSource> sources,
      UUID idempotencyKey);

  MediaAssetImportJob retry(UUID jobId, UUID idempotencyKey);
}
