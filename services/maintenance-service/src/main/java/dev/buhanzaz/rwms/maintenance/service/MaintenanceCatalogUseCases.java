package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Owns catalog-version reads, draft mutation, forking and activation workflows without taking ownership of downstream asset positions. */
@Service
public class MaintenanceCatalogUseCases {
  private static final UUID GLOBAL_CATALOG_AUDIT_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final CatalogVersionRepository catalogVersions;
  private final CatalogNodeRepository catalogNodes;
  private final CatalogLinkRepository catalogLinks;
  private final MaintenanceEventStore events;
  private final MaintenanceIdempotencyStore idempotency;
  private final FurnitureEquipmentLinkResolver furnitureEquipmentLinks;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final TransactionTemplate transactions;
  private final MaintenanceCatalogModelSupport catalogModelSupport;
  private final MaintenanceCatalogSupport catalogSupport;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;

  public MaintenanceCatalogUseCases(
      CatalogVersionRepository catalogVersions,
      CatalogNodeRepository catalogNodes,
      CatalogLinkRepository catalogLinks,
      MaintenanceEventStore events,
      MaintenanceIdempotencyStore idempotency,
      FurnitureEquipmentLinkResolver furnitureEquipmentLinks,
      WarehouseLifecycleOperations warehouseLifecycle,
      PlatformTransactionManager transactionManager,
      MaintenanceCatalogModelSupport catalogModelSupport,
      MaintenanceCatalogSupport catalogSupport,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport) {
    this.catalogVersions = catalogVersions;
    this.catalogNodes = catalogNodes;
    this.catalogLinks = catalogLinks;
    this.events = events;
    this.idempotency = idempotency;
    this.furnitureEquipmentLinks = furnitureEquipmentLinks;
    this.warehouseLifecycle = warehouseLifecycle;
    this.transactions = new TransactionTemplate(transactionManager);
    this.catalogModelSupport = catalogModelSupport;
    this.catalogSupport = catalogSupport;
    this.commandSupport = commandSupport;
    this.eventPayloadSupport = eventPayloadSupport;
  }

  public List<CatalogVersionResponse> catalogVersions(UUID authorizationWarehouseId) {
    Objects.requireNonNull(
        authorizationWarehouseId, "Catalog authorization warehouse is required");
    return catalogVersions.findAllByOrderByCreatedAtDesc().stream()
        .map(catalogModelSupport::catalogResponse)
        .toList();
  }

  public List<CatalogVersionResponse> catalogVersions() {
    return catalogVersions.findAllByOrderByCreatedAtDesc().stream()
        .map(catalogModelSupport::catalogResponse)
        .toList();
  }

  public CatalogVersionResponse catalogVersion(UUID id) { return catalogModelSupport.catalogResponse(catalogModelSupport.requireCatalog(id)); }

  public CatalogVersionResponse catalogVersion(UUID id, UUID authorizationWarehouseId) {
    Objects.requireNonNull(
        authorizationWarehouseId, "Catalog authorization warehouse is required");
    return catalogModelSupport.catalogResponse(catalogModelSupport.requireCatalog(id));
  }

  /** Returns catalog nodes enriched synchronously with their live asset-owned furniture settings. */
  public List<CatalogNodeResponse> catalogNodes(UUID id) {
    catalogModelSupport.requireCatalog(id);
    return furnitureEquipmentLinks.enrich(
        catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(id).stream()
            .map(catalogModelSupport::catalogNodeResponse)
            .toList());
  }

  public List<CatalogLinkResponse> catalogLinks(UUID id) {
    catalogModelSupport.requireCatalog(id);
    return catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(id).stream()
        .map(catalogModelSupport::catalogLinkResponse)
        .toList();
  }

  public CreateResult<CatalogVersionResponse> createCatalog(
      UUID subjectId, UUID key, CreateCatalogRequest request) {
    commandSupport.requireNoCallerTransaction("create a catalog");
    WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> preflight =
        commandSupport.inLocalTransaction(
            "catalog create preflight",
            () -> createCatalogPreflight(subjectId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    if (preflight.warehouseId() != null) {
      warehouseLifecycle.requireIncoming(preflight.warehouseId());
    }
    return commandSupport.inLocalTransaction(
        "catalog create finalization", () -> createCatalogInTransaction(subjectId, key, request));
  }

  /** Creates the installation-wide catalog using the retained server-owned audit context. */
  public CreateResult<CatalogVersionResponse> createGlobalCatalog(UUID subjectId, UUID key) {
    return createCatalog(
        subjectId, key, new CreateCatalogRequest(GLOBAL_CATALOG_AUDIT_WAREHOUSE_ID));
  }

  private WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> createCatalogPreflight(
      UUID subjectId, UUID key, CreateCatalogRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "catalog.create", key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(commandSupport.read(replay.get(), CatalogVersionResponse.class), true));
    }
    boolean activeExists =
        catalogVersions.findAllByOrderByCreatedAtDesc().stream()
            .anyMatch(version -> version.getState() == CatalogVersionState.ACTIVE);
    return new WarehouseAdmissionPreflight<>(activeExists ? null : request.warehouseId(), null);
  }

  private CreateResult<CatalogVersionResponse> createCatalogInTransaction(
      UUID subjectId, UUID key, CreateCatalogRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "catalog.create", key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), CatalogVersionResponse.class), true);
    }
    commandSupport.advisoryLock("maintenance:catalog-create:global");
    CatalogVersion existing =
        catalogVersions.findAllForUpdate().stream()
            .filter(version -> version.getState() == CatalogVersionState.ACTIVE)
            .findFirst()
            .orElse(null);
    if (existing != null) {
      CatalogVersionResponse response = catalogModelSupport.catalogResponse(existing);
      idempotency.store(subjectId, "catalog.create", key, requestHash, 201, response);
      return new CreateResult<>(response, true);
    }
    CatalogValidation validation = catalogSupport.validateCatalog(List.of(), List.of());
    Map<String, Object> report = catalogSupport.baseCatalogReport(List.of(), List.of(), validation);
    report.put("source", "CATALOG_BUILDER");
    report.put("reportSha256", commandSupport.hash(report));
    String sourceSha256 =
        commandSupport.hash(
            Map.of(
                "schema", "maintenance-global-catalog-builder-v1"));
    CatalogVersion version = catalogVersions.saveAndFlush(
        CatalogVersion.draft(
            request.warehouseId(),
            sourceSha256,
            0,
            0,
            commandSupport.write(report)));
    events.initialize(
        MaintenanceAggregateType.CATALOG_VERSION,
        version.getId(),
        version.getVersion(),
        MaintenanceEventType.CATALOG_IMPORTED,
        eventPayloadSupport.catalogLocal(version),
        eventPayloadSupport.catalogFact(MaintenanceEventType.CATALOG_IMPORTED, version),
        eventPayloadSupport.catalogSnapshot(version));
    long expectedVersion = version.getVersion();
    version.activate();
    CatalogVersion active = catalogVersions.saveAndFlush(version);
    events.append(
        MaintenanceAggregateType.CATALOG_VERSION,
        active.getId(),
        expectedVersion,
        MaintenanceEventType.CATALOG_ACTIVATED,
        eventPayloadSupport.catalogLocal(active),
        eventPayloadSupport.catalogFact(MaintenanceEventType.CATALOG_ACTIVATED, active),
        eventPayloadSupport.catalogSnapshot(active));
    warehouseLifecycle.recordOperation(
        active.getWarehouseId(), active.getId(), active.getActivatedAt());
    CatalogVersionResponse response = catalogModelSupport.catalogResponse(active);
    idempotency.store(subjectId, "catalog.create", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public CatalogVersionResponse changeCatalog(UUID id, ChangeCatalogRequest request) {
    commandSupport.requireNoCallerTransaction("change a catalog");
    UUID routingContextWarehouseId =
        transactions.execute(status -> catalogModelSupport.requireCatalog(id).getWarehouseId());
    if (routingContextWarehouseId == null) {
      throw new IllegalStateException("Catalog routing context lookup was empty");
    }
    return changeCatalog(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse changeCatalog(
      UUID id, UUID routingContextWarehouseId, ChangeCatalogRequest request) {
    commandSupport.requireNoCallerTransaction("change a catalog");
    Objects.requireNonNull(
        routingContextWarehouseId, "Catalog routing context warehouse is required");
    catalogSupport.validateCatalog(request.nodes(), request.links());
    CatalogMutationTarget target =
        transactions.execute(
            status -> {
              CatalogVersion version =
                  catalogSupport.requireMutableCatalog(id, request.expectedVersion(), false);
              return new CatalogMutationTarget(version.getState());
            });
    if (target == null) {
      throw new IllegalStateException("Catalog mutation preflight was empty");
    }
    if (target.state() == CatalogVersionState.ACTIVE) {
      warehouseLifecycle.requireIncoming(routingContextWarehouseId);
    }
    List<CatalogNodeInput> resolvedNodes = furnitureEquipmentLinks.resolve(
        id,
        routingContextWarehouseId,
        request.expectedVersion(),
        request.nodes());
    Map<UUID, String> characteristicNames =
        catalogSupport.resolveCabinCharacteristicNames(resolvedNodes);
    CatalogValidation validation = catalogSupport.validateCatalog(resolvedNodes, request.links());
    Map<UUID, RoutingSnapshot> canonicalRouting =
        catalogSupport.canonicalCatalogRouting(resolvedNodes);
    if (target.state() == CatalogVersionState.ACTIVE) {
      catalogSupport.validateCatalogRoutingForActivation(resolvedNodes, request.links());
    }
    CatalogVersionResponse result = transactions.execute(status -> changeCatalogAfterResolution(
        id,
        new ChangeCatalogRequest(request.expectedVersion(), resolvedNodes, request.links()),
        validation,
        canonicalRouting,
        characteristicNames));
    if (result == null) throw new IllegalStateException("Catalog change transaction was empty");
    return result;
  }

  private CatalogVersionResponse changeCatalogAfterResolution(
      UUID id,
      ChangeCatalogRequest request,
      CatalogValidation validation,
      Map<UUID, RoutingSnapshot> canonicalRouting,
      Map<UUID, String> characteristicNames) {
    CatalogVersion version = catalogSupport.requireMutableCatalog(id, request.expectedVersion(), true);
    List<CatalogNodeInput> previousNodes = catalogSupport.catalogNodeInputs(id);
    String contentSha256 = commandSupport.hash(new CatalogContent(request.nodes(), request.links()));
    if (contentSha256.equals(commandSupport.jsonMap(version.getValidationReport()).get("contentSha256"))) {
      return catalogModelSupport.catalogResponse(version);
    }
    catalogLinks.deleteAllByCatalogVersionId(id);
    catalogNodes.deleteAllByCatalogVersionId(id);
    catalogLinks.flush();
    catalogNodes.flush();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("valid", true);
    report.put("errorCount", 0);
    report.put("warningCount", 0);
    report.put("contentSha256", contentSha256);
    report.put("nodeCount", request.nodes().size());
    report.put("linkCount", request.links().size());
    report.put("materialCount", catalogSupport.materialCount(request.nodes()));
    report.put("dependencyAcyclic", validation.dependencyAcyclic());
    Object source = commandSupport.jsonMap(version.getValidationReport()).get("source");
    if (source != null) report.put("source", source);
    report.put("reportSha256", commandSupport.hash(report));
    version.replaceCatalog(request.nodes().size(), request.links().size(), commandSupport.write(report));
    CatalogVersion saved = catalogVersions.saveAndFlush(version);
    catalogSupport.saveCatalog(
        id,
        request.nodes(),
        request.links(),
        canonicalRouting,
        characteristicNames);
    events.append(
        MaintenanceAggregateType.CATALOG_VERSION,
        id,
        request.expectedVersion(),
        MaintenanceEventType.CATALOG_CHANGED,
        eventPayloadSupport.catalogLocal(saved),
        eventPayloadSupport.catalogFact(MaintenanceEventType.CATALOG_CHANGED, saved),
        eventPayloadSupport.catalogSnapshot(saved));
    if (saved.getState() == CatalogVersionState.ACTIVE) {
      catalogSupport.enqueueCatalogRoutingChange(saved, previousNodes, request.nodes());
    }
    return catalogModelSupport.catalogResponse(saved);
  }

  public CatalogVersionResponse replaceCatalogNodes(UUID id, ReplaceCatalogNodesRequest request) {
    commandSupport.requireNoCallerTransaction("replace catalog nodes");
    UUID routingContextWarehouseId =
        transactions.execute(status -> catalogModelSupport.requireCatalog(id).getWarehouseId());
    if (routingContextWarehouseId == null) {
      throw new IllegalStateException("Catalog routing context lookup was empty");
    }
    return replaceCatalogNodes(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse replaceCatalogNodes(
      UUID id, UUID routingContextWarehouseId, ReplaceCatalogNodesRequest request) {
    commandSupport.requireNoCallerTransaction("replace catalog nodes");
    List<CatalogLinkInput> links = transactions.execute(status -> {
      catalogModelSupport.requireCatalog(id);
      return catalogSupport.catalogLinkInputs(id);
    });
    if (links == null) throw new IllegalStateException("Catalog link read transaction was empty");
    return changeCatalog(
        id,
        routingContextWarehouseId,
        new ChangeCatalogRequest(request.expectedVersion(), request.nodes(), links));
  }

  public CatalogVersionResponse replaceCatalogLinks(UUID id, ReplaceCatalogLinksRequest request) {
    commandSupport.requireNoCallerTransaction("replace catalog links");
    UUID routingContextWarehouseId =
        transactions.execute(status -> catalogModelSupport.requireCatalog(id).getWarehouseId());
    if (routingContextWarehouseId == null) {
      throw new IllegalStateException("Catalog routing context lookup was empty");
    }
    return replaceCatalogLinks(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse replaceCatalogLinks(
      UUID id, UUID routingContextWarehouseId, ReplaceCatalogLinksRequest request) {
    commandSupport.requireNoCallerTransaction("replace catalog links");
    List<CatalogNodeInput> nodes = transactions.execute(status -> {
      catalogModelSupport.requireCatalog(id);
      return catalogSupport.catalogNodeInputs(id);
    });
    if (nodes == null) throw new IllegalStateException("Catalog node read transaction was empty");
    return changeCatalog(
        id,
        routingContextWarehouseId,
        new ChangeCatalogRequest(request.expectedVersion(), nodes, request.links()));
  }

  public CreateResult<CatalogVersionResponse> forkCatalog(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    commandSupport.requireNoCallerTransaction("fork a catalog");
    WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> preflight =
        commandSupport.inLocalTransaction(
            "catalog fork preflight", () -> forkCatalogPreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    return commandSupport.inLocalTransaction(
        "catalog fork finalization", () -> forkCatalogInTransaction(subjectId, key, id, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> forkCatalogPreflight(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    String requestHash = commandSupport.hash(request);
    String scope = "catalog.fork:" + id;
    Optional<JsonNode> replay = idempotency.replay(subjectId, scope, key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(commandSupport.read(replay.get(), CatalogVersionResponse.class), true));
    }
    CatalogVersion source = catalogModelSupport.requireCatalog(id);
    commandSupport.assertVersion(source.getVersion(), request.expectedVersion());
    if (source.getState() != CatalogVersionState.ACTIVE
        && source.getState() != CatalogVersionState.SUPERSEDED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only an active or superseded catalog version can be forked");
    }
    return new WarehouseAdmissionPreflight<>(source.getWarehouseId(), null);
  }

  private CreateResult<CatalogVersionResponse> forkCatalogInTransaction(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    String requestHash = commandSupport.hash(request);
    String scope = "catalog.fork:" + id;
    Optional<JsonNode> replay = idempotency.replay(subjectId, scope, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), CatalogVersionResponse.class), true);
    }
    commandSupport.advisoryLock("maintenance:catalog-fork:" + id);
    CatalogVersion source = catalogVersions.findByIdForUpdate(id)
        .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"));
    commandSupport.assertVersion(source.getVersion(), request.expectedVersion());
    if (source.getState() != CatalogVersionState.ACTIVE
        && source.getState() != CatalogVersionState.SUPERSEDED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only an active or superseded catalog version can be forked");
    }
    List<CatalogNodeInput> nodes = catalogSupport.catalogNodeInputs(id);
    List<CatalogLinkInput> links = catalogSupport.catalogLinkInputs(id);
    CatalogValidation validation = catalogSupport.validateCatalog(nodes, links);
    CatalogForkSnapshot sourceSnapshot = new CatalogForkSnapshot(
        source.getId(), source.getVersion(), source.getState(), nodes, links);
    String sourceSnapshotSha256 = commandSupport.hash(sourceSnapshot);
    Optional<CatalogVersion> existing =
        catalogVersions.findFirstBySourceSha256OrderByCreatedAtDesc(sourceSnapshotSha256);
    if (existing.isPresent()) {
      catalogSupport.requireMatchingFork(existing.get(), sourceSnapshot, sourceSnapshotSha256);
      CatalogVersionResponse response = catalogModelSupport.catalogResponse(existing.get());
      idempotency.store(subjectId, scope, key, requestHash, 201, response);
      return new CreateResult<>(response, true);
    }
    Map<String, Object> report =
        catalogSupport.forkCatalogReport(source, sourceSnapshotSha256, nodes, links, validation);
    CatalogVersion fork = catalogVersions.saveAndFlush(CatalogVersion.draft(
        source.getWarehouseId(), sourceSnapshotSha256, nodes.size(), links.size(), commandSupport.write(report)));
    catalogSupport.saveCatalog(
        fork.getId(),
        nodes,
        links,
        catalogSupport.canonicalRoutingFromCatalog(id),
        catalogSupport.catalogCharacteristicNames(id));
    events.initialize(
        MaintenanceAggregateType.CATALOG_VERSION,
        fork.getId(),
        fork.getVersion(),
        MaintenanceEventType.CATALOG_IMPORTED,
        eventPayloadSupport.catalogLocal(fork),
        eventPayloadSupport.catalogFact(MaintenanceEventType.CATALOG_IMPORTED, fork),
        eventPayloadSupport.catalogSnapshot(fork));
    CatalogVersionResponse response = catalogModelSupport.catalogResponse(fork);
    idempotency.store(subjectId, scope, key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public CreateResult<CatalogVersionResponse> activateCatalog(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    commandSupport.requireNoCallerTransaction("activate a catalog");
    UUID routingContextWarehouseId =
        transactions.execute(status -> catalogModelSupport.requireCatalog(id).getWarehouseId());
    if (routingContextWarehouseId == null) {
      throw new IllegalStateException("Catalog routing context lookup was empty");
    }
    return activateCatalog(subjectId, key, id, routingContextWarehouseId, request);
  }

  public CreateResult<CatalogVersionResponse> activateCatalog(
      UUID subjectId,
      UUID key,
      UUID id,
      UUID routingContextWarehouseId,
      VersionCommand request) {
    commandSupport.requireNoCallerTransaction("activate a catalog");
    Objects.requireNonNull(
        routingContextWarehouseId, "Catalog routing context warehouse is required");
    transactions.executeWithoutResult(status -> catalogModelSupport.requireCatalog(id));
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = transactions.execute(status -> idempotency.replay(
        subjectId, "catalog.activate:" + id, key, requestHash));
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), CatalogVersionResponse.class), true);
    }
    Boolean preflight = transactions.execute(
        status -> activationPreflight(id, request.expectedVersion()));
    if (!Boolean.TRUE.equals(preflight)) {
      throw new IllegalStateException("Catalog activation preflight transaction was empty");
    }
    warehouseLifecycle.requireIncoming(routingContextWarehouseId);
    catalogSupport.canonicalCatalogRouting(catalogSupport.catalogNodeInputs(id));
    CreateResult<CatalogVersionResponse> result = transactions.execute(
        status -> activateCatalogAfterPreflight(subjectId, key, id, request, requestHash));
    if (result == null) throw new IllegalStateException("Catalog activation transaction was empty");
    return result;
  }

  private boolean activationPreflight(UUID id, long expectedVersion) {
    CatalogVersion selected = catalogModelSupport.requireCatalog(id);
    commandSupport.assertVersion(selected.getVersion(), expectedVersion);
    if (selected.getState() != CatalogVersionState.DRAFT) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Only a draft catalog version can be activated");
    }
    catalogSupport.validateFurnitureCatalogForActivation(id);
    catalogSupport.validateCatalogRoutingForActivation(id);
    return true;
  }

  private CreateResult<CatalogVersionResponse> activateCatalogAfterPreflight(
      UUID subjectId, UUID key, UUID id, VersionCommand request, String requestHash) {
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "catalog.activate:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), CatalogVersionResponse.class), true);
    }
    commandSupport.advisoryLock("maintenance:catalog-activation:global");
    CatalogVersion selected = catalogVersions.findByIdForUpdate(id)
        .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"));
    commandSupport.assertVersion(selected.getVersion(), request.expectedVersion());
    catalogSupport.validateFurnitureCatalogForActivation(id);
    catalogSupport.validateCatalogRoutingForActivation(id);
    List<CatalogVersion> activeVersions = catalogVersions
        .findAllForUpdate().stream()
        .filter(value -> value.getState() == CatalogVersionState.ACTIVE)
        .toList();
    if (activeVersions.size() > 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Global catalog has more than one active version");
    }
    CatalogVersion active = activeVersions.isEmpty() ? null : activeVersions.getFirst();
    CatalogVersion superseded = null;
    List<MaintenanceEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.CATALOG_VERSION, id));
    if (active != null && !active.getId().equals(id)) {
      streams.add(new MaintenanceEventStore.StreamRef(
          MaintenanceAggregateType.CATALOG_VERSION, active.getId()));
    }
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(streams);
    commandSupport.assertVersion(locked.get(new MaintenanceEventStore.StreamRef(
        MaintenanceAggregateType.CATALOG_VERSION, id)), request.expectedVersion());
    if (active != null && !active.getId().equals(id)) {
      long previousVersion = active.getVersion();
      active.supersede();
      superseded = catalogVersions.saveAndFlush(active);
      events.append(
          MaintenanceAggregateType.CATALOG_VERSION,
          superseded.getId(),
          previousVersion,
          MaintenanceEventType.CATALOG_SUPERSEDED,
          eventPayloadSupport.catalogLocal(superseded),
          eventPayloadSupport.catalogFact(MaintenanceEventType.CATALOG_SUPERSEDED, superseded),
          eventPayloadSupport.catalogSnapshot(superseded));
    }
    selected.activate();
    CatalogVersion saved = catalogVersions.saveAndFlush(selected);
    events.append(
        MaintenanceAggregateType.CATALOG_VERSION,
        id,
        request.expectedVersion(),
        MaintenanceEventType.CATALOG_ACTIVATED,
        eventPayloadSupport.catalogLocal(saved),
        eventPayloadSupport.catalogFact(MaintenanceEventType.CATALOG_ACTIVATED, saved),
        eventPayloadSupport.catalogSnapshot(saved));
    warehouseLifecycle.recordOperation(
        saved.getWarehouseId(), saved.getId(), saved.getActivatedAt());
    catalogSupport.enqueueCatalogRouting(saved, superseded);
    CatalogVersionResponse response = catalogModelSupport.catalogResponse(saved);
    idempotency.store(subjectId, "catalog.activate:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

}
