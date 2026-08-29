package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyFailures.unavailable;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.AvailableCabin;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinAvailability;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinCatalogPage;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinFacets;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinFurnitureMovementPlan;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinSearchResult;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.ConvertedPresentationHolds;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentWarehouseAvailability;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderEquipmentReservation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderFurnitureMovementPlan;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitCandidatePage;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitEquipmentRequirements;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitReplacement;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitReservation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitsReplacementReceipt;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PresentationHolds;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Asset-service-owned order allocation, furniture composition, cabin catalog and presentation-hold
 * port used by logistics.
 */
interface LogisticsOrderPresentationDependencyPort {
  OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search);

  List<OrderUnitReservation> readOrderUnits(UUID orderId);

  OrderUnitReservation reserveOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole);

  OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey, UUID orderId, UUID unitId, UUID actorSubjectId, String actorRole);

  List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole);

  /** Live asset-owned equipment availability for one warehouse. */
  List<EquipmentWarehouseAvailability> readLogisticsEquipmentAvailability(UUID warehouseId);

  List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units);

  OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID replacementForRentalItemId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderUnitEquipmentRequirements> units);

  /**
   * Atomically swaps an ordered set of active order cabins while preserving the order-wide
   * furniture reservations and optionally pre-holding each exact existing movement task.
   */
  default OrderUnitsReplacementReceipt replaceOrderUnits(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units,
      List<OrderUnitReplacement> replacements) {
    return replaceOrderUnits(
        idempotencyKey,
        orderId,
        warehouseId,
        warehouseId,
        presentationId,
        actorSubjectId,
        actorRole,
        units,
        replacements);
  }

  /** Atomically swaps cabins while retaining service and physical source warehouse identities. */
  OrderUnitsReplacementReceipt replaceOrderUnits(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID inventorySourceWarehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units,
      List<OrderUnitReplacement> replacements);

  CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId, UUID rentalItemId, List<CabinFurnitureRequirement> requirements);

  default CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    throw unavailable("Cabin facets are not configured");
  }

  /** Reads a bounded cabin fact page without acquiring or renewing any hold. */
  default CabinCatalogPage readCabinCatalog(UUID warehouseId, String query, int page, int size) {
    throw unavailable("Cabin catalog is not configured");
  }

  /** Reads only cabins bookable by one inquiry, including that inquiry's own live holds. */
  default CabinCatalogPage readCustomerCabinCatalog(
      UUID warehouseId,
      UUID holdScopeId,
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      int page,
      int size) {
    throw unavailable("Customer cabin catalog is not configured");
  }

  /**
   * Sends the exact prepared asset request with its derived downstream idempotency key.
   * Implementations must not deserialize and rebuild {@code exactRequestBody} before sending it.
   */
  default CabinSearchResult searchAvailableCabins(
      UUID downstreamIdempotencyKey, String exactRequestBody) {
    throw unavailable("Cabin search is not configured");
  }

  default List<AvailableCabin> readCabinSnapshots(
      UUID warehouseId, List<UUID> rentalItemIds) {
    throw unavailable("Cabin snapshots are not configured");
  }

  default CabinAvailability readCabinAvailability(
      UUID warehouseId, List<UUID> rentalItemIds) {
    throw unavailable("Cabin availability is not configured");
  }

  default PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable("Presentation holds are not configured");
  }

  /** Replays an already-frozen asset replace-holds JSON body without rebuilding it. */
  default PresentationHolds replacePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    throw unavailable("Exact presentation hold replacement is not configured");
  }

  default PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {
    if (sourceHoldScopeId == null) {
      return replacePresentationHolds(
          idempotencyKey,
          presentationId,
          warehouseId,
          rentalItemIds,
          expiresAt,
          actorSubjectId,
          actorRole);
    }
    throw unavailable("Presentation hold transfer is not configured");
  }

  default PresentationHolds readPresentationHolds(UUID presentationId) {
    throw unavailable("Presentation holds are not configured");
  }

  default PresentationHolds readPresentationHolds(
      UUID presentationId, UUID actorSubjectId, String actorRole) {
    return readPresentationHolds(presentationId);
  }

  default PresentationHolds releasePresentationHolds(
      UUID idempotencyKey, UUID presentationId, UUID actorSubjectId, String actorRole) {
    throw unavailable("Presentation holds are not configured");
  }

  /** Replays an already-frozen asset release-holds JSON body without rebuilding it. */
  default PresentationHolds releasePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    throw unavailable("Exact presentation hold release is not configured");
  }

  default ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {
    return convertPresentationHolds(
        idempotencyKey,
        presentationId,
        orderId,
        warehouseId,
        selectedRentalItemIds,
        clientId,
        tenantSnapshot,
        actorSubjectId,
        actorRole,
        null);
  }

  /**
   * Atomically converts presentation holds and replaces the complete order furniture composition. A
   * non-null {@code units} value is authoritative for every resulting active order cabin.
   */
  default ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units) {
    throw unavailable("Presentation hold conversion is not configured");
  }
}
