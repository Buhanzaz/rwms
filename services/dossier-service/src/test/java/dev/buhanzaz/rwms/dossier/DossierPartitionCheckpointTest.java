package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierPartitionCheckpoint;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class DossierPartitionCheckpointTest {
  @Test
  void staleAndEqualRedeliveriesAreHarmlessWithoutRegressingTheCheckpoint() {
    OffsetDateTime startedAt = OffsetDateTime.now();
    OffsetDateTime acceptedAt = startedAt.plusSeconds(1);
    DossierPartitionCheckpoint checkpoint =
        DossierPartitionCheckpoint.start(
            "dossier-projection-v1", "rwms.asset.rental-item.v1", 2, startedAt);
    checkpoint.advance(10, acceptedAt);

    checkpoint.advance(10, acceptedAt.plusSeconds(1));
    checkpoint.advance(7, acceptedAt.plusSeconds(2));

    assertThat(checkpoint.getLastAcceptedOffset()).isEqualTo(10);
    assertThat(checkpoint.getUpdatedAt()).isEqualTo(acceptedAt);
  }

  @Test
  void negativeOffsetsRemainInvalid() {
    DossierPartitionCheckpoint checkpoint =
        DossierPartitionCheckpoint.start(
            "dossier-projection-v1",
            "rwms.asset.rental-item.v1",
            0,
            OffsetDateTime.now());

    assertThatThrownBy(() -> checkpoint.advance(-1, OffsetDateTime.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("offset must be non-negative");
  }
}
