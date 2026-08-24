package dev.buhanzaz.rwms.logistics.photo;

import java.util.Set;
import java.util.UUID;

/**
 * One immutable, presentation-safe media identity. SMALL is always available for thumbnails;
 * contentVariant is either LARGE or the SMALL fallback captured at creation time.
 */
public record CabinPhotoPresentationPhotoSnapshot(
    UUID mediaId, long generation, int sortOrder, String contentVariant) {
  private static final Set<String> CONTENT_VARIANTS = Set.of("SMALL", "LARGE");

  /** Rejects malformed or unsupported media identities before they can enter JSONB persistence. */
  public CabinPhotoPresentationPhotoSnapshot {
    if (mediaId == null
        || generation < 1
        || sortOrder < 0
        || !CONTENT_VARIANTS.contains(contentVariant)) {
      throw new IllegalArgumentException("Cabin photo presentation snapshot is invalid");
    }
  }
}
