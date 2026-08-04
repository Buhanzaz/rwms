package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "presentation_unit_hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PresentationUnitHold {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "presentation_id", nullable = false)
  private UUID presentationId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private PresentationUnitHoldState state;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "order_id")
  private UUID orderId;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "created_by_role", nullable = false, length = 32)
  private String createdByRole;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "ended_at")
  private OffsetDateTime endedAt;

  public static PresentationUnitHold create(
      UUID presentationId,
      UUID rentalItemId,
      UUID warehouseId,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      OffsetDateTime now) {
    if (!Objects.requireNonNull(expiresAt, "expiresAt").isAfter(now)) {
      throw new IllegalArgumentException("expiresAt must be in the future");
    }
    PresentationUnitHold hold = new PresentationUnitHold();
    hold.presentationId = Objects.requireNonNull(presentationId, "presentationId");
    hold.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    hold.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    hold.state = PresentationUnitHoldState.ACTIVE;
    hold.expiresAt = expiresAt;
    hold.createdBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    hold.createdByRole = requireRole(actorRole);
    hold.createdAt = Objects.requireNonNull(now, "now");
    hold.updatedAt = now;
    return hold;
  }

  public boolean renew(OffsetDateTime nextExpiresAt, OffsetDateTime now) {
    requireActive();
    if (!nextExpiresAt.isAfter(now)) {
      throw new IllegalArgumentException("expiresAt must be in the future");
    }
    if (expiresAt.equals(nextExpiresAt)) return false;
    expiresAt = nextExpiresAt;
    updatedAt = now;
    return true;
  }

  public void transferTo(
      UUID targetPresentationId,
      OffsetDateTime nextExpiresAt,
      OffsetDateTime now) {
    requireActive();
    if (!expiresAt.isAfter(now)) {
      throw new IllegalStateException("Presentation hold has expired");
    }
    if (!nextExpiresAt.isAfter(now)) {
      throw new IllegalArgumentException("expiresAt must be in the future");
    }
    presentationId = Objects.requireNonNull(targetPresentationId, "targetPresentationId");
    expiresAt = nextExpiresAt;
    updatedAt = Objects.requireNonNull(now, "now");
  }

  public boolean expire(OffsetDateTime now) {
    if (state != PresentationUnitHoldState.ACTIVE || expiresAt.isAfter(now)) return false;
    end(PresentationUnitHoldState.EXPIRED, null, now);
    return true;
  }

  public boolean release(OffsetDateTime now) {
    if (state != PresentationUnitHoldState.ACTIVE) return false;
    end(PresentationUnitHoldState.RELEASED, null, now);
    return true;
  }

  public boolean convert(UUID nextOrderId, OffsetDateTime now) {
    requireActive();
    if (!expiresAt.isAfter(now)) {
      throw new IllegalStateException("Presentation hold has expired");
    }
    end(PresentationUnitHoldState.CONVERTED, Objects.requireNonNull(nextOrderId, "orderId"), now);
    return true;
  }

  public boolean isLiveAt(OffsetDateTime now) {
    return state == PresentationUnitHoldState.ACTIVE && expiresAt.isAfter(now);
  }

  private void requireActive() {
    if (state != PresentationUnitHoldState.ACTIVE) {
      throw new IllegalStateException("Presentation hold is not active");
    }
  }

  private void end(
      PresentationUnitHoldState nextState, UUID nextOrderId, OffsetDateTime now) {
    state = nextState;
    orderId = nextOrderId;
    endedAt = now;
    updatedAt = now;
  }

  private static String requireRole(String value) {
    if (value == null
        || !java.util.Set.of(
                "SYSTEM_ADMIN",
                "WMS_ADMIN",
                "WAREHOUSE_MANAGER",
                "RENTAL_MANAGER",
                "VIEWER")
            .contains(value)) {
      throw new IllegalArgumentException("actorRole is invalid");
    }
    return value;
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
        && Objects.equals(id, ((PresentationUnitHold) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
