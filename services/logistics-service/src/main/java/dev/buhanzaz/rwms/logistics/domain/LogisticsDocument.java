package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Service-local aggregate projection. Canonical cabin and equipment state
 * remains in asset-service; this entity persists only opaque references and
 * immutable logistics snapshots.
 */
@Entity
@Table(name = "logistics_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsDocument {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Enumerated(EnumType.STRING)
  @Column(name = "document_type", nullable = false, length = 16)
  private LogisticsDocumentType documentType;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 40)
  private LogisticsDocumentState state;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "destination_warehouse_id")
  private UUID destinationWarehouseId;

  @Column(name = "party_snapshot", length = 512)
  private String partySnapshot;

  @Column(name = "driver_snapshot", length = 512)
  private String driverSnapshot;

  @Column(name = "requested_by_subject_id", nullable = false)
  private UUID requestedBySubjectId;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static LogisticsDocument createReturn(
      UUID warehouseId, UUID subjectId, UUID correlationId) {
    return initialize(
        LogisticsDocumentType.RETURN,
        warehouseId,
        null,
        null,
        null,
        subjectId,
        correlationId);
  }

  public static LogisticsDocument createShipment(
      UUID warehouseId,
      String partySnapshot,
      String driverSnapshot,
      UUID subjectId,
      UUID correlationId) {
    return initialize(
        LogisticsDocumentType.SHIPMENT,
        warehouseId,
        null,
        requiredSnapshot(partySnapshot, "partySnapshot"),
        requiredSnapshot(driverSnapshot, "driverSnapshot"),
        subjectId,
        correlationId);
  }

  public static LogisticsDocument createTransfer(
      UUID warehouseId, UUID destinationWarehouseId, UUID subjectId, UUID correlationId) {
    requireId(destinationWarehouseId, "destinationWarehouseId");
    if (destinationWarehouseId.equals(warehouseId)) {
      throw new IllegalArgumentException("Transfer destination must differ from origin warehouse");
    }
    return initialize(
        LogisticsDocumentType.TRANSFER,
        warehouseId,
        destinationWarehouseId,
        null,
        null,
        subjectId,
        correlationId);
  }

  public void beginReturnRegistration() {
    transition(LogisticsDocumentType.RETURN, LogisticsDocumentState.DRAFT, LogisticsDocumentState.REGISTERING);
  }

  public void requireReturnInspection() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.REGISTERING,
        LogisticsDocumentState.INSPECTION_REQUIRED);
  }

  public void returnRegistrationConflict() {
    transition(LogisticsDocumentType.RETURN, LogisticsDocumentState.REGISTERING, LogisticsDocumentState.CONFLICT);
  }

  public void returnRegistrationRequiresReconciliation() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.REGISTERING,
        LogisticsDocumentState.RECONCILIATION_REQUIRED);
  }

  public void beginReturnAcceptance() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.INSPECTION_REQUIRED,
        LogisticsDocumentState.ACCEPTING);
  }

  public void acceptReturn() {
    transition(LogisticsDocumentType.RETURN, LogisticsDocumentState.ACCEPTING, LogisticsDocumentState.ACCEPTED);
  }

  public void returnAcceptanceConflict() {
    transition(LogisticsDocumentType.RETURN, LogisticsDocumentState.ACCEPTING, LogisticsDocumentState.CONFLICT);
  }

  public void returnAcceptanceRequiresReconciliation() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.ACCEPTING,
        LogisticsDocumentState.RECONCILIATION_REQUIRED);
  }

  public void beginReturnEstimate() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.INSPECTION_REQUIRED,
        LogisticsDocumentState.ESTIMATE_PENDING);
  }

  public void requestReturnEstimate() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.ESTIMATE_PENDING,
        LogisticsDocumentState.ESTIMATE_REQUESTED);
  }

  public void returnEstimateConflict() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.ESTIMATE_PENDING,
        LogisticsDocumentState.CONFLICT);
  }

  public void returnEstimateRequiresReconciliation() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.ESTIMATE_PENDING,
        LogisticsDocumentState.RECONCILIATION_REQUIRED);
  }

  public void beginShipmentPreparation() {
    transition(LogisticsDocumentType.SHIPMENT, LogisticsDocumentState.DRAFT, LogisticsDocumentState.PREPARING);
  }

  public void awaitShipmentConfirmation() {
    transition(
        LogisticsDocumentType.SHIPMENT,
        LogisticsDocumentState.PREPARING,
        LogisticsDocumentState.AWAITING_CONFIRMATION);
  }

  public void beginShipmentConfirmation() {
    transition(
        LogisticsDocumentType.SHIPMENT,
        LogisticsDocumentState.AWAITING_CONFIRMATION,
        LogisticsDocumentState.CONFIRMING_PREPARATION);
  }

  public void ship() {
    transition(
        LogisticsDocumentType.SHIPMENT,
        LogisticsDocumentState.CONFIRMING_PREPARATION,
        LogisticsDocumentState.SHIPPED);
  }

  public void beginShipmentCancellation() {
    boolean allowed =
        documentType == LogisticsDocumentType.SHIPMENT
            && (state == LogisticsDocumentState.DRAFT
                || state == LogisticsDocumentState.PREPARING
                || state == LogisticsDocumentState.AWAITING_CONFIRMATION);
    if (!allowed) {
      throw new IllegalStateException("Shipment cannot be cancelled in its current lifecycle state");
    }
    state = LogisticsDocumentState.CANCELLING;
  }

  public void cancelShipment() {
    transition(
        LogisticsDocumentType.SHIPMENT,
        LogisticsDocumentState.CANCELLING,
        LogisticsDocumentState.CANCELLED);
  }

  public void shipmentConflict() {
    if (documentType != LogisticsDocumentType.SHIPMENT
        || (state != LogisticsDocumentState.PREPARING
            && state != LogisticsDocumentState.AWAITING_CONFIRMATION
            && state != LogisticsDocumentState.CONFIRMING_PREPARATION
            && state != LogisticsDocumentState.CANCELLING)) {
      throw new IllegalStateException("Shipment cannot enter conflict in its current lifecycle state");
    }
    state = LogisticsDocumentState.CONFLICT;
  }

  public void shipmentRequiresReconciliation() {
    if (documentType != LogisticsDocumentType.SHIPMENT
        || (state != LogisticsDocumentState.PREPARING
            && state != LogisticsDocumentState.AWAITING_CONFIRMATION
            && state != LogisticsDocumentState.CONFIRMING_PREPARATION
            && state != LogisticsDocumentState.CANCELLING)) {
      throw new IllegalStateException("Shipment cannot require reconciliation in its current lifecycle state");
    }
    state = LogisticsDocumentState.RECONCILIATION_REQUIRED;
  }

  public void beginTransferDeparture() {
    if (documentType != LogisticsDocumentType.TRANSFER
        || (state != LogisticsDocumentState.DRAFT && state != LogisticsDocumentState.DEPARTING)) {
      throw new IllegalStateException("Transfer departure is not allowed in its current lifecycle state");
    }
    state = LogisticsDocumentState.DEPARTING;
    touch();
  }

  public void markTransferInTransit() {
    transition(
        LogisticsDocumentType.TRANSFER,
        LogisticsDocumentState.DEPARTING,
        LogisticsDocumentState.IN_TRANSIT);
  }

  public void beginTransferArrival() {
    if (documentType != LogisticsDocumentType.TRANSFER
        || (state != LogisticsDocumentState.IN_TRANSIT && state != LogisticsDocumentState.ARRIVING)) {
      throw new IllegalStateException("Transfer arrival is not allowed in its current lifecycle state");
    }
    state = LogisticsDocumentState.ARRIVING;
    touch();
  }

  public void completeTransfer() {
    transition(LogisticsDocumentType.TRANSFER, LogisticsDocumentState.ARRIVING, LogisticsDocumentState.COMPLETED);
  }

  public void transferConflict() {
    if (documentType != LogisticsDocumentType.TRANSFER
        || (state != LogisticsDocumentState.DEPARTING && state != LogisticsDocumentState.ARRIVING)) {
      throw new IllegalStateException("Transfer cannot enter conflict in its current lifecycle state");
    }
    state = LogisticsDocumentState.CONFLICT;
  }

  public void transferRequiresReconciliation() {
    if (documentType != LogisticsDocumentType.TRANSFER
        || (state != LogisticsDocumentState.DEPARTING && state != LogisticsDocumentState.ARRIVING)) {
      throw new IllegalStateException("Transfer cannot require reconciliation in its current lifecycle state");
    }
    state = LogisticsDocumentState.RECONCILIATION_REQUIRED;
  }

  public void cancel() {
    boolean allowed =
        (documentType == LogisticsDocumentType.RETURN && state == LogisticsDocumentState.DRAFT)
            || (documentType == LogisticsDocumentType.SHIPMENT
                && (state == LogisticsDocumentState.DRAFT
                    || state == LogisticsDocumentState.PREPARING
                    || state == LogisticsDocumentState.AWAITING_CONFIRMATION))
            || (documentType == LogisticsDocumentType.TRANSFER && state == LogisticsDocumentState.DRAFT);
    if (!allowed) {
      throw new IllegalStateException("Document cannot be cancelled in its current lifecycle state");
    }
    state = LogisticsDocumentState.CANCELLED;
  }

  public void requireReconciliation() {
    if (state == LogisticsDocumentState.ACCEPTED
        || state == LogisticsDocumentState.CANCELLED
        || state == LogisticsDocumentState.COMPLETED
        || state == LogisticsDocumentState.ESTIMATE_REQUESTED
        || state == LogisticsDocumentState.SHIPPED) {
      throw new IllegalStateException("Terminal document cannot enter reconciliation");
    }
    state = LogisticsDocumentState.RECONCILIATION_REQUIRED;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = currentTime();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    touch();
  }

  private static LogisticsDocument initialize(
      LogisticsDocumentType documentType,
      UUID warehouseId,
      UUID destinationWarehouseId,
      String partySnapshot,
      String driverSnapshot,
      UUID subjectId,
      UUID correlationId) {
    requireId(warehouseId, "warehouseId");
    requireId(subjectId, "subjectId");
    requireId(correlationId, "correlationId");
    LogisticsDocument document = new LogisticsDocument();
    document.documentType = documentType;
    document.state = LogisticsDocumentState.DRAFT;
    document.warehouseId = warehouseId;
    document.destinationWarehouseId = destinationWarehouseId;
    document.partySnapshot = partySnapshot;
    document.driverSnapshot = driverSnapshot;
    document.requestedBySubjectId = subjectId;
    document.correlationId = correlationId;
    return document;
  }

  private void transition(
      LogisticsDocumentType expectedType,
      LogisticsDocumentState expectedState,
      LogisticsDocumentState nextState) {
    if (documentType != expectedType || state != expectedState) {
      throw new IllegalStateException("Document lifecycle transition is not allowed");
    }
    state = nextState;
  }

  private static void requireId(UUID value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
  }

  private static OffsetDateTime currentTime() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private void touch() {
    OffsetDateTime now = currentTime();
    updatedAt = updatedAt != null && !now.isAfter(updatedAt) ? updatedAt.plus(1, ChronoUnit.MICROS) : now;
  }

  private static String requiredSnapshot(String value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    String normalized = value.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
    if (normalized.length() > 512) throw new IllegalArgumentException(field + " is too long");
    return normalized;
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
        && Objects.equals(id, ((LogisticsDocument) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
