package dev.buhanzaz.rwms.asset.disposition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/** Immutable frozen cabin-content line of a prepared disposition. */
@Entity
@Table(name = "property_disposition_fence_content")
public class PropertyDispositionFenceContent {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "decision_id", nullable = false)
  private UUID decisionId;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "source_balance_id", nullable = false)
  private UUID sourceBalanceId;

  @Column(name = "expected_balance_version", nullable = false)
  private long expectedBalanceVersion;

  @Column(name = "current_quantity", nullable = false)
  private long currentQuantity;

  @Column(name = "move_quantity", nullable = false)
  private long moveQuantity;

  @Column(name = "disposition_quantity", nullable = false)
  private long dispositionQuantity;

  @Column(name = "hold_id", nullable = false)
  private UUID holdId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected PropertyDispositionFenceContent() {}

  public static PropertyDispositionFenceContent create(
      UUID decisionId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long expectedBalanceVersion,
      long currentQuantity,
      long moveQuantity,
      UUID holdId) {
    if (decisionId == null
        || equipmentId == null
        || sourceBalanceId == null
        || holdId == null
        || expectedBalanceVersion < 0
        || currentQuantity < 1
        || moveQuantity < 0
        || moveQuantity > currentQuantity) {
      throw new IllegalArgumentException("Property disposition content is invalid");
    }
    PropertyDispositionFenceContent value = new PropertyDispositionFenceContent();
    value.decisionId = decisionId;
    value.equipmentId = equipmentId;
    value.sourceBalanceId = sourceBalanceId;
    value.expectedBalanceVersion = expectedBalanceVersion;
    value.currentQuantity = currentQuantity;
    value.moveQuantity = moveQuantity;
    value.dispositionQuantity = Math.subtractExact(currentQuantity, moveQuantity);
    value.holdId = holdId;
    value.createdAt = now();
    return value;
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = now();
  }

  public UUID getId() { return id; }
  public UUID getDecisionId() { return decisionId; }
  public UUID getEquipmentId() { return equipmentId; }
  public UUID getSourceBalanceId() { return sourceBalanceId; }
  public long getExpectedBalanceVersion() { return expectedBalanceVersion; }
  public long getCurrentQuantity() { return currentQuantity; }
  public long getMoveQuantity() { return moveQuantity; }
  public long getDispositionQuantity() { return dispositionQuantity; }
  public UUID getHoldId() { return holdId; }
  public OffsetDateTime getCreatedAt() { return createdAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((PropertyDispositionFenceContent) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
