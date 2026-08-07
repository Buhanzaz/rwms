package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ActorSnapshot;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ActorType;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.ApprovePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionLine;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionLineInput;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionPlan;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionPlanInput;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateCabinPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateEquipmentPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryLossDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreatePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateResult;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionDecisionResponse;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionPage;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionRepairChainEntry;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RecoverPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RejectPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.WriteOffRepairRequest;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentSnapshotLine;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentSnapshotLineDraft;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecisionDraft;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.disposition.repository.PropertyDispositionDecisionRepository;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceActorReferenceProvider;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.PropertyDispositionFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import dev.buhanzaz.rwms.maintenance.service.WarehouseLifecycleOperations;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maintenance-owned decision orchestration for property write-off and loss.
 *
 * <p>Asset and logistics truth is deliberately read or changed outside a database transaction.
 * The durable decision transitions, audit events, repair-chain terminalization and processor
 * requeue are each committed together inside a short local transaction.
 */
@Service
public class PropertyDispositionApplicationService {
  private static final String SYSTEM_ACTOR_ID = "00000000-0000-0000-0000-0000000000d6";

  private final PropertyDispositionDecisionRepository decisions;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceActorReferenceProvider actors;
  private final PropertyDispositionProcessingStore processing;
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public PropertyDispositionApplicationService(
      PropertyDispositionDecisionRepository decisions,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceDependencyGateway dependencies,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceActorReferenceProvider actors,
      PropertyDispositionProcessingStore processing,
      ObjectMapper mapper,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.decisions = decisions;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.dependencies = dependencies;
    this.warehouseLifecycle = warehouseLifecycle;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.actors = actors;
    this.processing = processing;
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public CreateResult createManual(
      UUID subjectId, UUID idempotencyKey, CreatePropertyDispositionRequest request) {
    require(subjectId, "Disposition subject is required");
    require(idempotencyKey, "Disposition idempotency key is required");
    require(request, "Disposition request is required");
    String requestHash = hash(request);
    CreateResult replay = transactions.execute(ignored -> {
      PropertyDispositionDecision existing = decisions
          .findByInitiatedBySubjectIdAndIdempotencyKey(subjectId, idempotencyKey)
          .orElse(null);
      if (existing == null) return null;
      if (!existing.matchesManualReplay(subjectId, idempotencyKey, requestHash)) {
        throw conflict(
            "Idempotency key is already bound to a different property disposition request");
      }
      return new CreateResult(response(existing, repairChain(existing.getRootRepairId())), true);
    });
    if (replay != null) return replay;
    warehouseLifecycle.requireOutgoing(request.warehouseId());
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            gatewayAssetKind(request.assetKind()), request.assetId(), request.warehouseId());
    return requiredResult(
        transactions.execute(
            ignored -> createManualInTransaction(subjectId, idempotencyKey, request, snapshot)));
  }

  public CreateResult createRepairWriteOff(
      UUID subjectId,
      UUID idempotencyKey,
      UUID repairId,
      UUID warehouseId,
      WriteOffRepairRequest request) {
    require(subjectId, "Disposition subject is required");
    require(idempotencyKey, "Disposition idempotency key is required");
    require(repairId, "Repair ID is required");
    require(warehouseId, "Warehouse ID is required");
    require(request, "Repair write-off request is required");
    MaintenanceRepair initial = repairs.findByIdAndWarehouseId(repairId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    UUID rootRepairId = rootId(initial);
    CreateResult replay = transactions.execute(ignored -> decisions
        .findByAssetKindAndRootRepairId(PropertyDispositionAssetKind.CABIN, rootRepairId)
        .map(existing -> new CreateResult(response(existing, repairChain(rootRepairId)), true))
        .orElse(null));
    if (replay != null) return replay;
    if (request.expectedVersion() == null || request.expectedVersion() != initial.getVersion()) {
      throw versionConflict("Repair version is stale");
    }
    MaintenanceRepair root = repairs.findById(rootRepairId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Root repair not found"));
    warehouseLifecycle.requireOutgoing(warehouseId);
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            root.getRentalItemId(),
            warehouseId);
    return requiredResult(
        transactions.execute(
            ignored ->
                createRepairWriteOffInTransaction(
                    subjectId,
                    idempotencyKey,
                    repairId,
                    warehouseId,
                    request,
                    snapshot)));
  }

  public CreateResult createInventoryLoss(
      UUID idempotencyKey, CreateInventoryLossDispositionRequest request) {
    require(idempotencyKey, "Inventory disposition idempotency key is required");
    require(request, "Inventory disposition request is required");
    String requestHash = hash(request);
    CreateResult replay = transactions.execute(ignored -> {
      PropertyDispositionDecision existing = decisions
          .findBySourceAndInventoryIdAndFindingId(
              PropertyDispositionSource.INVENTORY,
              request.inventorySessionId(),
              request.findingId())
          .orElse(null);
      if (existing == null) return null;
      if (!existing.getRequestSha256().equals(requestHash)) {
        throw conflict("Inventory finding is already bound to a different loss disposition");
      }
      return new CreateResult(response(existing, List.of()), true);
    });
    if (replay != null) return replay;
    warehouseLifecycle.requireOutgoing(request.warehouseId());
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT,
            request.equipmentId(),
            request.warehouseId());
    return requiredResult(
        transactions.execute(
            ignored -> createInventoryLossInTransaction(idempotencyKey, request, snapshot)));
  }

  /**
   * Creates one pending administrator decision per unresolved asset custody claim. Furniture is
   * not terminalized here: the existing approval -> PREPARE -> APPLY processor remains the only
   * path that can consume the claim.
   */
  public int materializeFurnitureCustody(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    validateFurnitureCustodyInput(completedRepair, equipmentNames, claims);
    // Warehouse admission is a remote read. It must complete before the short local transaction
    // that creates durable disposition decisions and can acquire aggregate/row locks.
    warehouseLifecycle.requireOutgoing(completedRepair.getWarehouseId());
    return requiredResult(
        transactions.execute(
            ignored -> materializeFurnitureCustodyLocally(completedRepair, equipmentNames, claims)));
  }

  /**
   * Local-only half of {@link #materializeFurnitureCustody}. Reconciliation callers that have
   * already performed admission outside their remote phase may join their final CAS transaction
   * without reopening a warehouse-service call under local locks.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int materializeFurnitureCustodyLocally(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    validateFurnitureCustodyInput(completedRepair, equipmentNames, claims);
    return materializeFurnitureCustodyInTransaction(completedRepair, equipmentNames, claims);
  }

  /**
   * Records furniture selected from a legacy cabin with no recorded contents as separately
   * approved loss decisions. No claim, balance fence, or asset-service effect is created.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int materializeUnaccountedFurnitureLocally(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      Map<UUID, Long> equipmentQuantities) {
    require(completedRepair, "Completed repair is required for unaccounted furniture");
    require(equipmentNames, "Furniture equipment names are required");
    require(equipmentQuantities, "Furniture equipment quantities are required");
    if (completedRepair.getFurnitureAccountingMode()
        != FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS) {
      throw conflict("Repair does not use unaccounted furniture accounting");
    }
    if (completedRepair.getAcceptanceState() != RepairAcceptanceState.PENDING) {
      throw conflict("Unaccounted furniture can be proposed only after repair completion");
    }
    UUID rootRepairId = rootId(completedRepair);
    int createdCount = 0;
    for (Map.Entry<UUID, Long> line : equipmentQuantities.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
        .toList()) {
      UUID equipmentId = line.getKey();
      Long quantity = line.getValue();
      if (equipmentId == null || quantity == null || quantity < 1) {
        throw conflict("Unaccounted furniture quantity is invalid");
      }
      String equipmentName = Optional.ofNullable(equipmentNames.get(equipmentId))
          .map(String::trim)
          .filter(value -> !value.isEmpty() && value.length() <= 255)
          .orElseThrow(() -> conflict("Furniture equipment name is missing from repair truth"));
      PropertyDispositionDecision existing = decisions
          .findBySourceAndSourceRepairIdAndAssetId(
              PropertyDispositionSource.UNACCOUNTED, completedRepair.getId(), equipmentId)
          .orElse(null);
      if (existing != null) {
        if (!existing.getWarehouseId().equals(completedRepair.getWarehouseId())
            || existing.getAssetKind() != PropertyDispositionAssetKind.EQUIPMENT
            || !existing.getAssetId().equals(equipmentId)
            || !java.util.Objects.equals(existing.getRootRepairId(), rootRepairId)
            || !java.util.Objects.equals(existing.getQuantity(), quantity)
            || existing.getMaintenanceCustodyClaimId() != null
            || existing.getExpectedAssetVersion() != null
            || existing.getExpectedSourceBalanceVersion() != null) {
          throw conflict("Unaccounted furniture is already bound to another disposition");
        }
        continue;
      }
      String reason =
          "Мебель отсутствует в записанном наполнении бытовки; требуется отдельное решение по утрате";
      String requestHash =
          hash(
              new UnaccountedFurnitureDecisionFingerprint(
                  rootRepairId,
                  completedRepair.getId(),
                  equipmentId,
                  quantity,
                  reason));
      PropertyDispositionDecision decision =
          PropertyDispositionDecision.initiate(
              new PropertyDispositionDecisionDraft(
                  completedRepair.getWarehouseId(),
                  PropertyDispositionAssetKind.EQUIPMENT,
                  equipmentId,
                  equipmentName,
                  PropertyDispositionKind.WRITE_OFF,
                  PropertyDispositionSource.UNACCOUNTED,
                  null,
                  null,
                  quantity,
                  null,
                  null,
                  reason,
                  null,
                  completedRepair.getId(),
                  rootRepairId,
                  null,
                  null,
                  null,
                  null,
                  requestHash,
                  systemActorJson(),
                  null,
                  List.of()));
      created(decision);
      createdCount++;
    }
    return createdCount;
  }

  private int materializeFurnitureCustodyInTransaction(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    UUID rootRepairId = rootId(completedRepair);
    MaintenanceRepair rootRepair = completedRepair.getId().equals(rootRepairId)
        ? completedRepair
        : repairs.findById(rootRepairId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Root repair not found"));
    PropertyDispositionSource source = rootRepair.getEstimateId() == null
        ? PropertyDispositionSource.REPAIR
        : PropertyDispositionSource.ESTIMATE;
    int createdCount = 0;
    for (MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim claim : claims) {
      PropertyDispositionDecision existing =
          decisions.findByMaintenanceCustodyClaimId(claim.id()).orElse(null);
      if (existing != null) {
        if (!existing.getWarehouseId().equals(claim.warehouseId())
            || existing.getAssetKind() != PropertyDispositionAssetKind.EQUIPMENT
            || !existing.getAssetId().equals(claim.equipmentId())
            || !java.util.Objects.equals(existing.getRootRepairId(), rootRepairId)
            || existing.getQuantity() == null
            || existing.getQuantity() < 1
            || existing.getQuantity() > claim.unresolvedQuantity()) {
          throw conflict("Furniture custody claim is already bound to another disposition");
        }
        continue;
      }
      if (!completedRepair.getWarehouseId().equals(claim.warehouseId())
          || !completedRepair.getRentalItemId().equals(claim.rentalItemId())
          || claim.availableForDispositionQuantity() < 1) {
        throw conflict("Furniture custody claim is not available for this repair decision");
      }
      String equipmentName = Optional.ofNullable(equipmentNames.get(claim.equipmentId()))
          .map(String::trim)
          .filter(value -> !value.isEmpty() && value.length() <= 255)
          .orElseThrow(() -> conflict("Furniture custody equipment name is missing from repair truth"));
      String reason = source == PropertyDispositionSource.ESTIMATE
          ? "Мебель не возвращена на склад после завершения сметы"
          : "Мебель не возвращена на склад после завершения ремонта";
      String requestHash = hash(
          new FurnitureCustodyDecisionFingerprint(
              claim.id(),
              claim.custodyVersion(),
              rootRepairId,
              completedRepair.getId(),
              claim.equipmentId(),
              claim.availableForDispositionQuantity(),
              reason));
      PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
          new PropertyDispositionDecisionDraft(
              claim.warehouseId(),
              PropertyDispositionAssetKind.EQUIPMENT,
              claim.equipmentId(),
              equipmentName,
              PropertyDispositionKind.WRITE_OFF,
              source,
              null,
              null,
              claim.availableForDispositionQuantity(),
              claim.id(),
              claim.custodyVersion(),
              reason,
              null,
              completedRepair.getId(),
              rootRepairId,
              null,
              null,
              null,
              null,
              requestHash,
              systemActorJson(),
              null,
              List.of()));
      created(decision);
      createdCount++;
    }
    return createdCount;
  }

  private static void validateFurnitureCustodyInput(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    require(completedRepair, "Completed repair is required for furniture custody");
    require(equipmentNames, "Furniture equipment names are required");
    require(claims, "Furniture custody claims are required");
    if (completedRepair.getAcceptanceState() != RepairAcceptanceState.PENDING) {
      throw conflict("Furniture custody can be proposed only after repair completion");
    }
  }

  @Transactional(readOnly = true)
  public PropertyDispositionDecisionResponse get(UUID decisionId, UUID warehouseId) {
    PropertyDispositionDecision decision = decisions.findByIdAndWarehouseId(decisionId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    return response(decision, repairChain(decision.getRootRepairId()));
  }

  @Transactional(readOnly = true)
  public PropertyDispositionPage list(
      UUID warehouseId,
      PropertyDispositionKind kind,
      PropertyDispositionState state,
      int page,
      int size) {
    if (warehouseId == null || kind == null || page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Property disposition page request is invalid");
    }
    Page<PropertyDispositionDecision> result = state == null
        ? decisions.findAllByWarehouseIdAndKind(
            warehouseId, kind, PageRequest.of(page, size))
        : decisions.findAllByWarehouseIdAndKindAndState(
            warehouseId, kind, state, PageRequest.of(page, size));
    Map<UUID, List<MaintenanceRepair>> chains = repairChains(result.getContent());
    List<PropertyDispositionDecisionResponse> values = result.getContent().stream()
        .map(value -> response(value, chains.getOrDefault(value.getRootRepairId(), List.of())))
        .toList();
    return new PropertyDispositionPage(values, page, size, result.getTotalElements());
  }

  public PropertyDispositionDecisionResponse approve(
      UUID decisionId, UUID warehouseId, ApprovePropertyDispositionRequest request) {
    require(request, "Property disposition approval request is required");
    ReviewPreflight preflight = reviewPreflight(decisionId, warehouseId);
    // Admission is a remote warehouse-service read. It must finish before the final local
    // transaction acquires the disposition stream and decision row locks.
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    return requiredResult(
        transactions.execute(
            ignored -> approveInTransaction(decisionId, warehouseId, request, preflight)));
  }

  private PropertyDispositionDecisionResponse approveInTransaction(
      UUID decisionId,
      UUID warehouseId,
      ApprovePropertyDispositionRequest request,
      ReviewPreflight preflight) {
    PropertyDispositionDecision decision = locked(decisionId, warehouseId);
    requireSameReviewPreflight(decision, preflight);
    if (!decision.approve(request.expectedVersion(), actorJson(), request.comment())) {
      return response(decision, repairChain(decision.getRootRepairId()));
    }
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, request.expectedVersion(), MaintenanceEventType.PROPERTY_DISPOSITION_APPROVED);
    processing.requeue(saved.getId());
    return response(saved, repairChain(saved.getRootRepairId()));
  }

  public PropertyDispositionDecisionResponse reject(
      UUID decisionId, UUID warehouseId, RejectPropertyDispositionRequest request) {
    require(request, "Property disposition rejection request is required");
    ReviewPreflight preflight = reviewPreflight(decisionId, warehouseId);
    // Keep the warehouse admission out of the transaction which locks the decision aggregate.
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    return requiredResult(
        transactions.execute(
            ignored -> rejectInTransaction(decisionId, warehouseId, request, preflight)));
  }

  private PropertyDispositionDecisionResponse rejectInTransaction(
      UUID decisionId,
      UUID warehouseId,
      RejectPropertyDispositionRequest request,
      ReviewPreflight preflight) {
    PropertyDispositionDecision decision = locked(decisionId, warehouseId);
    requireSameReviewPreflight(decision, preflight);
    if (!decision.reject(request.expectedVersion(), actorJson(), request.reason())) {
      return response(decision, repairChain(decision.getRootRepairId()));
    }
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, request.expectedVersion(), MaintenanceEventType.PROPERTY_DISPOSITION_REJECTED);
    return response(saved, repairChain(saved.getRootRepairId()));
  }

  private ReviewPreflight reviewPreflight(UUID decisionId, UUID warehouseId) {
    require(decisionId, "Property disposition ID is required");
    require(warehouseId, "Warehouse ID is required");
    PropertyDispositionDecision observed = decisions.findByIdAndWarehouseId(decisionId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    return new ReviewPreflight(
        observed.getId(), observed.getWarehouseId(), observed.getVersion(), observed.getState());
  }

  private static void requireSameReviewPreflight(
      PropertyDispositionDecision decision, ReviewPreflight preflight) {
    if (!decision.getId().equals(preflight.decisionId())
        || !decision.getWarehouseId().equals(preflight.warehouseId())
        || decision.getVersion() != preflight.version()
        || decision.getState() != preflight.state()) {
      throw versionConflict("Property disposition changed during warehouse admission");
    }
  }

  public PropertyDispositionDecisionResponse recover(
      UUID decisionId, UUID warehouseId, RecoverPropertyDispositionRequest request) {
    require(request, "Property disposition recovery request is required");
    PropertyDispositionDecision observed = decisions.findByIdAndWarehouseId(decisionId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    boolean processingQuarantined = processing.isQuarantined(decisionId);
    if (request.expectedVersion() == null || request.expectedVersion() != observed.getVersion()
        || (observed.getState() != PropertyDispositionState.QUARANTINED
            && !(processingQuarantined && isProcessingState(observed.getState())))) {
      throw conflict("Property disposition recovery is stale or not quarantined");
    }
    warehouseLifecycle.requireOutgoing(observed.getWarehouseId());
    // Human recovery is deliberately not a blind retry: re-read the owning asset and, where
    // applicable, the logistics task before the local recovery transition is committed.
    dependencies.getPropertyAssetSnapshot(
        gatewayAssetKind(observed.getAssetKind()), observed.getAssetId(), observed.getWarehouseId());
    if (observed.getState() == PropertyDispositionState.MOVEMENT_PENDING
        || observed.getQuarantineResumeState() == PropertyDispositionState.MOVEMENT_PENDING) {
      if (observed.getMovementTaskId() == null) {
        throw conflict("Quarantined movement decision has no logistics task identity");
      }
      dependencies.getPropertyEquipmentMovementTask(observed.getMovementTaskId());
    }
    return requiredResult(transactions.execute(
        ignored -> recoverInTransaction(decisionId, warehouseId, request)));
  }

  private PropertyDispositionDecisionResponse recoverInTransaction(
      UUID decisionId, UUID warehouseId, RecoverPropertyDispositionRequest request) {
    PropertyDispositionDecision decision = locked(decisionId, warehouseId);
    long expectedVersion = request.expectedVersion();
    if (decision.getState() != PropertyDispositionState.QUARANTINED) {
      if (!processing.isQuarantined(decisionId)) {
        throw conflict("Property disposition processing is not quarantined");
      }
      decision.recoverProcessingReconciliation(
          expectedVersion,
          request.expectedRecoveryVersion(),
          actorJson(),
          request.reason());
    } else {
      decision.recover(
          expectedVersion,
          request.expectedRecoveryVersion(),
          actorJson(),
          request.reason());
    }
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_RECOVERED);
    processing.requeue(saved.getId());
    return response(saved, repairChain(saved.getRootRepairId()));
  }

  private static boolean isProcessingState(PropertyDispositionState state) {
    return state == PropertyDispositionState.APPROVED
        || state == PropertyDispositionState.MOVEMENT_PENDING
        || state == PropertyDispositionState.EFFECT_PENDING
        || state == PropertyDispositionState.EFFECTIVE;
  }

  /** Read model used only by the server-owned processor, never exposed through a browser route. */
  @Transactional(readOnly = true)
  public ProcessingView processingView(UUID decisionId) {
    PropertyDispositionDecision decision = decisions.findById(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    List<MaintenanceRepair> chain = repairChain(decision.getRootRepairId());
    boolean requiresAssetEffect = decision.requiresAssetEffect();
    MaintenanceDependencyGateway.PropertyDispositionLeaseProof leaseProof =
        requiresAssetEffect ? leaseProof(decision, chain) : null;
    List<MaintenanceDependencyGateway.PropertyDispositionContent> contents = decision.getContents().stream()
        .map(
            line ->
                new MaintenanceDependencyGateway.PropertyDispositionContent(
                    line.getEquipmentId(),
                    line.getExpectedBalanceVersion(),
                    line.getCurrentQuantity(),
                    line.getMoveQuantity()))
        .toList();
    MaintenanceDependencyGateway.PropertyDispositionPreparation preparation =
        requiresAssetEffect
            ? new MaintenanceDependencyGateway.PropertyDispositionPreparation(
                decision.getWarehouseId(),
                gatewayAssetKind(decision.getAssetKind()),
                decision.getAssetId(),
                gatewayDispositionKind(decision.getKind()),
                decision.getAssetKind() == PropertyDispositionAssetKind.CABIN
                    ? decision.getExpectedAssetVersion()
                    : null,
                decision.getExpectedSourceBalanceVersion(),
                decision.getQuantity(),
                decision.getMaintenanceCustodyClaimId(),
                decision.getMaintenanceCustodyVersion(),
                decision.getContentsMode() == null
                    ? null
                    : MaintenanceDependencyGateway.PropertyDispositionContentsMode.valueOf(
                        decision.getContentsMode().name()),
                contents,
                leaseProof)
            : null;
    MaintenanceDependencyGateway.PropertyEquipmentMovementCommand movement =
        decision.requiresMovement()
            ? new MaintenanceDependencyGateway.PropertyEquipmentMovementCommand(
                decision.getId(),
                decision.getWarehouseId(),
                unitNumber(decision.getAssetDisplayName()),
                Math.max(15, Math.multiplyExact(15, (int) contents.stream()
                    .filter(line -> line.moveQuantity() > 0)
                    .count())),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                contents.stream()
                    .filter(line -> line.moveQuantity() > 0)
                    .map(
                        line ->
                            new MaintenanceDependencyGateway.PropertyEquipmentMovementLine(
                                line.equipmentId(),
                                decision.getAssetId(),
                                line.expectedBalanceVersion(),
                                line.moveQuantity()))
                    .toList())
            : null;
    return new ProcessingView(
        decision.getId(),
        decision.getWarehouseId(),
        decision.getState(),
        decision.getMovementTaskId(),
        decision.requiresMovement(),
        requiresAssetEffect,
        preparation,
        movement,
        leaseReleaseCommand(decision, chain));
  }

  @Transactional
  public void startMovement(UUID decisionId, UUID taskId) {
    PropertyDispositionDecision decision = locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.startMovement(expectedVersion, taskId)) return;
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_MOVEMENT_PENDING);
  }

  @Transactional
  public void completeMovement(UUID decisionId) {
    PropertyDispositionDecision decision = locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.completeMovement(expectedVersion)) return;
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECT_PENDING);
  }

  @Transactional
  public void startAssetEffect(UUID decisionId) {
    PropertyDispositionDecision decision = locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.startAssetEffect(expectedVersion)) return;
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECT_PENDING);
  }

  /**
   * Commits the confirmed asset effect and every repair-chain terminal state together. A remote
   * effect is never claimed effective until this transaction succeeds.
   */
  @Transactional
  public void markEffective(UUID decisionId, UUID effectId) {
    PropertyDispositionDecision initial = decisions.findById(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    List<MaintenanceRepair> initialChain =
        initial.getAssetKind() == PropertyDispositionAssetKind.CABIN
            ? repairChain(initial.getRootRepairId())
            : List.of();
    List<MaintenanceEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(dispositionStream(decisionId));
    initialChain.forEach(repair -> streams.add(repairStream(repair.getId())));
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(streams);
    PropertyDispositionDecision decision = decisions.findByIdForUpdate(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    assertStreamParity(decision, versions.get(dispositionStream(decisionId)));
    List<MaintenanceRepair> chain = lockRepairChain(initialChain, versions);
    long expectedVersion = decision.getVersion();
    if (!decision.markEffective(expectedVersion, effectId)) return;
    for (MaintenanceRepair repair : chain) {
      if (repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) continue;
      if (repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED) {
        throw conflict("Accepted repair cannot be terminalized by a property disposition");
      }
      long expectedRepairVersion = repair.getVersion();
      repair.writeOff(decision.getReason(), actorJson());
      repair.markLeaseReconciliationRequired();
      MaintenanceRepair savedRepair = repairs.saveAndFlush(repair);
      events.append(
          MaintenanceAggregateType.REPAIR,
          savedRepair.getId(),
          expectedRepairVersion,
          MaintenanceEventType.REPAIR_WRITTEN_OFF,
          projectionSnapshots.repair(savedRepair),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_WRITTEN_OFF,
              savedRepair,
              repairStages.findAllByRepairIdOrderByStageNo(savedRepair.getId())),
          projectionSnapshots.repair(savedRepair));
    }
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECTIVE);
  }

  /** Commits an approved unaccounted-loss decision without requesting an asset-service effect. */
  @Transactional
  public void markEffectiveWithoutAssetEffect(UUID decisionId) {
    long streamVersion = events.lockCurrentVersion(
        MaintenanceAggregateType.PROPERTY_DISPOSITION, decisionId);
    PropertyDispositionDecision decision = decisions.findByIdForUpdate(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    assertStreamParity(decision, streamVersion);
    long expectedVersion = decision.getVersion();
    if (!decision.markEffectiveWithoutAssetEffect(expectedVersion)) return;
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECTIVE);
  }

  @Transactional
  public void quarantine(UUID decisionId, String failureCode, String failureDetail) {
    PropertyDispositionDecision decision = locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.quarantine(expectedVersion, failureCode, failureDetail)) return;
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_QUARANTINED);
  }

  /** Finishes a confirmed root-lease release without invoking a remote service in this transaction. */
  @Transactional
  public void confirmLeaseReleased(UUID decisionId, UUID leaseId) {
    PropertyDispositionDecision initial = decisions.findById(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    if (initial.getState() != PropertyDispositionState.EFFECTIVE || initial.getRootRepairId() == null) {
      return;
    }
    List<MaintenanceRepair> initialChain = repairChain(initial.getRootRepairId());
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(
        initialChain.stream().map(repair -> repairStream(repair.getId())).toList());
    List<MaintenanceRepair> chain = lockRepairChain(initialChain, versions);
    for (MaintenanceRepair repair : chain) {
      if (!leaseId.equals(repair.getLeaseId())
          || "RELEASED".equals(repair.getLeaseReconciliationState())) {
        continue;
      }
      long expectedVersion = repair.getVersion();
      repair.releaseLease();
      MaintenanceRepair saved = repairs.saveAndFlush(repair);
      events.append(
          MaintenanceAggregateType.REPAIR,
          saved.getId(),
          expectedVersion,
          MaintenanceEventType.REPAIR_WRITTEN_OFF,
          projectionSnapshots.repair(saved),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_WRITTEN_OFF,
              saved,
              repairStages.findAllByRepairIdOrderByStageNo(saved.getId())),
          projectionSnapshots.repair(saved));
    }
  }

  private CreateResult createManualInTransaction(
      UUID subjectId,
      UUID idempotencyKey,
      CreatePropertyDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    advisoryLock("property-disposition:manual:" + subjectId + ':' + idempotencyKey);
    String requestHash = hash(request);
    PropertyDispositionDecision replay = decisions
        .findByInitiatedBySubjectIdAndIdempotencyKey(subjectId, idempotencyKey)
        .orElse(null);
    if (replay != null) {
      if (!replay.matchesManualReplay(subjectId, idempotencyKey, requestHash)) {
        throw conflict("Idempotency key is already bound to a different property disposition request");
      }
      return new CreateResult(response(replay, repairChain(replay.getRootRepairId())), true);
    }
    validateManualSnapshot(request, snapshot);
    PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
        manualDraft(subjectId, idempotencyKey, requestHash, request, snapshot));
    return created(decision);
  }

  private CreateResult createRepairWriteOffInTransaction(
      UUID subjectId,
      UUID idempotencyKey,
      UUID repairId,
      UUID warehouseId,
      WriteOffRepairRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    MaintenanceRepair requested = repairs.findByIdAndWarehouseId(repairId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    UUID rootRepairId = rootId(requested);
    advisoryLock("property-disposition:repair-root:" + rootRepairId);
    PropertyDispositionDecision existing = decisions
        .findByAssetKindAndRootRepairId(PropertyDispositionAssetKind.CABIN, rootRepairId)
        .orElse(null);
    if (existing != null) {
      return new CreateResult(response(existing, repairChain(rootRepairId)), true);
    }
    if (request.expectedVersion() == null || requested.getVersion() != request.expectedVersion()) {
      throw versionConflict("Repair version is stale");
    }
    List<MaintenanceRepair> chain = repairChain(rootRepairId);
    MaintenanceRepair root = chain.stream()
        .filter(value -> value.getId().equals(rootRepairId))
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Root repair not found"));
    validateRepairCanPropose(requested, root, chain);
    validateCabinSnapshot(
        snapshot, root.getRentalItemId(), warehouseId, request.contentsPlan(), true);
    String requestHash = hash(new RepairRequestFingerprint(
        repairId, warehouseId, request.expectedVersion(), request.reason(), request.comment(), request.contentsPlan()));
    PropertyDispositionSource source = root.getEstimateId() == null
        ? PropertyDispositionSource.REPAIR : PropertyDispositionSource.ESTIMATE;
    PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
        new PropertyDispositionDecisionDraft(
            warehouseId,
            PropertyDispositionAssetKind.CABIN,
            root.getRentalItemId(),
            snapshot.assetDisplayName(),
            PropertyDispositionKind.WRITE_OFF,
            source,
            snapshot.version(),
            null,
            null,
            null,
            null,
            combinedReason(request.reason(), request.comment()),
            null,
            repairId,
            rootRepairId,
            null,
            null,
            subjectId,
            idempotencyKey,
            requestHash,
            actorJson(),
            contentsMode(request.contentsPlan()),
            cabinDraftLines(snapshot, request.contentsPlan())));
    return created(decision);
  }

  private CreateResult createInventoryLossInTransaction(
      UUID ignoredIdempotencyKey,
      CreateInventoryLossDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    advisoryLock(
        "property-disposition:inventory:"
            + request.inventorySessionId()
            + ':'
            + request.findingId());
    String requestHash = hash(request);
    PropertyDispositionDecision existing = decisions
        .findBySourceAndInventoryIdAndFindingId(
            PropertyDispositionSource.INVENTORY,
            request.inventorySessionId(),
            request.findingId())
        .orElse(null);
    if (existing != null) {
      if (!existing.getRequestSha256().equals(requestHash)) {
        throw conflict("Inventory finding is already bound to a different loss disposition");
      }
      return new CreateResult(response(existing, List.of()), true);
    }
    validateEquipmentSnapshot(
        snapshot,
        request.equipmentId(),
        request.warehouseId(),
        request.expectedAssetVersion(),
        request.expectedSourceBalanceVersion(),
        request.quantity());
    PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
        new PropertyDispositionDecisionDraft(
            request.warehouseId(),
            PropertyDispositionAssetKind.EQUIPMENT,
            request.equipmentId(),
            request.equipmentName().trim(),
            PropertyDispositionKind.LOSS,
            PropertyDispositionSource.INVENTORY,
            request.expectedAssetVersion(),
            request.expectedSourceBalanceVersion(),
            request.quantity(),
            null,
            null,
            request.reason(),
            request.evidenceLink(),
            null,
            null,
            request.inventorySessionId(),
            request.findingId(),
            null,
            null,
            requestHash,
            systemActorJson(),
            null,
            List.of()));
    return created(decision);
  }

  private CreateResult created(PropertyDispositionDecision decision) {
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    events.initialize(
        MaintenanceAggregateType.PROPERTY_DISPOSITION,
        saved.getId(),
        saved.getVersion(),
        MaintenanceEventType.PROPERTY_DISPOSITION_REQUESTED,
        projectionSnapshots.propertyDisposition(saved),
        integrationFact(saved),
        projectionSnapshots.propertyDisposition(saved));
    warehouseLifecycle.recordOperation(
        saved.getWarehouseId(),
        saved.getId(),
        saved.getCreatedAt().atOffset(ZoneOffset.UTC));
    return new CreateResult(response(saved, repairChain(saved.getRootRepairId())), false);
  }

  private PropertyDispositionDecision locked(UUID decisionId, UUID expectedWarehouseId) {
    long streamVersion = events.lockCurrentVersion(
        MaintenanceAggregateType.PROPERTY_DISPOSITION, decisionId);
    PropertyDispositionDecision decision = decisions.findByIdForUpdate(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    if (expectedWarehouseId != null && !expectedWarehouseId.equals(decision.getWarehouseId())) {
      throw new MaintenanceNotFoundException("Property disposition not found");
    }
    assertStreamParity(decision, streamVersion);
    return decision;
  }

  private void append(
      PropertyDispositionDecision decision, long expectedVersion, MaintenanceEventType eventType) {
    events.append(
        MaintenanceAggregateType.PROPERTY_DISPOSITION,
        decision.getId(),
        expectedVersion,
        eventType,
        projectionSnapshots.propertyDisposition(decision),
        integrationFact(decision),
        projectionSnapshots.propertyDisposition(decision));
  }

  private Map<String, Object> integrationFact(PropertyDispositionDecision decision) {
    PropertyDispositionFact fact = new PropertyDispositionFact(
        decision.getId(),
        decision.getWarehouseId(),
        decision.getAssetKind(),
        decision.getAssetId(),
        decision.getKind(),
        decision.getSource(),
        decision.getState(),
        decision.getAssetEffectState(),
        decision.getRootRepairId(),
        decision.getSourceRepairId(),
        decision.getInventoryId(),
        decision.getFindingId(),
        decision.getMovementTaskId(),
        decision.getEffectId(),
        decision.getRecoveryVersion());
    return mapper.convertValue(fact, new TypeReference<Map<String, Object>>() {});
  }

  private PropertyDispositionDecisionDraft manualDraft(
      UUID subjectId,
      UUID idempotencyKey,
      String requestHash,
      CreatePropertyDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (request instanceof CreateCabinPropertyDispositionRequest cabin) {
      return new PropertyDispositionDecisionDraft(
          cabin.warehouseId(),
          PropertyDispositionAssetKind.CABIN,
          cabin.assetId(),
          snapshot.assetDisplayName(),
          cabin.disposition(),
          PropertyDispositionSource.MANUAL,
          cabin.expectedAssetVersion(),
          null,
          null,
          null,
          null,
          cabin.reason(),
          cabin.evidenceLink(),
          null,
          null,
          null,
          null,
          subjectId,
          idempotencyKey,
          requestHash,
          actorJson(),
          contentsMode(cabin.contentsPlan()),
          cabinDraftLines(snapshot, cabin.contentsPlan()));
    }
    CreateEquipmentPropertyDispositionRequest equipment =
        (CreateEquipmentPropertyDispositionRequest) request;
    return new PropertyDispositionDecisionDraft(
        equipment.warehouseId(),
        PropertyDispositionAssetKind.EQUIPMENT,
        equipment.assetId(),
        snapshot.assetDisplayName(),
        equipment.disposition(),
        PropertyDispositionSource.MANUAL,
        equipment.expectedAssetVersion(),
        equipment.expectedSourceBalanceVersion(),
        equipment.quantity(),
        null,
        null,
        equipment.reason(),
        equipment.evidenceLink(),
        null,
        null,
        null,
        null,
        subjectId,
        idempotencyKey,
        requestHash,
        actorJson(),
        null,
        List.of());
  }

  private void validateManualSnapshot(
      CreatePropertyDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (request instanceof CreateCabinPropertyDispositionRequest cabin) {
      validateCabinSnapshot(
          snapshot, cabin.assetId(), cabin.warehouseId(), cabin.contentsPlan(), false);
      if (snapshot.version() != cabin.expectedAssetVersion()) {
        throw versionConflict("Cabin asset version is stale");
      }
      return;
    }
    if (!(request instanceof CreateEquipmentPropertyDispositionRequest equipment)) {
      throw new IllegalArgumentException("Unsupported property disposition request");
    }
    validateEquipmentSnapshot(
        snapshot,
        equipment.assetId(),
        equipment.warehouseId(),
        equipment.expectedAssetVersion(),
        equipment.expectedSourceBalanceVersion(),
        equipment.quantity());
  }

  private void validateCabinSnapshot(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot,
      UUID assetId,
      UUID warehouseId,
      CabinContentsDispositionPlanInput plan,
      boolean authorizedRepairLease) {
    if (snapshot.assetKind() != MaintenanceDependencyGateway.PropertyAssetKind.CABIN
        || !assetId.equals(snapshot.assetId())
        || !warehouseId.equals(snapshot.warehouseId())) {
      throw conflict("Cabin snapshot does not match the disposition identity");
    }
    requireDispositionAllowed(snapshot, authorizedRepairLease);
    if (snapshot.contents().isEmpty()) {
      if (plan != null) {
        throw conflict("An empty cabin must not receive a contents disposition plan");
      }
      return;
    }
    if (plan == null) {
      throw conflict("A non-empty cabin requires an explicit contents disposition plan");
    }
    Map<UUID, MaintenanceDependencyGateway.PropertyAssetContentSnapshot> observed = snapshot.contents()
        .stream()
        .collect(Collectors.toMap(
            MaintenanceDependencyGateway.PropertyAssetContentSnapshot::equipmentId,
            value -> value));
    Map<UUID, CabinContentsDispositionLineInput> submitted = plan.lines().stream()
        .collect(Collectors.toMap(
            CabinContentsDispositionLineInput::equipmentId,
            value -> value,
            (left, right) -> {
              throw conflict("Cabin contents plan has duplicate equipment rows");
            }));
    if (!observed.keySet().equals(submitted.keySet())) {
      throw conflict("Cabin contents plan must cover exactly the current non-zero cabin contents");
    }
    for (Map.Entry<UUID, MaintenanceDependencyGateway.PropertyAssetContentSnapshot> entry
        : observed.entrySet()) {
      CabinContentsDispositionLineInput line = submitted.get(entry.getKey());
      if (line.expectedBalanceVersion() != entry.getValue().balanceVersion()
          || line.moveToStockQuantity() > entry.getValue().quantity()) {
        throw versionConflict("Cabin contents balance changed while the disposition was prepared");
      }
      if (plan.mode() == dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode.DISPOSE_WITH_CABIN
          && line.moveToStockQuantity() != 0) {
        throw conflict("Dispose-with-cabin mode cannot assign furniture to stock movement");
      }
    }
  }

  private void validateEquipmentSnapshot(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot,
      UUID assetId,
      UUID warehouseId,
      long expectedAssetVersion,
      long expectedSourceBalanceVersion,
      long quantity) {
    if (snapshot.assetKind() != MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT
        || !assetId.equals(snapshot.assetId())
        || !warehouseId.equals(snapshot.warehouseId())) {
      throw conflict("Equipment snapshot does not match the disposition identity");
    }
    requireDispositionAllowed(snapshot, false);
    if (snapshot.version() != expectedAssetVersion
        || snapshot.sourceBalanceVersion() == null
        || snapshot.sourceBalanceVersion() != expectedSourceBalanceVersion) {
      throw versionConflict("Equipment asset or STOCK balance version is stale");
    }
    if (snapshot.quantity() == null || quantity < 1 || quantity > snapshot.quantity()) {
      throw conflict("Equipment disposition quantity exceeds the current STOCK balance");
    }
  }

  private static void requireDispositionAllowed(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot, boolean authorizedRepairLease) {
    if (!snapshot.dispositionAllowed()
        || snapshot.activeReservation()
        || snapshot.activeHold()
        || (!authorizedRepairLease && snapshot.activeLease())) {
      throw conflict("Property disposition is blocked by active asset operational state");
    }
  }

  private void validateRepairCanPropose(
      MaintenanceRepair requested,
      MaintenanceRepair root,
      List<MaintenanceRepair> chain) {
    if (requested.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || requested.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw conflict("Repair already has a terminal acceptance decision");
    }
    if (!requested.getWarehouseId().equals(root.getWarehouseId())
        || !requested.getRentalItemId().equals(root.getRentalItemId())
        || chain.stream().anyMatch(value -> !root.getWarehouseId().equals(value.getWarehouseId())
            || !root.getRentalItemId().equals(value.getRentalItemId()))) {
      throw conflict("Repair chain is not bound to one warehouse and cabin");
    }
    if (root.getLeaseId() == null
        || root.getLeaseVersion() == null
        || root.getFencingToken() == null
        || !"ACTIVE".equals(root.getLeaseReconciliationState())) {
      throw conflict("Repair root does not have an active asset lease proof");
    }
  }

  private List<PropertyDispositionContentSnapshotLineDraft> cabinDraftLines(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot,
      CabinContentsDispositionPlanInput plan) {
    if (plan == null) return List.of();
    Map<UUID, CabinContentsDispositionLineInput> inputs = plan.lines().stream()
        .collect(Collectors.toMap(CabinContentsDispositionLineInput::equipmentId, value -> value));
    return snapshot.contents().stream()
        .sorted(Comparator.comparing(line -> line.equipmentId().toString()))
        .map(
            value -> {
              CabinContentsDispositionLineInput input = inputs.get(value.equipmentId());
              return new PropertyDispositionContentSnapshotLineDraft(
                  value.equipmentId(),
                  value.equipmentName(),
                  value.equipmentFormat(),
                  value.quantity(),
                  input.moveToStockQuantity(),
                  value.balanceVersion());
            })
        .toList();
  }

  private static dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode
      contentsMode(CabinContentsDispositionPlanInput plan) {
    return plan == null ? null : plan.mode();
  }

  private MaintenanceDependencyGateway.PropertyDispositionLeaseProof leaseProof(
      PropertyDispositionDecision decision, List<MaintenanceRepair> chain) {
    if (decision.getAssetKind() != PropertyDispositionAssetKind.CABIN
        || (decision.getSource() != PropertyDispositionSource.REPAIR
            && decision.getSource() != PropertyDispositionSource.ESTIMATE)) {
      return null;
    }
    MaintenanceRepair root = chain.stream()
        .filter(value -> value.getId().equals(decision.getRootRepairId()))
        .findFirst()
        .orElseThrow(() -> conflict("Disposition root repair is missing"));
    if (root.getLeaseId() == null || root.getFencingToken() == null) {
      throw conflict("Repair-derived disposition has no active lease proof");
    }
    return new MaintenanceDependencyGateway.PropertyDispositionLeaseProof(
        root.getLeaseId(),
        root.getFencingToken(),
        root.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE",
        root.getEstimateId() == null ? root.getId() : root.getEstimateId());
  }

  private LeaseReleaseCommand leaseReleaseCommand(
      PropertyDispositionDecision decision, List<MaintenanceRepair> chain) {
    if (decision.getState() != PropertyDispositionState.EFFECTIVE
        || decision.getAssetKind() != PropertyDispositionAssetKind.CABIN
        || decision.getRootRepairId() == null) {
      return null;
    }
    MaintenanceRepair root = chain.stream()
        .filter(value -> value.getId().equals(decision.getRootRepairId()))
        .findFirst()
        .orElse(null);
    if (root == null
        || root.getLeaseId() == null
        || root.getLeaseVersion() == null
        || root.getFencingToken() == null
        || "RELEASED".equals(root.getLeaseReconciliationState())) {
      return null;
    }
    return new LeaseReleaseCommand(
        root.getLeaseId(),
        root.getLeaseVersion(),
        root.getFencingToken(),
        root.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE",
        root.getEstimateId() == null ? root.getId() : root.getEstimateId());
  }

  private List<MaintenanceRepair> repairChain(UUID rootRepairId) {
    if (rootRepairId == null) return List.of();
    return repairs.findRepairChain(rootRepairId);
  }

  private Map<UUID, List<MaintenanceRepair>> repairChains(
      Collection<PropertyDispositionDecision> values) {
    Set<UUID> roots = values.stream()
        .map(PropertyDispositionDecision::getRootRepairId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    if (roots.isEmpty()) return Map.of();
    return repairs.findAllForDispositionRoots(roots).stream()
        .collect(Collectors.groupingBy(
            PropertyDispositionApplicationService::rootId,
            LinkedHashMap::new,
            Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
  }

  private List<MaintenanceRepair> lockRepairChain(
      List<MaintenanceRepair> initial, Map<MaintenanceEventStore.StreamRef, Long> versions) {
    if (initial.isEmpty()) return List.of();
    Map<UUID, MaintenanceRepair> locked = repairs.findAllByIdForUpdate(
            initial.stream().map(MaintenanceRepair::getId).toList())
        .stream()
        .collect(Collectors.toMap(MaintenanceRepair::getId, value -> value));
    if (locked.size() != initial.size()) {
      throw conflict("Repair chain changed while property disposition was processing");
    }
    return initial.stream()
        .map(
            value -> {
              MaintenanceRepair repair = locked.get(value.getId());
              assertStreamParity(repair, versions.get(repairStream(repair.getId())));
              return repair;
            })
        .toList();
  }

  private PropertyDispositionDecisionResponse response(
      PropertyDispositionDecision decision, List<MaintenanceRepair> chain) {
    CabinContentsDispositionPlan contentsPlan = decision.getContentsMode() == null
        ? null
        : new CabinContentsDispositionPlan(
            decision.getContentsMode(),
            decision.getContents().stream().map(this::contentResponse).toList());
    List<PropertyDispositionRepairChainEntry> chainResponse = chain.stream()
        .map(value -> new PropertyDispositionRepairChainEntry(value.getId(), value.getVersion()))
        .toList();
    return new PropertyDispositionDecisionResponse(
        decision.getId(),
        decision.getVersion(),
        decision.getRecoveryVersion(),
        decision.getWarehouseId(),
        decision.getAssetKind(),
        decision.getAssetId(),
        decision.getAssetDisplayName(),
        decision.getKind(),
        decision.getSource(),
        decision.getState(),
        decision.getReason(),
        decision.getEvidenceLink(),
        decision.getQuantity(),
        decision.getExpectedAssetVersion(),
        decision.getExpectedSourceBalanceVersion(),
        decision.getMaintenanceCustodyClaimId(),
        decision.getMaintenanceCustodyVersion(),
        contentsPlan,
        decision.getRootRepairId(),
        chainResponse,
        decision.getInventoryId(),
        decision.getFindingId(),
        decision.getAssetEffectState(),
        decision.getMovementTaskId(),
        decision.getFailureCode(),
        decision.getFailureDetail(),
        actor(decision.getInitiatedByActorSnapshot()),
        decision.getCreatedAt(),
        actorOrNull(decision.getReviewedByActorSnapshot()),
        decision.getReviewedAt(),
        decision.getReviewComment() == null
            ? decision.getRejectionReason()
            : decision.getReviewComment(),
        decision.getState() == PropertyDispositionState.EFFECTIVE ? decision.getUpdatedAt() : null,
        decision.getUpdatedAt());
  }

  private CabinContentsDispositionLine contentResponse(PropertyDispositionContentSnapshotLine line) {
    return new CabinContentsDispositionLine(
        line.getEquipmentId(),
        line.getEquipmentName(),
        line.getEquipmentFormat(),
        line.getCurrentQuantity(),
        line.getMoveQuantity(),
        line.getCurrentQuantity() - line.getMoveQuantity(),
        line.getExpectedBalanceVersion());
  }

  private ActorSnapshot actor(String value) {
    ActorSnapshot actor = actorOrNull(value);
    if (actor == null) {
      throw new IllegalStateException("Property disposition actor snapshot is invalid");
    }
    return actor;
  }

  private ActorSnapshot actorOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      JsonNode node = mapper.readTree(value);
      String id = node.path("subjectId").stringValue();
      if (id == null || id.isBlank()) id = node.path("actorId").stringValue();
      String type = node.path("principalType").stringValue();
      if (id == null || id.isBlank() || type == null || type.isBlank()) return null;
      return new ActorSnapshot(id, "USER".equals(type) ? ActorType.USER : ActorType.SERVICE);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String actorJson() {
    var actor = actors.current();
    return actor == null ? systemActorJson() : write(actor);
  }

  private String systemActorJson() {
    return write(Map.of(
        "subjectId", SYSTEM_ACTOR_ID,
        "principalType", "SYSTEM",
        "profileRevision", SYSTEM_ACTOR_ID));
  }

  private String hash(Object value) {
    try {
      String serialized = mapper.writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, serialized);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize property disposition request");
      }
      return MaintenanceChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Property disposition request cannot be serialized", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Property disposition value cannot be serialized", exception);
    }
  }

  private void advisoryLock(String key) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        key);
  }

  private static MaintenanceDependencyGateway.PropertyAssetKind gatewayAssetKind(
      PropertyDispositionAssetKind value) {
    return MaintenanceDependencyGateway.PropertyAssetKind.valueOf(value.name());
  }

  private static MaintenanceDependencyGateway.PropertyDispositionKind gatewayDispositionKind(
      PropertyDispositionKind value) {
    return MaintenanceDependencyGateway.PropertyDispositionKind.valueOf(value.name());
  }

  private static UUID rootId(MaintenanceRepair repair) {
    return repair.getRootRepairId() == null ? repair.getId() : repair.getRootRepairId();
  }

  private static MaintenanceEventStore.StreamRef dispositionStream(UUID id) {
    return new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.PROPERTY_DISPOSITION, id);
  }

  private static MaintenanceEventStore.StreamRef repairStream(UUID id) {
    return new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.REPAIR, id);
  }

  private static void assertStreamParity(PropertyDispositionDecision decision, Long eventVersion) {
    if (eventVersion == null || decision.getVersion() != eventVersion) {
      throw conflict("Property disposition event stream is out of sync with local state");
    }
  }

  private static void assertStreamParity(MaintenanceRepair repair, Long eventVersion) {
    if (eventVersion == null || repair.getVersion() != eventVersion) {
      throw conflict("Repair event stream is out of sync with local state");
    }
  }

  private static String unitNumber(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Cabin display number is required for furniture movement");
    }
    String normalized = value.trim();
    if (normalized.length() > 64) {
      throw new IllegalArgumentException("Cabin display number is too long for furniture movement");
    }
    return normalized;
  }

  private static String combinedReason(String reason, String comment) {
    String normalizedReason = reason == null ? null : reason.trim();
    String normalizedComment = comment == null ? null : comment.trim();
    if (normalizedComment == null || normalizedComment.isEmpty()) return normalizedReason;
    String combined = normalizedReason + "\n" + normalizedComment;
    if (combined.length() > 2000) {
      throw new IllegalArgumentException("Repair disposition reason and comment exceed 2000 characters");
    }
    return combined;
  }

  private static <T> T requiredResult(T value) {
    if (value == null) throw new IllegalStateException("Property disposition transaction returned no result");
    return value;
  }

  private static void require(Object value, String message) {
    if (value == null) throw new IllegalArgumentException(message);
  }

  private static MaintenanceConflictException versionConflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_VERSION_CONFLICT", message);
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }

  public record ProcessingView(
      UUID decisionId,
      UUID warehouseId,
      PropertyDispositionState state,
      UUID movementTaskId,
      boolean requiresMovement,
      boolean requiresAssetEffect,
      MaintenanceDependencyGateway.PropertyDispositionPreparation preparation,
      MaintenanceDependencyGateway.PropertyEquipmentMovementCommand movementCommand,
      LeaseReleaseCommand leaseRelease) {}

  public record LeaseReleaseCommand(
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      String ownerType,
      UUID ownerId) {}

  private record ReviewPreflight(
      UUID decisionId,
      UUID warehouseId,
      long version,
      PropertyDispositionState state) {}

  private record RepairRequestFingerprint(
      UUID repairId,
      UUID warehouseId,
      Long expectedVersion,
      String reason,
      String comment,
      CabinContentsDispositionPlanInput contentsPlan) {}

  private record FurnitureCustodyDecisionFingerprint(
      UUID claimId,
      long custodyVersion,
      UUID rootRepairId,
      UUID completedRepairId,
      UUID equipmentId,
      long quantity,
      String reason) {}

  private record UnaccountedFurnitureDecisionFingerprint(
      UUID rootRepairId,
      UUID completedRepairId,
      UUID equipmentId,
      long quantity,
      String reason) {}
}
