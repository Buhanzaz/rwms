package dev.buhanzaz.rwms.asset.integration.media;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Least-privilege private media read used to prove one cabin's active gallery. */
public interface MediaCabinCreationSnapshotClient {
  /** Reads the canonical current snapshot, or empty when media has no active owner proof. */
  Optional<CabinCreationSnapshot> read(UUID warehouseId, UUID cabinId);

  /** Current logical gallery identity, count, READY images and cover for one exact cabin. */
  record CabinCreationSnapshot(
      UUID cabinId,
      UUID warehouseId,
      UUID activeFolderId,
      UUID coverMediaId,
      long photoCount,
      List<ReadyPhoto> readyPhotos) {
    public CabinCreationSnapshot {
      readyPhotos = readyPhotos == null ? List.of() : List.copyOf(readyPhotos);
    }
  }

  /** Opaque identity, manifest index and immutable source proof of one current READY image. */
  record ReadyPhoto(
      UUID mediaId,
      int generation,
      long photoIndex,
      String checksumSha256,
      String contentType,
      long contentLength) {}
}
