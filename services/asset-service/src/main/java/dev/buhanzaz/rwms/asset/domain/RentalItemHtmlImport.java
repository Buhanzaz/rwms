package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/**
 * JPA entity that persists rental item html import in the asset-owned database.
 */
@Entity
@Table(name = "rental_item_html_import")
public class RentalItemHtmlImport {
  private static final int MAX_PLAN_JSON_CHARACTERS = 1_000_000;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "actor_subject_id", nullable = false)
  private UUID actorSubjectId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "source_sha256", nullable = false, length = 64)
  private String sourceSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 40)
  private RentalItemHtmlImportState state;

  @Column(name = "row_count", nullable = false)
  private int rowCount;

  @Column(name = "selected_count", nullable = false)
  private int selectedCount;

  @Column(name = "invalid_count", nullable = false)
  private int invalidCount;

  @Column(name = "unresolved_count", nullable = false)
  private int unresolvedCount;

  @Column(name = "media_link_count", nullable = false)
  private int mediaLinkCount;

  @Column(name = "warning_count", nullable = false)
  private int warningCount;

  @Column(name = "plan_json", nullable = false, length = MAX_PLAN_JSON_CHARACTERS)
  private String planJson = "{}";

  @Column(name = "media_job_id")
  private UUID mediaJobId;

  /**
   * Durable owner of the commit intent. A media preflight can succeed remotely
   * while its response is lost, so recovery must not depend on a browser-held
   * idempotency key.
   */
  @Column(name = "commit_actor_subject_id")
  private UUID commitActorSubjectId;

  @Column(name = "commit_idempotency_key")
  private UUID commitIdempotencyKey;

  @Column(name = "commit_request_sha256", length = 64)
  private String commitRequestSha256;

  @Column(name = "failure_code", length = 128)
  private String failureCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RentalItemHtmlImport() {}

  public static RentalItemHtmlImport create(
      UUID warehouseId,
      UUID actorSubjectId,
      UUID idempotencyKey,
      String sourceSha256,
      int rowCount,
      int invalidCount,
      int unresolvedCount,
      int mediaLinkCount,
      int warningCount) {
    if (warehouseId == null
        || actorSubjectId == null
        || idempotencyKey == null) {
      throw new IllegalArgumentException("HTML import identity is required");
    }
    if (sourceSha256 == null || !sourceSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("HTML import digest is invalid");
    }
    requireCount(rowCount);
    requireCount(invalidCount);
    requireCount(unresolvedCount);
    requireCount(mediaLinkCount);
    requireCount(warningCount);
    RentalItemHtmlImport value = new RentalItemHtmlImport();
    value.warehouseId = warehouseId;
    value.actorSubjectId = actorSubjectId;
    value.idempotencyKey = idempotencyKey;
    value.sourceSha256 = sourceSha256;
    value.state =
        invalidCount == 0 && unresolvedCount == 0
            ? RentalItemHtmlImportState.READY
            : RentalItemHtmlImportState.REVIEW_REQUIRED;
    value.rowCount = rowCount;
    value.selectedCount = rowCount - invalidCount;
    value.invalidCount = invalidCount;
    value.unresolvedCount = unresolvedCount;
    value.mediaLinkCount = mediaLinkCount;
    value.warningCount = warningCount;
    return value;
  }

  public void replacePlan(
      String planJson, int selectedCount, int invalidCount, int unresolvedCount) {
    this.planJson = jsonObject(planJson, MAX_PLAN_JSON_CHARACTERS);
    requireCount(selectedCount);
    requireCount(invalidCount);
    requireCount(unresolvedCount);
    this.selectedCount = selectedCount;
    this.invalidCount = invalidCount;
    this.unresolvedCount = unresolvedCount;
    this.failureCode = null;
    this.state =
        invalidCount == 0 && unresolvedCount == 0
            ? RentalItemHtmlImportState.READY
            : RentalItemHtmlImportState.REVIEW_REQUIRED;
  }

  public void beginCommit(UUID actorSubjectId, UUID idempotencyKey, String requestSha256) {
    if (actorSubjectId == null
        || idempotencyKey == null
        || requestSha256 == null
        || !requestSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("HTML import commit identity is required");
    }
    if (state == RentalItemHtmlImportState.COMMITTING) {
      if (actorSubjectId.equals(commitActorSubjectId)
          && idempotencyKey.equals(commitIdempotencyKey)
          && requestSha256.equals(commitRequestSha256)) {
        return;
      }
      throw new IllegalStateException("HTML import is already committing");
    }
    requireState(RentalItemHtmlImportState.READY);
    commitActorSubjectId = actorSubjectId;
    commitIdempotencyKey = idempotencyKey;
    commitRequestSha256 = requestSha256;
    state = RentalItemHtmlImportState.COMMITTING;
    failureCode = null;
  }

  public boolean hasCommitIdentity(
      UUID actorSubjectId, UUID idempotencyKey, String requestSha256) {
    return actorSubjectId != null
        && idempotencyKey != null
        && requestSha256 != null
        && actorSubjectId.equals(commitActorSubjectId)
        && idempotencyKey.equals(commitIdempotencyKey)
        && requestSha256.equals(commitRequestSha256);
  }

  public void attachMediaJob(UUID jobId) {
    if (jobId == null) throw new IllegalArgumentException("Media job ID is required");
    if (mediaJobId != null && !mediaJobId.equals(jobId)) {
      throw new IllegalStateException("HTML import is already bound to another media job");
    }
    mediaJobId = jobId;
  }

  public void assetsCommitted(boolean mediaRequired) {
    requireState(RentalItemHtmlImportState.COMMITTING);
    state = mediaRequired
        ? RentalItemHtmlImportState.ASSETS_COMMITTED
        : RentalItemHtmlImportState.COMPLETED;
  }

  public void mediaStarted(UUID jobId) {
    if (jobId == null) throw new IllegalArgumentException("Media job ID is required");
    if (state != RentalItemHtmlImportState.ASSETS_COMMITTED
        && state != RentalItemHtmlImportState.MEDIA_IMPORTING) {
      throw new IllegalStateException("HTML import assets are not committed");
    }
    mediaJobId = jobId;
    state = RentalItemHtmlImportState.MEDIA_IMPORTING;
  }

  public void mediaCompleted(boolean warnings) {
    if (state != RentalItemHtmlImportState.MEDIA_IMPORTING
        && state != RentalItemHtmlImportState.ASSETS_COMMITTED) {
      throw new IllegalStateException("HTML import media is not active");
    }
    state =
        warnings
            ? RentalItemHtmlImportState.COMPLETED_WITH_WARNINGS
            : RentalItemHtmlImportState.COMPLETED;
  }

  public void mediaSkipped() {
    if (state != RentalItemHtmlImportState.FAILED || mediaJobId == null) {
      throw new IllegalStateException("HTML import media cannot be skipped");
    }
    failureCode = null;
    state = RentalItemHtmlImportState.COMPLETED_WITH_WARNINGS;
  }

  public void mediaPending() {
    if (mediaJobId == null) throw new IllegalStateException("Media job is not attached");
    if (state != RentalItemHtmlImportState.ASSETS_COMMITTED
        && state != RentalItemHtmlImportState.MEDIA_IMPORTING
        && state != RentalItemHtmlImportState.FAILED) {
      throw new IllegalStateException("HTML import media cannot be queued");
    }
    failureCode = null;
    state = RentalItemHtmlImportState.ASSETS_COMMITTED;
  }

  public void fail(String code) {
    String normalized = code == null ? "" : code.trim();
    if (!normalized.matches("^[A-Z][A-Z0-9_]{0,127}$")) {
      throw new IllegalArgumentException("Failure code is invalid");
    }
    failureCode = normalized;
    state = RentalItemHtmlImportState.FAILED;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  private void requireState(RentalItemHtmlImportState required) {
    if (state != required) {
      throw new IllegalStateException("HTML import is not in " + required + " state");
    }
  }

  private static void requireCount(int value) {
    if (value < 0) throw new IllegalArgumentException("HTML import count must not be negative");
  }

  private static String jsonObject(String value, int maximum) {
    String normalized = value == null || value.isBlank() ? "{}" : value.trim();
    if (normalized.length() > maximum
        || !normalized.startsWith("{")
        || !normalized.endsWith("}")) {
      throw new IllegalArgumentException("HTML import plan must be a bounded JSON object");
    }
    return normalized;
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getActorSubjectId() {
    return actorSubjectId;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getSourceSha256() {
    return sourceSha256;
  }

  public RentalItemHtmlImportState getState() {
    return state;
  }

  public int getRowCount() {
    return rowCount;
  }

  public int getSelectedCount() {
    return selectedCount;
  }

  public int getInvalidCount() {
    return invalidCount;
  }

  public int getUnresolvedCount() {
    return unresolvedCount;
  }

  public int getMediaLinkCount() {
    return mediaLinkCount;
  }

  public int getWarningCount() {
    return warningCount;
  }

  public String getPlanJson() {
    return planJson;
  }

  public UUID getMediaJobId() {
    return mediaJobId;
  }

  public UUID getCommitActorSubjectId() {
    return commitActorSubjectId;
  }

  public UUID getCommitIdempotencyKey() {
    return commitIdempotencyKey;
  }

  public String getCommitRequestSha256() {
    return commitRequestSha256;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

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
        && Objects.equals(id, ((RentalItemHtmlImport) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
