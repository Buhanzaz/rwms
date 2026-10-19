package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentHoldResponse;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns allocation-hold row access, availability blocking and expiry events for physical balances.
 *
 * <p>It deliberately exposes semantic hold operations rather than its table or repositories. The
 * calling use case owns owner validation, idempotency and the enclosing transaction.
 */
@Service
final class AssetEquipmentHoldService {
  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final Duration holdTtl;

  AssetEquipmentHoldService(
      JdbcTemplate jdbc,
      AssetEventStore events,
      @Value("${rwms.asset.equipment-hold.ttl:15m}") Duration holdTtl) {
    this.jdbc = jdbc;
    this.events = events;
    this.holdTtl = requireTtl(holdTtl, "equipment hold");
  }

  OffsetDateTime expiry() {
    return OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(holdTtl);
  }

  long activeHeld(AssetBalanceRow source) {
    return activeHeldExcluding(source, Set.of());
  }

  long activeHeldExcluding(AssetBalanceRow source, Set<UUID> excludedReservationIds) {
    List<HoldQuantity> holds =
        jdbc.query(
            """
            select id,quantity from equipment_allocation_hold
            where (
              source_balance_id=?
              or (
                source_balance_id is null
                and ?
                and equipment_id=?
                and warehouse_id=?
              )
            )
              and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
            """,
            (rs, row) -> new HoldQuantity(rs.getObject("id", UUID.class), rs.getLong("quantity")),
            source.id(),
            source.kind() == BalanceLocationKind.STOCK,
            source.equipmentId(),
            source.warehouseId());
    return holds.stream()
        .filter(hold -> !excludedReservationIds.contains(hold.id()))
        .mapToLong(HoldQuantity::quantity)
        .sum();
  }

  EquipmentHoldResponse read(UUID id) {
    return jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
              quantity,state,expires_at,committed_at,executed_at
            from equipment_allocation_hold where id=?
            """,
            (rs, row) -> row(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }

  EquipmentHoldResponse forUpdate(UUID id) {
    return jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
              quantity,state,expires_at,committed_at,executed_at
            from equipment_allocation_hold where id=? for update
            """,
            (rs, row) -> row(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }

  void expireFor(AssetBalanceRow source) {
    expireFor(
        source.id(),
        source.equipmentId(),
        source.warehouseId(),
        source.kind() == BalanceLocationKind.STOCK);
  }

  void expireFor(EquipmentHoldResponse hold) {
    expireFor(
        hold.sourceBalanceId(),
        hold.equipmentId(),
        hold.warehouseId(),
        hold.sourceBalanceId() == null);
  }

  /** Persists at most 200 expired holds for the scheduled facade invocation. */
  void expireDue() {
    List<EquipmentHoldResponse> expired =
        jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
              quantity,state,expires_at,committed_at,executed_at
            from equipment_allocation_hold
            where state='ACTIVE' and expires_at<=clock_timestamp()
            order by expires_at,id
            for update skip locked
            limit 200
            """,
            (rs, row) -> row(rs));
    expired.forEach(this::expire);
  }

  /**
   * Releases the already locked live or committed holds superseded by an authoritative completed
   * inventory. Each changed hold retains its row and emits the ordinary release fact.
   */
  List<UUID> releaseForCompletedInventory(List<UUID> holdIds) {
    if (holdIds == null || holdIds.isEmpty()) return List.of();
    List<UUID> released = new java.util.ArrayList<>();
    holdIds.stream()
        .distinct()
        .sorted(java.util.Comparator.comparing(UUID::toString))
        .forEach(
            id -> {
              EquipmentHoldResponse current = forUpdate(id);
              if (!"ACTIVE".equals(current.state()) && !"COMMITTED".equals(current.state())) {
                return;
              }
              int changed =
                  jdbc.update(
                      """
                      update equipment_allocation_hold
                      set version=version+1,state='RELEASED',released_at=clock_timestamp(),
                          updated_at=clock_timestamp()
                      where id=? and version=? and state in ('ACTIVE','COMMITTED')
                      """,
                      id,
                      current.version());
              if (changed != 1) {
                throw new AssetConflictException(
                    "Equipment hold changed concurrently during inventory supersession");
              }
              EquipmentHoldResponse updated = forUpdate(id);
              events.append(
                  AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
                  id,
                  current.version(),
                  AssetEventType.EQUIPMENT_HOLD_RELEASED,
                  fact(updated),
                  snapshot(updated));
              released.add(id);
            });
    return List.copyOf(released);
  }

  Map<String, ?> fact(EquipmentHoldResponse value) {
    // Facts must stay compatible with holds emitted before V7. The concrete source balance is
    // durable reservation state and belongs in the snapshot, not replay-verification facts.
    return Map.of(
        "holdId", value.id().toString(),
        "equipmentId", value.equipmentId().toString(),
        "warehouseId", value.warehouseId().toString(),
        "quantity", value.quantity(),
        "state", value.state());
  }

  Map<String, ?> snapshot(EquipmentHoldResponse value) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("holdId", value.id().toString());
    snapshot.put("version", value.version());
    snapshot.put("equipmentId", value.equipmentId().toString());
    snapshot.put("warehouseId", value.warehouseId().toString());
    snapshot.put("ownerType", value.ownerType());
    snapshot.put("ownerId", value.ownerId());
    snapshot.put(
        "sourceBalanceId",
        value.sourceBalanceId() == null ? null : value.sourceBalanceId().toString());
    snapshot.put("quantity", value.quantity());
    snapshot.put("state", value.state());
    snapshot.put("expiresAt", value.expiresAt().toString());
    snapshot.put(
        "committedAt", value.committedAt() == null ? null : value.committedAt().toString());
    snapshot.put(
        "executedAt", value.executedAt() == null ? null : value.executedAt().toString());
    return snapshot;
  }

  private void expireFor(
      UUID sourceBalanceId,
      UUID equipmentId,
      UUID warehouseId,
      boolean includeLegacyStockHolds) {
    List<EquipmentHoldResponse> expired =
        jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
              quantity,state,expires_at,committed_at,executed_at
            from equipment_allocation_hold
            where state='ACTIVE' and expires_at<=clock_timestamp()
              and (
                source_balance_id=?
                or (
                  source_balance_id is null
                  and ?
                  and equipment_id=?
                  and warehouse_id=?
                )
              )
            for update
            """,
            (rs, row) -> row(rs),
            sourceBalanceId,
            includeLegacyStockHolds,
            equipmentId,
            warehouseId);
    expired.forEach(this::expire);
  }

  private void expire(EquipmentHoldResponse current) {
    int changed =
        jdbc.update(
            """
            update equipment_allocation_hold
            set state='EXPIRED',released_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp()
            where id=? and version=? and state='ACTIVE'
            """,
            current.id(),
            current.version());
    if (changed != 1) {
      throw new AssetConflictException("Equipment hold changed concurrently during expiry");
    }
    EquipmentHoldResponse updated = forUpdate(current.id());
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        current.id(),
        current.version(),
        AssetEventType.EQUIPMENT_HOLD_EXPIRED,
        fact(updated),
        snapshot(updated));
  }

  private static EquipmentHoldResponse row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new EquipmentHoldResponse(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getString("owner_type"),
        rs.getString("owner_id"),
        rs.getObject("source_balance_id", UUID.class),
        rs.getLong("quantity"),
        rs.getString("state"),
        rs.getObject("expires_at", OffsetDateTime.class),
        rs.getObject("committed_at", OffsetDateTime.class),
        rs.getObject("executed_at", OffsetDateTime.class));
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

  /** Minimal active-hold projection used to calculate quantity unavailable to other owners. */
  private record HoldQuantity(UUID id, long quantity) {}
}
