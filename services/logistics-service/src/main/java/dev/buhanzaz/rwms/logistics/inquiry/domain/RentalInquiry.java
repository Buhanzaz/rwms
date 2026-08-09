package dev.buhanzaz.rwms.logistics.inquiry.domain;

import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * JPA persistence model for Rental Inquiry in the logistics-owned database.
 */
@Entity
@Table(name = "rental_inquiry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalInquiry {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "conversation_id", nullable = false)
  private UUID conversationId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "client_id", nullable = false)
  private OrderClient client;

  @Column(name = "manager_id", nullable = false)
  private UUID managerId;

  @Column(name = "manager_display_name", nullable = false, length = 255)
  private String managerDisplayName;

  @Column(name = "manager_role", nullable = false, length = 32)
  private String managerRole;

  @Column(name = "warehouse_id")
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private RentalInquiryState state;

  @Column(name = "booked_order_id")
  private UUID bookedOrderId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "booked_at")
  private OffsetDateTime bookedAt;

  public static RentalInquiry create(
      UUID conversationId,
      OrderClient client,
      UUID managerId,
      String managerDisplayName,
      String managerRole,
      OffsetDateTime now) {
    RentalInquiry inquiry = new RentalInquiry();
    inquiry.conversationId = Objects.requireNonNull(conversationId, "conversationId");
    inquiry.client = Objects.requireNonNull(client, "client");
    inquiry.managerId = Objects.requireNonNull(managerId, "managerId");
    inquiry.managerDisplayName = requireText(managerDisplayName, 255, "managerDisplayName");
    inquiry.managerRole = requireRole(managerRole);
    inquiry.state = RentalInquiryState.ACTIVE;
    inquiry.createdAt = Objects.requireNonNull(now, "now");
    inquiry.updatedAt = now;
    return inquiry;
  }

  public void selectWarehouse(UUID nextWarehouseId, OffsetDateTime now) {
    requireActive();
    UUID required = Objects.requireNonNull(nextWarehouseId, "warehouseId");
    if (warehouseId != null && !warehouseId.equals(required)) {
      throw new IllegalStateException("Inquiry already belongs to another warehouse");
    }
    if (warehouseId == null) {
      warehouseId = required;
      updatedAt = now;
    }
  }

  public void markBooked(UUID orderId, OffsetDateTime now) {
    if (state == RentalInquiryState.BOOKED && Objects.equals(bookedOrderId, orderId)) return;
    requireActive();
    state = RentalInquiryState.BOOKED;
    bookedOrderId = Objects.requireNonNull(orderId, "orderId");
    bookedAt = Objects.requireNonNull(now, "now");
    updatedAt = now;
  }

  public void archive(OffsetDateTime now) {
    if (state != RentalInquiryState.ACTIVE) return;
    state = RentalInquiryState.ARCHIVED;
    updatedAt = Objects.requireNonNull(now, "now");
  }

  private void requireActive() {
    if (state != RentalInquiryState.ACTIVE) {
      throw new IllegalStateException("Rental inquiry is no longer active");
    }
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requireRole(String value) {
    if (!java.util.Set.of(
            "SYSTEM_ADMIN",
            "WMS_ADMIN",
            "WAREHOUSE_MANAGER",
            "RENTAL_MANAGER",
            "VIEWER")
        .contains(value)) {
      throw new IllegalArgumentException("managerRole is invalid");
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
        && Objects.equals(id, ((RentalInquiry) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
