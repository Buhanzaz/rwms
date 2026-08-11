package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.mapper.EquipmentCatalogItemMapper;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Owns equipment-catalog aggregates and the permanent maintenance external-reference binding.
 *
 * <p>Physical balances, movement and allocation decisions stay in their dedicated collaborators;
 * this service changes only catalog metadata and its catalog event stream.
 */
@Service
final class AssetEquipmentCatalogService {
  private final EquipmentCatalogItemRepository equipment;
  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final AssetIdempotencyStore idempotency;
  private final EquipmentCatalogItemMapper mapper;
  private final AssetJsonCodec json;

  AssetEquipmentCatalogService(
      EquipmentCatalogItemRepository equipment,
      JdbcTemplate jdbc,
      AssetEventStore events,
      AssetIdempotencyStore idempotency,
      EquipmentCatalogItemMapper mapper,
      AssetJsonCodec json) {
    this.equipment = equipment;
    this.jdbc = jdbc;
    this.events = events;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.json = json;
  }

  List<EquipmentResponse> list() {
    return equipment.findAllByOrderByNameAscIdAsc().stream().map(this::response).toList();
  }

  List<EquipmentCatalogItem> catalog() {
    return equipment.findAllByOrderByNameAscIdAsc();
  }

  EquipmentCatalogItem require(UUID id) {
    return equipment
        .findById(id)
        .orElseThrow(() -> new AssetNotFoundException("Equipment catalog item was not found"));
  }

  EquipmentResponse equipment(UUID id) {
    return response(require(id));
  }

  EquipmentResponse response(EquipmentCatalogItem item) {
    return mapper.toResponse(item);
  }

  AssetService.CreateResult<EquipmentResponse> create(
      UUID subjectId, UUID key, CreateEquipmentRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "equipment-catalog.create", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), EquipmentResponse.class), true);
    }
    EquipmentCatalogItem candidate =
        EquipmentCatalogItem.create(
            request.name(), request.category(), request.comment(), request.maximumPerCabin());
    if (equipment.existsByNormalizedName(candidate.getNormalizedName())) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    EquipmentCatalogItem saved;
    try {
      saved = equipment.saveAndFlush(candidate);
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    events.initialize(
        AssetAggregateType.EQUIPMENT_CATALOG,
        saved.getId(),
        saved.getVersion(),
        AssetEventType.EQUIPMENT_CATALOG_CREATED,
        fact(saved),
        snapshot(saved));
    EquipmentResponse response = response(saved);
    idempotency.store(subjectId, "equipment-catalog.create", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  /**
   * Binds a maintenance catalog node to a furniture item permanently. Versionless legacy ensures
   * preserve the current maximum; a version-fenced editor intent may change or clear it. The node
   * advisory lock and duplicate-reference retry apply the same CAS semantics after races and after
   * generic idempotency retention expires.
   */
  AssetService.CreateResult<MaintenanceFurnitureEquipmentResponse> ensureMaintenanceFurniture(
      UUID subjectId, UUID key, EnsureMaintenanceFurnitureEquipmentRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "maintenance.equipment-catalog.ensure", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), MaintenanceFurnitureEquipmentResponse.class), true);
    }
    advisoryLock("maintenance-equipment:" + request.externalReferenceId());
    MaintenanceFurnitureEquipmentResponse existing =
        maintenanceFurnitureReference(request.externalReferenceId()).orElse(null);
    if (existing != null) {
      EquipmentCatalogItem existingItem = require(existing.equipmentId());
      updateMaintenanceFurniture(existingItem, request);
      existing = maintenanceFurnitureResponse(request.externalReferenceId(), existingItem);
      idempotency.store(
          subjectId, "maintenance.equipment-catalog.ensure", key, hash, 200, existing);
      return new AssetService.CreateResult<>(existing, true);
    }
    if (request.expectedEquipmentVersion() != null) {
      throw new AssetConflictException(
          "Maintenance equipment reference does not exist at the expected version");
    }

    String normalizedName = EquipmentCatalogItem.normalizeName(request.equipmentName());
    EquipmentCatalogItem catalogItem =
        equipment.findAllByOrderByNameAscIdAsc().stream()
            .filter(item -> item.getNormalizedName().equals(normalizedName))
            .findFirst()
            .orElse(null);
    if (catalogItem == null) {
      catalogItem =
          equipment.saveAndFlush(
              EquipmentCatalogItem.create(
                  request.equipmentName(),
                  EquipmentCategory.FURNITURE,
                  null,
                  request.maximumPerCabin()));
      events.initialize(
          AssetAggregateType.EQUIPMENT_CATALOG,
          catalogItem.getId(),
          catalogItem.getVersion(),
          AssetEventType.EQUIPMENT_CATALOG_CREATED,
          fact(catalogItem),
          snapshot(catalogItem));
    } else if (catalogItem.getCategory() != EquipmentCategory.FURNITURE) {
      throw new AssetConflictException(
          "Maintenance furniture reference conflicts with a non-furniture catalog item");
    }
    catalogItem =
        equipment
            .findByIdForUpdate(catalogItem.getId())
            .orElseThrow(() -> new AssetNotFoundException("Equipment catalog item was not found"));
    applyNewReferenceMaximum(catalogItem, request.maximumPerCabin());
    updateMaintenanceFurniture(catalogItem, request);
    try {
      jdbc.update(
          """
          insert into equipment_external_reference(
            source_system,external_reference_id,equipment_id,created_at)
          values ('MAINTENANCE_CATALOG_NODE',?,?,clock_timestamp())
          """,
          request.externalReferenceId(),
          catalogItem.getId());
    } catch (DataIntegrityViolationException exception) {
      MaintenanceFurnitureEquipmentResponse raced =
          maintenanceFurnitureReference(request.externalReferenceId()).orElse(null);
      if (raced == null) {
        throw new AssetConflictException("Maintenance equipment reference changed concurrently");
      }
      EquipmentCatalogItem racedItem = require(raced.equipmentId());
      updateMaintenanceFurniture(racedItem, request);
      return new AssetService.CreateResult<>(
          maintenanceFurnitureResponse(request.externalReferenceId(), racedItem), true);
    }
    MaintenanceFurnitureEquipmentResponse response =
        maintenanceFurnitureResponse(request.externalReferenceId(), catalogItem);
    idempotency.store(
        subjectId, "maintenance.equipment-catalog.ensure", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  EquipmentResponse update(UUID id, UpdateEquipmentRequest request) {
    EquipmentCatalogItem item = require(id);
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedVersion());
    if (equipment.existsByNormalizedNameAndIdNot(EquipmentCatalogItem.normalizeName(request.name()), id)) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    if (item.getCategory() != request.category() && hasEquipmentUsage(id)) {
      throw new AssetConflictException(
          "Equipment category is immutable after the item has balances or workflow references");
    }
    if (item.isActive() && !request.active() && hasLiveEquipmentUsage(id)) {
      throw new AssetConflictException(
          "Equipment with non-terminal quantity, reservations or holds cannot be deactivated");
    }
    if (!item.change(
        request.name(),
        request.category(),
        request.active(),
        request.comment(),
        request.maximumPerCabin())) {
      return response(item);
    }
    EquipmentCatalogItem saved;
    try {
      saved = equipment.saveAndFlush(item);
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    events.append(
        AssetAggregateType.EQUIPMENT_CATALOG,
        id,
        request.expectedVersion(),
        AssetEventType.EQUIPMENT_CATALOG_CHANGED,
        fact(saved),
        snapshot(saved));
    return response(saved);
  }

  Optional<MaintenanceFurnitureEquipmentResponse> maintenanceFurnitureReference(
      UUID externalReferenceId) {
    return jdbc
        .query(
            """
            select reference.external_reference_id,item.id,item.name,item.category,
                   item.version,item.maximum_per_cabin
            from equipment_external_reference reference
            join equipment_catalog_item item on item.id=reference.equipment_id
            where reference.source_system='MAINTENANCE_CATALOG_NODE'
              and reference.external_reference_id=?
            """,
            (rs, row) -> {
              if (EquipmentCategory.valueOf(rs.getString("category")) != EquipmentCategory.FURNITURE) {
                throw new AssetConflictException(
                    "Stored maintenance equipment reference is not furniture");
              }
              return new MaintenanceFurnitureEquipmentResponse(
                  rs.getObject("external_reference_id", UUID.class),
                  rs.getObject("id", UUID.class),
                  rs.getString("name"),
                  rs.getLong("version"),
                  rs.getObject("maximum_per_cabin", Integer.class));
            },
            externalReferenceId)
        .stream()
        .findFirst();
  }

  /** Returns a bounded live read of asset-owned settings for durable maintenance node identities. */
  List<MaintenanceFurnitureEquipmentResponse> maintenanceFurnitureReferences(
      List<UUID> externalReferenceIds) {
    if (externalReferenceIds == null
        || externalReferenceIds.size() > 10000
        || externalReferenceIds.stream().anyMatch(java.util.Objects::isNull)
        || externalReferenceIds.stream().distinct().count() != externalReferenceIds.size()) {
      throw new IllegalArgumentException("Maintenance equipment references are invalid");
    }
    return externalReferenceIds.stream()
        .sorted()
        .map(this::maintenanceFurnitureReference)
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * Applies a maintenance editor mutation only with the exact asset version. A versionless ensure
   * deliberately preserves both the current name and maximum for legacy/retry callers.
   */
  private void updateMaintenanceFurniture(
      EquipmentCatalogItem item, EnsureMaintenanceFurnitureEquipmentRequest request) {
    if (request.expectedEquipmentVersion() == null) {
      return;
    }
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedEquipmentVersion());
    if (!item.change(
        request.equipmentName(),
        EquipmentCategory.FURNITURE,
        item.isActive(),
        item.getComment(),
        request.maximumPerCabin())) {
      return;
    }
    long previousVersion = item.getVersion();
    EquipmentCatalogItem saved = equipment.saveAndFlush(item);
    events.append(
        AssetAggregateType.EQUIPMENT_CATALOG,
        saved.getId(),
        previousVersion,
        AssetEventType.EQUIPMENT_CATALOG_CHANGED,
        fact(saved),
        snapshot(saved));
  }

  /**
   * Initializes a previously unlinked existing furniture item only when no configured maximum can
   * be overwritten; later changes must use the version-fenced editor path.
   */
  private void applyNewReferenceMaximum(
      EquipmentCatalogItem item, Integer requestedMaximumPerCabin) {
    if (requestedMaximumPerCabin == null
        || java.util.Objects.equals(item.getMaximumPerCabin(), requestedMaximumPerCabin)) {
      return;
    }
    if (item.getMaximumPerCabin() != null) {
      throw new AssetConflictException(
          "Existing furniture has another maximum per cabin; reload and edit it with its version");
    }
    long previousVersion = item.getVersion();
    item.change(
        item.getName(),
        item.getCategory(),
        item.isActive(),
        item.getComment(),
        requestedMaximumPerCabin);
    EquipmentCatalogItem saved = equipment.saveAndFlush(item);
    events.append(
        AssetAggregateType.EQUIPMENT_CATALOG,
        saved.getId(),
        previousVersion,
        AssetEventType.EQUIPMENT_CATALOG_CHANGED,
        fact(saved),
        snapshot(saved));
  }

  private static MaintenanceFurnitureEquipmentResponse maintenanceFurnitureResponse(
      UUID externalReferenceId, EquipmentCatalogItem item) {
    return new MaintenanceFurnitureEquipmentResponse(
        externalReferenceId,
        item.getId(),
        item.getName(),
        item.getVersion(),
        item.getMaximumPerCabin());
  }

  private boolean hasEquipmentUsage(UUID equipmentId) {
    Boolean result =
        jdbc.queryForObject(
            """
            select exists(
              select 1 from equipment_balance where equipment_id=?
              union all
              select 1 from equipment_movement where equipment_id=?
              union all
              select 1 from equipment_allocation_hold where equipment_id=?
              union all
              select 1 from order_equipment_reservation where equipment_id=?
              union all
              select 1 from equipment_external_reference where equipment_id=?
            )
            """,
            Boolean.class,
            equipmentId,
            equipmentId,
            equipmentId,
            equipmentId,
            equipmentId);
    return Boolean.TRUE.equals(result);
  }

  private boolean hasLiveEquipmentUsage(UUID equipmentId) {
    Boolean result =
        jdbc.queryForObject(
            """
            select exists(
              select 1
              from equipment_balance
              where equipment_id=?
                and quantity>0
                and location_kind not in ('WRITTEN_OFF','LOST')
              union all
              select 1
              from equipment_allocation_hold
              where equipment_id=?
                and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
              union all
              select 1
              from order_equipment_reservation
              where equipment_id=? and state='ACTIVE'
              union all
              select 1
              from equipment_external_reference
              where equipment_id=?
            )
            """,
            Boolean.class,
            equipmentId,
            equipmentId,
            equipmentId,
            equipmentId);
    return Boolean.TRUE.equals(result);
  }

  private static Map<String, ?> fact(EquipmentCatalogItem item) {
    return Map.of(
        "equipmentId", item.getId().toString(),
        "category", item.getCategory().name(),
        "active", item.isActive());
  }

  private static Map<String, ?> snapshot(EquipmentCatalogItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("equipmentId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("name", item.getName());
    value.put("category", item.getCategory().name());
    value.put("active", item.isActive());
    value.put("maximumPerCabin", item.getMaximumPerCabin());
    value.put("comment", item.getComment());
    return value;
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value);
  }
}
