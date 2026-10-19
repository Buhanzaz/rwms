package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Validates, stores and resolves maintenance media references and durable media-owner proof intent. */
@Service
final class MaintenanceMediaSupport {
  private final MaintenanceRepairRepository repairs;
  private final InventoryRepairSourceRepository inventorySources;
  private final RepairStageRepository repairStages;
  private final MaintenanceMediaReferenceRepository mediaReferences;
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceReconciliationStore reconciliations;
  private final MaintenanceCommandSupport commandSupport;

  MaintenanceMediaSupport(
      MaintenanceRepairRepository repairs,
      InventoryRepairSourceRepository inventorySources,
      RepairStageRepository repairStages,
      MaintenanceMediaReferenceRepository mediaReferences,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceReconciliationStore reconciliations,
      MaintenanceCommandSupport commandSupport) {
    this.repairs = repairs;
    this.inventorySources = inventorySources;
    this.repairStages = repairStages;
    this.mediaReferences = mediaReferences;
    this.mediaFacts = mediaFacts;
    this.reconciliations = reconciliations;
    this.commandSupport = commandSupport;
  }

  protected void replaceMedia(
      String aggregateType,
      String ownerType,
      UUID aggregateId,
      UUID warehouseId,
      List<MediaReferenceInput> requested) {
    replaceMedia(
        aggregateType, aggregateId, ownerType, aggregateId, warehouseId, requested);
  }

  protected void replaceMedia(
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

  protected void replaceLogisticsReturnMedia(
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

  protected void validateMediaReferences(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      List<MediaReferenceInput> requested) {
    if (requested == null || requested.isEmpty()) return;
    Set<UUID> unique = new HashSet<>();
    for (MediaReferenceInput reference : requested) {
      if (reference == null || reference.mediaId() == null || reference.generation() == null) {
        throw MaintenanceCommandSupport.invalid("Media reference identity is required");
      }
      if (!unique.add(reference.mediaId())) throw MaintenanceCommandSupport.invalid("Duplicate media reference");
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

  protected void validateLineMediaReferences(
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

  protected void validateUpdatedRepairLineMediaReferences(
      MaintenanceRepair repair, List<EstimateLineResponse> requestedLines) {
    Map<UUID, StoredLineMedia> storedByMediaId = new HashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      java.util.stream.Stream.concat(
              commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
              commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
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
          throw MaintenanceCommandSupport.invalid("A photo already assigned to another work cannot be moved");
        }
      }
    }
    validateMediaReferences(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        newlyAssigned);
  }

  protected static void validateCoverMediaSelection(
      List<MediaReferenceInput> requested, UUID coverMediaId) {
    List<MediaReferenceInput> values = requested == null ? List.of() : requested;
    if (values.isEmpty()) {
      if (coverMediaId != null) {
        throw MaintenanceCommandSupport.invalid("Cover photo must be null when aggregate media is empty");
      }
      return;
    }
    if (coverMediaId == null) {
      throw MaintenanceCommandSupport.invalid("A cover photo must be selected when aggregate media is present");
    }
    if (values.stream().noneMatch(value -> coverMediaId.equals(value.mediaId()))) {
      throw MaintenanceCommandSupport.invalid("Cover photo must reference one of the aggregate media objects");
    }
  }

  protected void enqueueMediaOwnerProof(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      UUID sourceId,
      long sourceVersion,
      boolean active) {
    reconciliations.enqueueMediaOwnerProof(
        ownerType, ownerId, warehouseId, sourceId, sourceVersion, active);
  }

  protected List<MediaReferenceInput> repairMedia(MaintenanceRepair value) {
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
                        commandSupport.read(source.getPlanSnapshot(), FrozenInventoryPlanSnapshot.class)
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

  protected static UUID effectiveCoverMediaId(
      UUID selected, List<MediaReferenceInput> mediaReferences) {
    if (selected != null
        && mediaReferences.stream().anyMatch(value -> selected.equals(value.mediaId()))) {
      return selected;
    }
    return mediaReferences.isEmpty() ? null : mediaReferences.getFirst().mediaId();
  }

  protected Map<UUID, List<MediaReferenceInput>> mediaByAggregateIds(
      String type, Collection<UUID> aggregateIds) {
    if (aggregateIds.isEmpty()) return Map.of();
    return mediaReferences
        .findAllByAggregateTypeAndAggregateIdInOrderByAggregateIdAscMediaId(type, aggregateIds)
        .stream()
        .collect(
            Collectors.groupingBy(
                MaintenanceMediaReference::getAggregateId,
                Collectors.mapping(
                    value -> new MediaReferenceInput(value.getMediaId(), value.getGeneration()),
                    Collectors.toList())));
  }

  protected List<MediaReferenceInput> media(String type, UUID id) {
    return mediaReferences.findAllByAggregateTypeAndAggregateIdOrderByMediaId(type, id).stream()
        .map(value -> new MediaReferenceInput(value.getMediaId(), value.getGeneration()))
        .toList();
  }
}
