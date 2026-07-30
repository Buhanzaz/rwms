package dev.buhanzaz.rwms.maintenance.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceEventFactFactoryTest {
  private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final MaintenanceEventPayloadPolicy policy = new MaintenanceEventPayloadPolicy(mapper);
  private final MaintenanceEventFactFactory factory = new MaintenanceEventFactFactory(mapper, policy);

  @Test
  void catalogAdoptionApiProducesOnlyTheExactCatalogFact() {
    CatalogVersion catalog = mock(CatalogVersion.class);
    when(catalog.getId()).thenReturn(MaintenanceEventContractFixtures.CATALOG_ID);
    when(catalog.getWarehouseId()).thenReturn(MaintenanceEventContractFixtures.WAREHOUSE_ID);
    when(catalog.getState()).thenReturn(CatalogVersionState.ACTIVE);
    when(catalog.getSourceSha256()).thenReturn("1".repeat(64));
    when(catalog.getNodeCount()).thenReturn(232);
    when(catalog.getLinkCount()).thenReturn(254);
    when(catalog.getValidationReport()).thenReturn("{\"valid\":true}");

    assertThat(factory.catalogPayload(MaintenanceEventType.CATALOG_ACTIVATED, catalog))
        .containsOnlyKeys(
            "catalogVersionId",
            "warehouseId",
            "lifecycle",
            "sourceSha256",
            "nodeCount",
            "linkCount",
            "validationReportSha256")
        .doesNotContainKeys("version", "state", "validationReport");
  }

  @Test
  void estimateAdoptionApiDerivesCompletionKindWithoutLocalText() {
    MaintenanceEstimate estimate = mock(MaintenanceEstimate.class);
    when(estimate.getId()).thenReturn(MaintenanceEventContractFixtures.ESTIMATE_ID);
    when(estimate.getWarehouseId()).thenReturn(MaintenanceEventContractFixtures.WAREHOUSE_ID);
    when(estimate.getRentalItemId()).thenReturn(MaintenanceEventContractFixtures.RENTAL_ITEM_ID);
    when(estimate.getState()).thenReturn(EstimateState.COMPLETED);
    when(estimate.getRevision()).thenReturn(2);
    when(estimate.getDispatchDate()).thenReturn(LocalDate.of(2026, 7, 17));
    when(estimate.getRepairId()).thenReturn(MaintenanceEventContractFixtures.REPAIR_ID);

    assertThat(factory.estimatePayload(MaintenanceEventType.ESTIMATE_AMENDED, estimate, 3))
        .containsEntry("completionKind", "NON_EMPTY")
        .containsEntry("repairId", MaintenanceEventContractFixtures.REPAIR_ID.toString())
        .doesNotContainKeys("sourceParty", "comment", "catalogVersionId", "version");
  }

  @Test
  void repairAdoptionApiBuildsDeterministicallyOrderedSanitizedStageFacts() {
    MaintenanceRepair repair = mock(MaintenanceRepair.class);
    when(repair.getId()).thenReturn(MaintenanceEventContractFixtures.REPAIR_ID);
    when(repair.getRootRepairId()).thenReturn(null);
    when(repair.getSourceRepairId()).thenReturn(null);
    when(repair.getEstimateId()).thenReturn(MaintenanceEventContractFixtures.ESTIMATE_ID);
    when(repair.getWarehouseId()).thenReturn(MaintenanceEventContractFixtures.WAREHOUSE_ID);
    when(repair.getRentalItemId()).thenReturn(MaintenanceEventContractFixtures.RENTAL_ITEM_ID);
    when(repair.getOrigin()).thenReturn(RepairOrigin.ESTIMATE);
    when(repair.getKind()).thenReturn(RepairKind.PRIMARY);
    when(repair.getExecutionState()).thenReturn(RepairExecutionState.QUEUED);
    when(repair.getAcceptanceState()).thenReturn(RepairAcceptanceState.NOT_READY);
    when(repair.getDispatchDate()).thenReturn(LocalDate.of(2026, 7, 17));
    when(repair.getPriority()).thenReturn(1);
    when(repair.getExternalTaskId()).thenReturn(MaintenanceEventContractFixtures.EXTERNAL_TASK_ID);
    RepairStage later = stage(2, "00000000-0000-0000-0000-000000000612");
    RepairStage first = stage(0, "00000000-0000-0000-0000-000000000611");

    var payload = factory.repairPayload(
        MaintenanceEventType.REPAIR_QUEUED, repair, List.of(later, first));

    assertThat(payload)
        .containsOnlyKeys(
            "repairId",
            "rootRepairId",
            "sourceRepairId",
            "estimateId",
            "warehouseId",
            "rentalItemId",
            "origin",
            "kind",
            "executionState",
            "acceptanceState",
            "dispatchDate",
            "priority",
            "stages")
        .containsEntry("priority", 1)
        .doesNotContainKeys(
            "sourceParty", "reworkReason", "decisionReason", "leaseId", "objectPath");
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> stages =
        (List<java.util.Map<String, Object>>) payload.get("stages");
    assertThat(stages).extracting(stage -> stage.get("order")).containsExactly(0, 2);
  }

  private RepairStage stage(int order, String id) {
    RepairStage stage = mock(RepairStage.class);
    when(stage.getId()).thenReturn(java.util.UUID.fromString(id));
    when(stage.getStageKind()).thenReturn(RepairStageKind.REPAIR_WORK);
    when(stage.getStageNo()).thenReturn(order);
    when(stage.getState()).thenReturn(RepairStageState.QUEUED);
    when(stage.getRoutingQueueId()).thenReturn(MaintenanceEventContractFixtures.QUEUE_ID);
    when(stage.getTaskBoardVersion()).thenReturn(3L);
    when(stage.getTaskGenerationState()).thenReturn("GENERATED");
    when(stage.getDeliveryState()).thenReturn("DELIVERED");
    return stage;
  }
}
