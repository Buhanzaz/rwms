package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierGenerationState;
import dev.buhanzaz.rwms.dossier.domain.DossierProjectionGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayRun;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayState;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierReplayRunTest {
  @Test
  void rejectCompletesEveryInactiveReplayStateAndIsIdempotent() {
    for (DossierReplayState state :
        new DossierReplayState[] {
          DossierReplayState.BUILDING,
          DossierReplayState.TAILING,
          DossierReplayState.VERIFYING,
          DossierReplayState.READY
        }) {
      DossierReplayRun run = runIn(state);
      OffsetDateTime rejectedAt = OffsetDateTime.now();
      run.reject(rejectedAt);
      run.reject(rejectedAt.plusSeconds(1));

      assertThat(run.getState()).isEqualTo(DossierReplayState.REJECTED);
      assertThat(run.getCompletedAt()).isEqualTo(rejectedAt);
    }
  }

  @Test
  void activatedReplayCannotBeRejected() {
    DossierReplayRun run = runIn(DossierReplayState.READY);
    run.activated(OffsetDateTime.now());

    assertThatThrownBy(() -> run.reject(OffsetDateTime.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void projectionGenerationRejectionIsIdempotent() {
    DossierProjectionGeneration generation =
        DossierProjectionGeneration.building(OffsetDateTime.now());
    generation.reject();
    generation.reject();

    assertThat(generation.getState()).isEqualTo(DossierGenerationState.REJECTED);
  }

  private static DossierReplayRun runIn(DossierReplayState target) {
    DossierReplayRun run =
        DossierReplayRun.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.now(),
            "a".repeat(64),
            OffsetDateTime.now());
    if (target == DossierReplayState.BUILDING) return run;
    run.tailing();
    if (target == DossierReplayState.TAILING) return run;
    run.verifying();
    if (target == DossierReplayState.VERIFYING) return run;
    run.parity(1, 1, "b".repeat(64), "b".repeat(64));
    return run;
  }
}
