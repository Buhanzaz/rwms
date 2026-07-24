package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inventory_validation_snapshot")
public class InventoryValidationSnapshot {
  @Id @Column(name = "inventory_id", nullable = false) private UUID inventoryId;
  @Column(name = "session_revision", nullable = false) private long sessionRevision;
  @Column(name = "validation_sha256", nullable = false, length = 64)
  private String validationSha256;

  @Column(name = "acknowledgement_sha256", nullable = false, length = 64)
  private String acknowledgementSha256;
  @Column(name = "validated_at", nullable = false) private OffsetDateTime validatedAt;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "snapshot_body", nullable = false, columnDefinition = "jsonb") private String snapshotBody;

  protected InventoryValidationSnapshot() {}
  public InventoryValidationSnapshot(UUID inventoryId, long revision, String validation,
      String acknowledgement, OffsetDateTime validatedAt, String snapshotBody) {
    this.inventoryId = inventoryId; sessionRevision = revision; validationSha256 = validation;
    acknowledgementSha256 = acknowledgement; this.validatedAt = validatedAt;
    this.snapshotBody = snapshotBody;
  }
  public long getSessionRevision() { return sessionRevision; }
  public String getValidationSha256() { return validationSha256; }
  public String getAcknowledgementSha256() { return acknowledgementSha256; }
  public String getSnapshotBody() { return snapshotBody; }
  public UUID getInventoryId() { return inventoryId; }
}
