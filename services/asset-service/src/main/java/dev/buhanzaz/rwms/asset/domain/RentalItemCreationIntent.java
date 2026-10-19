package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.proxy.HibernateProxy;

/**
 * Asset-owned durable intent that fences a newly created cabin until its exact media gallery is
 * proven complete or an operator explicitly abandons the workflow.
 */
@Entity
@Table(name = "rental_item_creation_intent")
public class RentalItemCreationIntent {
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "expected_photo_count", nullable = false)
  private int expectedPhotoCount;

  @Column(name = "media_folder_id", nullable = false)
  private UUID mediaFolderId;

  @Column(name = "media_command_id", nullable = false)
  private UUID mediaCommandId;

  @Column(name = "photo_manifest_sha256", nullable = false, length = 64)
  private String photoManifestSha256;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "rental_item_creation_photo",
      joinColumns = @JoinColumn(name = "intent_id"))
  @OrderColumn(name = "photo_index")
  private List<RentalItemCreationPhotoManifestEntry> photoManifest = new ArrayList<>();

  @Column(name = "creation_lease_id", nullable = false)
  private UUID creationLeaseId;

  @Column(name = "creation_lease_fencing_token", nullable = false)
  private long creationLeaseFencingToken;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private RentalItemCreationIntentState state;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "cover_media_id")
  private UUID coverMediaId;

  @Column(name = "media_proof_sha256", length = 64)
  private String mediaProofSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "abandoned_at")
  private OffsetDateTime abandonedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RentalItemCreationIntent() {}

  /** Creates one pending intent after its cabin and creation lease have been persisted. */
  public static RentalItemCreationIntent create(
      UUID id,
      UUID rentalItemId,
      UUID warehouseId,
      int expectedPhotoCount,
      UUID mediaFolderId,
      UUID mediaCommandId,
      String photoManifestSha256,
      List<RentalItemCreationPhotoManifestEntry> photoManifest,
      UUID creationLeaseId,
      long creationLeaseFencingToken,
      UUID createdBySubjectId,
      OffsetDateTime createdAt) {
    if (id == null
        || rentalItemId == null
        || warehouseId == null
        || mediaFolderId == null
        || mediaCommandId == null
        || photoManifest == null
        || creationLeaseId == null
        || createdBySubjectId == null) {
      throw new IllegalArgumentException("Creation intent identity is required");
    }
    if (expectedPhotoCount < 1 || expectedPhotoCount > 20) {
      throw new IllegalArgumentException("expectedPhotoCount must be between 1 and 20");
    }
    if (photoManifest.size() != expectedPhotoCount) {
      throw new IllegalArgumentException("photoManifest must match expectedPhotoCount");
    }
    String manifestHash = photoManifestSha256 == null ? "" : photoManifestSha256.trim();
    if (!SHA256.matcher(manifestHash).matches()) {
      throw new IllegalArgumentException("photoManifestSha256 is invalid");
    }
    if (creationLeaseFencingToken <= 0) {
      throw new IllegalArgumentException("creationLeaseFencingToken must be positive");
    }
    OffsetDateTime timestamp = requireTime(createdAt, "createdAt");
    RentalItemCreationIntent intent = new RentalItemCreationIntent();
    intent.id = id;
    intent.rentalItemId = rentalItemId;
    intent.warehouseId = warehouseId;
    intent.expectedPhotoCount = expectedPhotoCount;
    intent.mediaFolderId = mediaFolderId;
    intent.mediaCommandId = mediaCommandId;
    intent.photoManifestSha256 = manifestHash;
    intent.photoManifest = new ArrayList<>(photoManifest);
    intent.creationLeaseId = creationLeaseId;
    intent.creationLeaseFencingToken = creationLeaseFencingToken;
    intent.state = RentalItemCreationIntentState.PENDING;
    intent.createdBySubjectId = createdBySubjectId;
    intent.createdAt = timestamp;
    intent.updatedAt = timestamp;
    return intent;
  }

  /** Records the exact READY media proof after the creation hold has been released. */
  public void complete(UUID coverMediaId, String mediaProofSha256, OffsetDateTime completedAt) {
    requirePending();
    if (coverMediaId == null) {
      throw new IllegalArgumentException("coverMediaId is required");
    }
    String proof = mediaProofSha256 == null ? "" : mediaProofSha256.trim();
    if (!SHA256.matcher(proof).matches()) {
      throw new IllegalArgumentException("mediaProofSha256 is invalid");
    }
    OffsetDateTime timestamp = requireTime(completedAt, "completedAt");
    if (timestamp.isBefore(createdAt)) {
      throw new IllegalArgumentException("completedAt cannot precede creation");
    }
    state = RentalItemCreationIntentState.COMPLETED;
    this.coverMediaId = coverMediaId;
    this.mediaProofSha256 = proof;
    this.completedAt = timestamp;
    updatedAt = timestamp;
  }

  /** Marks the incomplete workflow abandoned after its cabin is quarantined from rent. */
  public void abandon(OffsetDateTime abandonedAt) {
    requirePending();
    OffsetDateTime timestamp = requireTime(abandonedAt, "abandonedAt");
    if (timestamp.isBefore(createdAt)) {
      throw new IllegalArgumentException("abandonedAt cannot precede creation");
    }
    state = RentalItemCreationIntentState.ABANDONED;
    this.abandonedAt = timestamp;
    updatedAt = timestamp;
  }

  private void requirePending() {
    if (state != RentalItemCreationIntentState.PENDING) {
      throw new IllegalStateException("Creation intent is already terminal");
    }
  }

  private static OffsetDateTime requireTime(OffsetDateTime value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
    if (createdAt == null) createdAt = timestamp;
    if (updatedAt == null) updatedAt = timestamp;
  }

  @PreUpdate
  void preUpdate() {
    if (updatedAt == null) updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getRentalItemId() { return rentalItemId; }
  public UUID getWarehouseId() { return warehouseId; }
  public int getExpectedPhotoCount() { return expectedPhotoCount; }
  public UUID getMediaFolderId() { return mediaFolderId; }
  public UUID getMediaCommandId() { return mediaCommandId; }
  public String getPhotoManifestSha256() { return photoManifestSha256; }
  public List<RentalItemCreationPhotoManifestEntry> getPhotoManifest() {
    return List.copyOf(photoManifest);
  }
  public UUID getCreationLeaseId() { return creationLeaseId; }
  public long getCreationLeaseFencingToken() { return creationLeaseFencingToken; }
  public RentalItemCreationIntentState getState() { return state; }
  public UUID getCreatedBySubjectId() { return createdBySubjectId; }
  public UUID getCoverMediaId() { return coverMediaId; }
  public String getMediaProofSha256() { return mediaProofSha256; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getCompletedAt() { return completedAt; }
  public OffsetDateTime getAbandonedAt() { return abandonedAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((RentalItemCreationIntent) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
