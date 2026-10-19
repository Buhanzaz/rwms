package dev.buhanzaz.rwms.logistics.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** One recoverable task cancellation or asset-lease release belonging to an outcome receipt. */
@Entity
@Table(name = "inventory_outcome_task_action")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryOutcomeTaskAction {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "receipt_id", nullable = false)
  private UUID receiptId;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, length = 32)
  private InventoryOutcomeTaskTargetType targetType;

  @Column(name = "target_id", nullable = false)
  private UUID targetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private InventoryOutcomeTaskActionState state;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  public static InventoryOutcomeTaskAction pending(
      UUID receiptId, InventoryOutcomeTaskTargetType targetType, UUID targetId) {
    InventoryOutcomeTaskAction action = new InventoryOutcomeTaskAction();
    action.receiptId = Objects.requireNonNull(receiptId, "receiptId");
    action.targetType = Objects.requireNonNull(targetType, "targetType");
    action.targetId = Objects.requireNonNull(targetId, "targetId");
    action.state = InventoryOutcomeTaskActionState.PENDING;
    action.createdAt = now();
    action.updatedAt = action.createdAt;
    return action;
  }

  /** Freezes the task outcome so a retry never repeats a completed local decision. */
  public void finish(InventoryOutcomeTaskActionState outcome) {
    if (outcome == null || outcome == InventoryOutcomeTaskActionState.PENDING) {
      throw new IllegalArgumentException("Terminal inventory task outcome is required");
    }
    if (state != InventoryOutcomeTaskActionState.PENDING) {
      if (state != outcome) {
        throw new IllegalStateException("Inventory task outcome is already frozen");
      }
      return;
    }
    state = outcome;
    completedAt = now();
    updatedAt = completedAt;
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
        && Objects.equals(id, ((InventoryOutcomeTaskAction) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
