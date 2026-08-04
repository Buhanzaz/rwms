package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceProjectionAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairCapacitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocation;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.mapper.RepairPlaceAllocationResponseMapper;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairPlaceAllocationRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class RepairPlaceServiceTest {
  private final RepairPlaceAllocationRepository allocations =
      mock(RepairPlaceAllocationRepository.class);
  private final MaintenanceRepairRepository repairs = mock(MaintenanceRepairRepository.class);
  private final RepairStageRepository repairStages = mock(RepairStageRepository.class);
  private final RepairCapacitySettingsService capacity = mock(RepairCapacitySettingsService.class);
  private final RepairPlaceAllocationResponseMapper responseMapper =
      mock(RepairPlaceAllocationResponseMapper.class);
  private final RepairPlaceService service =
      new RepairPlaceService(
          allocations,
          repairs,
          repairStages,
          capacity,
          mock(MaintenanceIdempotencyStore.class),
          responseMapper,
          mock(JdbcTemplate.class),
          new ObjectMapper());

  @Test
  void completionWithoutRepairMovementReleasesOccupiedPlaceImmediately() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    RepairPlaceAllocation allocation = occupied(warehouseId, repairId);
    when(allocations.findByRepairIdForUpdate(repairId)).thenReturn(Optional.of(allocation));

    service.completeAfterRepair(warehouseId, repairId, false);

    assertThat(allocation.getState()).isEqualTo(RepairPlaceAllocationState.RELEASED);
    verify(allocations).save(allocation);
  }

  @Test
  void completionAfterRepairMovementLeavesPlaceReadyForAutomaticRemoval() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    RepairPlaceAllocation allocation = occupied(warehouseId, repairId);
    when(allocations.findByRepairIdForUpdate(repairId)).thenReturn(Optional.of(allocation));

    service.completeAfterRepair(warehouseId, repairId, true);

    assertThat(allocation.getState()).isEqualTo(RepairPlaceAllocationState.READY_TO_RELEASE);
    verify(allocations).save(allocation);
  }

  @Test
  void inventoryReplacementRequiresTheExactCompensatedOccupiedAllocation() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID predecessorId = UUID.randomUUID();
    UUID successorId = UUID.randomUUID();
    MaintenanceRepair predecessor = repair(warehouseId, rentalItemId, predecessorId);
    MaintenanceRepair successor = repair(warehouseId, rentalItemId, successorId);
    RepairPlaceAllocation allocation = occupied(warehouseId, predecessorId);
    UUID allocationId = UUID.randomUUID();
    ReflectionTestUtils.setField(allocation, "id", allocationId);
    ReflectionTestUtils.setField(allocation, "version", 7L);
    when(repairs.findByIdAndWarehouseId(predecessorId, warehouseId))
        .thenReturn(Optional.of(predecessor));
    when(repairs.findByIdAndWarehouseId(successorId, warehouseId))
        .thenReturn(Optional.of(successor));
    when(allocations.findByRepairIdForUpdate(successorId)).thenReturn(Optional.empty());
    when(allocations.findByRepairIdForUpdate(predecessorId)).thenReturn(Optional.of(allocation));

    assertThatThrownBy(() -> service.reassignOccupiedForInventoryReplacement(
        warehouseId, predecessorId, successorId, UUID.randomUUID(), 7L))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("no longer matches logistics compensation truth");
    assertThat(allocation.getRepairId()).isEqualTo(predecessorId);

    service.reassignOccupiedForInventoryReplacement(
        warehouseId, predecessorId, successorId, allocationId, 7L);
    assertThat(allocation.getRepairId()).isEqualTo(successorId);
    assertThat(allocation.getState()).isEqualTo(RepairPlaceAllocationState.OCCUPIED);
    verify(allocations).saveAndFlush(allocation);
  }

  @Test
  void releasedHistoricalAllocationDoesNotBlockNoAllocationReplacementTruth() {
    UUID warehouseId = UUID.randomUUID();
    UUID predecessorId = UUID.randomUUID();
    RepairPlaceAllocation allocation = occupied(warehouseId, predecessorId);
    allocation.readyToRelease();
    allocation.release();
    when(allocations.findByRepairIdForUpdate(predecessorId)).thenReturn(Optional.of(allocation));

    service.requireNoActiveAllocationForInventoryReplacement(
        warehouseId, predecessorId, null, null);
  }

  @Test
  void logisticsProjectionIncludesTheCurrentRepairStageForAnOccupiedPlace() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    RepairPlaceAllocation allocation = occupied(warehouseId, repairId);
    MaintenanceRepair repair = repair(warehouseId, rentalItemId, repairId);
    RepairStage stage =
        new RepairStage(
            UUID.randomUUID(),
            repairId,
            0,
            RepairStageKind.REPAIR_WORK,
            UUID.randomUUID(),
            "Электрика",
            "ELECTRICAL",
            null);
    stage.queued();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsRepairPlaceProjectionAllocationResponse expected =
        new LogisticsRepairPlaceProjectionAllocationResponse(
            UUID.randomUUID(),
            2,
            warehouseId,
            repairId,
            rentalItemId,
            RepairPlaceAllocationState.OCCUPIED,
            "Электрика",
            RepairStageState.QUEUED,
            repair.getPriority(),
            now,
            now);
    when(capacity.get(warehouseId))
        .thenReturn(new RepairCapacitySettingsResponse(warehouseId, 1, 6, 5, now, now));
    when(allocations.findAllByWarehouseIdAndStateInOrderByCreatedAtAscIdAsc(
            warehouseId,
            List.of(
                RepairPlaceAllocationState.RESERVED,
                RepairPlaceAllocationState.OCCUPIED,
                RepairPlaceAllocationState.READY_TO_RELEASE)))
        .thenReturn(List.of(allocation));
    when(repairs.findAllById(List.of(repairId))).thenReturn(List.of(repair));
    when(repairStages.findAllByRepairIdInOrderByRepairIdAscStageNoAscIdAsc(any()))
        .thenReturn(List.of(stage));
    when(
            responseMapper.toLogisticsProjectionResponse(
                allocation,
                rentalItemId,
                "Электрика",
                RepairStageState.QUEUED,
                repair.getPriority()))
        .thenReturn(expected);

    var projection = service.logisticsProjection(warehouseId);

    assertThat(projection.occupiedCount()).isEqualTo(1);
    assertThat(projection.allocations())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.rentalItemId()).isEqualTo(rentalItemId);
              assertThat(value.repairStageName()).isEqualTo("Электрика");
              assertThat(value.repairStageState()).isEqualTo(RepairStageState.QUEUED);
            });
  }

  private static RepairPlaceAllocation occupied(UUID warehouseId, UUID repairId) {
    RepairPlaceAllocation allocation = RepairPlaceAllocation.reserve(warehouseId, repairId);
    allocation.occupy();
    return allocation;
  }

  private static MaintenanceRepair repair(UUID warehouseId, UUID rentalItemId, UUID repairId) {
    MaintenanceRepair repair = MaintenanceRepair.primary(
        warehouseId,
        rentalItemId,
        1L,
        null,
        RepairOrigin.DIRECT_REPAIR,
        LocalDate.of(2026, 8, 4),
        "Repair-place test",
        "{}");
    ReflectionTestUtils.setField(repair, "id", repairId);
    return repair;
  }
}
