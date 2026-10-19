package dev.buhanzaz.rwms.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationLeaseTest {
  private static final OffsetDateTime ACQUIRED_AT =
      OffsetDateTime.of(2026, 7, 16, 10, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void acquireAndRenewPreserveIdentityOwnerAndFence() {
    UUID rentalItemId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    OperationLease lease = OperationLease.acquire(
        rentalItemId,
        "MAINTENANCE_REPAIR",
        UUID.randomUUID().toString(),
        7,
        idempotencyKey,
        ACQUIRED_AT,
        ACQUIRED_AT.plusMinutes(15));

    lease.renew(ACQUIRED_AT.plusMinutes(5), ACQUIRED_AT.plusMinutes(20));

    assertThat(lease.getRentalItemId()).isEqualTo(rentalItemId);
    assertThat(lease.getIdempotencyKey()).isEqualTo(idempotencyKey);
    assertThat(lease.getFencingToken()).isEqualTo(7);
    assertThat(lease.getState()).isEqualTo(OperationLeaseState.ACTIVE);
    assertThat(lease.getExpiresAt()).isEqualTo(ACQUIRED_AT.plusMinutes(20));
    assertThat(lease.getReleasedAt()).isNull();
  }

  @Test
  void releaseIsAnInvariantSpecificTerminalTransition() {
    OperationLease lease = leaseExpiringAt(ACQUIRED_AT.plusMinutes(15));

    lease.release(ACQUIRED_AT.plusMinutes(1));

    assertThat(lease.getState()).isEqualTo(OperationLeaseState.RELEASED);
    assertThat(lease.getReleasedAt()).isEqualTo(ACQUIRED_AT.plusMinutes(1));
    assertThatThrownBy(() -> lease.renew(ACQUIRED_AT.plusMinutes(2), ACQUIRED_AT.plusMinutes(17)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale or fenced");
  }

  @Test
  void expiryOccursOnlyAtOrAfterTheDeadlineAndCannotBeRenewed() {
    OffsetDateTime deadline = ACQUIRED_AT.plusMinutes(15);
    OperationLease lease = leaseExpiringAt(deadline);

    assertThat(lease.expire(deadline.minusNanos(1))).isFalse();
    assertThat(lease.expire(deadline)).isTrue();
    assertThat(lease.getState()).isEqualTo(OperationLeaseState.EXPIRED);
    assertThat(lease.getReleasedAt()).isEqualTo(deadline);
    assertThat(lease.expire(deadline.plusMinutes(1))).isFalse();
    assertThatThrownBy(() -> lease.renew(deadline.plusMinutes(1), deadline.plusMinutes(15)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale or fenced");
  }

  @Test
  void rejectsInvalidV1BusinessColumns() {
    assertThatThrownBy(() -> OperationLease.acquire(
            UUID.randomUUID(), "invalid-owner", "owner", 1, UUID.randomUUID(),
            ACQUIRED_AT, ACQUIRED_AT.plusMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ownerType");
    assertThatThrownBy(() -> OperationLease.acquire(
            UUID.randomUUID(), "MAINTENANCE", " ", 1, UUID.randomUUID(),
            ACQUIRED_AT, ACQUIRED_AT.plusMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ownerId");
    assertThatThrownBy(() -> OperationLease.acquire(
            UUID.randomUUID(), "MAINTENANCE", "owner", 0, UUID.randomUUID(),
            ACQUIRED_AT, ACQUIRED_AT.plusMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fencingToken");
  }

  private OperationLease leaseExpiringAt(OffsetDateTime expiresAt) {
    return OperationLease.acquire(
        UUID.randomUUID(),
        "MAINTENANCE_REPAIR",
        UUID.randomUUID().toString(),
        1,
        UUID.randomUUID(),
        ACQUIRED_AT,
        expiresAt);
  }
}
