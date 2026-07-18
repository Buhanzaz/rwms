package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint;
import dev.buhanzaz.rwms.dossier.domain.DossierAggregateBlockReason;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierCheckpointPolicyTest {
  @Test
  void mediaStartsAfterItsCanonicalInitialVersionZeroSoVersionOneIsAccepted() {
    DossierAggregateCheckpoint checkpoint =
        DossierAggregateCheckpoint.start(
            "dossier-projection-v1",
            DossierProducer.MEDIA,
            "rwms.media.media.v1",
            "MEDIA",
            UUID.randomUUID(),
            0,
            OffsetDateTime.now());

    assertThat(checkpoint.nextExpectedVersion()).isOne();
    checkpoint.apply(1, OffsetDateTime.now());
    assertThat(checkpoint.getAppliedVersion()).isOne();
    assertThat(checkpoint.isBlocked()).isFalse();
  }

  @Test
  void zeroBasedSourceFamiliesStillRequireVersionZero() {
    DossierAggregateCheckpoint checkpoint =
        DossierAggregateCheckpoint.start(
            "dossier-projection-v1",
            DossierProducer.ASSET,
            "rwms.asset.rental-item.v1",
            "RENTAL_ITEM",
            UUID.randomUUID(),
            -1,
            OffsetDateTime.now());

    assertThat(checkpoint.nextExpectedVersion()).isZero();
  }

  @Test
  void onlyMissingPrefixBlocksCanBeReconciled() {
    DossierAggregateCheckpoint gap = assetCheckpoint();
    gap.blockGap(2, OffsetDateTime.now());
    assertThat(gap.getBlockedReason()).isEqualTo(DossierAggregateBlockReason.MISSING_PREFIX);
    gap.reconcile(1, OffsetDateTime.now());
    assertThat(gap.isBlocked()).isFalse();

    DossierAggregateCheckpoint conflict = assetCheckpoint();
    conflict.blockConflict(
        DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT, OffsetDateTime.now());
    assertThat(conflict.getExpectedVersion()).isNull();
    assertThat(conflict.getObservedVersion()).isNull();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> conflict.reconcile(0, OffsetDateTime.now()))
        .isInstanceOf(IllegalStateException.class);

    DossierAggregateCheckpoint processingFailed = assetCheckpoint();
    processingFailed.blockConflict(
        DossierAggregateBlockReason.PROCESSING_FAILED, OffsetDateTime.now());
    assertThat(processingFailed.getBlockedReason())
        .isEqualTo(DossierAggregateBlockReason.PROCESSING_FAILED);
  }

  private static DossierAggregateCheckpoint assetCheckpoint() {
    return DossierAggregateCheckpoint.start(
        "dossier-projection-v1",
        DossierProducer.ASSET,
        "rwms.asset.rental-item.v1",
        "RENTAL_ITEM",
        UUID.randomUUID(),
        -1,
        OffsetDateTime.now());
  }
}
