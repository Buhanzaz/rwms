package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.DriverShift;
import dev.buhanzaz.rwms.taskboard.domain.DriverShiftPlan;
import dev.buhanzaz.rwms.taskboard.domain.DriverShiftStatus;
import dev.buhanzaz.rwms.taskboard.domain.EndVehicleCondition;
import dev.buhanzaz.rwms.taskboard.domain.ReturnConfirmationType;
import dev.buhanzaz.rwms.taskboard.domain.VehicleConfigurationType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit coverage for the explicit state sequence and warehouse-local 06:00 work-date rule. */
class DriverShiftStateMachineTest {
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-30T04:00:00Z");

  @Test
  void warehouseLocalDayChangesExactlyAtSix() {
    ZoneId zone = ZoneId.of("Europe/Moscow");
    assertThat(
            DriverShiftService.resolveWorkDate(
                OffsetDateTime.parse("2026-08-30T02:59:59Z"), zone, LocalTime.of(6, 0)))
        .isEqualTo(LocalDate.of(2026, 8, 29));
    assertThat(
            DriverShiftService.resolveWorkDate(
                OffsetDateTime.parse("2026-08-30T03:00:00Z"), zone, LocalTime.of(6, 0)))
        .isEqualTo(LocalDate.of(2026, 8, 30));
  }

  @Test
  void preparationAndClosingTransitionsAreOrderedAndAudited() {
    DriverShift shift = shift();
    shift.markBriefingSeen(NOW);
    assertThat(shift.getStatus()).isEqualTo(DriverShiftStatus.MEDICAL_CHECK_REQUIRED);
    shift.confirmMedical(NOW.plusMinutes(2), null);
    shift.completeInspection(NOW.plusMinutes(5));
    shift.start(NOW.plusMinutes(6));
    shift.markTasksComplete(NOW.plusHours(8));
    shift.startClosing(NOW.plusHours(8).plusMinutes(1));
    shift.confirmReturn(ReturnConfirmationType.MANUAL, NOW.plusHours(8).plusMinutes(20));
    shift.submitClosing(
        EndVehicleCondition.NO_NEW_DEFECTS, 10_100, 63, null, NOW.plusHours(8).plusMinutes(25));
    shift.close(NOW.plusHours(8).plusMinutes(30));
    assertThat(shift.getStatus()).isEqualTo(DriverShiftStatus.SHIFT_CLOSED);
    assertThat(shift.getClosedAt()).isEqualTo(NOW.plusHours(8).plusMinutes(30));
    assertThatThrownBy(() -> shift.markBriefingSeen(NOW.plusDays(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shiftCannotStartWithoutMedicalAndInspection() {
    DriverShift shift = shift();
    assertThatThrownBy(() -> shift.start(NOW)).isInstanceOf(IllegalStateException.class);
    shift.markBriefingSeen(NOW);
    assertThatThrownBy(() -> shift.start(NOW)).isInstanceOf(IllegalStateException.class);
    shift.confirmMedical(NOW, null);
    assertThatThrownBy(() -> shift.start(NOW)).isInstanceOf(IllegalStateException.class);
  }

  private DriverShift shift() {
    DriverShiftPlan plan = new DriverShiftPlan();
    plan.assignReviewedId(UUID.randomUUID());
    plan.initialize(UUID.randomUUID(), UUID.randomUUID(), NOW);
    plan.replace(
        1,
        "a".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Driver",
        LocalDate.of(2026, 8, 30),
        UUID.randomUUID(),
        "MAN TGS",
        "A123AA78",
        null,
        "MAN",
        "TGS",
        VehicleConfigurationType.TRUCK,
        10_000L,
        null,
        null,
        null,
        1,
        100,
        NOW);
    DriverShift shift = new DriverShift();
    shift.assignReviewedId(UUID.randomUUID());
    shift.initialize(plan, "Europe/Moscow", NOW);
    return shift;
  }
}
