package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.CatalogVersionFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.CompletionKind;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.DeliveryState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.EstimateFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.GenerationState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.MaintenanceIntegrationFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.PropertyDispositionFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.RepairFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.RepairStageFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.TaskSyncFact;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetEffectState;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class MaintenanceEventContractFixtures {
  static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
  static final UUID RENTAL_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000602");
  static final UUID CATALOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000603");
  static final UUID ESTIMATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000604");
  static final UUID REPAIR_ID = UUID.fromString("00000000-0000-0000-0000-000000000605");
  static final UUID ROOT_REPAIR_ID = UUID.fromString("00000000-0000-0000-0000-000000000606");
  static final UUID SOURCE_REPAIR_ID = UUID.fromString("00000000-0000-0000-0000-000000000607");
  static final UUID STAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000608");
  static final UUID QUEUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000609");
  static final UUID EXTERNAL_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000610");
  static final UUID DISPOSITION_ID = UUID.fromString("00000000-0000-0000-0000-000000000611");
  static final UUID DISPOSITION_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000612");
  static final UUID MOVEMENT_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000613");
  static final UUID EFFECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000614");

  static FactCase fact(MaintenanceEventType eventType) {
    return switch (eventType) {
      case CATALOG_IMPORTED, CATALOG_CHANGED -> new FactCase(
          MaintenanceAggregateType.CATALOG_VERSION,
          CATALOG_ID,
          catalog(CatalogVersionState.DRAFT));
      case CATALOG_ACTIVATED -> new FactCase(
          MaintenanceAggregateType.CATALOG_VERSION,
          CATALOG_ID,
          catalog(CatalogVersionState.ACTIVE));
      case CATALOG_SUPERSEDED -> new FactCase(
          MaintenanceAggregateType.CATALOG_VERSION,
          CATALOG_ID,
          catalog(CatalogVersionState.SUPERSEDED));
      case ESTIMATE_CREATED, ESTIMATE_DRAFT_CHANGED -> new FactCase(
          MaintenanceAggregateType.ESTIMATE,
          ESTIMATE_ID,
          estimate(EstimateState.DRAFT, CompletionKind.NOT_COMPLETED, 2, null));
      case ESTIMATE_COMPLETED, ESTIMATE_AMENDED -> new FactCase(
          MaintenanceAggregateType.ESTIMATE,
          ESTIMATE_ID,
          estimate(EstimateState.COMPLETED, CompletionKind.NON_EMPTY, 2, REPAIR_ID));
      case REPAIR_CREATED, REPAIR_PLAN_CHANGED -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          primaryRepair(RepairExecutionState.DRAFT, RepairAcceptanceState.NOT_READY));
      case REPAIR_QUEUED -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          primaryRepair(RepairExecutionState.QUEUED, RepairAcceptanceState.NOT_READY));
      case REPAIR_STAGE_COMPLETED -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          primaryRepair(RepairExecutionState.IN_PROGRESS, RepairAcceptanceState.NOT_READY));
      case REPAIR_PENDING_ACCEPTANCE -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          primaryRepair(RepairExecutionState.COMPLETED, RepairAcceptanceState.PENDING));
      case REPAIR_REWORK_CREATED -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          reworkRepair());
      case REPAIR_TRANSFER_PREPARED, REPAIR_TRANSFERRED -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          primaryRepair(RepairExecutionState.QUEUED, RepairAcceptanceState.NOT_READY));
      case REPAIR_ACCEPTED -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          primaryRepair(RepairExecutionState.COMPLETED, RepairAcceptanceState.ACCEPTED));
      case REPAIR_WRITTEN_OFF -> new FactCase(
          MaintenanceAggregateType.REPAIR,
          REPAIR_ID,
          primaryRepair(RepairExecutionState.COMPLETED, RepairAcceptanceState.WRITTEN_OFF));
      case PROPERTY_DISPOSITION_REQUESTED -> disposition(
          PropertyDispositionState.PENDING_APPROVAL,
          PropertyDispositionAssetEffectState.NOT_STARTED,
          null,
          null,
          0);
      case PROPERTY_DISPOSITION_APPROVED -> disposition(
          PropertyDispositionState.APPROVED,
          PropertyDispositionAssetEffectState.NOT_STARTED,
          null,
          null,
          0);
      case PROPERTY_DISPOSITION_REJECTED -> disposition(
          PropertyDispositionState.REJECTED,
          PropertyDispositionAssetEffectState.NOT_STARTED,
          null,
          null,
          0);
      case PROPERTY_DISPOSITION_MOVEMENT_PENDING -> disposition(
          PropertyDispositionState.MOVEMENT_PENDING,
          PropertyDispositionAssetEffectState.NOT_STARTED,
          MOVEMENT_TASK_ID,
          null,
          0);
      case PROPERTY_DISPOSITION_EFFECT_PENDING -> disposition(
          PropertyDispositionState.EFFECT_PENDING,
          PropertyDispositionAssetEffectState.PENDING,
          null,
          null,
          0);
      case PROPERTY_DISPOSITION_EFFECTIVE -> disposition(
          PropertyDispositionState.EFFECTIVE,
          PropertyDispositionAssetEffectState.APPLIED,
          null,
          EFFECT_ID,
          0);
      case PROPERTY_DISPOSITION_QUARANTINED -> disposition(
          PropertyDispositionState.QUARANTINED,
          PropertyDispositionAssetEffectState.QUARANTINED,
          null,
          null,
          0);
      case PROPERTY_DISPOSITION_RECOVERED -> disposition(
          PropertyDispositionState.APPROVED,
          PropertyDispositionAssetEffectState.NOT_STARTED,
          null,
          null,
          1);
    };
  }

  private static FactCase disposition(
      PropertyDispositionState state,
      PropertyDispositionAssetEffectState effectState,
      UUID movementTaskId,
      UUID effectId,
      long recoveryVersion) {
    return new FactCase(
        MaintenanceAggregateType.PROPERTY_DISPOSITION,
        DISPOSITION_ID,
        new PropertyDispositionFact(
            DISPOSITION_ID,
            WAREHOUSE_ID,
            PropertyDispositionAssetKind.CABIN,
            DISPOSITION_ASSET_ID,
            PropertyDispositionKind.WRITE_OFF,
            PropertyDispositionSource.MANUAL,
            state,
            effectState,
            null,
            null,
            null,
            null,
            movementTaskId,
            effectId,
            recoveryVersion));
  }

  static CatalogVersionFact catalog(CatalogVersionState lifecycle) {
    return new CatalogVersionFact(
        CATALOG_ID,
        WAREHOUSE_ID,
        lifecycle,
        "1".repeat(64),
        232,
        254,
        "2".repeat(64));
  }

  static EstimateFact estimate(
      EstimateState lifecycle,
      CompletionKind completionKind,
      int lineCount,
      UUID repairId) {
    return new EstimateFact(
        ESTIMATE_ID,
        WAREHOUSE_ID,
        RENTAL_ITEM_ID,
        lifecycle,
        1,
        LocalDate.of(2026, 7, 17),
        lineCount,
        completionKind,
        repairId);
  }

  private static RepairFact primaryRepair(
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState) {
    return new RepairFact(
        REPAIR_ID,
        REPAIR_ID,
        null,
        ESTIMATE_ID,
        WAREHOUSE_ID,
        RENTAL_ITEM_ID,
        RepairOrigin.ESTIMATE,
        RepairKind.PRIMARY,
        executionState,
        acceptanceState,
        LocalDate.of(2026, 7, 17),
        3,
        List.of(stage(executionState)));
  }

  private static RepairFact reworkRepair() {
    return new RepairFact(
        REPAIR_ID,
        ROOT_REPAIR_ID,
        SOURCE_REPAIR_ID,
        null,
        WAREHOUSE_ID,
        RENTAL_ITEM_ID,
        RepairOrigin.ESTIMATE,
        RepairKind.REWORK,
        RepairExecutionState.DRAFT,
        RepairAcceptanceState.NOT_READY,
        LocalDate.of(2026, 7, 17),
        3,
        List.of(stage(RepairExecutionState.DRAFT)));
  }

  private static RepairStageFact stage(RepairExecutionState repairState) {
    RepairStageState stageState = switch (repairState) {
      case DRAFT -> RepairStageState.PLANNED;
      case QUEUED -> RepairStageState.QUEUED;
      case IN_PROGRESS -> RepairStageState.DONE;
      case COMPLETED -> RepairStageState.DONE;
      case CANCELLED -> RepairStageState.CANCELLED;
    };
    GenerationState generation = repairState == RepairExecutionState.DRAFT
        ? GenerationState.PENDING_GENERATION
        : GenerationState.GENERATED;
    DeliveryState delivery = repairState == RepairExecutionState.DRAFT
        ? DeliveryState.PENDING
        : DeliveryState.DELIVERED;
    return new RepairStageFact(
        STAGE_ID,
        RepairStageKind.REPAIR_WORK,
        0,
        stageState,
        QUEUE_ID,
        new TaskSyncFact(
            EXTERNAL_TASK_ID,
            generation == GenerationState.GENERATED ? 3L : null,
            generation,
            delivery));
  }

  record FactCase(
      MaintenanceAggregateType aggregateType,
      UUID aggregateId,
      MaintenanceIntegrationFact payload) {}

  private MaintenanceEventContractFixtures() {}
}
