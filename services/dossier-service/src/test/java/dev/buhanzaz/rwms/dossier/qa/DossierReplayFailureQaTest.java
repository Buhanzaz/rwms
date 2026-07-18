package dev.buhanzaz.rwms.dossier.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.dossier.domain.DossierGenerationState;
import dev.buhanzaz.rwms.dossier.domain.DossierProjectionGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayRun;
import dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierMediaProjectionRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierPartitionCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierProjectionGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierReplayPartitionHighWaterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierReplayRunRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import dev.buhanzaz.rwms.dossier.service.DossierProjectionService;
import dev.buhanzaz.rwms.dossier.service.DossierReplayService;
import dev.buhanzaz.rwms.dossier.service.DossierReplayTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DossierReplayFailureQaTest {
  @Test
  void runOnceRejectsTheInactiveGenerationAndRethrowsTheOriginalBuildFailure() {
    DossierReplayTransactions transactions = mock(DossierReplayTransactions.class);
    UUID runId = UUID.randomUUID();
    IllegalStateException failure = new IllegalStateException("build failed");
    when(transactions.start())
        .thenReturn(
            new DossierReplayTransactions.ReplayClaim(
                runId, OffsetDateTime.now(ZoneOffset.UTC)));
    doThrow(failure).when(transactions).build(runId);

    assertThatThrownBy(() -> new DossierReplayService(transactions).runOnce()).isSameAs(failure);

    verify(transactions).rejectFailed(runId);
    verify(transactions, never()).tailVerifyAndActivate(any());
  }

  @Test
  void rejectionFailureIsSuppressedWithoutHidingTheOriginalReplayFailure() {
    DossierReplayTransactions transactions = mock(DossierReplayTransactions.class);
    UUID runId = UUID.randomUUID();
    IllegalStateException failure = new IllegalStateException("tail failed");
    IllegalStateException rejection = new IllegalStateException("reject failed");
    when(transactions.start())
        .thenReturn(
            new DossierReplayTransactions.ReplayClaim(
                runId, OffsetDateTime.now(ZoneOffset.UTC)));
    doThrow(failure).when(transactions).tailVerifyAndActivate(runId);
    doThrow(rejection).when(transactions).rejectFailed(runId);

    assertThatThrownBy(() -> new DossierReplayService(transactions).runOnce())
        .isSameAs(failure)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(rejection));
  }

  @Test
  void staleRecoveryRejectsBothTargetGenerationAndReplayRun() {
    DossierReplayRunRepository runs = mock(DossierReplayRunRepository.class);
    DossierProjectionGenerationRepository generations =
        mock(DossierProjectionGenerationRepository.class);
    DossierReplayRun stale = mock(DossierReplayRun.class);
    DossierProjectionGeneration target = mock(DossierProjectionGeneration.class);
    UUID targetId = UUID.randomUUID();
    OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC);
    when(stale.getStartedAt()).thenReturn(cutoff.minusMinutes(1));
    when(stale.getTargetGenerationId()).thenReturn(targetId);
    when(target.getState()).thenReturn(DossierGenerationState.BUILDING);
    when(runs.findAllByStateInOrderByStartedAtAsc(any())).thenReturn(List.of(stale));
    when(generations.findById(targetId)).thenReturn(Optional.of(target));
    DossierReplayTransactions transactions = transactions(runs, generations);

    assertThat(transactions.rejectStaleBefore(cutoff)).isOne();

    verify(target).reject();
    verify(stale).reject(any(OffsetDateTime.class));
  }

  private static DossierReplayTransactions transactions(
      DossierReplayRunRepository runs, DossierProjectionGenerationRepository generations) {
    return new DossierReplayTransactions(
        mock(DossierActiveGenerationRepository.class),
        generations,
        runs,
        mock(DossierSourceFactRepository.class),
        mock(DossierInboxRepository.class),
        mock(DossierPartitionCheckpointRepository.class),
        mock(DossierReplayPartitionHighWaterRepository.class),
        mock(DossierActivityRepository.class),
        mock(DossierMediaProjectionRepository.class),
        mock(DossierProjectionService.class),
        mock(DossierEnvelopeValidator.class),
        new ObjectMapper());
  }
}
