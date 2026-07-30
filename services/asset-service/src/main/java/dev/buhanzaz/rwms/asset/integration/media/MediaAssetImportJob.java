package dev.buhanzaz.rwms.asset.integration.media;

import java.util.List;
import java.util.UUID;

public record MediaAssetImportJob(
    UUID jobId,
    UUID assetImportId,
    UUID warehouseId,
    Status status,
    int preflightAttempts,
    int activationAttempts,
    String failureCode,
    List<SourceResult> results) {

  public MediaAssetImportJob {
    if (jobId == null
        || assetImportId == null
        || warehouseId == null
        || status == null
        || preflightAttempts < 0
        || activationAttempts < 0
        || results == null
        || results.size() > MediaAssetImportClient.MAX_SOURCES) {
      throw new IllegalArgumentException("Media import job is invalid");
    }
    if (failureCode != null && !failureCode.matches("^[A-Z_]{1,64}$")) {
      throw new IllegalArgumentException("Media import failure code is invalid");
    }
    results = List.copyOf(results);
  }

  public boolean hasWarnings() {
    return results.stream()
        .anyMatch(
            result ->
                result.skipped() > 0
                    || result.failed() > 0
                    || !result.warningCodes().isEmpty());
  }

  public enum Status {
    PREFLIGHT_PENDING,
    PREFLIGHT_RUNNING,
    PREFLIGHT_READY,
    ACTIVATION_PENDING,
    ACTIVATION_RUNNING,
    COMPLETED,
    FAILED
  }

  public record SourceResult(
      UUID sourceRowId,
      int prepared,
      int downloading,
      int imported,
      int skipped,
      int failed,
      List<UUID> mediaIds,
      List<String> warningCodes) {
    public SourceResult {
      if (sourceRowId == null
          || prepared < 0
          || downloading < 0
          || imported < 0
          || skipped < 0
          || failed < 0
          || mediaIds == null
          || mediaIds.size() > 1000
          || warningCodes == null
          || warningCodes.size() > 1000
          || warningCodes.stream().anyMatch(code -> !code.matches("^[A-Z_]{1,64}$"))) {
        throw new IllegalArgumentException("Media import source result is invalid");
      }
      mediaIds = List.copyOf(mediaIds);
      warningCodes = List.copyOf(warningCodes);
    }
  }
}
