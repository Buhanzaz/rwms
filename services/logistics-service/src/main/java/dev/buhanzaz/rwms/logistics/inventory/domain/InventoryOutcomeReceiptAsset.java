package dev.buhanzaz.rwms.logistics.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** Immutable selected finding/asset pair retained with an inventory outcome receipt. */
@Entity
@Table(name = "inventory_outcome_receipt_asset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryOutcomeReceiptAsset {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "receipt_id", nullable = false)
  private UUID receiptId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "desired_status", nullable = false, length = 24)
  private String desiredStatus;

  @Column(name = "disposition_kind", nullable = false, length = 24)
  private String dispositionKind;

  @Column(name = "created_document_id")
  private UUID createdDocumentId;

  @Column(name = "created_line_id")
  private UUID createdLineId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public static InventoryOutcomeReceiptAsset create(
      UUID receiptId,
      UUID findingId,
      UUID assetId,
      String dispositionKind,
      String desiredStatus) {
    if (!Set.of("LOCAL", "SHIPMENT", "WRITE_OFF").contains(dispositionKind)
        || !Set.of("FREE", "REPAIR", "CAPITAL_REPAIR", "RENTED", "WRITE_OFF_PENDING")
            .contains(desiredStatus)
        || ("LOCAL".equals(dispositionKind)
            && !Set.of("FREE", "REPAIR", "CAPITAL_REPAIR").contains(desiredStatus))
        || ("SHIPMENT".equals(dispositionKind) && !"RENTED".equals(desiredStatus))
        || ("WRITE_OFF".equals(dispositionKind)
            && !"WRITE_OFF_PENDING".equals(desiredStatus))) {
      throw new IllegalArgumentException("Unsupported completed inventory status");
    }
    InventoryOutcomeReceiptAsset value = new InventoryOutcomeReceiptAsset();
    value.receiptId = Objects.requireNonNull(receiptId, "receiptId");
    value.findingId = Objects.requireNonNull(findingId, "findingId");
    value.assetId = Objects.requireNonNull(assetId, "assetId");
    value.dispositionKind = dispositionKind;
    value.desiredStatus = desiredStatus;
    value.createdAt = now();
    return value;
  }

  /** Attaches the exact document and line created or reused for this disposition marker. */
  public void attachDocument(UUID documentId, UUID lineId) {
    if ("WRITE_OFF".equals(dispositionKind)) {
      throw new IllegalStateException("WRITE_OFF disposition cannot create a logistics document");
    }
    UUID requiredDocumentId = Objects.requireNonNull(documentId, "documentId");
    UUID requiredLineId = Objects.requireNonNull(lineId, "lineId");
    if ((createdDocumentId != null && !createdDocumentId.equals(requiredDocumentId))
        || (createdLineId != null && !createdLineId.equals(requiredLineId))) {
      throw new IllegalStateException("Inventory disposition document is already frozen");
    }
    createdDocumentId = requiredDocumentId;
    createdLineId = requiredLineId;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
        && Objects.equals(id, ((InventoryOutcomeReceiptAsset) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
