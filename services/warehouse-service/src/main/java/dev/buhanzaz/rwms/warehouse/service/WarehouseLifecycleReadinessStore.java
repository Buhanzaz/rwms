package dev.buhanzaz.rwms.warehouse.service;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Immutable, owner-authenticated evidence that a resource owner has drained this warehouse. */
@Service
public class WarehouseLifecycleReadinessStore {
  private final JdbcTemplate jdbc;

  public WarehouseLifecycleReadinessStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Optional<Confirmation> find(UUID warehouseId, WarehouseLifecycleReadinessOwner owner) {
    List<Confirmation> records =
        jdbc.query(
            """
            select readiness_owner, warehouse_version, confirmed_at
              from warehouse_lifecycle_readiness
             where warehouse_id=? and readiness_owner=?
            """,
            (resultSet, rowNumber) ->
                new Confirmation(
                    WarehouseLifecycleReadinessOwner.valueOf(
                        resultSet.getString("readiness_owner")),
                    resultSet.getLong("warehouse_version"),
                    resultSet.getObject("confirmed_at", OffsetDateTime.class)),
            warehouseId,
            owner.name());
    return records.stream().findFirst();
  }

  @Transactional(readOnly = true)
  public Set<WarehouseLifecycleReadinessOwner> missing(UUID warehouseId) {
    Set<WarehouseLifecycleReadinessOwner> missing =
        EnumSet.allOf(WarehouseLifecycleReadinessOwner.class);
    for (String owner :
        jdbc.queryForList(
            "select readiness_owner from warehouse_lifecycle_readiness where warehouse_id=?",
            String.class,
            warehouseId)) {
      missing.remove(WarehouseLifecycleReadinessOwner.valueOf(owner));
    }
    return Set.copyOf(missing);
  }

  /**
   * Returns only work still owed by one authenticated owner. The UUID keyset is deliberately
   * stateless: a subsequent reconciliation from the beginning catches warehouses that start
   * draining between pages.
   */
  @Transactional(readOnly = true)
  public WorkPage pendingWork(
      WarehouseLifecycleReadinessOwner owner, UUID after, int limit) {
    if (owner == null) throw new IllegalArgumentException("lifecycle readiness owner is required");
    if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
    String query =
        after == null
            ? """
              select warehouse.id, warehouse.version
                from warehouse
               where warehouse.lifecycle_state='DRAINING'
                 and not exists (
                   select 1
                     from warehouse_lifecycle_readiness readiness
                    where readiness.warehouse_id=warehouse.id
                      and readiness.readiness_owner=?)
               order by warehouse.id
               limit ?
              """
            : """
              select warehouse.id, warehouse.version
                from warehouse
               where warehouse.lifecycle_state='DRAINING'
                 and warehouse.id > ?
                 and not exists (
                   select 1
                     from warehouse_lifecycle_readiness readiness
                    where readiness.warehouse_id=warehouse.id
                      and readiness.readiness_owner=?)
               order by warehouse.id
               limit ?
              """;
    List<PendingWarehouse> candidates =
        after == null
            ? jdbc.query(
                query,
                (resultSet, rowNumber) ->
                    new PendingWarehouse(
                        resultSet.getObject("id", UUID.class), resultSet.getLong("version")),
                owner.name(),
                limit + 1)
            : jdbc.query(
                query,
                (resultSet, rowNumber) ->
                    new PendingWarehouse(
                        resultSet.getObject("id", UUID.class), resultSet.getLong("version")),
                after,
                owner.name(),
                limit + 1);
    boolean hasMore = candidates.size() > limit;
    List<PendingWarehouse> items =
        hasMore ? List.copyOf(candidates.subList(0, limit)) : List.copyOf(candidates);
    UUID nextAfter = hasMore ? items.getLast().warehouseId() : null;
    return new WorkPage(items, nextAfter);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Confirmation record(
      UUID warehouseId,
      WarehouseLifecycleReadinessOwner owner,
      long warehouseVersion,
      OffsetDateTime confirmedAt) {
    if (owner == null) throw new IllegalArgumentException("lifecycle readiness owner is required");
    if (warehouseVersion < 0) throw new IllegalArgumentException("warehouseVersion must not be negative");
    if (confirmedAt == null) throw new IllegalArgumentException("confirmedAt is required");
    int inserted =
        jdbc.update(
            """
            insert into warehouse_lifecycle_readiness(
                warehouse_id,readiness_owner,warehouse_version,confirmed_at)
            values (?, ?, ?, ?)
            on conflict (warehouse_id,readiness_owner) do nothing
            """,
            warehouseId,
            owner.name(),
            warehouseVersion,
            confirmedAt);
    if (inserted == 1) return new Confirmation(owner, warehouseVersion, confirmedAt);
    return find(warehouseId, owner)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Warehouse lifecycle readiness record disappeared during confirmation"));
  }

  public record Confirmation(
      WarehouseLifecycleReadinessOwner owner, long warehouseVersion, OffsetDateTime confirmedAt) {}

  public record PendingWarehouse(UUID warehouseId, long warehouseVersion) {}

  public record WorkPage(List<PendingWarehouse> items, UUID nextAfter) {}
}
