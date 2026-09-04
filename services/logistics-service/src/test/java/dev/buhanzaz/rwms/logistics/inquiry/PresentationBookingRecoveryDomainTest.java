package dev.buhanzaz.rwms.logistics.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the bounded lease, backoff, quarantine and terminal-cleanup domain invariants. */
final class PresentationBookingRecoveryDomainTest {

  @Test
  void retryBackoffDoublesAndTheEighthFailureQuarantinesThePendingBooking() {
    OffsetDateTime timestamp = OffsetDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZoneOffset.UTC);
    PresentationBooking booking = booking(timestamp);

    for (int attempt = 1; attempt <= 8; attempt++) {
      UUID leaseToken = UUID.randomUUID();
      booking.claimRecovery(leaseToken, timestamp.plusMinutes(5), timestamp);
      OffsetDateTime failedAt = timestamp.plusSeconds(1);
      booking.recoveryFailed(leaseToken, "unsafe-error-" + "x".repeat(100), failedAt);

      assertThat(booking.getAttemptCount()).isEqualTo(attempt);
      assertThat(booking.getLastErrorCode()).hasSize(64).startsWith("unsafe-error-");
      assertThat(booking.getRecoveryLeaseToken()).isNull();
      assertThat(booking.getRecoveryLeaseUntil()).isNull();
      if (attempt < 8) {
        long expectedDelay = Math.min(2L << (attempt - 1), 300L);
        assertThat(booking.getRecoveryNextAttemptAt())
            .isEqualTo(failedAt.plusSeconds(expectedDelay));
        assertThat(booking.getRecoveryQuarantinedAt()).isNull();
        timestamp = booking.getRecoveryNextAttemptAt();
      } else {
        assertThat(booking.getRecoveryNextAttemptAt()).isNull();
        assertThat(booking.getRecoveryQuarantinedAt()).isEqualTo(failedAt);
        assertThatThrownBy(
                () ->
                    booking.claimRecovery(
                        UUID.randomUUID(), failedAt.plusMinutes(6), failedAt.plusMinutes(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not claimable");
      }
    }
  }

  @Test
  void anExpiredLeaseCanBeReclaimedAndTheOldCapabilityCannotMutateIt() {
    OffsetDateTime timestamp = OffsetDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZoneOffset.UTC);
    PresentationBooking booking = booking(timestamp);
    UUID expired = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    booking.claimRecovery(expired, timestamp.plusMinutes(5), timestamp);

    assertThatThrownBy(
            () ->
                booking.claimRecovery(
                    current, timestamp.plusMinutes(6), timestamp.plusMinutes(1)))
        .isInstanceOf(IllegalStateException.class);

    OffsetDateTime reclaimedAt = timestamp.plusMinutes(5);
    booking.claimRecovery(current, reclaimedAt.plusMinutes(5), reclaimedAt);
    assertThatThrownBy(
            () -> booking.recoveryFailed(expired, "STALE", reclaimedAt.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer current");
    booking.recoveryFailed(current, "TRANSIENT", reclaimedAt.plusSeconds(1));
    assertThat(booking.getAttemptCount()).isOne();
  }

  @Test
  void terminalResultsClearEveryRecoveryField() {
    OffsetDateTime timestamp = OffsetDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZoneOffset.UTC);
    PresentationBooking completed = booking(timestamp);
    UUID completionLease = UUID.randomUUID();
    completed.claimRecovery(completionLease, timestamp.plusMinutes(5), timestamp);
    completed.assignOrder(UUID.randomUUID(), completionLease, timestamp.plusSeconds(1));
    completed.complete(completionLease, timestamp.plusSeconds(2));

    assertThat(completed.getState()).isEqualTo(PresentationBookingState.COMPLETED);
    assertRecoveryMetadataCleared(completed);

    PresentationBooking rejected = booking(timestamp);
    UUID rejectionLease = UUID.randomUUID();
    rejected.claimRecovery(rejectionLease, timestamp.plusMinutes(5), timestamp);
    rejected.reject(rejectionLease, "PERMANENT", timestamp.plusSeconds(1));

    assertThat(rejected.getState()).isEqualTo(PresentationBookingState.REJECTED);
    assertThat(rejected.getLastErrorCode()).isEqualTo("PERMANENT");
    assertRecoveryMetadataCleared(rejected);
  }

  private static PresentationBooking booking(OffsetDateTime timestamp) {
    return PresentationBooking.create(
        UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48"),
        1,
        UUID.randomUUID(),
        "[\"00000000-0000-4000-8000-000000000001\"]",
        "[]",
        null,
        null,
        null,
        null,
        null,
        timestamp);
  }

  private static void assertRecoveryMetadataCleared(PresentationBooking booking) {
    assertThat(booking.getRecoveryNextAttemptAt()).isNull();
    assertThat(booking.getRecoveryLeaseToken()).isNull();
    assertThat(booking.getRecoveryLeaseUntil()).isNull();
    assertThat(booking.getRecoveryQuarantinedAt()).isNull();
  }
}
