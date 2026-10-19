package dev.buhanzaz.rwms.asset.integration.media;

import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Explicit fail-closed implementation used when the private media boundary is disabled. */
final class DisabledMediaCabinCreationSnapshotClient
    implements MediaCabinCreationSnapshotClient {
  @Override
  public Optional<CabinCreationSnapshot> read(UUID warehouseId, UUID cabinId) {
    throw new AssetDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Cabin photo verification is temporarily unavailable");
  }
}
