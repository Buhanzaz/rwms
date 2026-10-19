package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyFailures.unavailable;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CapitalRepair;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CapitalRepairPage;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.HistoricalShipmentRepairClosure;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.MediaReference;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.RepairPlaceAllocation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.RepairPlaceProjection;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.ReturnEstimateSource;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.TransferRepairArrivalCompletion;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.TransferRepairArrivalPreflight;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.TransferRepairDeparture;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Maintenance-service-owned repair preparation, arrival and repair-board projection port. */
interface LogisticsMaintenanceDependencyPort {
  default TransferRepairDeparture prepareTransferDeparture(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    throw unavailable("Maintenance transfer departure preparation is not configured");
  }

  /**
   * Closes or cancels maintenance-owned work before an imported shipment may acquire a logistics
   * lease. The response pins the asset version that the following shipment saga must fence.
   */
  default HistoricalShipmentRepairClosure closeHistoricalShipment(
      UUID idempotencyKey, UUID shipmentId, UUID warehouseId, UUID rentalItemId) {
    throw unavailable("Maintenance historical shipment closure is not configured");
  }

  default TransferRepairArrivalPreflight preflightTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    throw unavailable("Maintenance transfer arrival preflight is not configured");
  }

  default TransferRepairArrivalCompletion completeTransferArrival(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority) {
    throw unavailable("Maintenance transfer arrival completion is not configured");
  }

  ReturnEstimateSource upsertReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      OffsetDateTime arrivedAt,
      List<MediaReference> mediaReferences);

  default CapitalRepairPage readCapitalRepairs(UUID warehouseId, int page, int size) {
    throw unavailable("Capital-repair projection is not configured");
  }

  default CapitalRepair readCapitalRepair(UUID repairId) {
    throw unavailable("Capital-repair lookup is not configured");
  }

  default RepairPlaceProjection readRepairPlaces(UUID warehouseId) {
    throw unavailable("Repair-place projection is not configured");
  }

  default RepairPlaceAllocation transitionRepairPlace(
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition) {
    throw unavailable("Repair-place transition is not configured");
  }
}
