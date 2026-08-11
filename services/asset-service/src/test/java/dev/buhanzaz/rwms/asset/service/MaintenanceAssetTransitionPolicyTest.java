package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceAssetTransitionPolicyTest {

  @Test
  void mapsOnlyTheApprovedMaintenanceActionsToCanonicalTargets() {
    UUID repairId = UUID.randomUUID();

    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.FREE, QUEUE_FOR_REPAIR, MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.REPAIR);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.REPAIR, QUEUE_FOR_REPAIR, MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.REPAIR);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.REPAIR,
        QUEUE_FOR_CAPITAL_REPAIR,
        MAINTENANCE_REPAIR,
        repairId,
        null))
        .isEqualTo(RentalItemStatus.CAPITAL_REPAIR);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.CAPITAL_REPAIR, QUEUE_FOR_REPAIR, MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.REPAIR);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.USED_SALE, QUEUE_FOR_REPAIR, MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.REPAIR);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.AFTER_RENT, COMPLETE_EMPTY_ESTIMATE,
        MAINTENANCE_ESTIMATE, repairId, null))
        .isEqualTo(RentalItemStatus.FREE);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.AFTER_RENT, COMPLETE_EMPTY_REPAIR,
        MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.FREE);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.REPAIR, MARK_PENDING_ACCEPTANCE,
        MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.WAITING_REPAIR_CHECK);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.CAPITAL_REPAIR,
        MARK_PENDING_ACCEPTANCE,
        MAINTENANCE_REPAIR,
        repairId,
        null))
        .isEqualTo(RentalItemStatus.WAITING_REPAIR_CHECK);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.WAITING_REPAIR_CHECK, ACCEPT_REPAIR,
        MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.FREE);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.WAITING_REPAIR_CHECK, WRITE_OFF,
        MAINTENANCE_REPAIR, repairId, null))
        .isEqualTo(RentalItemStatus.WRITTEN_OFF);
  }

  @Test
  void repairOwnedTransitionRejectsRentedFencedAndTerminalStatuses() {
    UUID repairId = UUID.randomUUID();

    for (RentalItemStatus status : RentalItemStatus.values()) {
      if (status == RentalItemStatus.RENTED
          || status == RentalItemStatus.IN_TRANSFER
          || status == RentalItemStatus.WRITTEN_OFF
          || status == RentalItemStatus.LOST) {
        assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
            status, QUEUE_FOR_REPAIR, MAINTENANCE_REPAIR, repairId, null))
            .as("source %s", status)
            .isInstanceOf(AssetConflictException.class)
            .hasMessageContaining("not allowed");
      } else {
        assertThat(MaintenanceAssetTransitionPolicy.target(
            status, QUEUE_FOR_REPAIR, MAINTENANCE_REPAIR, repairId, null))
            .as("source %s", status)
            .isEqualTo(RentalItemStatus.REPAIR);
      }
    }
  }

  @Test
  void everyMaintenanceActionRejectsFencedAndTerminalSources() {
    UUID ownerId = UUID.randomUUID();

    for (RentalItemStatus source : List.of(
        RentalItemStatus.IN_TRANSFER, RentalItemStatus.WRITTEN_OFF, RentalItemStatus.LOST)) {
      for (MaintenanceStatusAction action : MaintenanceStatusAction.values()) {
        assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
            source, action, MAINTENANCE_REPAIR, ownerId, null))
            .as("source %s, action %s", source, action)
            .isInstanceOf(AssetConflictException.class)
            .hasMessageContaining("not allowed");
      }
    }
  }

  @Test
  void emptyRepairFreesOnlyAnEligibleUnoccupiedCabinForTheRepairOwner() {
    UUID repairId = UUID.randomUUID();
    List<RentalItemStatus> allowed = List.of(
        RentalItemStatus.FREE,
        RentalItemStatus.WAREHOUSE,
        RentalItemStatus.OWN_NEEDS,
        RentalItemStatus.AFTER_RENT);

    allowed.forEach(source -> assertThat(MaintenanceAssetTransitionPolicy.target(
        source, COMPLETE_EMPTY_REPAIR, MAINTENANCE_REPAIR, repairId, null))
        .as("source %s", source)
        .isEqualTo(RentalItemStatus.FREE));

    for (RentalItemStatus source : RentalItemStatus.values()) {
      if (!allowed.contains(source)) {
        assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
            source, COMPLETE_EMPTY_REPAIR, MAINTENANCE_REPAIR, repairId, null))
            .as("source %s", source)
            .isInstanceOf(AssetConflictException.class)
            .hasMessageContaining("not allowed");
      }
    }

    assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.FREE,
        COMPLETE_EMPTY_REPAIR,
        MAINTENANCE_ESTIMATE,
        repairId,
        null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("repair-owned lease");
  }

  @Test
  void linkedReturnSourceRequiresTheOwningEstimateReference() {
    UUID estimateId = UUID.randomUUID();

    assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION, QUEUE_FOR_REPAIR,
        MAINTENANCE_ESTIMATE, estimateId, null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("linked-return estimate proof");
    assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION, QUEUE_FOR_REPAIR,
        MAINTENANCE_ESTIMATE, estimateId, UUID.randomUUID()))
        .isInstanceOf(AssetConflictException.class);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION, QUEUE_FOR_REPAIR,
        MAINTENANCE_ESTIMATE, estimateId, estimateId))
        .isEqualTo(RentalItemStatus.REPAIR);
  }

  @Test
  void rejectsEveryForbiddenMaintenanceSourceAndGenericActionShape() {
    UUID ownerId = UUID.randomUUID();
    List<RentalItemStatus> forbidden = List.of(
        RentalItemStatus.RENTED,
        RentalItemStatus.RESERVED,
        RentalItemStatus.IN_TRANSFER,
        RentalItemStatus.SALE,
        RentalItemStatus.USED_SALE,
        RentalItemStatus.CAPITAL_REPAIR,
        RentalItemStatus.WRITTEN_OFF,
        RentalItemStatus.LOST);

    forbidden.forEach(source -> assertThatThrownBy(() ->
        MaintenanceAssetTransitionPolicy.target(
            source, WRITE_OFF, MAINTENANCE_REPAIR, ownerId, null))
        .as("source %s", source)
        .isInstanceOf(AssetConflictException.class));
    assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.FREE, COMPLETE_EMPTY_ESTIMATE,
        MAINTENANCE_REPAIR, ownerId, null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("estimate-owned lease");
    assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.REPAIR, COMPLETE_EMPTY_ESTIMATE,
        MAINTENANCE_ESTIMATE, ownerId, null))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("not allowed");
    assertThatThrownBy(() -> MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.FREE, QUEUE_FOR_REPAIR,
        MAINTENANCE_REPAIR, ownerId, UUID.randomUUID()))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("only from WAITING_ESTIMATE_CONFIRMATION");
  }

  @Test
  void allowsBookedWithoutAnActiveOrderReservationToRetryQueueOrWriteOff() {
    UUID ownerId = UUID.randomUUID();

    assertThat(
            MaintenanceAssetTransitionPolicy.target(
                RentalItemStatus.BOOKED,
                QUEUE_FOR_REPAIR,
                MAINTENANCE_ESTIMATE,
                ownerId,
                null))
        .isEqualTo(RentalItemStatus.REPAIR);
    assertThat(
            MaintenanceAssetTransitionPolicy.target(
                RentalItemStatus.BOOKED,
                WRITE_OFF,
                MAINTENANCE_REPAIR,
                ownerId,
                null))
        .isEqualTo(RentalItemStatus.WRITTEN_OFF);
  }
}
