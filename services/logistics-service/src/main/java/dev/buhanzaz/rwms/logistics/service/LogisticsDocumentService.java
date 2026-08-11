package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.AcceptReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReconcileRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentPlanRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.StartReturnEstimatesRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferArrivalPreflightView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns return, shipment and transfer document commands; it records local state and outbox intent
 * transactionally, then recovers external effects through durable workflow state.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LogisticsDocumentService {
  static final String RETURN_WAREHOUSE_IDENTITY =
      LogisticsDocumentEffectOperations.RETURN_WAREHOUSE_IDENTITY;
  static final String RETURN_MEDIA_OWNER_PROOF_REGISTER =
      LogisticsDocumentEffectOperations.RETURN_MEDIA_OWNER_PROOF_REGISTER;
  static final String RETURN_MEDIA_VALIDATE =
      LogisticsDocumentEffectOperations.RETURN_MEDIA_VALIDATE;
  static final String RETURN_ASSET_SETTLE_FREE =
      LogisticsDocumentEffectOperations.RETURN_ASSET_SETTLE_FREE;
  static final String RETURN_ASSET_SETTLE_ESTIMATE =
      LogisticsDocumentEffectOperations.RETURN_ASSET_SETTLE_ESTIMATE;
  static final String RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE =
      LogisticsDocumentEffectOperations.RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE;
  static final String RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT =
      LogisticsDocumentEffectOperations.RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT;
  static final String RETURN_ASSET_LEASE_RELEASE =
      LogisticsDocumentEffectOperations.RETURN_ASSET_LEASE_RELEASE;
  static final String SHIPMENT_ASSET_SNAPSHOT =
      LogisticsDocumentEffectOperations.SHIPMENT_ASSET_SNAPSHOT;
  static final String SHIPMENT_ASSET_LEASE_ACQUIRE =
      LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_ACQUIRE;
  static final String SHIPMENT_ASSET_CONFIRM =
      LogisticsDocumentEffectOperations.SHIPMENT_ASSET_CONFIRM;
  static final String SHIPMENT_ASSET_LEASE_RELEASE =
      LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_RELEASE;
  static final String SHIPMENT_HOLD_ACQUIRE_PREFIX =
      LogisticsDocumentEffectOperations.SHIPMENT_HOLD_ACQUIRE_PREFIX;
  static final String SHIPMENT_HOLD_COMMIT_PREFIX =
      LogisticsDocumentEffectOperations.SHIPMENT_HOLD_COMMIT_PREFIX;
  static final String SHIPMENT_HOLD_RELEASE_PREFIX =
      LogisticsDocumentEffectOperations.SHIPMENT_HOLD_RELEASE_PREFIX;
  static final String TRANSFER_ORIGIN_WAREHOUSE_IDENTITY =
      LogisticsDocumentEffectOperations.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY;
  static final String TRANSFER_DESTINATION_WAREHOUSE_IDENTITY =
      LogisticsDocumentEffectOperations.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY;
  static final String TRANSFER_ASSET_SNAPSHOT =
      LogisticsDocumentEffectOperations.TRANSFER_ASSET_SNAPSHOT;
  static final String TRANSFER_MAINTENANCE_PREPARE_DEPARTURE =
      LogisticsDocumentEffectOperations.TRANSFER_MAINTENANCE_PREPARE_DEPARTURE;
  static final String TRANSFER_ASSET_LEASE_ACQUIRE =
      LogisticsDocumentEffectOperations.TRANSFER_ASSET_LEASE_ACQUIRE;
  static final String TRANSFER_ASSET_DEPART =
      LogisticsDocumentEffectOperations.TRANSFER_ASSET_DEPART;
  static final String TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY =
      LogisticsDocumentEffectOperations.TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY;
  static final String TRANSFER_MEDIA_VALIDATE =
      LogisticsDocumentEffectOperations.TRANSFER_MEDIA_VALIDATE;
  static final String TRANSFER_ASSET_ARRIVAL_SNAPSHOT =
      LogisticsDocumentEffectOperations.TRANSFER_ASSET_ARRIVAL_SNAPSHOT;
  static final String TRANSFER_ASSET_ARRIVE =
      LogisticsDocumentEffectOperations.TRANSFER_ASSET_ARRIVE;
  static final String TRANSFER_ASSET_LEASE_RELEASE =
      LogisticsDocumentEffectOperations.TRANSFER_ASSET_LEASE_RELEASE;
  static final String TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL =
      LogisticsDocumentEffectOperations.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL;
  static final String TRANSFER_MEDIA_OWNER_PROOF_REGISTER =
      LogisticsDocumentEffectOperations.TRANSFER_MEDIA_OWNER_PROOF_REGISTER;
  static final String TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE =
      LogisticsDocumentEffectOperations.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE;

  private final LogisticsReturnDocumentCoordinator returnCoordinator;
  private final LogisticsShipmentDocumentCoordinator shipmentCoordinator;
  private final LogisticsTransferDocumentCoordinator transferCoordinator;
  private final LogisticsRentalOrderShipmentCoordinator rentalOrderShipmentCoordinator;
  private final LogisticsRentalOrderCompletionCoordinator rentalOrderCompletionCoordinator;
  private final LogisticsDocumentReconciliationCoordinator reconciliationCoordinator;
  private final LogisticsDocumentReadProjection readProjection;

  @Transactional
  public CreateResult createReturn(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateReturnRequest request) {
    return result(
        returnCoordinator.createReturn(subjectId, idempotencyKey, correlationId, request));
  }

  @Transactional
  public CreateResult createReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateReturnRequest request,
      AdmissionTicket admission) {
    return result(
        returnCoordinator.createReturn(
            subjectId, idempotencyKey, correlationId, request, admission));
  }

  @Transactional
  public CreateResult createShipment(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateShipmentRequest request) {
    return result(
        shipmentCoordinator.createShipment(subjectId, idempotencyKey, correlationId, request));
  }

  @Transactional
  public CreateResult createShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateShipmentRequest request,
      AdmissionTicket admission) {
    return result(
        shipmentCoordinator.createShipment(
            subjectId, idempotencyKey, correlationId, request, admission));
  }

  @Transactional
  public CreateResult createTransfer(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateTransferRequest request) {
    return result(
        transferCoordinator.createTransfer(subjectId, idempotencyKey, correlationId, request));
  }

  @Transactional
  public CreateResult createTransfer(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateTransferRequest request,
      AdmissionTicket admission) {
    return result(
        transferCoordinator.createTransfer(
            subjectId, idempotencyKey, correlationId, request, admission));
  }

  /**
   * Reads the durable replay for a partial rental shipment command. The order service calls this
   * before checking the mutable order version so a retry remains idempotent even when another
   * command has since changed that order version.
   */
  @Transactional
  public CreateResult replayRentalOrderShipment(
      UUID subjectId, UUID idempotencyKey, String checksum) {
    return resultOrNull(
        rentalOrderShipmentCoordinator.replayRentalOrderShipment(
            subjectId, idempotencyKey, checksum));
  }

  /**
   * Creates one date/driver-bearing draft for exactly the requested order cabins. The caller owns
   * the order row lock and has already validated the actor and expected order version. This command
   * deliberately stops at DRAFT so furniture tasks can be created before preparation.
   */
  @Transactional
  public CreateResult createRentalOrderShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations,
      CreateOrderRentalShipmentRequest request,
      String checksum) {
    return result(
        rentalOrderShipmentCoordinator.createRentalOrderShipment(
            subjectId, idempotencyKey, correlationId, order, reservations, request, checksum));
  }

  @Transactional
  public CreateResult createRentalOrderShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations,
      CreateOrderRentalShipmentRequest request,
      String checksum,
      AdmissionTicket admission) {
    return result(
        rentalOrderShipmentCoordinator.createRentalOrderShipment(
            subjectId,
            idempotencyKey,
            correlationId,
            order,
            reservations,
            request,
            checksum,
            admission));
  }

  /**
   * Checks whether saved-order edits are still safe. Every non-cancelled rental shipment must be an
   * untouched draft and must not have a furniture movement task; several such drafts are valid
   * because a single order can be split into batches.
   */
  public boolean isRentalOrderShipmentDraftEditable(UUID orderId) {
    return rentalOrderShipmentCoordinator.isRentalOrderShipmentDraftEditable(orderId);
  }

  /** Returns whether at least one current order cabin remains eligible for replacement. */
  public boolean hasRentalOrderReplaceableUnit(UUID orderId, Set<UUID> activeUnitIds) {
    return rentalOrderShipmentCoordinator.hasRentalOrderReplaceableUnit(orderId, activeUnitIds);
  }

  /** Applies the shared per-cabin pre-start predicate before the remote execution fence. */
  public boolean isRentalOrderUnitReplacementPreStart(UUID orderId, UUID unitId) {
    return rentalOrderShipmentCoordinator.isRentalOrderUnitReplacementPreStart(orderId, unitId);
  }

  /**
   * Locks the shipment decision before an order mutation. This serializes a booking edit with
   * creation of a furniture movement task for the shipment.
   */
  @Transactional
  public boolean lockRentalOrderShipmentDraftForOrderEditing(UUID orderId) {
    return rentalOrderShipmentCoordinator.lockRentalOrderShipmentDraftForOrderEditing(orderId);
  }

  /** Synchronizes the party snapshot on every still-editable rental shipment draft. */
  @Transactional
  public void synchronizeRentalOrderShipmentDraft(
      UUID actorSubjectId,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations) {
    rentalOrderShipmentCoordinator.synchronizeRentalOrderShipmentDraft(
        actorSubjectId, order, reservations);
  }

  /**
   * Creates one independently schedulable return document for every cabin in this shipment and
   * advances the order only after every ordered cabin has reached SHIPPED. A shipment may still
   * contain several cabins, but a return date/driver can never leak from one cabin to another.
   *
   * <p>The set of already-created return lines makes the operation idempotent across a retry and
   * also keeps older installations safe when they already contain a single multi-line return
   * document for a shipment.
   */
  @Transactional
  public void completeRentalOrderShipment(LogisticsDocument shipment) {
    rentalOrderCompletionCoordinator.completeRentalOrderShipment(shipment);
  }

  /** Closes a linked order after its return has reached either terminal inspection outcome. */
  @Transactional
  public void closeRentalOrderReturn(LogisticsDocument returnDocument) {
    rentalOrderCompletionCoordinator.closeRentalOrderReturn(returnDocument);
  }

  /**
   * Commits the first durable return-registration attempt before any private service call. The
   * relay can therefore replay an uncertain outcome through the same stable operation identifier
   * instead of guessing whether asset state changed.
   */
  @Transactional
  public CreateResult registerReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      ReturnPickupRequest request) {
    return result(
        returnCoordinator.registerReturn(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            expectedDocumentVersion,
            request));
  }

  /**
   * Starts the undamaged-return completion saga after freezing the exact per-line media generation
   * references. The private media receiver is the source of truth for ownership and readiness; this
   * service persists only opaque references and its own durable attempt ledger.
   */
  @Transactional
  public CreateResult acceptUndamagedReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      AcceptReturnRequest request) {
    return result(
        returnCoordinator.acceptUndamagedReturn(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            expectedDocumentVersion,
            request));
  }

  /**
   * Starts one maintenance-owned draft estimate per return line after proving inspection photos.
   */
  @Transactional
  public CreateResult startReturnEstimates(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      StartReturnEstimatesRequest request) {
    return result(
        returnCoordinator.startReturnEstimates(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            expectedDocumentVersion,
            request));
  }

  /**
   * Retains the explicit command only for pre-existing DRAFT documents. New documents start their
   * durable preparation workflow atomically with their creation, so the panel never has to
   * coordinate create-and-plan commands.
   */
  @Transactional
  public CreateResult planShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      ShipmentPlanRequest request) {
    return result(
        shipmentCoordinator.planShipment(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            expectedDocumentVersion,
            request));
  }

  /** Recomputes due dates for the exact lines when a still-draft shipment is rescheduled. */
  @Transactional
  public void synchronizeRentalShipmentTerms(LogisticsDocument shipment, LocalDate shipmentDate) {
    shipmentCoordinator.synchronizeRentalShipmentTerms(shipment, shipmentDate);
  }

  /** Clears terms assigned to a shipment that was cancelled before departure. */
  @Transactional
  public void clearRentalShipmentTerms(LogisticsDocument shipment) {
    shipmentCoordinator.clearRentalShipmentTerms(shipment);
  }

  @Transactional
  public CreateResult confirmShipmentPreparation(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    return result(
        shipmentCoordinator.confirmShipmentPreparation(
            subjectId, idempotencyKey, correlationId, documentId, expectedDocumentVersion));
  }

  /**
   * Confirms a shipment. Keeping a planned date from another calendar day is an explicit operator
   * decision; all other callers remain protected by the departure-date guard.
   */
  @Transactional
  public CreateResult confirmShipmentPreparation(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      boolean keepScheduledDate) {
    return result(
        shipmentCoordinator.confirmShipmentPreparation(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            expectedDocumentVersion,
            keepScheduledDate));
  }

  @Transactional
  public CreateResult confirmShipmentPreparation(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      boolean keepScheduledDate,
      LocalDate warehouseToday) {
    return result(
        shipmentCoordinator.confirmShipmentPreparation(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            expectedDocumentVersion,
            keepScheduledDate,
            warehouseToday));
  }

  @Transactional
  public CreateResult cancelShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    return result(
        shipmentCoordinator.cancelShipment(
            subjectId, idempotencyKey, correlationId, documentId, expectedDocumentVersion));
  }

  /**
   * Begins one independently versioned transfer departure. The asset lease and canonical effect
   * remain asynchronous, but every required dependency attempt is committed before the relay is
   * allowed to make a network call.
   */
  @Transactional
  public CreateResult departTransferLine(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      long expectedLineVersion) {
    return result(
        transferCoordinator.departTransferLine(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            lineId,
            expectedDocumentVersion,
            expectedLineVersion));
  }

  /**
   * Freezes exact media generations before asking media-service for ownership truth, then lets the
   * durable relay verify the in-transit snapshot and invoke the one permitted destination
   * assignment effect.
   */
  @Transactional
  public CreateResult arriveTransferLine(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      long expectedLineVersion,
      ArriveTransferLineRequest request) {
    return result(
        transferCoordinator.arriveTransferLine(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            lineId,
            expectedDocumentVersion,
            expectedLineVersion,
            request));
  }

  public TransferArrivalPreflightView transferArrivalPreflight(
      UUID documentId, UUID lineId, long expectedDocumentVersion, long expectedLineVersion) {
    return transferCoordinator.transferArrivalPreflight(
        documentId, lineId, expectedDocumentVersion, expectedLineVersion);
  }

  @Transactional
  public CreateResult cancelTransfer(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    return result(
        transferCoordinator.cancelTransfer(
            subjectId, idempotencyKey, correlationId, documentId, expectedDocumentVersion));
  }

  /**
   * Records an operator-reviewed reconciliation request without inventing a reverse physical
   * movement or silently changing source-owned state.
   */
  @Transactional
  public CreateResult reconcile(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      LogisticsDocumentType documentType,
      long expectedDocumentVersion,
      ReconcileRequest request) {
    return result(
        reconciliationCoordinator.reconcile(
            subjectId,
            idempotencyKey,
            correlationId,
            documentId,
            documentType,
            expectedDocumentVersion,
            request));
  }

  public LogisticsDocumentView get(UUID id, LogisticsDocumentType type) {
    return readProjection.view(readProjection.document(id, type));
  }

  public boolean isRentalShipmentShipped(UUID shipmentId) {
    return readProjection.isRentalShipmentShipped(shipmentId);
  }

  public boolean isRentalOrderUnitAssignedToShipment(UUID orderId, UUID unitId) {
    return readProjection.isRentalOrderUnitAssignedToShipment(orderId, unitId);
  }

  public List<LogisticsDocumentView> list(LogisticsDocumentType type, UUID warehouseId) {
    return readProjection.list(type, warehouseId);
  }

  public LogisticsDocument document(UUID id, LogisticsDocumentType type) {
    return readProjection.document(id, type);
  }

  static String transferMaintenanceDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      Integer priority,
      Long rentalItemVersion) {
    return LogisticsTransferDocumentCoordinator.transferMaintenanceDigest(
        operation, document, line, priority, rentalItemVersion);
  }

  static String holdAcquireOperation(UUID equipmentId) {
    return LogisticsDocumentEffectOperations.holdAcquireOperation(equipmentId);
  }

  static String holdCommitOperation(UUID holdId) {
    return LogisticsDocumentEffectOperations.holdCommitOperation(holdId);
  }

  static String holdReleaseOperation(UUID holdId) {
    return LogisticsDocumentEffectOperations.holdReleaseOperation(holdId);
  }

  private static CreateResult result(LogisticsDocumentCommandResult result) {
    return new CreateResult(result.response(), result.replayed());
  }

  private static CreateResult resultOrNull(LogisticsDocumentCommandResult result) {
    return result == null ? null : result(result);
  }

  /** Logistics-document create result with stable idempotency replay truth. */
  public record CreateResult(LogisticsDocumentView response, boolean replayed) {}
}
