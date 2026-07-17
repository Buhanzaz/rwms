package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        RentalItemStatus.AFTER_RENT, COMPLETE_EMPTY_ESTIMATE,
        MAINTENANCE_ESTIMATE, repairId, null))
        .isEqualTo(RentalItemStatus.FREE);
    assertThat(MaintenanceAssetTransitionPolicy.target(
        RentalItemStatus.REPAIR, MARK_PENDING_ACCEPTANCE,
        MAINTENANCE_REPAIR, repairId, null))
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
        RentalItemStatus.NEW,
        RentalItemStatus.RENTED,
        RentalItemStatus.BOOKED,
        RentalItemStatus.RESERVED,
        RentalItemStatus.IN_TRANSFER,
        RentalItemStatus.SALE,
        RentalItemStatus.USED_SALE,
        RentalItemStatus.CAPITAL_REPAIR,
        RentalItemStatus.WRITTEN_OFF);

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
        RentalItemStatus.FREE, QUEUE_FOR_REPAIR,
        MAINTENANCE_REPAIR, ownerId, UUID.randomUUID()))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("only from WAITING_ESTIMATE_CONFIRMATION");
  }
}
