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
import dev.buhanzaz.rwms.warehouse.repository.WarehouseSupportLinkRepository;
import java.math.BigDecimal;
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

/**
 * Transactional application boundary for warehouse identity, lifecycle and operational time.
 *
 * <p>This service owns metadata changes, one-way lifecycle transitions, effective-dated timezone
 * history, operation evidence and the corresponding transactional-outbox facts. HTTP controllers
 * authorize callers before reaching this class; it enforces the business and concurrency invariants
 * independently of the transport.
 */
@Service
public class WarehouseService {
  private static final Comparator<Warehouse> ORDER =
      Comparator.<Warehouse, Integer>comparing(
              Warehouse::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Warehouse::getName)
          .thenComparing(Warehouse::getId);
  private final WarehouseRepository warehouses;
  private final WarehouseSupportLinkRepository supportLinks;
  private final WarehouseResponseMapper responses;
  private final WarehouseOutboxWriter outbox;
  private final WarehouseIdempotencyStore idempotency;
  private final WarehouseTimeZoneHistoryService timeZones;
  private final WarehouseOperationMarker operationMarkers;
  private final WarehouseLifecycleReadinessStore lifecycleReadiness;
  private final WarehouseLifecycleAuditStore lifecycleAudit;
  private final ObjectMapper objectMapper;

  /**
   * Creates the transactional application boundary.
   *
   * @param warehouses repository for the warehouse aggregate
   * @param supportLinks repository used to protect representative-link ownership
   * @param responses mapper for transport projections
   * @param outbox transactional publisher for warehouse facts
   * @param idempotency caller-scoped create idempotency store
   * @param timeZones effective-dated timezone history boundary
   * @param operationMarkers durable operation-evidence boundary
   * @param lifecycleReadiness immutable lifecycle-readiness boundary
   * @param lifecycleAudit append-only lifecycle audit boundary
   * @param objectMapper serializer used to derive the create-command fingerprint
   */
  public WarehouseService(
      WarehouseRepository warehouses,
      WarehouseSupportLinkRepository supportLinks,
      WarehouseResponseMapper responses,
      WarehouseOutboxWriter outbox,
      WarehouseIdempotencyStore idempotency,
      WarehouseTimeZoneHistoryService timeZones,
      WarehouseOperationMarker operationMarkers,
      WarehouseLifecycleReadinessStore lifecycleReadiness,
      WarehouseLifecycleAuditStore lifecycleAudit,
      ObjectMapper objectMapper) {
    this.warehouses = warehouses;
    this.supportLinks = supportLinks;
    this.responses = responses;
    this.outbox = outbox;
    this.idempotency = idempotency;
    this.timeZones = timeZones;
    this.operationMarkers = operationMarkers;
    this.lifecycleReadiness = lifecycleReadiness;
    this.lifecycleAudit = lifecycleAudit;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns the canonical directory ordered by sort order, name and UUID.
   *
   * <p>Normal directory reads exclude terminal warehouses, while administrators may request the
   * historical inactive entries explicitly.
   *
   * @param includeInactive whether terminal historical warehouses should be included
   * @return canonical ordered warehouse directory
   */
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

  /**
   * Returns one warehouse, including a terminal historical warehouse when its UUID is known.
   *
   * @param id stable warehouse identity
   * @return canonical public warehouse projection
   */
  @Transactional(readOnly = true)
  public WarehouseResponse get(UUID id) {
    return response(require(id), timeZones.databaseNow());
  }

  /**
   * Creates a warehouse and records the successful response against a caller-scoped idempotency
   * key.
   *
   * <p>The same subject, key and semantic request replay the saved response. Reusing a key for a
   * different request fails before any second warehouse is written.
   *
   * @param subjectId authenticated user who owns the idempotency key
   * @param idempotencyKey caller-generated retry identity
   * @param request validated warehouse creation data
   * @return newly created or exact replay response
   */
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
   *
   * @param id stable warehouse identity
   * @param request version-fenced replacement data
   * @return current warehouse projection after the replacement or semantic no-op
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
    if (!request.representative() && supportLinks.existsByServedWarehouseId(id)) {
      throw new WarehouseConflictException(
          "Remove warehouse support links before clearing the representative characteristic");
    }

    Warehouse.Mutation mutation =
        warehouse.replace(
            request.name(),
            request.city(),
            request.address(),
            request.latitude(),
            request.longitude(),
            request.sortOrder(),
            request.representative());
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

  /**
   * Appends a future-effective timezone decision for a warehouse that has already operated.
   *
   * <p>Historical operation timestamps continue to resolve their former timezone. An unused
   * warehouse must use {@link #replace(UUID, ReplaceWarehouseRequest)} for an immediate correction.
   *
   * @param id stable warehouse identity
   * @param request version-fenced future timezone decision
   * @return appended immutable timezone decision
   */
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

  /**
   * Changes lifecycle from {@code ACTIVE} to {@code DRAINING} under an optimistic version fence.
   *
   * <p>The transition is one-way: it blocks incoming work but preserves outgoing admission until
   * the distributed readiness protocol finishes.
   *
   * @param id stable warehouse identity
   * @param request version-fenced lifecycle command
   * @return warehouse projection in {@code DRAINING}
   */
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

  /**
   * Completes the terminal {@code DRAINING -> INACTIVE} transition only after every owner is ready.
   *
   * <p>Readiness is immutable evidence from asset, inventory, logistics, maintenance and task-board
   * owners; it is not inferred from a timeout or an empty transient query.
   *
   * @param id stable warehouse identity
   * @param request version-fenced lifecycle command
   * @return terminal warehouse projection
   */
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

  /**
   * Records immutable lifecycle readiness for the authenticated owner.
   *
   * <p>The owner record is the effect identity. Thus, an exact retry after a lost response returns
   * the existing acknowledgement even if the aggregate version has subsequently advanced.
   *
   * @param id stable warehouse identity
   * @param owner lifecycle owner inferred from the credential
   * @param request version observed before the first confirmation attempt
   * @return immutable readiness confirmation
   */
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

  /**
   * Returns durable reconciliation work still owed by one lifecycle owner.
   *
   * <p>The keyset page supports recovery after missed events and is not a one-shot notification
   * stream.
   *
   * @param owner authenticated lifecycle owner whose work is requested
   * @param after optional UUID cursor from a previous page
   * @param limit maximum number of work items
   * @return durable owner-scoped work page
   */
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

  /**
   * Records durable, source-scoped evidence that a warehouse-bound operation occurred.
   *
   * <p>One operation ID can be replayed only with the same source and timestamp. The first mark
   * permanently switches timezone changes from direct correction to effective-dated scheduling.
   *
   * @param id stable warehouse identity
   * @param source operation owner inferred from the credential
   * @param request immutable operation evidence
   * @return true when a new mark was recorded; false for an exact idempotent replay
   */
  @Transactional
  public boolean markOperation(
      UUID id, WarehouseOperationSource source, WarehouseOperationMarkRequest request) {
    Warehouse warehouse = requireForUpdate(id);
    return operationMarkers.mark(
        warehouse, source, request.operationId(), request.occurredAt(), timeZones.databaseNow());
  }

  /**
   * Resolves the immutable timezone decision effective at a supplied operation timestamp.
   *
   * @param id stable warehouse identity
   * @param at operation timestamp to resolve
   * @return effective timezone decision
   */
  @Transactional(readOnly = true)
  public WarehouseTimeZoneAtResponse timeZoneAt(UUID id, OffsetDateTime at) {
    require(id);
    WarehouseTimeZoneHistory effective = timeZones.effectiveAt(id, at);
    return new WarehouseTimeZoneAtResponse(
        id, effective.getTimeZone(), effective.getEffectiveFrom());
  }

  /**
   * Returns the minimal existence projection used by narrow internal validation contracts.
   *
   * @param id stable warehouse identity
   * @return minimal existence projection
   */
  @Transactional(readOnly = true)
  public InternalWarehouseExistenceResponse existence(UUID id) {
    Warehouse warehouse = require(id);
    return new InternalWarehouseExistenceResponse(
        warehouse.getId(), warehouse.getVersion(), warehouse.isActive());
  }

  /**
   * Returns active-only metadata for inventory.
   *
   * <p>Hiding draining and inactive warehouses prevents the caller from creating new inventory
   * work after incoming admission has closed.
   *
   * @param id stable warehouse identity
   * @return active-only inventory metadata
   */
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

  /**
   * Returns a historical-compatible identity projection for one logistics lookup.
   *
   * @param id stable warehouse identity
   * @return least-privilege logistics identity
   */
  @Transactional(readOnly = true)
  public LogisticsWarehouseIdentityResponse logisticsIdentity(UUID id) {
    return logisticsResponse(require(id), timeZones.databaseNow());
  }

  /**
   * Returns only active identity projections for logistics availability selection.
   *
   * @return active logistics identities in canonical order
   */
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

  /**
   * Decides whether one operation direction is admitted by the current lifecycle state.
   *
   * <p>The result must be used instead of the legacy {@code active} projection when an owner needs
   * to distinguish outgoing draining work from new incoming work.
   *
   * @param id stable warehouse identity
   * @param direction proposed operation direction
   * @return exact directional admission decision
   */
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
        request.name(),
        request.city(),
        request.address(),
        request.latitude(),
        request.longitude(),
        zone(request.timeZone()),
        request.sortOrder(),
        request.representative());
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

  /**
   * Hashes every normalized create field so an idempotency key cannot replay across a change of
   * the representative characteristic.
   */
  private String fingerprint(Warehouse warehouse) {
    try {
      return WarehouseChecksum.sha256(
          objectMapper.writeValueAsBytes(
              new CreateFingerprint(
                  warehouse.getName(),
                  warehouse.getCity(),
                  warehouse.getAddress(),
                  warehouse.getLatitude(),
                  warehouse.getLongitude(),
                  warehouse.getTimeZone(),
                  warehouse.getSortOrder(),
                  warehouse.isRepresentative())));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Warehouse create command cannot be fingerprinted", exception);
    }
  }

  /**
   * Outcome of the warehouse-create idempotency boundary.
   *
   * @param response newly created or previously stored response
   * @param replayed whether the response came from an exact idempotent replay
   */
  public record CreateResult(WarehouseResponse response, boolean replayed) {}

  /** Normalized semantic create command used only as the caller-scoped idempotency fingerprint. */
  private record CreateFingerprint(
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String timeZone,
      Integer sortOrder,
      boolean representative) {}
}
