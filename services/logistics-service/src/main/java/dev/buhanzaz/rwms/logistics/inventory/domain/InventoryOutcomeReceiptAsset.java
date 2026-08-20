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

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public static InventoryOutcomeReceiptAsset create(
      UUID receiptId, UUID findingId, UUID assetId, String desiredStatus) {
    if (!Set.of("FREE", "REPAIR", "CAPITAL_REPAIR").contains(desiredStatus)) {
      throw new IllegalArgumentException("Unsupported completed inventory status");
    }
    InventoryOutcomeReceiptAsset value = new InventoryOutcomeReceiptAsset();
    value.receiptId = Objects.requireNonNull(receiptId, "receiptId");
    value.findingId = Objects.requireNonNull(findingId, "findingId");
    value.assetId = Objects.requireNonNull(assetId, "assetId");
    value.desiredStatus = desiredStatus;
    value.createdAt = now();
    return value;
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
