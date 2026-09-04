package dev.buhanzaz.rwms.logistics.contractor.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShare;
import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShare.TaskBinding;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the immutable membership, expiry and monotonic revocation invariants of one share. */
class ContractorRouteShareDomainTest {
  @Test
  void createsOrderedExactMembershipAndRevokesOneTokenRevision() {
    OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    TaskBinding first = binding();
    TaskBinding second = binding();
    ContractorRouteShare share =
        ContractorRouteShare.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64),
            createdAt.plusDays(1),
            List.of(first, second),
            createdAt);

    assertThat(share.getTokenRevision()).isOne();
    assertThat(share.getTasks())
        .extracting(task -> task.getExternalTaskId())
        .containsExactly(first.externalTaskId(), second.externalTaskId());
    assertThat(share.isAvailable(1, createdAt.plusHours(1))).isTrue();
    assertThat(share.isAvailable(2, createdAt.plusHours(1))).isFalse();

    share.revoke(createdAt.plusHours(2));
    share.revoke(createdAt.plusHours(3));

    assertThat(share.getTokenRevision()).isEqualTo(2);
    assertThat(share.getRevokedAt()).isEqualTo(createdAt.plusHours(2));
    assertThat(share.isAvailable(1, createdAt.plusHours(2))).isFalse();
    assertThat(share.isAvailable(2, createdAt.plusHours(2))).isFalse();
  }

  @Test
  void rejectsDuplicateOrOversizedTaskMembership() {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    TaskBinding duplicate = binding();

    assertThatThrownBy(
            () ->
                ContractorRouteShare.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "b".repeat(64),
                    createdAt.plusHours(1),
                    List.of(duplicate, duplicate),
                    createdAt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(
            () ->
                ContractorRouteShare.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "b".repeat(64),
                    createdAt.plusHours(1),
                    java.util.stream.IntStream.rangeClosed(0, 50)
                        .mapToObj(ignored -> binding())
                        .toList(),
                    createdAt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("count");
  }

  @Test
  void expiresAtTheExactBoundaryAndMatchesOnlyTheOriginalHash() {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    ContractorRouteShare share =
        ContractorRouteShare.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "c".repeat(64),
            createdAt.plusMinutes(10),
            List.of(binding()),
            createdAt);

    assertThat(share.isAvailable(1, createdAt.plusMinutes(10).minusNanos(1))).isTrue();
    assertThat(share.isAvailable(1, createdAt.plusMinutes(10))).isFalse();
    assertThat(share.matchesRequest("c".repeat(64))).isTrue();
    assertThat(share.matchesRequest("d".repeat(64))).isFalse();
  }

  private static TaskBinding binding() {
    return new TaskBinding(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }
}
