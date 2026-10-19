package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable expected source-photo identity stored inside one cabin creation intent. */
@Embeddable
public class RentalItemCreationPhotoManifestEntry {
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final Set<String> CONTENT_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");

  @Column(name = "media_command_id", nullable = false)
  private UUID uploadCommandId;

  @Column(name = "checksum_sha256", nullable = false, length = 64)
  private String checksumSha256;

  @Column(name = "content_type", nullable = false, length = 64)
  private String contentType;

  @Column(name = "content_length", nullable = false)
  private long contentLength;

  protected RentalItemCreationPhotoManifestEntry() {}

  /** Creates one validated manifest value with its server-owned upload command identity. */
  public static RentalItemCreationPhotoManifestEntry create(
      UUID uploadCommandId, String checksumSha256, String contentType, long contentLength) {
    if (uploadCommandId == null) {
      throw new IllegalArgumentException("uploadCommandId is required");
    }
    String checksum = checksumSha256 == null ? "" : checksumSha256.trim();
    if (!SHA256.matcher(checksum).matches()) {
      throw new IllegalArgumentException("checksumSha256 is invalid");
    }
    String canonicalContentType =
        contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    if (!CONTENT_TYPES.contains(canonicalContentType)) {
      throw new IllegalArgumentException("contentType is not a supported cabin image type");
    }
    if (contentLength <= 0) {
      throw new IllegalArgumentException("contentLength must be positive");
    }
    RentalItemCreationPhotoManifestEntry entry =
        new RentalItemCreationPhotoManifestEntry();
    entry.uploadCommandId = uploadCommandId;
    entry.checksumSha256 = checksum;
    entry.contentType = canonicalContentType;
    entry.contentLength = contentLength;
    return entry;
  }

  public UUID getUploadCommandId() { return uploadCommandId; }
  public String getChecksumSha256() { return checksumSha256; }
  public String getContentType() { return contentType; }
  public long getContentLength() { return contentLength; }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof RentalItemCreationPhotoManifestEntry that)) return false;
    return contentLength == that.contentLength
        && Objects.equals(uploadCommandId, that.uploadCommandId)
        && Objects.equals(checksumSha256, that.checksumSha256)
        && Objects.equals(contentType, that.contentType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uploadCommandId, checksumSha256, contentType, contentLength);
  }
}
