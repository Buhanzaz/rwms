package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentBalanceResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsReturnEquipmentReceiptLine;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsReturnEquipmentReceiptLineResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MovementResponse;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns physical equipment-balance rows, balance locks and append-only movement evidence.
 *
 * <p>It does not decide whether a caller is authorized to move stock or which workflow owns a
 * hold. Callers supply an already validated command inside their transaction; this service
 * preserves the canonical balance/location and event rules.
 */
@Service
final class AssetEquipmentLedgerService {
  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final RentalItemRepository rentalItems;
  private final AssetEquipmentHoldService holds;

  AssetEquipmentLedgerService(
      JdbcTemplate jdbc,
      AssetEventStore events,
      RentalItemRepository rentalItems,
      AssetEquipmentHoldService holds) {
    this.jdbc = jdbc;
    this.events = events;
    this.rentalItems = rentalItems;
    this.holds = holds;
  }

  List<AssetBalanceRow> balances(UUID equipmentId, UUID warehouseId) {
    return jdbc.query(
        "select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where equipment_id=? and warehouse_id=? order by location_kind,rental_item_id",
        (rs, row) -> row(rs),
        equipmentId,
        warehouseId);
  }

  Optional<AssetBalanceRow> findForUpdate(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return jdbc
        .query(
            "select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ? and location_kind=? for update",
            (rs, row) -> row(rs),
            equipmentId,
            warehouseId,
            rentalItemId,
            kind.name())
        .stream()
        .findFirst();
  }

  AssetBalanceRow require(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return findForUpdate(equipmentId, warehouseId, rentalItemId, kind)
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  AssetBalanceRow requireForUpdate(UUID id) {
    return jdbc
        .query(
            "select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where id=? for update",
            (rs, row) -> row(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  AssetBalanceRow read(UUID id) {
    return jdbc
        .query(
            "select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where id=?",
            (rs, row) -> row(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  AssetBalanceRow createEmpty(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into equipment_balance(id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at) values (?,0,?,?,?,?,0,clock_timestamp(),clock_timestamp())",
        id,
        equipmentId,
        warehouseId,
        rentalItemId,
        kind.name());
    AssetBalanceRow row = requireForUpdate(id);
    events.initialize(
        AssetAggregateType.EQUIPMENT_BALANCE,
        id,
        0,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        fact(row),
        snapshot(row));
    return row;
  }

  void decrement(AssetBalanceRow row, long amount, long expectedVersion) {
    int changed =
        jdbc.update(
            "update equipment_balance set quantity=quantity-?,version=version+1,updated_at=clock_timestamp() where id=? and version=? and quantity>=?",
            amount,
            row.id(),
            expectedVersion,
            amount);
    if (changed != 1) {
      throw new AssetConflictException(
          "Equipment balance changed concurrently or cannot become negative");
    }
  }

  void increment(AssetBalanceRow row, long amount, long expectedVersion) {
    int changed =
        jdbc.update(
            "update equipment_balance set quantity=quantity+?,version=version+1,updated_at=clock_timestamp() where id=? and version=?",
            amount,
            row.id(),
            expectedVersion);
    if (changed != 1) {
      throw new AssetConflictException("Target equipment balance changed concurrently");
    }
  }

  void lock(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    advisoryLock(balanceLockKey(equipmentId, warehouseId, rentalItemId, kind));
  }

  void lockAll(Collection<String> values) {
    values.stream().filter(java.util.Objects::nonNull).distinct().sorted().forEach(this::advisoryLock);
  }

  void validateLocation(UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    boolean cabin =
        kind == BalanceLocationKind.CABIN_NON_RENTED || kind == BalanceLocationKind.CABIN_RENTED;
    if (cabin != (rentalItemId != null)) {
      throw new IllegalArgumentException("Balance location does not match cabin reference");
    }
    if (!cabin) {
      return;
    }
    RentalItem item =
        rentalItems
            .findById(rentalItemId)
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!warehouseId.equals(item.getWarehouseId())) {
      throw new AssetConflictException("Cabin must belong to the balance warehouse");
    }
    BalanceLocationKind expected =
        item.getStatus() == RentalItemStatus.RENTED
            ? BalanceLocationKind.CABIN_RENTED
            : BalanceLocationKind.CABIN_NON_RENTED;
    if (kind != expected) {
      throw new AssetConflictException("Cabin balance location does not match canonical cabin status");
    }
  }

  void reclassifyCabinBalances(RentalItem item, RentalItemStatus previous) {
    BalanceLocationKind before =
        previous == RentalItemStatus.RENTED
            ? BalanceLocationKind.CABIN_RENTED
            : BalanceLocationKind.CABIN_NON_RENTED;
    BalanceLocationKind after =
        item.getStatus() == RentalItemStatus.RENTED
            ? BalanceLocationKind.CABIN_RENTED
            : BalanceLocationKind.CABIN_NON_RENTED;
    if (before == after) {
      return;
    }
    List<AssetBalanceRow> candidates =
        jdbc.query(
            "select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where rental_item_id=? and location_kind=?",
            (rs, row) -> row(rs),
            item.getId(),
            before.name());
    lockAll(
        candidates.stream()
            .flatMap(
                row ->
                    java.util.stream.Stream.of(
                        balanceLockKey(
                            row.equipmentId(), row.warehouseId(), row.rentalItemId(), before),
                        balanceLockKey(
                            row.equipmentId(), row.warehouseId(), row.rentalItemId(), after)))
            .toList());
    events.lockStreams(
        candidates.stream()
            .map(row -> new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, row.id()))
            .toList());
    List<AssetBalanceRow> values =
        candidates.stream()
            .sorted(Comparator.comparing(row -> row.id().toString()))
            .map(row -> requireForUpdate(row.id()))
            .filter(row -> row.kind() == before)
            .toList();
    for (AssetBalanceRow row : values) {
      int updateCount =
          jdbc.update(
              "update equipment_balance set location_kind=?,version=version+1,updated_at=clock_timestamp() where id=? and version=?",
              after.name(),
              row.id(),
              row.version());
      if (updateCount != 1) {
        throw new AssetConflictException(
            "Equipment balance changed concurrently during cabin status reclassification");
      }
      AssetBalanceRow updatedRow = requireForUpdate(row.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          row.id(),
          row.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          fact(updatedRow),
          snapshot(updatedRow));
    }
  }

  /**
   * Relocates non-zero cabin balances at transfer arrival under the already validated lease.
   *
   * <p>Zero source buckets remain historical origin rows. A caller must hold the rental-item and
   * lease fences before entering this method; the method additionally obtains the canonical
   * ordered balance locks.
   */
  void relocateCabinContentsUnderLease(
      UUID subjectId, UUID rentalItemId, UUID sourceWarehouseId, UUID destinationWarehouseId) {
    List<AssetBalanceRow> candidates =
        jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
            from equipment_balance
            where rental_item_id=? and warehouse_id=? and quantity>0
            order by equipment_id,id
            """,
            (rs, row) -> row(rs),
            rentalItemId,
            sourceWarehouseId);
    for (AssetBalanceRow candidate : candidates) {
      if (candidate.kind() != BalanceLocationKind.CABIN_NON_RENTED
          && candidate.kind() != BalanceLocationKind.CABIN_RENTED) {
        throw new AssetConflictException(
            "Only canonical cabin balances may move with a transfer arrival");
      }
    }
    lockAll(
        candidates.stream()
            .flatMap(
                row ->
                    java.util.stream.Stream.of(
                        balanceLockKey(row.equipmentId(), sourceWarehouseId, rentalItemId, row.kind()),
                        balanceLockKey(
                            row.equipmentId(), destinationWarehouseId, rentalItemId, row.kind())))
            .toList());
    for (AssetBalanceRow candidate : candidates) {
      AssetBalanceRow source = requireForUpdate(candidate.id());
      if (source.quantity() == 0) {
        continue;
      }
      if (!source.warehouseId().equals(sourceWarehouseId)
          || !rentalItemId.equals(source.rentalItemId())
          || source.kind() != candidate.kind()) {
        throw new AssetConflictException(
            "Cabin balance changed concurrently during transfer arrival");
      }
      if (holds.activeHeld(source) > 0) {
        throw new AssetConflictException(
            "Cabin relocation would consume quantities reserved by an active hold");
      }
      AssetBalanceRow target =
          findForUpdate(source.equipmentId(), destinationWarehouseId, rentalItemId, source.kind())
              .orElseGet(
                  () ->
                      createEmpty(
                          source.equipmentId(),
                          destinationWarehouseId,
                          rentalItemId,
                          source.kind()));
      if (target.quantity() != 0) {
        throw new AssetConflictException(
            "Destination cabin already has a canonical equipment balance");
      }
      events.lockStreams(
          List.of(
              new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, source.id()),
              new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, target.id())));
      long quantity = source.quantity();
      decrement(source, quantity, source.version());
      increment(target, quantity, target.version());
      AssetBalanceRow sourceAfter = requireForUpdate(source.id());
      AssetBalanceRow targetAfter = requireForUpdate(target.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          source.id(),
          source.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          fact(sourceAfter),
          snapshot(sourceAfter));
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          target.id(),
          target.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          fact(targetAfter),
          snapshot(targetAfter));
      UUID movementId = UUID.randomUUID();
      jdbc.update(
          """
          insert into equipment_movement(
            id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at,actor_subject_id)
          values (?,0,?,?,?,?,'CABIN_TO_CABIN',clock_timestamp(),?)
          """,
          movementId,
          source.equipmentId(),
          source.id(),
          target.id(),
          quantity,
          subjectId);
      jdbc.update(
          """
          insert into equipment_movement_ledger(movement_id,line_no,balance_id,quantity_delta,recorded_at)
          values (?,1,?,-?,clock_timestamp()), (?,2,?,?,clock_timestamp())
          """,
          movementId,
          source.id(),
          quantity,
          movementId,
          target.id(),
          quantity);
      MovementResponse movement = movementResponse(movementId);
      events.initialize(
          AssetAggregateType.EQUIPMENT_MOVEMENT,
          movementId,
          0,
          AssetEventType.EQUIPMENT_TRANSFERRED,
          movementFact(movement),
          Map.of("movementId", movementId.toString(), "version", 0));
    }
  }

  MovementResponse movementResponse(UUID id) {
    return jdbc
        .query(
            "select id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at from equipment_movement where id=?",
            (rs, row) ->
                new MovementResponse(
                    rs.getObject("id", UUID.class),
                    rs.getLong("version"),
                    rs.getObject("equipment_id", UUID.class),
                    rs.getObject("source_balance_id", UUID.class),
                    rs.getObject("target_balance_id", UUID.class),
                    rs.getLong("quantity"),
                    rs.getString("movement_kind"),
                    rs.getObject("occurred_at", OffsetDateTime.class)),
            id)
        .stream()
        .findFirst()
        .orElseThrow();
  }

  /**
   * Records a locked, category-validated logistics return as an immutable stock receipt. The
   * caller performs warehouse and catalog validation around the canonical balance locks; this
   * ledger method owns concrete stock rows, event streams and duplicate receipt evidence.
   */
  void lockReturnReceiptBalances(
      UUID warehouseId, List<LogisticsReturnEquipmentReceiptLine> lines) {
    lockAll(
        lines.stream()
            .map(
                line ->
                    balanceLockKey(
                        line.equipmentId(), warehouseId, null, BalanceLocationKind.STOCK))
            .toList());
  }

  List<LogisticsReturnEquipmentReceiptLineResponse> receiveReturnEquipment(
      UUID subjectId,
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<LogisticsReturnEquipmentReceiptLine> lines) {
    List<LogisticsReturnEquipmentReceiptLineResponse> received = new ArrayList<>();
    for (LogisticsReturnEquipmentReceiptLine line : lines) {
      Optional<ReturnEquipmentReceiptRow> existing =
          findReturnReceipt(returnId, returnLineId, line.equipmentId());
      if (existing.isPresent()) {
        ReturnEquipmentReceiptRow receipt = existing.get();
        if (!receipt.warehouseId().equals(warehouseId) || receipt.quantity() != line.quantity()) {
          throw new AssetConflictException(
              "Return equipment receipt conflicts with existing immutable evidence");
        }
        AssetBalanceRow target = read(receipt.stockBalanceId());
        requireStockReceiptBalance(target, line.equipmentId(), warehouseId);
        received.add(returnReceiptResponse(receipt, target));
        continue;
      }
      AssetBalanceRow target =
          findForUpdate(line.equipmentId(), warehouseId, null, BalanceLocationKind.STOCK)
              .orElseGet(
                  () -> createEmpty(line.equipmentId(), warehouseId, null, BalanceLocationKind.STOCK));
      long streamVersion = events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, target.id());
      if (streamVersion != target.version()) {
        throw new AssetConflictException("Equipment stock balance changed concurrently");
      }
      increment(target, line.quantity(), streamVersion);
      AssetBalanceRow targetAfter = requireForUpdate(target.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          target.id(),
          streamVersion,
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          fact(targetAfter),
          snapshot(targetAfter));
      UUID receiptId = UUID.randomUUID();
      jdbc.update(
          """
          insert into logistics_return_equipment_receipt(
            id,return_id,return_line_id,equipment_id,warehouse_id,stock_balance_id,quantity,received_at,actor_subject_id)
          values (?,?,?,?,?,?,?,clock_timestamp(),?)
          """,
          receiptId,
          returnId,
          returnLineId,
          line.equipmentId(),
          warehouseId,
          targetAfter.id(),
          line.quantity(),
          subjectId);
      received.add(
          new LogisticsReturnEquipmentReceiptLineResponse(
              receiptId,
              line.equipmentId(),
              line.quantity(),
              targetAfter.id(),
              targetAfter.version(),
              targetAfter.quantity()));
    }
    return List.copyOf(received);
  }

  Map<String, ?> fact(AssetBalanceRow row) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("balanceId", row.id().toString());
    value.put("equipmentId", row.equipmentId().toString());
    value.put("warehouseId", row.warehouseId().toString());
    value.put("rentalItemId", row.rentalItemId() == null ? null : row.rentalItemId().toString());
    value.put("locationKind", row.kind().name());
    value.put("quantity", row.quantity());
    return value;
  }

  Map<String, ?> snapshot(AssetBalanceRow row) {
    return fact(row);
  }

  Map<String, ?> movementFact(MovementResponse value) {
    MovementContext context = movementContext(value.id());
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("movementId", value.id().toString());
    fact.put("equipmentId", value.equipmentId().toString());
    fact.put("sourceBalanceId", value.sourceBalanceId().toString());
    fact.put("targetBalanceId", value.targetBalanceId().toString());
    fact.put("quantity", value.quantity());
    fact.put("movementKind", value.kind());
    if (context.exact()) {
      fact.put("equipmentCategory", context.equipmentCategory());
      fact.put("sourceWarehouseId", context.sourceWarehouseId().toString());
      fact.put(
          "sourceRentalItemId",
          context.sourceRentalItemId() == null ? null : context.sourceRentalItemId().toString());
      fact.put("sourceLocationKind", context.sourceLocationKind());
      fact.put("targetWarehouseId", context.targetWarehouseId().toString());
      fact.put(
          "targetRentalItemId",
          context.targetRentalItemId() == null ? null : context.targetRentalItemId().toString());
      fact.put("targetLocationKind", context.targetLocationKind());
    }
    return fact;
  }

  static long sum(List<AssetBalanceRow> values, BalanceLocationKind kind) {
    return values.stream().filter(row -> row.kind() == kind).mapToLong(AssetBalanceRow::quantity).sum();
  }

  static boolean isTerminalLocation(BalanceLocationKind location) {
    return location == BalanceLocationKind.WRITTEN_OFF || location == BalanceLocationKind.LOST;
  }

  static String movementKind(BalanceLocationKind source, BalanceLocationKind target) {
    if (target == BalanceLocationKind.WRITTEN_OFF) {
      return "WRITE_OFF";
    }
    if (target == BalanceLocationKind.LOST) {
      return "LOSS";
    }
    if (source == BalanceLocationKind.STOCK
        && (target == BalanceLocationKind.CABIN_NON_RENTED
            || target == BalanceLocationKind.CABIN_RENTED)) {
      return "STOCK_TO_CABIN";
    }
    if ((source == BalanceLocationKind.CABIN_NON_RENTED
            || source == BalanceLocationKind.CABIN_RENTED)
        && target == BalanceLocationKind.STOCK) {
      return "CABIN_TO_STOCK";
    }
    if ((source == BalanceLocationKind.CABIN_NON_RENTED
            || source == BalanceLocationKind.CABIN_RENTED)
        && (target == BalanceLocationKind.CABIN_NON_RENTED
            || target == BalanceLocationKind.CABIN_RENTED)) {
      return "CABIN_TO_CABIN";
    }
    return "WAREHOUSE_TO_WAREHOUSE";
  }

  static AssetEventType movementEventType(String kind) {
    return switch (kind) {
      case "WRITE_OFF" -> AssetEventType.EQUIPMENT_WRITTEN_OFF;
      case "LOSS" -> AssetEventType.EQUIPMENT_LOST;
      default -> AssetEventType.EQUIPMENT_TRANSFERRED;
    };
  }

  static EquipmentBalanceResponse response(EquipmentAllocationPolicy.SourceAvailability source) {
    return new EquipmentBalanceResponse(
        source.balanceId(),
        source.version(),
        source.equipmentId(),
        source.warehouseId(),
        source.rentalItemId(),
        source.locationKind(),
        source.quantity(),
        source.activeHeldQuantity(),
        source.allocatable(),
        source.availableQuantity());
  }

  static String balanceLockKey(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return "asset-balance:"
        + equipmentId
        + ':'
        + warehouseId
        + ':'
        + (rentalItemId == null ? "-" : rentalItemId)
        + ':'
        + kind.name();
  }

  private MovementContext movementContext(UUID movementId) {
    return jdbc
        .query(
            """
            select equipment_category_snapshot,
              source_warehouse_id,source_rental_item_id,source_location_kind,
              target_warehouse_id,target_rental_item_id,target_location_kind,
              capture_origin
            from equipment_movement_context
            where movement_id=?
            """,
            (rs, row) ->
                new MovementContext(
                    rs.getString("equipment_category_snapshot"),
                    rs.getObject("source_warehouse_id", UUID.class),
                    rs.getObject("source_rental_item_id", UUID.class),
                    rs.getString("source_location_kind"),
                    rs.getObject("target_warehouse_id", UUID.class),
                    rs.getObject("target_rental_item_id", UUID.class),
                    rs.getString("target_location_kind"),
                    "AT_MOVEMENT".equals(rs.getString("capture_origin"))),
            movementId)
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException("Equipment movement context is missing for " + movementId));
  }

  private Optional<ReturnEquipmentReceiptRow> findReturnReceipt(
      UUID returnId, UUID returnLineId, UUID equipmentId) {
    return jdbc
        .query(
            """
            select id,return_id,return_line_id,equipment_id,warehouse_id,stock_balance_id,quantity
            from logistics_return_equipment_receipt
            where return_id=? and return_line_id=? and equipment_id=?
            """,
            (rs, row) ->
                new ReturnEquipmentReceiptRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("return_id", UUID.class),
                    rs.getObject("return_line_id", UUID.class),
                    rs.getObject("equipment_id", UUID.class),
                    rs.getObject("warehouse_id", UUID.class),
                    rs.getObject("stock_balance_id", UUID.class),
                    rs.getLong("quantity")),
            returnId,
            returnLineId,
            equipmentId)
        .stream()
        .findFirst();
  }

  private static void requireStockReceiptBalance(
      AssetBalanceRow balance, UUID equipmentId, UUID warehouseId) {
    if (!balance.equipmentId().equals(equipmentId)
        || !balance.warehouseId().equals(warehouseId)
        || balance.rentalItemId() != null
        || balance.kind() != BalanceLocationKind.STOCK) {
      throw new AssetConflictException("Return equipment receipt stock balance is malformed");
    }
  }

  private static LogisticsReturnEquipmentReceiptLineResponse returnReceiptResponse(
      ReturnEquipmentReceiptRow receipt, AssetBalanceRow balance) {
    return new LogisticsReturnEquipmentReceiptLineResponse(
        receipt.id(),
        receipt.equipmentId(),
        receipt.quantity(),
        balance.id(),
        balance.version(),
        balance.quantity());
  }

  private static AssetBalanceRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AssetBalanceRow(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("rental_item_id", UUID.class),
        BalanceLocationKind.valueOf(rs.getString("location_kind")),
        rs.getLong("quantity"));
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value);
  }

  /**
   * Normalized source and target coordinates recorded with a physical movement for deterministic
   * replay validation.
   */
  private record MovementContext(
      String equipmentCategory,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      boolean exact) {}

  /**
   * Immutable return-line receipt used to replay an already accepted quantity without writing a
   * second balance movement.
   */
  private record ReturnEquipmentReceiptRow(
      UUID id,
      UUID returnId,
      UUID returnLineId,
      UUID equipmentId,
      UUID warehouseId,
      UUID stockBalanceId,
      long quantity) {}
}
