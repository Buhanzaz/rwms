package dev.buhanzaz.rwms.logistics.customer.domain;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemCategory;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** Immutable customer problem report for one arrived cabin and its exact shipment media owner. */
@Entity
@Table(
    name = "customer_cabin_problem",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_cabin_problem_subject_key",
            columnNames = {"customer_subject_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerCabinProblem {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

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

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 32)
  private CustomerCabinProblemCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "phase", nullable = false, length = 32)
  private CustomerCabinProblemPhase phase;

  @Column(name = "description", nullable = false, length = 2_000)
  private String description;

  @Column(name = "media_references_json", nullable = false, columnDefinition = "text")
  private String mediaReferencesJson;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "reported_at", nullable = false)
  private OffsetDateTime reportedAt;

  /** Creates a validated arrived-cabin problem from canonical ready-media references. */
  public static CustomerCabinProblem create(
      UUID customerSubjectId,
      UUID bookingId,
      UUID orderId,
      UUID warehouseId,
      UUID cabinUnitId,
      UUID shipmentDocumentId,
      UUID shipmentLineId,
      UUID driverTaskId,
      CustomerCabinProblemCategory category,
      CustomerCabinProblemPhase phase,
      String description,
      String mediaReferencesJson,
      UUID idempotencyKey,
      String requestSha256,
      OffsetDateTime reportedAt) {
    CustomerCabinProblem problem = new CustomerCabinProblem();
    problem.customerSubjectId = Objects.requireNonNull(customerSubjectId, "customerSubjectId");
    problem.bookingId = Objects.requireNonNull(bookingId, "bookingId");
    problem.orderId = Objects.requireNonNull(orderId, "orderId");
    problem.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    problem.cabinUnitId = Objects.requireNonNull(cabinUnitId, "cabinUnitId");
    problem.shipmentDocumentId =
        Objects.requireNonNull(shipmentDocumentId, "shipmentDocumentId");
    problem.shipmentLineId = Objects.requireNonNull(shipmentLineId, "shipmentLineId");
    problem.driverTaskId = Objects.requireNonNull(driverTaskId, "driverTaskId");
    problem.category = Objects.requireNonNull(category, "category");
    problem.phase = Objects.requireNonNull(phase, "phase");
    problem.description = required(description, 2_000, "description");
    problem.mediaReferencesJson =
        Objects.requireNonNull(mediaReferencesJson, "mediaReferencesJson");
    problem.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    problem.requestSha256 = requireHash(requestSha256);
    problem.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt");
    return problem;
  }

  /** Returns whether an idempotent replay contains the exact canonical report. */
  public boolean matchesRequest(String requestSha256) {
    return this.requestSha256.equals(requestSha256);
  }

  private static String required(String value, int maximum, String field) {
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
        && Objects.equals(id, ((CustomerCabinProblem) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
