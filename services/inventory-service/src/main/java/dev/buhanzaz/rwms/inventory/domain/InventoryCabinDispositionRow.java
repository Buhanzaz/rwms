package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable candidate and canonical decision for one finding in a disposition review. */
@Entity
@Table(name = "inventory_cabin_disposition_row")
@IdClass(InventoryCabinDispositionRow.Key.class)
public class InventoryCabinDispositionRow {
  @Id
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Id
  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "finding_revision", nullable = false)
  private long findingRevision;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_version", nullable = false)
  private long assetVersion;

  @Column(name = "display_canonical_number", nullable = false, length = 128)
  private String displayCanonicalNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "candidate_kind", nullable = false, length = 16)
  private InventoryCabinDispositionCandidateKind candidateKind;

  @Enumerated(EnumType.STRING)
  @Column(name = "disposition_kind", length = 16)
  private InventoryCabinDispositionKind dispositionKind;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "disposition_details", columnDefinition = "jsonb")
  private String dispositionDetails;

  protected InventoryCabinDispositionRow() {}

  /** Captures one exact finding candidate; ordinary local findings are decided immediately. */
  public static InventoryCabinDispositionRow candidate(
      UUID inventoryId,
      UUID findingId,
      long findingRevision,
      UUID assetId,
      long assetVersion,
      String displayCanonicalNumber,
      InventoryCabinDispositionCandidateKind candidateKind,
      String localDetails) {
    if (inventoryId == null
        || findingId == null
        || findingRevision < 0
        || assetId == null
        || assetVersion < 0
        || displayCanonicalNumber == null
        || displayCanonicalNumber.isBlank()
        || displayCanonicalNumber.trim().length() > 128
        || candidateKind == null) {
      throw new IllegalArgumentException("Inventory disposition candidate is incomplete");
    }
    InventoryCabinDispositionRow value = new InventoryCabinDispositionRow();
    value.inventoryId = inventoryId;
    value.findingId = findingId;
    value.findingRevision = findingRevision;
    value.assetId = assetId;
    value.assetVersion = assetVersion;
    value.displayCanonicalNumber = displayCanonicalNumber.trim();
    value.candidateKind = candidateKind;
    if (candidateKind == InventoryCabinDispositionCandidateKind.LOCAL) {
      value.decide(InventoryCabinDispositionKind.LOCAL, localDetails);
    } else if (candidateKind == InventoryCabinDispositionCandidateKind.PRESERVE) {
      value.decide(InventoryCabinDispositionKind.PRESERVE, localDetails);
    }
    return value;
  }

  /** Records the canonical disposition selected in the phase owning this candidate. */
  public void decide(InventoryCabinDispositionKind kind, String details) {
    if (kind == null || details == null || details.isBlank() || details.trim().length() > 262_144) {
      throw new IllegalArgumentException("Inventory disposition decision is incomplete");
    }
    String normalized = details.trim();
    if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
      throw new IllegalArgumentException("Inventory disposition details must be a JSON object");
    }
    if ((candidateKind == InventoryCabinDispositionCandidateKind.RETURN
            && kind != InventoryCabinDispositionKind.LOCAL)
        || (candidateKind == InventoryCabinDispositionCandidateKind.MISSING
            && kind == InventoryCabinDispositionKind.LOCAL)
        || (candidateKind == InventoryCabinDispositionCandidateKind.PRESERVE
            && kind != InventoryCabinDispositionKind.PRESERVE)
        || (candidateKind == InventoryCabinDispositionCandidateKind.LOCAL
            && kind != InventoryCabinDispositionKind.LOCAL)) {
      throw new IllegalArgumentException("Inventory disposition does not match its candidate");
    }
    dispositionKind = kind;
    dispositionDetails = normalized;
  }

  /** Advances only the exact finding revision after furniture observations are persisted. */
  public void carryForwardFurnitureRevision(
      long expectedFindingRevision,
      long nextFindingRevision,
      UUID currentAssetId,
      long currentAssetVersion) {
    if (findingRevision != expectedFindingRevision
        || nextFindingRevision < expectedFindingRevision
        || !Objects.equals(assetId, currentAssetId)
        || assetVersion != currentAssetVersion
        || dispositionKind == null
        || dispositionDetails == null) {
      throw new IllegalStateException("Inventory disposition cannot be carried forward");
    }
    findingRevision = nextFindingRevision;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public long getFindingRevision() {
    return findingRevision;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public long getAssetVersion() {
    return assetVersion;
  }

  public String getDisplayCanonicalNumber() {
    return displayCanonicalNumber;
  }

  public InventoryCabinDispositionCandidateKind getCandidateKind() {
    return candidateKind;
  }

  public InventoryCabinDispositionKind getDispositionKind() {
    return dispositionKind;
  }

  public String getDispositionDetails() {
    return dispositionDetails;
  }

  /** Composite persistence key for a finding scoped by its inventory session. */
  public static final class Key implements Serializable {
    private UUID inventoryId;
    private UUID findingId;

    public Key() {}

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Key key)) return false;
      return Objects.equals(inventoryId, key.inventoryId)
          && Objects.equals(findingId, key.findingId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(inventoryId, findingId);
    }
  }
}
