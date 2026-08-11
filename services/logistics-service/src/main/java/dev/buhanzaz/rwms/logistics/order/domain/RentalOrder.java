package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned rental order including its client, fulfillment state and concrete delivery
 * acceptance facts.
 */
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

  @Column(name = "delivery_address", length = 1_000)
  private String deliveryAddress;

  @Column(name = "latitude", precision = 9, scale = 6)
  private BigDecimal latitude;

  @Column(name = "longitude", precision = 10, scale = 6)
  private BigDecimal longitude;

  @Column(name = "contact_phone", length = 32)
  private String contactPhone;

  @Column(name = "comment", length = 2_000)
  private String comment;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "rental_order_desired_delivery_window",
      joinColumns = @JoinColumn(name = "order_id", nullable = false))
  @OrderColumn(name = "position")
  @BatchSize(size = 100)
  private List<DesiredDeliveryWindow> desiredDeliveryWindows = new ArrayList<>();

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "rental_order_additional_contact",
      joinColumns = @JoinColumn(name = "order_id", nullable = false))
  @OrderColumn(name = "position")
  @BatchSize(size = 100)
  private List<AdditionalContact> additionalContacts = new ArrayList<>();

  @Column(name = "creation_idempotency_key", nullable = false)
  private UUID creationIdempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "creation_request_sha256", nullable = false, length = 64)
  private String creationRequestSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates a draft with manager-owned primary contact facts but no client-confirmed delivery facts. */
  public static RentalOrder create(
      String orderNumber,
      OrderClient client,
      UUID managerId,
      String managerDisplayName,
      UUID createdBySubjectId,
      String createdByDisplayName,
      String createdByRole,
      String contactPhone,
      String comment,
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
    order.createdAt = now();
    order.updatedAt = order.createdAt;
    order.replaceManagerOrderDetails(contactPhone, comment);
    order.creationIdempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    order.creationRequestSha256 = requireHash(requestSha256);
    return order;
  }

  public void selectWarehouse(UUID nextWarehouseId) {
    requireDraft();
    warehouseId = Objects.requireNonNull(nextWarehouseId, "nextWarehouseId");
    touch();
  }

  public boolean changeClient(OrderClient nextClient) {
    requireEditable();
    OrderClient requiredClient = Objects.requireNonNull(nextClient, "nextClient");
    if (Objects.equals(client.getId(), requiredClient.getId())) return false;
    client = requiredClient;
    touch();
    return true;
  }

  /**
   * Replaces manager-entered primary contact and comment facts without changing delivery details
   * collected from the client presentation.
   *
   * <p>Every field may remain absent while the order is a draft. The main contact becomes mandatory
   * before the order is saved.
   */
  public boolean replaceManagerOrderDetails(String nextContactPhone, String nextComment) {
    requireEditable();
    String normalizedPhone = PhoneNumberNormalizer.normalizeOptional(nextContactPhone);
    String normalizedComment = optionalText(nextComment, 2_000, "comment");
    if (Objects.equals(contactPhone, normalizedPhone) && Objects.equals(comment, normalizedComment)) {
      return false;
    }
    contactPhone = normalizedPhone;
    comment = normalizedComment;
    touch();
    return true;
  }

  /**
   * Replaces the client-confirmed delivery location and order-owned additional contacts after a
   * normal presentation confirmation. The primary contact and manager comment remain untouched.
   */
  public boolean replaceClientDeliveryDetails(
      String nextDeliveryAddress,
      BigDecimal nextLatitude,
      BigDecimal nextLongitude,
      List<AdditionalContact> nextAdditionalContacts) {
    requireEditable();
    String normalizedAddress = requireText(nextDeliveryAddress, 1_000, "deliveryAddress");
    Coordinates coordinates = coordinates(nextLatitude, nextLongitude);
    List<AdditionalContact> normalizedContacts = additionalContacts(nextAdditionalContacts);
    if (Objects.equals(deliveryAddress, normalizedAddress)
        && equalDecimal(latitude, coordinates.latitude())
        && equalDecimal(longitude, coordinates.longitude())
        && additionalContacts.equals(normalizedContacts)) {
      return false;
    }
    deliveryAddress = normalizedAddress;
    latitude = coordinates.latitude();
    longitude = coordinates.longitude();
    additionalContacts.clear();
    additionalContacts.addAll(normalizedContacts);
    touch();
    return true;
  }

  /**
   * Replaces the single client-selected receiving window after a normal presentation confirmation.
   * Existing client-confirmed delivery details, primary phone and comment remain unchanged.
   */
  public boolean replaceClientDesiredDeliveryWindow(DesiredDeliveryWindow nextWindow) {
    requireEditable();
    DesiredDeliveryWindow requiredWindow = Objects.requireNonNull(nextWindow, "desiredDeliveryWindow");
    if (desiredDeliveryWindows.size() == 1 && desiredDeliveryWindows.getFirst().equals(requiredWindow)) {
      return false;
    }
    desiredDeliveryWindows.clear();
    desiredDeliveryWindows.add(requiredWindow);
    touch();
    return true;
  }

  /** Returns the ordered order-owned contacts without exposing the mutable JPA collection. */
  public List<AdditionalContact> getAdditionalContacts() {
    return List.copyOf(additionalContacts);
  }

  /** Returns client-requested windows without exposing the mutable JPA collection. */
  public List<DesiredDeliveryWindow> getDesiredDeliveryWindows() {
    return List.copyOf(desiredDeliveryWindows);
  }

  public void touch() {
    requireEditable();
    updatedAt = nextUpdatedAt();
  }

  public void cancel() {
    requireDraft();
    status = RentalOrderStatus.CANCELLED;
    updatedAt = nextUpdatedAt();
  }

  public void saveForFulfillment() {
    requireDraft();
    if (warehouseId == null) {
      throw new IllegalStateException("Order warehouse is required");
    }
    requireFulfillmentDetails();
    status = RentalOrderStatus.SAVED;
    updatedAt = nextUpdatedAt();
  }

  /**
   * Fulfils a saved order without retroactively requiring V42 delivery facts from historical rows;
   * every order saved after V42 has already passed {@link #requireFulfillmentDetails()}.
   */
  public boolean fulfill() {
    if (status == RentalOrderStatus.FULFILLED) return false;
    if (status != RentalOrderStatus.SAVED) {
      throw new IllegalStateException("Order cannot be fulfilled in its current state");
    }
    // New saves already enforce delivery details. Legacy SAVED rows remain fulfillable after the
    // additive V42 migration without fabricating historical dates or contact facts.
    status = RentalOrderStatus.FULFILLED;
    updatedAt = nextUpdatedAt();
    return true;
  }

  /** Records an extension mutation while at least one cabin is already on rent. */
  public void recordRentalTermExtension() {
    if (status != RentalOrderStatus.SAVED && status != RentalOrderStatus.FULFILLED) {
      throw new IllegalStateException("Rental terms cannot be extended in the current order state");
    }
    updatedAt = nextUpdatedAt();
  }

  public boolean close() {
    if (status == RentalOrderStatus.CLOSED) return false;
    if (status != RentalOrderStatus.FULFILLED) {
      throw new IllegalStateException("Order cannot be closed in its current state");
    }
    status = RentalOrderStatus.CLOSED;
    updatedAt = nextUpdatedAt();
    return true;
  }

  public void requireDraft() {
    if (status != RentalOrderStatus.DRAFT) {
      throw new IllegalStateException("Order is not editable");
    }
  }

  /** Enforces that the normal client confirmation supplied a receiving preference before shipment. */
  public void requireDesiredDeliveryWindows() {
    if (desiredDeliveryWindows.isEmpty()) {
      throw new IllegalStateException(
          "Desired delivery windows are required before shipment scheduling");
    }
  }

  /**
   * A saved booking remains editable only while its linked rental shipment is still an untouched
   * draft. The shipment-specific guard lives in the service layer, where the document and
   * furniture-task locks are available.
   */
  public void requireEditable() {
    if (status != RentalOrderStatus.DRAFT && status != RentalOrderStatus.SAVED) {
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

  private static String optionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
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

  /** Verifies the client-confirmed address and manager primary phone required before fulfillment. */
  public void requireFulfillmentDetails() {
    if (deliveryAddress == null || contactPhone == null) {
      throw new IllegalStateException(
          "Order delivery address and contact phone are required");
    }
  }

  private static Coordinates coordinates(BigDecimal latitude, BigDecimal longitude) {
    if ((latitude == null) != (longitude == null)) {
      throw new IllegalArgumentException("latitude and longitude must be provided together");
    }
    if (latitude == null) return new Coordinates(null, null);
    if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
        || latitude.compareTo(BigDecimal.valueOf(90)) > 0
        || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
        || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
      throw new IllegalArgumentException("coordinates are out of range");
    }
    return new Coordinates(latitude, longitude);
  }

  private static List<AdditionalContact> additionalContacts(List<AdditionalContact> values) {
    if (values == null || values.isEmpty()) return List.of();
    if (values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("additionalContacts are invalid");
    }
    return List.copyOf(values);
  }

  private static boolean equalDecimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
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
    return thisClass == otherClass && id != null && Objects.equals(id, ((RentalOrder) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  /** Validated coordinate pair used only while applying a delivery draft. */
  private record Coordinates(BigDecimal latitude, BigDecimal longitude) {}
}
