package dev.buhanzaz.rwms.logistics.planning.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.planning.api.VehicleOperationalAssignmentPlanningApiModels.PlanningVehicleOperationalAssignment;
import dev.buhanzaz.rwms.logistics.planning.api.VehicleOperationalAssignmentPlanningApiModels.PlanningVehicleOperationalAssignmentMode;
import dev.buhanzaz.rwms.logistics.planning.api.VehicleOperationalAssignmentPlanningApiModels.PlanningVehicleOperationalAssignmentStatus;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentMode;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentSnapshot;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Verifies the frozen field-for-field planner mapping, including distinct transport enums. */
class VehicleOperationalAssignmentPlanningMapperTest {
  private final VehicleOperationalAssignmentPlanningMapper mapper =
      Mappers.getMapper(VehicleOperationalAssignmentPlanningMapper.class);

  @Test
  void mapsEveryFrozenPlanningField() {
    UUID assignmentId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    UUID vehicleId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    OffsetDateTime travelStartsAt =
        OffsetDateTime.of(2026, 9, 2, 8, 0, 0, 0, ZoneOffset.UTC);
    OffsetDateTime effectiveFrom = travelStartsAt.plusHours(4);
    OffsetDateTime effectiveUntil = effectiveFrom.plusDays(2);
    OffsetDateTime createdAt = travelStartsAt.minusDays(1);
    OffsetDateTime updatedAt = createdAt.plusMinutes(1);
    VehicleOperationalAssignmentSnapshot snapshot =
        new VehicleOperationalAssignmentSnapshot(
            assignmentId,
            7,
            transferId,
            vehicleId,
            sourceWarehouseId,
            destinationWarehouseId,
            VehicleOperationalAssignmentMode.TEMPORARY,
            VehicleOperationalAssignmentStatus.ACTIVE,
            travelStartsAt,
            effectiveFrom,
            effectiveUntil,
            createdAt,
            updatedAt);

    assertThat(mapper.toResponse(snapshot))
        .isEqualTo(
            new PlanningVehicleOperationalAssignment(
                assignmentId,
                7,
                transferId,
                vehicleId,
                sourceWarehouseId,
                destinationWarehouseId,
                PlanningVehicleOperationalAssignmentMode.TEMPORARY,
                PlanningVehicleOperationalAssignmentStatus.ACTIVE,
                travelStartsAt,
                effectiveFrom,
                effectiveUntil,
                createdAt,
                updatedAt));
  }
}
