package dev.buhanzaz.rwms.asset.disposition;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionLeaseProof;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Evaluates the current reservation, lease, and cabin-status eligibility for a property
 * disposition.
 *
 * <p>It reads only the current asset-side guards. Prepare and apply services retain the ordering
 * in which those guards are evaluated and the decision they permit or reject.
 */
@Service
final class PropertyDispositionEligibilityService {
  private final JdbcTemplate jdbc;

  PropertyDispositionEligibilityService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void assertCabinStatusAllowsDisposition(RentalItem cabin) {
    if (!cabinStatusAllowsDisposition(cabin)) {
      throw new AssetConflictException("Cabin is reserved, transferred, or terminal");
    }
  }

  /**
   * Evaluates only the status component of eligibility. RENTED is deliberately accepted because a
   * completed inventory write-off marker can supersede logistics custody before the terminal
   * maintenance decision while the rental status remains a lagging projection. Every caller also
   * checks current reservations, foreign holds and leases under its transaction locks.
   */
  boolean cabinStatusAllowsDisposition(RentalItem cabin) {
    RentalItemStatus status = cabin.getStatus();
    return !status.isTerminalDispositionStatus()
        && status != RentalItemStatus.BOOKED
        && status != RentalItemStatus.RESERVED
        && status != RentalItemStatus.IN_TRANSFER
        && cabin.getTransferOriginStatus() == null;
  }

  void assertNoActiveCabinReservation(UUID rentalItemId) {
    if (hasActiveCabinReservation(rentalItemId)) {
      throw new AssetConflictException("Cabin has an active rental, order, or presentation reservation");
    }
  }

  boolean hasActiveCabinReservation(UUID rentalItemId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from order_unit_reservation
          where rental_item_id=? and state='ACTIVE'
          union all
          select 1 from presentation_unit_hold
          where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
        )
        """,
        Boolean.class,
        rentalItemId,
        rentalItemId);
    return Boolean.TRUE.equals(active);
  }

  void assertNoActiveEquipmentReservation(UUID equipmentId, UUID warehouseId) {
    if (hasActiveEquipmentReservation(equipmentId, warehouseId)) {
      throw new AssetConflictException("Equipment has an active order reservation");
    }
  }

  boolean hasActiveEquipmentReservation(UUID equipmentId, UUID warehouseId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from order_equipment_reservation
          where equipment_id=? and warehouse_id=? and state='ACTIVE'
        )
        """,
        Boolean.class,
        equipmentId,
        warehouseId);
    return Boolean.TRUE.equals(active);
  }

  /**
   * Locks and validates the current operation lease before PREPARE. A lagging RENTED cabin cannot
   * reuse even a maintenance lease: logistics custody must first be durably released, leaving the
   * asset version and status untouched, before maintenance may fence the terminal decision.
   */
  void assertLeaseProof(RentalItem cabin, MaintenancePropertyDispositionLeaseProof proof) {
    LeaseRow active = activeLeaseForUpdate(cabin.getId()).orElse(null);
    if (active == null) {
      if (proof != null) {
        throw new AssetConflictException("Provided maintenance lease is no longer active");
      }
      return;
    }
    if (cabin.getStatus() == RentalItemStatus.RENTED) {
      throw new AssetConflictException(
          "Inventory-marked rented cabin still has an active operation lease");
    }
    if (proof == null
        || !active.id().equals(proof.leaseId())
        || active.fencingToken() != proof.fencingToken()
        || !active.ownerType().equals(proof.ownerType().name())
        || !active.ownerId().equals(proof.ownerId().toString())
        || !isMaintenanceLeaseOwner(active.ownerType())) {
      throw new AssetConflictException("Cabin has an active operation lease owned by another workflow");
    }
  }

  /**
   * A prepared fence may outlive the maintenance operation lease that fenced its preparation. A
   * later lease is still a competing workflow and must block APPLY; the absence of the original
   * lease is safe because this fence owns the cabin and content holds until terminalization. A
   * lagging RENTED cabin is stricter: any active lease means logistics custody returned and blocks
   * terminalization, even if its identity matches the originally prepared maintenance proof.
   */
  void assertFenceLeaseAllowsApply(
      RentalItem cabin, MaintenancePropertyDispositionLeaseProof preparedProof) {
    LeaseRow active = activeLeaseForUpdate(cabin.getId()).orElse(null);
    if (active == null) {
      return;
    }
    if (cabin.getStatus() == RentalItemStatus.RENTED) {
      throw new AssetConflictException(
          "Inventory-marked rented cabin acquired an active operation lease");
    }
    if (preparedProof == null
        || !active.id().equals(preparedProof.leaseId())
        || active.fencingToken() != preparedProof.fencingToken()
        || !active.ownerType().equals(preparedProof.ownerType().name())
        || !active.ownerId().equals(preparedProof.ownerId().toString())
        || !isMaintenanceLeaseOwner(active.ownerType())) {
      throw new AssetConflictException(
          "Cabin acquired a conflicting operation lease after preparation");
    }
  }

  boolean hasActiveLease(UUID rentalItemId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from operation_lease
          where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
        )
        """,
        Boolean.class,
        rentalItemId);
    return Boolean.TRUE.equals(active);
  }

  MaintenancePropertyDispositionLeaseProof fenceLeaseProof(PropertyDispositionFence fence) {
    if (fence.getMaintenanceLeaseId() == null) {
      return null;
    }
    return new MaintenancePropertyDispositionLeaseProof(
        fence.getMaintenanceLeaseId(),
        fence.getMaintenanceLeaseFencingToken(),
        MaintenanceLeaseOwnerType.valueOf(fence.getMaintenanceLeaseOwnerType()),
        fence.getMaintenanceLeaseOwnerId());
  }

  BalanceLocationKind cabinBalanceKind(RentalItem cabin) {
    return cabin.getStatus() == RentalItemStatus.RENTED
        ? BalanceLocationKind.CABIN_RENTED
        : BalanceLocationKind.CABIN_NON_RENTED;
  }

  void assertVersion(long actual, Long expected, String message) {
    if (expected == null || actual != expected) {
      throw new AssetConflictException(message);
    }
  }

  void requireSnapshotIdentity(
      PropertyDispositionApiModels.PropertyAssetKind kind, UUID assetId, UUID warehouseId) {
    if (kind == null || assetId == null || warehouseId == null) {
      throw new IllegalArgumentException("Property asset identity is required");
    }
  }

  void requireCommandIdentity(UUID subjectId, UUID decisionId, UUID idempotencyKey) {
    if (subjectId == null || decisionId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Property disposition command identity is required");
    }
  }

  private Optional<LeaseRow> activeLeaseForUpdate(UUID rentalItemId) {
    List<LeaseRow> rows = jdbc.query(
        """
        select id,version,rental_item_id,owner_type,owner_id,fencing_token,state,expires_at
        from operation_lease
        where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
        order by fencing_token,id
        for update
        """,
        (resultSet, row) ->
            new LeaseRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("rental_item_id", UUID.class),
                resultSet.getString("owner_type"),
                resultSet.getString("owner_id"),
                resultSet.getLong("fencing_token"),
                resultSet.getString("state"),
                resultSet.getObject("expires_at", OffsetDateTime.class)),
        rentalItemId);
    if (rows.size() > 1) {
      throw new IllegalStateException("Active operation lease uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private static boolean isMaintenanceLeaseOwner(String ownerType) {
    return MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE.name().equals(ownerType)
        || MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR.name().equals(ownerType);
  }

  /**
   * Minimal lease projection used to enforce owner, state, expiry, and fencing eligibility without
   * exposing persistence rows to the disposition workflow.
   */
  private record LeaseRow(
      UUID id,
      long version,
      UUID rentalItemId,
      String ownerType,
      String ownerId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}
}
