package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerOfflineLeaseCodecTest {
  private static final String TEST_SECRET =
      "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  private final WorkerOfflineLeaseCodec codec = new WorkerOfflineLeaseCodec(TEST_SECRET);

  @Test
  void validatesDelayedActionCreatedInsideLease() {
    UUID workerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime issuedAt =
        OffsetDateTime.of(2026, 7, 25, 10, 15, 30, 123_000_000, ZoneOffset.UTC);
    var lease = codec.issue(workerId, warehouseId, 17, issuedAt);

    codec.requireValid(
        lease.id(),
        workerId,
        warehouseId,
        issuedAt.plusHours(23),
        issuedAt.plusDays(2));

    assertThat(lease.issuedAt()).isEqualTo(issuedAt);
    assertThat(lease.expiresAt()).isEqualTo(issuedAt.plusHours(24));
    assertThat(lease.syncRevision()).isEqualTo(17);
    assertThat(lease.id().version()).isEqualTo(7);
    assertThat(lease.id().variant()).isEqualTo(2);
  }

  @Test
  void rejectsAnotherWorkerAndActionOutsideLease() {
    UUID workerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime issuedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
    var lease = codec.issue(workerId, warehouseId, 1, issuedAt);

    assertThatThrownBy(
            () ->
                codec.requireValid(
                    lease.id(),
                    UUID.randomUUID(),
                    warehouseId,
                    issuedAt.plusMinutes(1),
                    OffsetDateTime.now(ZoneOffset.UTC)))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                codec.requireValid(
                    lease.id(),
                    workerId,
                    warehouseId,
                    issuedAt.plusHours(25),
                    OffsetDateTime.now(ZoneOffset.UTC)))
        .isInstanceOf(ConflictException.class);
  }
}
