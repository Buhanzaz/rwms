package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
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

  MaintenanceTaskBoardSupport(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceReconciliationStore reconciliations,
      RepairPlaceService repairPlaces,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceMediaSupport mediaSupport,
      MaintenanceRepairModelSupport repairModelSupport) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.mediaFacts = mediaFacts;
    this.reconciliations = reconciliations;
    this.repairPlaces = repairPlaces;
    this.commandSupport = commandSupport;
    this.estimateSupport = estimateSupport;
    this.mediaSupport = mediaSupport;
    this.repairModelSupport = repairModelSupport;
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

  protected void confirmTaskRegistration(
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

  protected List<MaintenanceDependencyGateway.TaskStage> taskStages(MaintenanceRepair repair) {
    List<MediaReferenceInput> repairMedia = new ArrayList<>(mediaSupport.repairMedia(repair));
    return repairStages.findAllByRepairIdOrderByStageNo(repair.getId()).stream()
        .map(
            stage -> {
              List<EstimateLineResponse> work =
                  commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class);
              List<EstimateLineResponse> materials =
                  commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class);
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

  protected String taskStageText(RepairStage stage) {
    List<String> work =
        commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class).stream()
            .map(EstimateLineResponse::description)
            .filter(value -> value != null && !value.isBlank())
            .toList();
    List<String> materials =
        commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class).stream()
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
}
