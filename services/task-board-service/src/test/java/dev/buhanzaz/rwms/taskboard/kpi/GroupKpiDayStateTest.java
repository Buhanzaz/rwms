package dev.buhanzaz.rwms.taskboard.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.GroupKpiDayState;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GroupKpiDayStateTest {
  @Test
  void completedSegmentsCreditOnlyPositiveRemainingBudget() {
    GroupKpiDayState state = state();

    state.addCompletedSegment(3600, 900);
    state.addCompletedSegment(600, 600);
    state.addCompletedSegment(300, 900);

    assertThat(state.getCompletedBudgetSeconds()).isEqualTo(4500);
    assertThat(state.getEarnedRemainingSeconds()).isEqualTo(2700);
    assertThat(state.getCompletedTaskCount()).isEqualTo(3);
  }

  @Test
  void returnedSegmentCanRemoveOnlyItsProvisionallyAllocatedWork() {
    GroupKpiDayState state = state();
    state.addActiveSeconds(900);

    state.subtractActiveSeconds(600);

    assertThat(state.getActiveSeconds()).isEqualTo(300);
    assertThatThrownBy(() -> state.subtractActiveSeconds(301))
        .isInstanceOf(IllegalStateException.class);
  }

  private static GroupKpiDayState state() {
    return GroupKpiDayState.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 7, 30),
        LocalDate.of(2026, 7, 1),
        OffsetDateTime.parse("2026-07-30T06:00:00Z"));
  }
}
