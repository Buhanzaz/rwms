package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierInbox;
import dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierInboxTest {
  @Test
  void quarantinedDeliveryCanBecomeProcessedAfterGapRecovery() {
    DossierInbox inbox = inbox();
    inbox.decide(DossierInboxDecision.QUARANTINED, OffsetDateTime.now());

    inbox.recoverProcessed(OffsetDateTime.now());

    assertThat(inbox.getDecision()).isEqualTo(DossierInboxDecision.PROCESSED);
  }

  @Test
  void otherTerminalDecisionsCannotBeRecovered() {
    for (DossierInboxDecision terminal :
        new DossierInboxDecision[] {
          DossierInboxDecision.PROCESSED,
          DossierInboxDecision.DUPLICATE,
          DossierInboxDecision.DLT
        }) {
      DossierInbox inbox = inbox();
      inbox.decide(terminal, OffsetDateTime.now());
      assertThatThrownBy(() -> inbox.recoverProcessed(OffsetDateTime.now()))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void quarantinedDeliveryCanEnterDltAndRepeatedTransitionIsHarmless() {
    DossierInbox inbox = inbox();
    inbox.decide(DossierInboxDecision.QUARANTINED, OffsetDateTime.now());
    OffsetDateTime deadLetteredAt = OffsetDateTime.now();

    inbox.deadLetterQuarantined(deadLetteredAt);
    inbox.deadLetterQuarantined(deadLetteredAt.plusSeconds(1));

    assertThat(inbox.getDecision()).isEqualTo(DossierInboxDecision.DLT);
    assertThat(inbox.getDecidedAt()).isEqualTo(deadLetteredAt);
  }

  @Test
  void nonQuarantinedDeliveryCannotEnterDltThroughRecoveryApi() {
    for (DossierInboxDecision decision :
        new DossierInboxDecision[] {
          DossierInboxDecision.RECEIVED,
          DossierInboxDecision.PROCESSED,
          DossierInboxDecision.DUPLICATE
        }) {
      DossierInbox inbox = inbox();
      if (decision != DossierInboxDecision.RECEIVED) {
        inbox.decide(decision, OffsetDateTime.now());
      }
      assertThatThrownBy(() -> inbox.deadLetterQuarantined(OffsetDateTime.now()))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void processedDeferredFactCanEnterDltOnlyForAProvenConflict() {
    DossierInbox inbox = inbox();
    inbox.decide(DossierInboxDecision.PROCESSED, OffsetDateTime.now());
    OffsetDateTime conflictAt = OffsetDateTime.now();

    inbox.deadLetterProcessedConflict(conflictAt);
    inbox.deadLetterProcessedConflict(conflictAt.plusSeconds(1));

    assertThat(inbox.getDecision()).isEqualTo(DossierInboxDecision.DLT);
    assertThat(inbox.getDecidedAt()).isEqualTo(conflictAt);
  }

  private static DossierInbox inbox() {
    return DossierInbox.receive(UUID.randomUUID(), "d".repeat(64), OffsetDateTime.now());
  }
}
