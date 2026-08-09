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
        EquipmentCatalogItem.create(request.name(), request.category(), request.comment());
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
   * Binds a maintenance catalog node to a furniture item permanently; the binding is the source
   * of truth for late retries after generic idempotency retention expires.
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
      idempotency.store(
          subjectId, "maintenance.equipment-catalog.ensure", key, hash, 200, existing);
      return new AssetService.CreateResult<>(existing, true);
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
                  request.equipmentName(), EquipmentCategory.FURNITURE, null));
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
      return new AssetService.CreateResult<>(raced, true);
    }
    MaintenanceFurnitureEquipmentResponse response =
        new MaintenanceFurnitureEquipmentResponse(
            request.externalReferenceId(), catalogItem.getId(), catalogItem.getName());
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
    if (!item.change(request.name(), request.category(), request.active(), request.comment())) {
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
            select reference.external_reference_id,item.id,item.name,item.category
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
                  rs.getString("name"));
            },
            externalReferenceId)
        .stream()
        .findFirst();
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
    value.put("comment", item.getComment());
    return value;
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value);
  }
}
