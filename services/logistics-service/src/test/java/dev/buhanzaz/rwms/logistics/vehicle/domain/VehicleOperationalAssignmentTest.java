package dev.buhanzaz.rwms.logistics.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers mode-specific assignment intervals and lifecycle transitions without persistence. */
class VehicleOperationalAssignmentTest {
  private static final OffsetDateTime DEPARTURE =
      OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime ARRIVAL = DEPARTURE.plusHours(4);

  @Test
  void tripOnlyMovesThroughTransitToCompleted() {
    VehicleOperationalAssignment assignment =
        assignment(VehicleOperationalAssignmentMode.TRIP_ONLY, ARRIVAL);

    assignment.beginTransit(DEPARTURE.plusMinutes(1));
    assignment.arrive(ARRIVAL);

    assertThat(assignment.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.COMPLETED);
    assertThat(assignment.isArrivalApplied()).isTrue();
  }

  @Test
  void repositionMovesThroughTransitToActive() {
    VehicleOperationalAssignment assignment =
        assignment(VehicleOperationalAssignmentMode.TEMPORARY, ARRIVAL.plusDays(2));

    assignment.beginTransit(DEPARTURE.plusMinutes(1));
    assignment.arrive(ARRIVAL);

    assertThat(assignment.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.ACTIVE);
    assertThat(assignment.isArrivalApplied()).isTrue();
  }

  @Test
  void onlyPlannedAssignmentCanBeCancelled() {
    VehicleOperationalAssignment planned =
        assignment(VehicleOperationalAssignmentMode.PERMANENT, null);
    planned.cancelBeforeStart(DEPARTURE.plusMinutes(1));
    planned.cancelBeforeStart(DEPARTURE.plusMinutes(2));

    assertThat(planned.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.CANCELLED);

    VehicleOperationalAssignment travelling =
        assignment(VehicleOperationalAssignmentMode.PERMANENT, null);
    travelling.beginTransit(DEPARTURE.plusMinutes(1));
    assertThatThrownBy(() -> travelling.cancelBeforeStart(DEPARTURE.plusMinutes(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validatesModeSpecificIntervals() {
    assertThatThrownBy(
            () -> assignment(VehicleOperationalAssignmentMode.TRIP_ONLY, ARRIVAL.plusMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("end at effectiveFrom");
    assertThatThrownBy(
            () -> assignment(VehicleOperationalAssignmentMode.TEMPORARY, ARRIVAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Temporary assignment end");
    assertThatThrownBy(
            () -> assignment(VehicleOperationalAssignmentMode.PERMANENT, ARRIVAL.plusDays(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Permanent assignment cannot have an end");
  }

  @Test
  void activePermanentPlacementCompletesAtItsSuccessorEffectiveTime() {
    UUID destination = UUID.randomUUID();
    VehicleOperationalAssignment assignment =
        VehicleOperationalAssignment.planned(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            destination,
            VehicleOperationalAssignmentMode.PERMANENT,
            DEPARTURE,
            ARRIVAL,
            null,
            DEPARTURE);
    assignment.beginTransit(DEPARTURE.plusMinutes(1));
    assignment.arrive(ARRIVAL);
    OffsetDateTime successorEffectiveFrom = ARRIVAL.plusDays(2);

    assignment.completePlacement(successorEffectiveFrom, ARRIVAL.plusDays(1));

    assertThat(assignment.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.COMPLETED);
    assertThat(assignment.getEffectiveUntil()).isEqualTo(successorEffectiveFrom);
    assertThat(assignment.isArrivalApplied()).isTrue();
  }

  private static VehicleOperationalAssignment assignment(
      VehicleOperationalAssignmentMode mode, OffsetDateTime effectiveUntil) {
    return VehicleOperationalAssignment.planned(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        mode,
        DEPARTURE,
        ARRIVAL,
        effectiveUntil,
        DEPARTURE);
  }
}
