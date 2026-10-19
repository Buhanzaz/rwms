package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Durable media reservation authorized by a Driver Shift owner-proof event. */
@Entity
@Table(
    name = "driver_shift_photo",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_driver_shift_photo_client_reference",
          columnNames = {"shift_id", "client_reference_id"}),
      @UniqueConstraint(name = "uk_driver_shift_photo_evidence", columnNames = "evidence_id")
    },
    indexes = @Index(name = "idx_driver_shift_photo_shift_state", columnList = "shift_id,state"))
public class DriverShiftPhoto extends AbstractVersionedEntity {
  @Column(name = "shift_id", nullable = false)
  private UUID shiftId;

  @Column(name = "driver_id", nullable = false)
  private UUID driverId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "client_reference_id", nullable = false)
  private UUID clientReferenceId;

  @Column(name = "evidence_id", nullable = false)
  private UUID evidenceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "photo_role", nullable = false, length = 32)
  private ShiftPhotoRole role;

  @Column(name = "defect_id")
  private UUID defectId;

  @Column(name = "inspection_item_id")
  private UUID inspectionItemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private ShiftPhotoState state;

  @Column(name = "media_id")
  private UUID mediaId;

  @Column(name = "media_generation")
  private Long mediaGeneration;

  @Column(name = "captured_at", nullable = false)
  private OffsetDateTime capturedAt;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "content_type", nullable = false, length = 128)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "review_reason", length = 512)
  private String reviewReason;

  /** Reserves one upload identity and freezes safe capture metadata. */
  public void initialize(
      UUID shiftId,
      UUID driverId,
      UUID warehouseId,
      UUID clientReferenceId,
      UUID evidenceId,
      ShiftPhotoRole role,
      UUID defectId,
      UUID itemId,
      OffsetDateTime capturedAt,
      String contentType,
      long sizeBytes,
      String sha256,
      OffsetDateTime now) {
    this.shiftId = shiftId;
    this.driverId = driverId;
    this.warehouseId = warehouseId;
    this.clientReferenceId = clientReferenceId;
    this.evidenceId = evidenceId;
    this.role = role;
    this.defectId = defectId;
    inspectionItemId = itemId;
    this.capturedAt = capturedAt;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
    state = ShiftPhotoState.RESERVED;
    recordedAt = now;
  }

  /** Applies one terminal media-service fact after owner and correlation validation. */
  public void applyMedia(ShiftPhotoState state, UUID mediaId, Long generation, String reason) {
    this.state = state;
    this.mediaId = mediaId;
    mediaGeneration = generation;
    reviewReason = reason;
  }

  public UUID getShiftId() {
    return shiftId;
  }

  public UUID getDriverId() {
    return driverId;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getClientReferenceId() {
    return clientReferenceId;
  }

  public UUID getEvidenceId() {
    return evidenceId;
  }

  public ShiftPhotoRole getRole() {
    return role;
  }

  public UUID getDefectId() {
    return defectId;
  }

  public UUID getInspectionItemId() {
    return inspectionItemId;
  }

  public ShiftPhotoState getState() {
    return state;
  }

  public UUID getMediaId() {
    return mediaId;
  }

  public Long getMediaGeneration() {
    return mediaGeneration;
  }

  public OffsetDateTime getCapturedAt() {
    return capturedAt;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }
}
