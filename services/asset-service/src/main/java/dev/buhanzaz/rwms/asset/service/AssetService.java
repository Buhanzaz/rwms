package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderFurnitureMovementPlanLine;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitEquipmentRequirements;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReplacementMovementBundle;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset-owned command boundary. Cross-warehouse/cabin equipment changes never
 * leave this PostgreSQL transaction; task-board and future workflow services
 * receive committed facts only.
 *
 * <p>This facade preserves the established controller, inbox and same-package service seam while
 * routing each cohesive use case to its owning collaborator. Transactional annotations remain at
 * the facade boundary so a public command keeps its original atomicity and fencing scope.
 */
@Service
public class AssetService {
  private final AssetRentalItemService rentals;
  private final AssetLogisticsService logistics;
  private final AssetEquipmentService equipment;
  private final AssetMaintenanceService maintenance;
  private final AssetClassifierService classifiers;

  @Autowired
  AssetService(
      AssetRentalItemService rentals,
      AssetLogisticsService logistics,
      AssetEquipmentService equipment,
      AssetMaintenanceService maintenance,
      AssetClassifierService classifiers) {
    this.rentals = rentals;
    this.logistics = logistics;
    this.equipment = equipment;
    this.maintenance = maintenance;
    this.classifiers = classifiers;
  }

  @Transactional(readOnly = true)
  public RentalItemPage listRentalItems(
      UUID warehouseId,
      int page,
      int size,
      String search,
      java.util.Set<RentalItemStatus> excludedStatuses) {
    return rentals.list(warehouseId, page, size, search, excludedStatuses);
  }

  @Transactional(readOnly = true)
  public RentalItemResponse rentalItem(UUID id) {
    return rentals.rentalItem(id);
  }

  @Transactional(readOnly = true)
  public List<CabinCatalogValueResponse> maintenanceCabinCharacteristics() {
    return rentals.maintenanceCabinCharacteristics();
  }

  /**
   * Deliberately narrow read boundary for logistics. The public rental response contains passport
   * and local operator data which must never cross this service-to-service contract.
   */
  @Transactional(readOnly = true)
  public LogisticsRentalItemSnapshot logisticsSnapshot(UUID id) {
    return logistics.snapshot(id);
  }

  @Transactional
  public CreateResult<LogisticsOperationLeaseResponse> acquireLogisticsLease(
      UUID subjectId, UUID key, AcquireLogisticsOperationLeaseRequest request) {
    return logistics.acquireLease(subjectId, key, request);
  }

  @Transactional
  public CreateResult<LogisticsOperationLeaseResponse> renewLogisticsLease(
      UUID subjectId, UUID key, UUID id, LogisticsLeaseCommandRequest request) {
    return logistics.renewLease(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<LogisticsOperationLeaseResponse> releaseLogisticsLease(
      UUID subjectId, UUID key, UUID id, LogisticsLeaseCommandRequest request) {
    return logistics.releaseLease(subjectId, key, id, request);
  }

  /**
   * Validates the active typed lease and applies one canonical state effect in the same
   * transaction. Transfer arrival also moves attached cabin balances through asset's immutable
   * movement ledger.
   */
  @Transactional
  public CreateResult<LogisticsRentalItemSnapshot> applyLogisticsEffect(
      UUID subjectId, UUID key, UUID id, LogisticsFencedEffectRequest request) {
    return logistics.applyEffect(subjectId, key, id, request);
  }

  /**
   * Receives furniture physically found with a returned cabin but absent from canonical contents.
   * It is an asset-owned stock increase with append-only receipt evidence.
   */
  @Transactional
  public CreateResult<LogisticsReturnEquipmentReceiptResponse> receiveLogisticsReturnEquipment(
      UUID subjectId, UUID key, LogisticsReturnEquipmentReceiptRequest request) {
    return logistics.receiveReturnEquipment(subjectId, key, request);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> acquireLogisticsHold(
      UUID subjectId, UUID key, AcquireLogisticsEquipmentHoldRequest request) {
    return logistics.acquireHold(subjectId, key, request);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> renewLogisticsHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    return logistics.renewHold(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> commitLogisticsHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    return logistics.commitHold(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> releaseLogisticsHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    return logistics.releaseHold(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentMovementReservationResponse>
      acquireLogisticsEquipmentMovementReservation(
          UUID subjectId, UUID key, AcquireLogisticsEquipmentMovementReservationRequest request) {
    return logistics.acquireMovementReservation(subjectId, key, request);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentMovementReservationResponse>
      releaseLogisticsEquipmentMovementReservation(
          UUID subjectId,
          UUID key,
          UUID reservationId,
          LogisticsEquipmentMovementReservationCommandRequest request) {
    return logistics.releaseMovementReservation(subjectId, key, reservationId, request);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentMovementExecutionResponse>
      executeLogisticsEquipmentMovementReservations(
          UUID subjectId,
          UUID key,
          ExecuteLogisticsEquipmentMovementReservationsRequest request) {
    return logistics.executeMovementReservations(subjectId, key, request);
  }

  @Transactional
  public CreateResult<RentalItemResponse> createRentalItem(
      UUID subjectId, UUID key, CreateRentalItemRequest request) {
    return rentals.create(subjectId, key, request);
  }

  /**
   * Creates one cabin from a reviewed HTML import. It is not the public create path: the importer
   * resolves supplied catalog identifiers while interactive creation remains strict.
   */
  @Transactional
  public RentalItemResponse createRentalItemFromHtmlImport(
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      List<UUID> characteristicIds,
      Boolean linoleum,
      Map<String, Object> passport) {
    return rentals.createFromHtmlImport(
        warehouseId,
        number,
        status,
        rentalTypeId,
        dimensionId,
        finishingId,
        categoryId,
        category,
        characteristicIds,
        linoleum,
        passport);
  }

  @Transactional
  public RentalItemResponse updatePassport(UUID id, UpdatePassportRequest request) {
    return rentals.updatePassport(id, request);
  }

  @Transactional
  public RentalItemResponse updateStatus(UUID id, UpdateStatusRequest request) {
    return rentals.updateStatus(id, request);
  }

  @Transactional
  public RentalItemResponse fencedStatus(UUID id, FencedStatusRequest request) {
    return rentals.fencedStatus(id, request);
  }

  @Transactional
  public RentalItemResponse updateGeneralComment(UUID id, UpdateGeneralCommentRequest request) {
    return rentals.updateGeneralComment(id, request);
  }

  @Transactional
  public CreateResult<ManualNoteResponse> addManualNote(
      UUID subjectId, UUID key, UUID id, AddManualNoteRequest request) {
    return rentals.addManualNote(subjectId, key, id, request);
  }

  @Transactional(readOnly = true)
  public List<ManualNoteResponse> manualNotes(UUID rentalItemId) {
    return rentals.manualNotes(rentalItemId);
  }

  @Transactional(readOnly = true)
  public List<EquipmentResponse> listEquipment() {
    return equipment.list();
  }

  @Transactional(readOnly = true)
  public List<EquipmentWarehouseResponse> equipmentAtWarehouse(UUID warehouseId) {
    return equipment.atWarehouse(warehouseId);
  }

  /** Shared equipment availability read used by logistics booking and presentations. */
  @Transactional(readOnly = true)
  public List<EquipmentWarehouseResponse> logisticsEquipmentAvailability(UUID warehouseId) {
    return equipment.atWarehouse(warehouseId);
  }

  @Transactional(readOnly = true)
  public EquipmentResponse equipment(UUID id) {
    return equipment.equipment(id);
  }

  /**
   * Receives initial furniture for one newly-created legacy HTML row as append-only evidence.
   * Replaying a source row is a no-op; a cabin with content is never rewritten.
   */
  @Transactional
  public void recordHtmlImportEquipmentReceipts(
      UUID importId,
      String sourceRowId,
      UUID actorSubjectId,
      UUID rentalItemId,
      Map<UUID, Long> quantities) {
    equipment.recordHtmlImportEquipmentReceipts(
        importId, sourceRowId, actorSubjectId, rentalItemId, quantities);
  }

  @Transactional
  public CreateResult<EquipmentResponse> createEquipment(
      UUID subjectId, UUID key, CreateEquipmentRequest request) {
    return equipment.create(subjectId, key, request);
  }

  /**
   * Ensures the durable maintenance furniture binding; versionless calls preserve existing limits
   * while version-fenced calls may change or clear them.
   */
  @Transactional
  public CreateResult<MaintenanceFurnitureEquipmentResponse> ensureMaintenanceFurnitureEquipment(
      UUID subjectId, UUID key, EnsureMaintenanceFurnitureEquipmentRequest request) {
    return maintenance.ensureFurnitureEquipment(subjectId, key, request);
  }

  /** Returns a bounded live read of asset-owned settings for maintenance editor enrichment. */
  @Transactional(readOnly = true)
  public List<MaintenanceFurnitureEquipmentResponse> maintenanceFurnitureEquipmentSnapshots(
      MaintenanceFurnitureEquipmentSnapshotRequest request) {
    return maintenance.furnitureEquipmentSnapshots(request);
  }

  @Transactional
  public EquipmentResponse updateEquipment(UUID id, UpdateEquipmentRequest request) {
    return equipment.update(id, request);
  }

  @Transactional(readOnly = true)
  public EquipmentTotalsResponse equipmentTotals(UUID equipmentId, UUID warehouseId) {
    return equipment.totals(equipmentId, warehouseId);
  }

  @Transactional(readOnly = true)
  public List<EquipmentDispositionResponse> dispositions(UUID warehouseId) {
    return equipment.dispositions(warehouseId);
  }

  @Transactional
  public CreateResult<MovementResponse> transfer(
      UUID subjectId, UUID key, TransferEquipmentRequest request) {
    return equipment.transfer(subjectId, key, request);
  }

  /**
   * Joins an order command to the canonical rental-item/lease lock order before the order service
   * obtains a row lock. The caller must already own a transaction.
   */
  void lockOrderRentalItemForOrder(UUID rentalItemId) {
    rentals.lockOrderRentalItemForOrder(rentalItemId);
  }

  /** Supplies outstanding-only global capacity inputs to the atomic order reservation owner. */
  OrderEquipmentCapacity orderEquipmentCapacity(
      UUID equipmentId, UUID warehouseId, UUID orderId) {
    return equipment.orderCapacity(equipmentId, warehouseId, orderId);
  }

  /** Physical and outstanding-reservation inputs for one atomic order equipment decision. */
  record OrderEquipmentCapacity(
      long allocatableQuantity,
      long orderPhysicalQuantity,
      long otherOrderOutstandingQuantity) {}

  /** Applies the asset-owned order booking status after the caller joined the canonical locks. */
  RentalItemResponse bookOrderRentalItem(UUID rentalItemId) {
    return rentals.bookOrderRentalItem(rentalItemId);
  }

  /** Releases only the order-owned booking state; later lifecycle states stay fenced. */
  RentalItemResponse releaseOrderBooking(UUID rentalItemId) {
    return rentals.releaseOrderBooking(rentalItemId);
  }

  /** Fences every exact old-cabin balance in one batch with ordinary movement reservations. */
  List<List<LogisticsEquipmentMovementReservationResponse>> reserveReplacementMovements(
      UUID idempotencyKey, List<ReplacementMovementRequest> requests) {
    return logistics.reserveReplacementMovements(idempotencyKey, requests);
  }

  /** Existing logistics movement identity paired with its freshly recomputed exact source plan. */
  record ReplacementMovementRequest(
      UUID orderId,
      UUID releasedSourceReservationId,
      UUID targetRentalItemId,
      List<OrderUnitEquipmentRequirements> units,
      OrderUnitReplacementMovementBundle movement,
      List<OrderFurnitureMovementPlanLine> exactPlan) {}

  @Transactional
  public CreateResult<MovementResponse> dispose(
      UUID subjectId, UUID key, DispositionEquipmentRequest request) {
    return equipment.dispose(subjectId, key, request);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> acquireHold(
      UUID subjectId, UUID key, AcquireEquipmentHoldRequest request) {
    return equipment.acquireHold(subjectId, key, request);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> renewHold(
      UUID subjectId, UUID key, UUID id, RenewEquipmentHoldRequest request) {
    return equipment.renewHold(subjectId, key, id, request);
  }

  /**
   * A committed hold remains part of stock availability until its owning workflow releases it.
   * Commit is a state change, not an inferred logistics or inventory movement.
   */
  @Transactional
  public CreateResult<EquipmentHoldResponse> commitHold(
      UUID subjectId, UUID key, UUID id, CommitEquipmentHoldRequest request) {
    return equipment.commitHold(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> releaseHold(
      UUID subjectId, UUID key, UUID id, ReleaseEquipmentHoldRequest request) {
    return equipment.releaseHold(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> acquireLease(
      UUID subjectId, UUID key, AcquireOperationLeaseRequest request) {
    return rentals.acquireLease(subjectId, key, request);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> renewLease(
      UUID subjectId, UUID key, UUID id, RenewOperationLeaseRequest request) {
    return rentals.renewLease(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> releaseLease(
      UUID subjectId, UUID key, UUID id, ReleaseOperationLeaseRequest request) {
    return rentals.releaseLease(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> acquireMaintenanceLease(
      UUID subjectId, UUID key, AcquireMaintenanceOperationLeaseRequest request) {
    return maintenance.acquireLease(subjectId, key, request);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> renewMaintenanceLease(
      UUID subjectId, UUID key, UUID id, RenewMaintenanceOperationLeaseRequest request) {
    return maintenance.renewLease(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> releaseMaintenanceLease(
      UUID subjectId, UUID key, UUID id, ReleaseMaintenanceOperationLeaseRequest request) {
    return maintenance.releaseLease(subjectId, key, id, request);
  }

  @Transactional
  public CreateResult<RentalItemResponse> maintenanceFencedStatus(
      UUID subjectId, UUID key, UUID id, MaintenanceFencedStatusRequest request) {
    return maintenance.fencedStatus(subjectId, key, id, request);
  }

  /**
   * Applies an accepted maintenance material characteristic without replacing existing cabin
   * characteristics. Aggregate locking, relation uniqueness and idempotency make retries safe.
   */
  @Transactional
  public CreateResult<MaintenanceCharacteristicApplicationResponse> applyMaintenanceCharacteristic(
      UUID subjectId, UUID key, UUID rentalItemId, UUID characteristicId) {
    return maintenance.applyCharacteristic(subjectId, key, rentalItemId, characteristicId);
  }

  @Transactional(readOnly = true)
  public List<ClassifierResponse> classifiers(String type) {
    return classifiers.classifiers(type);
  }

  @Transactional
  public CreateResult<ClassifierResponse> createClassifier(
      UUID subjectId, UUID key, CreateClassifierRequest request) {
    return classifiers.create(subjectId, key, request);
  }

  @Transactional
  public ClassifierResponse updateClassifier(UUID id, ClassifierRequest request) {
    return classifiers.update(id, request);
  }

  /** Expiry immediately stops availability blocking; this sweep persists the visible state/event. */
  @Scheduled(fixedDelayString = "${rwms.asset.equipment-hold.expiration-sweep-delay:PT30S}")
  @Transactional
  public void expireDueEquipmentHolds() {
    equipment.expireDueHolds();
  }

  public record CreateResult<T>(T response, boolean replayed) {}
}
