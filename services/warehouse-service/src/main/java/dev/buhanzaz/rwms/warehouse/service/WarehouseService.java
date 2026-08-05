package dev.buhanzaz.rwms.warehouse.service;

import dev.buhanzaz.rwms.warehouse.api.CreateWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.InternalWarehouseExistenceResponse;
import dev.buhanzaz.rwms.warehouse.api.InventoryWarehouseMetadataResponse;
import dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseIdentityResponse;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.ScheduleWarehouseTimeZoneRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseLifecycleReadinessConfirmationResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseLifecycleReadinessRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseLifecycleTransitionRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseLifecycleReadinessWorkPageResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseLifecycleReadinessWorkResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseOperationAdmissionResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseOperationMarkRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseTimeZoneAtResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseTimeZoneChangeResponse;
import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseLifecycleState;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseTimeZoneHistory;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventType;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventPayload.TimeZoneDecision;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxWriter;
import dev.buhanzaz.rwms.warehouse.mapper.WarehouseResponseMapper;
import dev.buhanzaz.rwms.warehouse.repository.WarehouseRepository;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WarehouseService {
  private static final Comparator<Warehouse> ORDER =
      Comparator.<Warehouse, Integer>comparing(
              Warehouse::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Warehouse::getName)
          .thenComparing(Warehouse::getId);
  private final WarehouseRepository warehouses;
  private final WarehouseResponseMapper responses;
  private final WarehouseOutboxWriter outbox;
  private final WarehouseIdempotencyStore idempotency;
  private final WarehouseTimeZoneHistoryService timeZones;
  private final WarehouseOperationMarker operationMarkers;
  private final WarehouseLifecycleReadinessStore lifecycleReadiness;
  private final WarehouseLifecycleAuditStore lifecycleAudit;
  private final ObjectMapper objectMapper;

  public WarehouseService(
      WarehouseRepository warehouses,
      WarehouseResponseMapper responses,
      WarehouseOutboxWriter outbox,
      WarehouseIdempotencyStore idempotency,
      WarehouseTimeZoneHistoryService timeZones,
      WarehouseOperationMarker operationMarkers,
      WarehouseLifecycleReadinessStore lifecycleReadiness,
      WarehouseLifecycleAuditStore lifecycleAudit,
      ObjectMapper objectMapper) {
    this.warehouses = warehouses;
    this.responses = responses;
    this.outbox = outbox;
    this.idempotency = idempotency;
    this.timeZones = timeZones;
    this.operationMarkers = operationMarkers;
    this.lifecycleReadiness = lifecycleReadiness;
    this.lifecycleAudit = lifecycleAudit;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<WarehouseResponse> list(boolean includeInactive) {
    OffsetDateTime now = timeZones.databaseNow();
    List<Warehouse> source =
        (includeInactive
                ? warehouses.findAll()
                : warehouses.findAllByLifecycleStateNot(WarehouseLifecycleState.INACTIVE))
            .stream()
            .sorted(ORDER)
            .toList();
    Map<UUID, String> effectiveTimeZones =
        timeZones.effectiveTimeZonesAt(source.stream().map(Warehouse::getId).toList(), now);
    return source.stream()
        .map(warehouse -> responses.toResponse(warehouse, effectiveTimeZones.get(warehouse.getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public WarehouseResponse get(UUID id) {
    return response(require(id), timeZones.databaseNow());
  }

  @Transactional
  public CreateResult create(UUID subjectId, UUID idempotencyKey, CreateWarehouseRequest request) {
    Warehouse candidate = newWarehouse(request);
    String fingerprint = fingerprint(candidate);
    Optional<WarehouseResponse> replayed =
        idempotency.replay(subjectId, idempotencyKey, fingerprint);
    if (replayed.isPresent()) return new CreateResult(replayed.get(), true);
    if (warehouses.existsByNormalizedName(candidate.getNormalizedName())) {
      throw duplicateNameConflict();
    }
    Warehouse persisted;
    try {
      persisted = warehouses.saveAndFlush(candidate);
    } catch (DataIntegrityViolationException exception) {
      throw duplicateNameConflict();
    }
    OffsetDateTime now = timeZones.databaseNow();
    timeZones.initialize(persisted, now);
    outbox.append(persisted, WarehouseEventType.CREATED, persisted.getTimeZone());
    WarehouseResponse response = response(persisted, now);
    idempotency.storeSuccess(subjectId, idempotencyKey, fingerprint, response);
    return new CreateResult(response, false);
  }

  /**
   * A timezone in PUT is an immediate correction only. Once the durable operation marker exists,
   * callers must use the effective-dated scheduling command instead of rewriting history.
   */
  @Transactional
  public WarehouseResponse replace(UUID id, ReplaceWarehouseRequest request) {
    Warehouse warehouse = requireForUpdate(id);
    assertExpectedVersion(warehouse, request.expectedVersion());
    if (warehouses.existsByNormalizedNameAndIdNot(Warehouse.normalizeName(request.name()), id)) {
      throw duplicateNameConflict();
    }
    OffsetDateTime now = timeZones.databaseNow();
    String currentTimeZone = timeZones.currentTimeZone(id, now);
    ZoneId requestedTimeZone = zone(request.timeZone());
    boolean timeZoneChanged = !currentTimeZone.equals(requestedTimeZone.getId());
    if (timeZoneChanged && operationMarkers.hasRecordedOperation(id)) {
      throw new WarehouseConflictException(
          "An operated warehouse timezone must be scheduled with an effective timestamp");
    }

    Warehouse.Mutation mutation =
        warehouse.replace(request.name(), request.city(), request.address(), request.sortOrder());
    if (timeZoneChanged) warehouse.correctTimeZone(requestedTimeZone);
    if (mutation == Warehouse.Mutation.NONE && !timeZoneChanged) return response(warehouse, now);

    Warehouse persisted;
    try {
      persisted = warehouses.saveAndFlush(warehouse);
    } catch (DataIntegrityViolationException exception) {
      throw duplicateNameConflict();
    }
    if (timeZoneChanged) timeZones.append(id, now, requestedTimeZone, now);
    outbox.append(persisted, WarehouseEventType.CHANGED, requestedTimeZone.getId());
    return response(persisted, now);
  }

  @Transactional
  public WarehouseTimeZoneChangeResponse scheduleTimeZone(
      UUID id, ScheduleWarehouseTimeZoneRequest request) {
    Warehouse warehouse = requireForUpdate(id);
    assertExpectedVersion(warehouse, request.expectedVersion());
    OffsetDateTime now = timeZones.databaseNow();
    if (!operationMarkers.hasRecordedOperation(id)) {
      throw new WarehouseConflictException(
          "A warehouse without operations must correct timezone immediately instead of scheduling it");
    }
    OffsetDateTime effectiveFrom =
        WarehouseTimeZoneHistoryService.canonicalTimestamp(request.effectiveFrom());
    if (!effectiveFrom.isAfter(now)) {
      throw new IllegalArgumentException("effectiveFrom must be in the future");
    }
    ZoneId requestedTimeZone = zone(request.timeZone());
    if (requestedTimeZone.getId().equals(timeZones.effectiveAt(id, effectiveFrom).getTimeZone())) {
      throw new IllegalArgumentException("Requested timezone is already effective at that timestamp");
    }

    warehouse.recordTimeZoneDecision();
    Warehouse persisted = warehouses.saveAndFlush(warehouse);
    WarehouseTimeZoneHistory scheduled =
        timeZones.append(id, effectiveFrom, requestedTimeZone, now);
    outbox.append(
        persisted,
        WarehouseEventType.CHANGED,
        timeZones.currentTimeZone(persisted.getId(), now),
        new TimeZoneDecision(scheduled.getTimeZone(), scheduled.getEffectiveFrom()));
    return new WarehouseTimeZoneChangeResponse(
        persisted.getId(), persisted.getVersion(), scheduled.getTimeZone(), scheduled.getEffectiveFrom());
  }

  @Transactional
  public WarehouseResponse startDraining(UUID id, WarehouseLifecycleTransitionRequest request) {
    Warehouse warehouse = requireForUpdate(id);
    assertExpectedVersion(warehouse, request.expectedVersion());
    if (!warehouse.startDraining()) {
      throw new WarehouseConflictException("Warehouse can enter DRAINING only from ACTIVE");
    }
    Warehouse persisted = warehouses.saveAndFlush(warehouse);
    OffsetDateTime now = timeZones.databaseNow();
    lifecycleAudit.record(persisted, WarehouseLifecycleTransition.DRAINING_STARTED, now);
    outbox.append(
        persisted,
        WarehouseEventType.CHANGED,
        timeZones.currentTimeZone(persisted.getId(), now));
    return response(persisted, now);
  }

  @Transactional
  public WarehouseResponse completeInactivation(UUID id, WarehouseLifecycleTransitionRequest request) {
    Warehouse warehouse = requireForUpdate(id);
    assertExpectedVersion(warehouse, request.expectedVersion());
    if (warehouse.getLifecycleState() != WarehouseLifecycleState.DRAINING) {
      throw new WarehouseConflictException("Warehouse can become INACTIVE only from DRAINING");
    }
    Set<WarehouseLifecycleReadinessOwner> missing = lifecycleReadiness.missing(id);
    if (!missing.isEmpty()) {
      throw new WarehouseConflictException(
          "Warehouse cannot become INACTIVE until readiness is confirmed by "
              + missing.stream().map(owner -> owner.name()).sorted().toList());
    }
    if (!warehouse.completeInactivation()) {
      throw new WarehouseConflictException("Warehouse lifecycle transition was not accepted");
    }
    Warehouse persisted = warehouses.saveAndFlush(warehouse);
    OffsetDateTime now = timeZones.databaseNow();
    lifecycleAudit.record(persisted, WarehouseLifecycleTransition.INACTIVATED, now);
    outbox.append(
        persisted,
        WarehouseEventType.DEACTIVATED,
        timeZones.currentTimeZone(persisted.getId(), now));
    return response(persisted, now);
  }

  @Transactional
  public WarehouseLifecycleReadinessConfirmationResponse confirmLifecycleReadiness(
      UUID id,
      WarehouseLifecycleReadinessOwner owner,
      WarehouseLifecycleReadinessRequest request) {
    Warehouse warehouse = requireForUpdate(id);
    Optional<WarehouseLifecycleReadinessStore.Confirmation> existing =
        lifecycleReadiness.find(id, owner);
    if (existing.isPresent()) {
      // The immutable owner record is the stable effect identity. A lost response may be retried
      // with its original version even after this confirmation advanced the aggregate version.
      return readinessResponse(warehouse, existing.get());
    }
    assertExpectedVersion(warehouse, request.expectedVersion());
    if (warehouse.getLifecycleState() != WarehouseLifecycleState.DRAINING) {
      throw new WarehouseConflictException(
          "Warehouse lifecycle readiness can be confirmed only while DRAINING");
    }
    warehouse.recordLifecycleReadiness();
    Warehouse persisted = warehouses.saveAndFlush(warehouse);
    OffsetDateTime now = timeZones.databaseNow();
    WarehouseLifecycleReadinessStore.Confirmation confirmation =
        lifecycleReadiness.record(id, owner, persisted.getVersion(), now);
    outbox.append(
        persisted,
        WarehouseEventType.CHANGED,
        timeZones.currentTimeZone(persisted.getId(), now));
    return readinessResponse(persisted, confirmation);
  }

  @Transactional(readOnly = true)
  public WarehouseLifecycleReadinessWorkPageResponse lifecycleReadinessWork(
      WarehouseLifecycleReadinessOwner owner, UUID after, int limit) {
    WarehouseLifecycleReadinessStore.WorkPage work = lifecycleReadiness.pendingWork(owner, after, limit);
    return new WarehouseLifecycleReadinessWorkPageResponse(
        work.items().stream()
            .map(
                item ->
                    new WarehouseLifecycleReadinessWorkResponse(
                        item.warehouseId(),
                        item.warehouseVersion(),
                        WarehouseLifecycleState.DRAINING.name()))
            .toList(),
        work.nextAfter());
  }

  @Transactional
  public boolean markOperation(
      UUID id, WarehouseOperationSource source, WarehouseOperationMarkRequest request) {
    Warehouse warehouse = requireForUpdate(id);
    return operationMarkers.mark(
        warehouse, source, request.operationId(), request.occurredAt(), timeZones.databaseNow());
  }

  @Transactional(readOnly = true)
  public WarehouseTimeZoneAtResponse timeZoneAt(UUID id, OffsetDateTime at) {
    require(id);
    WarehouseTimeZoneHistory effective = timeZones.effectiveAt(id, at);
    return new WarehouseTimeZoneAtResponse(
        id, effective.getTimeZone(), effective.getEffectiveFrom());
  }

  @Transactional(readOnly = true)
  public InternalWarehouseExistenceResponse existence(UUID id) {
    Warehouse warehouse = require(id);
    return new InternalWarehouseExistenceResponse(
        warehouse.getId(), warehouse.getVersion(), warehouse.isActive());
  }

  @Transactional(readOnly = true)
  public InventoryWarehouseMetadataResponse inventoryMetadata(UUID id) {
    Warehouse warehouse = require(id);
    if (!warehouse.isActive()) throw new WarehouseNotFoundException();
    return new InventoryWarehouseMetadataResponse(
        warehouse.getId(),
        warehouse.getVersion(),
        warehouse.isActive(),
        timeZones.currentTimeZone(warehouse.getId(), timeZones.databaseNow()));
  }

  @Transactional(readOnly = true)
  public LogisticsWarehouseIdentityResponse logisticsIdentity(UUID id) {
    return logisticsResponse(require(id), timeZones.databaseNow());
  }

  @Transactional(readOnly = true)
  public List<LogisticsWarehouseIdentityResponse> logisticsIdentities() {
    OffsetDateTime now = timeZones.databaseNow();
    List<Warehouse> warehouses =
        this.warehouses.findAllByLifecycleState(WarehouseLifecycleState.ACTIVE).stream()
            .sorted(ORDER)
            .toList();
    Map<UUID, String> effectiveTimeZones =
        timeZones.effectiveTimeZonesAt(warehouses.stream().map(Warehouse::getId).toList(), now);
    return warehouses.stream()
        .map(
            warehouse ->
                responses.toLogisticsIdentity(
                    warehouse, effectiveTimeZones.get(warehouse.getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public WarehouseOperationAdmissionResponse admission(UUID id, WarehouseOperationDirection direction) {
    if (direction == null) throw new IllegalArgumentException("operation direction is required");
    Warehouse warehouse = require(id);
    boolean admitted =
        switch (direction) {
          case INCOMING -> warehouse.allowsIncomingOperations();
          case OUTGOING -> warehouse.allowsOutgoingOperations();
        };
    return new WarehouseOperationAdmissionResponse(
        warehouse.getId(),
        warehouse.getVersion(),
        warehouse.getLifecycleState().name(),
        direction.name(),
        admitted);
  }

  private Warehouse newWarehouse(CreateWarehouseRequest request) {
    return Warehouse.create(
        request.name(), request.city(), request.address(), zone(request.timeZone()), request.sortOrder());
  }

  private WarehouseResponse response(Warehouse warehouse, OffsetDateTime now) {
    return responses.toResponse(warehouse, timeZones.currentTimeZone(warehouse.getId(), now));
  }

  private LogisticsWarehouseIdentityResponse logisticsResponse(
      Warehouse warehouse, OffsetDateTime now) {
    return responses.toLogisticsIdentity(
        warehouse, timeZones.currentTimeZone(warehouse.getId(), now));
  }

  private WarehouseLifecycleReadinessConfirmationResponse readinessResponse(
      Warehouse warehouse, WarehouseLifecycleReadinessStore.Confirmation confirmation) {
    return new WarehouseLifecycleReadinessConfirmationResponse(
        warehouse.getId(),
        warehouse.getVersion(),
        warehouse.getLifecycleState().name(),
        confirmation.owner().name(),
        confirmation.confirmedAt());
  }

  private Warehouse require(UUID id) {
    return warehouses.findById(id).orElseThrow(WarehouseNotFoundException::new);
  }

  private Warehouse requireForUpdate(UUID id) {
    return warehouses.findByIdForUpdate(id).orElseThrow(WarehouseNotFoundException::new);
  }

  private static void assertExpectedVersion(Warehouse warehouse, long expectedVersion) {
    if (warehouse.getVersion() != expectedVersion) {
      throw new WarehouseConflictException("Warehouse has been changed by another request");
    }
  }

  private static WarehouseConflictException duplicateNameConflict() {
    return new WarehouseConflictException("A warehouse with this name already exists");
  }

  private static ZoneId zone(String value) {
    return Warehouse.requireCanonicalTimeZone(value);
  }

  private String fingerprint(Warehouse warehouse) {
    try {
      return WarehouseChecksum.sha256(
          objectMapper.writeValueAsBytes(
              new CreateFingerprint(
                  warehouse.getName(),
                  warehouse.getCity(),
                  warehouse.getAddress(),
                  warehouse.getTimeZone(),
                  warehouse.getSortOrder())));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Warehouse create command cannot be fingerprinted", exception);
    }
  }

  public record CreateResult(WarehouseResponse response, boolean replayed) {}

  private record CreateFingerprint(
      String name, String city, String address, String timeZone, Integer sortOrder) {}
}
