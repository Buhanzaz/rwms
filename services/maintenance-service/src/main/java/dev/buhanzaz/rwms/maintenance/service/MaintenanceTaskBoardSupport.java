package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Builds task-board and asset reconciliation intent from canonical repair plans. */
@Service
final class MaintenanceTaskBoardSupport {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceReconciliationStore reconciliations;
  private final RepairPlaceService repairPlaces;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateSupport estimateSupport;
  private final MaintenanceMediaSupport mediaSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final MaintenanceReworkSourceMediaResolver reworkSourceMedia;

  MaintenanceTaskBoardSupport(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceReconciliationStore reconciliations,
      RepairPlaceService repairPlaces,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceMediaSupport mediaSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      MaintenanceReworkSourceMediaResolver reworkSourceMedia) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.mediaFacts = mediaFacts;
    this.reconciliations = reconciliations;
    this.repairPlaces = repairPlaces;
    this.commandSupport = commandSupport;
    this.estimateSupport = estimateSupport;
    this.mediaSupport = mediaSupport;
    this.repairModelSupport = repairModelSupport;
    this.reworkSourceMedia = reworkSourceMedia;
  }

  protected void enqueueRepairQueue(
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

  protected void enqueueTaskRegistration(MaintenanceRepair repair, UUID key) {
    reconciliations.enqueue(
        repair.getId(),
        "TASK_BOARD",
        "REGISTER_TASK",
        key,
        Map.of("repairId", repair.getId().toString()));
  }

  protected void enqueueOrdinaryRepairExecution(
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

  /** Enqueues the dedicated outbound movement for an external capital repair. */
  protected void enqueueCapitalRepairMovement(MaintenanceRepair repair, UUID driverTaskKey) {
    if (!repair.isMovementToRepair()) return;
    reconciliations.enqueue(
        repair.getId(),
        "LOGISTICS",
        "CREATE_DRIVER_TASK",
        driverTaskKey,
        Map.of(
            "repairId", repair.getId().toString(),
            "kind", "CAPITAL_TO_PRODUCTION"));
  }

  protected boolean requiresDriverDeliveryToRepair(MaintenanceRepair repair) {
    return repair.isMovementToRepair();
  }

  protected static void prepareStagesForQueue(
      List<RepairStage> stages, boolean externalCapital) {
    for (RepairStage stage : stages) {
      if (externalCapital) {
        stage.completeAsExternalCapital();
      } else {
        stage.queued();
      }
    }
  }

  protected void enqueueRepairComplexityStatusSync(
      MaintenanceRepair repair, UUID key) {
    reconciliations.enqueue(
        repair.getId(),
        "ASSET",
        "SYNC_REPAIR_COMPLEXITY_STATUS",
        key,
        Map.of("repairId", repair.getId().toString()));
  }

  /**
   * Reasserts repair complexity after a completed-inventory correction released the retained
   * repair's exact operation lease. The durable flag tells the worker to acquire a replacement
   * fence before it treats an already-matching asset status as reconciled.
   */
  protected void enqueueRepairComplexityStatusSyncAfterLeaseRelease(
      MaintenanceRepair repair, UUID key) {
    reconciliations.enqueue(
        repair.getId(),
        "ASSET",
        "SYNC_REPAIR_COMPLEXITY_STATUS",
        key,
        Map.of("repairId", repair.getId().toString(), "reacquireReleasedLease", true));
  }

  protected void enqueueTerminalAsset(
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

  protected void enqueueAcceptedCharacteristics(
      MaintenanceRepair accepted, List<MaintenanceRepair> sourceChain) {
    Map<UUID, CabinCharacteristicReference> characteristics =
        new LinkedHashMap<>();
    List<MaintenanceRepair> acceptedChain = new ArrayList<>(sourceChain);
    acceptedChain.add(accepted);
    for (MaintenanceRepair repair : acceptedChain) {
      for (RepairStage stage :
          repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
        for (EstimateLineResponse material :
            commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class)) {
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
    UUID rootRepairId = commandSupport.rootId(accepted);
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

  /** Confirms task-board entry identities against the same canonical route published by maintenance. */
  protected void confirmTaskRegistration(
      UUID repairId, MaintenanceDependencyGateway.TaskSnapshot task) {
    confirmTaskRegistration(repairId, task, false);
  }

  /**
   * Confirms task-board entry identities after its source-owned pre-start replacement fence.
   * Existing queued mappings may move to the replacement route IDs; started mappings cannot.
   */
  protected void confirmPreStartTaskReplacement(
      UUID repairId, MaintenanceDependencyGateway.TaskSnapshot task) {
    confirmTaskRegistration(repairId, task, true);
  }

  private void confirmTaskRegistration(
      UUID repairId,
      MaintenanceDependencyGateway.TaskSnapshot task,
      boolean preStartReplacement) {
    List<List<RepairStage>> stageGroups =
        RepairPhaseSequence.canonicalStageGroups(
            repairStages.findAllByRepairIdOrderByStageNo(repairId));
    if (task.stages().size() != stageGroups.size()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board route truth does not match the maintenance plan");
    }
    List<RepairStage> stages = mergePersistedQueuedStageGroups(stageGroups);
    Map<Integer, MaintenanceDependencyGateway.TaskStageSnapshot> byRoute = new HashMap<>();
    task.stages().forEach(stage -> {
      if (byRoute.put(stage.routeIndex(), stage) != null) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board returned a duplicate routeIndex");
      }
    });
    for (int routeIndex = 0; routeIndex < stages.size(); routeIndex++) {
      RepairStage stage = stages.get(routeIndex);
      MaintenanceDependencyGateway.TaskStageSnapshot external = byRoute.get(routeIndex);
      if (external == null) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board routeIndex is missing");
      }
      if (preStartReplacement) {
        stage.confirmPreStartTaskBoardReplacement(
            external.taskBoardEntryId(), external.entryVersion());
      } else {
        stage.confirmTaskBoardRegistration(
            external.taskBoardEntryId(), external.entryVersion());
      }
    }
    repairStages.saveAllAndFlush(stages);
  }

  /**
   * Commits the local half of an already accepted task-board queue-package route.
   *
   * <p>The remote pre-start replacement happens before this method. Duplicate rows are deleted and
   * survivors are moved through collision-free temporary stage numbers before their final
   * contiguous order is written, so the existing {@code (repair_id, stage_no)} uniqueness fence is
   * never weakened. A transaction rollback restores the complete former local plan.
   */
  private List<RepairStage> mergePersistedQueuedStageGroups(
      List<List<RepairStage>> stageGroups) {
    if (stageGroups.stream().noneMatch(group -> group.size() > 1)) {
      return stageGroups.stream().map(List::getFirst).toList();
    }
    List<RepairStage> duplicates =
        stageGroups.stream().flatMap(group -> group.stream().skip(1)).toList();
    repairStages.deleteAll(duplicates);
    repairStages.flush();

    List<RepairStage> survivors = stageGroups.stream().map(List::getFirst).toList();
    int temporaryBase =
        stageGroups.stream()
                .flatMap(List::stream)
                .mapToInt(RepairStage::getStageNo)
                .max()
                .orElse(0)
            + 1;
    for (int index = 0; index < survivors.size(); index++) {
      survivors.get(index).resequenceQueuedTaskPlan(temporaryBase + index);
    }
    repairStages.saveAllAndFlush(survivors);

    for (int index = 0; index < stageGroups.size(); index++) {
      mergePersistedQueuedStageGroup(stageGroups.get(index), index);
    }
    repairStages.saveAllAndFlush(survivors);
    return List.copyOf(survivors);
  }

  /** Combines every source-owned content row of one physical queue into its stable first stage. */
  private void mergePersistedQueuedStageGroup(List<RepairStage> group, int stageNo) {
    RepairStage survivor = group.getFirst();
    List<EstimateLineResponse> work = mergedWorkLines(group);
    List<EstimateLineResponse> materials = mergedMaterialLines(group);
    if (work.size() + materials.size() > 2000) {
      throw new IllegalStateException(
          "Combined repair queue stage exceeds the supported content limit");
    }
    UUID primaryLineId =
        group.stream()
            .map(RepairStage::getPrimaryLineId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    survivor.mergeQueuedTaskPlan(
        stageNo,
        commandSupport.write(work),
        commandSupport.write(materials),
        primaryLineId,
        mergedGroupComment(group),
        mergedTaskDeadline(group));
  }

  protected Optional<MaintenanceRepair> primaryLifecycleOwnerWithLeaseIdentity(
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

  protected static void requireCatalogPositionWork(
      MaintenanceReconciliationStore.WorkItem work, String operation) {
    if (work.repairId() != null
        || !"TASK_BOARD".equals(work.dependency())
        || !operation.equals(work.operation())
        || work.catalogVersionId() == null
        || work.catalogNodeId() == null
        || work.catalogQueueId() == null
        || work.catalogExternalReferenceId() == null
        || work.catalogExternalReferenceId().isBlank()
        || !work.catalogVersionId().equals(MaintenanceCommandSupport.uuidField(work.payload(), "catalogVersionId"))
        || !work.catalogNodeId().equals(MaintenanceCommandSupport.uuidField(work.payload(), "catalogNodeId"))
        || !work.catalogQueueId().equals(MaintenanceCommandSupport.uuidField(work.payload(), "queueId"))
        || !work.catalogExternalReferenceId().equals(
            MaintenanceCommandSupport.stringField(work.payload(), "externalReferenceId"))) {
      throw new IllegalStateException("Stored catalog-position reconciliation is incomplete");
    }
  }

  protected MaintenanceRepair requireWorkRepair(
      MaintenanceReconciliationStore.WorkItem work) {
    if (work.repairId() == null) {
      throw new IllegalStateException("Repair reconciliation is missing repairId");
    }
    MaintenanceRepair repair = repairModelSupport.requireRepair(work.repairId());
    JsonNode payloadId = work.payload().get("repairId");
    if (payloadId == null || !payloadId.isTextual()
        || !repair.getId().toString().equals(payloadId.stringValue())) {
      throw new IllegalStateException("Reconciliation payload does not match repairId");
    }
    return repair;
  }

  /**
   * Builds canonically ordered worker snapshots with the selected cover first and line media linked
   * by work ID.
   *
   * <p>A repeated line also exposes the exact source stage's worker result photos as general source
   * media. They remain input context for the new stage and are never copied into its result-evidence
   * projection.
   */
  protected List<MaintenanceDependencyGateway.TaskStage> taskStages(MaintenanceRepair repair) {
    List<MediaReferenceInput> repairMedia = new ArrayList<>(mediaSupport.repairMedia(repair));
    String taskTitle =
        workerTaskTitle(repairModelSupport.repairComplexityFromStoredStages(repair).type());
    UUID coverMediaId =
        MaintenanceMediaSupport.effectiveCoverMediaId(repair.getCoverMediaId(), repairMedia);
    if (coverMediaId != null) {
      repairMedia.sort(
          Comparator.comparingInt(
              reference -> coverMediaId.equals(reference.mediaId()) ? 0 : 1));
    }
    List<MaintenanceDependencyGateway.TaskSourceMedia> commonRepairMedia =
        repairMedia.stream()
            .map(reference -> taskSourceMedia(reference, repair.getCreatedAt()))
            .toList();
    List<List<RepairStage>> stageGroups =
        RepairPhaseSequence.canonicalStageGroups(
            repairStages.findAllByRepairIdOrderByStageNo(repair.getId()));
    List<RepairStage> orderedStages = stageGroups.stream().flatMap(List::stream).toList();
    Map<UUID, List<MaintenanceDependencyGateway.TaskSourceMedia>> inheritedReworkMedia =
        reworkSourceMedia.resolve(repair, orderedStages);
    return IntStream.range(0, stageGroups.size())
        .mapToObj(
            routeIndex -> {
              List<RepairStage> group = stageGroups.get(routeIndex);
              RepairStage stage = group.getFirst();
              List<EstimateLineResponse> work = mergedWorkLines(group);
              List<EstimateLineResponse> materials = mergedMaterialLines(group);
              List<MaintenanceDependencyGateway.TaskWork> workSnapshots =
                  taskWorkSnapshots(work);
              List<MaintenanceDependencyGateway.TaskMaterial> materialSnapshots =
                  materials.stream()
                      .map(
                          line ->
                              new MaintenanceDependencyGateway.TaskMaterial(
                                  line.id(),
                                  MaintenanceEstimateSupport.taskLineName(line),
                                  MaintenanceEstimateSupport.taskLineQuantity(line).doubleValue(),
                                  line.unit()))
                      .toList();
              List<MaintenanceDependencyGateway.TaskComment> comments = new ArrayList<>();
              work.stream()
                  .filter(line -> line.comment() != null && !line.comment().isBlank())
                  .map(
                      line ->
                          new MaintenanceDependencyGateway.TaskComment(
                              line.id(),
                              MaintenanceEstimateSupport.taskLineComment(line.comment()),
                              "Смета",
                              repair.getCreatedAt()))
                  .forEach(comments::add);
              String groupComment = mergedGroupComment(group);
              if (!groupComment.isBlank()) {
                comments.add(
                    new MaintenanceDependencyGateway.TaskComment(
                        stage.getId(),
                        groupComment,
                        "Диспетчер",
                        repair.getCreatedAt()));
              }
              LinkedHashMap<UUID, MaintenanceDependencyGateway.TaskSourceMedia> sourceMedia =
                  new LinkedHashMap<>();
              commonRepairMedia.forEach(media -> sourceMedia.put(media.mediaId(), media));
              java.util.stream.Stream.concat(work.stream(), materials.stream())
                  .flatMap(line -> line.mediaReferences().stream())
                  .forEach(
                      reference ->
                          sourceMedia.put(
                              reference.mediaId(),
                              taskSourceMedia(reference, repair.getCreatedAt())));
              group.forEach(
                  member ->
                      inheritedReworkMedia
                          .getOrDefault(member.getId(), List.of())
                          .forEach(media -> sourceMedia.putIfAbsent(media.mediaId(), media)));
              if (sourceMedia.size() > 100) {
                throw new IllegalStateException(
                    "Worker task source media exceeds the supported limit");
              }
              return new MaintenanceDependencyGateway.TaskStage(
                  stage.getId(),
                  routeIndex,
                  stage.getStageKind(),
                  taskStageText(
                      work, materials, groupComment, stage.getRoutingQueueName()),
                  stage.getRoutingQueueId(),
                  mergedTaskDeadline(group),
                  workSnapshots,
                  materialSnapshots,
                  comments,
                  List.copyOf(sourceMedia.values()),
                  plannedDurationMinutes(work),
                  taskTitle);
            })
        .toList();
  }

  private List<EstimateLineResponse> mergedWorkLines(List<RepairStage> group) {
    return group.stream()
        .flatMap(
            stage ->
                commandSupport
                    .readList(stage.getWorkLines(), EstimateLineResponse.class)
                    .stream())
        .toList();
  }

  private List<EstimateLineResponse> mergedMaterialLines(List<RepairStage> group) {
    return group.stream()
        .flatMap(
            stage ->
                commandSupport
                    .readList(stage.getMaterialLines(), EstimateLineResponse.class)
                    .stream())
        .toList();
  }

  private static String mergedGroupComment(List<RepairStage> group) {
    String value =
        group.stream()
            .map(RepairStage::getGroupComment)
            .filter(comment -> comment != null && !comment.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.joining("\n\n"));
    if (value.length() > 2000) {
      throw new IllegalStateException(
          "Combined repair queue comment exceeds the supported limit");
    }
    return value;
  }

  private static OffsetDateTime mergedTaskDeadline(List<RepairStage> group) {
    List<OffsetDateTime> deadlines =
        group.stream()
            .map(RepairStage::getTaskDeadline)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    if (deadlines.size() > 1) {
      throw new IllegalStateException(
          "Combined repair queue stages have conflicting task deadlines");
    }
    return deadlines.isEmpty() ? null : deadlines.getFirst();
  }

  /** Returns the canonical maintenance-owned complexity label used as the WorkerApp task title. */
  static String workerTaskTitle(RepairComplexity complexity) {
    return complexity.displayName();
  }

  protected static List<MaintenanceDependencyGateway.TaskWork> taskWorkSnapshots(
      List<EstimateLineResponse> work) {
    return work.stream()
        .map(
            line -> {
              CatalogNodeSnapshot catalog = line.catalogSnapshot();
              return new MaintenanceDependencyGateway.TaskWork(
                  line.id(),
                  MaintenanceEstimateSupport.taskLineName(line),
                  MaintenanceEstimateSupport.taskLineQuantity(line).doubleValue(),
                  line.unit(),
                  line.normativeMinutes(),
                  MaintenanceEstimateSupport.taskLineComment(line.comment()),
                  line.mediaReferences().stream()
                      .map(MediaReferenceInput::mediaId)
                      .distinct()
                      .toList());
            })
        .toList();
  }

  protected static Integer plannedDurationMinutes(List<EstimateLineResponse> work) {
    if (work.isEmpty()) return null;
    BigDecimal total = BigDecimal.ZERO;
    for (EstimateLineResponse line : work) {
      int duration = line.normativeMinutes();
      if (duration < 1) {
        throw new IllegalStateException(
            "Stored repair work has no positive planned duration");
      }
      total = total.add(BigDecimal.valueOf(duration).multiply(MaintenanceEstimateSupport.taskLineQuantity(line)));
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

  protected MaintenanceDependencyGateway.TaskSourceMedia taskSourceMedia(
      MediaReferenceInput reference, OffsetDateTime fallbackRecordedAt) {
    MediaFactProjection fact = mediaFacts.findById(reference.mediaId()).orElse(null);
    String contentType = "application/octet-stream";
    OffsetDateTime capturedAt = null;
    OffsetDateTime recordedAt = fallbackRecordedAt;
    if (fact != null) {
      Map<String, Object> metadata = commandSupport.jsonMap(fact.getSafeMetadata());
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

  protected String taskStageText(
      List<EstimateLineResponse> workLines,
      List<EstimateLineResponse> materialLines,
      String groupComment,
      String routingQueueName) {
    List<String> work =
        workLines.stream()
            .map(EstimateLineResponse::description)
            .filter(value -> value != null && !value.isBlank())
            .toList();
    List<String> materials =
        materialLines.stream()
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
    if (groupComment != null && !groupComment.isBlank()) {
      parts.add(groupComment);
    }
    String result = parts.isEmpty() ? routingQueueName : String.join(". ", parts);
    return result.length() <= 2000 ? result : result.substring(0, 2000);
  }

}
