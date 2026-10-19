package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierReplayPartitionHighWater;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierReplayPartitionHighWaterTest {
  @Test
  void capturesAStableInclusivePartitionBoundary() {
    UUID runId = UUID.randomUUID();
    DossierReplayPartitionHighWater highWater =
        DossierReplayPartitionHighWater.capture(
            runId, "rwms.asset.rental-item.v1", 2, 41);

    assertThat(highWater.getRunId()).isEqualTo(runId);
    assertThat(highWater.getSourcePartition()).isEqualTo(2);
    assertThat(highWater.getMaxOffset()).isEqualTo(41);
  }

  @Test
  void rejectsInvalidPartitionCoordinates() {
    assertThatThrownBy(
            () ->
                DossierReplayPartitionHighWater.capture(
                    UUID.randomUUID(), "rwms.asset.rental-item.v1", -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
