package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.EstimateLineResponse;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ReworkLineDisposition;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairTaskEvidence;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairTaskEvidenceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves result photos of repeated source stages as general WorkerApp input media for rework.
 *
 * <p>The resolver reads maintenance's task-evidence projection in batches and never creates result
 * evidence for the child repair.
 */
@Service
final class MaintenanceReworkSourceMediaResolver {
  private final RepairStageRepository stages;
  private final RepairTaskEvidenceRepository evidence;
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceCommandSupport commandSupport;

  MaintenanceReworkSourceMediaResolver(
      RepairStageRepository stages,
      RepairTaskEvidenceRepository evidence,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceCommandSupport commandSupport) {
    this.stages = stages;
    this.evidence = evidence;
    this.mediaFacts = mediaFacts;
    this.commandSupport = commandSupport;
  }

  /**
   * Returns source-stage result media keyed by the child stage that repeats one of its lines.
   * Media order follows the source evidence projection and duplicates are removed by media ID.
   */
  Map<UUID, List<MaintenanceDependencyGateway.TaskSourceMedia>> resolve(
      MaintenanceRepair repair, List<RepairStage> childStages) {
    if (repair.getKind() != RepairKind.REWORK) {
      return Map.of();
    }

    Map<UUID, List<ReworkSourceLine>> sourcesByChildStage = new LinkedHashMap<>();
    Set<UUID> sourceRepairIds = new LinkedHashSet<>();
    for (RepairStage childStage : childStages) {
      List<ReworkSourceLine> sources = repeatedSources(childStage);
      if (!sources.isEmpty()) {
        sourcesByChildStage.put(childStage.getId(), sources);
        sources.forEach(source -> sourceRepairIds.add(source.repairId()));
      }
    }
    if (sourcesByChildStage.isEmpty()) {
      return Map.of();
    }

    Map<UUID, Map<UUID, UUID>> sourceStageByLine = new HashMap<>();
    Map<UUID, Map<UUID, List<RepairTaskEvidence>>> sourceEvidenceByStage = new HashMap<>();
    List<RepairTaskEvidence> inheritedEvidence = new ArrayList<>();
    for (UUID sourceRepairId : sourceRepairIds) {
      sourceStageByLine.put(sourceRepairId, indexSourceStages(sourceRepairId));
      Map<UUID, List<RepairTaskEvidence>> evidenceByStage = new LinkedHashMap<>();
      List<RepairTaskEvidence> repairEvidence =
          evidence.findAllByRepairIdOrderByRecordedAtAscEvidenceIdAsc(sourceRepairId);
      inheritedEvidence.addAll(repairEvidence);
      repairEvidence.forEach(
          item ->
              evidenceByStage
                  .computeIfAbsent(item.getRepairStageId(), ignored -> new ArrayList<>())
                  .add(item));
      sourceEvidenceByStage.put(sourceRepairId, evidenceByStage);
    }
    Map<UUID, MediaFactProjection> inheritedMediaFacts = mediaFacts(inheritedEvidence);

    Map<UUID, List<MaintenanceDependencyGateway.TaskSourceMedia>> result =
        new LinkedHashMap<>();
    sourcesByChildStage.forEach(
        (childStageId, sources) ->
            result.put(
                childStageId,
                sourceMedia(
                    sources,
                    sourceStageByLine,
                    sourceEvidenceByStage,
                    inheritedMediaFacts)));
    return Map.copyOf(result);
  }

  private List<ReworkSourceLine> repeatedSources(RepairStage childStage) {
    return java.util.stream.Stream.concat(
            commandSupport
                .readList(childStage.getWorkLines(), EstimateLineResponse.class)
                .stream(),
            commandSupport
                .readList(childStage.getMaterialLines(), EstimateLineResponse.class)
                .stream())
        .filter(line -> line.disposition() == ReworkLineDisposition.REPEAT)
        .map(
            line -> {
              if (line.sourceRepairId() == null || line.sourceLineId() == null) {
                throw new IllegalStateException("Repeated rework line source identity is missing");
              }
              return new ReworkSourceLine(line.sourceRepairId(), line.sourceLineId());
            })
        .distinct()
        .toList();
  }

  private Map<UUID, UUID> indexSourceStages(UUID sourceRepairId) {
    Map<UUID, UUID> stageByLine = new HashMap<>();
    for (RepairStage sourceStage : stages.findAllByRepairIdOrderByStageNo(sourceRepairId)) {
      java.util.stream.Stream.concat(
              commandSupport
                  .readList(sourceStage.getWorkLines(), EstimateLineResponse.class)
                  .stream(),
              commandSupport
                  .readList(sourceStage.getMaterialLines(), EstimateLineResponse.class)
                  .stream())
          .forEach(
              line -> {
                UUID previous = stageByLine.putIfAbsent(line.id(), sourceStage.getId());
                if (previous != null && !previous.equals(sourceStage.getId())) {
                  throw new IllegalStateException(
                      "Source repair line belongs to more than one stage");
                }
              });
    }
    return stageByLine;
  }

  private Map<UUID, MediaFactProjection> mediaFacts(List<RepairTaskEvidence> inheritedEvidence) {
    Map<UUID, MediaFactProjection> result = new HashMap<>();
    mediaFacts
        .findAllById(
            inheritedEvidence.stream()
                .map(RepairTaskEvidence::getMediaId)
                .distinct()
                .toList())
        .forEach(fact -> result.put(fact.getMediaId(), fact));
    return result;
  }

  private List<MaintenanceDependencyGateway.TaskSourceMedia> sourceMedia(
      List<ReworkSourceLine> sources,
      Map<UUID, Map<UUID, UUID>> sourceStageByLine,
      Map<UUID, Map<UUID, List<RepairTaskEvidence>>> sourceEvidenceByStage,
      Map<UUID, MediaFactProjection> inheritedMediaFacts) {
    LinkedHashMap<UUID, MaintenanceDependencyGateway.TaskSourceMedia> media =
        new LinkedHashMap<>();
    for (ReworkSourceLine source : sources) {
      UUID sourceStageId =
          sourceStageByLine.getOrDefault(source.repairId(), Map.of()).get(source.lineId());
      if (sourceStageId == null) {
        throw new IllegalStateException("Repeated rework line source stage is missing");
      }
      sourceEvidenceByStage
          .getOrDefault(source.repairId(), Map.of())
          .getOrDefault(sourceStageId, List.of())
          .forEach(
              item ->
                  media.putIfAbsent(
                      item.getMediaId(),
                      sourceMedia(item, inheritedMediaFacts.get(item.getMediaId()))));
    }
    return List.copyOf(media.values());
  }

  private MaintenanceDependencyGateway.TaskSourceMedia sourceMedia(
      RepairTaskEvidence evidence, MediaFactProjection fact) {
    String contentType = "application/octet-stream";
    if (fact != null) {
      Object storedContentType = commandSupport.jsonMap(fact.getSafeMetadata()).get("contentType");
      if (storedContentType instanceof String value && !value.isBlank()) {
        contentType = value;
      }
    }
    return new MaintenanceDependencyGateway.TaskSourceMedia(
        evidence.getMediaId(),
        evidence.getMediaGeneration(),
        contentType,
        evidence.getCapturedAt(),
        evidence.getRecordedAt());
  }

  /** Identifies one canonical line in the source repair chain of a rework. */
  private record ReworkSourceLine(UUID repairId, UUID lineId) {}
}
