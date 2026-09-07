package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable logistics-owned normal-return inspection evidence imported into one finding. */
@Entity
@Table(name = "inventory_return_inspection_import")
@IdClass(InventoryReturnInspectionImport.Key.class)
public class InventoryReturnInspectionImport {
  @Id
  @Column(name = "return_id", nullable = false)
  private UUID returnId;

  @Id
  @Column(name = "line_id", nullable = false)
  private UUID lineId;

  @Id
  @Column(name = "document_version", nullable = false)
  private long documentVersion;

  @Column(name = "estimate_id")
  private UUID estimateId;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "source_occurred_at", nullable = false)
  private OffsetDateTime sourceOccurredAt;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "arrived_at", nullable = false)
  private OffsetDateTime arrivedAt;

  @Column(name = "completed_at", nullable = false)
  private OffsetDateTime completedAt;

  @Column(name = "terminal_state", nullable = false, length = 32)
  private String terminalState;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_version", nullable = false)
  private long assetVersion;

  @Column(name = "asset_status", nullable = false, length = 48)
  private String assetStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "media_evidence", nullable = false, columnDefinition = "jsonb")
  private String mediaEvidence;

  @Column(name = "proof_sha256", nullable = false, length = 64)
  private String proofSha256;

  @Column(name = "imported_at", nullable = false)
  private OffsetDateTime importedAt;

  protected InventoryReturnInspectionImport() {}

  public static InventoryReturnInspectionImport imported(
      UUID returnId,
      UUID lineId,
      long documentVersion,
      UUID estimateId,
      UUID sourceEventId,
      OffsetDateTime sourceOccurredAt,
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      OffsetDateTime arrivedAt,
      OffsetDateTime completedAt,
      String terminalState,
      UUID assetId,
      long assetVersion,
      String assetStatus,
      String mediaEvidence,
      String proofSha256,
      OffsetDateTime importedAt) {
    if (returnId == null
        || lineId == null
        || documentVersion < 0
        || sourceEventId == null
        || sourceOccurredAt == null
        || inventoryId == null
        || findingId == null
        || warehouseId == null
        || arrivedAt == null
        || completedAt == null
        || completedAt.isBefore(arrivedAt)
        || !validTerminalStatus(terminalState, assetStatus)
        || assetId == null
        || assetVersion < 0
        || mediaEvidence == null
        || mediaEvidence.isBlank()
        || proofSha256 == null
        || !proofSha256.matches("^[0-9a-f]{64}$")
        || importedAt == null) {
      throw new IllegalArgumentException("Return inspection import evidence is incomplete");
    }
    InventoryReturnInspectionImport value = new InventoryReturnInspectionImport();
    value.returnId = returnId;
    value.lineId = lineId;
    value.documentVersion = documentVersion;
    value.estimateId = estimateId;
    value.sourceEventId = sourceEventId;
    value.sourceOccurredAt = sourceOccurredAt;
    value.inventoryId = inventoryId;
    value.findingId = findingId;
    value.warehouseId = warehouseId;
    value.arrivedAt = arrivedAt;
    value.completedAt = completedAt;
    value.terminalState = terminalState;
    value.assetId = assetId;
    value.assetVersion = assetVersion;
    value.assetStatus = assetStatus;
    value.mediaEvidence = mediaEvidence;
    value.proofSha256 = proofSha256;
    value.importedAt = importedAt;
    return value;
  }

  private static boolean validTerminalStatus(String terminalState, String assetStatus) {
    return "ACCEPTED".equals(terminalState) && "FREE".equals(assetStatus)
        || "ESTIMATE_REQUESTED".equals(terminalState)
            && "WAITING_ESTIMATE_CONFIRMATION".equals(assetStatus);
  }

  public UUID getReturnId() {
    return returnId;
  }

  public UUID getLineId() {
    return lineId;
  }

  public long getDocumentVersion() {
    return documentVersion;
  }

  public UUID getEstimateId() {
    return estimateId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getMediaEvidence() {
    return mediaEvidence;
  }

  public String getProofSha256() {
    return proofSha256;
  }

  public OffsetDateTime getImportedAt() {
    return importedAt;
  }

  /** Composite semantic idempotency identity for one immutable return-line revision. */
  public static final class Key implements Serializable {
    private UUID returnId;
    private UUID lineId;
    private long documentVersion;

    public Key() {}

    public Key(UUID returnId, UUID lineId, long documentVersion) {
      this.returnId = returnId;
      this.lineId = lineId;
      this.documentVersion = documentVersion;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Key value)) return false;
      return documentVersion == value.documentVersion
          && Objects.equals(returnId, value.returnId)
          && Objects.equals(lineId, value.lineId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(returnId, lineId, documentVersion);
    }
  }
}
