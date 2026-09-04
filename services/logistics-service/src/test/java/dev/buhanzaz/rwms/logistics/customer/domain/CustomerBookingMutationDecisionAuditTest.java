package dev.buhanzaz.rwms.logistics.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins structured dispatcher decision audit on an atomically completed reschedule. */
class CustomerBookingMutationDecisionAuditTest {
  @Test
  void completedRescheduleRetainsBothFencesAndHumanDecision() {
    UUID actor = UUID.randomUUID();
    CustomerBookingMutation mutation =
        CustomerBookingMutation.completedReschedule(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64),
            4,
            7,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "CUSTOMER_AGREED_ALTERNATIVE",
            actor,
            "Клиент согласовал другой доступный день",
            "{\"orderId\":\"00000000-0000-0000-0000-000000000001\"}",
            OffsetDateTime.parse("2026-09-01T09:00:00Z"));

    assertThat(mutation.getState()).isEqualTo(CustomerBookingMutationState.COMPLETED);
    assertThat(mutation.getExpectedSessionVersion()).isEqualTo(4);
    assertThat(mutation.getExpectedOrderVersion()).isEqualTo(7);
    assertThat(mutation.getDecisionCode()).isEqualTo("CUSTOMER_AGREED_ALTERNATIVE");
    assertThat(mutation.getDecisionActorSubjectId()).isEqualTo(actor);
    assertThat(mutation.getDecisionReason()).isEqualTo("Клиент согласовал другой доступный день");
    assertThat(mutation.getRescheduleResultJson()).contains("orderId");
  }
}
