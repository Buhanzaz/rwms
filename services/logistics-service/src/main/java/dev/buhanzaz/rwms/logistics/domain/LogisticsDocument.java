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
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/**
 * Service-local aggregate projection. Canonical cabin and equipment state remains in asset-service;
 * this entity persists only opaque references and immutable logistics snapshots.
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

  /** Opaque task-board worker identity selected for this document's driver work. */
  @Column(name = "driver_worker_id")
  private UUID driverWorkerId;

  /**
   * Opaque reference to the selected existing order client. Logistics keeps the immutable display
   * snapshot above; the client aggregate remains owned by the order module.
   */
  @Column(name = "client_id")
  private UUID clientId;

  /** Optional same-service worker task that moves transfer furniture. */
  @Column(name = "equipment_movement_task_id")
  private UUID equipmentMovementTaskId;

  @Column(name = "scheduled_date")
  private LocalDate scheduledDate;

  /**
   * Immutable instant when a normal return completed physical intake and became ready for
   * inspection. Inventory-created historical returns intentionally leave this value absent because
   * they bypass intake and estimate creation.
   */
  @Column(name = "return_arrived_at")
  private OffsetDateTime returnArrivedAt;

  /**
   * Historical physical column retained for old rows; new date-only logistics never reads or writes
   * it.
   */
  @Getter(AccessLevel.NONE)
  @Column(name = "scheduled_time")
  private java.time.LocalTime legacyScheduledTime;

  /** Historical absolute schedule column retained only for JPA validation and old rows. */
  @Getter(AccessLevel.NONE)
  @Column(name = "scheduled_at")
  private OffsetDateTime legacyScheduledAt;

  /** Opaque service-local reference used to join one rental lifecycle. */
  @Column(name = "rental_order_id")
  private UUID rentalOrderId;

  /** The shipment that produced a per-shipment rental return, when applicable. */
  @Column(name = "rental_shipment_id")
  private UUID rentalShipmentId;

  @Column(name = "requested_by_subject_id", nullable = false)
  private UUID requestedBySubjectId;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Completed inventory that displaced this document from active logistics state. */
  @Column(name = "inventory_superseded_by")
  private UUID inventorySupersededBy;

  @Column(name = "inventory_superseded_at")
  private OffsetDateTime inventorySupersededAt;

  @Column(name = "inventory_completed_at")
  private OffsetDateTime inventoryCompletedAt;

  @Column(name = "inventory_final_plan_version")
  private Long inventoryFinalPlanVersion;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "inventory_final_plan_sha256", length = 64)
  private String inventoryFinalPlanSha256;

  /** Completed inventory that created this historical document rather than superseding it. */
  @Column(name = "inventory_source_id")
  private UUID inventorySourceId;

  @Column(name = "inventory_source_finding_id")
  private UUID inventorySourceFindingId;

  @Column(name = "inventory_source_disposition_kind", length = 24)
  private String inventorySourceDispositionKind;

  @Column(name = "inventory_source_completed_at")
  private OffsetDateTime inventorySourceCompletedAt;

  @Column(name = "inventory_source_final_plan_version")
  private Long inventorySourceFinalPlanVersion;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "inventory_source_final_plan_sha256", length = 64)
  private String inventorySourceFinalPlanSha256;

  public static LogisticsDocument createReturn(
      UUID warehouseId, UUID subjectId, UUID correlationId) {
    return createReturn(warehouseId, null, null, subjectId, correlationId);
  }

  public static LogisticsDocument createReturn(
      UUID warehouseId, UUID clientId, String partySnapshot, UUID subjectId, UUID correlationId) {
    return createReturn(warehouseId, clientId, partySnapshot, null, subjectId, correlationId);
  }

  public static LogisticsDocument createReturn(
      UUID warehouseId,
      UUID clientId,
      String partySnapshot,
      String driverSnapshot,
      UUID subjectId,
      UUID correlationId) {
    return createReturn(
        warehouseId, clientId, partySnapshot, driverSnapshot, null, subjectId, correlationId);
  }

  /** Creates a return draft while retaining the stable selected driver identity when supplied. */
  public static LogisticsDocument createReturn(
      UUID warehouseId,
      UUID clientId,
      String partySnapshot,
      String driverSnapshot,
      UUID driverWorkerId,
      UUID subjectId,
      UUID correlationId) {
    LogisticsDocument document =
        initialize(
            LogisticsDocumentType.RETURN,
            warehouseId,
            null,
            optionalSnapshot(partySnapshot, "partySnapshot"),
            optionalSnapshot(driverSnapshot, "driverSnapshot"),
            subjectId,
            correlationId);
    document.clientId = clientId;
    document.driverWorkerId = driverWorkerId;
    return document;
  }

  public static LogisticsDocument createShipment(
      UUID warehouseId,
      String partySnapshot,
      String driverSnapshot,
      UUID subjectId,
      UUID correlationId) {
    return createShipment(
        warehouseId, null, partySnapshot, driverSnapshot, null, subjectId, correlationId);
  }

  public static LogisticsDocument createShipment(
      UUID warehouseId,
      UUID clientId,
      String partySnapshot,
      String driverSnapshot,
      UUID subjectId,
      UUID correlationId) {
    return createShipment(
        warehouseId, clientId, partySnapshot, driverSnapshot, null, subjectId, correlationId);
  }

  /** Creates a shipment draft with an optional stable task-board driver identity. */
  public static LogisticsDocument createShipment(
      UUID warehouseId,
      UUID clientId,
      String partySnapshot,
      String driverSnapshot,
      UUID driverWorkerId,
      UUID subjectId,
      UUID correlationId) {
    LogisticsDocument document =
        initialize(
            LogisticsDocumentType.SHIPMENT,
            warehouseId,
            null,
            requiredSnapshot(partySnapshot, "partySnapshot"),
            requiredSnapshot(driverSnapshot, "driverSnapshot"),
            subjectId,
            correlationId);
    document.clientId = clientId;
    document.driverWorkerId = driverWorkerId;
    return document;
  }

  public static LogisticsDocument createRentalOrderShipment(
      UUID warehouseId,
      UUID clientId,
      UUID rentalOrderId,
      String partySnapshot,
      UUID subjectId,
      UUID correlationId) {
    LogisticsDocument document =
        initialize(
            LogisticsDocumentType.SHIPMENT,
            warehouseId,
            null,
            requiredSnapshot(partySnapshot, "partySnapshot"),
            null,
            subjectId,
            correlationId);
    document.clientId = Objects.requireNonNull(clientId, "clientId");
    document.rentalOrderId = Objects.requireNonNull(rentalOrderId, "rentalOrderId");
    return document;
  }

  public static LogisticsDocument createRentalOrderReturn(
      UUID warehouseId,
      UUID clientId,
      UUID rentalOrderId,
      String partySnapshot,
      UUID subjectId,
      UUID correlationId) {
    return createRentalOrderReturn(
        warehouseId, clientId, rentalOrderId, null, partySnapshot, subjectId, correlationId);
  }

  public static LogisticsDocument createRentalOrderReturn(
      UUID warehouseId,
      UUID clientId,
      UUID rentalOrderId,
      UUID rentalShipmentId,
      String partySnapshot,
      UUID subjectId,
      UUID correlationId) {
    LogisticsDocument document =
        createReturn(
            warehouseId,
            Objects.requireNonNull(clientId, "clientId"),
            partySnapshot,
            null,
            subjectId,
            correlationId);
    document.rentalOrderId = Objects.requireNonNull(rentalOrderId, "rentalOrderId");
    document.rentalShipmentId = rentalShipmentId;
    return document;
  }

  /**
   * Creates an already-completed historical return from reviewed inventory evidence. It deliberately
   * skips driver, acceptance, estimate, hold and ordinary return workflow states.
   */
  public static LogisticsDocument createInventoryReturn(
      UUID warehouseId,
      UUID clientId,
      String clientSnapshot,
      LocalDate returnedOn,
      UUID inventoryId,
      UUID findingId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      UUID subjectId) {
    LogisticsDocument document =
        createReturn(
            warehouseId,
            Objects.requireNonNull(clientId, "clientId"),
            requiredSnapshot(clientSnapshot, "clientSnapshot"),
            subjectId,
            inventoryId);
    document.scheduledDate = Objects.requireNonNull(returnedOn, "returnedOn");
    document.state = LogisticsDocumentState.ACCEPTED;
    document.captureInventorySource(
        inventoryId,
        findingId,
        "LOCAL",
        inventoryCompletedAt,
        finalPlanVersion,
        finalPlanSha256);
    return document;
  }

  /**
   * Creates an already-shipped historical document from inventory evidence without driver, hold,
   * task or stock-allocation workflow.
   */
  public static LogisticsDocument createInventoryShipment(
      UUID warehouseId,
      UUID clientId,
      String clientSnapshot,
      LocalDate departedOn,
      UUID inventoryId,
      UUID findingId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      UUID subjectId) {
    LogisticsDocument document =
        initialize(
            LogisticsDocumentType.SHIPMENT,
            warehouseId,
            null,
            requiredSnapshot(clientSnapshot, "clientSnapshot"),
            null,
            subjectId,
            inventoryId);
    document.clientId = Objects.requireNonNull(clientId, "clientId");
    document.scheduledDate = Objects.requireNonNull(departedOn, "departedOn");
    document.state = LogisticsDocumentState.SHIPPED;
    document.captureInventorySource(
        inventoryId,
        findingId,
        "SHIPMENT",
        inventoryCompletedAt,
        finalPlanVersion,
        finalPlanSha256);
    return document;
  }

  /** Returns whether this historical document is the exact reusable fact for one plan outcome. */
  public boolean matchesInventorySource(
      UUID inventoryId,
      UUID findingId,
      String dispositionKind,
      long finalPlanVersion,
      String finalPlanSha256) {
    return Objects.equals(inventorySourceId, inventoryId)
        && Objects.equals(inventorySourceFindingId, findingId)
        && Objects.equals(inventorySourceDispositionKind, dispositionKind)
        && Objects.equals(inventorySourceFinalPlanVersion, finalPlanVersion)
        && Objects.equals(inventorySourceFinalPlanSha256, finalPlanSha256);
  }

  /** Refreshes the live client projection while a rental shipment is still a draft. */
  public boolean synchronizeRentalOrderParty(UUID nextClientId, String nextPartySnapshot) {
    if (documentType != LogisticsDocumentType.SHIPMENT
        || rentalOrderId == null
        || state != LogisticsDocumentState.DRAFT) {
      throw new IllegalStateException("Rental shipment draft is not editable");
    }
    UUID requiredClientId = Objects.requireNonNull(nextClientId, "clientId");
    String requiredPartySnapshot = requiredSnapshot(nextPartySnapshot, "partySnapshot");
    if (Objects.equals(clientId, requiredClientId)
        && Objects.equals(partySnapshot, requiredPartySnapshot)) {
      return false;
    }
    clientId = requiredClientId;
    partySnapshot = requiredPartySnapshot;
    touch();
    return true;
  }

  /** Bumps the draft document projection when its order-backed lines changed. */
  public void touchRentalOrderDraft() {
    if (documentType != LogisticsDocumentType.SHIPMENT
        || rentalOrderId == null
        || state != LogisticsDocumentState.DRAFT) {
      throw new IllegalStateException("Rental shipment draft is not editable");
    }
    touch();
  }

  /** Creates shared warehouse-driver work without a planned or display-only driver identity. */
  public static LogisticsDocument createTransfer(
      UUID warehouseId,
      UUID destinationWarehouseId,
      LocalDate scheduledDate,
      UUID subjectId,
      UUID correlationId) {
    requireId(destinationWarehouseId, "destinationWarehouseId");
    if (destinationWarehouseId.equals(warehouseId)) {
      throw new IllegalArgumentException("Transfer destination must differ from origin warehouse");
    }
    LogisticsDocument document =
        initialize(
            LogisticsDocumentType.TRANSFER,
            warehouseId,
            destinationWarehouseId,
            null,
            null,
            subjectId,
            correlationId);
    document.scheduledDate = Objects.requireNonNull(scheduledDate, "scheduledDate");
    return document;
  }

  public void linkEquipmentMovementTask(UUID taskId) {
    if (documentType != LogisticsDocumentType.TRANSFER || taskId == null) {
      throw new IllegalArgumentException("Transfer equipment movement task is required");
    }
    if (equipmentMovementTaskId != null && !equipmentMovementTaskId.equals(taskId)) {
      throw new IllegalStateException("Transfer already has a different equipment movement task");
    }
    equipmentMovementTaskId = taskId;
  }

  public void beginReturnRegistration() {
    requireSchedule("Return pickup");
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.DRAFT,
        LogisticsDocumentState.REGISTERING);
  }

  public void scheduleReturn(String driver, LocalDate date) {
    scheduleReturn(driver, null, date);
  }

  /** Plans a return pickup for the selected task-board worker. */
  public void scheduleReturn(String driver, UUID workerId, LocalDate date) {
    if (documentType != LogisticsDocumentType.RETURN || state != LogisticsDocumentState.DRAFT) {
      throw new IllegalStateException("Return pickup cannot be scheduled in its current state");
    }
    driverSnapshot = requiredSnapshot(driver, "driverSnapshot");
    driverWorkerId = workerId;
    scheduledDate = Objects.requireNonNull(date, "scheduledDate");
    touch();
  }

  public void requireReturnInspection() {
    if (returnArrivedAt != null) {
      throw new IllegalStateException("Return physical arrival is already recorded");
    }
    OffsetDateTime arrivedAt = currentTime();
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.REGISTERING,
        LogisticsDocumentState.INSPECTION_REQUIRED);
    returnArrivedAt = arrivedAt;
  }

  public void returnRegistrationConflict() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.REGISTERING,
        LogisticsDocumentState.CONFLICT);
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
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.ACCEPTING,
        LogisticsDocumentState.ACCEPTED);
  }

  public void returnAcceptanceConflict() {
    transition(
        LogisticsDocumentType.RETURN,
        LogisticsDocumentState.ACCEPTING,
        LogisticsDocumentState.CONFLICT);
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
    requireSchedule("Shipment");
    transition(
        LogisticsDocumentType.SHIPMENT,
        LogisticsDocumentState.DRAFT,
        LogisticsDocumentState.PREPARING);
  }

  public void scheduleShipment(String driver, LocalDate date) {
    scheduleShipment(driver, null, date);
  }

  /** Plans a shipment for the selected task-board worker. */
  public void scheduleShipment(String driver, UUID workerId, LocalDate date) {
    boolean allowed =
        documentType == LogisticsDocumentType.SHIPMENT
            && (state == LogisticsDocumentState.DRAFT
                || state == LogisticsDocumentState.AWAITING_CONFIRMATION);
    if (!allowed) {
      throw new IllegalStateException("Shipment cannot be scheduled in its current state");
    }
    driverSnapshot = requiredSnapshot(driver, "driverSnapshot");
    driverWorkerId = workerId;
    scheduledDate = Objects.requireNonNull(date, "scheduledDate");
    touch();
  }

  /**
   * Moves the agreed calendar date of a complete pre-start driver trip. The task-board execution
   * fence remains authoritative for whether the external task is still movable; this transition
   * prevents a successful whole-trip move from leaving the logistics document on a different date.
   */
  public void reschedulePreStartTrip(LocalDate date) {
    requirePreStartTripReschedule();
    LocalDate target = Objects.requireNonNull(date, "scheduledDate");
    if (!target.equals(scheduledDate)) {
      scheduledDate = target;
      touch();
    }
  }

  /**
   * Applies the local execution fence without changing the date. Callers can hold the document lock
   * across the task-board move and prevent a stale WAITING entry from moving an already started
   * logistics trip remotely.
   */
  public void requirePreStartTripReschedule() {
    boolean allowed =
        switch (documentType) {
          case SHIPMENT ->
              state == LogisticsDocumentState.DRAFT
                  || state == LogisticsDocumentState.PREPARING
                  || state == LogisticsDocumentState.AWAITING_CONFIRMATION;
          case RETURN, TRANSFER -> state == LogisticsDocumentState.DRAFT;
        };
    if (!allowed || scheduledDate == null) {
      throw new IllegalStateException("Document trip cannot be rescheduled after it starts");
    }
  }

  public void requireShipmentDepartureAllowed(OffsetDateTime currentTime) {
    requireShipmentDepartureAllowed(
        Objects.requireNonNull(currentTime, "currentTime").toLocalDate());
  }

  public void requireShipmentDepartureAllowed(LocalDate currentDate) {
    if (documentType != LogisticsDocumentType.SHIPMENT || scheduledDate == null) {
      throw new IllegalStateException("Shipment date is required");
    }
    if (scheduledDate.isAfter(Objects.requireNonNull(currentDate, "currentDate"))) {
      throw new IllegalStateException("Shipment date is in the future");
    }
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
      throw new IllegalStateException(
          "Shipment cannot be cancelled in its current lifecycle state");
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
      throw new IllegalStateException(
          "Shipment cannot enter conflict in its current lifecycle state");
    }
    state = LogisticsDocumentState.CONFLICT;
  }

  public void shipmentRequiresReconciliation() {
    if (documentType != LogisticsDocumentType.SHIPMENT
        || (state != LogisticsDocumentState.PREPARING
            && state != LogisticsDocumentState.AWAITING_CONFIRMATION
            && state != LogisticsDocumentState.CONFIRMING_PREPARATION
            && state != LogisticsDocumentState.CANCELLING)) {
      throw new IllegalStateException(
          "Shipment cannot require reconciliation in its current lifecycle state");
    }
    state = LogisticsDocumentState.RECONCILIATION_REQUIRED;
  }

  public void beginTransferDeparture() {
    if (documentType != LogisticsDocumentType.TRANSFER
        || (state != LogisticsDocumentState.DRAFT && state != LogisticsDocumentState.DEPARTING)) {
      throw new IllegalStateException(
          "Transfer departure is not allowed in its current lifecycle state");
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
        || (state != LogisticsDocumentState.IN_TRANSIT
            && state != LogisticsDocumentState.ARRIVING)) {
      throw new IllegalStateException(
          "Transfer arrival is not allowed in its current lifecycle state");
    }
    state = LogisticsDocumentState.ARRIVING;
    touch();
  }

  public void completeTransfer() {
    transition(
        LogisticsDocumentType.TRANSFER,
        LogisticsDocumentState.ARRIVING,
        LogisticsDocumentState.COMPLETED);
  }

  public void beginTransferCancellation() {
    transition(
        LogisticsDocumentType.TRANSFER,
        LogisticsDocumentState.DRAFT,
        LogisticsDocumentState.CANCELLING);
  }

  public void cancelTransfer() {
    transition(
        LogisticsDocumentType.TRANSFER,
        LogisticsDocumentState.CANCELLING,
        LogisticsDocumentState.CANCELLED);
  }

  public void transferConflict() {
    if (documentType != LogisticsDocumentType.TRANSFER
        || (state != LogisticsDocumentState.DRAFT
            && state != LogisticsDocumentState.DEPARTING
            && state != LogisticsDocumentState.ARRIVING
            && state != LogisticsDocumentState.CANCELLING)) {
      throw new IllegalStateException(
          "Transfer cannot enter conflict in its current lifecycle state");
    }
    state = LogisticsDocumentState.CONFLICT;
  }

  public void transferRequiresReconciliation() {
    if (documentType != LogisticsDocumentType.TRANSFER
        || (state != LogisticsDocumentState.DRAFT
            && state != LogisticsDocumentState.DEPARTING
            && state != LogisticsDocumentState.ARRIVING
            && state != LogisticsDocumentState.CANCELLING)) {
      throw new IllegalStateException(
          "Transfer cannot require reconciliation in its current lifecycle state");
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
            || (documentType == LogisticsDocumentType.TRANSFER
                && state == LogisticsDocumentState.DRAFT);
    if (!allowed) {
      throw new IllegalStateException(
          "Document cannot be cancelled in its current lifecycle state");
    }
    state = LogisticsDocumentState.CANCELLED;
  }

  /**
   * Makes a completed inventory the active truth without rewriting terminal transport history.
   * Every nonterminal document becomes cancelled even when physical execution had started; the
   * caller is responsible for cancelling its task-board work through the recoverable owner saga.
   */
  public void supersedeByCompletedInventory(
      UUID inventoryId, OffsetDateTime completedAt, long finalPlanVersion, String finalPlanSha256) {
    if (inventoryId == null
        || completedAt == null
        || finalPlanVersion < 1
        || finalPlanSha256 == null
        || !finalPlanSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Completed inventory source is invalid");
    }
    inventorySupersededBy = inventoryId;
    inventorySupersededAt = currentTime();
    inventoryCompletedAt = completedAt;
    inventoryFinalPlanVersion = finalPlanVersion;
    inventoryFinalPlanSha256 = finalPlanSha256;
    if (state != LogisticsDocumentState.ACCEPTED
        && state != LogisticsDocumentState.ESTIMATE_REQUESTED
        && state != LogisticsDocumentState.SHIPPED
        && state != LogisticsDocumentState.COMPLETED
        && state != LogisticsDocumentState.CANCELLED) {
      state = LogisticsDocumentState.CANCELLED;
    }
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

  private void captureInventorySource(
      UUID inventoryId,
      UUID findingId,
      String dispositionKind,
      OffsetDateTime completedAt,
      long finalPlanVersion,
      String finalPlanSha256) {
    if (!Set.of("LOCAL", "SHIPMENT").contains(dispositionKind)
        || completedAt == null
        || finalPlanVersion < 1
        || finalPlanSha256 == null
        || !finalPlanSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory document source is invalid");
    }
    inventorySourceId = Objects.requireNonNull(inventoryId, "inventoryId");
    inventorySourceFindingId = Objects.requireNonNull(findingId, "findingId");
    inventorySourceDispositionKind = dispositionKind;
    inventorySourceCompletedAt = completedAt;
    inventorySourceFinalPlanVersion = finalPlanVersion;
    inventorySourceFinalPlanSha256 = finalPlanSha256;
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
    updatedAt =
        updatedAt != null && !now.isAfter(updatedAt) ? updatedAt.plus(1, ChronoUnit.MICROS) : now;
  }

  private void requireSchedule(String subject) {
    if (driverSnapshot == null || scheduledDate == null) {
      throw new IllegalStateException(subject + " driver and date are required");
    }
  }

  private static String requiredSnapshot(String value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    String normalized = value.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
    if (normalized.length() > 512) throw new IllegalArgumentException(field + " is too long");
    return normalized;
  }

  private static String optionalSnapshot(String value, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > 512) {
      throw new IllegalArgumentException(field + " is too long");
    }
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
