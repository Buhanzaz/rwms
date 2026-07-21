package dev.buhanzaz.rwms.logistics.order.domain;

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
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "rental_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalOrder {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "order_number", nullable = false, length = 32)
  private String orderNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private RentalOrderStatus status;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "client_id", nullable = false)
  private OrderClient client;

  @Column(name = "manager_id", nullable = false)
  private UUID managerId;

  @Column(name = "manager_display_name", nullable = false, length = 255)
  private String managerDisplayName;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "created_by_display_name", nullable = false, length = 255)
  private String createdByDisplayName;

  @Column(name = "created_by_role", nullable = false, length = 32)
  private String createdByRole;

  @Column(name = "warehouse_id")
  private UUID warehouseId;

  @Column(name = "creation_idempotency_key", nullable = false)
  private UUID creationIdempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "creation_request_sha256", nullable = false, length = 64)
  private String creationRequestSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static RentalOrder create(
      String orderNumber,
      OrderClient client,
      UUID managerId,
      String managerDisplayName,
      UUID createdBySubjectId,
      String createdByDisplayName,
      String createdByRole,
      UUID idempotencyKey,
      String requestSha256) {
    RentalOrder order = new RentalOrder();
    order.orderNumber = requireText(orderNumber, 32, "orderNumber");
    order.status = RentalOrderStatus.DRAFT;
    order.client = Objects.requireNonNull(client, "client");
    order.managerId = Objects.requireNonNull(managerId, "managerId");
    order.managerDisplayName = requireText(managerDisplayName, 255, "managerDisplayName");
    order.createdBySubjectId = Objects.requireNonNull(createdBySubjectId, "createdBySubjectId");
    order.createdByDisplayName = requireText(createdByDisplayName, 255, "createdByDisplayName");
    order.createdByRole = requireText(createdByRole, 32, "createdByRole");
    order.creationIdempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    order.creationRequestSha256 = requireHash(requestSha256);
    order.createdAt = now();
    order.updatedAt = order.createdAt;
    return order;
  }

  public void selectWarehouse(UUID nextWarehouseId) {
    requireDraft();
    warehouseId = Objects.requireNonNull(nextWarehouseId, "nextWarehouseId");
    touch();
  }

  public boolean changeClient(OrderClient nextClient) {
    requireDraft();
    OrderClient requiredClient = Objects.requireNonNull(nextClient, "nextClient");
    if (Objects.equals(client.getId(), requiredClient.getId())) return false;
    client = requiredClient;
    touch();
    return true;
  }

  public void touch() {
    requireDraft();
    updatedAt = nextUpdatedAt();
  }

  public void cancel() {
    requireDraft();
    status = RentalOrderStatus.CANCELLED;
    updatedAt = nextUpdatedAt();
  }

  public void requireDraft() {
    if (status != RentalOrderStatus.DRAFT) {
      throw new IllegalStateException("Order is not editable");
    }
  }

  public boolean matchesCreationRequest(String requestSha256) {
    return creationRequestSha256.equals(requestSha256);
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private OffsetDateTime nextUpdatedAt() {
    OffsetDateTime candidate = now();
    return candidate.isAfter(updatedAt) ? candidate : updatedAt.plusNanos(1_000);
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
        && Objects.equals(id, ((RentalOrder) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
