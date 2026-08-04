package dev.buhanzaz.rwms.maintenance.disposition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/**
 * Immutable evidence of cabin contents observed when a disposition decision was opened.
 *
 * <p>The line deliberately stores display fields and the observed balance version instead of
 * referencing mutable catalog or balance projections.
 */
@Entity
@Table(name = "property_disposition_contents_snapshot_line")
public class PropertyDispositionContentSnapshotLine {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  private PropertyDispositionDecision decision;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "equipment_name", nullable = false, length = 255)
  private String equipmentName;

  @Column(name = "equipment_format", length = 512)
  private String equipmentFormat;

  @Column(name = "current_quantity", nullable = false)
  private long currentQuantity;

  @Column(name = "move_quantity", nullable = false)
  private long moveQuantity;

  @Column(name = "expected_balance_version", nullable = false)
  private long expectedBalanceVersion;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected PropertyDispositionContentSnapshotLine() {}

  static PropertyDispositionContentSnapshotLine snapshot(
      PropertyDispositionContentSnapshotLineDraft draft) {
    if (draft == null
        || draft.equipmentId() == null
        || draft.currentQuantity() <= 0
        || draft.moveQuantity() < 0
        || draft.moveQuantity() > draft.currentQuantity()
        || draft.expectedBalanceVersion() < 0) {
      throw new IllegalArgumentException("Cabin contents snapshot line is invalid");
    }
    PropertyDispositionContentSnapshotLine value = new PropertyDispositionContentSnapshotLine();
    value.equipmentId = draft.equipmentId();
    value.equipmentName = required(draft.equipmentName(), "Cabin contents equipment name", 255);
    value.equipmentFormat = optional(draft.equipmentFormat(), 512);
    value.currentQuantity = draft.currentQuantity();
    value.moveQuantity = draft.moveQuantity();
    value.expectedBalanceVersion = draft.expectedBalanceVersion();
    value.createdAt = now();
    return value;
  }

  void attachTo(PropertyDispositionDecision owner) {
    if (owner == null) {
      throw new IllegalArgumentException("Disposition decision is required");
    }
    if (decision != null && decision != owner) {
      throw new PropertyDispositionConflictException(
          "Cabin contents snapshot line already belongs to another decision");
    }
    decision = owner;
  }

  private static String required(String value, String field, int maximumLength) {
    String normalized = optional(value, maximumLength);
    if (normalized == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static String optional(String value, int maximumLength) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException("Disposition snapshot text is too long");
    }
    return normalized;
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getEquipmentId() {
    return equipmentId;
  }

  public String getEquipmentName() {
    return equipmentName;
  }

  public String getEquipmentFormat() {
    return equipmentFormat;
  }

  public long getCurrentQuantity() {
    return currentQuantity;
  }

  public long getMoveQuantity() {
    return moveQuantity;
  }

  public long getExpectedBalanceVersion() {
    return expectedBalanceVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> thisClass = effectiveClass(this);
    Class<?> otherClass = effectiveClass(other);
    if (thisClass != otherClass) return false;
    PropertyDispositionContentSnapshotLine value = (PropertyDispositionContentSnapshotLine) other;
    return id != null && Objects.equals(id, value.id);
  }

  @Override
  public final int hashCode() {
    return effectiveClass(this).hashCode();
  }

  @Override
  public String toString() {
    return "PropertyDispositionContentSnapshotLine{"
        + "id="
        + id
        + ", equipmentId="
        + equipmentId
        + ", currentQuantity="
        + currentQuantity
        + ", moveQuantity="
        + moveQuantity
        + '}';
  }

  private static Class<?> effectiveClass(Object value) {
    return value instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : value.getClass();
  }
}
