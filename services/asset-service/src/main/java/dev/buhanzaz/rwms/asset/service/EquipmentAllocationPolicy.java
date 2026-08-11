package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One SQL-backed definition of a source that may be promised to a new order or
 * ordinary furniture movement.
 *
 * <p>Stock is allocatable. Furniture inside a cabin is allocatable only while
 * the cabin is explicitly FREE or WAREHOUSE and has neither an active order
 * reservation, live client-presentation hold or live operation lease. Workflow,
 * rented, sale, own-needs and terminal cabins remain visible in totals but never
 * contribute to availability.
 */
@Component
public class EquipmentAllocationPolicy {
  private static final String SOURCE_SQL =
      """
      select balance.id,balance.version,balance.equipment_id,balance.warehouse_id,
        balance.rental_item_id,balance.location_kind,balance.quantity,
        coalesce((
          select sum(hold.quantity)
          from equipment_allocation_hold hold
          where (
              hold.source_balance_id=balance.id
              or (
                hold.source_balance_id is null
                and balance.location_kind='STOCK'
                and hold.equipment_id=balance.equipment_id
                and hold.warehouse_id=balance.warehouse_id
              )
            )
            and (
              hold.state='COMMITTED'
              or (hold.state='ACTIVE' and hold.expires_at>clock_timestamp())
            )
        ),0) active_held_quantity,
        case
          when balance.location_kind='STOCK' then true
          when balance.location_kind='CABIN_NON_RENTED'
            and rental.status in ('FREE','WAREHOUSE')
            and not exists (
              select 1
              from order_unit_reservation reservation
              where reservation.rental_item_id=balance.rental_item_id
                and reservation.state='ACTIVE'
            )
            and not exists (
              select 1
              from presentation_unit_hold presentation_hold
              where presentation_hold.rental_item_id=balance.rental_item_id
                and presentation_hold.state='ACTIVE'
                and presentation_hold.expires_at>clock_timestamp()
            )
            and not exists (
              select 1
              from operation_lease lease
              where lease.rental_item_id=balance.rental_item_id
                and lease.state='ACTIVE'
                and lease.expires_at>clock_timestamp()
            )
            then true
          else false
        end allocatable
      from equipment_balance balance
      left join rental_item rental on rental.id=balance.rental_item_id
      where balance.warehouse_id=?
        and (?::uuid is null or balance.equipment_id=?)
      order by balance.equipment_id,balance.location_kind,balance.rental_item_id
      """;

  private final JdbcTemplate jdbc;

  public EquipmentAllocationPolicy(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<SourceAvailability> sources(UUID equipmentId, UUID warehouseId) {
    return querySources(warehouseId, equipmentId);
  }

  /**
   * Reads the complete warehouse allocation snapshot in one bounded SQL statement. This is the
   * list-boundary equivalent of {@link #sources(UUID, UUID)} and prevents one balance/hold/lease
   * query per catalog item.
   */
  public Map<UUID, List<SourceAvailability>> sourcesAtWarehouse(UUID warehouseId) {
    List<SourceAvailability> values = querySources(warehouseId, null);
    return values.stream()
        .collect(
            Collectors.groupingBy(
                SourceAvailability::equipmentId, LinkedHashMap::new, Collectors.toList()));
  }

  private List<SourceAvailability> querySources(UUID warehouseId, UUID equipmentId) {
    return jdbc.query(
        SOURCE_SQL,
        (result, row) ->
            new SourceAvailability(
                result.getObject("id", UUID.class),
                result.getLong("version"),
                result.getObject("equipment_id", UUID.class),
                result.getObject("warehouse_id", UUID.class),
                result.getObject("rental_item_id", UUID.class),
                BalanceLocationKind.valueOf(result.getString("location_kind")),
                result.getLong("quantity"),
                result.getLong("active_held_quantity"),
                result.getBoolean("allocatable")),
        warehouseId,
        equipmentId,
        equipmentId);
  }

  public record SourceAvailability(
      UUID balanceId,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity,
      long activeHeldQuantity,
      boolean allocatable) {
    public long availableQuantity() {
      return allocatable ? Math.max(0, Math.subtractExact(quantity, activeHeldQuantity)) : 0;
    }
  }
}
