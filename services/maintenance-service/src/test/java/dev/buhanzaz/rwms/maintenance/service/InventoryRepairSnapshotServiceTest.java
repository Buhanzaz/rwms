package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceOperationRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InventoryRepairSnapshotServiceTest {
  private static final UUID ASSET_A =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID ASSET_B =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID ASSET_EMPTY =
      UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID REPAIR_A1 =
      UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID REPAIR_A2 =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID REPAIR_B =
      UUID.fromString("20000000-0000-0000-0000-000000000003");
  private static final UUID QUEUE =
      UUID.fromString("30000000-0000-0000-0000-000000000001");

  private final MaintenanceRepairRepository repairs = mock(MaintenanceRepairRepository.class);
  private final RepairStageRepository stages = mock(RepairStageRepository.class);
  private InventoryMaintenanceService service;

  @BeforeEach
  void createService() {
    service = new InventoryMaintenanceService(
        mock(CatalogVersionRepository.class),
        mock(CatalogNodeRepository.class),
        mock(CatalogLinkRepository.class),
        mock(InventoryRepairSourceOperationRepository.class),
        mock(InventoryRepairSourceRepository.class),
        mock(RentalItemFactProjectionRepository.class),
        mock(MediaFactProjectionRepository.class),
        repairs,
        stages,
        mock(MaintenanceEventStore.class),
        mock(MaintenanceEventFactFactory.class),
        mock(MaintenanceProjectionSnapshotFactory.class),
        mock(InventoryRepairReconciliationWriter.class),
        mock(MaintenanceReconciliationStore.class),
        mock(InventoryRepairSourceOperationRegistrar.class),
        mock(MaintenanceDependencyGateway.class),
        mock(MaintenanceJsonbCanonicalizer.class),
        JsonMapper.builder().findAndAddModules().build());
  }

  @Test
  void returnsEveryRequestedAssetAndSortsAssetsAndRepairs() {
    MaintenanceRepair a1 = repair(
        REPAIR_A1, ASSET_A, null, RepairExecutionState.DRAFT);
    MaintenanceRepair a2 = repair(
        REPAIR_A2, ASSET_A, REPAIR_A1, RepairExecutionState.QUEUED);
    MaintenanceRepair b = repair(
        REPAIR_B, ASSET_B, null, RepairExecutionState.COMPLETED);
    when(repairs.findAllByRentalItemIdInOrderByRentalItemIdAscIdAsc(
        List.of(ASSET_A, ASSET_B, ASSET_EMPTY)))
        .thenReturn(List.of(b, a2, a1));
    when(stages.findAllByRepairIdInOrderByRepairIdAscStageNoAscIdAsc(
        List.of(REPAIR_A1, REPAIR_A2, REPAIR_B)))
        .thenReturn(List.of());

    InventoryRepairSnapshotsResponse response = service.repairSnapshots(
        new InventoryRepairSnapshotRequest(List.of(ASSET_EMPTY, ASSET_B, ASSET_A)));

    assertThat(response.assets()).extracting(InventoryRepairSnapshot::assetId)
        .containsExactly(ASSET_A, ASSET_B, ASSET_EMPTY);
    assertThat(response.assets().getFirst().repairs())
        .extracting(InventoryRepairFact::repairId)
        .containsExactly(REPAIR_A1, REPAIR_A2);
    assertThat(response.assets().getFirst().repairs().getFirst().rootRepairId())
        .isEqualTo(REPAIR_A1);
    assertThat(response.assets().getFirst().repairs().get(1).rootRepairId())
        .isEqualTo(REPAIR_A1);
    assertThat(response.assets().getLast().repairs()).isEmpty();
  }

  @Test
  void fingerprintsOnlyStableSemanticPlanData() {
    AtomicReference<RepairExecutionState> execution =
        new AtomicReference<>(RepairExecutionState.DRAFT);
    MaintenanceRepair repair = repair(
        REPAIR_A1, ASSET_A, null, execution);
    AtomicReference<String> workLines =
        new AtomicReference<>("[{\"description\":\"Покраска\",\"quantity\":\"2\"}]");
    AtomicReference<String> groupComment = new AtomicReference<>("Первая группа");
    RepairStage stage = stage(workLines, groupComment);
    when(repairs.findAllByRentalItemIdInOrderByRentalItemIdAscIdAsc(List.of(ASSET_A)))
        .thenReturn(List.of(repair));
    when(stages.findAllByRepairIdInOrderByRepairIdAscStageNoAscIdAsc(List.of(REPAIR_A1)))
        .thenReturn(List.of(stage));

    InventoryRepairFact initial = onlyFact();
    execution.set(RepairExecutionState.QUEUED);
    workLines.set("[ { \"quantity\" : \"2\", \"description\" : \"Покраска\" } ]");
    InventoryRepairFact technicalStateChanged = onlyFact();
    groupComment.set("Вторая группа");
    InventoryRepairFact planChanged = onlyFact();

    assertThat(technicalStateChanged.executionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(technicalStateChanged.planFingerprintSha256())
        .isEqualTo(initial.planFingerprintSha256());
    assertThat(planChanged.planFingerprintSha256())
        .isNotEqualTo(initial.planFingerprintSha256())
        .matches("^[0-9a-f]{64}$");
  }

  @Test
  void duplicateAssetIdentityIsRejected() {
    assertThatThrownBy(
        () -> new InventoryRepairSnapshotRequest(List.of(ASSET_A, ASSET_A)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(() -> new InventoryRepairSnapshotRequest(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 to 5000");
    assertThatThrownBy(() -> new InventoryRepairSnapshotRequest(
        java.util.stream.LongStream.rangeClosed(1, 5001)
            .mapToObj(value -> new UUID(0, value))
            .toList()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 to 5000");
  }

  private InventoryRepairFact onlyFact() {
    return service.repairSnapshots(new InventoryRepairSnapshotRequest(List.of(ASSET_A)))
        .assets().getFirst().repairs().getFirst();
  }

  private static MaintenanceRepair repair(
      UUID repairId,
      UUID assetId,
      UUID rootRepairId,
      RepairExecutionState executionState) {
    AtomicReference<RepairExecutionState> execution = new AtomicReference<>(executionState);
    return repair(repairId, assetId, rootRepairId, execution);
  }

  private static MaintenanceRepair repair(
      UUID repairId,
      UUID assetId,
      UUID rootRepairId,
      AtomicReference<RepairExecutionState> executionState) {
    MaintenanceRepair value = mock(MaintenanceRepair.class);
    when(value.getId()).thenReturn(repairId);
    when(value.getRentalItemId()).thenReturn(assetId);
    when(value.getRootRepairId()).thenReturn(rootRepairId);
    when(value.getOrigin()).thenReturn(RepairOrigin.DIRECT_REPAIR);
    when(value.getKind()).thenReturn(rootRepairId == null ? RepairKind.PRIMARY : RepairKind.REWORK);
    when(value.getExecutionState()).thenAnswer(ignored -> executionState.get());
    when(value.getAcceptanceState()).thenReturn(RepairAcceptanceState.NOT_READY);
    return value;
  }

  private static RepairStage stage(
      AtomicReference<String> workLines,
      AtomicReference<String> groupComment) {
    RepairStage value = mock(RepairStage.class);
    when(value.getId()).thenReturn(
        UUID.fromString("40000000-0000-0000-0000-000000000001"));
    when(value.getRepairId()).thenReturn(REPAIR_A1);
    when(value.getStageNo()).thenReturn(0);
    when(value.getStageKind()).thenReturn(RepairStageKind.REPAIR_WORK);
    when(value.getRoutingQueueId()).thenReturn(QUEUE);
    when(value.getRoutingQueueName()).thenReturn("Repair");
    when(value.getRoutingQueueType()).thenReturn("REPAIR");
    when(value.getWorkLines()).thenAnswer(ignored -> workLines.get());
    when(value.getMaterialLines()).thenReturn("[]");
    when(value.getGroupComment()).thenAnswer(ignored -> groupComment.get());
    return value;
  }
}
