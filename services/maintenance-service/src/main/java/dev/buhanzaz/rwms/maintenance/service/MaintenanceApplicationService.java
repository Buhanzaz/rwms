package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.OperationLeaseFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceActorReferenceProvider;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.OperationLeaseFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MaintenanceApplicationService {
  private static final Duration LEASE_RENEWAL_GUARD = Duration.ofMinutes(5);

  private final CatalogVersionRepository catalogVersions;
  private final CatalogNodeRepository catalogNodes;
  private final CatalogLinkRepository catalogLinks;
  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository estimateLines;
  private final EstimatePlanStageRepository estimatePlans;
  private final EstimateRevisionRepository estimateRevisions;
  private final MaintenanceRepairRepository repairs;
  private final InventoryRepairSourceRepository inventorySources;
  private final RepairStageRepository repairStages;
  private final MaintenanceMediaReferenceRepository mediaReferences;
  private final MediaFactProjectionRepository mediaFacts;
  private final RentalItemFactProjectionRepository rentalItemFacts;
  private final OperationLeaseFactProjectionRepository leaseFacts;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceActorReferenceProvider actorReferences;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceIdempotencyStore idempotency;
  private final MaintenanceReconciliationStore reconciliations;
  private final ReviewedLegacyCatalogManifest reviewedLegacyCatalog;
  private final MaintenanceDependencyGateway dependencies;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public MaintenanceApplicationService(
      CatalogVersionRepository catalogVersions,
      CatalogNodeRepository catalogNodes,
      CatalogLinkRepository catalogLinks,
      MaintenanceEstimateRepository estimates,
      EstimateLineRepository estimateLines,
      EstimatePlanStageRepository estimatePlans,
      EstimateRevisionRepository estimateRevisions,
      MaintenanceRepairRepository repairs,
      InventoryRepairSourceRepository inventorySources,
      RepairStageRepository repairStages,
      MaintenanceMediaReferenceRepository mediaReferences,
      MediaFactProjectionRepository mediaFacts,
      RentalItemFactProjectionRepository rentalItemFacts,
      OperationLeaseFactProjectionRepository leaseFacts,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceActorReferenceProvider actorReferences,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceIdempotencyStore idempotency,
      MaintenanceReconciliationStore reconciliations,
      ReviewedLegacyCatalogManifest reviewedLegacyCatalog,
      MaintenanceDependencyGateway dependencies,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.catalogVersions = catalogVersions;
    this.catalogNodes = catalogNodes;
    this.catalogLinks = catalogLinks;
    this.estimates = estimates;
    this.estimateLines = estimateLines;
    this.estimatePlans = estimatePlans;
    this.estimateRevisions = estimateRevisions;
    this.repairs = repairs;
    this.inventorySources = inventorySources;
    this.repairStages = repairStages;
    this.mediaReferences = mediaReferences;
    this.mediaFacts = mediaFacts;
    this.rentalItemFacts = rentalItemFacts;
    this.leaseFacts = leaseFacts;
    this.events = events;
    this.eventFacts = eventFacts;
    this.actorReferences = actorReferences;
    this.projectionSnapshots = projectionSnapshots;
    this.idempotency = idempotency;
    this.reconciliations = reconciliations;
    this.reviewedLegacyCatalog = reviewedLegacyCatalog;
    this.dependencies = dependencies;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Transactional(readOnly = true)
  public List<CatalogVersionResponse> catalogVersions(UUID warehouseId) {
    return catalogVersions.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream().map(this::catalogResponse).toList();
  }

  @Transactional(readOnly = true)
  public CatalogVersionResponse catalogVersion(UUID id) { return catalogResponse(requireCatalog(id)); }

  @Transactional(readOnly = true)
  public CatalogVersionResponse catalogVersion(UUID id, UUID warehouseId) {
    return catalogResponse(catalogVersions.findByIdAndWarehouseId(id, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found")));
  }

  @Transactional(readOnly = true)
  public List<CatalogNodeResponse> catalogNodes(UUID id) {
    requireCatalog(id);
    return catalogNodes.findAllByCatalogVersionIdOrderByCode(id).stream().map(this::catalogNodeResponse).toList();
  }

  @Transactional(readOnly = true)
  public List<CatalogLinkResponse> catalogLinks(UUID id) {
    requireCatalog(id);
    return catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(id).stream()
        .map(this::catalogLinkResponse)
        .toList();
  }

  @Transactional
  public CreateResult<CatalogVersionResponse> importCatalog(
      UUID subjectId, UUID key, ImportCatalogRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "catalog.import", key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true);
    }
    ReviewedLegacyCatalogManifest.Review review = reviewedLegacyCatalog.validate(request);
    CatalogValidation validation = validateCatalog(request.nodes(), request.links());
    Optional<CatalogVersion> existing = catalogVersions.findByWarehouseIdAndSourceSha256(
        request.warehouseId(), request.sourceSha256());
    if (existing.isPresent()) {
      Map<String, Object> report = jsonMap(existing.get().getValidationReport());
      if (!requestHash.equals(report.get("contentSha256"))) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_IDEMPOTENCY_CONFLICT",
            "Catalog source hash is already bound to different validated content");
      }
      CatalogVersionResponse response = catalogResponse(existing.get());
      idempotency.store(subjectId, "catalog.import", key, requestHash, 201, response);
      return new CreateResult<>(response, true);
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("valid", true);
    report.put("errorCount", 0);
    report.put("warningCount", 0);
    report.put("contentSha256", requestHash);
    report.put("nodeCount", request.nodes().size());
    report.put("linkCount", request.links().size());
    report.put("dependencyAcyclic", validation.dependencyAcyclic());
    report.put("sourceEvidenceSha256", review.sourceEvidenceSha256());
    report.put("nodeEvidenceSha256", review.nodeEvidenceSha256());
    report.put("linkEvidenceSha256", review.linkEvidenceSha256());
    report.put("queueEvidenceSha256", review.queueEvidenceSha256());
    report.put("mappingSha256", review.mappingSha256());
    report.put("nodeTypes", review.nodeTypes());
    report.put("linkTypes", review.linkTypes());
    report.put("reportSha256", hash(report));
    CatalogVersion version = catalogVersions.saveAndFlush(
        CatalogVersion.draft(
            request.warehouseId(), request.sourceSha256(), request.nodes().size(), request.links().size(), write(report)));
    saveCatalog(version.getId(), version.getWarehouseId(), request.nodes(), request.links());
    events.initialize(
        MaintenanceAggregateType.CATALOG_VERSION,
        version.getId(),
        version.getVersion(),
        MaintenanceEventType.CATALOG_IMPORTED,
        catalogLocal(version),
        catalogFact(MaintenanceEventType.CATALOG_IMPORTED, version),
        catalogSnapshot(version));
    CatalogVersionResponse response = catalogResponse(version);
    idempotency.store(subjectId, "catalog.import", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CatalogVersionResponse changeCatalog(UUID id, ChangeCatalogRequest request) {
    CatalogVersion version = requireCatalog(id);
    assertVersion(version.getVersion(), request.expectedVersion());
    CatalogValidation validation = validateCatalog(request.nodes(), request.links());
    catalogNodes.findAllByCatalogVersionIdOrderByCode(id).forEach(node ->
        mediaReferences.deleteAllByAggregateTypeAndAggregateId("CATALOG_NODE", node.getRowId()));
    mediaReferences.flush();
    catalogLinks.deleteAllByCatalogVersionId(id);
    catalogNodes.deleteAllByCatalogVersionId(id);
    catalogLinks.flush();
    catalogNodes.flush();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("valid", true);
    report.put("errorCount", 0);
    report.put("warningCount", 0);
    report.put("contentSha256", hash(Map.of("nodes", request.nodes(), "links", request.links())));
    report.put("nodeCount", request.nodes().size());
    report.put("linkCount", request.links().size());
    report.put("dependencyAcyclic", validation.dependencyAcyclic());
    report.put("reportSha256", hash(report));
    version.replaceDraft(request.nodes().size(), request.links().size(), write(report));
    CatalogVersion saved = catalogVersions.saveAndFlush(version);
    saveCatalog(id, version.getWarehouseId(), request.nodes(), request.links());
    events.append(
        MaintenanceAggregateType.CATALOG_VERSION,
        id,
        request.expectedVersion(),
        MaintenanceEventType.CATALOG_CHANGED,
        catalogLocal(saved),
        catalogFact(MaintenanceEventType.CATALOG_CHANGED, saved),
        catalogSnapshot(saved));
    return catalogResponse(saved);
  }

  @Transactional
  public CatalogVersionResponse replaceCatalogNodes(UUID id, ReplaceCatalogNodesRequest request) {
    List<CatalogLinkInput> links = catalogLinks(id).stream().map(value -> new CatalogLinkInput(
        value.id(), value.fromNodeId(), value.toNodeId(), value.linkType(), value.sortOrder())).toList();
    return changeCatalog(id, new ChangeCatalogRequest(request.expectedVersion(), request.nodes(), links));
  }

  @Transactional
  public CatalogVersionResponse replaceCatalogLinks(UUID id, ReplaceCatalogLinksRequest request) {
    List<CatalogNodeInput> nodes = catalogNodes(id).stream().map(value -> new CatalogNodeInput(
        value.id(), value.code(), value.nodeType(), value.name(), value.active(), value.parentNodeId(),
        value.unit(), value.unitPrice(), value.durationMinutes(), value.includeInEstimate(),
        value.commonItem(), value.showInMainMenu(), value.photoRequired(), value.routing(),
        value.references(), value.comment(), value.mediaReferences())).toList();
    return changeCatalog(id, new ChangeCatalogRequest(request.expectedVersion(), nodes, request.links()));
  }

  @Transactional
  public CreateResult<CatalogVersionResponse> activateCatalog(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "catalog.activate:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true);
    }
    UUID warehouseId = jdbc.query(
        "select warehouse_id from catalog_version where id=?",
        (resultSet, rowNumber) -> resultSet.getObject("warehouse_id", UUID.class),
        id).stream().findFirst().orElseThrow(
            () -> new MaintenanceNotFoundException("Catalog version not found"));
    advisoryLock("maintenance:catalog-activation:" + warehouseId);
    CatalogVersion selected = catalogVersions.findByIdForUpdate(id)
        .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"));
    if (!warehouseId.equals(selected.getWarehouseId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Catalog warehouse changed during activation");
    }
    assertVersion(selected.getVersion(), request.expectedVersion());
    CatalogVersion active = catalogVersions.findAllByWarehouseIdForUpdate(warehouseId).stream()
        .filter(value -> value.getState() == CatalogVersionState.ACTIVE)
        .findFirst()
        .orElse(null);
    List<MaintenanceEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.CATALOG_VERSION, id));
    if (active != null && !active.getId().equals(id)) {
      streams.add(new MaintenanceEventStore.StreamRef(
          MaintenanceAggregateType.CATALOG_VERSION, active.getId()));
    }
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(streams);
    assertVersion(locked.get(new MaintenanceEventStore.StreamRef(
        MaintenanceAggregateType.CATALOG_VERSION, id)), request.expectedVersion());
    if (active != null && !active.getId().equals(id)) {
      long previousVersion = active.getVersion();
      active.supersede();
      CatalogVersion superseded = catalogVersions.saveAndFlush(active);
      events.append(
          MaintenanceAggregateType.CATALOG_VERSION,
          superseded.getId(),
          previousVersion,
          MaintenanceEventType.CATALOG_SUPERSEDED,
          catalogLocal(superseded),
          catalogFact(MaintenanceEventType.CATALOG_SUPERSEDED, superseded),
          catalogSnapshot(superseded));
    }
    selected.activate();
    CatalogVersion saved = catalogVersions.saveAndFlush(selected);
    events.append(
        MaintenanceAggregateType.CATALOG_VERSION,
        id,
        request.expectedVersion(),
        MaintenanceEventType.CATALOG_ACTIVATED,
        catalogLocal(saved),
        catalogFact(MaintenanceEventType.CATALOG_ACTIVATED, saved),
        catalogSnapshot(saved));
    CatalogVersionResponse response = catalogResponse(saved);
    idempotency.store(subjectId, "catalog.activate:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  @Transactional(readOnly = true)
  public List<EstimateResponse> estimates(UUID warehouseId) {
    return estimates.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream().map(this::estimateResponse).toList();
  }

  @Transactional(readOnly = true)
  public EstimateResponse estimate(UUID id) { return estimateResponse(requireEstimate(id)); }

  @Transactional(readOnly = true)
  public EstimateResponse estimate(UUID id, UUID warehouseId) {
    return estimateResponse(estimates.findByIdAndWarehouseId(id, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Estimate not found")));
  }

  @Transactional
  public CreateResult<EstimateResponse> createEstimate(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.create", key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EstimateResponse.class), true);
    CatalogVersion catalog = catalogVersions.findByWarehouseIdAndState(
        request.warehouseId(), CatalogVersionState.ACTIVE).orElseThrow(() ->
        new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "Warehouse has no active catalog version"));
    validateEstimatePlan(request.lines(), request.plan());
    RentalItemFactProjection rentalItem = requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    MaintenanceEstimate estimate = estimates.saveAndFlush(MaintenanceEstimate.create(
        request.warehouseId(), request.rentalItemId(), rentalItem.getAggregateVersion(),
        catalog.getId(), request.dispatchDate(), request.sourceParty(), null, actorJson()));
    replaceEstimateRevision(estimate, request.lines(), request.plan(), null);
    replaceMedia("ESTIMATE", "MAINTENANCE_ESTIMATE", estimate.getId(), estimate.getWarehouseId(),
        request.mediaReferences());
    events.initialize(
        MaintenanceAggregateType.ESTIMATE,
        estimate.getId(),
        estimate.getVersion(),
        MaintenanceEventType.ESTIMATE_CREATED,
        estimateLocal(estimate),
        estimateFact(MaintenanceEventType.ESTIMATE_CREATED, estimate),
        estimateSnapshot(estimate));
    EstimateResponse response = estimateResponse(estimate);
    idempotency.store(subjectId, "estimate.create", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public EstimateResponse updateEstimate(UUID id, UpdateEstimateRequest request) {
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    validateEstimatePlan(request.lines(), request.plan());
    estimate.replaceMetadata(request.dispatchDate(), request.sourceParty(), estimate.getComment());
    estimate.touchDraft();
    replaceEstimateRevision(estimate, request.lines(), request.plan(), null);
    replaceMedia("ESTIMATE", "MAINTENANCE_ESTIMATE", id, estimate.getWarehouseId(),
        request.mediaReferences());
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        id,
        request.expectedVersion(),
        MaintenanceEventType.ESTIMATE_DRAFT_CHANGED,
        estimateLocal(saved),
        estimateFact(MaintenanceEventType.ESTIMATE_DRAFT_CHANGED, saved),
        estimateSnapshot(saved));
    return estimateResponse(saved);
  }

  @Transactional
  public CreateResult<EstimateCommandResult> completeEstimate(
      UUID subjectId, UUID key, UUID id, CompleteEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.complete:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), EstimateCommandResult.class), true);
    }
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    List<EstimateLine> lines = currentLines(estimate);
    MaintenanceRepair commandRepair = null;
    if (lines.isEmpty()) {
      estimate.complete(null);
      reconciliations.enqueue(
          null,
          "ASSET",
          "COMPLETE_EMPTY_ESTIMATE",
          derived(key, "empty-asset"),
          Map.of("estimateId", estimate.getId().toString()));
    } else {
      commandRepair = createEstimateRepair(estimate, currentPlan(estimate));
      estimate.complete(commandRepair.getId());
      enqueueRepairQueue(commandRepair, derived(key, "queue-repair"), false);
    }
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        id,
        request.expectedVersion(),
        MaintenanceEventType.ESTIMATE_COMPLETED,
        estimateLocal(saved),
        estimateFact(MaintenanceEventType.ESTIMATE_COMPLETED, saved),
        estimateSnapshot(saved));
    EstimateCommandResult response = new EstimateCommandResult(
        estimateResponse(saved), commandRepair == null ? null : repairResponse(commandRepair),
        commandRepair == null
            ? new DeliverySnapshot(DeliveryState.RETRY_PENDING, 0, saved.getUpdatedAt())
            : delivery(commandRepair));
    idempotency.store(subjectId, "estimate.complete:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<EstimateCommandResult> amendEstimate(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.amend:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), EstimateCommandResult.class), true);
    }
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    validateEstimatePlan(request.lines(), request.plan());
    MaintenanceRepair repair = estimate.getRepairId() == null ? null : requireRepair(estimate.getRepairId());
    List<MaintenanceEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.ESTIMATE, id));
    MaintenanceRepair linkedRepair = repair;
    if (repair != null) {
      streams.add(new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.REPAIR, repair.getId()));
    }
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(streams);
    assertVersion(locked.get(new MaintenanceEventStore.StreamRef(
        MaintenanceAggregateType.ESTIMATE, id)), request.expectedVersion());
    if (repair != null) {
      if (request.expectedLinkedRepairVersion() == null) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "repairExpectedVersion is required for a linked repair amendment");
      }
      assertVersion(locked.get(new MaintenanceEventStore.StreamRef(
          MaintenanceAggregateType.REPAIR, repair.getId())), request.expectedLinkedRepairVersion());
      assertVersion(repair.getVersion(), request.expectedLinkedRepairVersion());
      repair.requirePreStartAmendment();
      if (request.lines().isEmpty()) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "An estimate linked to a repair cannot be amended to zero lines");
      }
      repair.amendPreStartPlan();
      replaceRepairStages(repair, request.plan());
      MaintenanceRepair repairSaved = repairs.saveAndFlush(repair);
      linkedRepair = repairSaved;
      if (repairSaved.getExecutionState() == RepairExecutionState.QUEUED
          && repairSaved.getTaskBoardVersion() != null) {
        reconciliations.enqueue(
            repairSaved.getId(),
            "TASK_BOARD",
            "UPDATE_TASK",
            derived(key, "task-update"),
            Map.of("repairId", repairSaved.getId().toString()));
      }
      events.append(
          MaintenanceAggregateType.REPAIR,
          repair.getId(),
          request.expectedLinkedRepairVersion(),
          MaintenanceEventType.REPAIR_PLAN_CHANGED,
          repairLocal(repairSaved),
          repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, repairSaved),
          repairSnapshot(repairSaved));
    } else {
      if (request.expectedLinkedRepairVersion() != null) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_VERSION_CONFLICT",
            "An estimate without a linked repair cannot accept a repair version");
      }
      if (request.lines().isEmpty()) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "An empty completed estimate needs no empty amendment revision");
      }
    }
    UUID linkedRepairId = linkedRepair == null ? null : linkedRepair.getId();
    estimate.replaceCompletedMetadata(
        request.dispatchDate(), request.sourceParty(), estimate.getComment(), linkedRepairId);
    replaceEstimateRevision(estimate, request.lines(), request.plan(), request.reason());
    if (linkedRepair == null) {
      linkedRepair = createEstimateRepairFromPlan(estimate, request.plan());
      estimate.linkCompletedRepair(linkedRepair.getId());
      enqueueRepairQueue(linkedRepair, derived(key, "queue-first-repair"), false);
    }
    replaceMedia("ESTIMATE", "MAINTENANCE_ESTIMATE", id, estimate.getWarehouseId(),
        request.mediaReferences());
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        id,
        request.expectedVersion(),
        MaintenanceEventType.ESTIMATE_AMENDED,
        estimateLocal(saved),
        estimateFact(MaintenanceEventType.ESTIMATE_AMENDED, saved),
        estimateSnapshot(saved));
    MaintenanceRepair linked = saved.getRepairId() == null ? null : requireRepair(saved.getRepairId());
    EstimateCommandResult response = new EstimateCommandResult(
        estimateResponse(saved), linked == null ? null : repairResponse(linked),
        linked == null ? new DeliverySnapshot(DeliveryState.DELIVERED, 0, saved.getUpdatedAt()) : delivery(linked));
    idempotency.store(subjectId, "estimate.amend:" + id, key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional(readOnly = true)
  public List<RepairResponse> repairs(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream().map(this::repairResponse).toList();
  }

  @Transactional(readOnly = true)
  public RepairResponse repair(UUID id) { return repairResponse(requireRepair(id)); }

  @Transactional(readOnly = true)
  public RepairResponse repair(UUID id, UUID warehouseId) {
    return repairResponse(repairs.findByIdAndWarehouseId(id, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found")));
  }

  @Transactional(readOnly = true)
  public RepairPlanResponse repairPlan(UUID id) {
    RepairResponse repair = repair(id);
    return repair.plan();
  }

  @Transactional
  public CreateResult<RepairResponse> createDirectRepair(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.direct", key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), RepairResponse.class), true);
    RentalItemFactProjection rentalItem = requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    MaintenanceRepair repair = repairs.saveAndFlush(MaintenanceRepair.primary(
        request.warehouseId(), request.rentalItemId(), rentalItem.getAggregateVersion(), null,
        RepairOrigin.DIRECT_REPAIR,
        request.dispatchDate(), request.sourceParty(), actorJson()));
    replaceRepairStages(repair, request.plan());
    replaceMedia("REPAIR", "MAINTENANCE_REPAIR", repair.getId(), repair.getWarehouseId(),
        request.mediaReferences());
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        repairLocal(repair),
        repairFact(MaintenanceEventType.REPAIR_CREATED, repair),
        repairSnapshot(repair));
    RepairResponse response = repairResponse(repair);
    idempotency.store(subjectId, "repair.direct", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public RepairResponse updateRepairPlan(UUID id, UpdateRepairPlanRequest request) {
    MaintenanceRepair repair = requireRepair(id);
    assertVersion(repair.getVersion(), request.expectedVersion());
    repair.touchPlan();
    replaceRepairStages(repair, request.stages());
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        id,
        request.expectedVersion(),
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        repairLocal(saved),
        repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
        repairSnapshot(saved));
    return repairResponse(saved);
  }

  @Transactional
  public CreateResult<RepairCommandResult> queueRepair(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.queue:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), RepairCommandResult.class), true);
    }
    MaintenanceRepair repair = requireRepair(id);
    assertVersion(repair.getVersion(), request.expectedVersion());
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(id);
    if (stages.isEmpty()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED", "Repair needs at least one planned stage before queueing");
    }
    MaintenanceRepair saved;
    if (repair.getKind() == RepairKind.REWORK) {
      LockedRework lockedRework = lockAndReloadRework(repair, request.expectedVersion());
      repair = lockedRework.rework();
      MaintenanceRepair root = lockedRework.root();
      MaintenanceRepair source = lockedRework.source();
      requireLease(root);
      MaintenanceDependencyGateway.LeaseSnapshot lease = new MaintenanceDependencyGateway.LeaseSnapshot(
          root.getLeaseId(), root.getLeaseVersion(), root.getRentalItemId(), ownerType(root),
          UUID.fromString(ownerId(root)), root.getFencingToken(), root.getLeaseExpiresAt());
      long sourceExpectedVersion = lockedRework.streamVersions().get(stream(source.getId()));
      source.enterRework();
      MaintenanceRepair sourceSaved = repairs.saveAndFlush(source);
      events.append(
          MaintenanceAggregateType.REPAIR,
          source.getId(),
          sourceExpectedVersion,
          MaintenanceEventType.REPAIR_REWORK_CREATED,
          repairLocal(sourceSaved),
          repairFact(MaintenanceEventType.REPAIR_REWORK_CREATED, sourceSaved),
          repairSnapshot(sourceSaved));
      stages = repairStages.findAllByRepairIdOrderByStageNo(id);
      stages.forEach(RepairStage::queued);
      repairStages.saveAllAndFlush(stages);
      repair.queue(lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
      saved = repairs.saveAndFlush(repair);
      enqueueTaskRegistration(saved, derived(key, "task-register"));
      events.append(
          MaintenanceAggregateType.REPAIR,
          id,
          request.expectedVersion(),
          MaintenanceEventType.REPAIR_QUEUED,
          repairLocal(saved),
          repairFact(MaintenanceEventType.REPAIR_QUEUED, saved),
          repairSnapshot(saved));
    } else {
      Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(List.of(stream(id)));
      assertVersion(locked.get(stream(id)), request.expectedVersion());
      enqueueRepairQueue(repair, stableOperationKey("queue-repair", repair.getId(), 0), false);
      saved = repair;
    }
    List<RepairResponse> affected = saved.getSourceRepairId() == null
        ? List.of() : List.of(repairResponse(requireRepair(saved.getSourceRepairId())));
    RepairCommandResult response = new RepairCommandResult(
        repairResponse(saved), affected, delivery(saved));
    idempotency.store(subjectId, "repair.queue:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<RepairResponse> createRework(
      UUID subjectId, UUID key, UUID sourceId, CreateReworkRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.rework:" + sourceId, key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), RepairResponse.class), true);
    MaintenanceRepair initialSource = requireRepair(sourceId);
    List<UUID> ids = new ArrayList<>();
    ids.add(sourceId);
    if (initialSource.getRootRepairId() != null) ids.add(initialSource.getRootRepairId());
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        ids.stream().map(MaintenanceApplicationService::stream).toList());
    assertVersion(locked.get(stream(sourceId)), request.expectedVersion());
    Map<UUID, MaintenanceRepair> current = repairs.findAllByIdForUpdate(ids).stream()
        .collect(java.util.stream.Collectors.toMap(MaintenanceRepair::getId, value -> value));
    MaintenanceRepair source = Optional.ofNullable(current.get(sourceId))
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    assertVersion(source.getVersion(), request.expectedVersion());
    assertStreamParity(source, locked);
    if (source.getRootRepairId() != null) {
      MaintenanceRepair root = Optional.ofNullable(current.get(source.getRootRepairId()))
          .orElseThrow(() -> new MaintenanceConflictException(
              "MAINTENANCE_STATE_CONFLICT", "Rework root repair is missing"));
      assertStreamParity(root, locked);
      validateReworkOwnership(source, root);
    }
    if (repairs.existsBySourceRepairIdAndExecutionStateIn(
        sourceId, List.of(
            RepairExecutionState.DRAFT,
            RepairExecutionState.QUEUED,
            RepairExecutionState.IN_PROGRESS))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "An active sibling rework already exists");
    }
    MaintenanceRepair child = repairs.saveAndFlush(
        MaintenanceRepair.rework(source, request.reason(), actorJson()));
    replaceRepairStages(child, request.plan());
    replaceMedia("REPAIR", "MAINTENANCE_REPAIR", child.getId(), child.getWarehouseId(),
        request.mediaReferences());
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        child.getId(),
        child.getVersion(),
        MaintenanceEventType.REPAIR_REWORK_CREATED,
        repairLocal(child),
        repairFact(MaintenanceEventType.REPAIR_REWORK_CREATED, child),
        repairSnapshot(child));
    RepairResponse response = repairResponse(child);
    idempotency.store(subjectId, "repair.rework:" + sourceId, key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<RepairCommandResult> accept(
      UUID subjectId, UUID key, UUID id, RepairDecisionRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "repair.accept:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), RepairCommandResult.class), true);
    }
    MaintenanceRepair initial = requireRepair(id);
    assertVersion(initial.getVersion(), request.expectedVersion());
    LockedRepairChain lockedChain = lockAndReloadRepairChain(initial, request.expectedVersion());
    MaintenanceRepair repair = lockedChain.repair();
    List<MaintenanceRepair> sourceChain = lockedChain.sources();
    requireNoActiveRework(repair);
    requireLease(repair);
    repair.accept(request.comment(), actorJson());
    repair.markLeaseReconciliationRequired();
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        id,
        request.expectedVersion(),
        MaintenanceEventType.REPAIR_ACCEPTED,
        decisionLocal(id, request.comment()),
        repairFact(MaintenanceEventType.REPAIR_ACCEPTED, saved),
        repairSnapshot(saved));
    cascadeTerminal(saved, sourceChain, true);
    enqueueTerminalAsset(saved, "ACCEPT_TO_FREE", stableOperationKey(
        "accept-asset", saved.getId(), saved.getVersion()));
    RepairCommandResult response = new RepairCommandResult(
        repairResponse(saved), sourceChain.stream().map(this::repairResponse).toList(), delivery(saved));
    idempotency.store(subjectId, "repair.accept:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<RepairCommandResult> writeOff(
      UUID subjectId, UUID key, UUID id, WriteOffRepairRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "repair.write-off:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), RepairCommandResult.class), true);
    }
    MaintenanceRepair initial = requireRepair(id);
    assertVersion(initial.getVersion(), request.expectedVersion());
    LockedRepairChain lockedChain = lockAndReloadRepairChain(initial, request.expectedVersion());
    MaintenanceRepair repair = lockedChain.repair();
    List<MaintenanceRepair> sourceChain = lockedChain.sources();
    requireNoActiveRework(repair);
    requireLease(repair);
    repair.writeOff(combineDecision(request.reason(), request.comment()), actorJson());
    repair.markLeaseReconciliationRequired();
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        id,
        request.expectedVersion(),
        MaintenanceEventType.REPAIR_WRITTEN_OFF,
        decisionLocal(id, combineDecision(request.reason(), request.comment())),
        repairFact(MaintenanceEventType.REPAIR_WRITTEN_OFF, saved),
        repairSnapshot(saved));
    cascadeTerminal(saved, sourceChain, false);
    enqueueTerminalAsset(saved, "WRITE_OFF", stableOperationKey(
        "write-off-asset", saved.getId(), saved.getVersion()));
    RepairCommandResult response = new RepairCommandResult(
        repairResponse(saved), sourceChain.stream().map(this::repairResponse).toList(), delivery(saved));
    idempotency.store(subjectId, "repair.write-off:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  @Transactional(readOnly = true)
  public List<AcceptanceProjection> acceptance(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream()
        .filter(value -> value.getAcceptanceState() == RepairAcceptanceState.PENDING
            || value.getAcceptanceState() == RepairAcceptanceState.IN_REWORK)
        .map(value -> new AcceptanceProjection(
            value.getId(), rootId(value), value.getWarehouseId(), value.getRentalItemId(),
            value.getExecutionState(), value.getAcceptanceState(), value.getVersion(), value.getUpdatedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<WriteOffProjection> writeOffs(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream()
        .filter(value -> value.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF)
        .map(value -> new WriteOffProjection(
            value.getId(), rootId(value), value.getWarehouseId(), value.getRentalItemId(),
            value.getVersion(), value.getDecisionRecordedAt() == null
                ? value.getUpdatedAt() : value.getDecisionRecordedAt(),
            actor(value.getDecisionActorRef() == null ? value.getActorRef() : value.getDecisionActorRef())))
        .toList();
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundMediaFact(
      UUID mediaId,
      long generation,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      String status,
      String safeMetadata,
      long aggregateVersion) {
    String localStatus = switch (status) {
      case "UPLOADING", "PROCESSING" -> "PENDING";
      case "READY", "FAILED", "DELETED" -> status;
      default -> throw new IllegalArgumentException("Unsupported media status " + status);
    };
    MediaFactProjection fact = mediaFacts.findById(mediaId).orElseGet(() ->
        MediaFactProjection.create(
            mediaId, generation, ownerType, ownerId, warehouseId, localStatus,
            safeMetadata, aggregateVersion));
    if (fact.getAggregateVersion() < aggregateVersion) {
      fact.apply(
          generation, ownerType, ownerId, warehouseId, localStatus,
          safeMetadata, aggregateVersion);
    }
    mediaFacts.save(fact);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundRentalItemFact(
      UUID rentalItemId,
      UUID warehouseId,
      String status,
      long aggregateVersion) {
    RentalItemFactProjection fact = rentalItemFacts.findById(rentalItemId).orElseGet(() ->
        RentalItemFactProjection.create(rentalItemId, warehouseId, status, aggregateVersion));
    if (fact.getAggregateVersion() < aggregateVersion) {
      fact.apply(warehouseId, status, aggregateVersion);
    }
    rentalItemFacts.save(fact);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundLeaseFact(
      UUID leaseId,
      UUID rentalItemId,
      long fencingToken,
      String state,
      long aggregateVersion) {
    Optional<OperationLeaseFactProjection> current = leaseFacts.findById(leaseId);
    OperationLeaseFactProjection fact = current.orElseGet(() ->
        OperationLeaseFactProjection.create(
            leaseId, rentalItemId, fencingToken, state, aggregateVersion));
    boolean advanced = current.isEmpty()
        || fact.apply(rentalItemId, fencingToken, state, aggregateVersion);
    leaseFacts.save(fact);
    if (!advanced || !"EXPIRED".equals(state)) return;
    List<MaintenanceRepair> affected = repairs.findAllByLeaseId(leaseId);
    if (affected.isEmpty()) return;
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(
        affected.stream().map(value -> stream(value.getId())).toList());
    repairs.findAllByIdForUpdate(affected.stream().map(MaintenanceRepair::getId).toList())
        .forEach(repair -> {
          if (!rentalItemId.equals(repair.getRentalItemId())
              || !Long.valueOf(fencingToken).equals(repair.getFencingToken())) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_LEASE_CONFLICT", "Lease fact does not match local fencing truth");
          }
          boolean changed = repair.markReconciliationRequired();
          if (!"RECONCILIATION_REQUIRED".equals(repair.getLeaseReconciliationState())) {
            repair.markLeaseReconciliationRequired();
            changed = true;
          }
          if (!changed) return;
          MaintenanceRepair saved = repairs.saveAndFlush(repair);
          events.append(
              MaintenanceAggregateType.REPAIR,
              saved.getId(),
              versions.get(stream(saved.getId())),
              reconciliationEvent(saved, "RENEW_LEASE"),
              repairLocal(saved),
              repairFact(reconciliationEvent(saved, "RENEW_LEASE"), saved),
              repairSnapshot(saved));
        });
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundTaskOutcome(
      UUID eventId,
      String eventType,
      UUID externalTaskId,
      UUID queueEntryId,
      long queueEntryVersion,
      OffsetDateTime occurredAt) {
    MaintenanceRepair initial = repairs.findByExternalTaskId(externalTaskId).orElseThrow(() ->
        new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Task-board fact has no maintenance repair owner"));
    LockedTaskOutcome locked = lockTaskOutcome(initial);
    MaintenanceRepair repair = locked.repair();
    long expectedVersion = locked.streamVersions().get(stream(repair.getId()));
    RepairStage stage = repairStages.findByExternalQueueEntryIdForUpdate(queueEntryId)
        .orElseThrow(() -> new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Task-board route entry has no repair-stage mapping"));
    if (!repair.getId().equals(stage.getRepairId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Task-board route entry belongs to another repair");
    }
    MaintenanceEventType maintenanceEvent;
    if (eventType.endsWith("completed.v1")) {
      if (stage.getState() == RepairStageState.DONE) return;
      stage.completed(eventId, queueEntryVersion, occurredAt);
      repairStages.saveAndFlush(stage);
      boolean allDone = repairStages.countByRepairIdAndStateNotIn(
          repair.getId(), List.of(RepairStageState.DONE)) == 0;
      repair.applyStageCompletion(allDone);
      maintenanceEvent = allDone
          ? MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE
          : MaintenanceEventType.REPAIR_STAGE_COMPLETED;
      if (allDone) {
        if (repair.getKind() == RepairKind.REWORK) {
          returnSourceFromRework(locked);
        } else {
          repair.markLeaseReconciliationRequired();
          enqueueTerminalAsset(
              repair,
              "PENDING_ACCEPTANCE",
              stableOperationKey("pending-acceptance", repair.getId(), expectedVersion + 1));
        }
      }
    } else if (eventType.endsWith("cancelled.v1")) {
      if (!stage.cancelled(eventId, queueEntryVersion)) return;
      repairStages.saveAndFlush(stage);
      repair.applyExternalTaskCancellation();
      if (repair.getKind() == RepairKind.REWORK) {
        repair.markReconciled();
        returnSourceFromRework(locked);
      } else {
        requireRenewableLease(repair);
        repair.markReconciliationRequired();
        reconciliations.enqueueRequired(
            repair.getId(),
            "ASSET",
            "CANCELLED_PRIMARY_RECONCILIATION",
            stableOperationKey(
                "cancelled-primary-reconciliation", repair.getId(), expectedVersion + 1),
            Map.of(
                "repairId", repair.getId().toString(),
                "leaseId", repair.getLeaseId().toString(),
                "reason", "NO_APPROVED_REVERSE_STATUS"));
      }
      maintenanceEvent = MaintenanceEventType.REPAIR_PLAN_CHANGED;
    } else {
      throw new IllegalArgumentException("Unsupported actionable task event " + eventType);
    }
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        maintenanceEvent,
        repairLocal(saved),
        repairFact(maintenanceEvent, saved),
        repairSnapshot(saved));
  }

  private LockedTaskOutcome lockTaskOutcome(MaintenanceRepair initial) {
    if (initial.getKind() == RepairKind.REWORK) {
      LockedRework locked = lockAndReloadRework(initial, initial.getVersion());
      return new LockedTaskOutcome(
          locked.rework(), locked.source(), locked.streamVersions());
    }
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(
        List.of(stream(initial.getId())));
    long expectedVersion = versions.get(stream(initial.getId()));
    assertVersion(expectedVersion, initial.getVersion());
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(initial.getId())).stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    assertStreamParity(repair, versions);
    return new LockedTaskOutcome(repair, null, versions);
  }

  private void returnSourceFromRework(LockedTaskOutcome locked) {
    MaintenanceRepair source = Optional.ofNullable(locked.source()).orElseThrow(() ->
        new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Rework task outcome is missing its source repair"));
    source.returnFromRework();
    MaintenanceRepair saved = repairs.saveAndFlush(source);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        locked.streamVersions().get(stream(saved.getId())),
        MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE,
        repairLocal(saved),
        repairFact(MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE, saved),
        repairSnapshot(saved));
  }

  public boolean reconcileOneTask() {
    MaintenanceReconciliationStore.WorkItem[] attempted = new MaintenanceReconciliationStore.WorkItem[1];
    try {
      Boolean processed = transactions.execute(status -> {
        Optional<MaintenanceReconciliationStore.WorkItem> candidate = reconciliations.lockNextDue();
        if (candidate.isEmpty()) return enqueueDueLeaseRenewal();
        MaintenanceReconciliationStore.WorkItem work = candidate.get();
        attempted[0] = work;
        Object response = switch (work.operation()) {
          case "QUEUE_REPAIR" -> reconcileRepairQueue(work);
          case "COMPLETE_EMPTY_ESTIMATE" -> reconcileEmptyEstimate(work);
          case "REGISTER_TASK" -> reconcileTask(work, false);
          case "UPDATE_TASK" -> reconcileTask(work, true);
          case "ACCEPT_TO_FREE", "WRITE_OFF", "PENDING_ACCEPTANCE" ->
              reconcileAssetTransition(work);
          case "RENEW_LEASE" -> reconcileLeaseRenewal(work);
          default -> throw new IllegalStateException(
              "Unsupported maintenance reconciliation operation " + work.operation());
        };
        reconciliations.confirmed(work, response);
        return true;
      });
      return Boolean.TRUE.equals(processed);
    } catch (RuntimeException exception) {
      MaintenanceReconciliationStore.WorkItem work = attempted[0];
      if (work == null) throw exception;
      transactions.executeWithoutResult(status -> {
        boolean quarantined = reconciliations.failed(work, exception);
        recordReconciliationFailure(work, quarantined);
      });
      return true;
    }
  }

  private MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate, List<EstimatePlanStage> estimatePlan) {
    List<PlanStageInput> plan = estimatePlan.stream()
        .map(value -> new PlanStageInput(
            value.getId(), value.getStageKind(), value.getStageNo(),
            new RoutingSnapshot(value.getRoutingQueueId(), value.getRoutingQueueCode(),
                value.getRoutingQueueKind()),
            value.getTaskDeadline()))
        .toList();
    return createEstimateRepairFromPlan(estimate, plan);
  }

  private MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate, List<PlanStageInput> plan) {
    MaintenanceRepair repair = repairs.saveAndFlush(MaintenanceRepair.primary(
        estimate.getWarehouseId(), estimate.getRentalItemId(), estimate.getRentalItemVersionSnapshot(),
        estimate.getId(), RepairOrigin.ESTIMATE, estimate.getDispatchDate(), estimate.getSourceParty(), actorJson()));
    replaceRepairStages(repair, plan);
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        repairLocal(repair),
        repairFact(MaintenanceEventType.REPAIR_CREATED, repair),
        repairSnapshot(repair));
    return repair;
  }

  private void replaceEstimateRevision(
      MaintenanceEstimate estimate,
      List<EstimateLineInput> lineInputs,
      List<PlanStageInput> planInputs,
      String amendmentReason) {
    int revision = estimate.getRevision();
    if (estimate.getState() == EstimateState.DRAFT) {
      estimateLines.deleteAllByEstimateIdAndEstimateRevision(estimate.getId(), revision);
      estimatePlans.deleteAllByEstimateIdAndEstimateRevision(estimate.getId(), revision);
      estimateLines.flush();
      estimatePlans.flush();
    }
    List<EstimateLine> lines = new ArrayList<>();
    Set<UUID> lineIds = new HashSet<>();
    for (int index = 0; index < lineInputs.size(); index++) {
      EstimateLineInput input = lineInputs.get(index);
      if (!lineIds.add(input.id())) throw invalid("Estimate line IDs must be unique inside a revision");
      validateMediaReferences(
          "MAINTENANCE_ESTIMATE", estimate.getId(), estimate.getWarehouseId(), input.mediaReferences());
      lines.add(new EstimateLine(
          input.id(), estimate.getId(), revision, index,
          input.catalogSnapshot() == null ? null : input.catalogSnapshot().nodeId(),
          input.catalogSnapshot() != null && input.catalogSnapshot().nodeType() == CatalogNodeType.MATERIAL
              ? "MATERIAL" : "WORK",
          input.description(), new BigDecimal(input.quantity()), moneyToMinor(input.unitPrice()),
          input.catalogSnapshot() == null ? null : input.catalogSnapshot().durationMinutes(),
          input.catalogSnapshot() == null || input.catalogSnapshot().routing() == null
              ? null : input.catalogSnapshot().routing().queueId().toString(),
          input.catalogSnapshot() == null ? null : write(input.catalogSnapshot()),
          input.comment(), write(input.mediaReferences())));
    }
    List<EstimatePlanStage> plan = new ArrayList<>();
    for (int index = 0; index < planInputs.size(); index++) {
      PlanStageInput input = planInputs.get(index);
      plan.add(new EstimatePlanStage(
          input.id(), estimate.getId(), revision, input.order(), input.kind(),
          input.routing().queueId(), input.routing().queueCode(), input.routing().queueKind(),
          input.taskDeadline()));
    }
    estimateLines.saveAll(lines);
    estimatePlans.saveAll(plan);
    long totalMinor = lines.stream()
        .map(line -> line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
    EstimateRevision revisionHeader = estimateRevisions
        .findByEstimateIdAndRevision(estimate.getId(), revision)
        .orElse(null);
    if (revisionHeader == null) {
      estimateRevisions.saveAndFlush(new EstimateRevision(
          estimate.getId(), revision, estimate.getDispatchDate(), estimate.getSourceParty(),
          amendmentReason, totalMinor, actorJson()));
    } else {
      if (estimate.getState() != EstimateState.DRAFT) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Completed estimate revisions are immutable");
      }
      revisionHeader.replaceDraft(
          estimate.getDispatchDate(), estimate.getSourceParty(), totalMinor, actorJson());
      estimateRevisions.saveAndFlush(revisionHeader);
    }
  }

  private void replaceRepairStages(MaintenanceRepair repair, List<PlanStageInput> inputs) {
    validatePlan(inputs, false);
    repairStages.deleteAllByRepairId(repair.getId());
    repairStages.flush();
    List<RepairStage> stages = new ArrayList<>();
    for (int index = 0; index < inputs.size(); index++) {
      PlanStageInput input = inputs.get(index);
      RepairStage stage = new RepairStage(
          input.id(), repair.getId(), input.order(), input.kind(), input.routing().queueId(),
          input.routing().queueCode(), input.routing().queueKind(), input.taskDeadline());
      if (repair.getExecutionState() == RepairExecutionState.QUEUED) stage.queued();
      stages.add(stage);
    }
    repairStages.saveAllAndFlush(stages);
  }

  private void replaceMedia(
      String aggregateType,
      String ownerType,
      UUID aggregateId,
      UUID warehouseId,
      List<MediaReferenceInput> requested) {
    replaceMedia(
        aggregateType, aggregateId, ownerType, aggregateId, warehouseId, requested);
  }

  private void replaceMedia(
      String aggregateType,
      UUID storageAggregateId,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      List<MediaReferenceInput> requested) {
    mediaReferences.deleteAllByAggregateTypeAndAggregateId(aggregateType, storageAggregateId);
    mediaReferences.flush();
    if (requested == null || requested.isEmpty()) return;
    validateMediaReferences(ownerType, ownerId, warehouseId, requested);
    List<MaintenanceMediaReference> values = requested.stream().map(reference -> {
      MediaFactProjection fact = mediaFacts.findById(reference.mediaId()).orElseThrow();
      return new MaintenanceMediaReference(
          aggregateType, storageAggregateId, reference.mediaId(), reference.generation(), ownerType,
          warehouseId, fact.getSafeMetadata());
    }).toList();
    mediaReferences.saveAll(values);
  }

  private void validateMediaReferences(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      List<MediaReferenceInput> requested) {
    if (requested == null || requested.isEmpty()) return;
    Set<UUID> unique = new HashSet<>();
    for (MediaReferenceInput reference : requested) {
      if (reference == null || reference.mediaId() == null || reference.generation() == null) {
        throw invalid("Media reference identity is required");
      }
      if (!unique.add(reference.mediaId())) throw invalid("Duplicate media reference");
      MediaFactProjection fact = mediaFacts.findById(reference.mediaId()).orElseThrow(() ->
          new MaintenanceValidationException("MAINTENANCE_MEDIA_NOT_READY", "Media fact is not known"));
      if (fact.getGeneration() != reference.generation()
          || !"READY".equals(fact.getMediaStatus())
          || !ownerType.equals(fact.getOwnerType())
          || !ownerId.equals(fact.getOwnerId())
          || !warehouseId.equals(fact.getWarehouseId())) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_MEDIA_NOT_READY", "Media owner, generation, warehouse or status does not match");
      }
    }
  }

  private CatalogValidation validateCatalog(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {
    if (nodes == null || links == null) throw new IllegalArgumentException("Catalog arrays are required");
    Set<UUID> ids = new HashSet<>();
    Set<String> codes = new HashSet<>();
    Map<UUID, List<UUID>> parents = new HashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (!ids.add(node.id())) throw invalid("Duplicate catalog node ID");
      if (!codes.add(node.code().trim().toUpperCase(java.util.Locale.ROOT))) {
        throw invalid("Duplicate catalog node code");
      }
    }
    for (CatalogNodeInput node : nodes) {
      if (node.parentNodeId() == null) continue;
      if (node.id().equals(node.parentNodeId())) throw invalid("Catalog node cannot parent itself");
      if (!ids.contains(node.parentNodeId())) throw invalid("Catalog parent node is missing");
      parents.computeIfAbsent(node.id(), ignored -> new ArrayList<>()).add(node.parentNodeId());
    }
    if (containsCycle(ids, parents)) throw invalid("Catalog parent hierarchy contains a cycle");
    Set<UUID> linkIds = new HashSet<>();
    Set<String> typedEdges = new HashSet<>();
    Map<UUID, List<UUID>> dependency = new HashMap<>();
    for (CatalogLinkInput link : links) {
      if (!linkIds.add(link.id())) throw invalid("Duplicate catalog link ID");
      if (!ids.contains(link.fromNodeId()) || !ids.contains(link.toNodeId())) {
        throw invalid("Catalog link endpoint is missing");
      }
      if (link.fromNodeId().equals(link.toNodeId())) throw invalid("Catalog link cannot self-reference");
      String edge = link.fromNodeId() + ":" + link.toNodeId() + ":" + link.linkType().name();
      if (!typedEdges.add(edge)) throw invalid("Duplicate typed catalog edge");
      if (link.linkType() == CatalogLinkType.DEPENDENCY) {
        dependency.computeIfAbsent(link.fromNodeId(), ignored -> new ArrayList<>()).add(link.toNodeId());
      }
    }
    if (containsCycle(ids, dependency)) throw invalid("Catalog dependency graph contains a cycle");
    return new CatalogValidation(true);
  }

  private static boolean containsCycle(Set<UUID> ids, Map<UUID, List<UUID>> edges) {
    Set<UUID> visiting = new HashSet<>();
    Set<UUID> visited = new HashSet<>();
    for (UUID id : ids) if (visit(id, edges, visiting, visited)) return true;
    return false;
  }

  private static boolean visit(
      UUID id, Map<UUID, List<UUID>> edges, Set<UUID> visiting, Set<UUID> visited) {
    if (visited.contains(id)) return false;
    if (!visiting.add(id)) return true;
    for (UUID target : edges.getOrDefault(id, List.of())) {
      if (visit(target, edges, visiting, visited)) return true;
    }
    visiting.remove(id);
    visited.add(id);
    return false;
  }

  private void validateEstimatePlan(
      List<EstimateLineInput> lines, List<PlanStageInput> plan) {
    if (lines == null || plan == null) throw new IllegalArgumentException("Estimate lines and plan are required");
    validatePlan(plan, lines.isEmpty());
    if (lines.isEmpty() && !plan.isEmpty()) {
      throw invalid("An empty estimate cannot contain movement or repair stages");
    }
    if (!lines.isEmpty() && plan.isEmpty()) {
      throw invalid("A non-empty estimate requires a repair plan");
    }
    if (!lines.isEmpty() && plan.stream().noneMatch(value -> value.kind() == dev.buhanzaz.rwms.maintenance.domain.RepairStageKind.REPAIR_WORK)) {
      throw invalid("A non-empty estimate plan requires a REPAIR_WORK stage");
    }
  }

  private void validatePlan(List<PlanStageInput> plan, boolean allowEmpty) {
    if (plan == null || (!allowEmpty && plan.isEmpty())) {
      throw invalid("A repair plan requires at least one stage");
    }
    Set<UUID> ids = new HashSet<>();
    Set<Integer> orders = new HashSet<>();
    Set<OffsetDateTime> deadlines = new HashSet<>();
    for (int index = 0; index < plan.size(); index++) {
      PlanStageInput stage = plan.get(index);
      if (stage == null || stage.id() == null || stage.routing() == null) {
        throw invalid("Every plan stage requires identity and routing");
      }
      if (!ids.add(stage.id())) throw invalid("Plan stage IDs must be unique");
      if (!orders.add(stage.order())) throw invalid("Plan stage order must be unique");
      if (stage.order() != index) {
        throw invalid("Plan stage order must be contiguous and match submitted order");
      }
      if (stage.taskDeadline() != null) deadlines.add(stage.taskDeadline());
    }
    if (deadlines.size() > 1) {
      throw invalid("Repair plan stage deadlines must be absent or one identical timestamp");
    }
  }

  private void saveCatalog(
      UUID versionId,
      UUID warehouseId,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links) {
    List<CatalogNode> savedNodes = catalogNodes.saveAllAndFlush(nodes.stream().map(node -> new CatalogNode(
        node.id(), versionId, node.code(), node.nodeType().name(), node.name(),
        node.active(), node.parentNodeId(), node.unit(),
        node.unitPrice() == null ? null : moneyToMinor(node.unitPrice()), node.durationMinutes(),
        node.includeInEstimate(), node.commonItem(), node.showInMainMenu(), node.photoRequired(),
        node.routing() == null ? null : node.routing().queueId(),
        node.routing() == null ? null : node.routing().queueCode(),
        node.routing() == null ? null : node.routing().queueKind(),
        write(node.references()), node.comment(), write(node.mediaReferences()))).toList());
    Map<UUID, CatalogNodeInput> inputsById = nodes.stream().collect(
        java.util.stream.Collectors.toMap(CatalogNodeInput::id, value -> value));
    savedNodes.forEach(node -> {
      CatalogNodeInput input = inputsById.get(node.getId());
      replaceMedia(
          "CATALOG_NODE", node.getRowId(), "MAINTENANCE_CATALOG_NODE", node.getId(),
          warehouseId, input.mediaReferences());
    });
    catalogLinks.saveAllAndFlush(links.stream().map(link -> new CatalogLink(
        link.id(), versionId, link.fromNodeId(), link.toNodeId(), link.linkType().name(), link.sortOrder())).toList());
  }

  private void requireNoActiveRework(MaintenanceRepair repair) {
    if (repairs.existsBySourceRepairIdAndExecutionStateIn(
        repair.getId(), List.of(
            RepairExecutionState.DRAFT,
            RepairExecutionState.QUEUED,
            RepairExecutionState.IN_PROGRESS))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Active rework blocks a terminal acceptance decision");
    }
  }

  private void requireLease(MaintenanceRepair repair) {
    requireRenewableLease(repair);
    if (!leaseIsFresh(repair.getLeaseExpiresAt())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_LEASE_CONFLICT",
          "Repair does not have a confirmed lease with at least five minutes remaining");
    }
  }

  private void requireRenewableLease(MaintenanceRepair repair) {
    if (repair.getLeaseId() == null
        || repair.getLeaseVersion() == null
        || repair.getFencingToken() == null
        || repair.getLeaseExpiresAt() == null
        || !"ACTIVE".equals(repair.getLeaseReconciliationState())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_LEASE_CONFLICT", "Repair does not have an active renewable lease snapshot");
    }
  }

  private void cascadeTerminal(
      MaintenanceRepair repair, List<MaintenanceRepair> sourceChain, boolean accepted) {
    for (MaintenanceRepair source : sourceChain) {
      UUID sourceId = source.getId();
      long expectedVersion = source.getVersion();
      if (accepted) {
        source.accept(repair.getDecisionReason(), repair.getDecisionActorRef());
      } else {
        source.writeOff(repair.getDecisionReason(), repair.getDecisionActorRef());
      }
      source.markLeaseReconciliationRequired();
      MaintenanceRepair saved = repairs.saveAndFlush(source);
      events.append(
          MaintenanceAggregateType.REPAIR,
          sourceId,
          expectedVersion,
          accepted ? MaintenanceEventType.REPAIR_ACCEPTED : MaintenanceEventType.REPAIR_WRITTEN_OFF,
          repairLocal(saved),
          repairFact(
              accepted ? MaintenanceEventType.REPAIR_ACCEPTED
                  : MaintenanceEventType.REPAIR_WRITTEN_OFF,
              saved),
          repairSnapshot(saved));
    }
  }

  private List<MaintenanceRepair> sourceChain(MaintenanceRepair repair) {
    List<MaintenanceRepair> result = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    UUID sourceId = repair.getSourceRepairId();
    while (sourceId != null) {
      if (!visited.add(sourceId)) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Repair source chain contains a cycle");
      }
      MaintenanceRepair source = requireRepair(sourceId);
      result.add(source);
      sourceId = source.getSourceRepairId();
    }
    return List.copyOf(result);
  }

  private LockedRepairChain lockAndReloadRepairChain(
      MaintenanceRepair initial, long expectedVersion) {
    List<MaintenanceRepair> initialSources = sourceChain(initial);
    List<UUID> ids = new ArrayList<>();
    ids.add(initial.getId());
    initialSources.forEach(source -> ids.add(source.getId()));
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        ids.stream().map(MaintenanceApplicationService::stream).toList());
    assertVersion(locked.get(stream(initial.getId())), expectedVersion);
    Map<UUID, MaintenanceRepair> current = repairs.findAllByIdForUpdate(ids).stream()
        .collect(java.util.stream.Collectors.toMap(MaintenanceRepair::getId, value -> value));
    MaintenanceRepair repair = Optional.ofNullable(current.get(initial.getId()))
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    assertVersion(repair.getVersion(), expectedVersion);
    assertStreamParity(repair, locked);
    List<MaintenanceRepair> sources = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    UUID sourceId = repair.getSourceRepairId();
    while (sourceId != null) {
      if (!visited.add(sourceId)) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Repair source chain contains a cycle");
      }
      MaintenanceRepair source = Optional.ofNullable(current.get(sourceId))
          .orElseThrow(() -> new MaintenanceConflictException(
              "MAINTENANCE_STATE_CONFLICT", "Repair source chain changed while locking"));
      assertStreamParity(source, locked);
      validateReworkOwnership(repair, source);
      sources.add(source);
      sourceId = source.getSourceRepairId();
    }
    if (sources.size() != initialSources.size()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair source chain changed while locking");
    }
    return new LockedRepairChain(repair, List.copyOf(sources), locked);
  }

  private LockedRework lockAndReloadRework(MaintenanceRepair initial, long expectedVersion) {
    if (initial.getKind() != RepairKind.REWORK
        || initial.getRootRepairId() == null
        || initial.getSourceRepairId() == null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair does not have a valid rework hierarchy");
    }
    List<UUID> ids = List.of(
        initial.getId(), initial.getRootRepairId(), initial.getSourceRepairId());
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        ids.stream().map(MaintenanceApplicationService::stream).toList());
    assertVersion(locked.get(stream(initial.getId())), expectedVersion);
    Map<UUID, MaintenanceRepair> current = repairs.findAllByIdForUpdate(ids).stream()
        .collect(java.util.stream.Collectors.toMap(MaintenanceRepair::getId, value -> value));
    MaintenanceRepair repair = Optional.ofNullable(current.get(initial.getId()))
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    MaintenanceRepair root = Optional.ofNullable(current.get(repair.getRootRepairId()))
        .orElseThrow(() -> new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Rework root repair is missing"));
    MaintenanceRepair source = Optional.ofNullable(current.get(repair.getSourceRepairId()))
        .orElseThrow(() -> new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Rework source repair is missing"));
    assertVersion(repair.getVersion(), expectedVersion);
    assertStreamParity(repair, locked);
    assertStreamParity(root, locked);
    assertStreamParity(source, locked);
    validateReworkOwnership(repair, root);
    validateReworkOwnership(repair, source);
    return new LockedRework(repair, root, source, locked);
  }

  private static void validateReworkOwnership(
      MaintenanceRepair rework, MaintenanceRepair related) {
    if (!rework.getWarehouseId().equals(related.getWarehouseId())
        || !rework.getRentalItemId().equals(related.getRentalItemId())
        || rework.getRentalItemVersionSnapshot() != related.getRentalItemVersionSnapshot()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Rework hierarchy crosses warehouse, rental item or initial asset version");
    }
    UUID expectedRoot = rework.getRootRepairId() == null ? rework.getId() : rework.getRootRepairId();
    UUID relatedRoot = related.getRootRepairId() == null ? related.getId() : related.getRootRepairId();
    if (!expectedRoot.equals(relatedRoot)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Rework hierarchy does not share one root owner");
    }
  }

  private static void assertStreamParity(
      MaintenanceRepair repair, Map<MaintenanceEventStore.StreamRef, Long> versions) {
    Long streamVersion = versions.get(stream(repair.getId()));
    if (streamVersion == null || streamVersion != repair.getVersion()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Repair projection and stream versions diverged");
    }
  }

  private static MaintenanceEventStore.StreamRef stream(UUID repairId) {
    return new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.REPAIR, repairId);
  }

  private void enqueueRepairQueue(
      MaintenanceRepair repair, UUID key, boolean linkedReturn) {
    reconciliations.enqueue(
        repair.getId(),
        "ASSET",
        "QUEUE_REPAIR",
        key,
        Map.of(
            "repairId", repair.getId().toString(),
            "linkedReturn", linkedReturn));
  }

  private void enqueueTaskRegistration(MaintenanceRepair repair, UUID key) {
    reconciliations.enqueue(
        repair.getId(),
        "TASK_BOARD",
        "REGISTER_TASK",
        key,
        Map.of("repairId", repair.getId().toString()));
  }

  private void enqueueTerminalAsset(
      MaintenanceRepair repair, String transition, UUID key) {
    reconciliations.enqueue(
        repair.getId(),
        "ASSET",
        transition,
        key,
        Map.of(
            "repairId", repair.getId().toString(),
            "transition", transition));
  }

  private void confirmTaskRegistration(
      UUID repairId, MaintenanceDependencyGateway.TaskSnapshot task) {
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repairId);
    if (task.stages().size() != stages.size()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board route truth does not match the maintenance plan");
    }
    Map<Integer, MaintenanceDependencyGateway.TaskStageSnapshot> byRoute = new HashMap<>();
    task.stages().forEach(stage -> {
      if (byRoute.put(stage.routeIndex(), stage) != null) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board returned a duplicate routeIndex");
      }
    });
    for (RepairStage stage : stages) {
      MaintenanceDependencyGateway.TaskStageSnapshot external = byRoute.get(stage.getStageNo());
      if (external == null) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board routeIndex is missing");
      }
      stage.confirmTaskBoardRegistration(
          external.taskBoardEntryId(), external.entryVersion());
    }
    repairStages.saveAllAndFlush(stages);
  }

  private Object reconcileRepairQueue(MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = requireWorkRepair(work);
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    assertVersion(repair.getVersion(), expectedVersion);
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      enqueueTaskRegistration(
          repair, stableOperationKey("register-task", repair.getExternalTaskId(), 0));
      return Map.of("repairId", repair.getId().toString(), "alreadyQueued", true);
    }
    if (repair.getExecutionState() != RepairExecutionState.DRAFT || repair.getKind() == RepairKind.REWORK) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair is no longer eligible for primary queue reconciliation");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    if (stages.isEmpty()) throw invalid("Repair needs at least one planned stage before queueing");
    String ownerType = ownerType(repair);
    String ownerId = ownerId(repair);
    MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.acquireLease(
        derived(work.idempotencyKey(), "acquire"), repair.getRentalItemId(),
        repair.getRentalItemVersionSnapshot(), ownerType, ownerId);
    validateLeaseTruth(repair, lease, ownerType, ownerId);
    if (!leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          derived(work.idempotencyKey(), "renew"), lease.leaseId(), lease.version(),
          lease.fencingToken(), ownerType, ownerId);
      validateLeaseTruth(repair, lease, ownerType, ownerId);
    }
    requireFreshDependencyLease(lease);
    MaintenanceDependencyGateway.AssetSnapshot asset = dependencies.fencedStatus(
        derived(work.idempotencyKey(), "status"), repair.getRentalItemId(), repair.getWarehouseId(),
        repair.getRentalItemVersionSnapshot(), lease.leaseId(), lease.fencingToken(), ownerType,
        ownerId, "QUEUE_TO_REPAIR", work.payload().path("linkedReturn").asBoolean(false));
    validateAssetTruth(repair, asset);
    stages.forEach(RepairStage::queued);
    repairStages.saveAllAndFlush(stages);
    repair.confirmRentalItemVersion(asset.version());
    repair.queue(lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    enqueueTaskRegistration(saved, stableOperationKey("register-task", saved.getExternalTaskId(), 0));
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_QUEUED,
        repairLocal(saved),
        repairFact(MaintenanceEventType.REPAIR_QUEUED, saved),
        repairSnapshot(saved));
    return Map.of(
        "repairId", saved.getId().toString(),
        "leaseId", lease.leaseId().toString(),
        "rentalItemVersion", asset.version());
  }

  private Object reconcileEmptyEstimate(MaintenanceReconciliationStore.WorkItem work) {
    UUID estimateId = uuidField(work.payload(), "estimateId");
    MaintenanceEstimate estimate = requireEstimate(estimateId);
    if (estimate.getState() != EstimateState.COMPLETED || estimate.getRepairId() != null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Estimate is no longer an empty completed estimate");
    }
    String ownerType = "MAINTENANCE_ESTIMATE";
    String ownerId = estimate.getId().toString();
    MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.acquireLease(
        derived(work.idempotencyKey(), "acquire"), estimate.getRentalItemId(),
        estimate.getRentalItemVersionSnapshot(), ownerType, ownerId);
    validateLeaseTruth(estimate, lease, ownerType, ownerId);
    if (!leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          derived(work.idempotencyKey(), "renew"), lease.leaseId(), lease.version(),
          lease.fencingToken(), ownerType, ownerId);
      validateLeaseTruth(estimate, lease, ownerType, ownerId);
    }
    requireFreshDependencyLease(lease);
    MaintenanceDependencyGateway.AssetSnapshot asset = dependencies.fencedStatus(
        derived(work.idempotencyKey(), "status"), estimate.getRentalItemId(), estimate.getWarehouseId(),
        estimate.getRentalItemVersionSnapshot(), lease.leaseId(), lease.fencingToken(), ownerType,
        ownerId, "EMPTY_ESTIMATE_TO_FREE", false);
    validateAssetTruth(estimate, asset);
    dependencies.releaseLease(
        derived(work.idempotencyKey(), "release"), lease.leaseId(), lease.version(),
        lease.fencingToken(), ownerType, ownerId);
    return Map.of(
        "estimateId", estimate.getId().toString(),
        "rentalItemVersion", asset.version(),
        "leaseReleased", true);
  }

  private Object reconcileTask(
      MaintenanceReconciliationStore.WorkItem work, boolean update) {
    MaintenanceRepair repair = requireWorkRepair(work);
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    assertVersion(repair.getVersion(), expectedVersion);
    if (repair.getExecutionState() != RepairExecutionState.QUEUED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Only a queued repair can reconcile its task");
    }
    List<MaintenanceDependencyGateway.TaskStage> stages = taskStages(repair.getId());
    MaintenanceDependencyGateway.TaskSnapshot task;
    if (update) {
      if (repair.getTaskBoardVersion() == null) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Task update has no confirmed task-board version");
      }
      task = dependencies.updatePreStartTask(
          work.idempotencyKey(), repair.getExternalTaskId(), repair.getTaskBoardVersion(), stages);
    } else {
      task = dependencies.registerTask(
          work.idempotencyKey(), repair.getExternalTaskId(), repair.getWarehouseId(),
          repair.getRentalItemId(), stages);
    }
    validateTaskTruth(repair, task, stages.size());
    confirmTaskRegistration(repair.getId(), task);
    repair.markTaskGenerated(task.version());
    repair.markReconciled();
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        update ? MaintenanceEventType.REPAIR_PLAN_CHANGED : MaintenanceEventType.REPAIR_QUEUED,
        repairLocal(saved),
        repairFact(
            update ? MaintenanceEventType.REPAIR_PLAN_CHANGED
                : MaintenanceEventType.REPAIR_QUEUED,
            saved),
        repairSnapshot(saved));
    return Map.of(
        "repairId", saved.getId().toString(),
        "externalTaskId", task.externalTaskId().toString(),
        "taskBoardVersion", task.version());
  }

  private Object reconcileAssetTransition(MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair initial = requireWorkRepair(work);
    LockedRepairChain locked = lockAndReloadRepairChain(initial, initial.getVersion());
    MaintenanceRepair repair = locked.repair();
    List<MaintenanceRepair> sources = locked.sources();
    String ownerType = ownerType(repair);
    String ownerId = ownerId(repair);
    MaintenanceDependencyGateway.LeaseSnapshot lease = localLease(repair, sources, ownerType, ownerId)
        .orElseGet(() -> dependencies.acquireLease(
            derived(work.idempotencyKey(), "acquire"), repair.getRentalItemId(),
            repair.getRentalItemVersionSnapshot(), ownerType, ownerId));
    validateLeaseTruth(repair, lease, ownerType, ownerId);
    if (!leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          derived(work.idempotencyKey(), "renew"), lease.leaseId(), lease.version(),
          lease.fencingToken(), ownerType, ownerId);
      validateLeaseTruth(repair, lease, ownerType, ownerId);
    }
    requireFreshDependencyLease(lease);
    String transition = work.operation();
    MaintenanceDependencyGateway.AssetSnapshot asset = dependencies.fencedStatus(
        derived(work.idempotencyKey(), "status"), repair.getRentalItemId(), repair.getWarehouseId(),
        repair.getRentalItemVersionSnapshot(), lease.leaseId(), lease.fencingToken(), ownerType,
        ownerId, transition, false);
    validateAssetTruth(repair, asset);
    boolean terminal = !"PENDING_ACCEPTANCE".equals(transition);
    if (terminal) {
      dependencies.releaseLease(
          derived(work.idempotencyKey(), "release"), lease.leaseId(), lease.version(),
          lease.fencingToken(), ownerType, ownerId);
    }
    reconcileLeaseProjection(
        repair, locked.streamVersions().get(stream(repair.getId())), asset.version(), lease,
        terminal, transitionEvent(repair, transition));
    for (MaintenanceRepair source : sources) {
      reconcileLeaseProjection(
          source, locked.streamVersions().get(stream(source.getId())), asset.version(), lease,
          terminal, transitionEvent(source, transition));
    }
    return Map.of(
        "repairId", repair.getId().toString(),
        "rentalItemVersion", asset.version(),
        "leaseReleased", terminal);
  }

  private Object reconcileLeaseRenewal(MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = requireWorkRepair(work);
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    assertVersion(repair.getVersion(), expectedVersion);
    requireRenewableLease(repair);
    String ownerType = ownerType(repair);
    String ownerId = ownerId(repair);
    MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.renewLease(
        work.idempotencyKey(), repair.getLeaseId(), repair.getLeaseVersion(),
        repair.getFencingToken(), ownerType, ownerId);
    validateLeaseTruth(repair, lease, ownerType, ownerId);
    requireFreshDependencyLease(lease);
    repair.renewLease(lease.version(), lease.expiresAt());
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    MaintenanceEventType renewalEvent = saved.getExecutionState() == RepairExecutionState.CANCELLED
        ? MaintenanceEventType.REPAIR_PLAN_CHANGED
        : MaintenanceEventType.REPAIR_QUEUED;
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        renewalEvent,
        repairLocal(saved),
        repairFact(renewalEvent, saved),
        repairSnapshot(saved));
    return Map.of(
        "repairId", saved.getId().toString(),
        "leaseVersion", lease.version(),
        "expiresAt", lease.expiresAt().toString());
  }

  private boolean enqueueDueLeaseRenewal() {
    Optional<MaintenanceRepair> candidate =
        repairs
            .findLeaseRenewalCandidateForUpdateSkipLocked(
                OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(LEASE_RENEWAL_GUARD),
                org.springframework.data.domain.PageRequest.of(0, 1))
            .stream()
            .findFirst();
    if (candidate.isEmpty()) return false;
    MaintenanceRepair repair = candidate.get();
    reconciliations.enqueue(
        repair.getId(),
        "ASSET",
        "RENEW_LEASE",
        stableOperationKey("renew-lease", repair.getLeaseId(), repair.getLeaseVersion()),
        Map.of("repairId", repair.getId().toString()));
    return true;
  }

  private void recordReconciliationFailure(
      MaintenanceReconciliationStore.WorkItem work, boolean quarantined) {
    if (work.repairId() == null) return;
    MaintenanceRepair repair = requireRepair(work.repairId());
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    assertVersion(repair.getVersion(), expectedVersion);
    boolean changed = quarantined
        ? repair.markReconciliationQuarantined()
        : repair.markReconciliationRequired();
    if (work.dependency().equals("TASK_BOARD")) {
      List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
      stages.forEach(stage -> stage.markTaskDeliveryFailed(quarantined));
      repairStages.saveAllAndFlush(stages);
      changed = true;
    }
    if (!changed) return;
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        reconciliationEvent(saved, work.operation()),
        repairLocal(saved),
        repairFact(reconciliationEvent(saved, work.operation()), saved),
        repairSnapshot(saved));
  }

  private MaintenanceRepair requireWorkRepair(
      MaintenanceReconciliationStore.WorkItem work) {
    if (work.repairId() == null) {
      throw new IllegalStateException("Repair reconciliation is missing repairId");
    }
    MaintenanceRepair repair = requireRepair(work.repairId());
    JsonNode payloadId = work.payload().get("repairId");
    if (payloadId == null || !payloadId.isTextual()
        || !repair.getId().toString().equals(payloadId.stringValue())) {
      throw new IllegalStateException("Reconciliation payload does not match repairId");
    }
    return repair;
  }

  private List<MaintenanceDependencyGateway.TaskStage> taskStages(UUID repairId) {
    return repairStages.findAllByRepairIdOrderByStageNo(repairId).stream()
        .map(value -> new MaintenanceDependencyGateway.TaskStage(
            value.getId(), value.getStageNo(), value.getStageKind(),
            value.getRoutingQueueCode(), value.getRoutingQueueId().toString(),
            value.getTaskDeadline()))
        .toList();
  }

  private Optional<MaintenanceDependencyGateway.LeaseSnapshot> localLease(
      MaintenanceRepair repair,
      List<MaintenanceRepair> sources,
      String ownerType,
      String ownerId) {
    List<MaintenanceRepair> candidates = new ArrayList<>();
    candidates.add(repair);
    candidates.addAll(sources);
    List<MaintenanceRepair> withLease = candidates.stream()
        .filter(value -> value.getLeaseId() != null)
        .toList();
    if (withLease.isEmpty()) return Optional.empty();
    MaintenanceRepair selected = withLease.getFirst();
    for (MaintenanceRepair candidate : withLease) {
      if (!selected.getLeaseId().equals(candidate.getLeaseId())
          || !selected.getFencingToken().equals(candidate.getFencingToken())) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_LEASE_CONFLICT", "Repair chain contains divergent lease snapshots");
      }
    }
    return Optional.of(new MaintenanceDependencyGateway.LeaseSnapshot(
        selected.getLeaseId(), selected.getLeaseVersion(), selected.getRentalItemId(), ownerType,
        UUID.fromString(ownerId), selected.getFencingToken(), selected.getLeaseExpiresAt()));
  }

  private void reconcileLeaseProjection(
      MaintenanceRepair repair,
      long expectedVersion,
      long assetVersion,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      boolean released,
      MaintenanceEventType eventType) {
    repair.confirmRentalItemVersion(assetVersion);
    if (repair.getLeaseId() != null) repair.renewLease(lease.version(), lease.expiresAt());
    if (released) {
      repair.releaseLease();
    } else {
      repair.markReconciled();
    }
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        eventType,
        repairLocal(saved),
        repairFact(eventType, saved),
        repairSnapshot(saved));
  }

  private static MaintenanceEventType transitionEvent(
      MaintenanceRepair repair, String transition) {
    return switch (transition) {
      case "PENDING_ACCEPTANCE" -> MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE;
      case "ACCEPT_TO_FREE" -> MaintenanceEventType.REPAIR_ACCEPTED;
      case "WRITE_OFF" -> MaintenanceEventType.REPAIR_WRITTEN_OFF;
      default -> throw new IllegalArgumentException("Unsupported asset transition " + transition);
    };
  }

  private static MaintenanceEventType reconciliationEvent(
      MaintenanceRepair repair, String operation) {
    if ("UPDATE_TASK".equals(operation)) return MaintenanceEventType.REPAIR_PLAN_CHANGED;
    if ("ACCEPT_TO_FREE".equals(operation)
        || repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED) {
      return MaintenanceEventType.REPAIR_ACCEPTED;
    }
    if ("WRITE_OFF".equals(operation)
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      return MaintenanceEventType.REPAIR_WRITTEN_OFF;
    }
    if ("PENDING_ACCEPTANCE".equals(operation)) {
      return MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE;
    }
    return repair.getExecutionState() == RepairExecutionState.DRAFT
        || repair.getExecutionState() == RepairExecutionState.CANCELLED
        ? MaintenanceEventType.REPAIR_PLAN_CHANGED
        : MaintenanceEventType.REPAIR_QUEUED;
  }

  private static void validateLeaseTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    validateLeaseTruth(
        repair.getRentalItemId(), lease, ownerType, ownerId);
  }

  private static void validateLeaseTruth(
      MaintenanceEstimate estimate,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    validateLeaseTruth(
        estimate.getRentalItemId(), lease, ownerType, ownerId);
  }

  private static void validateLeaseTruth(
      UUID rentalItemId,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    if (lease == null || lease.leaseId() == null
        || !rentalItemId.equals(lease.rentalItemId())
        || !ownerType.equals(lease.ownerType())
        || !UUID.fromString(ownerId).equals(lease.ownerId())
        || lease.version() < 0 || lease.fencingToken() < 1 || lease.expiresAt() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease truth does not match the maintenance owner");
    }
  }

  private static void validateAssetTruth(
      MaintenanceRepair repair, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null || !repair.getRentalItemId().equals(asset.rentalItemId())
        || !repair.getWarehouseId().equals(asset.warehouseId())
        || asset.version() <= repair.getRentalItemVersionSnapshot()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not advance the expected rental item");
    }
  }

  private static void validateAssetTruth(
      MaintenanceEstimate estimate, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null || !estimate.getRentalItemId().equals(asset.rentalItemId())
        || !estimate.getWarehouseId().equals(asset.warehouseId())
        || asset.version() <= estimate.getRentalItemVersionSnapshot()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not advance the expected rental item");
    }
  }

  private static void validateTaskTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.TaskSnapshot task,
      int expectedStages) {
    if (task == null || !repair.getExternalTaskId().equals(task.externalTaskId())
        || task.version() < 0 || !"ACTIVE".equals(task.state())
        || task.stages() == null || task.stages().size() != expectedStages) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board truth does not match the maintenance repair");
    }
  }

  private static UUID uuidField(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException("Reconciliation payload is missing " + field);
    }
    try {
      return UUID.fromString(value.stringValue());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Reconciliation payload has invalid " + field, exception);
    }
  }

  private List<EstimateLine> currentLines(MaintenanceEstimate estimate) {
    return estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(
        estimate.getId(), estimate.getRevision());
  }

  private List<EstimatePlanStage> currentPlan(MaintenanceEstimate estimate) {
    return estimatePlans.findAllByEstimateIdAndEstimateRevisionOrderByStageNo(
        estimate.getId(), estimate.getRevision());
  }

  private CatalogVersion requireCatalog(UUID id) {
    return catalogVersions.findById(id).orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"));
  }

  private MaintenanceEstimate requireEstimate(UUID id) {
    return estimates.findById(id).orElseThrow(() -> new MaintenanceNotFoundException("Estimate not found"));
  }

  private MaintenanceRepair requireRepair(UUID id) {
    return repairs.findById(id).orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
  }

  private RentalItemFactProjection requireRentalItemFact(
      UUID rentalItemId, UUID warehouseId) {
    RentalItemFactProjection fact = rentalItemFacts.findById(rentalItemId).orElseThrow(() ->
        new MaintenanceValidationException(
            "MAINTENANCE_DEPENDENCY_UNAVAILABLE",
            "Current rental-item ownership/version fact is not available"));
    if (!warehouseId.equals(fact.getWarehouseId())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Rental item does not belong to the command warehouse");
    }
    return fact;
  }

  private static void assertVersion(long actual, long expected) {
    if (actual != expected) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Maintenance aggregate version conflict");
    }
  }

  private CatalogVersionResponse catalogResponse(CatalogVersion value) {
    Map<String, Object> validation = jsonMap(value.getValidationReport());
    return new CatalogVersionResponse(
        value.getId(), value.getWarehouseId(), value.getVersion(), value.getState(),
        value.getSourceSha256(), new CatalogCounts(value.getNodeCount(), value.getLinkCount()),
        new CatalogValidationReport(
            booleanValue(validation, "valid"), intValue(validation, "errorCount"),
            intValue(validation, "warningCount"), stringValue(validation, "reportSha256")),
        value.getCreatedAt(), value.getActivatedAt());
  }

  private CatalogNodeResponse catalogNodeResponse(CatalogNode value) {
    return new CatalogNodeResponse(
        value.getId(), value.getCatalogVersionId(), value.getCode(),
        CatalogNodeType.valueOf(value.getNodeType()), value.getName(),
        value.isActive(), value.getParentNodeId(), value.getUnit(), money(value.getPriceMinor()),
        value.getDurationMinutes(), value.isIncludeInEstimate(), value.isCommonItem(),
        value.isShowInMainMenu(), value.isPhotoRequired(),
        value.getRoutingQueueId() == null ? null : new RoutingSnapshot(
            value.getRoutingQueueId(), value.getRoutingQueueCode(), value.getRoutingQueueKind()),
        readList(value.getOpaqueReferences(), OpaqueCatalogReference.class), value.getComment(),
        readList(value.getMediaReferences(), MediaReferenceInput.class));
  }

  private CatalogLinkResponse catalogLinkResponse(CatalogLink value) {
    return new CatalogLinkResponse(
        value.getId(), value.getCatalogVersionId(), value.getSourceNodeId(), value.getTargetNodeId(),
        CatalogLinkType.valueOf(value.getLinkType()), value.getSortOrder());
  }

  private EstimateResponse estimateResponse(MaintenanceEstimate value) {
    List<EstimateRevisionResponse> revisions = estimateRevisions
        .findAllByEstimateIdOrderByRevision(value.getId()).stream()
        .map(revision -> new EstimateRevisionResponse(
            revision.getRevision(), revision.getDispatchDate(), revision.getSourceParty(),
            lineResponses(value.getId(), revision.getRevision()),
            planResponses(value.getId(), revision.getRevision()), money(revision.getTotalMinor()),
            revision.getAmendmentReason(), revision.getRecordedAt()))
        .toList();
    return new EstimateResponse(
        value.getId(), value.getWarehouseId(), value.getRentalItemId(), value.getVersion(),
        value.getState(), value.getRevision(), revisions, value.getRepairId(),
        media("ESTIMATE", value.getId()), value.getCreatedAt(), value.getCompletedAt(),
        actor(value.getActorRef()));
  }

  private List<EstimateLineResponse> lineResponses(UUID estimateId, int revision) {
    return estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(estimateId, revision).stream()
        .map(line -> new EstimateLineResponse(
            line.getId(), line.getCatalogSnapshot() == null ? null
                : read(line.getCatalogSnapshot(), CatalogNodeSnapshot.class),
            line.getTitle(), quantity(line.getQuantity()), money(line.getUnitPriceMinor()),
            money(line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor()))),
            line.getComment(), readList(line.getMediaReferences(), MediaReferenceInput.class)))
        .toList();
  }

  private List<PlanStageInput> planResponses(UUID estimateId, int revision) {
    return estimatePlans.findAllByEstimateIdAndEstimateRevisionOrderByStageNo(estimateId, revision).stream()
        .map(stage -> new PlanStageInput(
            stage.getId(), stage.getStageKind(), stage.getStageNo(),
            new RoutingSnapshot(stage.getRoutingQueueId(), stage.getRoutingQueueCode(),
                stage.getRoutingQueueKind()),
            stage.getTaskDeadline()))
        .toList();
  }

  private RepairResponse repairResponse(MaintenanceRepair value) {
    List<RepairStageResponse> stages = repairStages.findAllByRepairIdOrderByStageNo(value.getId()).stream()
        .map(stage -> new RepairStageResponse(
            stage.getId(), stage.getStageKind(), stage.getStageNo(), stage.getState(),
            new RoutingSnapshot(stage.getRoutingQueueId(), stage.getRoutingQueueCode(),
                stage.getRoutingQueueKind()),
            stage.getTaskDeadline(), new TaskSyncSnapshot(
                value.getExternalTaskId(), stage.getTaskBoardVersion(),
                GenerationState.valueOf(stage.getTaskGenerationState()),
                new DeliverySnapshot(
                    DeliveryState.valueOf(stage.getDeliveryState()),
                    stage.getDeliveryAttempts(), stage.getDeliveryUpdatedAt())),
            stage.getCompletedAt()))
        .toList();
    InventorySourceReference inventorySource = inventorySources.findByRepairId(value.getId())
        .map(source -> new InventorySourceReference(
            source.getInventoryId(), source.getFindingId(), source.getSourceRevision(),
            source.getPlanFingerprint(), source.getSourceFingerprint()))
        .orElse(null);
    return new RepairResponse(
        value.getId(), rootId(value), value.getSourceRepairId(), value.getEstimateId(),
        value.getWarehouseId(), value.getRentalItemId(), value.getOrigin(), value.getKind(),
        value.getExecutionState(), value.getAcceptanceState(), value.getVersion(), value.getDispatchDate(),
        value.getSourceParty(), new RepairPlanResponse(value.getId(), value.getVersion(), stages),
        inventorySource,
        value.getLeaseId() == null ? null : new LeaseSnapshot(
            value.getLeaseId(), value.getFencingToken(), value.getLeaseExpiresAt(),
            leaseReconciliationState(value.getLeaseReconciliationState())),
        media("REPAIR", value.getId()), value.getCreatedAt(), value.getUpdatedAt(),
        actor(value.getActorRef()));
  }

  private List<MediaReferenceInput> media(String type, UUID id) {
    return mediaReferences.findAllByAggregateTypeAndAggregateIdOrderByMediaId(type, id).stream()
        .map(value -> new MediaReferenceInput(value.getMediaId(), value.getGeneration()))
        .toList();
  }

  private Map<String, Object> catalogLocal(CatalogVersion value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("catalogVersionId", value.getId().toString());
    result.put("validationReport", jsonMap(value.getValidationReport()));
    return result;
  }

  private Map<String, Object> catalogFact(
      MaintenanceEventType eventType, CatalogVersion value) {
    return eventFacts.catalogPayload(eventType, value);
  }

  private Map<String, Object> catalogSnapshot(CatalogVersion value) {
    return projectionSnapshots.catalog(value);
  }

  private Map<String, Object> estimateLocal(MaintenanceEstimate value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("estimateId", value.getId().toString());
    if (value.getSourceParty() != null) result.put("sourceParty", value.getSourceParty());
    if (value.getComment() != null) result.put("comment", value.getComment());
    result.put("lines", currentLines(value).stream().map(line -> Map.of(
        "lineNo", line.getLineNo(), "type", line.getLineType(), "title", line.getTitle(),
        "quantity", line.getQuantity().toPlainString(), "unitPriceMinor", line.getUnitPriceMinor())).toList());
    return result;
  }

  private Map<String, Object> estimateFact(
      MaintenanceEventType eventType, MaintenanceEstimate value) {
    return eventFacts.estimatePayload(eventType, value, currentLines(value).size());
  }

  private Map<String, Object> estimateSnapshot(MaintenanceEstimate value) {
    return projectionSnapshots.estimate(value);
  }

  private Map<String, Object> repairLocal(MaintenanceRepair value) {
    return new LinkedHashMap<>(repairSnapshot(value));
  }

  private Map<String, Object> repairFact(
      MaintenanceEventType eventType, MaintenanceRepair value) {
    return eventFacts.repairPayload(
        eventType, value, repairStages.findAllByRepairIdOrderByStageNo(value.getId()));
  }

  private Map<String, Object> repairSnapshot(MaintenanceRepair value) {
    return projectionSnapshots.repair(value);
  }

  private String ownerType(MaintenanceRepair repair) {
    MaintenanceRepair owner = repair.getRootRepairId() == null
        ? repair : requireRepair(repair.getRootRepairId());
    return owner.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE";
  }

  private String ownerId(MaintenanceRepair repair) {
    MaintenanceRepair owner = repair.getRootRepairId() == null
        ? repair : requireRepair(repair.getRootRepairId());
    if (owner.getEstimateId() != null) return owner.getEstimateId().toString();
    return owner.getId().toString();
  }

  private String hash(Object value) {
    try {
      String serialized = mapper.writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, serialized);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize maintenance request JSON");
      }
      return MaintenanceChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Maintenance request cannot be hashed", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Maintenance value cannot be serialized", exception);
    }
  }

  private String actorJson() {
    var actor = actorReferences.current();
    if (actor != null) return write(actor);
    return write(Map.of(
        "subjectId", "00000000-0000-0000-0000-0000000000d6",
        "principalType", "SYSTEM",
        "profileRevision", "00000000-0000-0000-0000-0000000000d6"));
  }

  private DeliverySnapshot delivery(MaintenanceRepair value) {
    return new DeliverySnapshot(
        DeliveryState.valueOf(value.getDeliveryState()),
        value.getDeliveryAttempts(), value.getDeliveryUpdatedAt());
  }

  private Map<String, Object> decisionLocal(UUID repairId, String comment) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("repairId", repairId.toString());
    if (comment != null) result.put("comment", comment);
    return result;
  }

  private static String combineDecision(String reason, String comment) {
    if (comment == null || comment.isBlank()) return reason.trim();
    return reason.trim() + "\n" + comment.trim();
  }

  private static UUID rootId(MaintenanceRepair value) {
    return value.getRootRepairId() == null ? value.getId() : value.getRootRepairId();
  }

  private ActorSnapshot actor(String storedActor) {
    Map<String, Object> value = jsonMap(storedActor);
    Object actorId = value.get("subjectId");
    if (actorId == null) actorId = value.get("actorId");
    String principalType = String.valueOf(value.getOrDefault("principalType", value.get("actorType")));
    return new ActorSnapshot(
        actorId == null ? "00000000-0000-0000-0000-0000000000d6" : actorId.toString(),
        "USER".equals(principalType) ? ActorType.USER : ActorType.SERVICE);
  }

  private static LeaseReconciliationState leaseReconciliationState(String value) {
    return "NOT_REQUIRED".equals(value)
        ? LeaseReconciliationState.NOT_ACQUIRED : LeaseReconciliationState.valueOf(value);
  }

  private static long moneyToMinor(String value) {
    try {
      return new BigDecimal(value).movePointRight(2).longValueExact();
    } catch (ArithmeticException | NumberFormatException exception) {
      throw invalid("Money must contain exactly two fractional digits and fit int64 minor units");
    }
  }

  private static String money(Long minor) {
    return minor == null ? null : money(minor.longValue());
  }

  private static String money(long minor) {
    return BigDecimal.valueOf(minor, 2).toPlainString();
  }

  private static String money(BigDecimal minor) {
    return money(minor.setScale(0, RoundingMode.HALF_UP).longValueExact());
  }

  private static String quantity(BigDecimal value) {
    BigDecimal normalized = value.stripTrailingZeros();
    return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
  }

  private static boolean booleanValue(Map<String, Object> value, String key) {
    return Boolean.TRUE.equals(value.get(key));
  }

  private static int intValue(Map<String, Object> value, String key) {
    Object stored = value.get(key);
    return stored instanceof Number number ? number.intValue() : 0;
  }

  private static String stringValue(Map<String, Object> value, String key) {
    Object stored = value.get(key);
    if (stored == null) {
      throw new IllegalStateException("Stored catalog validation report is missing " + key);
    }
    return stored.toString();
  }

  private Map<String, Object> jsonMap(String value) {
    try {
      return mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance JSON is invalid", exception);
    }
  }

  private <T> T read(JsonNode value, Class<T> type) {
    try {
      return mapper.treeToValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance response is invalid", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance value is invalid", exception);
    }
  }

  private <T> List<T> readList(String value, Class<T> type) {
    try {
      JsonNode node = mapper.readTree(value);
      List<T> result = new ArrayList<>();
      for (JsonNode item : node) result.add(mapper.treeToValue(item, type));
      return List.copyOf(result);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance list is invalid", exception);
    }
  }

  private static MaintenanceValidationException invalid(String message) {
    return new MaintenanceValidationException("MAINTENANCE_VALIDATION_FAILED", message);
  }

  private static UUID derived(UUID key, String suffix) {
    return UUID.nameUUIDFromBytes((key + ":" + suffix).getBytes(StandardCharsets.UTF_8));
  }

  private static UUID stableOperationKey(String operation, UUID aggregateId, long version) {
    return UUID.nameUUIDFromBytes(
        (operation + ":" + aggregateId + ":" + version).getBytes(StandardCharsets.UTF_8));
  }

  private static boolean leaseIsFresh(OffsetDateTime expiresAt) {
    return expiresAt != null
        && !expiresAt.isBefore(
            OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(LEASE_RENEWAL_GUARD));
  }

  private static void requireFreshDependencyLease(
      MaintenanceDependencyGateway.LeaseSnapshot lease) {
    if (!leaseIsFresh(lease.expiresAt())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease does not retain the five-minute safety window");
    }
  }

  private void advisoryLock(String key) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        key);
  }

  public record CreateResult<T>(T response, boolean replayed) {}
  private record CatalogValidation(boolean dependencyAcyclic) {}
  private record LockedRepairChain(
      MaintenanceRepair repair,
      List<MaintenanceRepair> sources,
      Map<MaintenanceEventStore.StreamRef, Long> streamVersions) {}
  private record LockedRework(
      MaintenanceRepair rework,
      MaintenanceRepair root,
      MaintenanceRepair source,
      Map<MaintenanceEventStore.StreamRef, Long> streamVersions) {}
  private record LockedTaskOutcome(
      MaintenanceRepair repair,
      MaintenanceRepair source,
      Map<MaintenanceEventStore.StreamRef, Long> streamVersions) {}
}
