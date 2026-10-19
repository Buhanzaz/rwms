package dev.buhanzaz.rwms.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsAggregateCheckpointTest {
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 7, 30, 8, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void holdsGapAndClearsItOnlyAfterAllContiguousVersionsAreApplied() {
    AnalyticsAggregateCheckpoint checkpoint =
        AnalyticsAggregateCheckpoint.start(
            "analytics-projection-v1",
            "rwms.task-board.group-kpi-day.v1",
            "GROUP_KPI_DAY",
            UUID.randomUUID(),
            NOW);

    checkpoint.holdGap(2, NOW);
    assertThat(checkpoint.getExpectedVersion()).isZero();
    assertThat(checkpoint.getObservedVersion()).isEqualTo(2);

    checkpoint.apply(0, NOW.plusSeconds(1));
    checkpoint.apply(1, NOW.plusSeconds(2));
    assertThat(checkpoint.isGapOpen()).isTrue();

    checkpoint.apply(2, NOW.plusSeconds(3));
    assertThat(checkpoint.isGapOpen()).isFalse();
    assertThat(checkpoint.getAppliedVersion()).isEqualTo(2);
  }

  @Test
  void countsBoundedGapRecoveryAttemptsAndThenBecomesTerminal() {
    AnalyticsAggregateCheckpoint checkpoint =
        AnalyticsAggregateCheckpoint.start(
            "analytics-projection-v1",
            "rwms.task-board.group-kpi-day.v1",
            "GROUP_KPI_DAY",
            UUID.randomUUID(),
            NOW);
    checkpoint.holdGap(3, NOW);

    assertThat(checkpoint.retryGap(3, NOW.plusMinutes(1))).isFalse();
    assertThat(checkpoint.retryGap(3, NOW.plusMinutes(2))).isFalse();
    assertThat(checkpoint.retryGap(3, NOW.plusMinutes(3))).isTrue();
    assertThat(checkpoint.isTerminallyBlocked()).isTrue();
    assertThatThrownBy(() -> checkpoint.apply(0, NOW.plusMinutes(4)))
        .isInstanceOf(IllegalStateException.class);
  }
}
