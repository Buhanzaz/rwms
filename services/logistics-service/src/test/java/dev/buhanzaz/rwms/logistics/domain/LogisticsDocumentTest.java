package dev.buhanzaz.rwms.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogisticsDocumentTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000102");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000103");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000104");

  @Test
  void returnFollowsTheFencedIntakeAndAcceptanceLifecycle() {
    LogisticsDocument document = LogisticsDocument.createReturn(WAREHOUSE, SUBJECT, CORRELATION);

    document.scheduleReturn("Водитель", LocalDate.parse("2026-07-01"));
    document.beginReturnRegistration();
    document.requireReturnInspection();
    document.beginReturnAcceptance();
    document.acceptReturn();

    assertThat(document.getDocumentType()).isEqualTo(LogisticsDocumentType.RETURN);
    assertThat(document.getState()).isEqualTo(LogisticsDocumentState.ACCEPTED);
    assertThat(document.getScheduledDate()).isEqualTo(LocalDate.parse("2026-07-01"));
    assertThat(document.getReturnArrivedAt()).isNotNull();
  }

  @Test
  void inventoryHistoricalReturnDoesNotInventPhysicalIntake() {
    LogisticsDocument document =
        LogisticsDocument.createInventoryReturn(
            WAREHOUSE,
            UUID.randomUUID(),
            "Клиент",
            LocalDate.parse("2026-08-01"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.parse("2026-08-03T12:00:00Z"),
            3,
            "a".repeat(64),
            SUBJECT);

    assertThat(document.getState()).isEqualTo(LogisticsDocumentState.ACCEPTED);
    assertThat(document.getReturnArrivedAt()).isNull();
  }

  @Test
  void shipmentSnapshotsAreTrimmedAndCannotBeCancelledAfterConfirmationBegins() {
    LogisticsDocument document =
        LogisticsDocument.createShipment(WAREHOUSE, "  Арендатор  ", "  Водитель  ", SUBJECT, CORRELATION);

    assertThat(document.getPartySnapshot()).isEqualTo("Арендатор");
    assertThat(document.getDriverSnapshot()).isEqualTo("Водитель");
    assertThat(document.getCustomerDeliveryPurpose())
        .isEqualTo(CustomerDeliveryPurpose.RENTAL_DELIVERY);

    document.scheduleShipment("Водитель", LocalDate.parse("2026-07-01"));
    document.beginShipmentPreparation();
    document.awaitShipmentConfirmation();
    document.beginShipmentConfirmation();

    assertThatThrownBy(document::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be cancelled");
  }

  @Test
  void nonCustomerMovementsDoNotAcquireACustomerDeliveryPurpose() {
    LogisticsDocument rentalReturn =
        LogisticsDocument.createReturn(WAREHOUSE, SUBJECT, CORRELATION);
    LogisticsDocument transfer =
        LogisticsDocument.createTransfer(
            WAREHOUSE,
            DESTINATION,
            LocalDate.parse("2026-09-03"),
            SUBJECT,
            CORRELATION);

    assertThat(rentalReturn.getCustomerDeliveryPurpose()).isNull();
    assertThat(transfer.getCustomerDeliveryPurpose()).isNull();
  }

  @Test
  void shipmentCannotDepartBeforeItsScheduledDate() {
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            WAREHOUSE, "Арендатор", "Водитель", SUBJECT, CORRELATION);
    OffsetDateTime now = OffsetDateTime.parse("2026-07-22T08:00:00Z");
    document.scheduleShipment("Водитель", now.toLocalDate().plusDays(1));
    document.beginShipmentPreparation();
    document.awaitShipmentConfirmation();

    assertThatThrownBy(() -> document.requireShipmentDepartureAllowed(now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("future");

    document.scheduleShipment("Водитель", now.toLocalDate());
    document.requireShipmentDepartureAllowed(now);
  }

  @Test
  void unstartedOrderLineReplacementChangesOnlyItsPhysicalSourceAndCabinFence() {
    UUID orderId = UUID.randomUUID();
    UUID oldUnitId = UUID.randomUUID();
    UUID replacementUnitId = UUID.randomUUID();
    UUID supportWarehouseId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createRentalOrderShipment(
            WAREHOUSE,
            UUID.randomUUID(),
            orderId,
            "ООО Регион",
            SUBJECT,
            CORRELATION);
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(
            document, 1, oldUnitId, 3, "ООО Регион", orderId, WAREHOUSE);

    line.replaceRentalItem(oldUnitId, replacementUnitId, 7, supportWarehouseId);

    assertThat(document.getWarehouseId()).isEqualTo(WAREHOUSE);
    assertThat(line.getAssetId()).isEqualTo(replacementUnitId);
    assertThat(line.getAssetVersion()).isEqualTo(7);
    assertThat(line.getInventorySourceWarehouseId()).isEqualTo(supportWarehouseId);
  }

  @Test
  void historicalRentalDocumentsKeepThePastDateAndOptionalShipmentDriverEvidence() {
    LocalDate occurredOn = LocalDate.parse("2026-08-01");
    UUID driverWorkerId = UUID.randomUUID();
    LogisticsDocument shipment =
        LogisticsDocument.createHistoricalRentalShipment(
            WAREHOUSE,
            UUID.randomUUID(),
            "ООО История",
            "  Иванов Иван  ",
            driverWorkerId,
            occurredOn,
            SUBJECT,
            CORRELATION);
    LogisticsDocument unknownDriverShipment =
        LogisticsDocument.createHistoricalRentalShipment(
            WAREHOUSE, UUID.randomUUID(), "ООО История", occurredOn, SUBJECT, CORRELATION);
    LogisticsDocument rentalReturn =
        LogisticsDocument.createHistoricalRentalReturn(
            WAREHOUSE, UUID.randomUUID(), "ООО История", occurredOn, SUBJECT, CORRELATION);

    shipment.beginShipmentPreparation();
    unknownDriverShipment.beginShipmentPreparation();
    rentalReturn.beginReturnRegistration();

    assertThat(shipment.isHistoricalRentalImport()).isTrue();
    assertThat(shipment.getScheduledDate()).isEqualTo(occurredOn);
    assertThat(shipment.getDriverSnapshot()).isEqualTo("Иванов Иван");
    assertThat(shipment.getDriverWorkerId()).isEqualTo(driverWorkerId);
    assertThat(shipment.getState()).isEqualTo(LogisticsDocumentState.PREPARING);
    assertThat(unknownDriverShipment.getDriverSnapshot()).isNull();
    assertThat(unknownDriverShipment.getDriverWorkerId()).isNull();
    assertThat(rentalReturn.isHistoricalRentalImport()).isTrue();
    assertThat(rentalReturn.getScheduledDate()).isEqualTo(occurredOn);
    assertThat(rentalReturn.getDriverSnapshot()).isNull();
    assertThat(rentalReturn.getDriverWorkerId()).isNull();
    assertThat(rentalReturn.getState()).isEqualTo(LogisticsDocumentState.REGISTERING);
  }

  @Test
  void failedHistoricalShipmentCanBeCancelledWithoutChangingACompletedShipment() {
    LogisticsDocument failed =
        LogisticsDocument.createHistoricalRentalShipment(
            WAREHOUSE,
            UUID.randomUUID(),
            "ООО История",
            LocalDate.parse("2026-08-01"),
            SUBJECT,
            CORRELATION);
    failed.beginShipmentPreparation();
    failed.shipmentConflict();

    failed.beginFailedHistoricalShipmentCancellation();
    failed.cancelShipment();

    assertThat(failed.getState()).isEqualTo(LogisticsDocumentState.CANCELLED);
    assertThatThrownBy(failed::beginFailedHistoricalShipmentCancellation)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed historical shipment");
  }

  @Test
  void knownHistoricalShipmentCapabilitiesCanBeReleasedAfterAConflict() {
    LogisticsDocument document =
        LogisticsDocument.createHistoricalRentalShipment(
            WAREHOUSE,
            UUID.randomUUID(),
            "ООО История",
            LocalDate.parse("2026-08-01"),
            SUBJECT,
            CORRELATION);
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(document, 1, UUID.randomUUID(), 7, "ООО История");
    OffsetDateTime acquiredAt = OffsetDateTime.parse("2026-08-01T10:00:00Z");
    LogisticsGuard guard =
        LogisticsGuard.active(
            document, line, UUID.randomUUID(), 2, 3, line.getAssetVersion(), acquiredAt);
    LogisticsEquipmentHoldReference hold =
        LogisticsEquipmentHoldReference.active(
            document,
            line,
            UUID.randomUUID(),
            UUID.randomUUID(),
            WAREHOUSE,
            1,
            4,
            5,
            acquiredAt);
    guard.conflict();
    hold.requireReconciliation();

    guard.prepareReleaseAfterFailedHistoricalShipment();
    hold.prepareReleaseAfterFailedHistoricalShipment();
    guard.release();
    hold.release(6, acquiredAt.plusMinutes(1));

    assertThat(guard.getGuardState()).isEqualTo(LogisticsGuardState.RELEASED);
    assertThat(hold.getHoldState()).isEqualTo(LogisticsEquipmentHoldState.RELEASED);
  }

  @Test
  void transferRejectsSameOriginAndDestination() {
    assertThatThrownBy(
            () ->
                LogisticsDocument.createTransfer(
                    WAREHOUSE,
                    WAREHOUSE,
                    LocalDate.parse("2026-07-01"),
                    SUBJECT,
                    CORRELATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differ from origin");
  }

  @Test
  void transferLineDoesNotPermitArrivalBeforeDeparture() {
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            WAREHOUSE,
            DESTINATION,
            LocalDate.parse("2026-07-01"),
            SUBJECT,
            CORRELATION);
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(
            document,
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000105"),
            4,
            null);

    assertThatThrownBy(line::beginArrival)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transition");
  }
}
