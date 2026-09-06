package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentMovementReservation;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Logistics-local asset response and decoder for one equipment movement reservation. */
record EquipmentMovementReservationResponse(
    UUID reservationId,
    long version,
    String ownerType,
    UUID movementId,
    UUID lineId,
    UUID equipmentId,
    String equipmentName,
    UUID sourceBalanceId,
    UUID sourceWarehouseId,
    UUID sourceRentalItemId,
    String sourceLocationKind,
    long quantity,
    String state,
    OffsetDateTime reservedUntil,
    OffsetDateTime executedAt) {

  static EquipmentMovementReservation decode(EquipmentMovementReservationResponse response) {
    if (response == null
        || response.reservationId() == null
        || response.version() < 0
        || !"LOGISTICS_EQUIPMENT_MOVEMENT".equals(response.ownerType())
        || response.movementId() == null
        || response.lineId() == null
        || response.equipmentId() == null
        || response.equipmentName() == null
        || response.equipmentName().isBlank()
        || response.sourceBalanceId() == null
        || response.sourceWarehouseId() == null
        || response.sourceLocationKind() == null
        || response.quantity() < 1
        || response.state() == null
        || response.reservedUntil() == null) {
      throw malformed("Asset-service returned an invalid equipment movement reservation");
    }
    return new EquipmentMovementReservation(
        response.reservationId(),
        response.version(),
        response.ownerType(),
        response.movementId(),
        response.lineId(),
        response.equipmentId(),
        response.equipmentName(),
        response.sourceBalanceId(),
        response.sourceWarehouseId(),
        response.sourceRentalItemId(),
        response.sourceLocationKind(),
        response.quantity(),
        response.state(),
        response.reservedUntil(),
        response.executedAt());
  }
}
