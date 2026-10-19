package dev.buhanzaz.rwms.dossier.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayRun;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierMediaProjectionRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierPartitionCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierProjectionGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierReplayPartitionHighWaterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierReplayRunRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DossierReplayTransactionsStartTest {
  @Test
  void locksThePointerBeforeCheckingForAnotherActiveReplay() {
    DossierActiveGenerationRepository pointers = mock(DossierActiveGenerationRepository.class);
    DossierProjectionGenerationRepository generations =
        mock(DossierProjectionGenerationRepository.class);
    DossierReplayRunRepository runs = mock(DossierReplayRunRepository.class);
    DossierActiveGeneration pointer = mock(DossierActiveGeneration.class);
    when(pointer.getGenerationId()).thenReturn(UUID.randomUUID());
    when(pointers.findForUpdateByPointerName(DossierActiveGeneration.POINTER_NAME))
        .thenReturn(Optional.of(pointer));
    when(runs.findAllByStateInOrderByStartedAtAsc(org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(List.of(mock(DossierReplayRun.class)));
    DossierReplayTransactions transactions =
        new DossierReplayTransactions(
            pointers,
            generations,
            runs,
            mock(DossierSourceFactRepository.class),
            mock(DossierInboxRepository.class),
            mock(DossierPartitionCheckpointRepository.class),
            mock(DossierReplayPartitionHighWaterRepository.class),
            mock(DossierActivityRepository.class),
            mock(DossierMediaProjectionRepository.class),
            mock(DossierSanitizedDeadLetterRepository.class),
            mock(DossierProjectionService.class),
            mock(dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator.class),
            new ObjectMapper());

    assertThatThrownBy(transactions::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DOSSIER_REPLAY_ALREADY_RUNNING");

    var order = inOrder(pointers, runs);
    order.verify(pointers).findForUpdateByPointerName(DossierActiveGeneration.POINTER_NAME);
    order.verify(runs)
        .findAllByStateInOrderByStartedAtAsc(org.mockito.ArgumentMatchers.anyCollection());
    verifyNoInteractions(generations);
  }
}
