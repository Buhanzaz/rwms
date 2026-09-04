package dev.buhanzaz.rwms.logistics.retention.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Covers durable legal-hold and immutable archive-checksum fences. */
class LogisticsRetentionDomainTest {
  @Test
  void legalHoldPreservesItsOriginalAuditWhenReleased() {
    UUID placer = UUID.randomUUID();
    UUID releaser = UUID.randomUUID();
    LogisticsRetentionLegalHold hold =
        LogisticsRetentionLegalHold.place(
            LogisticsRetentionDataset.EVENT_OUTBOX, "order:42", "Судебный запрос", placer);

    hold.release(0, releaser, "Официальное снятие ограничения");

    assertThat(hold.getReason()).isEqualTo("Судебный запрос");
    assertThat(hold.getPlacedBySubjectId()).isEqualTo(placer);
    assertThat(hold.getReleasedBySubjectId()).isEqualTo(releaser);
    assertThat(hold.getReleasedAt()).isNotNull();
    assertThatThrownBy(() -> hold.release(0, releaser, "Повтор"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void archiveManifestRequiresAValidRangeAndIndependentVersionFence() {
    UUID creator = UUID.randomUUID();
    LogisticsArchiveManifest manifest =
        LogisticsArchiveManifest.record(
            LogisticsRetentionDataset.EVENT_INBOX,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            OffsetDateTime.parse("2026-02-01T00:00:00Z"),
            "private/logistics/inbox-2026-01.ndjson.enc",
            "a".repeat(64),
            420,
            creator);
    ReflectionTestUtils.setField(manifest, "version", 3L);

    assertThatThrownBy(() -> manifest.verify(2, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("version");

    UUID verifier = UUID.randomUUID();
    manifest.verify(3, verifier);
    assertThat(manifest.getState()).isEqualTo(LogisticsArchiveManifestState.VERIFIED);
    assertThat(manifest.getCreatedBySubjectId()).isEqualTo(creator);
    assertThat(manifest.getVerifiedBySubjectId()).isEqualTo(verifier);
  }

  @Test
  void archiveManifestRejectsAnEmptyOrReversedPeriod() {
    OffsetDateTime boundary = OffsetDateTime.parse("2026-02-01T00:00:00Z");

    assertThatThrownBy(
            () ->
                LogisticsArchiveManifest.record(
                    LogisticsRetentionDataset.EVENT_INBOX,
                    boundary,
                    boundary,
                    "private/logistics/inbox-2026-01.ndjson.enc",
                    "a".repeat(64),
                    1,
                    UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
    assertThatThrownBy(
            () ->
                LogisticsArchiveManifest.record(
                    LogisticsRetentionDataset.EVENT_INBOX,
                    boundary.plusDays(1),
                    boundary,
                    "private/logistics/inbox-2026-01.ndjson.enc",
                    "a".repeat(64),
                    1,
                    UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
  }
}
