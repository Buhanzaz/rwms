package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.OperationLeaseResponse;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OperationLeaseState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns operation-lease locking, fencing, expiry and event representation.
 *
 * <p>Use-case services keep idempotency and typed-owner policy at their boundary. This component
 * owns only the durable lease aggregate and the fixed lock order that protects it with its rental
 * item.
 */
@Service
final class AssetLeaseService {
  private final OperationLeaseRepository operationLeases;
  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final AssetRentalProjectionService rentals;
  private final Duration leaseTtl;

  AssetLeaseService(
      OperationLeaseRepository operationLeases,
      JdbcTemplate jdbc,
      AssetEventStore events,
      AssetRentalProjectionService rentals,
      @Value("${rwms.asset.operation-lease.ttl:15m}") Duration leaseTtl) {
    this.operationLeases = operationLeases;
    this.jdbc = jdbc;
    this.events = events;
    this.rentals = rentals;
    this.leaseTtl = requireTtl(leaseTtl, "operation lease");
  }

  void lockRentalItemAndLease(UUID rentalItemId) {
    advisoryLock(rentalItemLockKey(rentalItemId));
    advisoryLock(leaseLockKey(rentalItemId));
  }

  void lockRentalItem(UUID rentalItemId) {
    advisoryLock(rentalItemLockKey(rentalItemId));
  }

  OperationLease requireForUpdate(UUID id) {
    UUID rentalItemId =
        operationLeases
            .findRentalItemIdById(id)
            .orElseThrow(() -> new AssetNotFoundException("Operation lease was not found"));
    lockRentalItemAndLease(rentalItemId);
    return operationLeases
        .findByIdForUpdate(id)
        .orElseThrow(() -> new AssetNotFoundException("Operation lease was not found"));
  }

  OperationLease validate(UUID rentalItemId, UUID leaseId, long fencingToken) {
    lockRentalItemAndLease(rentalItemId);
    expire(rentalItemId);
    OperationLease lease =
        operationLeases
            .findByIdForUpdate(leaseId)
            .orElseThrow(() -> new AssetNotFoundException("Operation lease was not found"));
    if (!lease.getRentalItemId().equals(rentalItemId)) {
      throw new AssetConflictException("Operation lease belongs to another rental item");
    }
    assertFencing(lease, fencingToken, now());
    return lease;
  }

  void assertNoActive(UUID rentalItemId) {
    lockRentalItemAndLease(rentalItemId);
    expire(rentalItemId);
    if (!activeForUpdate(rentalItemId).isEmpty()) {
      throw new AssetConflictException(
          "Rental item has an active operation lease; use a fenced internal command");
    }
  }

  void assertNoActiveOrderReservation(UUID rentalItemId, String message) {
    assertNoActiveOrderReservation(rentalItemId, null, message);
  }

  void assertNoActiveOrderReservation(UUID rentalItemId, String code, String message) {
    Boolean active =
        jdbc.queryForObject(
            """
            select exists(
              select 1 from order_unit_reservation
              where rental_item_id=? and state='ACTIVE'
            )
            """,
            Boolean.class,
            rentalItemId);
    if (Boolean.TRUE.equals(active)) {
      if (code != null) {
        throw new OrderUnitReservationConflictException(code, message);
      }
      throw new AssetConflictException(message);
    }
  }

  /**
   * Rejects physical cabin mutations while an unexpired client-presentation hold owns the cabin
   * snapshot. Callers must acquire {@link #lockRentalItem(UUID)} first so hold creation and the
   * attempted mutation have one serialization point.
   */
  void assertNoActivePresentationHold(UUID rentalItemId) {
    Boolean active =
        jdbc.queryForObject(
            """
            select exists(
              select 1 from presentation_unit_hold
              where rental_item_id=?
                and state='ACTIVE'
                and expires_at>clock_timestamp()
            )
            """,
            Boolean.class,
            rentalItemId);
    if (Boolean.TRUE.equals(active)) {
      throw new AssetConflictException(
          "Presentation-held rental item contents cannot change before release or conversion");
    }
  }

  /**
   * Prevents maintenance from taking custody of a replaced cabin while its atomically prepared
   * old-to-new furniture movement still owns a physical source hold.
   */
  void assertNoPendingReplacementFurnitureMovement(UUID rentalItemId) {
    Boolean pending =
        jdbc.queryForObject(
            """
            select exists(
              select 1
              from equipment_allocation_hold movement_hold
              join equipment_balance source_balance
                on source_balance.id=movement_hold.source_balance_id
              where source_balance.rental_item_id=?
                and movement_hold.owner_type='LOGISTICS_EQUIPMENT_MOVEMENT'
                and movement_hold.replacement_source_reservation_id is not null
                and movement_hold.state='ACTIVE'
                and movement_hold.expires_at>clock_timestamp()
            )
            """,
            Boolean.class,
            rentalItemId);
    if (Boolean.TRUE.equals(pending)) {
      throw new AssetConflictException(
          "Replacement cabin furniture movement must finish before maintenance starts");
    }
  }

  List<OperationLease> activeForUpdate(UUID rentalItemId) {
    List<OperationLease> active =
        operationLeases.findByRentalItemIdAndStateForUpdate(rentalItemId, OperationLeaseState.ACTIVE);
    if (active.size() > 1) {
      throw new IllegalStateException("Active operation-lease uniqueness is corrupted");
    }
    return active;
  }

  void expire(UUID rentalItemId) {
    expire(rentalItemId, now());
  }

  /** Expires rows against one fixed clock boundary shared by the surrounding recovery command. */
  private void expire(UUID rentalItemId, OffsetDateTime expiredAt) {
    List<OperationLease> expired =
        operationLeases.findExpiredByRentalItemIdAndStateForUpdate(
            rentalItemId, OperationLeaseState.ACTIVE, expiredAt);
    for (OperationLease current : expired) {
      long expectedVersion = current.getVersion();
      if (!current.expire(expiredAt)) {
        continue;
      }
      OperationLeaseResponse updated = response(operationLeases.saveAndFlush(current));
      events.append(
          AssetAggregateType.OPERATION_LEASE,
          current.getId(),
          expectedVersion,
          AssetEventType.OPERATION_LEASE_EXPIRED,
          fact(updated),
          snapshot(updated));
    }
  }

  /**
   * Ends the active lease, if any, because a later completed inventory has replaced its physical
   * cabin truth. Expired and released rows remain untouched as historical evidence.
   */
  List<UUID> releaseForCompletedInventory(UUID rentalItemId) {
    return releaseForCompletedInventory(rentalItemId, Set.of());
  }

  /**
   * Ends every active lease except an explicitly retained owner type during a completed-inventory
   * reassertion. Expired and released rows remain historical evidence and each transition emits the
   * ordinary lease event.
   */
  List<UUID> releaseForCompletedInventory(UUID rentalItemId, Set<String> retainedOwnerTypes) {
    Set<String> retained =
        Set.copyOf(Objects.requireNonNull(retainedOwnerTypes, "retainedOwnerTypes"));
    lockRentalItemAndLease(rentalItemId);
    OffsetDateTime releasedAt = now();
    expire(rentalItemId, releasedAt);
    List<UUID> released = new java.util.ArrayList<>();
    for (OperationLease current : activeForUpdate(rentalItemId)) {
      if (retained.contains(current.getOwnerType())) {
        continue;
      }
      long expectedVersion = current.getVersion();
      current.release(releasedAt);
      OperationLeaseResponse updated = response(operationLeases.saveAndFlush(current));
      events.append(
          AssetAggregateType.OPERATION_LEASE,
          current.getId(),
          expectedVersion,
          AssetEventType.OPERATION_LEASE_RELEASED,
          fact(updated),
          snapshot(updated));
      released.add(current.getId());
    }
    return List.copyOf(released);
  }

  OperationLeaseResponse acquire(
      UUID rentalItemId, String ownerType, String ownerId, UUID key) {
    long next = Math.addExact(operationLeases.maximumFencingToken(rentalItemId), 1);
    OffsetDateTime acquiredAt = now();
    OperationLease persisted =
        operationLeases.saveAndFlush(
            OperationLease.acquire(
                rentalItemId,
                ownerType,
                ownerId,
                next,
                key,
                acquiredAt,
                acquiredAt.plus(leaseTtl)));
    OperationLeaseResponse response = response(persisted);
    events.initialize(
        AssetAggregateType.OPERATION_LEASE,
        persisted.getId(),
        persisted.getVersion(),
        AssetEventType.OPERATION_LEASE_ACQUIRED,
        fact(response),
        snapshot(response));
    return response;
  }

  OperationLeaseResponse renewExisting(OperationLease current) {
    long expectedVersion = current.getVersion();
    OffsetDateTime renewedAt = now();
    current.renew(renewedAt, renewedAt.plus(leaseTtl));
    OperationLeaseResponse renewed = response(operationLeases.saveAndFlush(current));
    events.append(
        AssetAggregateType.OPERATION_LEASE,
        current.getId(),
        expectedVersion,
        AssetEventType.OPERATION_LEASE_RENEWED,
        fact(renewed),
        snapshot(renewed));
    return renewed;
  }

  OperationLeaseResponse renew(UUID id, Long expectedVersion, long fencingToken) {
    OperationLease current = requireForUpdate(id);
    assertVersion(current.getVersion(), expectedVersion);
    OffsetDateTime renewedAt = now();
    assertFencing(current, fencingToken, renewedAt);
    assertRentalItemAllowsLeaseEffects(rentals.require(current.getRentalItemId()));
    current.renew(renewedAt, renewedAt.plus(leaseTtl));
    OperationLeaseResponse updated = response(operationLeases.saveAndFlush(current));
    events.append(
        AssetAggregateType.OPERATION_LEASE,
        id,
        expectedVersion,
        AssetEventType.OPERATION_LEASE_RENEWED,
        fact(updated),
        snapshot(updated));
    return updated;
  }

  OperationLeaseResponse release(UUID id, Long expectedVersion, long fencingToken) {
    OperationLease current = requireForUpdate(id);
    assertVersion(current.getVersion(), expectedVersion);
    OffsetDateTime releasedAt = now();
    assertFencing(current, fencingToken, releasedAt);
    current.release(releasedAt);
    OperationLeaseResponse updated = response(operationLeases.saveAndFlush(current));
    events.append(
        AssetAggregateType.OPERATION_LEASE,
        id,
        expectedVersion,
        AssetEventType.OPERATION_LEASE_RELEASED,
        fact(updated),
        snapshot(updated));
    return updated;
  }

  OperationLeaseResponse response(OperationLease lease) {
    return new OperationLeaseResponse(
        lease.getId(),
        lease.getVersion(),
        lease.getRentalItemId(),
        lease.getOwnerType(),
        lease.getOwnerId(),
        lease.getFencingToken(),
        lease.getState().name(),
        lease.getExpiresAt());
  }

  void assertFencing(OperationLease lease, long token, OffsetDateTime instant) {
    if (!lease.isActiveAt(instant) || lease.getFencingToken() != token) {
      throw new AssetConflictException("Operation lease is stale or fenced");
    }
  }

  static void assertVersion(long actual, Long expected) {
    if (expected == null || expected < 0) {
      throw new IllegalArgumentException("expectedVersion is required");
    }
    if (actual != expected) {
      throw new AssetConflictException("Asset data changed concurrently");
    }
  }

  static void assertRentalItemAllowsLeaseEffects(RentalItem item) {
    if (item.getStatus().isTerminalDispositionStatus()) {
      throw new AssetConflictException(
          item.getStatus() == RentalItemStatus.LOST
              ? "Lost rental item cannot receive lease or maintenance effects"
              : "Written-off rental item cannot receive lease or maintenance effects");
    }
  }

  static String rentalItemLockKey(UUID rentalItemId) {
    return "asset-rental-item:" + rentalItemId;
  }

  static String leaseLockKey(UUID rentalItemId) {
    return "lease:" + rentalItemId;
  }

  private static Duration requireTtl(Duration ttl, String name) {
    if (ttl == null
        || ttl.isNegative()
        || ttl.isZero()
        || ttl.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException(name + " ttl must be between 1 ms and 1 h");
    }
    return ttl;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static Map<String, ?> fact(OperationLeaseResponse value) {
    return Map.of(
        "leaseId", value.id().toString(),
        "rentalItemId", value.rentalItemId().toString(),
        "fencingToken", value.fencingToken(),
        "state", value.state());
  }

  private static Map<String, ?> snapshot(OperationLeaseResponse value) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("leaseId", value.id().toString());
    snapshot.put("version", value.version());
    snapshot.put("rentalItemId", value.rentalItemId().toString());
    snapshot.put("ownerType", value.ownerType());
    snapshot.put("ownerId", value.ownerId());
    snapshot.put("fencingToken", value.fencingToken());
    snapshot.put("state", value.state());
    snapshot.put("expiresAt", value.expiresAt().toString());
    return snapshot;
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value);
  }
}
