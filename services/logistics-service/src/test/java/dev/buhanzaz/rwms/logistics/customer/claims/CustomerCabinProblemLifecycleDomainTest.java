package dev.buhanzaz.rwms.logistics.customer.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemCategory;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemPhase;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinProblem;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers the local claim lifecycle invariants layered over immutable customer evidence. */
class CustomerCabinProblemLifecycleDomainTest {

  @Test
  void createsOpenClaimWithAnExactThreeDayResolutionDeadline() {
    OffsetDateTime reportedAt = OffsetDateTime.parse("2026-09-02T08:15:30.123456Z");

    CustomerCabinProblem problem = problem(reportedAt);

    assertThat(problem.getStatus()).isEqualTo(CustomerCabinProblemStatus.OPEN);
    assertThat(problem.getResolutionDeadline()).isEqualTo(reportedAt.plusDays(3));
    assertThat(problem.getResolutionKind()).isNull();
    assertThat(problem.getResolvedBySubjectId()).isNull();
    assertThat(problem.getResolutionComment()).isNull();
    assertThat(problem.getResolvedAt()).isNull();
  }

  @Test
  void requiresTheExpectedVersionAndAnInProgressClaimBeforeResolution() {
    CustomerCabinProblem problem = problem(OffsetDateTime.parse("2026-09-02T08:15:30Z"));

    assertThatThrownBy(() -> problem.startProgress(1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(
            () ->
                problem.resolve(
                    0,
                    CustomerCabinProblemResolutionKind.DISCOUNT,
                    UUID.randomUUID(),
                    "Согласована скидка",
                    OffsetDateTime.parse("2026-09-02T09:00:00Z")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not in progress");

    assertThat(problem.startProgress(0)).isEqualTo(CustomerCabinProblemStatus.OPEN);
    assertThat(problem.getStatus()).isEqualTo(CustomerCabinProblemStatus.IN_PROGRESS);
    assertThat(
            problem.resolve(
                0,
                CustomerCabinProblemResolutionKind.REPLACEMENT,
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                "  Замена согласована  ",
                OffsetDateTime.parse("2026-09-02T09:00:00Z")))
        .isEqualTo(CustomerCabinProblemStatus.IN_PROGRESS);
    assertThat(problem.getStatus()).isEqualTo(CustomerCabinProblemStatus.RESOLVED);
    assertThat(problem.getResolutionKind())
        .isEqualTo(CustomerCabinProblemResolutionKind.REPLACEMENT);
    assertThat(problem.getResolutionComment()).isEqualTo("Замена согласована");
    assertThatThrownBy(() -> problem.startProgress(0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not open");
  }

  private static CustomerCabinProblem problem(OffsetDateTime reportedAt) {
    return CustomerCabinProblem.create(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        UUID.fromString("00000000-0000-0000-0000-000000000003"),
        UUID.fromString("00000000-0000-0000-0000-000000000004"),
        UUID.fromString("00000000-0000-0000-0000-000000000005"),
        UUID.fromString("00000000-0000-0000-0000-000000000006"),
        UUID.fromString("00000000-0000-0000-0000-000000000007"),
        UUID.fromString("00000000-0000-0000-0000-000000000008"),
        UUID.fromString("00000000-0000-0000-0000-000000000009"),
        CustomerCabinProblemCategory.OTHER,
        CustomerCabinProblemPhase.BEFORE_ACCEPTANCE,
        "Нужна проверка дефекта",
        "[]",
        UUID.fromString("00000000-0000-0000-0000-000000000010"),
        "a".repeat(64),
        reportedAt);
  }
}
