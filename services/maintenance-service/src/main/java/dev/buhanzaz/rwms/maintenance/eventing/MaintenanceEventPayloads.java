package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Exact sanitized Kafka facts. Service-local text and media references never enter these records. */
public final class MaintenanceEventPayloads {
  public sealed interface MaintenanceIntegrationFact
      permits CatalogVersionFact, EstimateFact, RepairFact {}

  public record CatalogVersionFact(
      UUID catalogVersionId,
      UUID warehouseId,
      CatalogVersionState lifecycle,
      String sourceSha256,
      int nodeCount,
      int linkCount,
      String validationReportSha256)
      implements MaintenanceIntegrationFact {
    public CatalogVersionFact {
      Objects.requireNonNull(catalogVersionId, "catalogVersionId is required");
      Objects.requireNonNull(warehouseId, "warehouseId is required");
      Objects.requireNonNull(lifecycle, "lifecycle is required");
      requireSha256(sourceSha256, "sourceSha256");
      requireSha256(validationReportSha256, "validationReportSha256");
      if (nodeCount < 0 || linkCount < 0) {
        throw new IllegalArgumentException("Catalog counts must not be negative");
      }
    }
  }

  public enum CompletionKind {
    NOT_COMPLETED,
    EMPTY,
    NON_EMPTY
  }

  public record EstimateFact(
      UUID estimateId,
      UUID warehouseId,
      UUID rentalItemId,
      EstimateState lifecycle,
      int revision,
      LocalDate dispatchDate,
      int lineCount,
      CompletionKind completionKind,
      UUID repairId)
      implements MaintenanceIntegrationFact {
    public EstimateFact {
      Objects.requireNonNull(estimateId, "estimateId is required");
      Objects.requireNonNull(warehouseId, "warehouseId is required");
      Objects.requireNonNull(rentalItemId, "rentalItemId is required");
      Objects.requireNonNull(lifecycle, "lifecycle is required");
      Objects.requireNonNull(dispatchDate, "dispatchDate is required");
      Objects.requireNonNull(completionKind, "completionKind is required");
      if (revision < 1 || lineCount < 0) {
        throw new IllegalArgumentException("Estimate revision and line count are invalid");
      }
      if (lifecycle == EstimateState.DRAFT
          && (completionKind != CompletionKind.NOT_COMPLETED || repairId != null)) {
        throw new IllegalArgumentException("A draft estimate cannot have a completion outcome");
      }
      if (lifecycle == EstimateState.COMPLETED
          && completionKind == CompletionKind.NOT_COMPLETED) {
        throw new IllegalArgumentException("A completed estimate requires a completion outcome");
      }
      if (completionKind == CompletionKind.EMPTY && (lineCount != 0 || repairId != null)) {
        throw new IllegalArgumentException("An empty estimate cannot reference a repair");
      }
      if (completionKind == CompletionKind.NON_EMPTY && (lineCount == 0 || repairId == null)) {
        throw new IllegalArgumentException("A non-empty estimate must reference its repair");
      }
    }
  }

  public enum GenerationState {
    PENDING_GENERATION,
    GENERATED,
    NOT_REQUIRED,
    FAILED
  }

  public enum DeliveryState {
    PENDING,
    RETRY_PENDING,
    DELIVERED,
    QUARANTINED
  }

  public record TaskSyncFact(
      UUID externalTaskId,
      Long taskBoardRegistrationVersion,
      GenerationState generationState,
      DeliveryState deliveryState) {
    public TaskSyncFact {
      Objects.requireNonNull(externalTaskId, "externalTaskId is required");
      Objects.requireNonNull(generationState, "generationState is required");
      Objects.requireNonNull(deliveryState, "deliveryState is required");
      if (taskBoardRegistrationVersion != null && taskBoardRegistrationVersion < 0) {
        throw new IllegalArgumentException("taskBoardRegistrationVersion must not be negative");
      }
    }
  }

  public record RepairStageFact(
      UUID stageId,
      RepairStageKind kind,
      int order,
      RepairStageState state,
      UUID queueId,
      TaskSyncFact taskSync) {
    public RepairStageFact {
      Objects.requireNonNull(stageId, "stageId is required");
      Objects.requireNonNull(kind, "kind is required");
      Objects.requireNonNull(state, "state is required");
      Objects.requireNonNull(queueId, "queueId is required");
      Objects.requireNonNull(taskSync, "taskSync is required");
      if (order < 0) {
        throw new IllegalArgumentException("Repair stage order must not be negative");
      }
    }
  }

  public record RepairFact(
      UUID repairId,
      UUID rootRepairId,
      UUID sourceRepairId,
      UUID estimateId,
      UUID warehouseId,
      UUID rentalItemId,
      RepairOrigin origin,
      RepairKind kind,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      LocalDate dispatchDate,
      int priority,
      List<RepairStageFact> stages)
      implements MaintenanceIntegrationFact {
    public RepairFact {
      Objects.requireNonNull(repairId, "repairId is required");
      Objects.requireNonNull(rootRepairId, "rootRepairId is required");
      Objects.requireNonNull(warehouseId, "warehouseId is required");
      Objects.requireNonNull(rentalItemId, "rentalItemId is required");
      Objects.requireNonNull(origin, "origin is required");
      Objects.requireNonNull(kind, "kind is required");
      Objects.requireNonNull(executionState, "executionState is required");
      Objects.requireNonNull(acceptanceState, "acceptanceState is required");
      Objects.requireNonNull(dispatchDate, "dispatchDate is required");
      Objects.requireNonNull(stages, "stages are required");
      if (priority < 1 || priority > 5) {
        throw new IllegalArgumentException("Repair priority must be between 1 and 5");
      }
      stages = stages.stream()
          .sorted(Comparator.comparingInt(RepairStageFact::order).thenComparing(RepairStageFact::stageId))
          .toList();
      Set<UUID> stageIds = new HashSet<>();
      Set<Integer> stageOrders = new HashSet<>();
      for (RepairStageFact stage : stages) {
        if (!stageIds.add(stage.stageId()) || !stageOrders.add(stage.order())) {
          throw new IllegalArgumentException("Repair stage IDs and orders must be unique");
        }
      }
      if (kind == RepairKind.PRIMARY
          && (!repairId.equals(rootRepairId) || sourceRepairId != null)) {
        throw new IllegalArgumentException("A primary repair must be its own root");
      }
      if (kind == RepairKind.REWORK
          && (sourceRepairId == null || repairId.equals(rootRepairId))) {
        throw new IllegalArgumentException("A rework must reference its source and root repair");
      }
    }
  }

  private static void requireSha256(String value, String name) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
    }
  }

  private MaintenanceEventPayloads() {}
}
