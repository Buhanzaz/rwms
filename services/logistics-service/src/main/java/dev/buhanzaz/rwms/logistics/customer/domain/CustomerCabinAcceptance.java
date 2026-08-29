package dev.buhanzaz.rwms.logistics.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** Immutable signed customer acceptance of one arrived cabin in one booking. */
@Entity
@Table(
    name = "customer_cabin_acceptance",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_customer_cabin_acceptance_booking_cabin",
          columnNames = {"booking_id", "cabin_unit_id"}),
      @UniqueConstraint(
          name = "uk_customer_cabin_acceptance_subject_key",
          columnNames = {"customer_subject_id", "idempotency_key"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerCabinAcceptance {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "customer_subject_id", nullable = false)
  private UUID customerSubjectId;

  @Column(name = "booking_id", nullable = false)
  private UUID bookingId;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "cabin_unit_id", nullable = false)
  private UUID cabinUnitId;

  @Column(name = "shipment_document_id", nullable = false)
  private UUID shipmentDocumentId;

  @Column(name = "shipment_line_id", nullable = false)
  private UUID shipmentLineId;

  @Column(name = "driver_task_id", nullable = false)
  private UUID driverTaskId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "signature_json", nullable = false, columnDefinition = "text")
  private String signatureJson;

  @Column(name = "signature_point_count", nullable = false)
  private int signaturePointCount;

  @Column(name = "accepted_at", nullable = false)
  private OffsetDateTime acceptedAt;

  /** Creates acceptance only from a completed exact shipment task and canonical signature. */
  public static CustomerCabinAcceptance create(
      UUID customerSubjectId,
      UUID bookingId,
      UUID orderId,
      UUID warehouseId,
      UUID cabinUnitId,
      UUID shipmentDocumentId,
      UUID shipmentLineId,
      UUID driverTaskId,
      UUID idempotencyKey,
      String requestSha256,
      String signatureJson,
      int signaturePointCount,
      OffsetDateTime acceptedAt) {
    CustomerCabinAcceptance acceptance = new CustomerCabinAcceptance();
    acceptance.customerSubjectId = Objects.requireNonNull(customerSubjectId, "customerSubjectId");
    acceptance.bookingId = Objects.requireNonNull(bookingId, "bookingId");
    acceptance.orderId = Objects.requireNonNull(orderId, "orderId");
    acceptance.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    acceptance.cabinUnitId = Objects.requireNonNull(cabinUnitId, "cabinUnitId");
    acceptance.shipmentDocumentId =
        Objects.requireNonNull(shipmentDocumentId, "shipmentDocumentId");
    acceptance.shipmentLineId = Objects.requireNonNull(shipmentLineId, "shipmentLineId");
    acceptance.driverTaskId = Objects.requireNonNull(driverTaskId, "driverTaskId");
    acceptance.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    acceptance.requestSha256 = requireHash(requestSha256);
    acceptance.signatureJson = Objects.requireNonNull(signatureJson, "signatureJson");
    if (signaturePointCount < 1 || signaturePointCount > 8_192) {
      throw new IllegalArgumentException("signaturePointCount is invalid");
    }
    acceptance.signaturePointCount = signaturePointCount;
    acceptance.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
    return acceptance;
  }

  /** Returns whether an idempotent replay contains the exact canonical signature. */
  public boolean matchesRequest(String requestSha256) {
    return this.requestSha256.equals(requestSha256);
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
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
        && Objects.equals(id, ((CustomerCabinAcceptance) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
