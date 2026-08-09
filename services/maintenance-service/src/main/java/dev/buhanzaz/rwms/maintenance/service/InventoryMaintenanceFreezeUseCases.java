package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperation;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperationId;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceOperationRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Freezes immutable inventory maintenance plan evidence and coordinates its routing preflight
 * retry without holding maintenance rows across the remote task-board call.
 */
@Component
final class InventoryMaintenanceFreezeUseCases {
  private final CatalogVersionRepository catalogs;
  private final CatalogNodeRepository catalogNodes;
  private final CatalogLinkRepository catalogLinks;
  private final InventoryRepairSourceOperationRepository sourceOperations;
  private final InventoryRepairSourceRepository sources;
  private final InventoryRepairSourceOperationRegistrar sourceRegistrar;
  private final MaintenanceJsonbCanonicalizer canonicalizer;
  private final ObjectMapper mapper;
  private final InventoryMaintenancePlanValidation planValidation;
  private final InventoryMaintenanceTransactionBoundary transactions;

  InventoryMaintenanceFreezeUseCases(
      CatalogVersionRepository catalogs,
      CatalogNodeRepository catalogNodes,
      CatalogLinkRepository catalogLinks,
      InventoryRepairSourceOperationRepository sourceOperations,
      InventoryRepairSourceRepository sources,
      InventoryRepairSourceOperationRegistrar sourceRegistrar,
      MaintenanceJsonbCanonicalizer canonicalizer,
      ObjectMapper mapper,
      InventoryMaintenancePlanValidation planValidation,
      InventoryMaintenanceTransactionBoundary transactions) {
    this.catalogs = catalogs;
    this.catalogNodes = catalogNodes;
    this.catalogLinks = catalogLinks;
    this.sourceOperations = sourceOperations;
    this.sources = sources;
    this.sourceRegistrar = sourceRegistrar;
    this.canonicalizer = canonicalizer;
    this.mapper = mapper;
    this.planValidation = planValidation;
    this.transactions = transactions;
  }

  InventoryMaintenanceFreezeResult freeze(FreezeInventoryPlanRequest request) {
    transactions.requireNoCallerTransaction("freeze an inventory plan");
    List<InventoryPlanStageSnapshot> routingPreflightStages = null;
    while (true) {
      List<InventoryPlanStageSnapshot> preflightedStages = routingPreflightStages;
      try {
        return transactions.inNewTransaction(() -> freezeLocally(request, preflightedStages));
      } catch (InventoryMaintenancePlanValidation.RemotePreflightRequired requirement) {
        if (requirement.kind()
                != InventoryMaintenancePlanValidation.RemotePreflightRequired.Kind.ROUTING
            || requirement.stages().equals(routingPreflightStages)) {
          throw new IllegalStateException("Inventory freeze repeated an already completed preflight");
        }
        planValidation.requireWarehouseRoutingReady(requirement.warehouseId(), requirement.stages());
        routingPreflightStages = requirement.stages();
      }
    }
  }

  private InventoryMaintenanceFreezeResult freezeLocally(
      FreezeInventoryPlanRequest request, List<InventoryPlanStageSnapshot> routingPreflightStages) {
    if (request.sourceRevision() < 1) {
      throw InventoryMaintenancePlanValidation.invalid("Inventory source revision must be at least one");
    }
    String requestFingerprint = canonicalizer.sha256(request);
    InventoryRepairSource replay = sources
        .findByInventoryIdAndFindingIdAndSourceRevision(
            request.inventoryId(), request.findingId(), request.sourceRevision())
        .orElse(null);
    if (replay != null) {
      if (!requestFingerprint.equals(replay.getPlanRequestSha256())) {
        throw conflict("Inventory source is already bound to a different frozen plan request");
      }
      return new InventoryMaintenanceFreezeResult(frozenResponse(replay), true);
    }

    CatalogVersion catalog =
        catalogs
            .findFirstByStateOrderByActivatedAtDescCreatedAtDesc(CatalogVersionState.ACTIVE)
            .orElseThrow(() -> InventoryMaintenancePlanValidation.invalid(
                "Global maintenance catalog has no active version"));
    Map<UUID, CatalogNode> nodes = catalogNodes
        .findAllByCatalogVersionIdOrderByNameAscIdAsc(catalog.getId()).stream()
        .collect(Collectors.toMap(CatalogNode::getId, Function.identity()));
    Map<UUID, List<UUID>> incomingLinks = CatalogRoutingResolver.incoming(
        catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(catalog.getId()));
    FrozenInventoryPlanSnapshot snapshot = planValidation.freezeSnapshot(
        request, catalog, nodes, incomingLinks, routingPreflightStages);
    String fingerprint = canonicalizer.sha256(snapshot);

    InventoryRepairSourceOperationId operationId =
        new InventoryRepairSourceOperationId(
            request.inventoryId(), request.findingId(), request.sourceRevision());
    String canonicalRequestFingerprint = requestFingerprint;
    registerConcurrentSafe(
        () -> sourceRegistrar.register(operationId, canonicalRequestFingerprint));
    InventoryRepairSourceOperation operation = sourceOperations.findByIdForUpdate(operationId)
        .orElseThrow(() -> new IllegalStateException(
            "Inventory repair source operation registration failed"));
    if (!operation.getRequestSha256().equals(requestFingerprint)) {
      throw conflict("Inventory source is already bound to a different frozen plan request");
    }
    InventoryRepairSource existing = sources
        .findBySourceRevisionForUpdate(
            request.inventoryId(), request.findingId(), request.sourceRevision())
        .orElse(null);
    if (existing != null) {
      if (!requestFingerprint.equals(existing.getPlanRequestSha256())) {
        throw conflict("Inventory source is already bound to a different frozen plan request");
      }
      return new InventoryMaintenanceFreezeResult(frozenResponse(existing), true);
    }
    InventoryRepairSource saved = sources.saveAndFlush(InventoryRepairSource.freeze(
        request.inventoryId(), request.findingId(), request.sourceRevision(), request.warehouseId(),
        catalog.getId(), requestFingerprint, fingerprint, write(snapshot),
        write(InventoryMaintenancePlanValidation.sourceMedia(snapshot))));
    return new InventoryMaintenanceFreezeResult(frozenResponse(saved), false);
  }

  private FrozenInventoryPlanResponse frozenResponse(InventoryRepairSource source) {
    return new FrozenInventoryPlanResponse(
        source.getWarehouseId(), source.getInventoryId(), source.getFindingId(),
        source.getSourceRevision(), read(source.getPlanSnapshot(), FrozenInventoryPlanSnapshot.class),
        source.getPlanFingerprint());
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory maintenance value cannot be serialized", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory maintenance snapshot is invalid", exception);
    }
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the immutable source key; lock and validate it below.
    }
  }

  private static MaintenanceConflictException conflict(String detail) {
    return new MaintenanceConflictException("MAINTENANCE_IDEMPOTENCY_CONFLICT", detail);
  }
}

/** Internal immutable result adapted to the facade's public freeze response. */
record InventoryMaintenanceFreezeResult(FrozenInventoryPlanResponse response, boolean replayed) {}
