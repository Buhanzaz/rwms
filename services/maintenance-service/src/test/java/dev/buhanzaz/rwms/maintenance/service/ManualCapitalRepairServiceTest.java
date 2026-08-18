package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexityColors;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexitySettings;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairTaskEvidenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies manual capital-repair classification and estimate-to-repair propagation. */
class ManualCapitalRepairServiceTest {
  private static final UUID WAREHOUSE_ID = UUID.randomUUID();

  @Test
  void explicitChoiceForcesCapitalIndependentlyOfCatalogWorkFlags() {
    RepairComplexitySettingsService settings = mock(RepairComplexitySettingsService.class);
    RepairComplexityColorsService colors = mock(RepairComplexityColorsService.class);
    when(settings.requireSettings(WAREHOUSE_ID))
        .thenReturn(RepairComplexitySettings.create(WAREHOUSE_ID, 60, 180, 360));
    when(colors.requireColors()).thenReturn(RepairComplexityColors.defaults());
    MaintenanceRepairModelSupport support =
        new MaintenanceRepairModelSupport(
            mock(MaintenanceRepairRepository.class),
            mock(InventoryRepairSourceRepository.class),
            mock(RepairStageRepository.class),
            mock(RepairTaskEvidenceRepository.class),
            mock(RentalItemFactProjectionRepository.class),
            mock(MaintenanceEventStore.class),
            settings,
            colors,
            mock(MaintenanceCommandSupport.class),
            mock(MaintenanceMediaSupport.class));

    var ordinary = support.repairComplexityForLines(WAREHOUSE_ID, List.of(), false);
    var forced = support.repairComplexityForLines(WAREHOUSE_ID, List.of(), true);
    MaintenanceRepair repair =
        MaintenanceRepair.primary(
            WAREHOUSE_ID,
            UUID.randomUUID(),
            0,
            null,
            RepairOrigin.DIRECT_REPAIR,
            LocalDate.of(2026, 8, 17),
            null,
            "{}");
    repair.selectForceCapitalRepair(true);
    var forcedWithoutMovement = support.repairComplexity(repair, List.of());

    assertThat(ordinary.type()).isEqualTo(RepairComplexity.LIGHT);
    assertThat(ordinary.forcedCapital()).isFalse();
    assertThat(forced.type()).isEqualTo(RepairComplexity.CAPITAL);
    assertThat(forced.forcedCapital()).isTrue();
    assertThat(repair.isMovementToRepair()).isFalse();
    assertThat(forcedWithoutMovement.type()).isEqualTo(RepairComplexity.CAPITAL);
    assertThat(forcedWithoutMovement.forcedCapital()).isTrue();
  }

  @Test
  void estimateChoiceIsCopiedToRepairAndRemainsUpdatableBeforeStart() {
    MaintenanceEstimate estimate =
        MaintenanceEstimate.create(
            WAREHOUSE_ID,
            UUID.randomUUID(),
            4,
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 17),
            null,
            null,
            "{}");
    estimate.selectForceCapitalRepair(true);

    MaintenanceRepairRepository repairs = mock(MaintenanceRepairRepository.class);
    MaintenanceEstimateSupport estimateSupport = mock(MaintenanceEstimateSupport.class);
    MaintenanceEstimateModelSupport estimateModelSupport =
        mock(MaintenanceEstimateModelSupport.class);
    MaintenanceCommandSupport commandSupport = mock(MaintenanceCommandSupport.class);
    when(commandSupport.actorJson()).thenReturn("{}");
    when(repairs.saveAndFlush(any(MaintenanceRepair.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(estimateSupport.resolvePlanContent(anyList(), anyList())).thenReturn(List.of());
    when(estimateModelSupport.lineResponses(any(), anyInt())).thenReturn(List.of());
    MaintenanceEstimateRevisionSupport support =
        new MaintenanceEstimateRevisionSupport(
            mock(EstimateLineRepository.class),
            mock(EstimatePlanStageRepository.class),
            mock(EstimateRevisionRepository.class),
            repairs,
            mock(RepairStageRepository.class),
            mock(MaintenanceEventStore.class),
            commandSupport,
            estimateModelSupport,
            mock(MaintenanceEventPayloadSupport.class),
            mock(MaintenanceMediaSupport.class),
            estimateSupport);

    MaintenanceRepair repair = support.createEstimateRepairFromPlan(estimate, List.of());

    assertThat(repair.isForceCapitalRepair()).isTrue();
    assertThat(repair.selectForceCapitalRepair(false)).isTrue();
    assertThat(repair.isForceCapitalRepair()).isFalse();
  }
}
