package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Provides repair-chain, rework, lease-refresh and repair-stream lifecycle mechanics. */
@Service
final class MaintenanceRepairLifecycleSupport {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final InventoryPublicationSuccessorActivator inventorySuccessors;
  private final RepairPlaceService repairPlaces;
  private final InventoryPublicationPrestartReplacementGuard prestartReplacementGuard;
  private final MaintenanceDependencyGateway dependencies;
  private final JdbcTemplate jdbc;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateSupport estimateSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;

  MaintenanceRepairLifecycleSupport(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      InventoryPublicationSuccessorActivator inventorySuccessors,
      RepairPlaceService repairPlaces,
      InventoryPublicationPrestartReplacementGuard prestartReplacementGuard,
      MaintenanceDependencyGateway dependencies,
      JdbcTemplate jdbc,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceRepairModelSupport repairModelSupport) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.inventorySuccessors = inventorySuccessors;
    this.repairPlaces = repairPlaces;
    this.prestartReplacementGuard = prestartReplacementGuard;
    this.dependencies = dependencies;
    this.jdbc = jdbc;
    this.commandSupport = commandSupport;
    this.estimateSupport = estimateSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.reconciliationSupport = reconciliationSupport;
    this.repairModelSupport = repairModelSupport;
  }

  protected List<EstimateLineResponse> canonicalReworkLines(
      MaintenanceRepair source, List<ReworkLineInput> inputs) {
    if (inputs == null) throw commandSupport.invalid("Rework lines are required");
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
                    commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
                    commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class).stream()))
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
        throw commandSupport.invalid("ADDED rework line identity is invalid");
      }
    }
    Map<UUID, EstimateLineResponse> canonicalAdded = estimateSupport.canonicalRepairLines(
            source.getWarehouseId(), added.stream().map(AddedReworkLineInput::line).toList())
        .stream()
        .collect(java.util.stream.Collectors.toMap(EstimateLineResponse::id, value -> value));

    List<EstimateLineResponse> result = new ArrayList<>();
    for (ReworkLineInput input : inputs) {
      if (input == null
          || input.id() == null
          || !childIds.add(input.id())
          || existingLineIds.contains(input.id())) {
        throw commandSupport.invalid("Rework child line IDs must be new, present and unique");
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
        throw commandSupport.invalid("Unsupported rework line disposition");
      }
      ReworkCandidateLine candidate = candidateBySource.get(
          new ReworkCandidateKey(repeat.sourceRepairId(), repeat.sourceLineId()));
      if (candidate == null) {
        throw commandSupport.invalid(
            "REPEAT must reference the latest completed line in the same repair chain");
      }
      if (!repeatedLineages.add(candidate.lineageRootLineId())) {
        throw commandSupport.invalid("One rework can repeat a line lineage only once");
      }
      BigDecimal quantity;
      try {
        quantity = new BigDecimal(repeat.quantity());
      } catch (RuntimeException exception) {
        throw commandSupport.invalid("Rework line quantity is invalid");
      }
      if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3) {
        throw commandSupport.invalid("Rework line quantity is invalid");
      }
      EstimateLineResponse inherited = candidate.line();
      if (inherited.catalogSnapshot() != null
          && inherited.catalogSnapshot().furnitureEquipment() != null
          && quantity.stripTrailingZeros().scale() > 0) {
        throw commandSupport.invalid("Furniture quantity must be a whole number");
      }
      String comment = estimateSupport.workLineComment(inherited.lineType(), repeat.comment());
      if (comment != null) {
        comment = comment.isBlank() ? null : comment.trim();
        if (comment != null && comment.length() > 2000) {
          throw commandSupport.invalid("Rework line comment is too long");
        }
      }
      long unitPriceMinor = commandSupport.moneyToMinor(inherited.unitPrice());
      result.add(
          new EstimateLineResponse(
              repeat.id(),
              inherited.catalogSnapshot(),
              inherited.lineType(),
              inherited.description(),
              inherited.unit(),
              commandSupport.quantity(quantity),
              inherited.unitPrice(),
              commandSupport.money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              inherited.normativeMinutes(),
              comment,
              inherited.mediaReferences(),
              ReworkLineDisposition.REPEAT,
              candidate.sourceRepairId(),
              candidate.sourceLineId(),
              candidate.lineageRootLineId()));
    }
    estimateSupport.validateWorkLineMediaIsolation(result);
    return List.copyOf(result);
  }

  protected List<ReworkCandidateLine> reworkCandidateItems(MaintenanceRepair source) {
    LinkedHashMap<UUID, ReworkCandidateLine> latestByLineage = new LinkedHashMap<>();
    for (MaintenanceRepair repair : repairChain(source)) {
      if (repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) continue;
      for (RepairStage stage :
          repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
        if (stage.getState() != RepairStageState.DONE) continue;
        List<EstimateLineResponse> completedLines =
            java.util.stream.Stream.concat(
                    commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
                    commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
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

  protected List<MaintenanceRepair> repairChain(MaintenanceRepair source) {
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

  protected static EstimateLineResponse withReworkMetadata(
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

  protected void requireNoActiveRework(MaintenanceRepair repair) {
    if (hasUnresolvedRework(repair.getId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Active rework blocks a terminal acceptance decision");
    }
  }

  protected boolean hasUnresolvedRework(UUID sourceRepairId) {
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

  protected LeaseRefreshPlan prepareLeaseRefreshPlan(
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
        eventPayloadSupport.ownerType(owner),
        eventPayloadSupport.ownerId(owner),
        owner.getLeaseId(),
        owner.getLeaseVersion(),
        owner.getFencingToken(),
        owner.getLeaseExpiresAt(),
        copySignatures);
  }

  protected LeaseRefresh refreshLeaseForCommand(LeaseRefreshPlan plan, UUID commandKey) {
    commandSupport.requireNoCallerTransaction("refresh a repair operation lease");
    MaintenanceDependencyGateway.LeaseSnapshot lease = new MaintenanceDependencyGateway.LeaseSnapshot(
        plan.leaseId(),
        plan.leaseVersion(),
        plan.rentalItemId(),
        plan.ownerType(),
        UUID.fromString(plan.ownerId()),
        plan.fencingToken(),
        plan.expiresAt());
    boolean ownerRenewed = false;
    if (commandSupport.leaseHasExpired(lease.expiresAt())) {
      lease = dependencies.acquireLease(
          commandSupport.derived(commandKey, "reacquire-lease"),
          plan.rentalItemId(),
          plan.rentalItemVersion(),
          plan.ownerType(),
          plan.ownerId());
      reconciliationSupport.validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      commandSupport.requireFreshDependencyLease(lease);
      ownerRenewed = true;
    } else if (!commandSupport.leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          commandSupport.derived(commandKey, "renew-lease"),
          plan.leaseId(),
          plan.leaseVersion(),
          plan.fencingToken(),
          plan.ownerType(),
          plan.ownerId());
      reconciliationSupport.validateLeaseTruth(plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      if (!plan.leaseId().equals(lease.leaseId())
          || plan.fencingToken() != lease.fencingToken()
          || lease.version() != Math.addExact(plan.leaseVersion(), 1)) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Asset-service lease renewal does not preserve the current fence and version");
      }
      commandSupport.requireFreshDependencyLease(lease);
      ownerRenewed = true;
    }
    return new LeaseRefresh(lease, ownerRenewed);
  }

  protected void requireMatchingLeaseRefreshPlan(
      LeaseRefreshPlan expected, MaintenanceRepair repair, List<MaintenanceRepair> sourceChain) {
    LeaseRefreshPlan current = prepareLeaseRefreshPlan(repair, sourceChain);
    if (!expected.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair lease changed before command finalization");
    }
  }

  protected static void applyLeaseSnapshot(
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

  protected MaintenanceRepair leaseOwner(
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

  protected static List<MaintenanceRepair> leaseCopies(
      MaintenanceRepair repair,
      List<MaintenanceRepair> sourceChain,
      MaintenanceRepair owner) {
    Map<UUID, MaintenanceRepair> copies = new LinkedHashMap<>();
    copies.put(repair.getId(), repair);
    sourceChain.forEach(source -> copies.put(source.getId(), source));
    copies.put(owner.getId(), owner);
    return List.copyOf(copies.values());
  }

  protected void requireRenewableLease(MaintenanceRepair repair) {
    if (repair.getLeaseId() == null
        || repair.getLeaseVersion() == null
        || repair.getFencingToken() == null
        || repair.getLeaseExpiresAt() == null
        || !"ACTIVE".equals(repair.getLeaseReconciliationState())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_LEASE_CONFLICT", "Repair does not have an active renewable lease snapshot");
    }
  }

  protected boolean blocksRepairExecution(UUID repairId) {
    return prestartReplacementGuard.blocksRepairExecution(repairId);
  }

  protected boolean isRepairPlaceOccupied(UUID warehouseId, UUID repairId) {
    return repairPlaces.isOccupied(warehouseId, repairId);
  }

  protected void releaseAfterAcceptance(MaintenanceRepair repair) {
    inventorySuccessors.releaseAfterAcceptance(
        repair, lastRepairEventId(repair.getId()), repair.getDecisionRecordedAt());
  }

  protected void cascadeTerminal(
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
          eventPayloadSupport.repairLocal(saved),
          eventPayloadSupport.repairFact(
              accepted ? MaintenanceEventType.REPAIR_ACCEPTED
                  : MaintenanceEventType.REPAIR_WRITTEN_OFF,
              saved),
          eventPayloadSupport.repairSnapshot(saved));
      if (accepted) {
        inventorySuccessors.releaseAfterAcceptance(
            saved, lastRepairEventId(saved.getId()), saved.getDecisionRecordedAt());
      }
    }
  }

  protected List<MaintenanceRepair> sourceChain(MaintenanceRepair repair) {
    List<MaintenanceRepair> result = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    UUID sourceId = repair.getSourceRepairId();
    while (sourceId != null) {
      if (!visited.add(sourceId)) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Repair source chain contains a cycle");
      }
      MaintenanceRepair source = repairModelSupport.requireRepair(sourceId);
      result.add(source);
      sourceId = source.getSourceRepairId();
    }
    return List.copyOf(result);
  }

  protected LockedRepairChain lockAndReloadRepairChain(
      MaintenanceRepair initial, long expectedVersion) {
    List<MaintenanceRepair> initialSources = sourceChain(initial);
    List<UUID> ids = new ArrayList<>();
    ids.add(initial.getId());
    initialSources.forEach(source -> ids.add(source.getId()));
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        ids.stream().map(MaintenanceRepairLifecycleSupport::stream).toList());
    commandSupport.assertVersion(locked.get(stream(initial.getId())), expectedVersion);
    Map<UUID, MaintenanceRepair> current = repairs.findAllByIdForUpdate(ids).stream()
        .collect(java.util.stream.Collectors.toMap(MaintenanceRepair::getId, value -> value));
    MaintenanceRepair repair = Optional.ofNullable(current.get(initial.getId()))
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    commandSupport.assertVersion(repair.getVersion(), expectedVersion);
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

  protected LockedRework lockAndReloadRework(MaintenanceRepair initial, long expectedVersion) {
    if (initial.getKind() != RepairKind.REWORK
        || initial.getRootRepairId() == null
        || initial.getSourceRepairId() == null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair does not have a valid rework hierarchy");
    }
    List<UUID> ids = List.of(
        initial.getId(), initial.getRootRepairId(), initial.getSourceRepairId());
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        ids.stream().map(MaintenanceRepairLifecycleSupport::stream).toList());
    commandSupport.assertVersion(locked.get(stream(initial.getId())), expectedVersion);
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
    commandSupport.assertVersion(repair.getVersion(), expectedVersion);
    assertStreamParity(repair, locked);
    assertStreamParity(root, locked);
    assertStreamParity(source, locked);
    validateReworkOwnership(repair, root);
    validateReworkOwnership(repair, source);
    return new LockedRework(repair, root, source, locked);
  }

  protected static void validateReworkOwnership(
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

  protected static void assertStreamParity(
      MaintenanceRepair repair, Map<MaintenanceEventStore.StreamRef, Long> versions) {
    Long streamVersion = versions.get(stream(repair.getId()));
    if (streamVersion == null || streamVersion != repair.getVersion()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Repair projection and stream versions diverged");
    }
  }

  protected static MaintenanceEventStore.StreamRef stream(UUID repairId) {
    return new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.REPAIR, repairId);
  }

  protected UUID lastRepairEventId(UUID repairId) {
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

  protected void validateInboundDeliveryRetryRequest(RetryInboundDeliveryRequest request) {
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
      throw commandSupport.invalid("Inbound delivery retry request is invalid");
    }
  }

  protected void requireAbsentInboundDriverTask(UUID repairId) {
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
}
