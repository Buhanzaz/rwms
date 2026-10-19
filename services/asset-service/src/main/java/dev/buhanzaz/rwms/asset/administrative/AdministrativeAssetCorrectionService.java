package dev.buhanzaz.rwms.asset.administrative;

import static dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.*;

import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.AdministrativeCorrectionAssetKind;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * The sole public asset-service path for correcting an erroneous warehouse
 * record without claiming a physical move. It does not create an equipment
 * movement or a logistics document; those facts must remain logistics-owned.
 */
@Service
public class AdministrativeAssetCorrectionService {
  private static final Set<RentalItemStatus> CORRECTABLE_CABIN_STATUSES =
      Set.of(
          RentalItemStatus.SALE,
          RentalItemStatus.USED_SALE,
          RentalItemStatus.FREE,
          RentalItemStatus.WAREHOUSE,
          RentalItemStatus.OWN_NEEDS);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final WarehouseRegistryClient warehouses;
  private final RentalItemRepository rentalItems;
  private final AdministrativeAssetCorrectionRepository corrections;
  private final AssetEventStore events;

  public AdministrativeAssetCorrectionService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager,
      WarehouseRegistryClient warehouses,
      RentalItemRepository rentalItems,
      AdministrativeAssetCorrectionRepository corrections,
      AssetEventStore events) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.warehouses = warehouses;
    this.rentalItems = rentalItems;
    this.corrections = corrections;
    this.events = events;
  }

  /**
   * The permanent correction row is the replay source of truth, rather than
   * the expiring generic idempotency cache used by ordinary commands.
   */
  public CommandResult<AdministrativeAssetCorrectionResponse> create(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateAdministrativeAssetCorrectionRequest request) {
    requireCommandIdentity(actorSubjectId, idempotencyKey);
    NormalizedCorrection normalized = normalize(request);
    String fingerprint = fingerprint(normalized);

    CommandResult<AdministrativeAssetCorrectionResponse> replay =
        existingReplay(actorSubjectId, idempotencyKey, fingerprint);
    if (replay != null) {
      return replay;
    }

    // This synchronous registry check deliberately stays outside the local
    // asset transaction. A historical replay above remains available even
    // after a warehouse is later deactivated.
    warehouses.requireIncoming(normalized.targetWarehouseId());
    return required(
        transactions.execute(
            ignored -> createInTransaction(actorSubjectId, idempotencyKey, fingerprint, normalized)));
  }

  private CommandResult<AdministrativeAssetCorrectionResponse> createInTransaction(
      UUID actorSubjectId,
      UUID idempotencyKey,
      String fingerprint,
      NormalizedCorrection request) {
    advisoryLock(correctionLockKey(actorSubjectId, idempotencyKey));
    Optional<AdministrativeAssetCorrection> existing =
        corrections.findReplayForUpdate(actorSubjectId, idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), fingerprint);
    }
    return request instanceof NormalizedCabinCorrection cabin
        ? correctCabin(actorSubjectId, idempotencyKey, fingerprint, cabin)
        : correctEquipment(
            actorSubjectId,
            idempotencyKey,
            fingerprint,
            (NormalizedEquipmentCorrection) request);
  }

  private CommandResult<AdministrativeAssetCorrectionResponse> existingReplay(
      UUID actorSubjectId, UUID idempotencyKey, String fingerprint) {
    return transactions.execute(
        ignored -> {
          advisoryLock(correctionLockKey(actorSubjectId, idempotencyKey));
          Optional<AdministrativeAssetCorrection> existing =
              corrections.findReplayForUpdate(actorSubjectId, idempotencyKey);
          return existing.map(value -> replay(value, fingerprint)).orElse(null);
        });
  }

  private CommandResult<AdministrativeAssetCorrectionResponse> replay(
      AdministrativeAssetCorrection correction, String fingerprint) {
    if (!correction.getRequestSha256().equals(fingerprint)) {
      throw new AssetConflictException(
          "Administrative correction idempotency key is already bound to another request");
    }
    return new CommandResult<>(response(correction), true);
  }

  private CommandResult<AdministrativeAssetCorrectionResponse> correctCabin(
      UUID actorSubjectId,
      UUID idempotencyKey,
      String fingerprint,
      NormalizedCabinCorrection request) {
    advisoryLocks(
        List.of(
            rentalItemLockKey(request.assetId()),
            leaseLockKey(request.assetId())));
    RentalItem cabin =
        rentalItems
            .findByIdForUpdate(request.assetId())
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!request.sourceWarehouseId().equals(cabin.getWarehouseId())) {
      throw new AssetConflictException("Cabin does not belong to its recorded correction warehouse");
    }
    assertVersion(cabin.getVersion(), request.expectedVersion(), "Cabin changed concurrently");
    assertCabinCorrectable(cabin);
    assertNoActiveCabinWorkflow(cabin.getId());
    assertRentalNumberAvailable(cabin, request.targetWarehouseId());

    List<BalanceRow> contents = cabinBalancesForUpdate(cabin.getId(), request.sourceWarehouseId());
    advisoryLocks(
        contents.stream()
            .flatMap(
                balance ->
                    java.util.stream.Stream.of(
                        balanceLockKey(
                            balance.equipmentId(),
                            request.sourceWarehouseId(),
                            cabin.getId(),
                            balance.locationKind()),
                        balanceLockKey(
                            balance.equipmentId(),
                            request.targetWarehouseId(),
                            cabin.getId(),
                            balance.locationKind())))
            .toList());
    contents = cabinBalancesForUpdate(cabin.getId(), request.sourceWarehouseId());
    assertCanonicalCabinContents(cabin, request.sourceWarehouseId(), contents);
    assertNoTargetCabinBalances(cabin.getId(), request.targetWarehouseId(), contents);
    assertNoActiveBalanceHolds(contents.stream().map(BalanceRow::id).toList());
    assertNoActiveEquipmentReservations(
        contents.stream().map(BalanceRow::equipmentId).distinct().toList(),
        request.sourceWarehouseId());
    assertNoPreparedDispositionFence(
        AdministrativeCorrectionAssetKind.CABIN,
        cabin.getId(),
        request.sourceWarehouseId());

    List<AssetEventStore.StreamRef> streams = new ArrayList<>();
    AssetEventStore.StreamRef cabinStream =
        new AssetEventStore.StreamRef(AssetAggregateType.RENTAL_ITEM, cabin.getId());
    streams.add(cabinStream);
    contents.forEach(
        balance ->
            streams.add(
                new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, balance.id())));
    Map<AssetEventStore.StreamRef, Long> streamVersions = events.lockStreams(streams);
    assertStreamVersion(streamVersions, cabinStream, cabin.getVersion(), "Cabin event stream changed concurrently");
    for (BalanceRow balance : contents) {
      assertStreamVersion(
          streamVersions,
          new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, balance.id()),
          balance.version(),
          "Cabin content event stream changed concurrently");
    }

    if (!cabin.changeWarehouse(request.targetWarehouseId())) {
      throw new AssetConflictException("Administrative correction warehouse must differ");
    }
    RentalItem saved;
    try {
      saved = rentalItems.saveAndFlush(cabin);
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException(
          "Rental item number identity is already used in the destination warehouse");
    }

    for (BalanceRow balance : contents) {
      int updated =
          jdbc.update(
              """
              update equipment_balance
              set warehouse_id=?,version=version+1,updated_at=clock_timestamp()
              where id=? and version=? and warehouse_id=? and rental_item_id=? and location_kind=?
              """,
              request.targetWarehouseId(),
              balance.id(),
              balance.version(),
              request.sourceWarehouseId(),
              cabin.getId(),
              balance.locationKind().name());
      if (updated != 1) {
        throw new AssetConflictException("Cabin content changed concurrently during correction");
      }
    }

    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        request.expectedVersion(),
        AssetEventType.RENTAL_ITEM_WAREHOUSE_CHANGED,
        rentalFact(saved),
        rentalFact(saved));
    for (BalanceRow balance : contents) {
      BalanceRow after = balanceForUpdate(balance.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          after.id(),
          balance.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          balanceFact(after),
          balanceFact(after));
    }

    AdministrativeAssetCorrection correction =
        corrections.saveAndFlush(
            AdministrativeAssetCorrection.create(
                AdministrativeCorrectionAssetKind.CABIN,
                saved.getId(),
                request.sourceWarehouseId(),
                request.targetWarehouseId(),
                null,
                request.reason(),
                request.evidenceLink(),
                fingerprint,
                actorSubjectId,
                idempotencyKey,
                databaseNow()));
    writeAppliedEvent(correction);
    return new CommandResult<>(response(correction), false);
  }

  private CommandResult<AdministrativeAssetCorrectionResponse> correctEquipment(
      UUID actorSubjectId,
      UUID idempotencyKey,
      String fingerprint,
      NormalizedEquipmentCorrection request) {
    advisoryLocks(
        List.of(
            balanceLockKey(
                request.assetId(),
                request.sourceWarehouseId(),
                null,
                BalanceLocationKind.STOCK),
            balanceLockKey(
                request.assetId(),
                request.targetWarehouseId(),
                null,
                BalanceLocationKind.STOCK)));
    BalanceRow source =
        stockBalanceForUpdate(request.assetId(), request.sourceWarehouseId());
    BalanceRow target =
        findStockBalanceForUpdate(request.assetId(), request.targetWarehouseId())
            .orElseGet(
                () ->
                    createEmptyStockBalance(
                        request.assetId(), request.targetWarehouseId()));
    assertVersion(
        source.version(), request.sourceExpectedVersion(), "Source stock balance changed concurrently");
    assertVersion(
        target.version(), request.targetExpectedVersion(), "Target stock balance changed concurrently");
    if (source.quantity() < request.quantity()) {
      throw new AssetConflictException("Administrative correction exceeds source stock quantity");
    }
    assertNoActiveEquipmentHolds(source, target);
    assertNoActiveEquipmentReservations(
        List.of(request.assetId()), request.sourceWarehouseId(), request.targetWarehouseId());
    assertNoPreparedDispositionFence(
        AdministrativeCorrectionAssetKind.EQUIPMENT,
        request.assetId(),
        request.sourceWarehouseId(),
        request.targetWarehouseId());

    AssetEventStore.StreamRef sourceStream =
        new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, source.id());
    AssetEventStore.StreamRef targetStream =
        new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, target.id());
    Map<AssetEventStore.StreamRef, Long> streamVersions =
        events.lockStreams(List.of(sourceStream, targetStream));
    assertStreamVersion(
        streamVersions,
        sourceStream,
        source.version(),
        "Source stock event stream changed concurrently");
    assertStreamVersion(
        streamVersions,
        targetStream,
        target.version(),
        "Target stock event stream changed concurrently");

    int sourceUpdated =
        jdbc.update(
            """
            update equipment_balance
            set quantity=quantity-?,version=version+1,updated_at=clock_timestamp()
            where id=? and version=? and quantity>=?
            """,
            request.quantity(),
            source.id(),
            request.sourceExpectedVersion(),
            request.quantity());
    if (sourceUpdated != 1) {
      throw new AssetConflictException("Source stock balance changed concurrently or cannot become negative");
    }
    int targetUpdated =
        jdbc.update(
            """
            update equipment_balance
            set quantity=quantity+?,version=version+1,updated_at=clock_timestamp()
            where id=? and version=?
            """,
            request.quantity(),
            target.id(),
            request.targetExpectedVersion());
    if (targetUpdated != 1) {
      throw new AssetConflictException("Target stock balance changed concurrently");
    }
    BalanceRow sourceAfter = balanceForUpdate(source.id());
    BalanceRow targetAfter = balanceForUpdate(target.id());
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        source.id(),
        request.sourceExpectedVersion(),
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(sourceAfter),
        balanceFact(sourceAfter));
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        target.id(),
        request.targetExpectedVersion(),
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(targetAfter),
        balanceFact(targetAfter));

    AdministrativeAssetCorrection correction =
        corrections.saveAndFlush(
            AdministrativeAssetCorrection.create(
                AdministrativeCorrectionAssetKind.EQUIPMENT,
                request.assetId(),
                request.sourceWarehouseId(),
                request.targetWarehouseId(),
                request.quantity(),
                request.reason(),
                request.evidenceLink(),
                fingerprint,
                actorSubjectId,
                idempotencyKey,
                databaseNow()));
    writeAppliedEvent(correction);
    return new CommandResult<>(response(correction), false);
  }

  private void assertCabinCorrectable(RentalItem cabin) {
    if (!CORRECTABLE_CABIN_STATUSES.contains(cabin.getStatus())
        || cabin.getTransferOriginStatus() != null) {
      throw new AssetConflictException(
          "Only a manual-status cabin without an owning workflow may receive an administrative correction");
    }
  }

  private void assertNoActiveCabinWorkflow(UUID rentalItemId) {
    Boolean active =
        jdbc.queryForObject(
            """
            select exists(
              select 1
              from order_unit_reservation
              where rental_item_id=? and state='ACTIVE'
              union all
              select 1
              from presentation_unit_hold
              where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
              union all
              select 1
              from operation_lease
              where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
            )
            """,
            Boolean.class,
            rentalItemId,
            rentalItemId,
            rentalItemId);
    if (Boolean.TRUE.equals(active)) {
      throw new AssetConflictException(
          "Cabin has an active rental, reservation, hold, or operation lease");
    }
  }

  private void assertRentalNumberAvailable(RentalItem cabin, UUID targetWarehouseId) {
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKeyAndIdNot(
        targetWarehouseId, cabin.getIdentityMatchKey(), cabin.getId())) {
      throw new AssetConflictException(
          "Rental item number identity is already used in the destination warehouse");
    }
  }

  private void assertCanonicalCabinContents(
      RentalItem cabin, UUID sourceWarehouseId, List<BalanceRow> contents) {
    for (BalanceRow balance : contents) {
      boolean cabinLocation =
          balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED
              || balance.locationKind() == BalanceLocationKind.CABIN_RENTED;
      if (!cabinLocation
          || !cabin.getId().equals(balance.rentalItemId())
          || !sourceWarehouseId.equals(balance.warehouseId())) {
        throw new AssetConflictException("Cabin content balance is not canonical");
      }
      BalanceLocationKind expected =
          cabin.getStatus() == RentalItemStatus.RENTED
              ? BalanceLocationKind.CABIN_RENTED
              : BalanceLocationKind.CABIN_NON_RENTED;
      if (balance.locationKind() != expected) {
        throw new AssetConflictException("Cabin content location does not match cabin status");
      }
    }
  }

  private void assertNoTargetCabinBalances(
      UUID rentalItemId, UUID targetWarehouseId, List<BalanceRow> contents) {
    for (BalanceRow content : contents) {
      List<UUID> targetBalances =
          jdbc.query(
              """
              select id
              from equipment_balance
              where equipment_id=? and warehouse_id=? and rental_item_id=? and location_kind=?
              for update
              """,
              (result, row) -> result.getObject("id", UUID.class),
              content.equipmentId(),
              targetWarehouseId,
              rentalItemId,
              content.locationKind().name());
      if (!targetBalances.isEmpty()) {
        throw new AssetConflictException(
            "Destination cabin already has a canonical equipment balance");
      }
    }
  }

  private void assertNoActiveBalanceHolds(Collection<UUID> balanceIds) {
    if (balanceIds.isEmpty()) {
      return;
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(balanceIds.size(), "?"));
    List<UUID> active =
        jdbc.query(
            """
            select id
            from equipment_allocation_hold
            where source_balance_id in (%s)
              and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
            for update
            """.formatted(placeholders),
            (result, row) -> result.getObject("id", UUID.class),
            balanceIds.toArray());
    if (!active.isEmpty()) {
      throw new AssetConflictException("Cabin contents have an active equipment hold");
    }
  }

  private void assertNoActiveEquipmentHolds(BalanceRow source, BalanceRow target) {
    List<UUID> active =
        jdbc.query(
            """
            select id
            from equipment_allocation_hold
            where (
                source_balance_id in (?,?)
                or (
                  source_balance_id is null
                  and equipment_id=?
                  and warehouse_id in (?,?)
                )
              )
              and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
            for update
            """,
            (result, row) -> result.getObject("id", UUID.class),
            source.id(),
            target.id(),
            source.equipmentId(),
            source.warehouseId(),
            target.warehouseId());
    if (!active.isEmpty()) {
      throw new AssetConflictException("Equipment correction has an active equipment hold");
    }
  }

  private void assertNoActiveEquipmentReservations(
      Collection<UUID> equipmentIds, UUID... warehouseIds) {
    if (equipmentIds.isEmpty()) {
      return;
    }
    List<UUID> distinctEquipment = equipmentIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    List<UUID> distinctWarehouses =
        java.util.Arrays.stream(warehouseIds)
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    String equipmentPlaceholders =
        String.join(",", java.util.Collections.nCopies(distinctEquipment.size(), "?"));
    String warehousePlaceholders =
        String.join(",", java.util.Collections.nCopies(distinctWarehouses.size(), "?"));
    List<Object> parameters = new ArrayList<>();
    parameters.addAll(distinctEquipment);
    parameters.addAll(distinctWarehouses);
    List<UUID> active =
        jdbc.query(
            """
            select id
            from order_equipment_reservation
            where equipment_id in (%s)
              and warehouse_id in (%s)
              and state='ACTIVE'
            for update
            """.formatted(equipmentPlaceholders, warehousePlaceholders),
            (result, row) -> result.getObject("id", UUID.class),
            parameters.toArray());
    if (!active.isEmpty()) {
      throw new AssetConflictException("Equipment correction has an active order reservation");
    }
  }

  private void assertNoPreparedDispositionFence(
      AdministrativeCorrectionAssetKind assetKind, UUID assetId, UUID... warehouseIds) {
    List<UUID> distinctWarehouses =
        java.util.Arrays.stream(warehouseIds)
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    String placeholders =
        String.join(",", java.util.Collections.nCopies(distinctWarehouses.size(), "?"));
    List<Object> parameters = new ArrayList<>();
    parameters.add(assetKind.name());
    parameters.add(assetId);
    parameters.addAll(distinctWarehouses);
    List<UUID> active =
        jdbc.query(
            """
            select decision_id
            from property_disposition_fence
            where asset_kind=? and asset_id=? and warehouse_id in (%s) and state='PREPARED'
            for update
            """.formatted(placeholders),
            (result, row) -> result.getObject("decision_id", UUID.class),
            parameters.toArray());
    if (!active.isEmpty()) {
      throw new AssetConflictException("Asset has a prepared maintenance disposition");
    }
  }

  private List<BalanceRow> cabinBalancesForUpdate(UUID rentalItemId, UUID warehouseId) {
    return jdbc.query(
        """
        select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
        from equipment_balance
        where rental_item_id=? and warehouse_id=?
        order by equipment_id,location_kind,id
        for update
        """,
        (result, row) -> balanceRow(result),
        rentalItemId,
        warehouseId);
  }

  private BalanceRow stockBalanceForUpdate(UUID equipmentId, UUID warehouseId) {
    return findStockBalanceForUpdate(equipmentId, warehouseId)
        .orElseThrow(() -> new AssetNotFoundException("Equipment stock balance was not found"));
  }

  private Optional<BalanceRow> findStockBalanceForUpdate(UUID equipmentId, UUID warehouseId) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
            from equipment_balance
            where equipment_id=? and warehouse_id=? and rental_item_id is null and location_kind='STOCK'
            for update
            """,
            (result, row) -> balanceRow(result),
            equipmentId,
            warehouseId)
        .stream()
        .findFirst();
  }

  private BalanceRow createEmptyStockBalance(UUID equipmentId, UUID warehouseId) {
    UUID balanceId = UUID.randomUUID();
    int inserted =
        jdbc.update(
            """
            insert into equipment_balance(
              id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at)
            values (?,0,?,?,null,'STOCK',0,clock_timestamp(),clock_timestamp())
            on conflict (equipment_id,warehouse_id,location_kind) where rental_item_id is null do nothing
            """,
            balanceId,
            equipmentId,
            warehouseId);
    if (inserted == 1) {
      BalanceRow created = balanceForUpdate(balanceId);
      events.initialize(
          AssetAggregateType.EQUIPMENT_BALANCE,
          created.id(),
          created.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          balanceFact(created),
          balanceFact(created));
      return created;
    }
    return findStockBalanceForUpdate(equipmentId, warehouseId)
        .orElseThrow(
            () ->
                new AssetConflictException(
                    "Target stock balance changed concurrently during administrative correction"));
  }

  private BalanceRow balanceForUpdate(UUID balanceId) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
            from equipment_balance
            where id=?
            for update
            """,
            (result, row) -> balanceRow(result),
            balanceId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  private static BalanceRow balanceRow(java.sql.ResultSet result) throws java.sql.SQLException {
    return new BalanceRow(
        result.getObject("id", UUID.class),
        result.getLong("version"),
        result.getObject("equipment_id", UUID.class),
        result.getObject("warehouse_id", UUID.class),
        result.getObject("rental_item_id", UUID.class),
        BalanceLocationKind.valueOf(result.getString("location_kind")),
        result.getLong("quantity"));
  }

  private void assertStreamVersion(
      Map<AssetEventStore.StreamRef, Long> streamVersions,
      AssetEventStore.StreamRef stream,
      long expectedVersion,
      String message) {
    Long actual = streamVersions.get(stream);
    if (actual == null || actual != expectedVersion) {
      throw new AssetConflictException(message);
    }
  }

  private void writeAppliedEvent(AdministrativeAssetCorrection correction) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("correctionId", correction.getId().toString());
    body.put("assetKind", correction.getAssetKind().name());
    body.put("assetId", correction.getAssetId().toString());
    body.put("sourceWarehouseId", correction.getSourceWarehouseId().toString());
    body.put("targetWarehouseId", correction.getTargetWarehouseId().toString());
    body.put("quantity", correction.getQuantity());
    body.put("requestSha256", correction.getRequestSha256());
    String json = write(body);
    String canonical =
        jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
    if (canonical == null) {
      throw new IllegalStateException("PostgreSQL did not canonicalize administrative correction audit");
    }
    jdbc.update(
        """
        insert into administrative_asset_correction_event(
          event_id,correction_id,event_type,actor_subject_id,request_sha256,
          event_body,event_sha256,occurred_at)
        values (?,?,'APPLIED',?,?,?::jsonb,?,?)
        """,
        UUID.randomUUID(),
        correction.getId(),
        correction.getActorSubjectId(),
        correction.getRequestSha256(),
        canonical,
        AssetChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8)),
        correction.getAppliedAt());
  }

  private AdministrativeAssetCorrectionResponse response(
      AdministrativeAssetCorrection correction) {
    return new AdministrativeAssetCorrectionResponse(
        correction.getId(),
        correction.getAssetKind(),
        correction.getAssetId(),
        correction.getSourceWarehouseId(),
        correction.getTargetWarehouseId(),
        correction.getQuantity(),
        correction.getReason(),
        correction.getEvidenceLink(),
        correction.getRequestSha256(),
        correction.getActorSubjectId(),
        correction.getAppliedAt());
  }

  private NormalizedCorrection normalize(CreateAdministrativeAssetCorrectionRequest request) {
    if (request instanceof CreateCabinAdministrativeCorrectionRequest cabin) {
      UUID assetId = requiredUuid(cabin.assetId(), "assetId");
      UUID source = requiredUuid(cabin.recordedWarehouseId(), "recordedWarehouseId");
      UUID target = requiredUuid(cabin.correctedWarehouseId(), "correctedWarehouseId");
      if (cabin.assetKind() != AdministrativeCorrectionAssetKind.CABIN || source.equals(target)) {
        throw new IllegalArgumentException("Cabin administrative correction shape is invalid");
      }
      return new NormalizedCabinCorrection(
          assetId,
          requiredVersion(cabin.expectedVersion(), "expectedVersion"),
          source,
          target,
          requiredText(cabin.reason(), "reason"),
          requiredEvidenceLink(cabin.evidenceLink()));
    }
    if (request instanceof CreateEquipmentAdministrativeCorrectionRequest equipment) {
      UUID assetId = requiredUuid(equipment.assetId(), "assetId");
      UUID source = requiredUuid(equipment.sourceWarehouseId(), "sourceWarehouseId");
      UUID target = requiredUuid(equipment.targetWarehouseId(), "targetWarehouseId");
      if (equipment.assetKind() != AdministrativeCorrectionAssetKind.EQUIPMENT || source.equals(target)) {
        throw new IllegalArgumentException("Equipment administrative correction shape is invalid");
      }
      Long quantity = equipment.quantity();
      if (quantity == null || quantity < 1) {
        throw new IllegalArgumentException("quantity must be positive");
      }
      return new NormalizedEquipmentCorrection(
          assetId,
          source,
          requiredVersion(equipment.sourceExpectedVersion(), "sourceExpectedVersion"),
          target,
          requiredVersion(equipment.targetExpectedVersion(), "targetExpectedVersion"),
          quantity,
          requiredText(equipment.reason(), "reason"),
          requiredEvidenceLink(equipment.evidenceLink()));
    }
    throw new IllegalArgumentException("Administrative correction request is required");
  }

  private String fingerprint(NormalizedCorrection request) {
    try {
      return AssetChecksum.sha256(
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(request));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Administrative correction cannot be fingerprinted", exception);
    }
  }

  private static void requireCommandIdentity(UUID actorSubjectId, UUID idempotencyKey) {
    if (actorSubjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Administrative correction actor and idempotency key are required");
    }
  }

  private static UUID requiredUuid(UUID value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static long requiredVersion(Long value, String name) {
    if (value == null || value < 0) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static void assertVersion(long actual, long expected, String message) {
    if (actual != expected) {
      throw new AssetConflictException(message);
    }
  }

  private static String requiredText(String value, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 2000) {
      throw new IllegalArgumentException(name + " is required and must be at most 2000 characters");
    }
    return normalized;
  }

  private static String requiredEvidenceLink(String value) {
    String normalized = requiredText(value, "evidenceLink");
    URI uri;
    try {
      uri = URI.create(normalized);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "evidenceLink must be an absolute HTTPS URI with a host", exception);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("evidenceLink must be an absolute HTTPS URI with a host");
    }
    return normalized;
  }

  private void advisoryLocks(Collection<String> values) {
    values.stream().filter(Objects::nonNull).distinct().sorted().forEach(this::advisoryLock);
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", result -> {}, value);
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) {
      throw new IllegalStateException("PostgreSQL did not return the administrative correction timestamp");
    }
    return value;
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Administrative correction audit cannot be serialized", exception);
    }
  }

  private static <T> T required(T value) {
    if (value == null) {
      throw new IllegalStateException("Administrative correction transaction returned no result");
    }
    return value;
  }

  private static Map<String, ?> rentalFact(RentalItem item) {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("rentalItemId", item.getId().toString());
    fact.put("warehouseId", item.getWarehouseId().toString());
    fact.put("status", item.getStatus().name());
    fact.put(
        "numberSha256",
        AssetChecksum.sha256(item.getNumber().getBytes(StandardCharsets.UTF_8)));
    if (item.getTransferOriginStatus() != null) {
      fact.put("transferAssetStatus", item.getTransferOriginStatus().name());
    }
    return Map.copyOf(fact);
  }

  private static Map<String, ?> balanceFact(BalanceRow balance) {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("balanceId", balance.id().toString());
    fact.put("equipmentId", balance.equipmentId().toString());
    fact.put("warehouseId", balance.warehouseId().toString());
    fact.put(
        "rentalItemId",
        balance.rentalItemId() == null ? null : balance.rentalItemId().toString());
    fact.put("locationKind", balance.locationKind().name());
    fact.put("quantity", balance.quantity());
    return fact;
  }

  private static String correctionLockKey(UUID actorSubjectId, UUID idempotencyKey) {
    return "administrative-asset-correction:" + actorSubjectId + ':' + idempotencyKey;
  }

  private static String rentalItemLockKey(UUID rentalItemId) {
    return "asset-rental-item:" + rentalItemId;
  }

  private static String leaseLockKey(UUID rentalItemId) {
    return "lease:" + rentalItemId;
  }

  private static String balanceLockKey(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind) {
    return "asset-balance:"
        + equipmentId
        + ':'
        + warehouseId
        + ':'
        + (rentalItemId == null ? "-" : rentalItemId)
        + ':'
        + locationKind.name();
  }

  private sealed interface NormalizedCorrection
      permits NormalizedCabinCorrection, NormalizedEquipmentCorrection {
    UUID assetId();

    UUID sourceWarehouseId();

    UUID targetWarehouseId();

    String reason();

    String evidenceLink();
  }

  private record NormalizedCabinCorrection(
      UUID assetId,
      long expectedVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      String reason,
      String evidenceLink)
      implements NormalizedCorrection {}

  private record NormalizedEquipmentCorrection(
      UUID assetId,
      UUID sourceWarehouseId,
      long sourceExpectedVersion,
      UUID targetWarehouseId,
      long targetExpectedVersion,
      long quantity,
      String reason,
      String evidenceLink)
      implements NormalizedCorrection {}

  private record BalanceRow(
      UUID id,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {}
}
