package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType.*;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferAssetStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogisticsAssetTransitionPolicyTest {

  @Test
  void mapsOnlyApprovedTypedLogisticsActionsToCanonicalTargets() {
    UUID destination = UUID.randomUUID();

    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.RENTED, RETURN_INTAKE, LOGISTICS_RETURN, null))
        .isEqualTo(RentalItemStatus.AFTER_RENT);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.AFTER_RENT, RETURN_SETTLE_FREE, LOGISTICS_RETURN, null))
        .isEqualTo(RentalItemStatus.FREE);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.AFTER_RENT, RETURN_SETTLE_SHORTAGE, LOGISTICS_RETURN, null))
        .isEqualTo(RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.FREE, SHIPMENT_CONFIRM, LOGISTICS_SHIPMENT, null))
        .isEqualTo(RentalItemStatus.RENTED);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.BOOKED, SHIPMENT_CONFIRM, LOGISTICS_SHIPMENT, null))
        .isEqualTo(RentalItemStatus.RENTED);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.FREE,
        TRANSFER_DEPART,
        LOGISTICS_TRANSFER,
        null,
        TransferAssetStatus.FREE,
        null))
        .isEqualTo(RentalItemStatus.IN_TRANSFER);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.REPAIR,
        TRANSFER_DEPART,
        LOGISTICS_TRANSFER,
        null,
        TransferAssetStatus.REPAIR,
        null))
        .isEqualTo(RentalItemStatus.IN_TRANSFER);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.CAPITAL_REPAIR,
        TRANSFER_DEPART,
        LOGISTICS_TRANSFER,
        null,
        TransferAssetStatus.REPAIR,
        null))
        .isEqualTo(RentalItemStatus.IN_TRANSFER);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.IN_TRANSFER,
        TRANSFER_ARRIVE,
        LOGISTICS_TRANSFER,
        destination,
        TransferAssetStatus.FREE,
        RentalItemStatus.FREE))
        .isEqualTo(RentalItemStatus.FREE);
    assertThat(LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.IN_TRANSFER,
        TRANSFER_ARRIVE,
        LOGISTICS_TRANSFER,
        destination,
        TransferAssetStatus.REPAIR,
        RentalItemStatus.REPAIR))
        .isEqualTo(RentalItemStatus.REPAIR);
  }

  @Test
  void rejectsWrongOwnerSourceAndDestinationShapes() {
    assertThatThrownBy(() -> LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.FREE, SHIPMENT_CONFIRM, LOGISTICS_RETURN, null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("owner");
    assertThatThrownBy(() -> LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.RENTED,
        TRANSFER_DEPART,
        LOGISTICS_TRANSFER,
        null,
        TransferAssetStatus.FREE,
        null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("match");
    assertThatThrownBy(() -> LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.IN_TRANSFER,
        TRANSFER_ARRIVE,
        LOGISTICS_TRANSFER,
        null,
        TransferAssetStatus.FREE,
        RentalItemStatus.FREE))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("destination");
    assertThatThrownBy(() -> LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.FREE, RETURN_INTAKE, LOGISTICS_RETURN, UUID.randomUUID()))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Only transfer arrival");
    assertThatThrownBy(() -> LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.REPAIR,
        TRANSFER_DEPART,
        LOGISTICS_TRANSFER,
        null,
        TransferAssetStatus.FREE,
        null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("match");
    assertThatThrownBy(() -> LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.IN_TRANSFER,
        TRANSFER_ARRIVE,
        LOGISTICS_TRANSFER,
        UUID.randomUUID(),
        TransferAssetStatus.FREE,
        RentalItemStatus.REPAIR))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("persisted departure");
    assertThatThrownBy(() -> LogisticsAssetTransitionPolicy.target(
        RentalItemStatus.FREE,
        TRANSFER_DEPART,
        LOGISTICS_TRANSFER,
        null,
        null,
        null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("requires transferAssetStatus");
  }
}
