package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService;
import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.OperationLeaseFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexityColors;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceActorReferenceProvider;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.mapper.CatalogFurnitureReferenceMapper;
import dev.buhanzaz.rwms.maintenance.mapper.CatalogNodeResponseMapper;
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
import dev.buhanzaz.rwms.maintenance.repository.RepairTaskEvidenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MaintenanceApplicationService {
  private static final UUID LOGISTICS_SERVICE_SUBJECT =
      UUID.nameUUIDFromBytes("rwms:logistics-service".getBytes(StandardCharsets.UTF_8));
  private static final int DELIVERED_REPAIR_TASK_BOARD_PRIORITY = 1;
  private static final Duration LEASE_RENEWAL_GUARD = Duration.ofMinutes(5);
  /** Bounded durable ownership window for remote reconciliation effects. */
  private static final Duration RECONCILIATION_CLAIM_LEASE = Duration.ofMinutes(2);
  private static final String AFTER_RENT_STATUS = "AFTER_RENT";
  private static final String RENTED_STATUS = "RENTED";
  private static final Set<String> EMPTY_DIRECT_REPAIR_SOURCE_STATUSES =
      Set.of("FREE", "WAREHOUSE", "OWN_NEEDS");
  private static final Set<String> ESTIMATE_REPAIR_QUEUE_SOURCE_STATUSES = Set.of(
      "FREE",
      "WAREHOUSE",
      "OWN_NEEDS",
      "AFTER_RENT",
      "WAITING_ESTIMATE_CONFIRMATION",
      "REPAIR",
      "CAPITAL_REPAIR",
      "USED_SALE");
  private static final Set<String> DIRECT_REPAIR_QUEUE_SOURCE_STATUSES = Set.of(
      "BOOKED",
      "REPAIR",
      "WAITING_REPAIR_CHECK",
      "WRITTEN_OFF",
      "CAPITAL_REPAIR",
      "WAITING_ESTIMATE_CONFIRMATION",
      "SALE",
      "USED_SALE",
      "RESERVED",
      "FREE",
      "WAREHOUSE",
      "OWN_NEEDS",
      "IN_TRANSFER");
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
  private final RepairTaskEvidenceRepository taskEvidence;
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
  private final CatalogFurnitureReferenceMapper catalogFurnitureMapper;
  private final CatalogNodeResponseMapper catalogNodeResponseMapper;
  private final RepairCapacitySettingsService repairCapacitySettings;
  private final RepairComplexitySettingsService repairComplexitySettings;
  private final RepairComplexityColorsService repairComplexityColors;
  private final RepairPlaceService repairPlaces;
  private final InventoryPublicationSuccessorActivator inventorySuccessors;
  private final InventoryPublicationPrestartReplacementGuard prestartReplacementGuard;
  private final PropertyDispositionApplicationService propertyDispositions;
  private final FurnitureEquipmentLinkResolver furnitureEquipmentLinks;
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseLifecycleOperations warehouseLifecycle;
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
      RepairTaskEvidenceRepository taskEvidence,
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
      CatalogFurnitureReferenceMapper catalogFurnitureMapper,
      CatalogNodeResponseMapper catalogNodeResponseMapper,
      RepairCapacitySettingsService repairCapacitySettings,
      RepairComplexitySettingsService repairComplexitySettings,
      RepairComplexityColorsService repairComplexityColors,
      RepairPlaceService repairPlaces,
      InventoryPublicationSuccessorActivator inventorySuccessors,
      InventoryPublicationPrestartReplacementGuard prestartReplacementGuard,
      PropertyDispositionApplicationService propertyDispositions,
      FurnitureEquipmentLinkResolver furnitureEquipmentLinks,
      MaintenanceDependencyGateway dependencies,
      WarehouseLifecycleOperations warehouseLifecycle,
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
    this.taskEvidence = taskEvidence;
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
    this.catalogFurnitureMapper = catalogFurnitureMapper;
    this.catalogNodeResponseMapper = catalogNodeResponseMapper;
    this.repairCapacitySettings = repairCapacitySettings;
    this.repairComplexitySettings = repairComplexitySettings;
    this.repairComplexityColors = repairComplexityColors;
    this.repairPlaces = repairPlaces;
    this.inventorySuccessors = inventorySuccessors;
    this.prestartReplacementGuard = prestartReplacementGuard;
    this.propertyDispositions = propertyDispositions;
    this.furnitureEquipmentLinks = furnitureEquipmentLinks;
    this.dependencies = dependencies;
    this.warehouseLifecycle = warehouseLifecycle;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public CreateResult<PrepareTransferRepairResponse> prepareTransferDeparture(
      UUID transferId,
      UUID lineId,
      UUID key,
      TransferRepairRequest request) {
    requireNoCallerTransaction("prepare a repair transfer departure");
    TransferDeparturePreflight preflight =
        inLocalTransaction(
            "repair transfer departure preflight",
            () -> prepareTransferDeparturePreflight(transferId, lineId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    TransferDeparturePlan plan = preflight.plan();
    if (plan.warehouseId() != null) {
      warehouseLifecycle.requireOutgoing(plan.warehouseId());
    }
    if (plan.leaseRelease() != null) {
      releaseTransferLease(plan.leaseRelease(), key);
    }
    return inLocalTransaction(
        "repair transfer departure finalization",
        () -> prepareTransferDepartureInTransaction(transferId, lineId, key, request, plan));
  }

  private TransferDeparturePreflight
      prepareTransferDeparturePreflight(
          UUID transferId, UUID lineId, UUID key, TransferRepairRequest request) {
    String scope = "transfer.prepare:" + transferId + ":" + lineId;
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new TransferDeparturePreflight(
          new CreateResult<>(
              read(replay.get(), PrepareTransferRepairResponse.class), true),
          null);
    }
    List<MaintenanceRepair> candidates =
        repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(request.rentalItemId());
    MaintenanceRepair active = requireSingleActiveRepair(candidates, false);
    return new TransferDeparturePreflight(null, transferDeparturePlan(candidates, active, request));
  }

  private CreateResult<PrepareTransferRepairResponse> prepareTransferDepartureInTransaction(
      UUID transferId,
      UUID lineId,
      UUID key,
      TransferRepairRequest request,
      TransferDeparturePlan expectedPlan) {
    String scope = "transfer.prepare:" + transferId + ":" + lineId;
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), PrepareTransferRepairResponse.class), true);
    }
    List<MaintenanceRepair> candidates =
        repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(request.rentalItemId());
    MaintenanceRepair active = requireSingleActiveRepair(candidates, false);
    TransferDeparturePlan currentPlan = transferDeparturePlan(candidates, active, request);
    requireMatchingTransferDeparturePlan(expectedPlan, currentPlan);
    if (active == null) {
      PrepareTransferRepairResponse response =
          new PrepareTransferRepairResponse(null, null, "FREE");
      idempotency.store(
          LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
      return new CreateResult<>(response, false);
    }
    List<MaintenanceRepair> chain = lockRepairChain(active);
    active = requireRepairInChain(chain, active.getId());
    requireMatchingTransferDeparturePlan(
        expectedPlan, transferDeparturePlan(chain, active, request));
    lockRepairStreams(chain);

    MaintenanceRepair leaseOwner = repairLifecycleOwner(chain, active);
    if (expectedPlan.leaseRelease() != null) {
      leaseOwner.releaseLease();
    }

    Map<UUID, Long> versions =
        chain.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MaintenanceRepair::getId, MaintenanceRepair::getVersion));
    for (MaintenanceRepair repair : chain) {
      repair.prepareWarehouseTransfer(
          transferId, lineId, request.targetWarehouseId());
    }
    List<MaintenanceRepair> saved = repairs.saveAllAndFlush(chain);
    appendRepairTransferEvents(
        saved, versions, MaintenanceEventType.REPAIR_TRANSFER_PREPARED);
    MaintenanceRepair savedActive = requireRepairInChain(saved, active.getId());
    warehouseLifecycle.recordOperation(
        request.sourceWarehouseId(),
        derived(key, "warehouse-transfer-departure"),
        savedActive.getUpdatedAt());
    PrepareTransferRepairResponse response =
        new PrepareTransferRepairResponse(
            savedActive.getId(), savedActive.getVersion(), "REPAIR");
    idempotency.store(
        LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  public TransferRepairArrivalPreflightResponse transferArrivalPreflight(
      UUID transferId, UUID lineId, TransferRepairRequest request) {
    requireNoCallerTransaction("preflight a repair transfer arrival");
    TransferArrivalPlan plan =
        inLocalTransaction(
            "repair transfer arrival routing prepare",
            () -> transferArrivalPlan(transferId, lineId, request));
    if (plan.activeRepairId() == null) {
      return new TransferRepairArrivalPreflightResponse(null, false, List.of());
    }
    TransferRoutingResult routing = resolveTransferArrivalRouting(plan);
    return inLocalTransaction(
        "repair transfer arrival routing finalization",
        () -> {
          TransferArrivalPlan current = transferArrivalPlan(transferId, lineId, request);
          requireMatchingTransferArrivalPlan(plan, current);
          return new TransferRepairArrivalPreflightResponse(
              current.activeRepairId(), true, routing.missingQueueDefinitionIds());
        });
  }

  public CreateResult<CompleteTransferRepairResponse> completeTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID key,
      CompleteTransferRepairRequest request) {
    requireNoCallerTransaction("complete a repair transfer arrival");
    TransferArrivalCompletionPreflight preflight =
        inLocalTransaction(
            "repair transfer arrival preflight",
            () -> completeTransferArrivalPreflight(transferId, lineId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    TransferArrivalPlan plan = preflight.plan();
    if (plan.activeRepairId() == null) {
      return inLocalTransaction(
          "repair transfer arrival empty finalization",
          () -> completeTransferArrivalInTransaction(transferId, lineId, key, request, plan, null));
    }
    warehouseLifecycle.requireIncoming(plan.targetWarehouseId());
    TransferRoutingResult routing = resolveTransferArrivalRouting(plan);
    if (!routing.missingQueueDefinitionIds().isEmpty()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_TARGET_QUEUE_MISSING",
          "The target warehouse is missing queues required by the active repair");
    }
    TransferArrivalRemoteResult remote = completeTransferArrivalRemote(plan, key, routing);
    return inLocalTransaction(
        "repair transfer arrival finalization",
        () -> completeTransferArrivalInTransaction(transferId, lineId, key, request, plan, remote));
  }

  private TransferArrivalCompletionPreflight
      completeTransferArrivalPreflight(
          UUID transferId, UUID lineId, UUID key, CompleteTransferRepairRequest request) {
    String scope = "transfer.complete:" + transferId + ":" + lineId;
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new TransferArrivalCompletionPreflight(
          new CreateResult<>(
              read(replay.get(), CompleteTransferRepairResponse.class), true),
          null);
    }
    TransferArrivalPlan plan =
        transferArrivalPlan(
            transferId,
            lineId,
            request.rentalItemId(),
            request.sourceWarehouseId(),
            request.targetWarehouseId(),
            request.rentalItemVersion());
    if (plan.activeRepairId() != null && request.priority() == null) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_TRANSFER_CONTINUATION_REQUIRED",
          "The receiving employee must select the active repair priority");
    }
    return new TransferArrivalCompletionPreflight(null, plan);
  }

  private CreateResult<CompleteTransferRepairResponse> completeTransferArrivalInTransaction(
      UUID transferId,
      UUID lineId,
      UUID key,
      CompleteTransferRepairRequest request,
      TransferArrivalPlan expectedPlan,
      TransferArrivalRemoteResult remote) {
    String scope = "transfer.complete:" + transferId + ":" + lineId;
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), CompleteTransferRepairResponse.class), true);
    }

    if (expectedPlan.activeRepairId() == null) {
      TransferArrivalPlan current =
          transferArrivalPlan(
              transferId,
              lineId,
              request.rentalItemId(),
              request.sourceWarehouseId(),
              request.targetWarehouseId(),
              request.rentalItemVersion());
      requireMatchingTransferArrivalPlan(expectedPlan, current);
      CompleteTransferRepairResponse response =
          new CompleteTransferRepairResponse(
              null, null, request.targetWarehouseId());
      idempotency.store(
          LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
      return new CreateResult<>(response, false);
    }
    List<MaintenanceRepair> observedChain =
        repairs.findAllByTransferLineIdOrderByCreatedAtAscIdAsc(lineId);
    TransferArrivalPlan observed =
        transferArrivalPlan(
            observedChain,
            transferId,
            lineId,
            request.rentalItemId(),
            request.sourceWarehouseId(),
            request.targetWarehouseId(),
            request.rentalItemVersion());
    requireMatchingTransferArrivalPlan(expectedPlan, observed);
    List<MaintenanceRepair> chain =
        repairs.findAllByIdForUpdate(
            observedChain.stream().map(MaintenanceRepair::getId).toList());
    List<MaintenanceRepair> postLockChain =
        repairs.findAllByTransferLineIdOrderByCreatedAtAscIdAsc(lineId);
    TransferArrivalPlan current =
        transferArrivalPlan(
            postLockChain,
            transferId,
            lineId,
            request.rentalItemId(),
            request.sourceWarehouseId(),
            request.targetWarehouseId(),
            request.rentalItemVersion());
    requireMatchingTransferArrivalPlan(expectedPlan, current);
    MaintenanceRepair active = requireRepairInChain(chain, expectedPlan.activeRepairId());
    RepairComplexity targetComplexity = expectedPlan.targetComplexity();
    if (request.priority() == null || remote == null) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_TRANSFER_CONTINUATION_REQUIRED",
          "The receiving employee must select the active repair priority");
    }
    validateTransferArrivalRemoteTruth(expectedPlan, remote);
    lockRepairStreams(chain);
    Map<UUID, Long> versions =
        chain.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MaintenanceRepair::getId, MaintenanceRepair::getVersion));

    MaintenanceRepair leaseOwner = repairLifecycleOwner(chain, active);
    if (targetComplexity == RepairComplexity.CAPITAL) {
      List<RepairStage> externalCapitalStages = new ArrayList<>();
      List<MaintenanceRepair> externalCapitalRepairs = new ArrayList<>();
      for (MaintenanceRepair repair : chain) {
        if (repair.getExecutionState() != RepairExecutionState.QUEUED
            && repair.getExecutionState() != RepairExecutionState.IN_PROGRESS) {
          continue;
        }
        List<RepairStage> stages =
            repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
        stages.forEach(RepairStage::completeAsExternalCapital);
        externalCapitalStages.addAll(stages);
        externalCapitalRepairs.add(repair);
      }
      if (!externalCapitalStages.isEmpty()) {
        repairStages.saveAllAndFlush(externalCapitalStages);
      }
      externalCapitalRepairs.forEach(
          MaintenanceRepair::completeAsExternalCapital);
    }

    for (MaintenanceRepair repair : chain) {
      repair.completeWarehouseTransfer(
          transferId,
          lineId,
          request.targetWarehouseId(),
          request.priority());
      Long taskVersion = remote.relocatedTaskVersions().get(repair.getId());
      if (taskVersion != null) {
        repair.markTaskRelocated(taskVersion);
      }
      repair.confirmRentalItemVersion(remote.asset().version());
    }
    leaseOwner.adoptLeaseAfterTransfer(
        remote.lease().leaseId(),
        remote.lease().version(),
        remote.lease().fencingToken(),
        remote.lease().expiresAt());
    List<MaintenanceRepair> saved = repairs.saveAllAndFlush(chain);
    appendRepairTransferEvents(
        saved, versions, MaintenanceEventType.REPAIR_TRANSFERRED);
    MaintenanceRepair savedActive = requireRepairInChain(saved, active.getId());
    warehouseLifecycle.recordOperation(
        request.targetWarehouseId(),
        derived(key, "warehouse-transfer-arrival"),
        savedActive.getUpdatedAt());
    CompleteTransferRepairResponse response =
        new CompleteTransferRepairResponse(
            savedActive.getId(),
            savedActive.getVersion(),
            request.targetWarehouseId());
    idempotency.store(
        LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  @Transactional(readOnly = true)
  public List<CatalogVersionResponse> catalogVersions(UUID authorizationWarehouseId) {
    Objects.requireNonNull(
        authorizationWarehouseId, "Catalog authorization warehouse is required");
    return catalogVersions.findAllByOrderByCreatedAtDesc().stream()
        .map(this::catalogResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public CatalogVersionResponse catalogVersion(UUID id) { return catalogResponse(requireCatalog(id)); }

  @Transactional(readOnly = true)
  public CatalogVersionResponse catalogVersion(UUID id, UUID authorizationWarehouseId) {
    Objects.requireNonNull(
        authorizationWarehouseId, "Catalog authorization warehouse is required");
    return catalogResponse(requireCatalog(id));
  }

  @Transactional(readOnly = true)
  public List<CatalogNodeResponse> catalogNodes(UUID id) {
    requireCatalog(id);
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(id).stream()
        .map(this::catalogNodeResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<CatalogLinkResponse> catalogLinks(UUID id) {
    requireCatalog(id);
    return catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(id).stream()
        .map(this::catalogLinkResponse)
        .toList();
  }

  public CreateResult<CatalogVersionResponse> createCatalog(
      UUID subjectId, UUID key, CreateCatalogRequest request) {
    requireNoCallerTransaction("create a catalog");
    WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> preflight =
        inLocalTransaction(
            "catalog create preflight",
            () -> createCatalogPreflight(subjectId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    if (preflight.warehouseId() != null) {
      warehouseLifecycle.requireIncoming(preflight.warehouseId());
    }
    return inLocalTransaction(
        "catalog create finalization", () -> createCatalogInTransaction(subjectId, key, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> createCatalogPreflight(
      UUID subjectId, UUID key, CreateCatalogRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "catalog.create", key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true));
    }
    boolean activeExists =
        catalogVersions.findAllByOrderByCreatedAtDesc().stream()
            .anyMatch(version -> version.getState() == CatalogVersionState.ACTIVE);
    return new WarehouseAdmissionPreflight<>(activeExists ? null : request.warehouseId(), null);
  }

  private CreateResult<CatalogVersionResponse> createCatalogInTransaction(
      UUID subjectId, UUID key, CreateCatalogRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "catalog.create", key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true);
    }
    advisoryLock("maintenance:catalog-create:global");
    CatalogVersion existing =
        catalogVersions.findAllForUpdate().stream()
            .filter(version -> version.getState() == CatalogVersionState.ACTIVE)
            .findFirst()
            .orElse(null);
    if (existing != null) {
      CatalogVersionResponse response = catalogResponse(existing);
      idempotency.store(subjectId, "catalog.create", key, requestHash, 201, response);
      return new CreateResult<>(response, true);
    }
    CatalogValidation validation = validateCatalog(List.of(), List.of());
    Map<String, Object> report = baseCatalogReport(List.of(), List.of(), validation);
    report.put("source", "CATALOG_BUILDER");
    report.put("reportSha256", hash(report));
    String sourceSha256 =
        hash(
            Map.of(
                "schema", "maintenance-global-catalog-builder-v1"));
    CatalogVersion version = catalogVersions.saveAndFlush(
        CatalogVersion.draft(
            request.warehouseId(),
            sourceSha256,
            0,
            0,
            write(report)));
    events.initialize(
        MaintenanceAggregateType.CATALOG_VERSION,
        version.getId(),
        version.getVersion(),
        MaintenanceEventType.CATALOG_IMPORTED,
        catalogLocal(version),
        catalogFact(MaintenanceEventType.CATALOG_IMPORTED, version),
        catalogSnapshot(version));
    long expectedVersion = version.getVersion();
    version.activate();
    CatalogVersion active = catalogVersions.saveAndFlush(version);
    events.append(
        MaintenanceAggregateType.CATALOG_VERSION,
        active.getId(),
        expectedVersion,
        MaintenanceEventType.CATALOG_ACTIVATED,
        catalogLocal(active),
        catalogFact(MaintenanceEventType.CATALOG_ACTIVATED, active),
        catalogSnapshot(active));
    warehouseLifecycle.recordOperation(
        active.getWarehouseId(), active.getId(), active.getActivatedAt());
    CatalogVersionResponse response = catalogResponse(active);
    idempotency.store(subjectId, "catalog.create", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public CatalogVersionResponse changeCatalog(UUID id, ChangeCatalogRequest request) {
    requireNoCallerTransaction("change a catalog");
    UUID routingContextWarehouseId =
        transactions.execute(status -> requireCatalog(id).getWarehouseId());
    if (routingContextWarehouseId == null) {
      throw new IllegalStateException("Catalog routing context lookup was empty");
    }
    return changeCatalog(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse changeCatalog(
      UUID id, UUID routingContextWarehouseId, ChangeCatalogRequest request) {
    requireNoCallerTransaction("change a catalog");
    Objects.requireNonNull(
        routingContextWarehouseId, "Catalog routing context warehouse is required");
    validateCatalog(request.nodes(), request.links());
    CatalogMutationTarget target =
        transactions.execute(
            status -> {
              CatalogVersion version =
                  requireMutableCatalog(id, request.expectedVersion(), false);
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
        resolveCabinCharacteristicNames(resolvedNodes);
    CatalogValidation validation = validateCatalog(resolvedNodes, request.links());
    Map<UUID, RoutingSnapshot> canonicalRouting =
        canonicalCatalogRouting(resolvedNodes);
    if (target.state() == CatalogVersionState.ACTIVE) {
      validateCatalogRoutingForActivation(resolvedNodes, request.links());
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
    CatalogVersion version = requireMutableCatalog(id, request.expectedVersion(), true);
    List<CatalogNodeInput> previousNodes = catalogNodeInputs(id);
    String contentSha256 = hash(new CatalogContent(request.nodes(), request.links()));
    if (contentSha256.equals(jsonMap(version.getValidationReport()).get("contentSha256"))) {
      return catalogResponse(version);
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
    report.put("materialCount", materialCount(request.nodes()));
    report.put("dependencyAcyclic", validation.dependencyAcyclic());
    Object source = jsonMap(version.getValidationReport()).get("source");
    if (source != null) report.put("source", source);
    report.put("reportSha256", hash(report));
    version.replaceCatalog(request.nodes().size(), request.links().size(), write(report));
    CatalogVersion saved = catalogVersions.saveAndFlush(version);
    saveCatalog(
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
        catalogLocal(saved),
        catalogFact(MaintenanceEventType.CATALOG_CHANGED, saved),
        catalogSnapshot(saved));
    if (saved.getState() == CatalogVersionState.ACTIVE) {
      enqueueCatalogRoutingChange(saved, previousNodes, request.nodes());
    }
    return catalogResponse(saved);
  }

  public CatalogVersionResponse replaceCatalogNodes(UUID id, ReplaceCatalogNodesRequest request) {
    requireNoCallerTransaction("replace catalog nodes");
    UUID routingContextWarehouseId =
        transactions.execute(status -> requireCatalog(id).getWarehouseId());
    if (routingContextWarehouseId == null) {
      throw new IllegalStateException("Catalog routing context lookup was empty");
    }
    return replaceCatalogNodes(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse replaceCatalogNodes(
      UUID id, UUID routingContextWarehouseId, ReplaceCatalogNodesRequest request) {
    requireNoCallerTransaction("replace catalog nodes");
    List<CatalogLinkInput> links = transactions.execute(status -> {
      requireCatalog(id);
      return catalogLinkInputs(id);
    });
    if (links == null) throw new IllegalStateException("Catalog link read transaction was empty");
    return changeCatalog(
        id,
        routingContextWarehouseId,
        new ChangeCatalogRequest(request.expectedVersion(), request.nodes(), links));
  }

  public CatalogVersionResponse replaceCatalogLinks(UUID id, ReplaceCatalogLinksRequest request) {
    requireNoCallerTransaction("replace catalog links");
    UUID routingContextWarehouseId =
        transactions.execute(status -> requireCatalog(id).getWarehouseId());
    if (routingContextWarehouseId == null) {
      throw new IllegalStateException("Catalog routing context lookup was empty");
    }
    return replaceCatalogLinks(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse replaceCatalogLinks(
      UUID id, UUID routingContextWarehouseId, ReplaceCatalogLinksRequest request) {
    requireNoCallerTransaction("replace catalog links");
    List<CatalogNodeInput> nodes = transactions.execute(status -> {
      requireCatalog(id);
      return catalogNodeInputs(id);
    });
    if (nodes == null) throw new IllegalStateException("Catalog node read transaction was empty");
    return changeCatalog(
        id,
        routingContextWarehouseId,
        new ChangeCatalogRequest(request.expectedVersion(), nodes, request.links()));
  }

  public CreateResult<CatalogVersionResponse> forkCatalog(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    requireNoCallerTransaction("fork a catalog");
    WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> preflight =
        inLocalTransaction(
            "catalog fork preflight", () -> forkCatalogPreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    return inLocalTransaction(
        "catalog fork finalization", () -> forkCatalogInTransaction(subjectId, key, id, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<CatalogVersionResponse>> forkCatalogPreflight(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    String requestHash = hash(request);
    String scope = "catalog.fork:" + id;
    Optional<JsonNode> replay = idempotency.replay(subjectId, scope, key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true));
    }
    CatalogVersion source = requireCatalog(id);
    assertVersion(source.getVersion(), request.expectedVersion());
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
    String requestHash = hash(request);
    String scope = "catalog.fork:" + id;
    Optional<JsonNode> replay = idempotency.replay(subjectId, scope, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true);
    }
    advisoryLock("maintenance:catalog-fork:" + id);
    CatalogVersion source = catalogVersions.findByIdForUpdate(id)
        .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"));
    assertVersion(source.getVersion(), request.expectedVersion());
    if (source.getState() != CatalogVersionState.ACTIVE
        && source.getState() != CatalogVersionState.SUPERSEDED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only an active or superseded catalog version can be forked");
    }
    List<CatalogNodeInput> nodes = catalogNodeInputs(id);
    List<CatalogLinkInput> links = catalogLinkInputs(id);
    CatalogValidation validation = validateCatalog(nodes, links);
    CatalogForkSnapshot sourceSnapshot = new CatalogForkSnapshot(
        source.getId(), source.getVersion(), source.getState(), nodes, links);
    String sourceSnapshotSha256 = hash(sourceSnapshot);
    Optional<CatalogVersion> existing =
        catalogVersions.findFirstBySourceSha256OrderByCreatedAtDesc(sourceSnapshotSha256);
    if (existing.isPresent()) {
      requireMatchingFork(existing.get(), sourceSnapshot, sourceSnapshotSha256);
      CatalogVersionResponse response = catalogResponse(existing.get());
      idempotency.store(subjectId, scope, key, requestHash, 201, response);
      return new CreateResult<>(response, true);
    }
    Map<String, Object> report =
        forkCatalogReport(source, sourceSnapshotSha256, nodes, links, validation);
    CatalogVersion fork = catalogVersions.saveAndFlush(CatalogVersion.draft(
        source.getWarehouseId(), sourceSnapshotSha256, nodes.size(), links.size(), write(report)));
    saveCatalog(
        fork.getId(),
        nodes,
        links,
        canonicalRoutingFromCatalog(id),
        catalogCharacteristicNames(id));
    events.initialize(
        MaintenanceAggregateType.CATALOG_VERSION,
        fork.getId(),
        fork.getVersion(),
        MaintenanceEventType.CATALOG_IMPORTED,
        catalogLocal(fork),
        catalogFact(MaintenanceEventType.CATALOG_IMPORTED, fork),
        catalogSnapshot(fork));
    CatalogVersionResponse response = catalogResponse(fork);
    idempotency.store(subjectId, scope, key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public CreateResult<CatalogVersionResponse> activateCatalog(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    requireNoCallerTransaction("activate a catalog");
    UUID routingContextWarehouseId =
        transactions.execute(status -> requireCatalog(id).getWarehouseId());
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
    requireNoCallerTransaction("activate a catalog");
    Objects.requireNonNull(
        routingContextWarehouseId, "Catalog routing context warehouse is required");
    transactions.executeWithoutResult(status -> requireCatalog(id));
    String requestHash = hash(request);
    Optional<JsonNode> replay = transactions.execute(status -> idempotency.replay(
        subjectId, "catalog.activate:" + id, key, requestHash));
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true);
    }
    Boolean preflight = transactions.execute(
        status -> activationPreflight(id, request.expectedVersion()));
    if (!Boolean.TRUE.equals(preflight)) {
      throw new IllegalStateException("Catalog activation preflight transaction was empty");
    }
    warehouseLifecycle.requireIncoming(routingContextWarehouseId);
    canonicalCatalogRouting(catalogNodeInputs(id));
    CreateResult<CatalogVersionResponse> result = transactions.execute(
        status -> activateCatalogAfterPreflight(subjectId, key, id, request, requestHash));
    if (result == null) throw new IllegalStateException("Catalog activation transaction was empty");
    return result;
  }

  private boolean activationPreflight(UUID id, long expectedVersion) {
    CatalogVersion selected = requireCatalog(id);
    assertVersion(selected.getVersion(), expectedVersion);
    if (selected.getState() != CatalogVersionState.DRAFT) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Only a draft catalog version can be activated");
    }
    validateFurnitureCatalogForActivation(id);
    validateCatalogRoutingForActivation(id);
    return true;
  }

  private CreateResult<CatalogVersionResponse> activateCatalogAfterPreflight(
      UUID subjectId, UUID key, UUID id, VersionCommand request, String requestHash) {
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "catalog.activate:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), CatalogVersionResponse.class), true);
    }
    advisoryLock("maintenance:catalog-activation:global");
    CatalogVersion selected = catalogVersions.findByIdForUpdate(id)
        .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"));
    assertVersion(selected.getVersion(), request.expectedVersion());
    validateFurnitureCatalogForActivation(id);
    validateCatalogRoutingForActivation(id);
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
    assertVersion(locked.get(new MaintenanceEventStore.StreamRef(
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
    warehouseLifecycle.recordOperation(
        saved.getWarehouseId(), saved.getId(), saved.getActivatedAt());
    enqueueCatalogRouting(saved, superseded);
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

  public CreateResult<EstimateResponse> createEstimate(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    requireNoCallerTransaction("create an estimate");
    WarehouseAdmissionPreflight<CreateResult<EstimateResponse>> preflight =
        inLocalTransaction(
            "estimate create preflight", () -> createEstimatePreflight(subjectId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    requireCustomRoutingReady(request.warehouseId(), request.plan());
    return inLocalTransaction(
        "estimate create finalization", () -> createEstimateInTransaction(subjectId, key, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<EstimateResponse>> createEstimatePreflight(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "estimate.create", key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(read(replay.get(), EstimateResponse.class), true));
    }
    RentalItemFactProjection rentalItem =
        requireRentalItemFact(request.rentalItemId(), request.warehouseId());
    requireEstimateSourceStatus(rentalItem);
    requireActiveCatalog(request.warehouseId());
    validateEstimatePlan(request.lines(), request.plan());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    return new WarehouseAdmissionPreflight<>(request.warehouseId(), null);
  }

  private CreateResult<EstimateResponse> createEstimateInTransaction(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.create", key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EstimateResponse.class), true);
    RentalItemFactProjection rentalItem = requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    requireEstimateSourceStatus(rentalItem);
    CatalogVersion catalog = requireActiveCatalog(request.warehouseId());
    validateEstimatePlan(request.lines(), request.plan());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceEstimate draft = MaintenanceEstimate.create(
        request.warehouseId(), request.rentalItemId(), rentalItem.getAggregateVersion(),
        catalog.getId(), request.dispatchDate(), request.sourceParty(), null, actorJson());
    draft.replaceCoverMediaId(request.coverMediaId());
    MaintenanceEstimate estimate = estimates.saveAndFlush(draft);
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
    enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        estimate.getWarehouseId(),
        estimate.getId(),
        estimate.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    EstimateResponse response = estimateResponse(estimate);
    idempotency.store(subjectId, "estimate.create", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  /**
   * Creates the maintenance-owned estimate paired with one immutable logistics return source.
   *
   * <p>The caller owns the concurrent source-key arbitration and transaction. Logistics has
   * already validated these opaque references against the exact return-line media owner before
   * invoking the maintenance boundary, so they are persisted without pretending that their source
   * media owner was already the newly generated estimate ID.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID createLogisticsReturnEstimate(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReferenceInput> sourceMediaReferences) {
    if (warehouseId == null
        || rentalItemId == null
        || rentalItemVersion < 0
        || dispatchDate == null
        || sourceMediaReferences == null
        || sourceMediaReferences.isEmpty()) {
      throw new IllegalArgumentException("Logistics return estimate source is incomplete");
    }
    CatalogVersion catalog = requireActiveCatalog(warehouseId);
    MaintenanceEstimate estimate =
        estimates.saveAndFlush(
            MaintenanceEstimate.create(
                warehouseId,
                rentalItemId,
                rentalItemVersion,
                catalog.getId(),
                dispatchDate,
                "Возврат из аренды",
                null,
                actorJson()));
    replaceEstimateRevision(estimate, List.of(), List.of(), null);
    replaceLogisticsReturnMedia(estimate, sourceMediaReferences);
    events.initialize(
        MaintenanceAggregateType.ESTIMATE,
        estimate.getId(),
        estimate.getVersion(),
        MaintenanceEventType.ESTIMATE_CREATED,
        estimateLocal(estimate),
        estimateFact(MaintenanceEventType.ESTIMATE_CREATED, estimate),
        estimateSnapshot(estimate));
    enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        estimate.getWarehouseId(),
        estimate.getId(),
        estimate.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    return estimate.getId();
  }

  public EstimateResponse updateEstimate(UUID id, UpdateEstimateRequest request) {
    requireNoCallerTransaction("update an estimate");
    WarehouseAdmissionPreflight<Void> preflight =
        inLocalTransaction(
            "estimate update preflight", () -> updateEstimatePreflight(id, request));
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return inLocalTransaction(
        "estimate update finalization", () -> updateEstimateInTransaction(id, request));
  }

  private WarehouseAdmissionPreflight<Void> updateEstimatePreflight(
      UUID id, UpdateEstimateRequest request) {
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    validateEstimatePlan(request.lines(), request.plan());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    return new WarehouseAdmissionPreflight<>(estimate.getWarehouseId(), null);
  }

  private EstimateResponse updateEstimateInTransaction(
      UUID id, UpdateEstimateRequest request) {
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    validateEstimatePlan(request.lines(), request.plan());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    estimate.replaceMetadata(request.dispatchDate(), request.sourceParty(), estimate.getComment());
    estimate.replaceCoverMediaId(request.coverMediaId());
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
    enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
    return estimateResponse(saved);
  }

  public CreateResult<EstimateCommandResult> completeEstimate(
      UUID subjectId, UUID key, UUID id, CompleteEstimateRequest request) {
    requireNoCallerTransaction("complete an estimate");
    EstimateCompletionPreflight preflight =
        inLocalTransaction(
            "estimate completion preflight",
            () -> completeEstimatePreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    requireCustomRoutingReady(preflight.warehouseId(), preflight.plan());
    FurnitureAccountingMode furnitureAccountingMode =
        resolveEstimateFurnitureAccounting(preflight, request);
    return inLocalTransaction(
        "estimate completion finalization",
        () ->
            completeEstimateInTransaction(
                subjectId, key, id, request, furnitureAccountingMode));
  }

  private EstimateCompletionPreflight completeEstimatePreflight(
      UUID subjectId, UUID key, UUID id, CompleteEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.complete:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new EstimateCompletionPreflight(
          new CreateResult<>(read(replay.get(), EstimateCommandResult.class), true),
          null,
          List.of(),
          null,
          List.of());
    }
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    return new EstimateCompletionPreflight(
        null,
        estimate.getWarehouseId(),
        storedPlanInputs(currentPlan(estimate)),
        estimate.getRentalItemId(),
        estimateFurnitureQuantities(estimate));
  }

  private FurnitureAccountingMode resolveEstimateFurnitureAccounting(
      EstimateCompletionPreflight preflight, CompleteEstimateRequest request) {
    if (preflight.furniture().isEmpty()) {
      return FurnitureAccountingMode.TRACKED_CABIN_CONTENTS;
    }
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            preflight.rentalItemId(),
            preflight.warehouseId());
    boolean noRecordedContents =
        hasNoRecordedCabinContents(
            preflight.rentalItemId(), preflight.warehouseId(), snapshot);
    if (noRecordedContents) {
      if (!request.allowsUnaccountedFurniture()) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED",
            "Cabin has no recorded contents. Confirm completion without warehouse additional-equipment accounting.");
      }
      return FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS;
    }
    if (request.allowsUnaccountedFurniture()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Unaccounted furniture confirmation is allowed only when the cabin has no recorded contents");
    }
    return FurnitureAccountingMode.TRACKED_CABIN_CONTENTS;
  }

  private CreateResult<EstimateCommandResult> completeEstimateInTransaction(
      UUID subjectId,
      UUID key,
      UUID id,
      CompleteEstimateRequest request,
      FurnitureAccountingMode furnitureAccountingMode) {
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
      commandRepair =
          createEstimateRepair(
              estimate,
              currentPlan(estimate),
              request.priority(),
              request.movementToRepair(),
              request.logisticsPlanningMode(),
              request.logisticsScheduledDate(),
              furnitureAccountingMode);
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

  public CreateResult<EstimateCommandResult> amendEstimate(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    requireNoCallerTransaction("amend an estimate");
    WarehouseAdmissionPreflight<CreateResult<EstimateCommandResult>> preflight =
        inLocalTransaction(
            "estimate amendment preflight",
            () -> amendEstimatePreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return inLocalTransaction(
        "estimate amendment finalization",
        () -> amendEstimateInTransaction(subjectId, key, id, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<EstimateCommandResult>> amendEstimatePreflight(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.amend:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(read(replay.get(), EstimateCommandResult.class), true));
    }
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    validateEstimatePlan(request.lines(), request.plan());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    return new WarehouseAdmissionPreflight<>(estimate.getWarehouseId(), null);
  }

  private CreateResult<EstimateCommandResult> amendEstimateInTransaction(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.amend:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), EstimateCommandResult.class), true);
    }
    MaintenanceEstimate estimate = requireEstimate(id);
    assertVersion(estimate.getVersion(), request.expectedVersion());
    validateEstimatePlan(request.lines(), request.plan());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceRepair repair = estimate.getRepairId() == null ? null : requireRepair(estimate.getRepairId());
    if (repair != null
        && !estimateFurnitureQuantities(estimate)
            .equals(estimateFurnitureQuantities(estimate, request.lines()))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Furniture quantities cannot change after an estimate has created its repair");
    }
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
      List<EstimateLineResponse> canonicalLines =
          canonicalEstimateLines(estimate, request.lines());
      RepairComplexitySnapshot amendedComplexity =
          repairComplexityForLines(repair.getWarehouseId(), canonicalLines);
      boolean reclassifyingCapital =
          repair.getExecutionState() == RepairExecutionState.QUEUED
              && amendedComplexity.type() == RepairComplexity.CAPITAL;
      repair.amendPreStartPlan();
      if (reclassifyingCapital) {
        repair.markReclassifyingCapital();
      }
      replaceRepairStages(
          repair, request.plan(), canonicalLines);
      MaintenanceRepair repairSaved = repairs.saveAndFlush(repair);
      linkedRepair = repairSaved;
      if (repairSaved.getExecutionState() == RepairExecutionState.QUEUED) {
        enqueueRepairComplexityStatusSync(
            repairSaved, derived(key, "repair-complexity-status"));
        if (repairSaved.getTaskBoardVersion() != null && !reclassifyingCapital) {
          reconciliations.enqueue(
              repairSaved.getId(),
              "TASK_BOARD",
              "UPDATE_TASK",
              derived(key, "task-update"),
              Map.of("repairId", repairSaved.getId().toString()));
        }
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
    estimate.replaceCoverMediaId(request.coverMediaId());
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
    enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
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
  public List<RepairResponse> activeCapitalRepairs(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream()
        .filter(value -> value.getExecutionState() != RepairExecutionState.DRAFT)
        .filter(value -> value.getExecutionState() != RepairExecutionState.CANCELLED)
        .filter(value -> value.getAcceptanceState() != RepairAcceptanceState.ACCEPTED)
        .filter(value -> value.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF)
        .map(this::repairResponse)
        .filter(value -> value.complexity().type() == RepairComplexity.CAPITAL)
        .toList();
  }

  @Transactional(readOnly = true)
  public RepairResponse activeCapitalRepair(UUID repairId) {
    MaintenanceRepair value =
        repairs
            .findById(repairId)
            .orElseThrow(
                () -> new MaintenanceNotFoundException("Active capital repair not found"));
    if (value.getExecutionState() == RepairExecutionState.DRAFT
        || value.getExecutionState() == RepairExecutionState.CANCELLED
        || value.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || value.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw new MaintenanceNotFoundException("Active capital repair not found");
    }
    RepairResponse response = repairResponse(value);
    if (response.complexity().type() != RepairComplexity.CAPITAL) {
      throw new MaintenanceNotFoundException("Active capital repair not found");
    }
    return response;
  }

  @Transactional(readOnly = true)
  public RepairResponse repair(UUID id) { return repairResponse(requireRepair(id)); }

  @Transactional(readOnly = true)
  public RepairResponse repair(UUID id, UUID warehouseId) {
    return repairResponse(repairs.findByIdAndWarehouseId(id, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found")));
  }

  @Transactional(readOnly = true)
  public List<RepairWorkerEvidenceResponse> repairWorkerEvidence(UUID repairId) {
    Map<UUID, Integer> stageIndexes =
        repairStages.findAllByRepairIdOrderByStageNo(repairId).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    RepairStage::getId, RepairStage::getStageNo));
    return taskEvidence.findAllByRepairIdOrderByRecordedAtAscEvidenceIdAsc(repairId).stream()
        .map(
            item ->
                new RepairWorkerEvidenceResponse(
                    item.getEvidenceId(),
                    item.getRepairId(),
                    item.getRepairStageId(),
                    stageIndexes.getOrDefault(item.getRepairStageId(), item.getRouteIndex()),
                    item.getEntryId(),
                    item.getTaskId(),
                    item.getRouteIndex(),
                    item.getWorkerId(),
                    item.getWorkerGroupId(),
                    item.getMediaId(),
                    item.getMediaGeneration(),
                    item.getCapturedAt(),
                    item.getRecordedAt(),
                    TaskEvidenceState.valueOf(item.getEvidenceState())))
        .toList();
  }

  @Transactional(readOnly = true)
  public RepairPlanResponse repairPlan(UUID id) {
    RepairResponse repair = repair(id);
    return repair.plan();
  }

  /**
   * Called by the logistics-only repair-place boundary after a driver has completed inbound
   * delivery and the place is occupied. The repair remains durable and queued while it waits,
   * but its ordinary task-board entry is intentionally absent until this point.
   *
   * <p>The stable reconciliation key makes a replayed logistics callback harmless and also
   * recovers a task registration if the first callback completed the place transition but failed
   * before it could enqueue the task-board work.</p>
   */
  public void activateQueuedRepairAfterDelivery(UUID warehouseId, UUID repairId) {
    requireNoCallerTransaction("activate a delivered queued repair");
    WarehouseAdmissionPreflight<Void> preflight =
        inLocalTransaction(
            "delivered queued repair preflight",
            () -> activateQueuedRepairAfterDeliveryPreflight(warehouseId, repairId));
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    inLocalTransaction(
        "delivered queued repair finalization",
        () -> {
          activateQueuedRepairAfterDeliveryInTransaction(warehouseId, repairId);
          return Boolean.TRUE;
        });
  }

  private WarehouseAdmissionPreflight<Void> activateQueuedRepairAfterDeliveryPreflight(
      UUID warehouseId, UUID repairId) {
    MaintenanceRepair repair =
        repairs
            .findByIdAndWarehouseId(repairId, warehouseId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    return new WarehouseAdmissionPreflight<>(repair.getWarehouseId(), null);
  }

  private void activateQueuedRepairAfterDeliveryInTransaction(UUID warehouseId, UUID repairId) {
    MaintenanceRepair repair =
        repairs
            .findByIdAndWarehouseId(repairId, warehouseId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    if (prestartReplacementGuard.blocksRepairExecution(repair.getId())) return;
    long expectedVersion =
        events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    assertVersion(repair.getVersion(), expectedVersion);
    if (repair.getExecutionState() != RepairExecutionState.QUEUED
        || repair.getReclassificationState() != RepairReclassificationState.STABLE
        || repair.getTaskBoardVersion() != null
        || !requiresDriverDeliveryToRepair(repair)
        || !repairPlaces.isOccupied(warehouseId, repairId)) {
      return;
    }
    enqueueTaskRegistration(
        repair, stableOperationKey("register-task", repair.getExternalTaskId(), 0));
  }

  @Transactional(readOnly = true)
  public ReworkCandidatesResponse reworkCandidates(UUID repairId, UUID warehouseId) {
    MaintenanceRepair source = repairs.findByIdAndWarehouseId(repairId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    return new ReworkCandidatesResponse(reworkCandidateItems(source));
  }

  public CreateResult<RepairResponse> createDirectRepair(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    requireNoCallerTransaction("create a direct repair");
    WarehouseAdmissionPreflight<CreateResult<RepairResponse>> preflight =
        inLocalTransaction(
            "direct repair preflight", () -> createDirectRepairPreflight(subjectId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return inLocalTransaction(
        "direct repair finalization", () -> createDirectRepairInTransaction(subjectId, key, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<RepairResponse>> createDirectRepairPreflight(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.direct", key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(read(replay.get(), RepairResponse.class), true));
    }
    RentalItemFactProjection rentalItem = requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    requireDirectRepairSourceStatus(rentalItem);
    if (request.lines() != null
        && request.lines().isEmpty()
        && !EMPTY_DIRECT_REPAIR_SOURCE_STATUSES.contains(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "An empty direct repair requires an unoccupied rental item");
    }
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    validatePlan(request.plan(), request.lines() == null || request.lines().isEmpty());
    return new WarehouseAdmissionPreflight<>(request.warehouseId(), null);
  }

  private CreateResult<RepairResponse> createDirectRepairInTransaction(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.direct", key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), RepairResponse.class), true);
    RentalItemFactProjection rentalItem = requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    requireDirectRepairSourceStatus(rentalItem);
    if (request.lines() != null
        && request.lines().isEmpty()
        && !EMPTY_DIRECT_REPAIR_SOURCE_STATUSES.contains(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "An empty direct repair requires an unoccupied rental item");
    }
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceRepair draft = MaintenanceRepair.primary(
        request.warehouseId(), request.rentalItemId(), rentalItem.getAggregateVersion(), null,
        RepairOrigin.DIRECT_REPAIR,
        request.dispatchDate(), request.sourceParty(), actorJson());
    draft.replaceCoverMediaId(request.coverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(draft);
    List<EstimateLineResponse> canonicalLines =
        canonicalRepairLines(request.warehouseId(), request.lines());
    validateLineMediaReferences(
        "MAINTENANCE_REPAIR", repair.getId(), repair.getWarehouseId(), canonicalLines);
    replaceRepairStages(
        repair,
        request.plan(),
        canonicalLines);
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
    enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        repair.getId(),
        repair.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        repair.getWarehouseId(), repair.getId(), repair.getCreatedAt());
    RepairResponse response = repairResponse(repair);
    idempotency.store(subjectId, "repair.direct", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public RepairResponse updateRepairPlan(UUID id, UpdateRepairPlanRequest request) {
    requireNoCallerTransaction("update a repair plan");
    WarehouseAdmissionPreflight<Void> preflight =
        inLocalTransaction(
            "repair plan update preflight", () -> updateRepairPlanPreflight(id, request));
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    requireCustomRoutingReady(preflight.warehouseId(), request.stages());
    return inLocalTransaction(
        "repair plan update finalization", () -> updateRepairPlanInTransaction(id, request));
  }

  private WarehouseAdmissionPreflight<Void> updateRepairPlanPreflight(
      UUID id, UpdateRepairPlanRequest request) {
    MaintenanceRepair initial = requireRepair(id);
    assertVersion(initial.getVersion(), request.expectedVersion());
    initial.requirePreStartAmendment();
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    validatePlan(
        request.stages(), request.lines() == null || request.lines().isEmpty());
    return new WarehouseAdmissionPreflight<>(initial.getWarehouseId(), null);
  }

  private RepairResponse updateRepairPlanInTransaction(
      UUID id, UpdateRepairPlanRequest request) {
    Map<MaintenanceEventStore.StreamRef, Long> locked =
        events.lockStreams(List.of(stream(id)));
    assertVersion(locked.get(stream(id)), request.expectedVersion());
    MaintenanceRepair repair =
        repairs.findAllByIdForUpdate(List.of(id)).stream()
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    assertStreamParity(repair, locked);
    repair.requirePreStartAmendment();
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    boolean updateRegisteredTask =
        repair.getExecutionState() == RepairExecutionState.QUEUED
            && repair.getTaskBoardVersion() != null;
    boolean synchronizeQueuedRepairStatus =
        repair.getExecutionState() == RepairExecutionState.QUEUED;
    List<EstimateLineResponse> canonicalLines =
        canonicalRepairLines(repair.getWarehouseId(), request.lines());
    validateUpdatedRepairLineMediaReferences(repair, canonicalLines);
    RepairComplexitySnapshot updatedComplexity =
        repairComplexityForLines(repair.getWarehouseId(), canonicalLines);
    boolean reclassifyingCapital =
        repair.getExecutionState() == RepairExecutionState.QUEUED
            && updatedComplexity.type() == RepairComplexity.CAPITAL;
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      repair.amendPreStartPlan();
    } else {
      repair.touchPlan();
    }
    if (reclassifyingCapital) {
      repair.markReclassifyingCapital();
    }
    repair.replaceCoverMediaId(request.coverMediaId());
    replaceRepairStages(
        repair,
        request.stages(),
        canonicalLines);
    replaceMedia(
        "REPAIR",
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        request.mediaReferences());
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    if (synchronizeQueuedRepairStatus) {
      enqueueRepairComplexityStatusSync(
          saved,
          stableOperationKey(
              "repair-complexity-status", saved.getId(), saved.getVersion()));
    }
    if (updateRegisteredTask && !reclassifyingCapital) {
      reconciliations.enqueue(
          saved.getId(),
          "TASK_BOARD",
          "UPDATE_TASK",
          stableOperationKey("update-task", saved.getId(), saved.getVersion()),
          Map.of("repairId", saved.getId().toString()));
    }
    events.append(
        MaintenanceAggregateType.REPAIR,
        id,
        request.expectedVersion(),
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        repairLocal(saved),
        repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
        repairSnapshot(saved));
    enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
    return repairResponse(saved);
  }

  public CreateResult<RepairCommandResult> queueRepair(
      UUID subjectId, UUID key, UUID id, QueueRepairRequest request) {
    requireNoCallerTransaction("queue a repair");
    QueueRepairCommandPreflight preflight =
        inLocalTransaction(
            "repair queue preflight", () -> queueRepairPreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    LeaseRefresh leaseRefresh =
        preflight.leasePlan() == null
            ? null
            : refreshLeaseForCommand(preflight.leasePlan(), key);
    return inLocalTransaction(
        "repair queue finalization",
        () -> queueRepairInTransaction(subjectId, key, id, request, preflight.leasePlan(), leaseRefresh));
  }

  private QueueRepairCommandPreflight queueRepairPreflight(
      UUID subjectId, UUID key, UUID id, QueueRepairRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.queue:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new QueueRepairCommandPreflight(
          new CreateResult<>(read(replay.get(), RepairCommandResult.class), true), null, null);
    }
    MaintenanceRepair repair = requireRepair(id);
    assertVersion(repair.getVersion(), request.expectedVersion());
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(id);
    if (stages.isEmpty()) {
      if (repair.getOrigin() != RepairOrigin.DIRECT_REPAIR
          || repair.getKind() != RepairKind.PRIMARY
          || repair.getExecutionState() != RepairExecutionState.DRAFT) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED",
            "Only an empty direct repair can be completed without planned stages");
      }
      return new QueueRepairCommandPreflight(null, repair.getWarehouseId(), null);
    }
    LeaseRefreshPlan leasePlan = null;
    if (repair.getKind() == RepairKind.REWORK) {
      MaintenanceRepair source =
          requireRepair(
              Objects.requireNonNull(
                  repair.getSourceRepairId(), "Rework repair source is required"));
      List<MaintenanceRepair> chain = sourceChain(repair);
      MaintenanceRepair root = leaseOwner(repair, chain);
      leasePlan = prepareLeaseRefreshPlan(root, List.of(source));
    }
    return new QueueRepairCommandPreflight(null, repair.getWarehouseId(), leasePlan);
  }

  private CreateResult<RepairCommandResult> queueRepairInTransaction(
      UUID subjectId,
      UUID key,
      UUID id,
      QueueRepairRequest request,
      LeaseRefreshPlan expectedLeasePlan,
      LeaseRefresh remoteLeaseRefresh) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.queue:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), RepairCommandResult.class), true);
    }
    MaintenanceRepair repair = requireRepair(id);
    assertVersion(repair.getVersion(), request.expectedVersion());
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(id);
    if (stages.isEmpty()) {
      if (repair.getOrigin() != RepairOrigin.DIRECT_REPAIR
          || repair.getKind() != RepairKind.PRIMARY
          || repair.getExecutionState() != RepairExecutionState.DRAFT) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED",
            "Only an empty direct repair can be completed without planned stages");
      }
      Map<MaintenanceEventStore.StreamRef, Long> locked =
          events.lockStreams(List.of(stream(id)));
      assertVersion(locked.get(stream(id)), request.expectedVersion());
      repair =
          repairs.findAllByIdForUpdate(List.of(id)).stream()
              .findFirst()
              .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
      assertStreamParity(repair, locked);
      repair.selectPriority(request.priority());
      repair.selectMovementToRepair(
          request.movementToRepair(),
          request.logisticsPlanningMode(), request.logisticsScheduledDate());
      repair.queueUnderExistingRepair();
      repair.applyExternalTaskCancellation();
      repair.markReconciliationRequired();
      MaintenanceRepair saved = repairs.saveAndFlush(repair);
      events.append(
          MaintenanceAggregateType.REPAIR,
          id,
          request.expectedVersion(),
          MaintenanceEventType.REPAIR_PLAN_CHANGED,
          repairLocal(saved),
          repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
          repairSnapshot(saved));
      enqueueTerminalAsset(
          saved,
          "EMPTY_REPAIR_TO_FREE",
          stableOperationKey("complete-empty-repair", saved.getId(), 0));
      RepairCommandResult response =
          new RepairCommandResult(repairResponse(saved), List.of(), delivery(saved));
      idempotency.store(subjectId, "repair.queue:" + id, key, requestHash, 200, response);
      return new CreateResult<>(response, false);
    }
    MaintenanceRepair saved;
    if (repair.getKind() == RepairKind.REWORK) {
      LockedRework lockedRework = lockAndReloadRework(repair, request.expectedVersion());
      repair = lockedRework.rework();
      MaintenanceRepair root = lockedRework.root();
      MaintenanceRepair source = lockedRework.source();
      if (expectedLeasePlan == null || remoteLeaseRefresh == null) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT",
            "Rework repair lease refresh was not prepared");
      }
      requireMatchingLeaseRefreshPlan(expectedLeasePlan, root, List.of(source));
      LeaseRefresh leaseRefresh = remoteLeaseRefresh;
      if (leaseRefresh.ownerRenewed() && !root.getId().equals(source.getId())) {
        long rootExpectedVersion = lockedRework.streamVersions().get(stream(root.getId()));
        applyLeaseSnapshot(root, leaseRefresh.lease());
        MaintenanceRepair renewedRoot = repairs.saveAndFlush(root);
        MaintenanceEventType renewalEvent = renewalEvent(renewedRoot);
        events.append(
            MaintenanceAggregateType.REPAIR,
            renewedRoot.getId(),
            rootExpectedVersion,
            renewalEvent,
            repairLocal(renewedRoot),
            repairFact(renewalEvent, renewedRoot),
            repairSnapshot(renewedRoot));
      }
      MaintenanceDependencyGateway.LeaseSnapshot lease = leaseRefresh.lease();
      long sourceExpectedVersion = lockedRework.streamVersions().get(stream(source.getId()));
      applyLeaseSnapshot(source, lease);
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
      RepairComplexitySnapshot complexity =
          repairComplexityFromStoredStages(repair.getWarehouseId(), repair.getId());
      prepareStagesForQueue(stages, complexity.type() == RepairComplexity.CAPITAL);
      repairStages.saveAllAndFlush(stages);
      repair.selectPriority(request.priority());
      repair.selectMovementToRepair(
          request.movementToRepair(),
          request.logisticsPlanningMode(), request.logisticsScheduledDate());
      if (complexity.type() == RepairComplexity.CAPITAL) {
        repair.queueExternalCapital(
            lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
      } else {
        repair.queue(lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
      }
      saved = repairs.saveAndFlush(repair);
      if (complexity.type() != RepairComplexity.CAPITAL) {
        enqueueOrdinaryRepairExecution(
            saved,
            derived(key, "task-register"),
            derived(key, "driver-logistics-task"));
      }
      enqueueRepairComplexityStatusSync(
          saved, derived(key, "repair-complexity-status"));
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
      boolean priorityChanged = repair.getPriority() != request.priority();
      repair.selectPriority(request.priority());
      boolean inboundMovementChanged =
          repair.selectMovementToRepair(
              request.movementToRepair(),
              request.logisticsPlanningMode(),
              request.logisticsScheduledDate());
      saved =
          priorityChanged || inboundMovementChanged
              ? repairs.saveAndFlush(repair)
              : repair;
      if (priorityChanged || inboundMovementChanged) {
        events.append(
            MaintenanceAggregateType.REPAIR,
            id,
            request.expectedVersion(),
            MaintenanceEventType.REPAIR_PLAN_CHANGED,
            repairLocal(saved),
            repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
            repairSnapshot(saved));
      }
      UUID queueKey = stableOperationKey("queue-repair", saved.getId(), 0);
      reconciliations.resumeStableQuarantined(
          saved.getId(),
          "ASSET",
          "QUEUE_REPAIR",
          queueKey,
          subjectId,
          "Authenticated repair queue retry after canonical asset revalidation");
      enqueueRepairQueue(saved, queueKey, false);
    }
    List<RepairResponse> affected = saved.getSourceRepairId() == null
        ? List.of() : List.of(repairResponse(requireRepair(saved.getSourceRepairId())));
    RepairCommandResult response = new RepairCommandResult(
        repairResponse(saved), affected, delivery(saved));
    idempotency.store(subjectId, "repair.queue:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  /**
   * Reviewed recovery for a single stable inbound logistics intent. Remote logistics truth is
   * deliberately read between two short local transactions: no HTTP call can hold the repair or
   * event-stream locks, while the second transaction fences the local state before mutation.
   */
  public CreateResult<RepairCommandResult> retryInboundDelivery(
      UUID subjectId,
      UUID key,
      UUID id,
      UUID warehouseId,
      RetryInboundDeliveryRequest request) {
    requireNoCallerTransaction("retry inbound delivery");
    validateInboundDeliveryRetryRequest(request);
    String scope = "repair.inbound-delivery-retry:" + id;
    String requestHash = hash(request);

    CreateResult<RepairCommandResult> replay =
        transactions.execute(
            status -> {
              Optional<JsonNode> stored = idempotency.replay(subjectId, scope, key, requestHash);
              if (stored.isPresent()) {
                return new CreateResult<>(read(stored.get(), RepairCommandResult.class), true);
              }
              MaintenanceRepair initial =
                  repairs
                      .findByIdAndWarehouseId(id, warehouseId)
                      .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
              assertVersion(initial.getVersion(), request.expectedVersion());
              return null;
            });
    if (replay != null) return replay;

    warehouseLifecycle.requireOutgoing(warehouseId);

    requireAbsentInboundDriverTask(id);

    CreateResult<RepairCommandResult> result =
        transactions.execute(
            status -> {
              Optional<JsonNode> stored = idempotency.replay(subjectId, scope, key, requestHash);
              if (stored.isPresent()) {
                return new CreateResult<>(read(stored.get(), RepairCommandResult.class), true);
              }
              Map<MaintenanceEventStore.StreamRef, Long> locked =
                  events.lockStreams(List.of(stream(id)));
              assertVersion(locked.get(stream(id)), request.expectedVersion());
              MaintenanceRepair repair =
                  repairs.findAllByIdForUpdate(List.of(id)).stream()
                      .findFirst()
                      .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
              if (!warehouseId.equals(repair.getWarehouseId())) {
                throw new MaintenanceNotFoundException("Repair not found");
              }
              assertVersion(repair.getVersion(), request.expectedVersion());
              assertStreamParity(repair, locked);

              UUID driverTaskKey = stableOperationKey("driver-logistics-task", repair.getId(), 0);
              boolean resumed =
                  reconciliations.resumeStableQuarantined(
                      repair.getId(),
                      "LOGISTICS",
                      "CREATE_DRIVER_TASK",
                      driverTaskKey,
                      subjectId,
                      request.reason());
              if (!resumed) {
                throw new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT",
                    "The exact inbound delivery reconciliation is not quarantined");
              }
              try {
                repair.retryQuarantinedInboundDelivery(
                    request.logisticsPlanningMode(), request.logisticsScheduledDate());
              } catch (IllegalStateException exception) {
                throw new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT", exception.getMessage());
              }
              MaintenanceRepair saved = repairs.saveAndFlush(repair);
              events.append(
                  MaintenanceAggregateType.REPAIR,
                  saved.getId(),
                  request.expectedVersion(),
                  MaintenanceEventType.REPAIR_PLAN_CHANGED,
                  repairLocal(saved),
                  repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
                  repairSnapshot(saved));
              RepairCommandResult response =
                  new RepairCommandResult(repairResponse(saved), List.of(), delivery(saved));
              idempotency.store(subjectId, scope, key, requestHash, 200, response);
              return new CreateResult<>(response, false);
            });
    if (result == null) {
      throw new IllegalStateException("Inbound delivery retry transaction was empty");
    }
    return result;
  }

  public CreateResult<RepairCommandResult> queueRepair(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    requireNoCallerTransaction("queue a repair");
    return queueRepair(subjectId, key, id, new QueueRepairRequest(request.expectedVersion(), 3));
  }

  public CreateResult<RepairResponse> createRework(
      UUID subjectId, UUID key, UUID sourceId, CreateReworkRequest request) {
    requireNoCallerTransaction("create a rework repair");
    WarehouseAdmissionPreflight<CreateResult<RepairResponse>> preflight =
        inLocalTransaction(
            "rework repair preflight",
            () -> createReworkPreflight(subjectId, key, sourceId, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return inLocalTransaction(
        "rework repair finalization",
        () -> createReworkInTransaction(subjectId, key, sourceId, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<RepairResponse>> createReworkPreflight(
      UUID subjectId, UUID key, UUID sourceId, CreateReworkRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.rework:" + sourceId, key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(read(replay.get(), RepairResponse.class), true));
    }
    MaintenanceRepair initialSource = requireRepair(sourceId);
    assertVersion(initialSource.getVersion(), request.expectedVersion());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    validatePlan(
        request.plan(), request.lines() == null || request.lines().isEmpty());
    return new WarehouseAdmissionPreflight<>(initialSource.getWarehouseId(), null);
  }

  private CreateResult<RepairResponse> createReworkInTransaction(
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
    if (hasUnresolvedRework(sourceId)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "An active sibling rework already exists");
    }
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceRepair childDraft =
        MaintenanceRepair.rework(source, request.reason(), actorJson());
    childDraft.replaceCoverMediaId(request.coverMediaId());
    MaintenanceRepair child = repairs.saveAndFlush(childDraft);
    List<EstimateLineResponse> canonicalLines =
        canonicalReworkLines(source, request.lines());
    validateLineMediaReferences(
        "MAINTENANCE_REPAIR",
        child.getId(),
        child.getWarehouseId(),
        canonicalLines.stream()
            .filter(line -> line.disposition() == ReworkLineDisposition.ADDED)
            .toList());
    replaceRepairStages(
        child,
        request.plan(),
        canonicalLines);
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
    enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        child.getId(),
        child.getWarehouseId(),
        child.getId(),
        child.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        child.getWarehouseId(), child.getId(), child.getCreatedAt());
    RepairResponse response = repairResponse(child);
    idempotency.store(subjectId, "repair.rework:" + sourceId, key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public CreateResult<RepairCommandResult> accept(
      UUID subjectId, UUID key, UUID id, RepairDecisionRequest request) {
    requireNoCallerTransaction("accept a repair");
    AcceptanceCommandPreflight preflight =
        inLocalTransaction(
            "repair acceptance preflight", () -> acceptPreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    LeaseRefresh leaseRefresh = refreshLeaseForCommand(preflight.leasePlan(), key);
    return inLocalTransaction(
        "repair acceptance finalization",
        () -> acceptInTransaction(subjectId, key, id, request, preflight.leasePlan(), leaseRefresh));
  }

  private AcceptanceCommandPreflight acceptPreflight(
      UUID subjectId, UUID key, UUID id, RepairDecisionRequest request) {
    if (request.mediaReferences() == null || request.mediaReferences().isEmpty()) {
      throw invalid("At least one acceptance photo is required");
    }
    String requestHash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "repair.accept:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new AcceptanceCommandPreflight(
          new CreateResult<>(read(replay.get(), RepairCommandResult.class), true), null, null);
    }
    MaintenanceRepair initial = requireRepair(id);
    assertVersion(initial.getVersion(), request.expectedVersion());
    List<MaintenanceRepair> sources = sourceChain(initial);
    requireNoActiveRework(initial);
    return new AcceptanceCommandPreflight(
        null,
        initial.getWarehouseId(),
        prepareLeaseRefreshPlan(initial, sources));
  }

  private CreateResult<RepairCommandResult> acceptInTransaction(
      UUID subjectId,
      UUID key,
      UUID id,
      RepairDecisionRequest request,
      LeaseRefreshPlan expectedLeasePlan,
      LeaseRefresh leaseRefresh) {
    if (request.mediaReferences() == null || request.mediaReferences().isEmpty()) {
      throw invalid("At least one acceptance photo is required");
    }
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
    requireMatchingLeaseRefreshPlan(expectedLeasePlan, repair, sourceChain);
    replaceMedia(
        "ACCEPTANCE",
        "MAINTENANCE_ACCEPTANCE",
        repair.getId(),
        repair.getWarehouseId(),
        request.mediaReferences());
    applyLeaseSnapshot(repair, leaseRefresh.lease());
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
    inventorySuccessors.releaseAfterAcceptance(
        saved, lastRepairEventId(saved.getId()), saved.getDecisionRecordedAt());
    enqueueMediaOwnerProof(
        "MAINTENANCE_ACCEPTANCE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
    cascadeTerminal(saved, sourceChain, true, leaseRefresh.lease());
    enqueueAcceptedCharacteristics(saved, sourceChain);
    enqueueTerminalAsset(saved, "ACCEPT_TO_FREE", stableOperationKey(
        "accept-asset", saved.getId(), saved.getVersion()));
    RepairCommandResult response = new RepairCommandResult(
        repairResponse(saved), sourceChain.stream().map(this::repairResponse).toList(), delivery(saved));
    idempotency.store(subjectId, "repair.accept:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  @Transactional(readOnly = true)
  public List<AcceptanceProjection> acceptance(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream()
        .filter(value -> value.getAcceptanceState() == RepairAcceptanceState.PENDING
            || value.getAcceptanceState() == RepairAcceptanceState.IN_REWORK)
        // A source repair is not actionable while a child rework is still being
        // planned, executed or awaiting its own acceptance. Returning both
        // chain nodes made clients offer a terminal action that must be rejected.
        .filter(value -> !hasUnresolvedRework(value.getId()))
        .map(value -> new AcceptanceProjection(
            value.getId(), rootId(value), value.getWarehouseId(), value.getRentalItemId(),
            value.getExecutionState(), value.getAcceptanceState(), value.getVersion(), value.getUpdatedAt()))
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
    Optional<RentalItemFactProjection> current = rentalItemFacts.findById(rentalItemId);
    if (current.isEmpty() && (warehouseId == null || status == null)) {
      throw new IllegalStateException(
          "Partial rental-item fact cannot initialize the maintenance projection");
    }
    RentalItemFactProjection fact = current.orElseGet(() ->
        RentalItemFactProjection.create(rentalItemId, warehouseId, status, aggregateVersion));
    if (fact.getAggregateVersion() < aggregateVersion) {
      fact.apply(
          warehouseId == null ? fact.getWarehouseId() : warehouseId,
          status == null ? fact.getAssetStatus() : status,
          aggregateVersion);
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
    if (eventType.endsWith("cancelled.v1")
        && prestartReplacementGuard.ownsTaskCancellation(initial.getId(), externalTaskId)) {
      // cancel-if-pre-start can publish before the coordinator has persisted compensation or
      // finalized the replacement. Keep the event as corroborating external audit truth; V31
      // will atomically mirror its cancellation together with the successor/source outcome.
      return;
    }
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
        repairPlaces.completeAfterRepair(
            repair.getWarehouseId(), repair.getId(), repair.isMovementToRepair());
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
      } else if (repair.isQueuedWithoutOperationLease()) {
        repair.markReconciled();
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
    if (maintenanceEvent == MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE) {
      reconciliations.enqueue(
          saved.getId(),
          "ASSET",
          "MATERIALIZE_FURNITURE_CUSTODY",
          stableOperationKey("materialize-furniture-custody", saved.getId(), 0),
          Map.of("repairId", saved.getId().toString()));
      inventorySuccessors.releaseAfterTaskBoardCompletion(saved, eventId, occurredAt);
      enqueueMediaOwnerProof(
          "MAINTENANCE_ACCEPTANCE",
          saved.getId(),
          saved.getWarehouseId(),
          saved.getId(),
          saved.getVersion(),
          true);
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void applyInboundTaskSchedule(
      UUID externalTaskId, LocalDate scheduledDate, long taskBoardVersion) {
    MaintenanceRepair initial = repairs.findByExternalTaskId(externalTaskId).orElseThrow(() ->
        new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Task-board fact has no maintenance repair owner"));
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        List.of(stream(initial.getId())));
    long expectedVersion = locked.get(stream(initial.getId()));
    assertVersion(expectedVersion, initial.getVersion());
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(initial.getId())).stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    assertStreamParity(repair, locked);
    if (!repair.synchronizeTaskBoardSchedule(scheduledDate, taskBoardVersion)) return;
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        repairLocal(saved),
        repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
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
    enqueueMediaOwnerProof(
        "MAINTENANCE_ACCEPTANCE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
  }

  public boolean reconcileOneTask() {
    Optional<MaintenanceReconciliationStore.WorkItem> candidate =
        reconciliations.claimNextDue(RECONCILIATION_CLAIM_LEASE);
    if (candidate.isEmpty()) {
      Boolean enqueued = transactions.execute(status -> enqueueDueLeaseRenewal());
      return Boolean.TRUE.equals(enqueued);
    }
    MaintenanceReconciliationStore.WorkItem work = candidate.orElseThrow();
    try {
      reconcileClaimedTask(work);
      return true;
    } catch (RuntimeException exception) {
      recordClaimedTaskFailure(work, exception);
      return true;
    }
  }

  public boolean reconcileOneMediaOwnerProof() {
    Optional<MaintenanceReconciliationStore.WorkItem> candidate =
        reconciliations.claimNextDueMedia(RECONCILIATION_CLAIM_LEASE);
    if (candidate.isEmpty()) return false;
    MaintenanceReconciliationStore.WorkItem work = candidate.orElseThrow();
    try {
      // The durable claim committed above; no local transaction or row lock is held while
      // media-service is called.
      MaintenanceDependencyGateway.MediaOwnerProof proof = mediaProof(work);
      MaintenanceDependencyGateway.MediaOwnerProof response =
          dependencies.upsertMediaOwnerProof(proof);
      if (!proof.equals(response)) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Media-service returned mismatched owner proof truth");
      }
      transactions.executeWithoutResult(status -> reconciliations.confirmed(work, response));
      return true;
    } catch (RuntimeException exception) {
      try {
        transactions.executeWithoutResult(status -> reconciliations.failed(work, exception));
      } catch (RuntimeException staleClaim) {
        if (!MaintenanceReconciliationStore.isStaleClaim(staleClaim)) throw staleClaim;
      }
      return true;
    }
  }

  private void reconcileClaimedTask(MaintenanceReconciliationStore.WorkItem work) {
    switch (work.operation()) {
      case "QUEUE_REPAIR" -> reconcileQueuedRepairClaim(work);
      case "MATERIALIZE_FURNITURE_CUSTODY" -> reconcileFurnitureCustodyClaim(work);
      case "COMPLETE_EMPTY_ESTIMATE" -> reconcileEmptyEstimateClaim(work);
      case "REGISTER_TASK" -> reconcileTaskClaim(work, false);
      case "UPDATE_TASK" -> reconcileTaskClaim(work, true);
      case "CREATE_DRIVER_TASK" -> reconcileDriverLogisticsTaskClaim(work);
      case "SYNC_REPAIR_COMPLEXITY_STATUS" -> reconcileRepairComplexityStatusClaim(work);
      case "APPLY_CHARACTERISTIC" -> reconcileAcceptedCharacteristicClaim(work);
      case "REGISTER_CATALOG_POSITION" -> reconcileCatalogPositionRegistrationClaim(work);
      case "DELETE_CATALOG_POSITION" -> reconcileCatalogPositionDeletionClaim(work);
      case "EMPTY_REPAIR_TO_FREE", "ACCEPT_TO_FREE", "WRITE_OFF", "PENDING_ACCEPTANCE" ->
          reconcileAssetTransitionClaim(work);
      case "RENEW_LEASE" -> reconcileLeaseRenewalClaim(work);
      default -> throw new IllegalStateException(
          "Unsupported maintenance reconciliation operation " + work.operation());
    }
  }

  private void recordClaimedTaskFailure(
      MaintenanceReconciliationStore.WorkItem work, RuntimeException exception) {
    try {
      transactions.executeWithoutResult(
          status -> {
            boolean quarantined = reconciliations.failed(work, exception);
            recordReconciliationFailure(work, quarantined);
          });
    } catch (RuntimeException staleClaim) {
      // The claim may have expired while a remote dependency was slow. A newer worker owns
      // the durable outcome; never turn that harmless race into another failed attempt.
      if (!MaintenanceReconciliationStore.isStaleClaim(staleClaim)) throw staleClaim;
    }
  }

  private static MaintenanceDependencyGateway.MediaOwnerProof mediaProof(
      MaintenanceReconciliationStore.WorkItem work) {
    if (!"MEDIA".equals(work.dependency())
        || !"UPSERT_MEDIA_OWNER_PROOF".equals(work.operation())
        || work.mediaOwnerType() == null
        || work.mediaOwnerId() == null
        || work.mediaWarehouseId() == null
        || work.mediaOwnerRevision() == null
        || work.mediaAggregateVersion() == null
        || work.mediaProofEventId() == null
        || work.mediaActive() == null) {
      throw new IllegalStateException("Stored media owner proof is incomplete");
    }
    return new MaintenanceDependencyGateway.MediaOwnerProof(
        work.mediaOwnerType(),
        work.mediaOwnerId(),
        work.mediaWarehouseId(),
        work.mediaOwnerRevision(),
        work.mediaAggregateVersion(),
        work.mediaProofEventId(),
        work.mediaActive());
  }

  private void reconcileAcceptedCharacteristicClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    CharacteristicPlan plan = requireReconciliationResult(
        transactions.execute(status -> prepareCharacteristicPlan(work)));
    MaintenanceDependencyGateway.AppliedCabinCharacteristic applied =
        dependencies.applyCabinCharacteristic(
            work.idempotencyKey(), plan.rentalItemId(), plan.characteristicId());
    validateAppliedCharacteristicTruth(plan, applied);
    transactions.executeWithoutResult(
        status -> {
          CharacteristicPlan current = prepareCharacteristicPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Accepted-characteristic reconciliation changed before finalization");
          }
          reconciliations.confirmed(
              work,
              Map.of(
                  "rentalItemId", applied.rentalItemId().toString(),
                  "characteristicId", applied.characteristicId().toString(),
                  "added", applied.added(),
                  "rentalItemVersion", applied.rentalItemVersion()));
        });
  }

  private CharacteristicPlan prepareCharacteristicPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = requireWorkRepair(work);
    UUID rentalItemId = uuidField(work.payload(), "rentalItemId");
    UUID characteristicId = uuidField(work.payload(), "characteristicId");
    UUID rootRepairId = uuidField(work.payload(), "rootRepairId");
    if (!"ASSET".equals(work.dependency())
        || !"APPLY_CHARACTERISTIC".equals(work.operation())
        || !repair.getRentalItemId().equals(rentalItemId)
        || !rootId(repair).equals(rootRepairId)
        || repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED) {
      throw new IllegalStateException("Stored accepted-characteristic reconciliation is incomplete");
    }
    return new CharacteristicPlan(
        rentalItemId, characteristicId, rootRepairId, repair.getRentalItemVersionSnapshot());
  }

  private static void validateAppliedCharacteristicTruth(
      CharacteristicPlan plan,
      MaintenanceDependencyGateway.AppliedCabinCharacteristic applied) {
    if (applied == null
        || !plan.rentalItemId().equals(applied.rentalItemId())
        || !plan.characteristicId().equals(applied.characteristicId())
        || applied.rentalItemVersion() < plan.minimumRentalItemVersion()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service returned mismatched cabin characteristic truth");
    }
  }

  private void reconcileCatalogPositionRegistrationClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    if (!catalogPositionReadyInClaim(work)) return;
    MaintenanceDependencyGateway.CatalogPositionReference reference =
        dependencies.registerCatalogPosition(
            work.catalogQueueId(), work.catalogExternalReferenceId());
    validateCatalogPositionReferenceTruth(work, reference);
    transactions.executeWithoutResult(
        status -> {
          requireCatalogPositionWork(work, "REGISTER_CATALOG_POSITION");
          reconciliations.confirmed(
              work,
              Map.of(
                  "catalogVersionId", work.catalogVersionId().toString(),
                  "catalogNodeId", work.catalogNodeId().toString(),
                  "queueDefinitionId", reference.queueDefinitionId().toString(),
                  "externalReferenceId", reference.externalReferenceId(),
                  "referenceId", reference.id().toString(),
                  "referenceVersion", reference.version()));
        });
  }

  private void reconcileCatalogPositionDeletionClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    if (!catalogPositionReadyInClaim(work)) return;
    dependencies.deleteCatalogPosition(work.catalogExternalReferenceId(), 0L);
    transactions.executeWithoutResult(
        status -> {
          requireCatalogPositionWork(work, "DELETE_CATALOG_POSITION");
          reconciliations.confirmed(
              work,
              Map.of(
                  "catalogVersionId", work.catalogVersionId().toString(),
                  "catalogNodeId", work.catalogNodeId().toString(),
                  "queueId", work.catalogQueueId().toString(),
                  "externalReferenceId", work.catalogExternalReferenceId(),
                  "deletedReferenceVersion", 0L));
        });
  }

  private static void validateCatalogPositionReferenceTruth(
      MaintenanceReconciliationStore.WorkItem work,
      MaintenanceDependencyGateway.CatalogPositionReference reference) {
    if (reference == null
        || !work.catalogQueueId().equals(reference.queueDefinitionId())
        || !work.catalogExternalReferenceId().equals(reference.externalReferenceId())
        || !"CATALOG_POSITION".equals(reference.type())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned mismatched catalog-position truth");
    }
  }

  private boolean catalogPositionReadyInClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    Boolean ready = transactions.execute(
        status -> {
          requireCatalogPositionWork(work, work.operation());
          List<UUID> predecessorKeys = uuidListField(work.payload(), "predecessorKeys");
          if (reconciliations.allCatalogPositionPredecessorsConfirmed(predecessorKeys)) {
            return true;
          }
          reconciliations.defer(work, Duration.ofSeconds(5));
          return false;
        });
    return Boolean.TRUE.equals(ready);
  }

  private void reconcileDriverLogisticsTaskClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    DriverTaskPlan plan = requireReconciliationResult(
        transactions.execute(status -> prepareDriverTaskPlan(work)));
    MaintenanceDependencyGateway.DriverTaskSnapshot task =
        dependencies.createDriverTask(work.idempotencyKey(), plan.command());
    validateDriverTaskTruth(plan.command(), task);
    transactions.executeWithoutResult(
        status -> {
          DriverTaskPlan current = prepareDriverTaskPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Driver-task reconciliation changed before finalization");
          }
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", plan.repairId().toString(),
                  "driverTaskId", task.id().toString(),
                  "driverTaskVersion", task.version(),
                  "state", task.state()));
        });
  }

  private DriverTaskPlan prepareDriverTaskPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    if (!"LOGISTICS".equals(work.dependency())
        || !"DELIVER_TO_REPAIR".equals(work.payload().path("kind").asText())) {
      throw new IllegalStateException("Stored maintenance driver-task intent is invalid");
    }
    MaintenanceRepair repair = requireWorkRepair(work);
    if (prestartReplacementGuard.blocksRepairExecution(repair.getId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair is blocked by a pre-start replacement");
    }
    if (repair.getExecutionState() != RepairExecutionState.QUEUED
        || repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only queued ordinary repair work can create a delivery task");
    }
    if (!repair.isMovementToRepair()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair no longer requires delivery to a repair place");
    }
    String sourceType;
    UUID sourceId;
    var inventorySource = inventorySources.findByRepairId(repair.getId()).orElse(null);
    if (inventorySource != null) {
      sourceType = "INVENTORY";
      sourceId = inventorySource.getFindingId();
    } else if (repair.getEstimateId() != null) {
      sourceType = "ESTIMATE";
      sourceId = repair.getEstimateId();
    } else {
      sourceType = "REPAIR";
      sourceId = repair.getId();
    }
    return new DriverTaskPlan(
        repair.getId(),
        new MaintenanceDependencyGateway.DriverTaskCommand(
            repair.getWarehouseId(),
            repair.getRentalItemId(),
            repair.getId(),
            sourceType,
            sourceId,
            "DELIVER_TO_REPAIR",
            repair.getLogisticsPlanningMode(),
            repair.getLogisticsScheduledDate(),
            repair.getPriority(),
            false));
  }

  private static void validateDriverTaskTruth(
      MaintenanceDependencyGateway.DriverTaskCommand command,
      MaintenanceDependencyGateway.DriverTaskSnapshot task) {
    if (task == null
        || task.id() == null
        || task.version() < 0
        || !command.warehouseId().equals(task.warehouseId())
        || !command.cabinId().equals(task.cabinId())
        || !command.repairId().equals(task.repairId())
        || !command.sourceType().equals(task.sourceType())
        || !command.sourceId().equals(task.sourceId())
        || !command.kind().equals(task.kind())
        || command.planningMode() != task.planningMode()
        || (command.planningMode() == RepairLogisticsPlanningMode.FIXED_DATE
            && !command.scheduledDate().equals(task.scheduledDate()))
        || command.priority() != task.priority()
        || !Set.of("REGISTERING", "SCHEDULED", "CURRENT", "FINALIZING", "COMPLETED")
            .contains(task.state())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Logistics-service returned mismatched driver-task truth");
    }
  }

  private void reconcileFurnitureCustodyClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    FurnitureCustodyPlan plan = requireReconciliationResult(
        transactions.execute(status -> prepareFurnitureCustodyPlan(work)));
    if (plan.furnitureAccountingMode() == FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS) {
      warehouseLifecycle.requireOutgoing(plan.warehouseId());
      transactions.executeWithoutResult(
          status -> {
            FurnitureCustodyPlan current = prepareFurnitureCustodyPlan(work);
            if (!plan.equals(current)) {
              throw new MaintenanceConflictException(
                  "MAINTENANCE_STATE_CONFLICT",
                  "Unaccounted furniture reconciliation changed before finalization");
            }
            int created = propertyDispositions.materializeUnaccountedFurnitureLocally(
                requireRepair(current.repairId()),
                current.equipmentNames(),
                current.equipmentQuantities());
            reconciliations.confirmed(
                work,
                Map.of(
                    "repairId", current.repairId().toString(),
                    "accountingMode", current.furnitureAccountingMode().name(),
                    "createdDecisionCount", created));
          });
      return;
    }
    List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims =
        dependencies.unresolvedFurnitureCustody(plan.ownerType(), plan.ownerId());
    // Property disposition admission is another remote warehouse-service read. It is deliberately
    // completed before final local locks and before the local-only materializer below.
    warehouseLifecycle.requireOutgoing(plan.warehouseId());
    transactions.executeWithoutResult(
        status -> {
          FurnitureCustodyPlan current = prepareFurnitureCustodyPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Furniture-custody reconciliation changed before finalization");
          }
          validateFurnitureCustodyClaims(current, claims);
          int created = propertyDispositions.materializeFurnitureCustodyLocally(
              requireRepair(current.repairId()), current.equipmentNames(), claims);
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", current.repairId().toString(),
                  "ownerType", current.ownerType(),
                  "ownerId", current.ownerId().toString(),
                  "claimCount", claims.size(),
                  "createdDecisionCount", created));
        });
  }

  private FurnitureCustodyPlan prepareFurnitureCustodyPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = requireWorkRepair(work);
    if (repair.getAcceptanceState() != RepairAcceptanceState.PENDING) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Furniture custody can be materialized only after repair completion");
    }
    return new FurnitureCustodyPlan(
        repair.getId(),
        repair.getWarehouseId(),
        repair.getRentalItemId(),
        ownerType(repair),
        UUID.fromString(ownerId(repair)),
        repair.getFurnitureAccountingMode(),
        Map.copyOf(furnitureEquipmentNames(repair)),
        furnitureQuantities(repair).stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                FurnitureQuantity::equipmentId, FurnitureQuantity::quantity)));
  }

  private static void validateFurnitureCustodyClaims(
      FurnitureCustodyPlan plan,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    if (claims == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not return furniture custody truth");
    }
    for (MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim claim : claims) {
      if (!plan.warehouseId().equals(claim.warehouseId())
          || !plan.rentalItemId().equals(claim.rentalItemId())
          || !plan.ownerType().equals(claim.ownerType())
          || !plan.ownerId().equals(claim.ownerId())) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Asset-service returned furniture custody for another repair asset");
      }
    }
  }

  private void reconcileEmptyEstimateClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    EmptyEstimatePlan plan = requireReconciliationResult(
        transactions.execute(status -> prepareEmptyEstimatePlan(work)));
    MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.acquireLease(
        derived(work.idempotencyKey(), "acquire"),
        plan.rentalItemId(),
        plan.rentalItemVersion(),
        plan.ownerType(),
        plan.ownerId());
    validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    if (!leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          derived(work.idempotencyKey(), "renew"),
          lease.leaseId(),
          lease.version(),
          lease.fencingToken(),
          plan.ownerType(),
          plan.ownerId());
      validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    }
    requireFreshDependencyLease(lease);
    MaintenanceDependencyGateway.AssetSnapshot asset = dependencies.fencedStatus(
        derived(work.idempotencyKey(), "status"),
        plan.rentalItemId(),
        plan.warehouseId(),
        plan.rentalItemVersion(),
        lease.leaseId(),
        lease.fencingToken(),
        plan.ownerType(),
        plan.ownerId(),
        "EMPTY_ESTIMATE_TO_FREE",
        false);
    validateEstimateAssetTruth(plan, asset);
    dependencies.releaseLease(
        derived(work.idempotencyKey(), "release"),
        lease.leaseId(),
        lease.version(),
        lease.fencingToken(),
        plan.ownerType(),
        plan.ownerId());
    MaintenanceDependencyGateway.LeaseSnapshot finalLease = lease;
    transactions.executeWithoutResult(
        status -> {
          EmptyEstimatePlan current = prepareEmptyEstimatePlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Empty-estimate reconciliation changed before finalization");
          }
          validateEstimateAssetTruth(current, asset);
          reconciliations.confirmed(
              work,
              Map.of(
                  "estimateId", current.estimateId().toString(),
                  "rentalItemVersion", asset.version(),
                  "leaseReleased", true,
                  "leaseId", finalLease.leaseId().toString()));
        });
  }

  private EmptyEstimatePlan prepareEmptyEstimatePlan(
      MaintenanceReconciliationStore.WorkItem work) {
    UUID estimateId = uuidField(work.payload(), "estimateId");
    MaintenanceEstimate estimate = requireEstimate(estimateId);
    if (estimate.getState() != EstimateState.COMPLETED || estimate.getRepairId() != null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Estimate is no longer an empty completed estimate");
    }
    return new EmptyEstimatePlan(
        estimate.getId(),
        estimate.getWarehouseId(),
        estimate.getRentalItemId(),
        estimate.getRentalItemVersionSnapshot(),
        "MAINTENANCE_ESTIMATE",
        estimate.getId().toString());
  }

  private static void validateEstimateAssetTruth(
      EmptyEstimatePlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() <= plan.rentalItemVersion()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not advance the expected rental item");
    }
  }

  private void reconcileTaskClaim(
      MaintenanceReconciliationStore.WorkItem work, boolean update) {
    Optional<TaskPlan> prepared = transactions.execute(status -> prepareTaskPlan(work, update));
    if (prepared == null || prepared.isEmpty()) return;
    TaskPlan plan = prepared.orElseThrow();

    // Both the canonical cabin read and the task-board mutation are deliberately outside any
    // maintenance transaction. The task command retains its stable reconciliation key.
    MaintenanceDependencyGateway.AssetSnapshot asset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateTaskAssetTruth(plan, asset);
    LocalDate scheduledDate = plan.resolveDeliveredLocalDate()
        ? warehouseLifecycle.localDateAt(
            plan.warehouseId(), OffsetDateTime.now(java.time.ZoneOffset.UTC))
        : plan.scheduledDate();
    int priority = plan.resolveDeliveredLocalDate()
        ? DELIVERED_REPAIR_TASK_BOARD_PRIORITY
        : plan.priority();
    MaintenanceDependencyGateway.TaskSnapshot task = update
        ? dependencies.updatePreStartTask(
            work.idempotencyKey(),
            plan.externalTaskId(),
            plan.expectedTaskVersion(),
            asset.number(),
            plan.stages())
        : dependencies.registerTask(
            work.idempotencyKey(),
            plan.externalTaskId(),
            plan.repairId(),
            plan.warehouseId(),
            plan.rentalItemId(),
            asset.number(),
            scheduledDate,
            priority,
            plan.dailyCapacity(),
            plan.stages());
    validateTaskPlanTruth(plan, task);

    transactions.executeWithoutResult(
        status -> {
          Optional<TaskPlan> current = prepareTaskPlan(work, update);
          if (current == null || current.isEmpty()) return;
          if (!plan.equals(current.orElseThrow())) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT", "Task reconciliation changed before finalization");
          }
          MaintenanceRepair repair = requireRepair(plan.repairId());
          long expectedVersion =
              events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
          assertVersion(repair.getVersion(), expectedVersion);
          validateTaskTruth(repair, task, plan.stages().size());
          confirmTaskRegistration(repair.getId(), task);
          repair.markTaskGenerated(task.version());
          repair.markReconciled();
          MaintenanceRepair saved = repairs.saveAndFlush(repair);
          MaintenanceEventType eventType =
              update ? MaintenanceEventType.REPAIR_PLAN_CHANGED : MaintenanceEventType.REPAIR_QUEUED;
          events.append(
              MaintenanceAggregateType.REPAIR,
              saved.getId(),
              expectedVersion,
              eventType,
              repairLocal(saved),
              repairFact(eventType, saved),
              repairSnapshot(saved));
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", saved.getId().toString(),
                  "externalTaskId", task.externalTaskId().toString(),
                  "taskBoardVersion", task.version()));
        });
  }

  private Optional<TaskPlan> prepareTaskPlan(
      MaintenanceReconciliationStore.WorkItem work, boolean update) {
    MaintenanceRepair repair = requireWorkRepair(work);
    if (prestartReplacementGuard.blocksRepairExecution(repair.getId())) {
      reconciliations.defer(work, Duration.ofSeconds(2));
      return Optional.empty();
    }
    if (repair.getExecutionState() != RepairExecutionState.QUEUED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Only a queued repair can reconcile its task");
    }
    if (update && repair.getTaskBoardVersion() == null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Task update has no confirmed task-board version");
    }
    LocalDate scheduledDate = repair.getDispatchDate();
    int priority = repair.getPriority();
    boolean resolveDeliveredLocalDate = !update
        && requiresDriverDeliveryToRepair(repair)
        && repairPlaces.isOccupied(repair.getWarehouseId(), repair.getId());
    return Optional.of(
        new TaskPlan(
            repair.getId(),
            repair.getWarehouseId(),
            repair.getRentalItemId(),
            repair.getExternalTaskId(),
            update,
            update ? repair.getTaskBoardVersion() : -1L,
            scheduledDate,
            priority,
            resolveDeliveredLocalDate,
            repairCapacitySettings.get(repair.getWarehouseId()).repairPlaceCount(),
            List.copyOf(taskStages(repair))));
  }

  private static void validateTaskAssetTruth(
      TaskPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.number() == null
        || asset.number().isBlank()
        || asset.number().length() > 64) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Canonical rental-item snapshot is not safe for task synchronization");
    }
  }

  private static void validateTaskPlanTruth(
      TaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    if (task == null
        || !plan.externalTaskId().equals(task.externalTaskId())
        || task.version() < 0
        || !"ACTIVE".equals(task.state())
        || task.stages() == null
        || task.stages().size() != plan.stages().size()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board truth does not match the maintenance repair");
    }
  }

  private void reconcileQueuedRepairClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    Optional<QueueRepairPlan> prepared = transactions.execute(status -> prepareQueueRepairPlan(work));
    if (prepared == null || prepared.isEmpty()) return;
    QueueRepairPlan plan = prepared.orElseThrow();
    if (plan.alreadyQueued()) {
      transactions.executeWithoutResult(
          status -> {
            Optional<QueueRepairPlan> current = prepareQueueRepairPlan(work);
            if (current == null || current.isEmpty() || !plan.equals(current.orElseThrow())) {
              throw new MaintenanceConflictException(
                  "MAINTENANCE_STATE_CONFLICT",
                  "Repair queue reconciliation changed before finalization");
            }
            reconciliations.confirmed(
                work, Map.of("repairId", plan.repairId().toString(), "alreadyQueued", true));
          });
      return;
    }

    // All asset reads and mutations take place only after the prepare transaction has committed.
    MaintenanceDependencyGateway.AssetSnapshot liveAsset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateQueueAssetTruth(plan, liveAsset);
    List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture;
    if (plan.requestedFurniture().isEmpty()) {
      furniture = List.of();
    } else {
      MaintenanceDependencyGateway.PropertyAssetSnapshot cabin =
          dependencies.getPropertyAssetSnapshot(
              MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
              plan.rentalItemId(),
              plan.warehouseId());
      furniture =
          plan.furnitureAccountingMode() == FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS
              ? unaccountedFurnitureReturnsFromEmptyCabin(plan, cabin)
              : pendingReturnsFromCabinSnapshot(plan, cabin);
    }

    QueueRepairRemoteResult remote;
    if (("REPAIR".equals(liveAsset.status()) || "CAPITAL_REPAIR".equals(liveAsset.status()))
        && plan.existingLifecycleOwner()) {
      remote = QueueRepairRemoteResult.adopt(liveAsset, furniture);
    } else {
      if (("REPAIR".equals(liveAsset.status()) || "CAPITAL_REPAIR".equals(liveAsset.status()))
          && !liveAsset.status().equals(plan.projectedAssetStatus())) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Rental item repair adoption requires a matching canonical asset fact");
      }
      MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.acquireLease(
          derived(work.idempotencyKey(), "acquire"),
          plan.rentalItemId(),
          liveAsset.version(),
          plan.ownerType(),
          plan.ownerId());
      validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      if (!leaseIsFresh(lease.expiresAt())) {
        lease = dependencies.renewLease(
            derived(work.idempotencyKey(), "renew"),
            lease.leaseId(),
            lease.version(),
            lease.fencingToken(),
            plan.ownerType(),
            plan.ownerId());
        validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      }
      requireFreshDependencyLease(lease);
      MaintenanceDependencyGateway.AssetSnapshot asset = furniture.isEmpty()
          ? dependencies.fencedStatus(
              derived(work.idempotencyKey(), "status"),
              plan.rentalItemId(),
              plan.warehouseId(),
              liveAsset.version(),
              lease.leaseId(),
              lease.fencingToken(),
              plan.ownerType(),
              plan.ownerId(),
              plan.queueTransition(),
              plan.linkedReturn())
          : dependencies.fencedStatus(
              derived(work.idempotencyKey(), "status"),
              plan.rentalItemId(),
              plan.warehouseId(),
              liveAsset.version(),
              lease.leaseId(),
              lease.fencingToken(),
              plan.ownerType(),
              plan.ownerId(),
              plan.queueTransition(),
              plan.linkedReturn(),
              furniture);
      validateQueueTransitionTruth(plan, liveAsset, asset);
      remote = QueueRepairRemoteResult.queued(liveAsset, lease, asset, furniture);
    }

    transactions.executeWithoutResult(status -> finalizeQueuedRepairClaim(work, plan, remote));
  }

  private Optional<QueueRepairPlan> prepareQueueRepairPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = requireWorkRepair(work);
    if (prestartReplacementGuard.blocksRepairExecution(repair.getId())) {
      reconciliations.defer(work, Duration.ofSeconds(2));
      return Optional.empty();
    }
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      return Optional.of(QueueRepairPlan.alreadyQueued(repair));
    }
    if (repair.getExecutionState() != RepairExecutionState.DRAFT || repair.getKind() == RepairKind.REWORK) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair is no longer eligible for primary queue reconciliation");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    if (stages.isEmpty()) {
      throw invalid("Repair needs at least one planned stage before queueing");
    }
    RepairComplexitySnapshot complexity =
        repairComplexityFromStoredStages(repair.getWarehouseId(), repair.getId());
    RentalItemFactProjection fact = requireRentalItemFact(
        repair.getRentalItemId(), repair.getWarehouseId());
    boolean existingOwner = primaryLifecycleOwnerWithLeaseIdentity(repair).isPresent();
    return Optional.of(
        new QueueRepairPlan(
            repair.getId(),
            repair.getVersion(),
            false,
            repair.getWarehouseId(),
            repair.getRentalItemId(),
            repair.getRentalItemVersionSnapshot(),
            repair.getOrigin(),
            complexity.type() == RepairComplexity.CAPITAL,
            complexity.type() == RepairComplexity.CAPITAL ? "CAPITAL_REPAIR" : "REPAIR",
            complexity.type() == RepairComplexity.CAPITAL
                ? "QUEUE_TO_CAPITAL_REPAIR"
                : "QUEUE_TO_REPAIR",
            ownerType(repair),
            ownerId(repair),
            work.payload().path("linkedReturn").asBoolean(false),
            fact.getAssetStatus(),
            fact.getAggregateVersion(),
            existingOwner,
            repair.getFurnitureAccountingMode(),
            List.copyOf(furnitureQuantities(repair))));
  }

  private void finalizeQueuedRepairClaim(
      MaintenanceReconciliationStore.WorkItem work,
      QueueRepairPlan plan,
      QueueRepairRemoteResult remote) {
    Optional<QueueRepairPlan> currentOptional = prepareQueueRepairPlan(work);
    if (currentOptional == null || currentOptional.isEmpty()) return;
    QueueRepairPlan current = currentOptional.orElseThrow();
    if (!plan.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair queue reconciliation changed before finalization");
    }
    MaintenanceRepair repair = requireRepair(plan.repairId());
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    assertVersion(repair.getVersion(), expectedVersion);
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      reconciliations.confirmed(
          work, Map.of("repairId", repair.getId().toString(), "alreadyQueued", true));
      return;
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    if (remote.adoptExistingRepair()) {
      if (!current.existingLifecycleOwner()) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Repair lifecycle owner disappeared before finalization");
      }
      prepareStagesForQueue(stages, current.capital());
      repairStages.saveAllAndFlush(stages);
      repair.confirmRentalItemVersion(remote.liveAsset().version());
      if (current.capital()) {
        repair.queueExternalCapitalUnderExistingRepair();
      } else {
        repair.queueUnderExistingRepair();
      }
      MaintenanceRepair saved = repairs.saveAndFlush(repair);
      if (!current.capital()) {
        enqueueOrdinaryRepairExecution(
            saved,
            stableOperationKey("register-task", saved.getExternalTaskId(), 0),
            stableOperationKey("driver-logistics-task", saved.getId(), 0));
        enqueueRepairComplexityStatusSync(
            saved, derived(work.idempotencyKey(), "repair-complexity-status"));
      }
      events.append(
          MaintenanceAggregateType.REPAIR,
          saved.getId(),
          expectedVersion,
          MaintenanceEventType.REPAIR_QUEUED,
          repairLocal(saved),
          repairFact(MaintenanceEventType.REPAIR_QUEUED, saved),
          repairSnapshot(saved));
      reconciliations.confirmed(
          work,
          Map.of(
              "repairId", saved.getId().toString(),
              "assetAlreadyInRepair", true,
              "rentalItemVersion", remote.liveAsset().version()));
      return;
    }

    MaintenanceDependencyGateway.LeaseSnapshot lease = remote.lease();
    MaintenanceDependencyGateway.AssetSnapshot asset = remote.asset();
    if (lease == null || asset == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair queue remote result is incomplete");
    }
    validateLeaseTruth(repair, lease, current.ownerType(), current.ownerId());
    validateQueueTransitionTruth(current, remote.liveAsset(), asset);
    prepareStagesForQueue(stages, current.capital());
    repairStages.saveAllAndFlush(stages);
    repair.confirmRentalItemVersion(asset.version());
    if (current.capital()) {
      repair.queueExternalCapital(
          lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
    } else {
      repair.queue(lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
    }
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    if (!current.capital()) {
      enqueueOrdinaryRepairExecution(
          saved,
          stableOperationKey("register-task", saved.getExternalTaskId(), 0),
          stableOperationKey("driver-logistics-task", saved.getId(), 0));
    }
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_QUEUED,
        repairLocal(saved),
        repairFact(MaintenanceEventType.REPAIR_QUEUED, saved),
        repairSnapshot(saved));
    reconciliations.confirmed(
        work,
        Map.of(
            "repairId", saved.getId().toString(),
            "leaseId", lease.leaseId().toString(),
            "rentalItemVersion", asset.version()));
  }

  private static void validateQueueAssetTruth(
      QueueRepairPlan plan, MaintenanceDependencyGateway.AssetSnapshot snapshot) {
    Set<String> eligibleStatuses = plan.origin() == RepairOrigin.DIRECT_REPAIR
        ? DIRECT_REPAIR_QUEUE_SOURCE_STATUSES
        : ESTIMATE_REPAIR_QUEUE_SOURCE_STATUSES;
    if (snapshot == null
        || !plan.rentalItemId().equals(snapshot.rentalItemId())
        || !plan.warehouseId().equals(snapshot.warehouseId())
        || snapshot.version() < 0
        || snapshot.status() == null
        || !eligibleStatuses.contains(snapshot.status())
        || snapshot.version() < plan.rentalItemVersion()
        || snapshot.version() < plan.projectedAssetVersion()
        || (snapshot.version() == plan.projectedAssetVersion()
            && !snapshot.status().equals(plan.projectedAssetStatus()))) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Canonical rental-item snapshot is not safe for repair queueing");
    }
  }

  private static List<MaintenanceDependencyGateway.FurniturePendingReturn>
      pendingReturnsFromCabinSnapshot(
          QueueRepairPlan plan, MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    requireMatchingCabinForFurniture(plan.rentalItemId(), plan.warehouseId(), snapshot);
    Map<UUID, MaintenanceDependencyGateway.PropertyAssetContentSnapshot> contents =
        snapshot.contents().stream().collect(
            java.util.stream.Collectors.toMap(
                MaintenanceDependencyGateway.PropertyAssetContentSnapshot::equipmentId,
                value -> value));
    return plan.requestedFurniture().stream().map(
        line -> {
          MaintenanceDependencyGateway.PropertyAssetContentSnapshot content =
              contents.get(line.equipmentId());
          if (content == null || content.quantity() < line.quantity()) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Cabin no longer contains the furniture selected for maintenance");
          }
          return new MaintenanceDependencyGateway.FurniturePendingReturn(
              line.equipmentId(), content.balanceVersion(), line.quantity());
        }).toList();
  }

  private static List<MaintenanceDependencyGateway.FurniturePendingReturn>
      unaccountedFurnitureReturnsFromEmptyCabin(
          QueueRepairPlan plan, MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (!hasNoRecordedCabinContents(plan.rentalItemId(), plan.warehouseId(), snapshot)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Cabin contents were recorded after unaccounted furniture confirmation; the repair cannot bypass normal furniture accounting");
    }
    return List.of();
  }

  private static boolean hasNoRecordedCabinContents(
      UUID rentalItemId,
      UUID warehouseId,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    requireMatchingCabinForFurniture(rentalItemId, warehouseId, snapshot);
    return snapshot.contents().isEmpty();
  }

  private static void requireMatchingCabinForFurniture(
      UUID rentalItemId,
      UUID warehouseId,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (snapshot == null
        || snapshot.assetKind() != MaintenanceDependencyGateway.PropertyAssetKind.CABIN
        || !rentalItemId.equals(snapshot.assetId())
        || !warehouseId.equals(snapshot.warehouseId())
        || snapshot.contents() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service returned another cabin while selecting repair furniture");
    }
  }

  private static void validateQueueTransitionTruth(
      QueueRepairPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot liveAsset,
      MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (liveAsset == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair queue live asset snapshot is missing");
    }
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() < liveAsset.version()
        || (asset.version() == liveAsset.version() && !plan.desiredAssetStatus().equals(liveAsset.status()))
        || !plan.desiredAssetStatus().equals(asset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm the calculated repair status");
    }
  }

  private void reconcileAssetTransitionClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    AssetTransitionPlan plan = requireReconciliationResult(
        transactions.execute(status -> prepareAssetTransitionPlan(work)));
    MaintenanceDependencyGateway.LeaseSnapshot lease = plan.localLease();
    if (lease == null) {
      lease = dependencies.acquireLease(
          derived(work.idempotencyKey(), "acquire"),
          plan.rentalItemId(),
          plan.rentalItemVersion(),
          plan.ownerType(),
          plan.ownerId());
    }
    validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    if (!leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          derived(work.idempotencyKey(), "renew"),
          lease.leaseId(),
          lease.version(),
          lease.fencingToken(),
          plan.ownerType(),
          plan.ownerId());
      validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    }
    requireFreshDependencyLease(lease);
    MaintenanceDependencyGateway.AssetSnapshot asset = dependencies.fencedStatus(
        derived(work.idempotencyKey(), "status"),
        plan.rentalItemId(),
        plan.warehouseId(),
        plan.rentalItemVersion(),
        lease.leaseId(),
        lease.fencingToken(),
        plan.ownerType(),
        plan.ownerId(),
        plan.transition(),
        false);
    validateAssetTransitionTruth(plan, asset);
    boolean terminal = !"PENDING_ACCEPTANCE".equals(plan.transition());
    if (terminal) {
      dependencies.releaseLease(
          derived(work.idempotencyKey(), "release"),
          lease.leaseId(),
          lease.version(),
          lease.fencingToken(),
          plan.ownerType(),
          plan.ownerId());
    }
    MaintenanceDependencyGateway.LeaseSnapshot finalLease = lease;
    transactions.executeWithoutResult(
        status -> finalizeAssetTransitionClaim(work, plan, asset, finalLease, terminal));
  }

  private AssetTransitionPlan prepareAssetTransitionPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = requireWorkRepair(work);
    List<MaintenanceRepair> sources = sourceChain(repair);
    String ownerType = ownerType(repair);
    String ownerId = ownerId(repair);
    return new AssetTransitionPlan(
        repair.getId(),
        repair.getVersion(),
        sources.stream().map(MaintenanceRepair::getId).toList(),
        repair.getWarehouseId(),
        repair.getRentalItemId(),
        repair.getRentalItemVersionSnapshot(),
        ownerType,
        ownerId,
        work.operation(),
        localLease(repair, sources, ownerType, ownerId).orElse(null));
  }

  private void finalizeAssetTransitionClaim(
      MaintenanceReconciliationStore.WorkItem work,
      AssetTransitionPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      boolean terminal) {
    MaintenanceRepair initial = requireRepair(plan.repairId());
    LockedRepairChain locked = lockAndReloadRepairChain(initial, plan.repairVersion());
    MaintenanceRepair repair = locked.repair();
    List<MaintenanceRepair> sources = locked.sources();
    if (!plan.sourceRepairIds().equals(sources.stream().map(MaintenanceRepair::getId).toList())
        || !plan.warehouseId().equals(repair.getWarehouseId())
        || !plan.rentalItemId().equals(repair.getRentalItemId())
        || !plan.transition().equals(work.operation())
        || !plan.ownerType().equals(ownerType(repair))
        || !plan.ownerId().equals(ownerId(repair))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Asset-transition reconciliation changed before finalization");
    }
    validateLeaseTruth(repair, lease, plan.ownerType(), plan.ownerId());
    validateAssetTruth(repair, asset);
    reconcileLeaseProjection(
        repair,
        locked.streamVersions().get(stream(repair.getId())),
        asset.version(),
        lease,
        terminal,
        transitionEvent(repair, plan.transition()));
    for (MaintenanceRepair source : sources) {
      reconcileLeaseProjection(
          source,
          locked.streamVersions().get(stream(source.getId())),
          asset.version(),
          lease,
          terminal,
          transitionEvent(source, plan.transition()));
    }
    reconciliations.confirmed(
        work,
        Map.of(
            "repairId", repair.getId().toString(),
            "rentalItemVersion", asset.version(),
            "leaseReleased", terminal));
  }

  private static void validateAssetTransitionTruth(
      AssetTransitionPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() <= plan.rentalItemVersion()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not match the expected rental item");
    }
  }

  private void reconcileLeaseRenewalClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    LeaseRenewalPlan plan = requireReconciliationResult(
        transactions.execute(status -> prepareLeaseRenewalPlan(work)));
    MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.renewLease(
        work.idempotencyKey(),
        plan.leaseId(),
        plan.leaseVersion(),
        plan.fencingToken(),
        plan.ownerType(),
        plan.ownerId());
    validateLeaseRenewalTruth(plan, lease);
    requireFreshDependencyLease(lease);
    transactions.executeWithoutResult(
        status -> {
          LeaseRenewalPlan current = prepareLeaseRenewalPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT", "Lease renewal changed before finalization");
          }
          MaintenanceRepair repair = requireRepair(plan.repairId());
          long expectedVersion =
              events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
          assertVersion(repair.getVersion(), expectedVersion);
          repair.renewLease(lease.version(), lease.expiresAt());
          MaintenanceRepair saved = repairs.saveAndFlush(repair);
          MaintenanceEventType renewalEvent = renewalEvent(saved);
          events.append(
              MaintenanceAggregateType.REPAIR,
              saved.getId(),
              expectedVersion,
              renewalEvent,
              repairLocal(saved),
              repairFact(renewalEvent, saved),
              repairSnapshot(saved));
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", saved.getId().toString(),
                  "leaseVersion", lease.version(),
                  "expiresAt", lease.expiresAt().toString()));
        });
  }

  private LeaseRenewalPlan prepareLeaseRenewalPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = requireWorkRepair(work);
    requireRenewableLease(repair);
    return new LeaseRenewalPlan(
        repair.getId(),
        repair.getVersion(),
        repair.getRentalItemId(),
        repair.getLeaseId(),
        repair.getLeaseVersion(),
        repair.getFencingToken(),
        ownerType(repair),
        ownerId(repair));
  }

  private static void validateLeaseRenewalTruth(
      LeaseRenewalPlan plan, MaintenanceDependencyGateway.LeaseSnapshot lease) {
    validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    if (!plan.leaseId().equals(lease.leaseId())
        || plan.fencingToken() != lease.fencingToken()
        || lease.version() != Math.addExact(plan.leaseVersion(), 1)) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease renewal does not preserve the current fence and version");
    }
  }

  private void reconcileRepairComplexityStatusClaim(
      MaintenanceReconciliationStore.WorkItem work) {
    ComplexitySyncPlan plan = requireReconciliationResult(
        transactions.execute(status -> prepareComplexitySyncPlan(work, false)));

    // Withdraw existing ordinary routes first. Each call has a stable derived key, so a crash
    // before the final local commit replays the same task-board effect safely.
    for (TaskCancellationPlan cancellation : plan.taskCancellations()) {
      MaintenanceDependencyGateway.TaskSnapshot cancelled = dependencies.cancelTask(
          derived(work.idempotencyKey(), "withdraw-task:" + cancellation.repairId()),
          cancellation.externalTaskId(),
          cancellation.expectedVersion());
      if (cancelled == null || !"CANCELLED".equals(cancelled.state())) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board did not confirm ordinary repair route withdrawal");
      }
    }

    MaintenanceDependencyGateway.AssetSnapshot currentAsset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateComplexityAssetTruth(plan, currentAsset);
    MaintenanceDependencyGateway.AssetSnapshot synchronizedAsset = currentAsset;
    MaintenanceDependencyGateway.LeaseSnapshot lease = plan.lease();
    if (!plan.desiredStatus().equals(currentAsset.status())) {
      validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      if (!leaseIsFresh(lease.expiresAt())) {
        lease = dependencies.renewLease(
            derived(work.idempotencyKey(), "renew"),
            lease.leaseId(),
            lease.version(),
            lease.fencingToken(),
            plan.ownerType(),
            plan.ownerId());
        validateComplexityRenewedLeaseTruth(plan, lease);
      }
      requireFreshDependencyLease(lease);
      synchronizedAsset = dependencies.fencedStatus(
          derived(work.idempotencyKey(), "status"),
          plan.rentalItemId(),
          plan.warehouseId(),
          currentAsset.version(),
          lease.leaseId(),
          lease.fencingToken(),
          plan.ownerType(),
          plan.ownerId(),
          plan.transition(),
          false);
      validateComplexityTransitionTruth(plan, currentAsset, synchronizedAsset);
    }
    MaintenanceDependencyGateway.LeaseSnapshot finalLease = lease;
    MaintenanceDependencyGateway.AssetSnapshot finalAsset = synchronizedAsset;
    transactions.executeWithoutResult(
        status -> finalizeComplexitySyncClaim(work, plan, finalLease, finalAsset));
  }

  private ComplexitySyncPlan prepareComplexitySyncPlan(
      MaintenanceReconciliationStore.WorkItem work, boolean lockRepairs) {
    MaintenanceRepair requested = requireWorkRepair(work);
    List<MaintenanceRepair> rentalItemRepairs = lockRepairs
        ? repairs.findAllByRentalItemIdForUpdate(requested.getRentalItemId())
        : repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(requested.getRentalItemId());
    return complexitySyncPlan(work, requested.getId(), rentalItemRepairs);
  }

  private ComplexitySyncPlan complexitySyncPlan(
      MaintenanceReconciliationStore.WorkItem work,
      UUID requestedRepairId,
      List<MaintenanceRepair> rentalItemRepairs) {
    MaintenanceRepair repair = rentalItemRepairs.stream()
        .filter(value -> value.getId().equals(requestedRepairId))
        .findFirst()
        .orElseThrow(
            () -> new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Queued repair disappeared during complexity synchronization"));
    boolean externalCapitalPendingStatusSync =
        repair.getExecutionState() == RepairExecutionState.COMPLETED
            && repair.getAcceptanceState() == RepairAcceptanceState.PENDING
            && repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL;
    if (repair.getExecutionState() != RepairExecutionState.QUEUED
        && repair.getExecutionState() != RepairExecutionState.IN_PROGRESS
        && !externalCapitalPendingStatusSync) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only active ordinary work or pending external capital repair can synchronize its calculated status");
    }
    List<MaintenanceRepair> complexityRepairs = rentalItemRepairs.stream().filter(
        value -> value.getExecutionState() != RepairExecutionState.DRAFT
            && value.getExecutionState() != RepairExecutionState.CANCELLED
            && value.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
            && value.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF).toList();
    List<MaintenanceRepair> leaseOwners = rentalItemRepairs.stream()
        .filter(value -> value.getKind() == RepairKind.PRIMARY)
        .filter(
            value -> value.getExecutionState() != RepairExecutionState.DRAFT
                && value.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
                && value.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF
                && value.getLeaseId() != null
                && value.getLeaseVersion() != null
                && value.getFencingToken() != null
                && value.getLeaseExpiresAt() != null
                && Set.of("ACTIVE", "RECONCILIATION_REQUIRED")
                    .contains(value.getLeaseReconciliationState()))
        .toList();
    if (leaseOwners.size() != 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_LEASE_CONFLICT",
          "Repair complexity synchronization requires one cabin lifecycle owner");
    }
    MaintenanceRepair leaseOwner = leaseOwners.getFirst();
    Map<UUID, MaintenanceRepair> scope = new LinkedHashMap<>();
    complexityRepairs.forEach(value -> scope.put(value.getId(), value));
    scope.put(leaseOwner.getId(), leaseOwner);
    List<MaintenanceRepair> synchronizedRepairs = List.copyOf(scope.values());
    requireTransferSource(synchronizedRepairs, repair.getRentalItemId(), repair.getWarehouseId());

    boolean capital = complexityRepairs.stream().map(
        value -> repairComplexityFromStoredStages(value.getWarehouseId(), value.getId()))
        .anyMatch(complexity -> complexity.type() == RepairComplexity.CAPITAL);
    List<TaskCancellationPlan> cancellations = capital
        ? complexityRepairs.stream()
            .filter(value -> value.getExecutionState() == RepairExecutionState.QUEUED
                || value.getExecutionState() == RepairExecutionState.IN_PROGRESS)
            .filter(value -> value.getTaskBoardVersion() != null)
            .map(value -> new TaskCancellationPlan(
                value.getId(), value.getExternalTaskId(), value.getTaskBoardVersion()))
            .toList()
        : List.of();
    long latestProjectedVersion = synchronizedRepairs.stream()
        .mapToLong(MaintenanceRepair::getRentalItemVersionSnapshot)
        .max()
        .orElseThrow();
    String ownerType = ownerType(leaseOwner);
    String ownerId = ownerId(leaseOwner);
    MaintenanceDependencyGateway.LeaseSnapshot lease =
        new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseOwner.getLeaseId(),
            leaseOwner.getLeaseVersion(),
            leaseOwner.getRentalItemId(),
            ownerType,
            UUID.fromString(ownerId),
            leaseOwner.getFencingToken(),
            leaseOwner.getLeaseExpiresAt());
    List<RepairSyncSignature> signatures = synchronizedRepairs.stream()
        .sorted(Comparator.comparing(value -> value.getId().toString()))
        .map(MaintenanceApplicationService::repairSyncSignature)
        .toList();
    return new ComplexitySyncPlan(
        repair.getId(),
        repair.getWarehouseId(),
        repair.getRentalItemId(),
        capital,
        capital ? "CAPITAL_REPAIR" : "REPAIR",
        capital ? "QUEUE_TO_CAPITAL_REPAIR" : "QUEUE_TO_REPAIR",
        latestProjectedVersion,
        ownerType,
        ownerId,
        lease,
        leaseOwner.getId(),
        complexityRepairs.stream().map(MaintenanceRepair::getId).sorted(
            Comparator.comparing(UUID::toString)).toList(),
        signatures,
        List.copyOf(cancellations));
  }

  private void finalizeComplexitySyncClaim(
      MaintenanceReconciliationStore.WorkItem work,
      ComplexitySyncPlan plan,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      MaintenanceDependencyGateway.AssetSnapshot synchronizedAsset) {
    ComplexitySyncPlan current = prepareComplexitySyncPlan(work, true);
    if (!plan.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair complexity synchronization changed before finalization");
    }
    validateComplexityAssetTruth(plan, synchronizedAsset);
    if (!plan.desiredStatus().equals(synchronizedAsset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm the recalculated repair status");
    }
    validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());

    Map<UUID, MaintenanceRepair> byId = repairs
        .findAllByRentalItemIdForUpdate(plan.rentalItemId())
        .stream()
        .collect(java.util.stream.Collectors.toMap(MaintenanceRepair::getId, value -> value));
    List<MaintenanceRepair> synchronizedRepairs = plan.signatures().stream()
        .map(signature -> Optional.ofNullable(byId.get(signature.id())).orElseThrow(
            () -> new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT", "Repair synchronization scope changed")))
        .toList();
    Map<MaintenanceEventStore.StreamRef, Long> streamVersions = events.lockStreams(
        synchronizedRepairs.stream().map(value -> stream(value.getId())).toList());
    synchronizedRepairs.forEach(value -> assertStreamParity(value, streamVersions));
    Map<UUID, MaintenanceRepair> changed = new LinkedHashMap<>();
    if (plan.capital()) {
      for (MaintenanceRepair value : synchronizedRepairs) {
        if (!plan.complexityRepairIds().contains(value.getId())) continue;
        if (value.getExecutionState() != RepairExecutionState.QUEUED
            && value.getExecutionState() != RepairExecutionState.IN_PROGRESS) {
          continue;
        }
        List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(value.getId());
        stages.forEach(RepairStage::completeAsExternalCapital);
        repairStages.saveAllAndFlush(stages);
        value.completeAsExternalCapital();
        repairPlaces.completeAfterRepair(
            value.getWarehouseId(), value.getId(), value.isMovementToRepair());
        changed.put(value.getId(), value);
      }
    } else {
      for (MaintenanceRepair value : synchronizedRepairs) {
        if (!plan.complexityRepairIds().contains(value.getId())) continue;
        if ((value.getExecutionState() == RepairExecutionState.QUEUED
                || value.getExecutionState() == RepairExecutionState.IN_PROGRESS)
            && value.stabilizeOrdinaryClassification()) {
          changed.put(value.getId(), value);
        }
      }
    }
    for (MaintenanceRepair value : synchronizedRepairs) {
      if (value.getRentalItemVersionSnapshot() != synchronizedAsset.version()) {
        value.confirmRentalItemVersion(synchronizedAsset.version());
        changed.put(value.getId(), value);
      }
    }
    RepairSyncSignature ownerSignature = plan.signatures().stream()
        .filter(signature -> signature.id().equals(plan.leaseOwnerRepairId()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Repair lifecycle owner is missing"));
    if (lease.version() != ownerSignature.leaseVersion()
        || !lease.expiresAt().equals(ownerSignature.leaseExpiresAt())) {
      UUID previousLeaseId = ownerSignature.leaseId();
      long previousFencingToken = ownerSignature.fencingToken();
      for (MaintenanceRepair value : synchronizedRepairs) {
        if (previousLeaseId.equals(value.getLeaseId())
            && value.getFencingToken() != null
            && value.getFencingToken() == previousFencingToken) {
          applyLeaseSnapshot(value, lease);
          changed.put(value.getId(), value);
        }
      }
    }
    if (!changed.isEmpty()) {
      List<MaintenanceRepair> saved = repairs.saveAllAndFlush(
          changed.values().stream()
              .sorted(Comparator.comparing(value -> value.getId().toString()))
              .toList());
      for (MaintenanceRepair value : saved) {
        long expectedVersion = streamVersions.get(stream(value.getId()));
        events.append(
            MaintenanceAggregateType.REPAIR,
            value.getId(),
            expectedVersion,
            MaintenanceEventType.REPAIR_PLAN_CHANGED,
            repairLocal(value),
            repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, value),
            repairSnapshot(value));
      }
    }
    reconciliations.confirmed(
        work,
        Map.of(
            "repairId", plan.repairId().toString(),
            "repairStatus", plan.desiredStatus(),
            "rentalItemVersion", synchronizedAsset.version()));
  }

  private static RepairSyncSignature repairSyncSignature(MaintenanceRepair value) {
    return new RepairSyncSignature(
        value.getId(),
        value.getVersion(),
        value.getExecutionState(),
        value.getAcceptanceState(),
        value.getReclassificationState(),
        value.getTaskBoardVersion(),
        value.getExternalTaskId(),
        value.getRentalItemVersionSnapshot(),
        value.getLeaseId(),
        value.getLeaseVersion(),
        value.getFencingToken(),
        value.getLeaseExpiresAt(),
        value.isMovementToRepair());
  }

  private static void validateComplexityAssetTruth(
      ComplexitySyncPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() < plan.latestProjectedVersion()
        || !Set.of("REPAIR", "CAPITAL_REPAIR").contains(asset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Canonical rental-item snapshot is not safe for repair complexity synchronization");
    }
  }

  private static void validateComplexityRenewedLeaseTruth(
      ComplexitySyncPlan plan, MaintenanceDependencyGateway.LeaseSnapshot lease) {
    validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    if (!plan.lease().leaseId().equals(lease.leaseId())
        || plan.lease().fencingToken() != lease.fencingToken()
        || lease.version() != Math.addExact(plan.lease().version(), 1)) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease renewal does not preserve the current fence and version");
    }
  }

  private static void validateComplexityTransitionTruth(
      ComplexitySyncPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot current,
      MaintenanceDependencyGateway.AssetSnapshot synchronizedAsset) {
    if (synchronizedAsset == null
        || !plan.rentalItemId().equals(synchronizedAsset.rentalItemId())
        || !plan.warehouseId().equals(synchronizedAsset.warehouseId())
        || synchronizedAsset.version() < current.version()
        || (synchronizedAsset.version() == current.version()
            && !plan.desiredStatus().equals(current.status()))
        || !plan.desiredStatus().equals(synchronizedAsset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm the recalculated repair status");
    }
  }

  private MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate, List<EstimatePlanStage> estimatePlan) {
    return createEstimateRepair(
        estimate,
        estimatePlan,
        3,
        false,
        null,
        null);
  }

  private MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate, List<EstimatePlanStage> estimatePlan, int priority) {
    return createEstimateRepair(
        estimate,
        estimatePlan,
        priority,
        false,
        null,
        null);
  }

  private MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate,
      List<EstimatePlanStage> estimatePlan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate) {
    return createEstimateRepair(
        estimate,
        estimatePlan,
        priority,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        FurnitureAccountingMode.TRACKED_CABIN_CONTENTS);
  }

  private MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate,
      List<EstimatePlanStage> estimatePlan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      FurnitureAccountingMode furnitureAccountingMode) {
    return createEstimateRepairFromPlan(
        estimate,
        storedPlanInputs(estimatePlan),
        priority,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        furnitureAccountingMode);
  }

  private List<PlanStageInput> storedPlanInputs(List<EstimatePlanStage> estimatePlan) {
    return estimatePlan.stream()
        .map(value -> new PlanStageInput(
            value.getId(), value.getStageKind(), value.getStageNo(),
            new RoutingSnapshot(value.getRoutingQueueId(), value.getRoutingQueueName(),
                value.getRoutingQueueType()),
            readList(value.getIncludedLineIds(), UUID.class),
            value.getPrimaryLineId(),
            value.getGroupComment(),
            value.getTaskDeadline()))
        .toList();
  }

  private MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate, List<PlanStageInput> plan) {
    return createEstimateRepairFromPlan(
        estimate,
        plan,
        3,
        false,
        null,
        null);
  }

  private MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate, List<PlanStageInput> plan, int priority) {
    return createEstimateRepairFromPlan(
        estimate,
        plan,
        priority,
        false,
        null,
        null);
  }

  private MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate,
      List<PlanStageInput> plan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate) {
    return createEstimateRepairFromPlan(
        estimate,
        plan,
        priority,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        FurnitureAccountingMode.TRACKED_CABIN_CONTENTS);
  }

  private MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate,
      List<PlanStageInput> plan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      FurnitureAccountingMode furnitureAccountingMode) {
    MaintenanceRepair newRepair = MaintenanceRepair.primary(
        estimate.getWarehouseId(), estimate.getRentalItemId(), estimate.getRentalItemVersionSnapshot(),
        estimate.getId(), RepairOrigin.ESTIMATE, estimate.getDispatchDate(), estimate.getSourceParty(), actorJson());
    if (furnitureAccountingMode == FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS) {
      newRepair.useUnaccountedFurnitureAccounting();
    }
    newRepair.selectPriority(priority);
    newRepair.selectMovementToRepair(
        movementToRepair,
        logisticsPlanningMode, logisticsScheduledDate);
    newRepair.replaceCoverMediaId(estimate.getCoverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(newRepair);
    replaceRepairStages(
        repair,
        plan,
        lineResponses(estimate.getId(), estimate.getRevision()));
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        repairLocal(repair),
        repairFact(MaintenanceEventType.REPAIR_CREATED, repair),
        repairSnapshot(repair));
    enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        repair.getId(),
        repair.getVersion(),
        true);
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
    List<EstimateLineResponse> canonicalLines = new ArrayList<>();
    List<CatalogNodeSnapshot> canonicalSnapshots = new ArrayList<>();
    Set<UUID> lineIds = new HashSet<>();
    for (int index = 0; index < lineInputs.size(); index++) {
      EstimateLineInput input = lineInputs.get(index);
      if (!lineIds.add(input.id())) throw invalid("Estimate line IDs must be unique inside a revision");
      CatalogNodeSnapshot catalogSnapshot = canonicalCatalogSnapshot(
          estimate, input.catalogSnapshot());
      if (catalogSnapshot != null) canonicalSnapshots.add(catalogSnapshot);
      EstimateLineType lineType = canonicalLineType(catalogSnapshot, input.lineType());
      String unit = canonicalLineUnit(catalogSnapshot, input.unit());
      BigDecimal quantity = new BigDecimal(input.quantity());
      if (catalogSnapshot != null
          && catalogSnapshot.furnitureEquipment() != null
          && quantity.signum() > 0
          && quantity.stripTrailingZeros().scale() > 0) {
        throw invalid("Furniture quantity must be a whole number");
      }
      validateMediaReferences(
          "MAINTENANCE_ESTIMATE", estimate.getId(), estimate.getWarehouseId(), input.mediaReferences());
      long unitPriceMinor = moneyToMinor(input.unitPrice());
      int normativeMinutes =
          estimateLineNormativeMinutes(catalogSnapshot, lineType, input.normativeMinutes());
      lines.add(new EstimateLine(
          input.id(), estimate.getId(), revision, index,
          catalogSnapshot == null ? null : catalogSnapshot.nodeId(),
          lineType.name(), input.description(), unit, quantity, unitPriceMinor,
          normativeMinutes,
          catalogSnapshot == null || catalogSnapshot.routing() == null
              ? null : catalogSnapshot.routing().queueId().toString(),
          catalogSnapshot == null ? null : write(catalogSnapshot),
          workLineComment(lineType, input.comment()), write(input.mediaReferences())));
      canonicalLines.add(
          new EstimateLineResponse(
              input.id(),
              catalogSnapshot,
              lineType,
              input.description(),
              unit,
              quantity(quantity),
              money(unitPriceMinor),
              money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              normativeMinutes,
              workLineComment(lineType, input.comment()),
              List.copyOf(input.mediaReferences())));
    }
    validateWorkLineMediaIsolation(canonicalLines);
    List<PlanStageInput> resolvedPlanInputs =
        resolvePlanContent(canonicalLines, planInputs);
    validateEstimateRouting(canonicalSnapshots, resolvedPlanInputs);
    validatePlanContent(canonicalLines, resolvedPlanInputs);
    validateCustomRoutingStructure(canonicalLines, resolvedPlanInputs);
    List<EstimatePlanStage> plan = new ArrayList<>();
    for (int index = 0; index < resolvedPlanInputs.size(); index++) {
      PlanStageInput input = resolvedPlanInputs.get(index);
      plan.add(new EstimatePlanStage(
          input.id(), estimate.getId(), revision, input.order(), input.kind(),
          input.routing().queueId(), input.routing().queueName(), input.routing().queueType(),
          write(input.includedLineIds()), input.primaryLineId(), input.groupComment(),
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

  private void validateEstimateRouting(
      List<CatalogNodeSnapshot> lineSnapshots,
      List<PlanStageInput> planInputs) {
    List<RoutingSnapshot> catalogRouting =
        lineSnapshots.stream()
            .filter(snapshot -> snapshot.nodeType() == CatalogNodeType.WORK)
            .map(CatalogNodeSnapshot::routing)
            .toList();
    if (catalogRouting.contains(null)) {
      throw invalid("Every estimate work must inherit or define a catalog queue");
    }
    Set<RoutingIdentity> expected =
        catalogRouting.stream()
            .map(MaintenanceApplicationService::routingIdentity)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<RoutingIdentity> actual =
        planInputs.stream()
            .filter(stage -> stage.kind() == dev.buhanzaz.rwms.maintenance.domain.RepairStageKind.REPAIR_WORK)
            .map(stage -> routingIdentity(stage.routing()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (!actual.containsAll(expected)) {
      throw invalid(
          "Catalog repair-work stages must use queue identities inherited from the global catalog");
    }
  }

  private static RoutingIdentity routingIdentity(RoutingSnapshot routing) {
    if (routing == null) {
      throw new IllegalArgumentException("Routing snapshot is required");
    }
    return new RoutingIdentity(routing.queueId());
  }

  private CatalogNodeSnapshot canonicalCatalogSnapshot(
      MaintenanceEstimate estimate, CatalogNodeSnapshot submitted) {
    if (submitted == null) return null;
    if (!estimate.getCatalogVersionId().equals(submitted.catalogVersionId())) {
      throw invalid("Estimate line must use the catalog version captured by the estimate");
    }
    return canonicalCatalogSnapshot(
        estimate.getWarehouseId(), estimate.getCatalogVersionId(), submitted);
  }

  private CatalogNodeSnapshot canonicalCatalogSnapshot(
      UUID warehouseId,
      UUID catalogVersionId,
      CatalogNodeSnapshot submitted) {
    if (submitted == null) return null;
    if (!catalogVersionId.equals(submitted.catalogVersionId())) {
      throw invalid("Repair line must use one canonical catalog version");
    }
    CatalogVersion version = catalogVersions.findById(catalogVersionId)
        .orElseThrow(() -> invalid("Estimate catalog version is unavailable"));
    Objects.requireNonNull(warehouseId, "Estimate warehouse is required");
    if (version.getState() == CatalogVersionState.DRAFT) {
      throw invalid("Estimate lines cannot use a draft catalog version");
    }
    CatalogNode node = catalogNodes.findByCatalogVersionIdAndId(
            catalogVersionId, submitted.nodeId())
        .orElseThrow(() -> invalid("Estimate catalog node is unavailable"));
    List<CatalogNode> versionNodes =
        catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(node.getCatalogVersionId());
    Map<UUID, CatalogNode> nodesById =
        versionNodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNode::getId, value -> value));
    Map<UUID, List<UUID>> incomingLinks = CatalogRoutingResolver.incoming(
        catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(catalogVersionId));
    if (!node.isActive() || !node.isIncludeInEstimate()) {
      throw invalid("Estimate catalog node is not active for estimates");
    }
    CatalogNodeType type = CatalogNodeType.valueOf(node.getNodeType());
    if (type != CatalogNodeType.WORK
        && type != CatalogNodeType.MATERIAL
        && type != CatalogNodeType.OPTION) {
      throw invalid("Catalog node type cannot be added to an estimate");
    }
    if (type == CatalogNodeType.MATERIAL && node.getFurnitureEquipmentId() == null) {
      if (belongsToFurnitureTree(node, nodesById)) {
        throw invalid(
            "Furniture material must be linked to additional equipment before use in an estimate");
      }
    }
    return new CatalogNodeSnapshot(
        node.getCatalogVersionId(),
        node.getId(),
        type,
        node.getName(),
        node.getUnit(),
        money(node.getPriceMinor()),
        node.getDurationMinutes(),
        CatalogRoutingResolver.snapshot(
            CatalogRoutingResolver.resolve(
                node.getId(),
                nodesById,
                CatalogNode::getParentNodeId,
                MaintenanceApplicationService::directRouteValue,
                incomingLinks)),
        node.getFurnitureEquipmentId() == null
            ? null
            : catalogFurnitureMapper.toReference(node),
        node.isForcesCapitalRepair(),
        node.getCharacteristicId() == null
            ? null
            : new CabinCharacteristicReference(
                node.getCharacteristicId(), node.getCharacteristicName()));
  }

  private static EstimateLineType canonicalLineType(
      CatalogNodeSnapshot catalogSnapshot, EstimateLineType submitted) {
    if (submitted == null) {
      throw invalid("Estimate line type is required");
    }
    if (catalogSnapshot == null) {
      return submitted;
    }
    EstimateLineType canonical = catalogSnapshot.nodeType() == CatalogNodeType.WORK
        ? EstimateLineType.WORK
        : EstimateLineType.MATERIAL;
    if (submitted != canonical) {
      throw invalid("Estimate line type must match its active catalog position");
    }
    return canonical;
  }

  private static String canonicalLineUnit(CatalogNodeSnapshot catalogSnapshot, String submitted) {
    if (catalogSnapshot != null) {
      return catalogSnapshot.unit();
    }
    if (submitted == null || submitted.isBlank()) {
      throw invalid("Custom estimate line unit is required");
    }
    String normalized = submitted.trim();
    if (normalized.length() > 32) {
      throw invalid("Custom estimate line unit is too long");
    }
    return normalized;
  }

  private List<FurnitureQuantity> furnitureQuantities(MaintenanceRepair repair) {
    if (repair.getEstimateId() != null) {
      return estimateFurnitureQuantities(requireEstimate(repair.getEstimateId()));
    }
    Map<UUID, FurnitureQuantity> quantities = new HashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      java.util.stream.Stream.concat(
              readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
              readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
          .forEach(
              line ->
                  addFurnitureQuantity(
                      quantities, line.catalogSnapshot(), new BigDecimal(line.quantity())));
    }
    return orderedFurnitureQuantities(quantities);
  }

  private List<FurnitureQuantity> estimateFurnitureQuantities(
      MaintenanceEstimate estimate) {
    Map<UUID, FurnitureQuantity> losses = new HashMap<>();
    for (EstimateLine line : currentLines(estimate)) {
      if (line.getCatalogSnapshot() == null) continue;
      CatalogNodeSnapshot storedSnapshot = read(
          line.getCatalogSnapshot(), CatalogNodeSnapshot.class);
      CatalogNodeSnapshot canonicalSnapshot = canonicalCatalogSnapshot(estimate, storedSnapshot);
      addFurnitureQuantity(losses, canonicalSnapshot, line.getQuantity());
    }
    return orderedFurnitureQuantities(losses);
  }

  private List<FurnitureQuantity> estimateFurnitureQuantities(
      MaintenanceEstimate estimate, List<EstimateLineInput> inputs) {
    Map<UUID, FurnitureQuantity> losses = new HashMap<>();
    for (EstimateLineInput input : inputs) {
      CatalogNodeSnapshot snapshot = canonicalCatalogSnapshot(
          estimate, input.catalogSnapshot());
      addFurnitureQuantity(losses, snapshot, new BigDecimal(input.quantity()));
    }
    return orderedFurnitureQuantities(losses);
  }

  private static void addFurnitureQuantity(
      Map<UUID, FurnitureQuantity> losses,
      CatalogNodeSnapshot snapshot,
      BigDecimal quantity) {
    if (snapshot == null || snapshot.furnitureEquipment() == null || quantity.signum() == 0) {
      return;
    }
    final long wholeQuantity;
    try {
      wholeQuantity = quantity.longValueExact();
    } catch (ArithmeticException exception) {
      throw invalid("Furniture quantity must be a whole number within the supported range");
    }
    FurnitureEquipmentReference equipment = snapshot.furnitureEquipment();
    FurnitureQuantity previous = losses.get(equipment.equipmentId());
    final long aggregateQuantity;
    try {
      aggregateQuantity = Math.addExact(previous == null ? 0 : previous.quantity(), wholeQuantity);
    } catch (ArithmeticException exception) {
      throw invalid("Furniture quantity must be a whole number within the supported range");
    }
    losses.put(
        equipment.equipmentId(),
        new FurnitureQuantity(equipment.equipmentId(), aggregateQuantity));
  }

  private static List<FurnitureQuantity> orderedFurnitureQuantities(
      Map<UUID, FurnitureQuantity> losses) {
    return losses.values().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .toList();
  }

  private Map<UUID, String> furnitureEquipmentNames(MaintenanceRepair repair) {
    Map<UUID, String> names = new HashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      java.util.stream.Stream.concat(
              readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
              readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
          .map(EstimateLineResponse::catalogSnapshot)
          .filter(Objects::nonNull)
          .map(CatalogNodeSnapshot::furnitureEquipment)
          .filter(Objects::nonNull)
          .forEach(
              equipment -> {
                String previous = names.putIfAbsent(
                    equipment.equipmentId(), equipment.equipmentName());
                if (previous != null && !previous.equals(equipment.equipmentName())) {
                  throw new IllegalStateException(
                      "Stored repair furniture has conflicting equipment names");
                }
              });
    }
    return Map.copyOf(names);
  }

  private record FurnitureQuantity(UUID equipmentId, long quantity) {}

  private void replaceRepairStages(MaintenanceRepair repair, List<PlanStageInput> inputs) {
    replaceRepairStages(repair, inputs, List.of());
  }

  private void replaceRepairStages(
      MaintenanceRepair repair,
      List<PlanStageInput> inputs,
      List<EstimateLineResponse> lines) {
    validatePlan(inputs, lines.isEmpty());
    inputs = resolvePlanContent(lines, inputs);
    validatePlanContent(lines, inputs);
    validateCustomRoutingStructure(lines, inputs);
    Map<UUID, EstimateLineResponse> lineById =
        lines.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    EstimateLineResponse::id, value -> value));
    repairStages.deleteAllByRepairId(repair.getId());
    repairStages.flush();
    List<RepairStage> stages = new ArrayList<>();
    for (int index = 0; index < inputs.size(); index++) {
      PlanStageInput input = inputs.get(index);
      List<EstimateLineResponse> stageLines =
          input.includedLineIds().stream().map(lineById::get).toList();
      List<EstimateLineResponse> workLines =
          stageLines.stream().filter(this::isWorkLine).toList();
      List<EstimateLineResponse> materialLines =
          stageLines.stream().filter(line -> !isWorkLine(line)).toList();
      RepairStage stage = new RepairStage(
          input.id(), repair.getId(), input.order(), input.kind(), input.routing().queueId(),
          input.routing().queueName(), input.routing().queueType(),
          write(workLines), write(materialLines), input.primaryLineId(), input.groupComment(),
          input.taskDeadline());
      if (repair.getExecutionState() == RepairExecutionState.QUEUED) stage.queued();
      stages.add(stage);
    }
    repairStages.saveAllAndFlush(stages);
  }

  private List<EstimateLineResponse> canonicalRepairLines(
      UUID warehouseId, List<EstimateLineInput> inputs) {
    if (inputs == null) throw invalid("Repair lines are required");
    if (inputs.isEmpty()) return List.of();
    CatalogVersion active = requireActiveCatalog(warehouseId);
    List<EstimateLineResponse> result = new ArrayList<>();
    Set<UUID> ids = new HashSet<>();
    for (EstimateLineInput input : inputs) {
      if (input == null || input.id() == null || !ids.add(input.id())) {
        throw invalid("Repair line IDs must be present and unique");
      }
      CatalogNodeSnapshot snapshot =
          input.catalogSnapshot() == null
              ? null
              : canonicalCatalogSnapshot(
                  warehouseId, active.getId(), input.catalogSnapshot());
      EstimateLineType lineType = canonicalLineType(snapshot, input.lineType());
      String unit = canonicalLineUnit(snapshot, input.unit());
      BigDecimal quantity;
      try {
        quantity = new BigDecimal(input.quantity());
      } catch (NumberFormatException exception) {
        throw invalid("Repair line quantity is invalid");
      }
      long unitPriceMinor = moneyToMinor(input.unitPrice());
      int normativeMinutes =
          estimateLineNormativeMinutes(snapshot, lineType, input.normativeMinutes());
      result.add(
          new EstimateLineResponse(
              input.id(),
              snapshot,
              lineType,
              input.description(),
              unit,
              quantity(quantity),
              money(unitPriceMinor),
              money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              normativeMinutes,
              workLineComment(lineType, input.comment()),
              List.copyOf(input.mediaReferences())));
    }
    validateWorkLineMediaIsolation(result);
    return List.copyOf(result);
  }

  private static void validateWorkLineMediaIsolation(
      List<EstimateLineResponse> lines) {
    Set<UUID> assigned = new HashSet<>();
    for (EstimateLineResponse line : lines) {
      if (line.lineType() != EstimateLineType.WORK
          && !line.mediaReferences().isEmpty()) {
        throw invalid("Photos can only be assigned to work lines");
      }
      if (line.lineType() != EstimateLineType.WORK) continue;
      for (MediaReferenceInput reference : line.mediaReferences()) {
        if (!assigned.add(reference.mediaId())) {
          throw invalid("One photo cannot be assigned to multiple work lines");
        }
      }
    }
  }

  private static String workLineComment(
      EstimateLineType lineType, String comment) {
    if (lineType != EstimateLineType.WORK || comment == null || comment.isBlank()) {
      return null;
    }
    return comment.trim();
  }

  private List<EstimateLineResponse> canonicalReworkLines(
      MaintenanceRepair source, List<ReworkLineInput> inputs) {
    if (inputs == null) throw invalid("Rework lines are required");
    List<ReworkCandidateLine> candidates = reworkCandidateItems(source);
    Map<ReworkCandidateKey, ReworkCandidateLine> candidateBySource =
        candidates.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    value ->
                        new ReworkCandidateKey(
                            value.sourceRepairId(), value.sourceLineId()),
                    value -> value));
    Set<UUID> existingLineIds = repairChain(source).stream()
        .flatMap(
            repair ->
                repairStages.findAllByRepairIdOrderByStageNo(repair.getId()).stream())
        .flatMap(
            stage ->
                java.util.stream.Stream.concat(
                    readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
                    readList(stage.getMaterialLines(), EstimateLineResponse.class).stream()))
        .map(EstimateLineResponse::id)
        .collect(java.util.stream.Collectors.toSet());
    Set<UUID> childIds = new HashSet<>();
    Set<UUID> repeatedLineages = new HashSet<>();

    List<AddedReworkLineInput> added = inputs.stream()
        .filter(AddedReworkLineInput.class::isInstance)
        .map(AddedReworkLineInput.class::cast)
        .toList();
    for (AddedReworkLineInput input : added) {
      if (input.disposition() != ReworkLineDisposition.ADDED
          || input.line() == null
          || !input.id().equals(input.line().id())) {
        throw invalid("ADDED rework line identity is invalid");
      }
    }
    Map<UUID, EstimateLineResponse> canonicalAdded = canonicalRepairLines(
            source.getWarehouseId(), added.stream().map(AddedReworkLineInput::line).toList())
        .stream()
        .collect(java.util.stream.Collectors.toMap(EstimateLineResponse::id, value -> value));

    List<EstimateLineResponse> result = new ArrayList<>();
    for (ReworkLineInput input : inputs) {
      if (input == null
          || input.id() == null
          || !childIds.add(input.id())
          || existingLineIds.contains(input.id())) {
        throw invalid("Rework child line IDs must be new, present and unique");
      }
      if (input instanceof AddedReworkLineInput addedInput) {
        EstimateLineResponse line = canonicalAdded.get(addedInput.id());
        result.add(
            withReworkMetadata(
                line,
                ReworkLineDisposition.ADDED,
                null,
                null,
                line.id()));
        continue;
      }
      if (!(input instanceof RepeatReworkLineInput repeat)
          || repeat.disposition() != ReworkLineDisposition.REPEAT) {
        throw invalid("Unsupported rework line disposition");
      }
      ReworkCandidateLine candidate = candidateBySource.get(
          new ReworkCandidateKey(repeat.sourceRepairId(), repeat.sourceLineId()));
      if (candidate == null) {
        throw invalid(
            "REPEAT must reference the latest completed line in the same repair chain");
      }
      if (!repeatedLineages.add(candidate.lineageRootLineId())) {
        throw invalid("One rework can repeat a line lineage only once");
      }
      BigDecimal quantity;
      try {
        quantity = new BigDecimal(repeat.quantity());
      } catch (RuntimeException exception) {
        throw invalid("Rework line quantity is invalid");
      }
      if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3) {
        throw invalid("Rework line quantity is invalid");
      }
      EstimateLineResponse inherited = candidate.line();
      if (inherited.catalogSnapshot() != null
          && inherited.catalogSnapshot().furnitureEquipment() != null
          && quantity.stripTrailingZeros().scale() > 0) {
        throw invalid("Furniture quantity must be a whole number");
      }
      String comment = workLineComment(inherited.lineType(), repeat.comment());
      if (comment != null) {
        comment = comment.isBlank() ? null : comment.trim();
        if (comment != null && comment.length() > 2000) {
          throw invalid("Rework line comment is too long");
        }
      }
      long unitPriceMinor = moneyToMinor(inherited.unitPrice());
      result.add(
          new EstimateLineResponse(
              repeat.id(),
              inherited.catalogSnapshot(),
              inherited.lineType(),
              inherited.description(),
              inherited.unit(),
              quantity(quantity),
              inherited.unitPrice(),
              money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              inherited.normativeMinutes(),
              comment,
              inherited.mediaReferences(),
              ReworkLineDisposition.REPEAT,
              candidate.sourceRepairId(),
              candidate.sourceLineId(),
              candidate.lineageRootLineId()));
    }
    validateWorkLineMediaIsolation(result);
    return List.copyOf(result);
  }

  private List<ReworkCandidateLine> reworkCandidateItems(MaintenanceRepair source) {
    LinkedHashMap<UUID, ReworkCandidateLine> latestByLineage = new LinkedHashMap<>();
    for (MaintenanceRepair repair : repairChain(source)) {
      if (repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) continue;
      for (RepairStage stage :
          repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
        if (stage.getState() != RepairStageState.DONE) continue;
        List<EstimateLineResponse> completedLines =
            java.util.stream.Stream.concat(
                    readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
                    readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
                .toList();
        for (EstimateLineResponse line : completedLines) {
          UUID lineageRoot =
              line.lineageRootLineId() == null ? line.id() : line.lineageRootLineId();
          ReworkCandidateLine candidate =
              new ReworkCandidateLine(repair.getId(), line.id(), lineageRoot, line);
          latestByLineage.remove(lineageRoot);
          latestByLineage.put(lineageRoot, candidate);
        }
      }
    }
    return List.copyOf(latestByLineage.values());
  }

  private List<MaintenanceRepair> repairChain(MaintenanceRepair source) {
    UUID rootRepairId =
        source.getRootRepairId() == null ? source.getId() : source.getRootRepairId();
    List<MaintenanceRepair> chain = repairs.findRepairChain(rootRepairId);
    if (chain.isEmpty()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair chain is missing");
    }
    MaintenanceRepair root = chain.stream()
        .filter(value -> value.getId().equals(rootRepairId))
        .findFirst()
        .orElseThrow(
            () ->
                new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT", "Rework root repair is missing"));
    for (MaintenanceRepair repair : chain) {
      validateReworkOwnership(repair, root);
    }
    return List.copyOf(chain);
  }

  private static EstimateLineResponse withReworkMetadata(
      EstimateLineResponse line,
      ReworkLineDisposition disposition,
      UUID sourceRepairId,
      UUID sourceLineId,
      UUID lineageRootLineId) {
    return new EstimateLineResponse(
        line.id(),
        line.catalogSnapshot(),
        line.lineType(),
        line.description(),
        line.unit(),
        line.quantity(),
        line.unitPrice(),
        line.lineTotal(),
        line.normativeMinutes(),
        line.comment(),
        line.mediaReferences(),
        disposition,
        sourceRepairId,
        sourceLineId,
        lineageRootLineId);
  }

  private List<EstimateLineResponse> canonicalEstimateLines(
      MaintenanceEstimate estimate, List<EstimateLineInput> inputs) {
    if (inputs == null) throw invalid("Estimate lines are required");
    List<EstimateLineResponse> result = new ArrayList<>();
    Set<UUID> ids = new HashSet<>();
    for (EstimateLineInput input : inputs) {
      if (input == null || input.id() == null || !ids.add(input.id())) {
        throw invalid("Estimate line IDs must be present and unique");
      }
      CatalogNodeSnapshot snapshot =
          canonicalCatalogSnapshot(estimate, input.catalogSnapshot());
      EstimateLineType lineType = canonicalLineType(snapshot, input.lineType());
      String unit = canonicalLineUnit(snapshot, input.unit());
      BigDecimal quantity;
      try {
        quantity = new BigDecimal(input.quantity());
      } catch (NumberFormatException exception) {
        throw invalid("Estimate line quantity is invalid");
      }
      long unitPriceMinor = moneyToMinor(input.unitPrice());
      int normativeMinutes =
          estimateLineNormativeMinutes(snapshot, lineType, input.normativeMinutes());
      result.add(
          new EstimateLineResponse(
              input.id(),
              snapshot,
              lineType,
              input.description(),
              unit,
              quantity(quantity),
              money(unitPriceMinor),
              money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              normativeMinutes,
              workLineComment(lineType, input.comment()),
              List.copyOf(input.mediaReferences())));
    }
    return List.copyOf(result);
  }

  private void validatePlanContent(
      List<EstimateLineResponse> lines, List<PlanStageInput> plan) {
    Map<UUID, EstimateLineResponse> lineById =
        lines.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    EstimateLineResponse::id,
                    value -> value,
                    (left, right) -> {
                      throw invalid("Repair line IDs must be unique");
                    },
                    LinkedHashMap::new));
    Set<UUID> assigned = new HashSet<>();
    for (PlanStageInput stage : plan) {
      if (stage.includedLineIds() == null || stage.groupComment() == null) {
        throw invalid("Repair plan stage content is required");
      }
      Set<UUID> stageIds = new HashSet<>();
      for (UUID lineId : stage.includedLineIds()) {
        EstimateLineResponse line = lineById.get(lineId);
        if (line == null) throw invalid("Repair plan references an unavailable catalog line");
        if (!stageIds.add(lineId) || !assigned.add(lineId)) {
          throw invalid("Each repair line can belong to only one stage");
        }
        if (line.catalogSnapshot() == null && isWorkLine(line)) {
          requireCustomWorkRouting(stage.routing());
        } else if (isWorkLine(line)
            && !routingIdentity(stage.routing())
                .equals(routingIdentity(line.catalogSnapshot().routing()))) {
          throw invalid("Repair stage queue must match its catalog work queue");
        }
      }
      if (stage.primaryLineId() != null) {
        EstimateLineResponse primary = lineById.get(stage.primaryLineId());
        if (primary == null
            || !stageIds.contains(stage.primaryLineId())
            || !isWorkLine(primary)) {
          throw invalid("Primary stage line must be an included work");
        }
      }
      if (stage.primaryLineId() == null
          && stageIds.stream().map(lineById::get).anyMatch(this::isWorkLine)) {
        throw invalid("A repair-work stage containing work requires a primary work line");
      }
    }
    if (!assigned.equals(lineById.keySet())) {
      throw invalid("Every repair line must belong to exactly one repair-work stage");
    }
  }

  private List<PlanStageInput> resolvePlanContent(
      List<EstimateLineResponse> lines, List<PlanStageInput> plan) {
    if (lines.isEmpty()
        || plan.stream().anyMatch(stage -> !stage.includedLineIds().isEmpty())) {
      return plan;
    }
    Map<RoutingIdentity, List<PlanStageInput>> stagesByRoute =
        plan.stream()
        .collect(
                java.util.stream.Collectors.groupingBy(
                    stage -> routingIdentity(stage.routing()),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.toList()));
    Map<UUID, List<UUID>> included = new LinkedHashMap<>();
    for (PlanStageInput stage : plan) included.put(stage.id(), new ArrayList<>());
    for (EstimateLineResponse line : lines) {
      if (line.catalogSnapshot() == null) {
        throw invalid("Custom repair lines require explicit stage grouping and routing");
      }
      if (line.catalogSnapshot().routing() == null) {
        throw invalid("Every catalog repair line must inherit a queue from the catalog builder");
      }
      List<PlanStageInput> candidates =
          stagesByRoute.get(routingIdentity(line.catalogSnapshot().routing()));
      if (candidates == null || candidates.size() != 1) {
        throw invalid(
            "Catalog routing must identify exactly one repair-work stage; submit explicit line grouping");
      }
      included.get(candidates.getFirst().id()).add(line.id());
    }
    Map<UUID, EstimateLineResponse> lineById =
        lines.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    EstimateLineResponse::id, value -> value));
    return plan.stream()
        .map(
            stage -> {
              List<UUID> stageLineIds = List.copyOf(included.get(stage.id()));
              UUID primaryLineId =
                  stageLineIds.stream()
                      .filter(lineId -> isWorkLine(lineById.get(lineId)))
                      .findFirst()
                      .orElse(null);
              return new PlanStageInput(
                  stage.id(),
                  stage.kind(),
                  stage.order(),
                  stage.routing(),
                  stageLineIds,
                  primaryLineId,
                  stage.groupComment(),
                  stage.taskDeadline());
            })
        .toList();
  }

  private boolean isWorkLine(EstimateLineResponse line) {
    return line.lineType() == EstimateLineType.WORK;
  }

  private static void requireCustomWorkRouting(RoutingSnapshot routing) {
    String queueType = routing.queueType().trim().toUpperCase(Locale.ROOT);
    if (!"REPAIR".equals(queueType) && !"HOLDING".equals(queueType)) {
      throw invalid(
          "Custom repair works can only use a repair or holding queue");
    }
  }

  /**
   * Performs only local syntax and lineage validation. The task-board preflight is intentionally
   * separated into {@link #requireCustomRoutingReady(UUID, List)} so callers can make that
   * remote read after their prepare transaction has committed.
   */
  private void validateCustomRoutingStructure(
      List<EstimateLineResponse> lines, List<PlanStageInput> plan) {
    Set<UUID> customLineIds =
        lines.stream()
            .filter(line -> line.catalogSnapshot() == null)
            .map(EstimateLineResponse::id)
            .collect(java.util.stream.Collectors.toSet());
    List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        customRoutingRequirements(plan);
    if (requirements.isEmpty() && !customLineIds.isEmpty()) {
      throw invalid("Every custom repair line requires a selected queue");
    }
  }

  /**
   * Reads task-board routing truth outside a local transaction. This method deliberately rejects
   * an ambient transaction instead of suspending it: suspending would retain caller locks while
   * the dependency request waits.
   */
  private void requireCustomRoutingReady(UUID warehouseId, List<PlanStageInput> plan) {
    requireNoCallerTransaction("validate task-board routing");
    List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        customRoutingRequirements(plan);
    if (requirements.isEmpty()) return;
    MaintenanceDependencyGateway.RoutingPreflight preflight =
        dependencies.preflightMaintenanceRouting(
            warehouseId, requirements);
    if (preflight == null || !preflight.ready()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_ROUTING_INVALID",
          "A required global queue is not connected to this warehouse");
    }
  }

  private static List<MaintenanceDependencyGateway.RoutingQueueRequirement>
      customRoutingRequirements(List<PlanStageInput> plan) {
    Map<UUID, MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        new LinkedHashMap<>();
    for (PlanStageInput stage : plan) {
      String queueType = stage.routing().queueType().trim().toUpperCase(Locale.ROOT);
      requireCustomWorkRouting(stage.routing());
      MaintenanceDependencyGateway.RoutingQueueRequirement requirement =
          new MaintenanceDependencyGateway.RoutingQueueRequirement(
              stage.routing().queueId(), queueType);
      MaintenanceDependencyGateway.RoutingQueueRequirement previous =
          requirements.putIfAbsent(requirement.queueDefinitionId(), requirement);
      if (previous != null && !previous.equals(requirement)) {
        throw invalid("One custom-line queue ID has conflicting routing snapshots");
      }
    }
    return List.copyOf(requirements.values());
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

  private void replaceLogisticsReturnMedia(
      MaintenanceEstimate estimate, List<MediaReferenceInput> requested) {
    List<MaintenanceMediaReference> values =
        requested.stream()
            .map(
                reference ->
                    new MaintenanceMediaReference(
                        "ESTIMATE",
                        estimate.getId(),
                        reference.mediaId(),
                        reference.generation(),
                        "MAINTENANCE_ESTIMATE",
                        estimate.getWarehouseId(),
                        mediaFacts
                            .findById(reference.mediaId())
                            .map(MediaFactProjection::getSafeMetadata)
                            .orElse("{}")))
            .toList();
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

  private void validateLineMediaReferences(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      List<EstimateLineResponse> lines) {
    validateMediaReferences(
        ownerType,
        ownerId,
        warehouseId,
        lines.stream()
            .flatMap(line -> line.mediaReferences().stream())
            .toList());
  }

  private void validateUpdatedRepairLineMediaReferences(
      MaintenanceRepair repair, List<EstimateLineResponse> requestedLines) {
    Map<UUID, StoredLineMedia> storedByMediaId = new HashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      java.util.stream.Stream.concat(
              readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
              readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
          .forEach(
              line ->
                  line.mediaReferences()
                      .forEach(
                          reference -> {
                            StoredLineMedia previous =
                                storedByMediaId.putIfAbsent(
                                    reference.mediaId(),
                                    new StoredLineMedia(line.id(), reference));
                            if (previous != null
                                && (!previous.lineId().equals(line.id())
                                    || !previous.reference().equals(reference))) {
                              throw new IllegalStateException(
                                  "Stored repair photo belongs to multiple work lines");
                            }
                          }));
    }

    List<MediaReferenceInput> newlyAssigned = new ArrayList<>();
    for (EstimateLineResponse line : requestedLines) {
      for (MediaReferenceInput reference : line.mediaReferences()) {
        StoredLineMedia stored = storedByMediaId.get(reference.mediaId());
        if (stored == null) {
          newlyAssigned.add(reference);
          continue;
        }
        if (!stored.lineId().equals(line.id()) || !stored.reference().equals(reference)) {
          throw invalid("A photo already assigned to another work cannot be moved");
        }
      }
    }
    validateMediaReferences(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        newlyAssigned);
  }

  private record StoredLineMedia(UUID lineId, MediaReferenceInput reference) {}

  private static void validateCoverMediaSelection(
      List<MediaReferenceInput> requested, UUID coverMediaId) {
    List<MediaReferenceInput> values = requested == null ? List.of() : requested;
    if (values.isEmpty()) {
      if (coverMediaId != null) {
        throw invalid("Cover photo must be null when aggregate media is empty");
      }
      return;
    }
    if (coverMediaId == null) {
      throw invalid("A cover photo must be selected when aggregate media is present");
    }
    if (values.stream().noneMatch(value -> coverMediaId.equals(value.mediaId()))) {
      throw invalid("Cover photo must reference one of the aggregate media objects");
    }
  }

  private CatalogVersion requireMutableCatalog(UUID id, long expectedVersion, boolean lock) {
    CatalogVersion version = lock
        ? catalogVersions.findByIdForUpdate(id)
            .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"))
        : requireCatalog(id);
    assertVersion(version.getVersion(), expectedVersion);
    if (version.getState() == CatalogVersionState.SUPERSEDED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Superseded catalog versions are immutable");
    }
    return version;
  }

  private Map<UUID, String> resolveCabinCharacteristicNames(
      List<CatalogNodeInput> nodes) {
    requireNoCallerTransaction("resolve cabin characteristic truth");
    Set<UUID> requestedIds =
        nodes.stream()
            .map(CatalogNodeInput::characteristicId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (requestedIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> namesByCharacteristicId =
        dependencies.cabinCharacteristics().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MaintenanceDependencyGateway.CabinCharacteristicSnapshot::characteristicId,
                    MaintenanceDependencyGateway.CabinCharacteristicSnapshot::characteristicName,
                    (left, right) -> {
                      throw new MaintenanceDependencyException(
                          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                          "Asset-service returned duplicate cabin characteristic identities");
                    },
                    LinkedHashMap::new));
    Set<UUID> missing = new LinkedHashSet<>(requestedIds);
    missing.removeAll(namesByCharacteristicId.keySet());
    if (!missing.isEmpty()) {
      throw invalid(
          "Catalog material references an unavailable cabin characteristic: "
              + missing.iterator().next());
    }
    Map<UUID, String> result = new LinkedHashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (node.characteristicId() != null) {
        result.put(
            node.id(),
            namesByCharacteristicId.get(node.characteristicId()));
      }
    }
    return Map.copyOf(result);
  }

  private Map<UUID, String> catalogCharacteristicNames(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .filter(node -> node.getCharacteristicId() != null)
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                CatalogNode::getId, CatalogNode::getCharacteristicName));
  }

  private CatalogValidation validateCatalog(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {
    if (nodes == null || links == null) throw new IllegalArgumentException("Catalog arrays are required");
    Set<UUID> ids = new HashSet<>();
    Set<String> workAndMaterialNames = new HashSet<>();
    Map<UUID, CatalogNodeInput> nodesById = new HashMap<>();
    Map<UUID, FurnitureEquipmentReference> equipmentSnapshots = new HashMap<>();
    Map<UUID, List<UUID>> parents = new HashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (!ids.add(node.id())) throw invalid("Duplicate catalog node ID");
      if (node.nodeType() == CatalogNodeType.WORK
          || node.nodeType() == CatalogNodeType.MATERIAL) {
        String identity =
            node.nodeType().name()
                + ":"
                + Objects.toString(node.parentNodeId(), "ROOT")
                + ":"
                + Objects.toString(node.name(), "")
                    .trim()
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
        if (!workAndMaterialNames.add(identity)) {
          throw invalid(
              "Duplicate work/material name under the same catalog parent: "
                  + node.name());
        }
      }
      nodesById.put(node.id(), node);
      if (node.nodeType() == CatalogNodeType.WORK
          && (node.durationMinutes() == null || node.durationMinutes() < 1)) {
        throw invalid("Catalog WORK durationMinutes must be positive");
      }
      if (Boolean.TRUE.equals(node.furnitureCategory())
          && node.nodeType() != CatalogNodeType.CATEGORY) {
        throw invalid("Only a catalog category can mark a furniture tree");
      }
      if (node.furnitureEquipment() != null && node.nodeType() != CatalogNodeType.MATERIAL) {
        throw invalid("Only a material can reference furniture equipment");
      }
      if (Boolean.TRUE.equals(node.forcesCapitalRepair())
          && node.nodeType() != CatalogNodeType.WORK) {
        throw invalid("Only a work can force capital repair");
      }
      if (node.characteristicId() != null
          && node.nodeType() != CatalogNodeType.MATERIAL) {
        throw invalid("Only a material can reference a cabin characteristic");
      }
      if (node.furnitureEquipment() != null) {
        FurnitureEquipmentReference previous = equipmentSnapshots.putIfAbsent(
            node.furnitureEquipment().equipmentId(), node.furnitureEquipment());
        if (previous != null
            && !previous.equipmentName().equals(node.furnitureEquipment().equipmentName())) {
          throw invalid("One furniture equipment ID must use one canonical name");
        }
      }
    }
    for (CatalogNodeInput node : nodes) {
      if (node.parentNodeId() == null) continue;
      if (node.id().equals(node.parentNodeId())) throw invalid("Catalog node cannot parent itself");
      if (!ids.contains(node.parentNodeId())) throw invalid("Catalog parent node is missing");
      parents.computeIfAbsent(node.id(), ignored -> new ArrayList<>()).add(node.parentNodeId());
    }
    if (containsCycle(ids, parents)) throw invalid("Catalog parent hierarchy contains a cycle");
    Set<UUID> furnitureRoots = nodes.stream()
        .filter(MaintenanceApplicationService::marksFurnitureTree)
        .map(CatalogNodeInput::id)
        .collect(java.util.stream.Collectors.toSet());
    for (CatalogNodeInput node : nodes) {
      if (node.furnitureEquipment() != null
          && !belongsToFurnitureTree(node.id(), furnitureRoots, nodesById)) {
        throw invalid("Furniture equipment can only be linked inside a furniture category");
      }
    }
    Set<UUID> linkIds = new HashSet<>();
    Set<String> typedEdges = new HashSet<>();
    Map<UUID, List<UUID>> dependency = new HashMap<>();
    for (CatalogLinkInput link : links) {
      if (!linkIds.add(link.id())) throw invalid("Duplicate catalog link ID");
      if (!ids.contains(link.fromNodeId()) || !ids.contains(link.toNodeId())) {
        throw invalid("Catalog link endpoint is missing");
      }
      if (link.fromNodeId().equals(link.toNodeId())) throw invalid("Catalog link cannot self-reference");
      if ((link.sourceAnchor() == null) != (link.targetAnchor() == null)) {
        throw invalid("Catalog link anchors must be both absent or both present");
      }
      String edge = link.fromNodeId() + ":" + link.toNodeId() + ":" + link.linkType().name();
      if (!typedEdges.add(edge)) throw invalid("Duplicate typed catalog edge");
      if (link.linkType() == CatalogLinkType.DEPENDENCY) {
        dependency.computeIfAbsent(link.fromNodeId(), ignored -> new ArrayList<>()).add(link.toNodeId());
      }
    }
    if (containsCycle(ids, dependency)) throw invalid("Catalog dependency graph contains a cycle");
    return new CatalogValidation(true);
  }

  private void validateFurnitureCatalogForActivation(UUID catalogVersionId) {
    List<CatalogNode> nodes =
        catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId);
    Map<UUID, CatalogNode> nodesById = nodes.stream().collect(
        java.util.stream.Collectors.toMap(CatalogNode::getId, value -> value));
    boolean missingEquipment = nodes.stream().anyMatch(node ->
        node.isActive()
            && node.isIncludeInEstimate()
            && "MATERIAL".equals(node.getNodeType())
            && belongsToFurnitureTree(node, nodesById)
            && node.getFurnitureEquipmentId() == null);
    if (missingEquipment) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Every active furniture material must be linked to additional equipment before activation");
    }
    Map<UUID, CatalogNode> equipmentSnapshots = new HashMap<>();
    for (CatalogNode node : nodes) {
      if (node.getFurnitureEquipmentId() == null) continue;
      CatalogNode previous = equipmentSnapshots.putIfAbsent(node.getFurnitureEquipmentId(), node);
      if (previous != null
          && !previous.getFurnitureEquipmentName().equals(node.getFurnitureEquipmentName())) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED",
            "One furniture equipment ID must use one canonical name");
      }
    }
  }

  private Map<UUID, RoutingSnapshot> canonicalCatalogRouting(
      List<CatalogNodeInput> nodes) {
    requireNoCallerTransaction("resolve canonical catalog routing");
    Map<UUID, CatalogRoutingInput> unique = new LinkedHashMap<>();
    for (CatalogNodeInput node : nodes) {
      CatalogRoutingInput routing = directRouting(node);
      if (routing == null) continue;
      CatalogRoutingInput previous = unique.putIfAbsent(routing.queueId(), routing);
      if (previous != null && !previous.equals(routing)) {
        throw invalid("One queue ID has conflicting catalog routing types");
      }
    }
    if (unique.isEmpty()) return Map.of();
    List<MaintenanceDependencyGateway.CatalogRoutingQueueRequirement> requirements =
        unique.values().stream()
            .map(routing -> new MaintenanceDependencyGateway.CatalogRoutingQueueRequirement(
                routing.queueId(), routing.queueType().trim().toUpperCase(Locale.ROOT)))
            .toList();
    MaintenanceDependencyGateway.CatalogRoutingPreflight preflight =
        dependencies.preflightCatalogRouting(requirements);
    if (preflight == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board omitted canonical catalog routing truth");
    }
    Map<UUID, MaintenanceDependencyGateway.CatalogRoutingQueueRequirement> byId =
        requirements.stream().collect(java.util.stream.Collectors.toMap(
            MaintenanceDependencyGateway.CatalogRoutingQueueRequirement::queueDefinitionId,
            value -> value));
    if (!preflight.ready()) {
      List<FieldViolation> violations = new ArrayList<>();
      for (UUID missingQueueId : preflight.missingQueueDefinitionIds()) {
        if (!byId.containsKey(missingQueueId)) continue;
        violations.add(new FieldViolation(
            "routing." + missingQueueId,
            "MAINTENANCE_ROUTING_QUEUE_MISSING",
            "Required task-board queue " + missingQueueId + " is missing"));
      }
      for (MaintenanceDependencyGateway.CatalogRoutingMismatch mismatch :
          preflight.mismatches()) {
        if (!byId.containsKey(mismatch.queueDefinitionId())) continue;
        violations.add(new FieldViolation(
            "routing." + mismatch.queueDefinitionId(),
            "MAINTENANCE_ROUTING_QUEUE_MISMATCH",
            "Task-board queue definition " + mismatch.queueDefinitionId() + " differs in "
                + String.join(", ", mismatch.fields())));
      }
      if (violations.isEmpty()) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board returned an unexplained routing preflight failure");
      }
      throw new MaintenanceCatalogValidationException(violations);
    }
    Map<UUID, RoutingSnapshot> byQueueId = new LinkedHashMap<>();
    for (MaintenanceDependencyGateway.QueueDefinitionSnapshot queue :
        preflight.resolvedDefinitions()) {
      MaintenanceDependencyGateway.CatalogRoutingQueueRequirement requirement =
          byId.get(queue.queueDefinitionId());
      if (requirement == null
          || !requirement.type().equals(queue.type().trim().toUpperCase(Locale.ROOT))) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board returned inconsistent canonical routing truth");
      }
      byQueueId.put(
          queue.queueDefinitionId(),
          new RoutingSnapshot(queue.queueDefinitionId(), queue.name(), queue.type()));
    }
    if (!byQueueId.keySet().containsAll(unique.keySet())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board omitted a canonical routing snapshot");
    }
    Map<UUID, RoutingSnapshot> result = new LinkedHashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (node.routing() != null) result.put(node.id(), byQueueId.get(node.routing().queueId()));
    }
    return Map.copyOf(result);
  }

  private Map<UUID, RoutingSnapshot> canonicalRoutingFromCatalog(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .filter(node -> node.getRoutingQueueId() != null)
        .collect(java.util.stream.Collectors.toMap(
            CatalogNode::getId,
            node -> new RoutingSnapshot(
                node.getRoutingQueueId(), node.getRoutingQueueName(), node.getRoutingQueueType()),
            (first, ignored) -> first,
            LinkedHashMap::new));
  }

  private void validateCatalogRoutingForActivation(UUID catalogVersionId) {
    validateCatalogRoutingForActivation(
        catalogNodeInputs(catalogVersionId), catalogLinkInputs(catalogVersionId));
  }

  private void validateCatalogRoutingForActivation(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {
    Map<UUID, CatalogNodeInput> nodesById =
        nodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNodeInput::id, value -> value));
    Map<UUID, List<UUID>> incomingLinks = CatalogRoutingResolver.incomingInputs(links);
    List<FieldViolation> violations = new ArrayList<>();
    for (CatalogNodeInput node : nodes) {
      CatalogRoutingInput direct = directRouting(node);
      if (direct != null
          && !("REPAIR".equals(direct.queueType())
              || "HOLDING".equals(direct.queueType()))) {
        violations.add(
            new FieldViolation(
                "nodes." + node.id() + ".routing",
                "MAINTENANCE_ROUTING_QUEUE_TYPE_UNSUPPORTED",
                "Catalog categories may route only to REPAIR or HOLDING queues"));
      }
      if (node.active()
          && node.includeInEstimate()
          && node.nodeType() == CatalogNodeType.WORK
          && CatalogRoutingResolver.resolve(
                  node.id(),
                  nodesById,
                  CatalogNodeInput::parentNodeId,
                  MaintenanceApplicationService::directRouteValue,
                  incomingLinks)
              == null) {
        violations.add(
            new FieldViolation(
                "nodes." + node.id() + ".routing",
                "MAINTENANCE_ROUTING_QUEUE_REQUIRED",
                "Every active estimate work must inherit or define a task-board queue"));
      }
    }
    if (!violations.isEmpty()) {
      throw new MaintenanceCatalogValidationException(violations);
    }
  }

  private static RoutingSnapshot directRouting(CatalogNode node) {
    return node.getRoutingQueueId() == null
        ? null
        : new RoutingSnapshot(
            node.getRoutingQueueId(),
            node.getRoutingQueueName(),
            node.getRoutingQueueType());
  }

  private static CatalogRoutingInput directRouting(CatalogNodeInput node) {
    return node.routing();
  }

  private static CatalogRoutingResolver.Route directRouteValue(CatalogNode node) {
    return CatalogRoutingResolver.from(directRouting(node));
  }

  private static CatalogRoutingResolver.Route directRouteValue(CatalogNodeInput node) {
    return CatalogRoutingResolver.from(directRouting(node));
  }

  private void enqueueCatalogRouting(
      CatalogVersion active, CatalogVersion superseded) {
    List<CatalogNode> activeRouted = routedCatalogNodes(active.getId());
    List<UUID> registrationKeys = new ArrayList<>();
    for (CatalogNode node : activeRouted) {
      UUID registrationKey = catalogRegistrationKey(active.getId(), node);
      registrationKeys.add(registrationKey);
      reconciliations.enqueueCatalogPosition(
          "REGISTER_CATALOG_POSITION",
          registrationKey,
          active.getId(),
          node.getId(),
          node.getRoutingQueueId(),
          catalogExternalReference(active.getId(), node.getId()),
          List.of());
    }
    if (superseded == null) return;
    List<UUID> cleanupPredecessors = new ArrayList<>(registrationKeys);
    cleanupPredecessors.addAll(reconciliations.catalogRegistrationKeys(superseded.getId()));
    cleanupPredecessors = cleanupPredecessors.stream().distinct().toList();
    for (CatalogNode node : routedCatalogNodes(superseded.getId())) {
      reconciliations.enqueueCatalogPosition(
          "DELETE_CATALOG_POSITION",
          stableOperationKey(
              "delete-catalog-position:" + node.getId() + ":" + active.getId(),
              superseded.getId(),
              0),
          superseded.getId(),
          node.getId(),
          node.getRoutingQueueId(),
          catalogExternalReference(superseded.getId(), node.getId()),
          cleanupPredecessors);
    }
  }

  private void enqueueCatalogRoutingChange(
      CatalogVersion active,
      List<CatalogNodeInput> previousNodes,
      List<CatalogNodeInput> currentNodes) {
    Map<UUID, CatalogNodeInput> previousById =
        previousNodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNodeInput::id, value -> value));
    Map<UUID, CatalogNodeInput> currentById =
        currentNodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNodeInput::id, value -> value));
    Set<UUID> nodeIds = new java.util.TreeSet<>(Comparator.comparing(UUID::toString));
    nodeIds.addAll(previousById.keySet());
    nodeIds.addAll(currentById.keySet());
    for (UUID nodeId : nodeIds) {
      CatalogNodeInput previous = previousById.get(nodeId);
      CatalogNodeInput current = currentById.get(nodeId);
      CatalogRoutingInput previousRouting = previous == null ? null : previous.routing();
      CatalogRoutingInput currentRouting = current == null ? null : current.routing();
      if (java.util.Objects.equals(previousRouting, currentRouting)) continue;
      List<UUID> predecessors = new ArrayList<>();
      if (previousRouting != null) {
        UUID deleteKey =
            stableOperationKey(
                "delete-catalog-position-change:"
                    + nodeId
                    + ":"
                    + previousRouting.queueId()
                    + ":"
                    + active.getVersion(),
                active.getId(),
                0);
        reconciliations.enqueueCatalogPosition(
            "DELETE_CATALOG_POSITION",
            deleteKey,
            active.getId(),
            nodeId,
            previousRouting.queueId(),
            catalogExternalReference(active.getId(), nodeId),
            List.of());
        predecessors.add(deleteKey);
      }
      if (currentRouting != null) {
        UUID registerKey =
            stableOperationKey(
                "register-catalog-position-change:"
                    + nodeId
                    + ":"
                    + currentRouting.queueId()
                    + ":"
                    + active.getVersion(),
                active.getId(),
                0);
        reconciliations.enqueueCatalogPosition(
            "REGISTER_CATALOG_POSITION",
            registerKey,
            active.getId(),
            nodeId,
            currentRouting.queueId(),
            catalogExternalReference(active.getId(), nodeId),
            predecessors);
      }
    }
  }

  private List<CatalogNode> routedCatalogNodes(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .filter(node -> node.getRoutingQueueId() != null)
        .toList();
  }

  private static UUID catalogRegistrationKey(UUID catalogVersionId, CatalogNode node) {
    return stableOperationKey(
        "register-catalog-position:" + node.getId() + ":" + node.getRoutingQueueId(),
        catalogVersionId,
        0);
  }

  private static String catalogExternalReference(UUID catalogVersionId, UUID catalogNodeId) {
    return "catalog:" + catalogVersionId + ":" + catalogNodeId;
  }

  private static boolean marksFurnitureTree(CatalogNodeInput node) {
    return node.nodeType() == CatalogNodeType.CATEGORY
        && Boolean.TRUE.equals(node.furnitureCategory());
  }

  private static boolean belongsToFurnitureTree(
      UUID nodeId,
      Set<UUID> furnitureRoots,
      Map<UUID, CatalogNodeInput> nodesById) {
    Set<UUID> visited = new HashSet<>();
    CatalogNodeInput current = nodesById.get(nodeId);
    while (current != null && visited.add(current.id())) {
      if (furnitureRoots.contains(current.id())) return true;
      current = current.parentNodeId() == null ? null : nodesById.get(current.parentNodeId());
    }
    return false;
  }

  private static boolean belongsToFurnitureTree(
      CatalogNode node, Map<UUID, CatalogNode> nodesById) {
    Set<UUID> visited = new HashSet<>();
    CatalogNode current = node;
    while (current != null && visited.add(current.getId())) {
      if (current.isFurnitureCategory()) return true;
      current = current.getParentNodeId() == null
          ? null
          : nodesById.get(current.getParentNodeId());
    }
    return false;
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
      throw invalid("An empty estimate cannot contain repair stages");
    }
    if (!lines.isEmpty() && plan.isEmpty()) {
      throw invalid("A non-empty estimate requires a repair plan");
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
      if (stage.kind() != RepairStageKind.REPAIR_WORK) {
        throw invalid("Repair plans can contain REPAIR_WORK stages only");
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

  private Map<String, Object> forkCatalogReport(
      CatalogVersion source,
      String sourceSnapshotSha256,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links,
      CatalogValidation validation) {
    Map<String, Object> report = baseCatalogReport(nodes, links, validation);
    report.put("source", "CATALOG_BUILDER_FORK");
    report.put("sourceCatalogVersionId", source.getId().toString());
    report.put("sourceCatalogVersion", source.getVersion());
    report.put("sourceCatalogLifecycle", source.getState().name());
    report.put("sourceSnapshotSha256", sourceSnapshotSha256);
    report.put("sourceNodeCount", nodes.size());
    report.put("sourceLinkCount", links.size());
    report.put("sourceMaterialCount", materialCount(nodes));
    report.put("reportSha256", hash(report));
    return report;
  }

  private Map<String, Object> baseCatalogReport(
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links,
      CatalogValidation validation) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("valid", true);
    report.put("errorCount", 0);
    report.put("warningCount", 0);
    report.put("contentSha256", hash(new CatalogContent(nodes, links)));
    report.put("nodeCount", nodes.size());
    report.put("linkCount", links.size());
    report.put("materialCount", materialCount(nodes));
    report.put("dependencyAcyclic", validation.dependencyAcyclic());
    return report;
  }

  private void requireMatchingFork(
      CatalogVersion existing,
      CatalogForkSnapshot sourceSnapshot,
      String sourceSnapshotSha256) {
    if (existing.getState() != CatalogVersionState.DRAFT) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "The source snapshot is already bound to a published catalog version");
    }
    List<CatalogNodeInput> nodes = catalogNodeInputs(existing.getId());
    List<CatalogLinkInput> links = catalogLinkInputs(existing.getId());
    if (existing.getNodeCount() != nodes.size()
        || existing.getLinkCount() != links.size()) {
      throw catalogSourceConflict();
    }
    CatalogForkSnapshot actual = new CatalogForkSnapshot(
        sourceSnapshot.sourceCatalogVersionId(),
        sourceSnapshot.sourceCatalogVersion(),
        sourceSnapshot.sourceLifecycle(),
        nodes,
        links);
    Map<String, Object> report = jsonMap(existing.getValidationReport());
    if (!sourceSnapshotSha256.equals(existing.getSourceSha256())
        || !sourceSnapshotSha256.equals(hash(actual))
        || !sourceSnapshotSha256.equals(report.get("sourceSnapshotSha256"))
        || !sourceSnapshot.sourceCatalogVersionId().toString()
            .equals(report.get("sourceCatalogVersionId"))
        || !numberEquals(report.get("sourceCatalogVersion"), sourceSnapshot.sourceCatalogVersion())
        || !sourceSnapshot.sourceLifecycle().name().equals(report.get("sourceCatalogLifecycle"))) {
      throw catalogSourceConflict();
    }
  }

  private static boolean numberEquals(Object value, long expected) {
    return value instanceof Number number && number.longValue() == expected;
  }

  private static int materialCount(List<CatalogNodeInput> nodes) {
    return Math.toIntExact(
        nodes.stream()
            .filter(node -> node.nodeType() == CatalogNodeType.MATERIAL)
            .count());
  }

  private static MaintenanceConflictException catalogSourceConflict() {
    return new MaintenanceConflictException(
        "MAINTENANCE_STATE_CONFLICT",
        "Existing catalog source does not match the requested catalog snapshot");
  }

  private List<CatalogNodeInput> catalogNodeInputs(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .map(this::catalogNodeInput)
        .toList();
  }

  private CatalogNodeInput catalogNodeInput(CatalogNode value) {
    return new CatalogNodeInput(
        value.getId(),
        CatalogNodeType.valueOf(value.getNodeType()),
        value.getName(),
        value.isActive(),
        value.getParentNodeId(),
        value.isFurnitureCategory(),
        value.getFurnitureEquipmentId() == null
            ? null
            : catalogFurnitureMapper.toReference(value),
        value.getUnit(),
        money(value.getPriceMinor()),
        value.getDurationMinutes(),
        value.isIncludeInEstimate(),
        value.isCommonItem(),
        value.isShowInMainMenu(),
        value.getCanvasX(),
        value.getCanvasY(),
        value.getRoutingQueueId() == null
            ? null
            : new CatalogRoutingInput(
                value.getRoutingQueueId(),
                value.getRoutingQueueType()),
        value.getComment(),
        value.getDisplayColor(),
        value.isForcesCapitalRepair(),
        value.getCharacteristicId());
  }

  private List<CatalogLinkInput> catalogLinkInputs(UUID catalogVersionId) {
    return catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(catalogVersionId).stream()
        .map(link -> new CatalogLinkInput(
            link.getId(),
            link.getSourceNodeId(),
            link.getTargetNodeId(),
            CatalogLinkType.valueOf(link.getLinkType()),
            link.getSourceAnchor() == null
                ? null
                : CatalogLinkAnchor.valueOf(link.getSourceAnchor()),
            link.getTargetAnchor() == null
                ? null
                : CatalogLinkAnchor.valueOf(link.getTargetAnchor()),
            link.getSortOrder()))
        .toList();
  }

  private void saveCatalog(
      UUID versionId,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links,
      Map<UUID, RoutingSnapshot> canonicalRouting,
      Map<UUID, String> characteristicNames) {
    catalogNodes.saveAllAndFlush(nodes.stream().map(node -> new CatalogNode(
        node.id(), versionId, node.nodeType().name(), node.name(),
        node.active(), node.parentNodeId(), marksFurnitureTree(node),
        node.furnitureEquipment() == null ? null : node.furnitureEquipment().equipmentId(),
        node.furnitureEquipment() == null ? null : node.furnitureEquipment().equipmentName(),
        node.unit(),
        node.unitPrice() == null ? null : moneyToMinor(node.unitPrice()), node.durationMinutes(),
        node.includeInEstimate(), node.commonItem(), node.showInMainMenu(),
        node.canvasX(), node.canvasY(),
        node.routing() == null ? null : node.routing().queueId(),
        node.routing() == null ? null : requiredCanonicalRouting(canonicalRouting, node).queueName(),
        node.routing() == null ? null : requiredCanonicalRouting(canonicalRouting, node).queueType(),
        node.comment(),
        node.displayColor(),
        node.forcesCapitalRepair(),
        node.characteristicId(),
        node.characteristicId() == null
            ? null
            : requiredCharacteristicName(characteristicNames, node))).toList());
    catalogLinks.saveAllAndFlush(links.stream().map(link -> new CatalogLink(
        link.id(), versionId, link.fromNodeId(), link.toNodeId(), link.linkType().name(),
        link.sourceAnchor() == null ? null : link.sourceAnchor().name(),
        link.targetAnchor() == null ? null : link.targetAnchor().name(),
        link.sortOrder())).toList());
  }

  private static RoutingSnapshot requiredCanonicalRouting(
      Map<UUID, RoutingSnapshot> canonicalRouting, CatalogNodeInput node) {
    RoutingSnapshot routing = canonicalRouting.get(node.id());
    if (routing == null || !routing.queueId().equals(node.routing().queueId())) {
      throw new IllegalStateException("Catalog routing was not canonicalized by task-board");
    }
    return routing;
  }

  private static String requiredCharacteristicName(
      Map<UUID, String> characteristicNames, CatalogNodeInput node) {
    String name = characteristicNames.get(node.id());
    if (name == null || name.isBlank()) {
      throw new IllegalStateException(
          "Catalog characteristic identity was not resolved by asset-service");
    }
    return name;
  }

  private void requireNoActiveRework(MaintenanceRepair repair) {
    if (hasUnresolvedRework(repair.getId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Active rework blocks a terminal acceptance decision");
    }
  }

  private boolean hasUnresolvedRework(UUID sourceRepairId) {
    return repairs.existsBySourceRepairIdAndExecutionStateIn(
            sourceRepairId,
            List.of(
                RepairExecutionState.DRAFT,
                RepairExecutionState.QUEUED,
                RepairExecutionState.IN_PROGRESS))
        || repairs.existsBySourceRepairIdAndAcceptanceStateIn(
            sourceRepairId,
            List.of(RepairAcceptanceState.PENDING, RepairAcceptanceState.IN_REWORK));
  }

  /** Builds an exact local lease signature while the caller still owns only a short transaction. */
  private LeaseRefreshPlan prepareLeaseRefreshPlan(
      MaintenanceRepair repair, List<MaintenanceRepair> sourceChain) {
    MaintenanceRepair owner = leaseOwner(repair, sourceChain);
    List<MaintenanceRepair> copies = leaseCopies(repair, sourceChain, owner);
    requireRenewableLease(owner);
    for (MaintenanceRepair copy : copies) {
      requireRenewableLease(copy);
      if (!owner.getLeaseId().equals(copy.getLeaseId())
          || !owner.getFencingToken().equals(copy.getFencingToken())
          || copy.getLeaseVersion() > owner.getLeaseVersion()) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_LEASE_CONFLICT",
            "Repair chain does not have one current renewable lease snapshot");
      }
    }
    List<LeaseCopySignature> copySignatures =
        copies.stream()
            .map(
                value ->
                    new LeaseCopySignature(
                        value.getId(),
                        value.getVersion(),
                        value.getLeaseId(),
                        value.getLeaseVersion(),
                        value.getFencingToken(),
                        value.getLeaseExpiresAt(),
                        value.getLeaseReconciliationState()))
            .toList();
    return new LeaseRefreshPlan(
        owner.getId(),
        owner.getRentalItemId(),
        owner.getRentalItemVersionSnapshot(),
        ownerType(owner),
        ownerId(owner),
        owner.getLeaseId(),
        owner.getLeaseVersion(),
        owner.getFencingToken(),
        owner.getLeaseExpiresAt(),
        copySignatures);
  }

  /**
   * The only remote portion of command lease refresh. It receives an immutable signature that
   * was committed by a preceding prepare transaction, and therefore may never run in a local
   * transaction.
   */
  private LeaseRefresh refreshLeaseForCommand(LeaseRefreshPlan plan, UUID commandKey) {
    requireNoCallerTransaction("refresh a repair operation lease");
    MaintenanceDependencyGateway.LeaseSnapshot lease = new MaintenanceDependencyGateway.LeaseSnapshot(
        plan.leaseId(),
        plan.leaseVersion(),
        plan.rentalItemId(),
        plan.ownerType(),
        UUID.fromString(plan.ownerId()),
        plan.fencingToken(),
        plan.expiresAt());
    boolean ownerRenewed = false;
    if (leaseHasExpired(lease.expiresAt())) {
      lease = dependencies.acquireLease(
          derived(commandKey, "reacquire-lease"),
          plan.rentalItemId(),
          plan.rentalItemVersion(),
          plan.ownerType(),
          plan.ownerId());
      validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      requireFreshDependencyLease(lease);
      ownerRenewed = true;
    } else if (!leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          derived(commandKey, "renew-lease"),
          plan.leaseId(),
          plan.leaseVersion(),
          plan.fencingToken(),
          plan.ownerType(),
          plan.ownerId());
      validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      if (!plan.leaseId().equals(lease.leaseId())
          || plan.fencingToken() != lease.fencingToken()
          || lease.version() != Math.addExact(plan.leaseVersion(), 1)) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Asset-service lease renewal does not preserve the current fence and version");
      }
      requireFreshDependencyLease(lease);
      ownerRenewed = true;
    }
    return new LeaseRefresh(lease, ownerRenewed);
  }

  private void requireMatchingLeaseRefreshPlan(
      LeaseRefreshPlan expected, MaintenanceRepair repair, List<MaintenanceRepair> sourceChain) {
    LeaseRefreshPlan current = prepareLeaseRefreshPlan(repair, sourceChain);
    if (!expected.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair lease changed before command finalization");
    }
  }

  private static void applyLeaseSnapshot(
      MaintenanceRepair repair, MaintenanceDependencyGateway.LeaseSnapshot lease) {
    if (!repair.getLeaseId().equals(lease.leaseId())
        || repair.getFencingToken() != lease.fencingToken()) {
      repair.replaceExpiredLease(
          lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
    } else if (repair.getLeaseVersion() != lease.version()
        || !repair.getLeaseExpiresAt().equals(lease.expiresAt())) {
      repair.renewLease(lease.version(), lease.expiresAt());
    }
  }

  private MaintenanceRepair leaseOwner(
      MaintenanceRepair repair, List<MaintenanceRepair> sourceChain) {
    if (repair.getRootRepairId() == null) return repair;
    return sourceChain.stream()
        .filter(source -> repair.getRootRepairId().equals(source.getId()))
        .findFirst()
        .orElseThrow(
            () ->
                new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT", "Repair root is missing from its locked source chain"));
  }

  private static List<MaintenanceRepair> leaseCopies(
      MaintenanceRepair repair,
      List<MaintenanceRepair> sourceChain,
      MaintenanceRepair owner) {
    Map<UUID, MaintenanceRepair> copies = new LinkedHashMap<>();
    copies.put(repair.getId(), repair);
    sourceChain.forEach(source -> copies.put(source.getId(), source));
    copies.put(owner.getId(), owner);
    return List.copyOf(copies.values());
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
      MaintenanceRepair repair,
      List<MaintenanceRepair> sourceChain,
      boolean accepted,
      MaintenanceDependencyGateway.LeaseSnapshot lease) {
    for (MaintenanceRepair source : sourceChain) {
      UUID sourceId = source.getId();
      long expectedVersion = source.getVersion();
      applyLeaseSnapshot(source, lease);
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
      if (accepted) {
        inventorySuccessors.releaseAfterAcceptance(
            saved, lastRepairEventId(saved.getId()), saved.getDecisionRecordedAt());
      }
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

  /** Returns the exact maintenance event just appended in this transaction. */
  private UUID lastRepairEventId(UUID repairId) {
    UUID eventId = jdbc.queryForObject(
        """
        select last_event_id from event_stream_head
         where aggregate_type='REPAIR' and aggregate_id=?
        """,
        UUID.class,
        repairId.toString());
    if (eventId == null) {
      throw new IllegalStateException("Repair event stream has no terminal fact identity");
    }
    return eventId;
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

  /**
   * An ordinary repair is not executable by a repair queue until its inbound driver movement has
   * completed. Keeping this decision beside task registration prevents a repair board entry from
   * racing ahead of the physical cabin.
   */
  private void enqueueOrdinaryRepairExecution(
      MaintenanceRepair repair, UUID taskRegistrationKey, UUID driverTaskKey) {
    if (!requiresDriverDeliveryToRepair(repair)
        || repairPlaces.isOccupied(repair.getWarehouseId(), repair.getId())) {
      enqueueTaskRegistration(repair, taskRegistrationKey);
      return;
    }
    reconciliations.enqueue(
        repair.getId(),
        "LOGISTICS",
        "CREATE_DRIVER_TASK",
        driverTaskKey,
        Map.of(
            "repairId", repair.getId().toString(),
            "kind", "DELIVER_TO_REPAIR"));
  }

  private boolean requiresDriverDeliveryToRepair(MaintenanceRepair repair) {
    return repair.isMovementToRepair();
  }

  private static void prepareStagesForQueue(
      List<RepairStage> stages, boolean externalCapital) {
    for (RepairStage stage : stages) {
      if (externalCapital) {
        stage.completeAsExternalCapital();
      } else {
        stage.queued();
      }
    }
  }

  private void enqueueRepairComplexityStatusSync(
      MaintenanceRepair repair, UUID key) {
    reconciliations.enqueue(
        repair.getId(),
        "ASSET",
        "SYNC_REPAIR_COMPLEXITY_STATUS",
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

  private void enqueueAcceptedCharacteristics(
      MaintenanceRepair accepted, List<MaintenanceRepair> sourceChain) {
    Map<UUID, CabinCharacteristicReference> characteristics =
        new LinkedHashMap<>();
    List<MaintenanceRepair> acceptedChain = new ArrayList<>(sourceChain);
    acceptedChain.add(accepted);
    for (MaintenanceRepair repair : acceptedChain) {
      for (RepairStage stage :
          repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
        for (EstimateLineResponse material :
            readList(stage.getMaterialLines(), EstimateLineResponse.class)) {
          CabinCharacteristicReference characteristic =
              material.catalogSnapshot() == null
                  ? null
                  : material.catalogSnapshot().characteristic();
          if (characteristic == null) {
            continue;
          }
          CabinCharacteristicReference previous =
              characteristics.putIfAbsent(
                  characteristic.characteristicId(), characteristic);
          if (previous != null
              && !previous.characteristicName().equals(
                  characteristic.characteristicName())) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "One cabin characteristic has conflicting repair snapshots");
          }
        }
      }
    }
    UUID rootRepairId = rootId(accepted);
    for (CabinCharacteristicReference characteristic :
        characteristics.values().stream()
            .sorted(
                Comparator.comparing(
                    value -> value.characteristicId().toString()))
            .toList()) {
      UUID operationKey =
          UUID.nameUUIDFromBytes(
              ("apply-characteristic:"
                      + rootRepairId
                      + ":"
                      + characteristic.characteristicId())
                  .getBytes(StandardCharsets.UTF_8));
      reconciliations.enqueue(
          accepted.getId(),
          "ASSET",
          "APPLY_CHARACTERISTIC",
          operationKey,
          Map.of(
              "repairId", accepted.getId().toString(),
              "rootRepairId", rootRepairId.toString(),
              "rentalItemId", accepted.getRentalItemId().toString(),
              "characteristicId",
                  characteristic.characteristicId().toString()));
    }
  }

  private void enqueueMediaOwnerProof(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      UUID sourceId,
      long sourceVersion,
      boolean active) {
    reconciliations.enqueueMediaOwnerProof(
        ownerType, ownerId, warehouseId, sourceId, sourceVersion, active);
  }

  private void confirmTaskRegistration(
      UUID repairId, MaintenanceDependencyGateway.TaskSnapshot task) {
    List<RepairStage> stages =
        repairStages.findAllByRepairIdOrderByStageNo(repairId);
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

  private Optional<MaintenanceRepair> primaryLifecycleOwnerWithLeaseIdentity(
      MaintenanceRepair repair) {
    List<MaintenanceRepair> owners = repairs.findPrimaryLifecycleOwnerWithLeaseIdentity(
        repair.getRentalItemId(), repair.getId(), org.springframework.data.domain.PageRequest.of(0, 2));
    if (owners.size() > 1) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Rental item in REPAIR has divergent primary repair lifecycle owners");
    }
    if (owners.isEmpty()) return Optional.empty();
    MaintenanceRepair owner = owners.getFirst();
    if (!repair.getWarehouseId().equals(owner.getWarehouseId())
        || owner.getLeaseVersion() == null
        || owner.getFencingToken() == null
        || owner.getLeaseExpiresAt() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Primary repair lifecycle owner lease identity is incomplete");
    }
    return Optional.of(owner);
  }

  private static void requireCatalogPositionWork(
      MaintenanceReconciliationStore.WorkItem work, String operation) {
    if (work.repairId() != null
        || !"TASK_BOARD".equals(work.dependency())
        || !operation.equals(work.operation())
        || work.catalogVersionId() == null
        || work.catalogNodeId() == null
        || work.catalogQueueId() == null
        || work.catalogExternalReferenceId() == null
        || work.catalogExternalReferenceId().isBlank()
        || !work.catalogVersionId().equals(uuidField(work.payload(), "catalogVersionId"))
        || !work.catalogNodeId().equals(uuidField(work.payload(), "catalogNodeId"))
        || !work.catalogQueueId().equals(uuidField(work.payload(), "queueId"))
        || !work.catalogExternalReferenceId().equals(
            stringField(work.payload(), "externalReferenceId"))) {
      throw new IllegalStateException("Stored catalog-position reconciliation is incomplete");
    }
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
    boolean changed;
    if ("TASK_BOARD".equals(work.dependency())) {
      repair.markTaskDeliveryFailed(quarantined);
      List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
      stages.forEach(stage -> stage.markTaskDeliveryFailed(quarantined));
      repairStages.saveAllAndFlush(stages);
      changed = true;
    } else {
      changed = quarantined
          ? repair.markReconciliationQuarantined()
          : repair.markReconciliationRequired();
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

  private List<MaintenanceDependencyGateway.TaskStage> taskStages(MaintenanceRepair repair) {
    List<MediaReferenceInput> repairMedia = new ArrayList<>(repairMedia(repair));
    return repairStages.findAllByRepairIdOrderByStageNo(repair.getId()).stream()
        .map(
            stage -> {
              List<EstimateLineResponse> work =
                  readList(stage.getWorkLines(), EstimateLineResponse.class);
              List<EstimateLineResponse> materials =
                  readList(stage.getMaterialLines(), EstimateLineResponse.class);
              List<MaintenanceDependencyGateway.TaskWork> workSnapshots =
                  taskWorkSnapshots(work);
              List<MaintenanceDependencyGateway.TaskMaterial> materialSnapshots =
                  materials.stream()
                      .map(
                          line ->
                              new MaintenanceDependencyGateway.TaskMaterial(
                                  line.id(),
                                  taskLineName(line),
                                  taskLineQuantity(line).doubleValue(),
                                  line.unit()))
                      .toList();
              List<MaintenanceDependencyGateway.TaskComment> comments = new ArrayList<>();
              work.stream()
                  .filter(line -> line.comment() != null && !line.comment().isBlank())
                  .map(
                      line ->
                          new MaintenanceDependencyGateway.TaskComment(
                              line.id(),
                              taskLineComment(line.comment()),
                              "Смета",
                              repair.getCreatedAt()))
                  .forEach(comments::add);
              if (stage.getGroupComment() != null && !stage.getGroupComment().isBlank()) {
                comments.add(
                    new MaintenanceDependencyGateway.TaskComment(
                        stage.getId(),
                        stage.getGroupComment().trim(),
                        "Диспетчер",
                        repair.getCreatedAt()));
              }
              LinkedHashMap<UUID, MediaReferenceInput> sourceMedia = new LinkedHashMap<>();
              repairMedia.forEach(reference -> sourceMedia.put(reference.mediaId(), reference));
              java.util.stream.Stream.concat(work.stream(), materials.stream())
                  .flatMap(line -> line.mediaReferences().stream())
                  .forEach(reference -> sourceMedia.put(reference.mediaId(), reference));
              return new MaintenanceDependencyGateway.TaskStage(
                  stage.getId(),
                  stage.getStageNo(),
                  stage.getStageKind(),
                  taskStageText(stage),
                  stage.getRoutingQueueId(),
                  stage.getTaskDeadline(),
                  workSnapshots,
                  materialSnapshots,
                  comments,
                  sourceMedia.values().stream()
                      .map(reference -> taskSourceMedia(reference, repair.getCreatedAt()))
                      .toList(),
                  plannedDurationMinutes(work));
            })
        .toList();
  }

  private static List<MaintenanceDependencyGateway.TaskWork> taskWorkSnapshots(
      List<EstimateLineResponse> work) {
    return work.stream()
        .map(
            line -> {
              CatalogNodeSnapshot catalog = line.catalogSnapshot();
              return new MaintenanceDependencyGateway.TaskWork(
                  line.id(),
                  taskLineName(line),
                  taskLineQuantity(line).doubleValue(),
                  line.unit(),
                  line.normativeMinutes(),
                  taskLineComment(line.comment()),
                  line.mediaReferences().stream()
                      .map(MediaReferenceInput::mediaId)
                      .distinct()
                      .toList());
            })
        .toList();
  }

  /** Converts each work's per-unit norm without underreporting fractional quantities. */
  private static Integer plannedDurationMinutes(List<EstimateLineResponse> work) {
    if (work.isEmpty()) return null;
    BigDecimal total = BigDecimal.ZERO;
    for (EstimateLineResponse line : work) {
      int duration = line.normativeMinutes();
      if (duration < 1) {
        throw new IllegalStateException(
            "Stored repair work has no positive planned duration");
      }
      total = total.add(BigDecimal.valueOf(duration).multiply(taskLineQuantity(line)));
    }
    try {
      int result = total.setScale(0, RoundingMode.CEILING).intValueExact();
      if (result < 1) {
        throw new IllegalStateException(
            "Stored repair work has no positive planned duration");
      }
      return result;
    } catch (ArithmeticException exception) {
      throw new IllegalStateException("Worker task planned duration exceeds the supported range", exception);
    }
  }

  private static int taskWorkDurationMinutes(CatalogNodeSnapshot catalog) {
    Integer duration = catalog.durationMinutes();
    if (duration == null || duration < 1) {
      throw new IllegalStateException(
          "Stored catalog work has no positive planned duration");
    }
    return duration;
  }

  private static int estimateLineNormativeMinutes(
      CatalogNodeSnapshot catalog, EstimateLineType lineType, Integer submittedMinutes) {
    if (catalog != null) {
      if (catalog.nodeType().name().equals(lineType.name())) {
        return lineType == EstimateLineType.MATERIAL
            ? 0
            : taskWorkDurationMinutes(catalog);
      }
      throw invalid("Catalog snapshot type does not match the estimate line");
    }
    if (lineType == EstimateLineType.MATERIAL) return 0;
    if (submittedMinutes == null || submittedMinutes < 1 || submittedMinutes > 525600) {
      throw invalid("Custom repair work requires execution time in minutes");
    }
    return submittedMinutes;
  }

  private static BigDecimal taskLineQuantity(EstimateLineResponse line) {
    if (line == null || line.quantity() == null) {
      throw new IllegalStateException("Worker task line quantity is missing");
    }
    try {
      BigDecimal quantity = new BigDecimal(line.quantity());
      if (quantity.signum() <= 0 || quantity.scale() > 3) {
        throw new IllegalStateException("Worker task line quantity is invalid");
      }
      double snapshotQuantity = quantity.doubleValue();
      if (!Double.isFinite(snapshotQuantity)) {
        throw new IllegalStateException("Worker task line quantity exceeds the supported range");
      }
      return quantity;
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("Worker task line quantity is invalid", exception);
    }
  }

  private static String taskLineName(EstimateLineResponse line) {
    if (line == null || line.description() == null || line.description().isBlank()) {
      throw new IllegalStateException("Worker task line name is missing");
    }
    return line.description().trim();
  }

  private static String taskLineComment(String comment) {
    return comment == null || comment.isBlank() ? null : comment.trim();
  }

  private MaintenanceDependencyGateway.TaskSourceMedia taskSourceMedia(
      MediaReferenceInput reference, OffsetDateTime fallbackRecordedAt) {
    MediaFactProjection fact = mediaFacts.findById(reference.mediaId()).orElse(null);
    String contentType = "application/octet-stream";
    OffsetDateTime capturedAt = null;
    OffsetDateTime recordedAt = fallbackRecordedAt;
    if (fact != null) {
      Map<String, Object> metadata = jsonMap(fact.getSafeMetadata());
      Object storedContentType = metadata.get("contentType");
      if (storedContentType instanceof String value && !value.isBlank()) {
        contentType = value;
      }
      Object storedCapturedAt = metadata.get("capturedAt");
      if (storedCapturedAt instanceof String value) {
        try {
          capturedAt = OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
          capturedAt = null;
        }
      }
      recordedAt = fact.getUpdatedAt();
    }
    if (recordedAt == null) {
      recordedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
    return new MaintenanceDependencyGateway.TaskSourceMedia(
        reference.mediaId(),
        reference.generation(),
        contentType,
        capturedAt,
        recordedAt);
  }

  private String taskStageText(RepairStage stage) {
    List<String> work =
        readList(stage.getWorkLines(), EstimateLineResponse.class).stream()
            .map(EstimateLineResponse::description)
            .filter(value -> value != null && !value.isBlank())
            .toList();
    List<String> materials =
        readList(stage.getMaterialLines(), EstimateLineResponse.class).stream()
            .map(
                line ->
                    line.description()
                        + " — "
                        + line.quantity()
                        + (line.unit() == null
                                || line.unit().isBlank()
                            ? ""
                            : " " + line.unit()))
            .toList();
    List<String> parts = new ArrayList<>();
    if (!work.isEmpty()) parts.add(String.join(", ", work));
    if (!materials.isEmpty()) parts.add("Материалы: " + String.join(", ", materials));
    if (stage.getGroupComment() != null && !stage.getGroupComment().isBlank()) {
      parts.add(stage.getGroupComment());
    }
    String result =
        parts.isEmpty() ? stage.getRoutingQueueName() : String.join(". ", parts);
    return result.length() <= 2000 ? result : result.substring(0, 2000);
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
      case "EMPTY_REPAIR_TO_FREE" -> MaintenanceEventType.REPAIR_PLAN_CHANGED;
      case "PENDING_ACCEPTANCE" -> MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE;
      case "ACCEPT_TO_FREE" -> MaintenanceEventType.REPAIR_ACCEPTED;
      case "WRITE_OFF" -> MaintenanceEventType.REPAIR_WRITTEN_OFF;
      default -> throw new IllegalArgumentException("Unsupported asset transition " + transition);
    };
  }

  private static MaintenanceEventType renewalEvent(MaintenanceRepair repair) {
    return repair.getExecutionState() == RepairExecutionState.CANCELLED
        ? MaintenanceEventType.REPAIR_PLAN_CHANGED
        : MaintenanceEventType.REPAIR_QUEUED;
  }

  private static MaintenanceEventType reconciliationEvent(
      MaintenanceRepair repair, String operation) {
    if ("UPDATE_TASK".equals(operation)
        || "SYNC_REPAIR_COMPLEXITY_STATUS".equals(operation)) {
      return MaintenanceEventType.REPAIR_PLAN_CHANGED;
    }
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

  private static void validateRenewedLeaseTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    validateLeaseTruth(repair, lease, ownerType, ownerId);
    long expectedVersion = Math.addExact(repair.getLeaseVersion(), 1);
    if (!repair.getLeaseId().equals(lease.leaseId())
        || repair.getFencingToken() != lease.fencingToken()
        || lease.version() != expectedVersion) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease renewal does not preserve the current fence and version");
    }
  }

  private static void validateAssetTruth(
      MaintenanceRepair repair, MaintenanceDependencyGateway.AssetSnapshot asset) {
    validateAssetTruth(repair, asset, repair.getRentalItemVersionSnapshot());
  }

  private static void validateAssetTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      long expectedVersion) {
    validateAssetTruth(repair, asset, expectedVersion, false);
  }

  private static void validateAssetTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      long expectedVersion,
      boolean allowUnchangedVersion) {
    if (asset == null || !repair.getRentalItemId().equals(asset.rentalItemId())
        || !repair.getWarehouseId().equals(asset.warehouseId())
        || asset.version() < expectedVersion
        || (!allowUnchangedVersion && asset.version() == expectedVersion)) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not match the expected rental item");
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

  private static String stringField(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual() || value.stringValue().isBlank()) {
      throw new IllegalStateException("Reconciliation payload is missing " + field);
    }
    return value.stringValue();
  }

  private static List<UUID> uuidListField(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalStateException("Reconciliation payload is missing " + field);
    }
    List<UUID> result = new ArrayList<>();
    value.forEach(
        item -> {
          if (!item.isTextual()) {
            throw new IllegalStateException(
                "Reconciliation payload has invalid " + field);
          }
          try {
            result.add(UUID.fromString(item.stringValue()));
          } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Reconciliation payload has invalid " + field, exception);
          }
        });
    if (result.size() != new HashSet<>(result).size()) {
      throw new IllegalStateException("Reconciliation payload has duplicate " + field);
    }
    return List.copyOf(result);
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

  private CatalogVersion requireCatalog(UUID id, UUID warehouseId) {
    Objects.requireNonNull(warehouseId, "Catalog authorization warehouse is required");
    return requireCatalog(id);
  }

  private CatalogVersion requireActiveCatalog(UUID warehouseId) {
    Objects.requireNonNull(warehouseId, "Catalog authorization warehouse is required");
    return catalogVersions
        .findFirstByStateOrderByActivatedAtDescCreatedAtDesc(CatalogVersionState.ACTIVE)
        .orElseThrow(
            () ->
                new MaintenanceValidationException(
                    "MAINTENANCE_VALIDATION_FAILED",
                    "Global maintenance catalog has no active version"));
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

  private static void requireEstimateSourceStatus(RentalItemFactProjection rentalItem) {
    if (!AFTER_RENT_STATUS.equals(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Estimate source rental item must have AFTER_RENT status");
    }
  }

  private static void requireDirectRepairSourceStatus(RentalItemFactProjection rentalItem) {
    if (RENTED_STATUS.equals(rentalItem.getAssetStatus())
        || AFTER_RENT_STATUS.equals(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Direct repair source rental item must not have RENTED or AFTER_RENT status");
    }
  }

  private static void assertVersion(long actual, long expected) {
    if (actual != expected) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Maintenance aggregate version conflict");
    }
  }

  private CatalogVersionResponse catalogResponse(CatalogVersion value) {
    Map<String, Object> validation = jsonMap(value.getValidationReport());
    CatalogRoutingSyncSnapshot routingSync = reconciliations.catalogRoutingTruth(value.getId())
        .map(truth -> new CatalogRoutingSyncSnapshot(
            DeliveryState.valueOf(truth.state()),
            truth.registrationsRequired(),
            truth.registrationsConfirmed(),
            truth.cleanupRequired(),
            truth.cleanupConfirmed(),
            truth.attempts(),
            truth.updatedAt()))
        .orElse(null);
    return new CatalogVersionResponse(
        value.getId(), value.getWarehouseId(), value.getVersion(), value.getState(),
        value.getSourceSha256(), new CatalogCounts(value.getNodeCount(), value.getLinkCount()),
        new CatalogValidationReport(
            booleanValue(validation, "valid"), intValue(validation, "errorCount"),
            intValue(validation, "warningCount"), stringValue(validation, "reportSha256")),
        value.getCreatedAt(), value.getActivatedAt(), routingSync);
  }

  private CatalogNodeResponse catalogNodeResponse(CatalogNode value) {
    return catalogNodeResponseMapper.toResponse(
        value,
        CatalogNodeType.valueOf(value.getNodeType()),
        value.getFurnitureEquipmentId() == null
            ? null
            : catalogFurnitureMapper.toReference(value),
        money(value.getPriceMinor()),
        value.getRoutingQueueId() == null
            ? null
            : new RoutingSnapshot(
                value.getRoutingQueueId(), value.getRoutingQueueName(), value.getRoutingQueueType()),
        value.getCharacteristicId() == null
            ? null
            : new CabinCharacteristicReference(
                value.getCharacteristicId(), value.getCharacteristicName()));
  }

  private CatalogLinkResponse catalogLinkResponse(CatalogLink value) {
    return new CatalogLinkResponse(
        value.getId(), value.getCatalogVersionId(), value.getSourceNodeId(), value.getTargetNodeId(),
        CatalogLinkType.valueOf(value.getLinkType()),
        value.getSourceAnchor() == null
            ? null
            : CatalogLinkAnchor.valueOf(value.getSourceAnchor()),
        value.getTargetAnchor() == null
            ? null
            : CatalogLinkAnchor.valueOf(value.getTargetAnchor()),
        value.getSortOrder());
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
    List<MediaReferenceInput> aggregateMedia = media("ESTIMATE", value.getId());
    return new EstimateResponse(
        value.getId(), value.getWarehouseId(), value.getRentalItemId(), value.getVersion(),
        value.getState(), value.getRevision(), revisions, value.getRepairId(),
        aggregateMedia, effectiveCoverMediaId(value.getCoverMediaId(), aggregateMedia),
        value.getCreatedAt(), value.getCompletedAt(),
        actor(value.getActorRef()));
  }

  private List<EstimateLineResponse> lineResponses(UUID estimateId, int revision) {
    return estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(estimateId, revision).stream()
        .map(line -> new EstimateLineResponse(
            line.getId(), line.getCatalogSnapshot() == null ? null
                : read(line.getCatalogSnapshot(), CatalogNodeSnapshot.class),
            EstimateLineType.valueOf(line.getLineType()),
            line.getTitle(), line.getUnit(), quantity(line.getQuantity()),
            money(line.getUnitPriceMinor()),
            money(line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor()))),
            requiredStoredDuration(line.getLineType(), line.getDurationMinutes()),
            line.getComment(), readList(line.getMediaReferences(), MediaReferenceInput.class)))
        .toList();
  }

  private static int requiredStoredDuration(String lineType, Integer duration) {
    if (duration == null
        || ("WORK".equals(lineType) && duration < 1)
        || ("MATERIAL".equals(lineType) && duration != 0)) {
      throw new IllegalStateException(
          "Stored estimate line duration violates the canonical invariant");
    }
    return duration;
  }

  private List<PlanStageInput> planResponses(UUID estimateId, int revision) {
    return estimatePlans.findAllByEstimateIdAndEstimateRevisionOrderByStageNo(estimateId, revision).stream()
        .map(stage -> new PlanStageInput(
            stage.getId(), stage.getStageKind(), stage.getStageNo(),
            new RoutingSnapshot(stage.getRoutingQueueId(), stage.getRoutingQueueName(),
                stage.getRoutingQueueType()),
            readList(stage.getIncludedLineIds(), UUID.class),
            stage.getPrimaryLineId(),
            stage.getGroupComment(),
            stage.getTaskDeadline()))
        .toList();
  }

  private RepairResponse repairResponse(MaintenanceRepair value) {
    Map<UUID, List<TaskEvidenceResponse>> evidenceByStage =
        taskEvidence.findAllByRepairIdOrderByRecordedAtAscEvidenceIdAsc(value.getId()).stream()
            .map(
                evidence ->
                    Map.entry(
                        evidence.getRepairStageId(),
                        new TaskEvidenceResponse(
                            evidence.getEvidenceId(),
                            evidence.getEntryId(),
                            evidence.getWorkerId(),
                            evidence.getWorkerGroupId(),
                            evidence.getMediaId(),
                            evidence.getMediaGeneration(),
                            evidence.getCapturedAt(),
                            evidence.getRecordedAt(),
                            TaskEvidenceState.valueOf(evidence.getEvidenceState()))))
            .collect(
                java.util.stream.Collectors.groupingBy(
                    Map.Entry::getKey,
                    LinkedHashMap::new,
                    java.util.stream.Collectors.mapping(
                        Map.Entry::getValue, java.util.stream.Collectors.toList())));
    List<RepairStageResponse> stages = repairStages.findAllByRepairIdOrderByStageNo(value.getId()).stream()
        .map(stage -> new RepairStageResponse(
            stage.getId(), stage.getStageKind(), stage.getStageNo(), stage.getState(),
            new RoutingSnapshot(stage.getRoutingQueueId(), stage.getRoutingQueueName(),
                stage.getRoutingQueueType()),
            readList(stage.getWorkLines(), EstimateLineResponse.class),
            readList(stage.getMaterialLines(), EstimateLineResponse.class),
            stage.getPrimaryLineId(),
            stage.getGroupComment(),
            List.copyOf(evidenceByStage.getOrDefault(stage.getId(), List.of())),
            stage.getTaskDeadline(), new TaskSyncSnapshot(
                value.getExternalTaskId(), stage.getExternalQueueEntryId(),
                stage.getTaskBoardVersion(),
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
    List<MediaReferenceInput> aggregateMedia = repairMedia(value);
    RepairComplexitySnapshot complexity = repairComplexity(value.getWarehouseId(), stages);
    return new RepairResponse(
        value.getId(), rootId(value), value.getSourceRepairId(), value.getEstimateId(),
        value.getWarehouseId(), value.getRentalItemId(), value.getOrigin(), value.getKind(),
        value.getExecutionState(), value.getAcceptanceState(), value.getReclassificationState(),
        value.getVersion(), value.getDispatchDate(),
        value.getPriority(), value.getSourceParty(),
        new RepairPlanResponse(value.getId(), value.getVersion(), stages),
        inventorySource,
        value.getLeaseId() == null ? null : new LeaseSnapshot(
            value.getLeaseId(), value.getFencingToken(), value.getLeaseExpiresAt(),
            leaseReconciliationState(value.getLeaseReconciliationState())),
        aggregateMedia, effectiveCoverMediaId(value.getCoverMediaId(), aggregateMedia),
        complexity,
        value.isMovementToRepair(),
        value.getLogisticsPlanningMode(),
        value.getLogisticsScheduledDate(),
        value.getCreatedAt(), value.getUpdatedAt(),
        actor(value.getActorRef()));
  }

  private RepairComplexitySnapshot repairComplexity(
      UUID warehouseId, List<RepairStageResponse> stages) {
    return repairComplexityForLines(
        warehouseId,
        stages.stream().flatMap(stage -> stage.workLines().stream()).toList());
  }

  private RepairComplexitySnapshot repairComplexityFromStoredStages(
      UUID warehouseId, UUID repairId) {
    List<EstimateLineResponse> lines =
        repairStages.findAllByRepairIdOrderByStageNo(repairId).stream()
            .flatMap(
                stage ->
                    readList(stage.getWorkLines(), EstimateLineResponse.class).stream())
            .toList();
    return repairComplexityForLines(warehouseId, lines);
  }

  private RepairComplexitySnapshot repairComplexityForLines(
      UUID warehouseId, List<EstimateLineResponse> lines) {
    BigDecimal plannedMinutes = BigDecimal.ZERO;
    boolean forcedCapital = false;
    for (EstimateLineResponse line : lines) {
      plannedMinutes =
          plannedMinutes.add(
              new BigDecimal(line.quantity())
                  .multiply(BigDecimal.valueOf(line.normativeMinutes())));
      if (line.catalogSnapshot() != null
          && line.catalogSnapshot().forcesCapitalRepair()) {
        forcedCapital = true;
      }
    }
    RepairComplexity type =
        repairComplexitySettings
            .requireSettings(warehouseId)
            .classify(plannedMinutes, forcedCapital);
    RepairComplexityColors colors = repairComplexityColors.requireColors();
    String canonicalMinutes =
        plannedMinutes.signum() == 0
            ? "0"
            : plannedMinutes.stripTrailingZeros().toPlainString();
    return new RepairComplexitySnapshot(
        type,
        type.displayName(),
        colors.color(type),
        canonicalMinutes,
        forcedCapital);
  }

  private MaintenanceRepair requireSingleActiveRepair(
      List<MaintenanceRepair> values, boolean preparedOnly) {
    List<MaintenanceRepair> active =
        values.stream()
            .filter(
                repair ->
                    repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
                        && repair.getAcceptanceState()
                            != RepairAcceptanceState.WRITTEN_OFF
                        && repair.getExecutionState()
                            != RepairExecutionState.CANCELLED)
            .filter(
                repair ->
                    !preparedOnly
                        || "DEPARTURE_PREPARED".equals(
                            repair.getTransferState()))
            .toList();
    Set<UUID> roots =
        active.stream()
            .map(MaintenanceApplicationService::rootId)
            .collect(java.util.stream.Collectors.toSet());
    if (roots.size() > 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Rental item has more than one active repair chain");
    }
    return active.stream()
        .max(
            Comparator.comparing(MaintenanceRepair::getCreatedAt)
                .thenComparing(repair -> repair.getId().toString()))
        .orElse(null);
  }

  private List<MaintenanceRepair> lockRepairChain(MaintenanceRepair active) {
    List<MaintenanceRepair> chain = repairs.findRepairChain(rootId(active));
    if (chain.isEmpty()) {
      throw new MaintenanceNotFoundException("Active repair chain not found");
    }
    return repairs.findAllByIdForUpdate(
        chain.stream().map(MaintenanceRepair::getId).toList());
  }

  private static MaintenanceRepair requireRepairInChain(
      List<MaintenanceRepair> chain, UUID repairId) {
    return chain.stream()
        .filter(repair -> repair.getId().equals(repairId))
        .findFirst()
        .orElseThrow(
            () ->
                new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT",
                    "Active repair changed during transfer"));
  }

  private static void requireTransferSource(
      List<MaintenanceRepair> chain,
      UUID rentalItemId,
      UUID sourceWarehouseId) {
    if (chain.stream()
        .anyMatch(
            repair ->
                !rentalItemId.equals(repair.getRentalItemId())
                    || !sourceWarehouseId.equals(
                        repair.getWarehouseId()))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Active repair does not belong to the transfer source warehouse");
    }
  }

  private static void requirePreparedTransfer(
      List<MaintenanceRepair> chain,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    if (chain.isEmpty()
        || chain.stream()
            .anyMatch(
                repair ->
                    !"DEPARTURE_PREPARED".equals(
                            repair.getTransferState())
                        || !transferId.equals(
                            repair.getTransferDocumentId())
                        || !lineId.equals(repair.getTransferLineId())
                        || !targetWarehouseId.equals(
                            repair.getTransferTargetWarehouseId())
                        || !rentalItemId.equals(
                            repair.getRentalItemId())
                        || !sourceWarehouseId.equals(
                            repair.getWarehouseId()))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair chain is not prepared for this warehouse transfer");
    }
  }

  private void lockRepairStreams(List<MaintenanceRepair> chain) {
    Map<MaintenanceEventStore.StreamRef, Long> versions =
        events.lockStreams(
            chain.stream()
                .map(
                    repair ->
                        new MaintenanceEventStore.StreamRef(
                            MaintenanceAggregateType.REPAIR,
                            repair.getId()))
                .toList());
    for (MaintenanceRepair repair : chain) {
      Long streamVersion =
          versions.get(
              new MaintenanceEventStore.StreamRef(
                  MaintenanceAggregateType.REPAIR, repair.getId()));
      assertVersion(streamVersion, repair.getVersion());
    }
  }

  private static MaintenanceRepair repairLifecycleOwner(
      List<MaintenanceRepair> chain, MaintenanceRepair active) {
    UUID rootRepairId = rootId(active);
    return chain.stream()
        .filter(repair -> repair.getId().equals(rootRepairId))
        .findFirst()
        .orElseThrow(
            () ->
                new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT",
                    "Repair lifecycle owner is missing"));
  }

  private boolean hasUnfinishedStages(UUID repairId) {
    return repairStages.findAllByRepairIdOrderByStageNo(repairId).stream()
        .anyMatch(
            stage ->
                stage.getState() != RepairStageState.DONE
                    && stage.getState() != RepairStageState.CANCELLED);
  }

  private TransferDeparturePlan transferDeparturePlan(
      List<MaintenanceRepair> candidates,
      MaintenanceRepair active,
      TransferRepairRequest request) {
    if (active == null) {
      return new TransferDeparturePlan(null, null, List.of(), null);
    }
    List<MaintenanceRepair> chain = repairs.findRepairChain(rootId(active));
    if (chain.isEmpty()) {
      throw new MaintenanceNotFoundException("Active repair chain not found");
    }
    requireTransferSource(chain, request.rentalItemId(), request.sourceWarehouseId());
    MaintenanceRepair owner = repairLifecycleOwner(chain, active);
    TransferLeaseReleasePlan leaseRelease = null;
    if (owner.getLeaseId() != null
        && ("ACTIVE".equals(owner.getLeaseReconciliationState())
            || "RECONCILIATION_REQUIRED".equals(owner.getLeaseReconciliationState()))) {
      if (owner.getLeaseVersion() == null || owner.getFencingToken() == null) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_LEASE_CONFLICT",
            "Transfer departure lease snapshot is incomplete");
      }
      leaseRelease =
          new TransferLeaseReleasePlan(
              owner.getId(),
              owner.getLeaseId(),
              owner.getLeaseVersion(),
              owner.getFencingToken(),
              ownerType(owner),
              ownerId(owner));
    }
    return new TransferDeparturePlan(
        request.sourceWarehouseId(),
        active.getId(),
        transferRepairSignatures(chain),
        leaseRelease);
  }

  private void releaseTransferLease(TransferLeaseReleasePlan plan, UUID key) {
    requireNoCallerTransaction("release a transfer departure lease");
    dependencies.releaseLease(
        derived(key, "transfer-release:" + plan.ownerRepairId()),
        plan.leaseId(),
        plan.leaseVersion(),
        plan.fencingToken(),
        plan.ownerType(),
        plan.ownerId());
  }

  private static void requireMatchingTransferDeparturePlan(
      TransferDeparturePlan expected, TransferDeparturePlan current) {
    if (!expected.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair transfer departure changed before finalization");
    }
  }

  private TransferArrivalPlan transferArrivalPlan(
      UUID transferId, UUID lineId, TransferRepairRequest request) {
    return transferArrivalPlan(
        transferId,
        lineId,
        request.rentalItemId(),
        request.sourceWarehouseId(),
        request.targetWarehouseId(),
        null);
  }

  private TransferArrivalPlan transferArrivalPlan(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return transferArrivalPlan(
        transferId,
        lineId,
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        null);
  }

  private TransferArrivalPlan transferArrivalPlan(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long expectedAssetVersion) {
    return transferArrivalPlan(
        repairs.findAllByTransferLineIdOrderByCreatedAtAscIdAsc(lineId),
        transferId,
        lineId,
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        expectedAssetVersion);
  }

  private TransferArrivalPlan transferArrivalPlan(
      List<MaintenanceRepair> chain,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return transferArrivalPlan(
        chain,
        transferId,
        lineId,
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        null);
  }

  private TransferArrivalPlan transferArrivalPlan(
      List<MaintenanceRepair> chain,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long expectedAssetVersion) {
    if (chain.isEmpty()) {
      return new TransferArrivalPlan(
          null,
          rentalItemId,
          sourceWarehouseId,
          targetWarehouseId,
          expectedAssetVersion,
          null,
          List.of(),
          List.of(),
          null,
          List.of());
    }
    requirePreparedTransfer(
        chain, transferId, lineId, rentalItemId, sourceWarehouseId, targetWarehouseId);
    MaintenanceRepair active = requireSingleActiveRepair(chain, true);
    if (active == null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Prepared transfer no longer has an active repair");
    }
    RepairComplexity targetComplexity =
        repairComplexityFromStoredStages(targetWarehouseId, active.getId()).type();
    List<TransferTaskPlan> tasks =
        chain.stream()
            .filter(repair -> repair.getTaskBoardVersion() != null)
            .filter(repair -> hasUnfinishedStages(repair.getId()))
            .sorted(Comparator.comparing(repair -> repair.getId().toString()))
            .map(
                repair ->
                    new TransferTaskPlan(
                        repair.getId(),
                        repair.getExternalTaskId(),
                        repair.getTaskBoardVersion(),
                        targetComplexity == RepairComplexity.CAPITAL))
            .toList();
    MaintenanceRepair owner = repairLifecycleOwner(chain, active);
    return new TransferArrivalPlan(
        active.getId(),
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        expectedAssetVersion,
        targetComplexity,
        transferRepairSignatures(chain),
        tasks,
        new TransferLeaseOwnerPlan(owner.getId(), ownerType(owner), ownerId(owner)),
        transferRoutingRequirements(chain, targetComplexity));
  }

  private static void requireMatchingTransferArrivalPlan(
      TransferArrivalPlan expected, TransferArrivalPlan current) {
    if (!expected.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair transfer arrival changed before finalization");
    }
  }

  private TransferRoutingResult resolveTransferArrivalRouting(TransferArrivalPlan plan) {
    requireNoCallerTransaction("resolve repair transfer routing truth");
    MaintenanceDependencyGateway.QueueCapabilities capabilities =
        dependencies.queueCapabilities(plan.targetWarehouseId());
    if (capabilities == null
        || !plan.targetWarehouseId().equals(capabilities.warehouseId())
        || capabilities.movementQueueDefinitions() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned queue capabilities for another warehouse");
    }
    if (plan.routingRequirements().isEmpty()) {
      return new TransferRoutingResult(List.of());
    }
    MaintenanceDependencyGateway.RoutingPreflight preflight =
        dependencies.preflightMaintenanceRouting(
            capabilities.warehouseId(), plan.routingRequirements());
    if (preflight == null
        || !capabilities.warehouseId().equals(preflight.warehouseId())
        || preflight.missingQueueDefinitionIds() == null
        || preflight.missingWarehouseBindingDefinitionIds() == null
        || preflight.mismatches() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned malformed repair transfer routing truth");
    }
    Set<UUID> missing = new LinkedHashSet<>();
    missing.addAll(preflight.missingQueueDefinitionIds());
    missing.addAll(preflight.missingWarehouseBindingDefinitionIds());
    preflight.mismatches().stream()
        .map(MaintenanceDependencyGateway.RoutingMismatch::queueDefinitionId)
        .forEach(missing::add);
    if (preflight.ready() != missing.isEmpty()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned inconsistent repair transfer routing truth");
    }
    return new TransferRoutingResult(
        missing.stream().sorted(Comparator.comparing(UUID::toString)).toList());
  }

  private TransferArrivalRemoteResult completeTransferArrivalRemote(
      TransferArrivalPlan plan, UUID key, TransferRoutingResult routing) {
    requireNoCallerTransaction("apply repair transfer arrival dependency effects");
    if (!routing.missingQueueDefinitionIds().isEmpty()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_TARGET_QUEUE_MISSING",
          "The target warehouse is missing queues required by the active repair");
    }
    Map<UUID, Long> relocatedTaskVersions = new LinkedHashMap<>();
    Set<UUID> cancelledTaskRepairIds = new LinkedHashSet<>();
    for (TransferTaskPlan task : plan.tasks()) {
      MaintenanceDependencyGateway.TaskSnapshot current = dependencies.getTask(task.externalTaskId());
      validateTransferTaskReplayDiagnostic(task, current);
      if (task.cancelForCapital()) {
        MaintenanceDependencyGateway.TaskSnapshot cancelled =
            dependencies.cancelTask(
                derived(key, "task-withdraw-capital:" + task.repairId()),
                task.externalTaskId(),
                task.expectedVersion());
        validateCancelledTransferTask(task, cancelled);
        cancelledTaskRepairIds.add(task.repairId());
      } else {
        MaintenanceDependencyGateway.TaskSnapshot relocated =
            dependencies.relocateTask(
                derived(key, "task-relocate:" + task.repairId()),
                task.externalTaskId(),
                task.expectedVersion(),
                plan.targetWarehouseId());
        validateRelocatedTransferTask(task, relocated);
        relocatedTaskVersions.put(task.repairId(), relocated.version());
      }
    }

    MaintenanceDependencyGateway.AssetSnapshot asset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateTransferArrivalAssetReplayDiagnostic(plan, asset);
    long expectedAssetVersion = requiredTransferArrivalAssetVersion(plan);
    MaintenanceDependencyGateway.LeaseSnapshot lease =
        dependencies.acquireLease(
            derived(key, "transfer-arrival-lease:" + plan.leaseOwner().repairId()),
            plan.rentalItemId(),
            expectedAssetVersion,
            plan.leaseOwner().ownerType(),
            plan.leaseOwner().ownerId());
    validateLeaseTruth(
        plan.rentalItemId(), lease, plan.leaseOwner().ownerType(), plan.leaseOwner().ownerId());

    MaintenanceDependencyGateway.AssetSnapshot finalAsset = asset;
    if (plan.targetComplexity() == RepairComplexity.CAPITAL) {
      finalAsset =
          dependencies.fencedStatus(
              derived(key, "transfer-arrival-capital:" + plan.activeRepairId()),
              plan.rentalItemId(),
              plan.targetWarehouseId(),
              expectedAssetVersion,
              lease.leaseId(),
              lease.fencingToken(),
              plan.leaseOwner().ownerType(),
              plan.leaseOwner().ownerId(),
              "QUEUE_TO_CAPITAL_REPAIR",
              false);
    }
    validateTransferArrivalAssetAfterEffects(plan, finalAsset);
    return new TransferArrivalRemoteResult(
        routing,
        Map.copyOf(relocatedTaskVersions),
        Set.copyOf(cancelledTaskRepairIds),
        finalAsset,
        lease);
  }

  private static void validateTransferTaskReplayDiagnostic(
      TransferTaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    boolean preEffect =
        task != null
            && plan.externalTaskId().equals(task.externalTaskId())
            && task.version() == plan.expectedVersion()
            && "ACTIVE".equals(task.state());
    boolean postEffect =
        task != null
            && plan.externalTaskId().equals(task.externalTaskId())
            && task.version() == Math.addExact(plan.expectedVersion(), 1)
            && (plan.cancelForCapital()
                ? "CANCELLED".equals(task.state())
                : "ACTIVE".equals(task.state()));
    if (!preEffect && !postEffect) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Task-board task changed before repair transfer arrival");
    }
  }

  private static void validateCancelledTransferTask(
      TransferTaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    if (task == null
        || !plan.externalTaskId().equals(task.externalTaskId())
        || task.version() != Math.addExact(plan.expectedVersion(), 1)
        || !"CANCELLED".equals(task.state())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board did not confirm ordinary repair route withdrawal");
    }
  }

  private static void validateRelocatedTransferTask(
      TransferTaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    if (task == null
        || !plan.externalTaskId().equals(task.externalTaskId())
        || task.version() != Math.addExact(plan.expectedVersion(), 1)
        || !"ACTIVE".equals(task.state())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board did not confirm repair task relocation");
    }
  }

  private static long requiredTransferArrivalAssetVersion(TransferArrivalPlan plan) {
    if (plan.expectedAssetVersion() == null || plan.expectedAssetVersion() < 0) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_TRANSFER_ASSET_VERSION_REQUIRED",
          "Transfer arrival must include the immutable asset version observed by logistics");
    }
    return plan.expectedAssetVersion();
  }

  private static void validateTransferArrivalAssetReplayDiagnostic(
      TransferArrivalPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    long expectedVersion = requiredTransferArrivalAssetVersion(plan);
    boolean preEffect =
        asset != null
            && plan.rentalItemId().equals(asset.rentalItemId())
            && plan.targetWarehouseId().equals(asset.warehouseId())
            && asset.version() == expectedVersion
            && "REPAIR".equals(asset.status());
    boolean postCapitalEffect =
        plan.targetComplexity() == RepairComplexity.CAPITAL
            && asset != null
            && plan.rentalItemId().equals(asset.rentalItemId())
            && plan.targetWarehouseId().equals(asset.warehouseId())
            && asset.version() == Math.addExact(expectedVersion, 1)
            && "CAPITAL_REPAIR".equals(asset.status());
    if (!preEffect && !postCapitalEffect) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Asset changed before repair transfer arrival replay");
    }
  }

  private static void validateTransferArrivalAssetAfterEffects(
      TransferArrivalPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot after) {
    long expectedVersion = requiredTransferArrivalAssetVersion(plan);
    String expectedStatus =
        plan.targetComplexity() == RepairComplexity.CAPITAL ? "CAPITAL_REPAIR" : "REPAIR";
    long expectedResultVersion =
        plan.targetComplexity() == RepairComplexity.CAPITAL
            ? Math.addExact(expectedVersion, 1)
            : expectedVersion;
    if (after == null
        || !plan.rentalItemId().equals(after.rentalItemId())
        || !plan.targetWarehouseId().equals(after.warehouseId())
        || after.version() != expectedResultVersion
        || !expectedStatus.equals(after.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm repair transfer arrival status");
    }
  }

  private static void validateTransferArrivalRemoteTruth(
      TransferArrivalPlan plan, TransferArrivalRemoteResult remote) {
    if (remote.routing() == null
        || !remote.routing().missingQueueDefinitionIds().isEmpty()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair transfer arrival routing result is incomplete");
    }
    Set<UUID> expectedTaskRepairIds =
        plan.tasks().stream().map(TransferTaskPlan::repairId).collect(java.util.stream.Collectors.toSet());
    if (plan.targetComplexity() == RepairComplexity.CAPITAL) {
      if (!expectedTaskRepairIds.equals(remote.cancelledTaskRepairIds())
          || !remote.relocatedTaskVersions().isEmpty()) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Repair transfer capital task reconciliation is incomplete");
      }
    } else if (!expectedTaskRepairIds.equals(remote.relocatedTaskVersions().keySet())
        || !remote.cancelledTaskRepairIds().isEmpty()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair transfer task relocation result is incomplete");
    }
    for (TransferTaskPlan task : plan.tasks()) {
      if (!task.cancelForCapital()
          && !Long.valueOf(Math.addExact(task.expectedVersion(), 1))
              .equals(remote.relocatedTaskVersions().get(task.repairId()))) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board did not return the immutable transfer relocation result");
      }
    }
    validateTransferArrivalAssetAfterEffects(plan, remote.asset());
    validateLeaseTruth(
        plan.rentalItemId(),
        remote.lease(),
        plan.leaseOwner().ownerType(),
        plan.leaseOwner().ownerId());
  }

  private List<MaintenanceDependencyGateway.RoutingQueueRequirement> transferRoutingRequirements(
      List<MaintenanceRepair> chain, RepairComplexity targetComplexity) {
    Map<UUID, MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        new LinkedHashMap<>();
    if (targetComplexity != RepairComplexity.CAPITAL) {
      for (MaintenanceRepair repair : chain) {
        for (RepairStage stage :
            repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
          if (stage.getState() == RepairStageState.DONE
              || stage.getState() == RepairStageState.CANCELLED) {
            continue;
          }
          MaintenanceDependencyGateway.RoutingQueueRequirement requirement =
              new MaintenanceDependencyGateway.RoutingQueueRequirement(
                  stage.getRoutingQueueId(),
                  stage.getRoutingQueueType().trim().toUpperCase(Locale.ROOT));
          MaintenanceDependencyGateway.RoutingQueueRequirement previous =
              requirements.putIfAbsent(
                  requirement.queueDefinitionId(), requirement);
          if (previous != null && !previous.equals(requirement)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "One repair queue definition has conflicting route types");
          }
        }
      }
    }
    return List.copyOf(requirements.values());
  }

  private static List<TransferRepairSignature> transferRepairSignatures(
      List<MaintenanceRepair> values) {
    return values.stream()
        .map(
            repair ->
                new TransferRepairSignature(
                    repair.getId(),
                    repair.getVersion(),
                    repair.getWarehouseId(),
                    repair.getRentalItemId(),
                    repair.getRentalItemVersionSnapshot(),
                    repair.getRootRepairId(),
                    repair.getSourceRepairId(),
                    repair.getExecutionState(),
                    repair.getAcceptanceState(),
                    repair.getReclassificationState(),
                    repair.getTransferState(),
                    repair.getTransferDocumentId(),
                    repair.getTransferLineId(),
                    repair.getTransferTargetWarehouseId(),
                    repair.getExternalTaskId(),
                    repair.getTaskBoardVersion(),
                    repair.getLeaseId(),
                    repair.getLeaseVersion(),
                    repair.getFencingToken(),
                    repair.getLeaseExpiresAt(),
                    repair.getLeaseReconciliationState()))
        .sorted(Comparator.comparing(value -> value.id().toString()))
        .toList();
  }

  private void appendRepairTransferEvents(
      List<MaintenanceRepair> saved,
      Map<UUID, Long> previousVersions,
      MaintenanceEventType eventType) {
    for (MaintenanceRepair repair :
        saved.stream()
            .sorted(Comparator.comparing(value -> value.getId().toString()))
            .toList()) {
      Long previousVersion = previousVersions.get(repair.getId());
      if (previousVersion == null) {
        throw new IllegalStateException(
            "Repair transfer lost its previous aggregate version");
      }
      events.append(
          MaintenanceAggregateType.REPAIR,
          repair.getId(),
          previousVersion,
          eventType,
          repairLocal(repair),
          repairFact(eventType, repair),
          repairSnapshot(repair));
    }
  }

  private List<MediaReferenceInput> repairMedia(MaintenanceRepair value) {
    MaintenanceRepair current = value;
    Set<UUID> visited = new HashSet<>();
    while (current != null && visited.add(current.getId())) {
      List<MediaReferenceInput> direct = media("REPAIR", current.getId());
      if (!direct.isEmpty()) return direct;
      if (current.getEstimateId() != null) {
        List<MediaReferenceInput> estimateMedia = media("ESTIMATE", current.getEstimateId());
        if (!estimateMedia.isEmpty()) return estimateMedia;
      }
      if (current.getOrigin() == RepairOrigin.INVENTORY) {
        List<MediaReferenceInput> inventoryMedia =
            inventorySources
                .findByRepairId(current.getId())
                .map(
                    source ->
                        read(source.getPlanSnapshot(), FrozenInventoryPlanSnapshot.class)
                            .mediaReferences())
                .orElse(List.of());
        if (!inventoryMedia.isEmpty()) return inventoryMedia;
      }
      current =
          current.getSourceRepairId() == null
              ? null
              : repairs.findById(current.getSourceRepairId()).orElse(null);
    }
    return List.of();
  }

  private static UUID effectiveCoverMediaId(
      UUID selected, List<MediaReferenceInput> mediaReferences) {
    if (selected != null
        && mediaReferences.stream().anyMatch(value -> selected.equals(value.mediaId()))) {
      return selected;
    }
    return mediaReferences.isEmpty() ? null : mediaReferences.getFirst().mediaId();
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
    result.put(
        "lines",
        currentLines(value).stream()
            .map(
                line -> {
                  Map<String, Object> lineState = new LinkedHashMap<>();
                  lineState.put("lineNo", line.getLineNo());
                  lineState.put("type", line.getLineType());
                  lineState.put("title", line.getTitle());
                  lineState.put("unit", line.getUnit());
                  lineState.put("quantity", line.getQuantity().toPlainString());
                  lineState.put("unitPriceMinor", line.getUnitPriceMinor());
                  return lineState;
                })
            .toList());
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

  private void requireAbsentInboundDriverTask(UUID repairId) {
    MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation compensation =
        dependencies.maintenanceDriverTaskCompensation(
            repairId,
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR);
    if (compensation == null
        || !repairId.equals(compensation.repairId())
        || compensation.kind()
            != MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR
        || compensation.outcome() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Logistics-service omitted valid inbound driver-task truth");
    }
    if (compensation.outcome()
        != MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.ABSENT) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Inbound driver task still exists or requires logistics reconciliation");
    }
  }

  private static void validateInboundDeliveryRetryRequest(RetryInboundDeliveryRequest request) {
    if (request == null
        || request.expectedVersion() == null
        || request.expectedVersion() < 0
        || request.logisticsPlanningMode() == null
        || (request.logisticsPlanningMode() == RepairLogisticsPlanningMode.AUTO
            && request.logisticsScheduledDate() != null)
        || (request.logisticsPlanningMode() == RepairLogisticsPlanningMode.FIXED_DATE
            && request.logisticsScheduledDate() == null)
        || request.reason() == null
        || request.reason().isBlank()
        || request.reason().length() > 2000) {
      throw invalid("Inbound delivery retry request is invalid");
    }
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

  private static <T> T requireReconciliationResult(T value) {
    if (value == null) {
      throw new IllegalStateException("Reconciliation transaction did not return a result");
    }
    return value;
  }

  /**
   * Remote warehouse admission must run after the prepare transaction has committed and before
   * final local locks are acquired. A caller-owned transaction would keep those locks alive while
   * the remote call waits, so public orchestration boundaries reject it instead of suspending it.
   */
  private static void requireNoCallerTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Maintenance cannot " + operation + " inside a caller transaction");
    }
  }

  private <T> T inLocalTransaction(String operation, Supplier<T> action) {
    T result = transactions.execute(status -> action.get());
    if (result == null) {
      throw new IllegalStateException("Maintenance " + operation + " transaction was empty");
    }
    return result;
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

  private static boolean leaseHasExpired(OffsetDateTime expiresAt) {
    return expiresAt == null
        || !expiresAt.isAfter(OffsetDateTime.now(java.time.ZoneOffset.UTC));
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
  private record LeaseRefresh(
      MaintenanceDependencyGateway.LeaseSnapshot lease, boolean ownerRenewed) {}
  private record CatalogValidation(boolean dependencyAcyclic) {}
  private record CatalogMutationTarget(CatalogVersionState state) {}
  private record WarehouseAdmissionPreflight<T>(UUID warehouseId, T replay) {}
  private record EstimateCompletionPreflight(
      CreateResult<EstimateCommandResult> replay,
      UUID warehouseId,
      List<PlanStageInput> plan,
      UUID rentalItemId,
      List<FurnitureQuantity> furniture) {}
  private record QueueRepairCommandPreflight(
      CreateResult<RepairCommandResult> replay,
      UUID warehouseId,
      LeaseRefreshPlan leasePlan) {}
  private record AcceptanceCommandPreflight(
      CreateResult<RepairCommandResult> replay,
      UUID warehouseId,
      LeaseRefreshPlan leasePlan) {}
  private record LeaseCopySignature(
      UUID repairId,
      long repairVersion,
      UUID leaseId,
      Long leaseVersion,
      Long fencingToken,
      OffsetDateTime expiresAt,
      String reconciliationState) {}
  private record LeaseRefreshPlan(
      UUID ownerRepairId,
      UUID rentalItemId,
      long rentalItemVersion,
      String ownerType,
      String ownerId,
      UUID leaseId,
      long leaseVersion,
      long fencingToken,
      OffsetDateTime expiresAt,
      List<LeaseCopySignature> copies) {}
  private record TransferDeparturePreflight(
      CreateResult<PrepareTransferRepairResponse> replay,
      TransferDeparturePlan plan) {}
  private record TransferLeaseReleasePlan(
      UUID ownerRepairId,
      UUID leaseId,
      long leaseVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {}
  private record TransferDeparturePlan(
      UUID warehouseId,
      UUID activeRepairId,
      List<TransferRepairSignature> chain,
      TransferLeaseReleasePlan leaseRelease) {}
  private record TransferArrivalCompletionPreflight(
      CreateResult<CompleteTransferRepairResponse> replay,
      TransferArrivalPlan plan) {}
  private record TransferLeaseOwnerPlan(
      UUID repairId, String ownerType, String ownerId) {}
  private record TransferTaskPlan(
      UUID repairId,
      UUID externalTaskId,
      long expectedVersion,
      boolean cancelForCapital) {}
  private record TransferRoutingResult(List<UUID> missingQueueDefinitionIds) {}
  private record TransferArrivalRemoteResult(
      TransferRoutingResult routing,
      Map<UUID, Long> relocatedTaskVersions,
      Set<UUID> cancelledTaskRepairIds,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      MaintenanceDependencyGateway.LeaseSnapshot lease) {}
  private record TransferArrivalPlan(
      UUID activeRepairId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long expectedAssetVersion,
      RepairComplexity targetComplexity,
      List<TransferRepairSignature> chain,
      List<TransferTaskPlan> tasks,
      TransferLeaseOwnerPlan leaseOwner,
      List<MaintenanceDependencyGateway.RoutingQueueRequirement> routingRequirements) {}
  private record TransferRepairSignature(
      UUID id,
      long version,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID rootRepairId,
      UUID sourceRepairId,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      RepairReclassificationState reclassificationState,
      String transferState,
      UUID transferDocumentId,
      UUID transferLineId,
      UUID transferTargetWarehouseId,
      UUID externalTaskId,
      Long taskBoardVersion,
      UUID leaseId,
      Long leaseVersion,
      Long fencingToken,
      OffsetDateTime leaseExpiresAt,
      String leaseReconciliationState) {}
  private record RoutingIdentity(UUID queueId) {}
  private record ReworkCandidateKey(UUID repairId, UUID lineId) {}
  private record CatalogContent(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {}
  private record CatalogForkSnapshot(
      UUID sourceCatalogVersionId,
      long sourceCatalogVersion,
      CatalogVersionState sourceLifecycle,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links) {}
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
  private record CharacteristicPlan(
      UUID rentalItemId,
      UUID characteristicId,
      UUID rootRepairId,
      long minimumRentalItemVersion) {}
  private record DriverTaskPlan(
      UUID repairId, MaintenanceDependencyGateway.DriverTaskCommand command) {}
  private record FurnitureCustodyPlan(
      UUID repairId,
      UUID warehouseId,
      UUID rentalItemId,
      String ownerType,
      UUID ownerId,
      FurnitureAccountingMode furnitureAccountingMode,
      Map<UUID, String> equipmentNames,
      Map<UUID, Long> equipmentQuantities) {}
  private record EmptyEstimatePlan(
      UUID estimateId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      String ownerType,
      String ownerId) {}
  private record TaskPlan(
      UUID repairId,
      UUID warehouseId,
      UUID rentalItemId,
      UUID externalTaskId,
      boolean update,
      long expectedTaskVersion,
      LocalDate scheduledDate,
      int priority,
      boolean resolveDeliveredLocalDate,
      int dailyCapacity,
      List<MaintenanceDependencyGateway.TaskStage> stages) {}
  private record QueueRepairPlan(
      UUID repairId,
      long repairVersion,
      boolean alreadyQueued,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      RepairOrigin origin,
      boolean capital,
      String desiredAssetStatus,
      String queueTransition,
      String ownerType,
      String ownerId,
      boolean linkedReturn,
      String projectedAssetStatus,
      long projectedAssetVersion,
      boolean existingLifecycleOwner,
      FurnitureAccountingMode furnitureAccountingMode,
      List<FurnitureQuantity> requestedFurniture) {
    static QueueRepairPlan alreadyQueued(MaintenanceRepair repair) {
      return new QueueRepairPlan(
          repair.getId(),
          repair.getVersion(),
          true,
          null,
          null,
          0,
          null,
          false,
          null,
          null,
          null,
          null,
          false,
          null,
          0,
          false,
          FurnitureAccountingMode.TRACKED_CABIN_CONTENTS,
          List.of());
    }
  }
  private record QueueRepairRemoteResult(
      boolean adoptExistingRepair,
      MaintenanceDependencyGateway.AssetSnapshot liveAsset,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture) {
    static QueueRepairRemoteResult adopt(
        MaintenanceDependencyGateway.AssetSnapshot liveAsset,
        List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture) {
      return new QueueRepairRemoteResult(true, liveAsset, null, null, List.copyOf(furniture));
    }

    static QueueRepairRemoteResult queued(
        MaintenanceDependencyGateway.AssetSnapshot liveAsset,
        MaintenanceDependencyGateway.LeaseSnapshot lease,
        MaintenanceDependencyGateway.AssetSnapshot asset,
        List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture) {
      return new QueueRepairRemoteResult(false, liveAsset, lease, asset, List.copyOf(furniture));
    }
  }
  private record AssetTransitionPlan(
      UUID repairId,
      long repairVersion,
      List<UUID> sourceRepairIds,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      String ownerType,
      String ownerId,
      String transition,
      MaintenanceDependencyGateway.LeaseSnapshot localLease) {}
  private record LeaseRenewalPlan(
      UUID repairId,
      long repairVersion,
      UUID rentalItemId,
      UUID leaseId,
      long leaseVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {}
  private record TaskCancellationPlan(
      UUID repairId, UUID externalTaskId, long expectedVersion) {}
  private record RepairSyncSignature(
      UUID id,
      long version,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      RepairReclassificationState reclassificationState,
      Long taskBoardVersion,
      UUID externalTaskId,
      long rentalItemVersion,
      UUID leaseId,
      Long leaseVersion,
      Long fencingToken,
      OffsetDateTime leaseExpiresAt,
      boolean movementToRepair) {}
  private record ComplexitySyncPlan(
      UUID repairId,
      UUID warehouseId,
      UUID rentalItemId,
      boolean capital,
      String desiredStatus,
      String transition,
      long latestProjectedVersion,
      String ownerType,
      String ownerId,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      UUID leaseOwnerRepairId,
      List<UUID> complexityRepairIds,
      List<RepairSyncSignature> signatures,
      List<TaskCancellationPlan> taskCancellations) {}
}
