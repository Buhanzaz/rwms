package dev.buhanzaz.rwms.platform.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RwmsEventDeliveryPolicyTest {

    private final RwmsEventDeliveryPolicy policy = new RwmsEventDeliveryPolicy();

    @Test
    void retriesOnlyTransientFailuresAtTheApprovedDelaysThenDeadLetters() {
        assertThat(policy.afterFailure(RwmsEventDeliveryPolicy.FailureCategory.TRANSIENT_INFRASTRUCTURE, 1))
                .isEqualTo(RwmsEventDeliveryPolicy.FailureDecision.retryAfter(Duration.ofSeconds(1)));
        assertThat(policy.afterFailure(RwmsEventDeliveryPolicy.FailureCategory.TRANSIENT_INFRASTRUCTURE, 2))
                .isEqualTo(RwmsEventDeliveryPolicy.FailureDecision.retryAfter(Duration.ofSeconds(2)));
        assertThat(policy.afterFailure(RwmsEventDeliveryPolicy.FailureCategory.TRANSIENT_INFRASTRUCTURE, 3))
                .isEqualTo(RwmsEventDeliveryPolicy.FailureDecision.retryAfter(Duration.ofSeconds(4)));
        assertThat(policy.afterFailure(RwmsEventDeliveryPolicy.FailureCategory.TRANSIENT_INFRASTRUCTURE, 4))
                .isEqualTo(RwmsEventDeliveryPolicy.FailureDecision.deadLetter());

        assertThat(RwmsEventDeliveryPolicy.TOTAL_DELIVERY_ATTEMPTS).isEqualTo(4);
        assertThat(RwmsEventDeliveryPolicy.RETRY_BACKOFFS)
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void validationFailuresAreNeverRetried() {
        for (int attempt = 1; attempt <= RwmsEventDeliveryPolicy.TOTAL_DELIVERY_ATTEMPTS; attempt++) {
            assertThat(policy.afterFailure(RwmsEventDeliveryPolicy.FailureCategory.VALIDATION, attempt))
                    .isEqualTo(RwmsEventDeliveryPolicy.FailureDecision.deadLetter());
        }
    }

    @Test
    void buildsOnlyTheExactConsumerOwnedDltName() {
        assertThat(policy.deadLetterTopic("rwms.task-board.queue-entry.v1", "dossier-service"))
                .isEqualTo("rwms.task-board.queue-entry.v1.dossier-service.dlt");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.deadLetterTopic(
                        "rwms.task-board.queue-entry.created.v1", "dossier-service"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.deadLetterTopic("rwms.task-board.queue-entry.v1", "Dossier Service"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.deadLetterTopic("rwms.task-board.queue-entry.v1", "dossier.service"));
    }

    @Test
    void rejectsInvalidAttemptNumbersAndUnsupportedFailureClasses() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.afterFailure(
                        RwmsEventDeliveryPolicy.FailureCategory.TRANSIENT_INFRASTRUCTURE, 0));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> policy.afterFailure(
                        RwmsEventDeliveryPolicy.FailureCategory.TRANSIENT_INFRASTRUCTURE, 5));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> policy.afterFailure(null, 1));
    }
}
