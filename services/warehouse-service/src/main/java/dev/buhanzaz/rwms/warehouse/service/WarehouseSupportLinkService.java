package dev.buhanzaz.rwms.warehouse.service;

import dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseIdentityResponse;
import dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseSupportLinkResponse;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseSupportLinksRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseSupportLinkInput;
import dev.buhanzaz.rwms.warehouse.api.WarehouseSupportLinksResponse;
import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseSupportLink;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseSupportLinkDefinition;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventType;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxWriter;
import dev.buhanzaz.rwms.warehouse.mapper.WarehouseResponseMapper;
import dev.buhanzaz.rwms.warehouse.mapper.WarehouseSupportLinkResponseMapper;
import dev.buhanzaz.rwms.warehouse.repository.WarehouseRepository;
import dev.buhanzaz.rwms.warehouse.repository.WarehouseSupportLinkRepository;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional owner of the directed support-link collection of a representative warehouse.
 *
 * <p>Candidate planning remains logistics-owned. This boundary owns only warehouse topology,
 * calendar policy, aggregate fencing and the exact owner-held coordinates exposed to logistics.
 */
@Service
public class WarehouseSupportLinkService {
  private final WarehouseRepository warehouses;
  private final WarehouseSupportLinkRepository links;
  private final WarehouseSupportLinkResponseMapper linkResponses;
  private final WarehouseResponseMapper warehouseResponses;
  private final WarehouseTimeZoneHistoryService timeZones;
  private final WarehouseOutboxWriter outbox;

  /**
   * Creates the support-link application boundary.
   *
   * @param warehouses owner repository for endpoint identities and lifecycle locks
   * @param links support-link repository
   * @param linkResponses support-link read mapper
   * @param warehouseResponses warehouse identity read mapper
   * @param timeZones effective timezone boundary used for local calendar evaluation
   * @param outbox transactional warehouse invalidation publisher
   */
  public WarehouseSupportLinkService(
      WarehouseRepository warehouses,
      WarehouseSupportLinkRepository links,
      WarehouseSupportLinkResponseMapper linkResponses,
      WarehouseResponseMapper warehouseResponses,
      WarehouseTimeZoneHistoryService timeZones,
      WarehouseOutboxWriter outbox) {
    this.warehouses = warehouses;
    this.links = links;
    this.linkResponses = linkResponses;
    this.warehouseResponses = warehouseResponses;
    this.timeZones = timeZones;
    this.outbox = outbox;
  }

  /**
   * Returns the complete configured collection, including inactive calendar edges.
   *
   * @param servedWarehouseId warehouse being served
   * @return current warehouse version and configured links
   */
  @Transactional(readOnly = true)
  public WarehouseSupportLinksResponse list(UUID servedWarehouseId) {
    Warehouse served = require(servedWarehouseId);
    return response(served, links.findAllByServedWarehouseId(servedWarehouseId));
  }

  /**
   * Atomically replaces one representative warehouse's support collection.
   *
   * <p>The served warehouse and every source are locked in UUID order. Thus lifecycle changes,
   * two competing replacements and duplicate source edges cannot pass validation concurrently.
   * A semantically identical PUT is an idempotent no-op and preserves every version.
   *
   * @param servedWarehouseId representative warehouse being served
   * @param request version-fenced full replacement
   * @return current collection after replacement
   */
  @Transactional
  public WarehouseSupportLinksResponse replace(
      UUID servedWarehouseId, ReplaceWarehouseSupportLinksRequest request) {
    if (request == null) throw new IllegalArgumentException("request is required");
    List<WarehouseSupportLinkInput> requested = request.links();
    assertUniqueSources(servedWarehouseId, requested);

    Set<UUID> endpointIds = new LinkedHashSet<>();
    endpointIds.add(servedWarehouseId);
    requested.stream().map(WarehouseSupportLinkInput::supportWarehouseId).forEach(endpointIds::add);
    Map<UUID, Warehouse> endpoints =
        warehouses.findAllByIdForUpdate(endpointIds).stream()
            .collect(Collectors.toMap(Warehouse::getId, Function.identity()));
    if (endpoints.size() != endpointIds.size()) throw new WarehouseNotFoundException();
    Warehouse served = endpoints.get(servedWarehouseId);
    assertExpectedVersion(served, request.expectedVersion());
    if (!served.isActive()) {
      throw new WarehouseConflictException("Only an active warehouse can receive support links");
    }
    if (!served.isRepresentative()) {
      throw new WarehouseConflictException(
          "Support links can be configured only for a representative warehouse");
    }
    for (WarehouseSupportLinkInput input : requested) {
      if (!endpoints.get(input.supportWarehouseId()).isActive()) {
        throw new WarehouseConflictException("A support warehouse must be active");
      }
    }

    List<WarehouseSupportLink> existing =
        links.findAllByServedWarehouseIdForUpdate(servedWarehouseId);
    Map<UUID, WarehouseSupportLink> bySource =
        existing.stream()
            .collect(Collectors.toMap(WarehouseSupportLink::getSupportWarehouseId, Function.identity()));
    Set<UUID> retained = new HashSet<>();
    List<WarehouseSupportLink> changed = new ArrayList<>();
    boolean mutated = false;
    for (WarehouseSupportLinkInput input : requested) {
      retained.add(input.supportWarehouseId());
      WarehouseSupportLink current = bySource.get(input.supportWarehouseId());
      if (current == null) {
        changed.add(
            WarehouseSupportLink.create(
                input.supportWarehouseId(), servedWarehouseId, definition(input)));
        mutated = true;
      } else if (current.replace(definition(input))) {
        changed.add(current);
        mutated = true;
      }
    }
    List<WarehouseSupportLink> removed =
        existing.stream()
            .filter(link -> !retained.contains(link.getSupportWarehouseId()))
            .toList();
    mutated |= !removed.isEmpty();
    if (!mutated) return response(served, existing);

    try {
      if (!removed.isEmpty()) links.deleteAll(removed);
      if (!changed.isEmpty()) links.saveAll(changed);
      links.flush();
      served.recordSupportLinkDecision();
      Warehouse persisted = warehouses.saveAndFlush(served);
      OffsetDateTime now = timeZones.databaseNow();
      outbox.append(
          persisted,
          WarehouseEventType.CHANGED,
          timeZones.currentTimeZone(persisted.getId(), now));
      return response(persisted, links.findAllByServedWarehouseId(servedWarehouseId));
    } catch (DataIntegrityViolationException exception) {
      throw new WarehouseConflictException("Warehouse support links conflict with current data");
    }
  }

  /**
   * Returns only links that are active and calendar-eligible at the served warehouse's local time.
   *
   * <p>Inactive endpoints are excluded even if a formerly valid link row remains for audit and
   * future administrative correction.
   *
   * @param servedWarehouseId warehouse being served
   * @param at planner instant
   * @return eligible directed links with source and target coordinates
   */
  @Transactional(readOnly = true)
  public List<LogisticsWarehouseSupportLinkResponse> logisticsLinks(
      UUID servedWarehouseId, OffsetDateTime at) {
    if (at == null) throw new IllegalArgumentException("at is required");
    Warehouse served = require(servedWarehouseId);
    if (!served.isActive() || !served.isRepresentative()) return List.of();
    ZoneId servedZone =
        ZoneId.of(timeZones.currentTimeZone(servedWarehouseId, timeZones.databaseNow()));
    var local = at.atZoneSameInstant(servedZone);
    List<WarehouseSupportLink> eligible =
        links.findAllByServedWarehouseId(servedWarehouseId).stream()
            .filter(link -> link.allows(local.toLocalDate(), local.toLocalTime()))
            .toList();
    if (eligible.isEmpty()) return List.of();

    Set<UUID> sourceIds =
        eligible.stream().map(WarehouseSupportLink::getSupportWarehouseId).collect(Collectors.toSet());
    Map<UUID, Warehouse> sources =
        warehouses.findAllById(sourceIds).stream()
            .filter(Warehouse::isActive)
            .collect(Collectors.toMap(Warehouse::getId, Function.identity()));
    if (sources.isEmpty()) return List.of();

    OffsetDateTime now = timeZones.databaseNow();
    List<Warehouse> identities = new ArrayList<>(sources.values());
    identities.add(served);
    Map<UUID, String> effectiveZones =
        timeZones.effectiveTimeZonesAt(identities.stream().map(Warehouse::getId).toList(), now);
    LogisticsWarehouseIdentityResponse servedResponse =
        warehouseResponses.toLogisticsIdentity(served, effectiveZones.get(served.getId()));
    Map<UUID, LogisticsWarehouseIdentityResponse> sourceResponses = new HashMap<>();
    sources.forEach(
        (id, source) ->
            sourceResponses.put(
                id, warehouseResponses.toLogisticsIdentity(source, effectiveZones.get(id))));
    return eligible.stream()
        .filter(link -> sourceResponses.containsKey(link.getSupportWarehouseId()))
        .map(
            link ->
                linkResponses.toLogisticsResponse(
                    link, sourceResponses.get(link.getSupportWarehouseId()), servedResponse))
        .toList();
  }

  private WarehouseSupportLinksResponse response(
      Warehouse served, List<WarehouseSupportLink> configured) {
    return new WarehouseSupportLinksResponse(
        served.getId(),
        served.getVersion(),
        configured.stream().map(linkResponses::toResponse).toList());
  }

  private static WarehouseSupportLinkDefinition definition(WarehouseSupportLinkInput input) {
    return new WarehouseSupportLinkDefinition(
        input.active(),
        input.priority(),
        input.allowDrivers(),
        input.allowVehicles(),
        input.allowInventory(),
        input.allowDirectFulfillment(),
        input.allowInterwarehouseTransfer(),
        input.allowContractorFallback(),
        input.allowedWeekdays(),
        input.allowedDates(),
        input.excludedDates(),
        input.serviceStart(),
        input.serviceEnd());
  }

  private static void assertUniqueSources(
      UUID servedWarehouseId, List<WarehouseSupportLinkInput> requested) {
    Set<UUID> seen = new HashSet<>();
    for (WarehouseSupportLinkInput input : requested) {
      if (servedWarehouseId.equals(input.supportWarehouseId())) {
        throw new IllegalArgumentException("A warehouse cannot support itself");
      }
      if (!seen.add(input.supportWarehouseId())) {
        throw new WarehouseConflictException("A support warehouse can appear only once");
      }
    }
  }

  private Warehouse require(UUID id) {
    return warehouses.findById(id).orElseThrow(WarehouseNotFoundException::new);
  }

  private static void assertExpectedVersion(Warehouse warehouse, long expectedVersion) {
    if (warehouse.getVersion() != expectedVersion) {
      throw new WarehouseConflictException("Warehouse has been changed by another request");
    }
  }
}
