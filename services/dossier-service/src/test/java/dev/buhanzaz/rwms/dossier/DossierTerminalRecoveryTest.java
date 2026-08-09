package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierTerminalRecoveryTest {
  @Test
  void outboxCanBeExplicitlyRequeuedOnlyFromDlt() {
    DossierOutboxEvent event = outbox();
    event.retry(OffsetDateTime.now());
    event.deadLetter();
    OffsetDateTime requeuedAt = OffsetDateTime.now();

    event.requeueFromDeadLetter(requeuedAt);

    assertThat(event.getStatus()).isEqualTo(DossierOutboxState.RETRY);
    assertThat(event.getAttemptCount()).isZero();
    assertThat(event.getNextAttemptAt()).isEqualTo(requeuedAt);
    assertThatThrownBy(() -> event.requeueFromDeadLetter(OffsetDateTime.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void publishedOutboxCannotBeRequeued() {
    DossierOutboxEvent event = outbox();
    event.published(OffsetDateTime.now());

    assertThatThrownBy(() -> event.requeueFromDeadLetter(OffsetDateTime.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sanitizedDltCanBeExplicitlyRequeuedOnlyFromTerminalFailure() {
    DossierSanitizedDeadLetter letter = deadLetter();
    letter.retry(OffsetDateTime.now());
    letter.deadLetter();
    OffsetDateTime requeuedAt = OffsetDateTime.now();

    letter.requeueFromDeadLetter(requeuedAt);

    assertThat(letter.getStatus()).isEqualTo(DossierOutboxState.RETRY);
    assertThat(letter.getAttemptCount()).isZero();
    assertThat(letter.getNextAttemptAt()).isEqualTo(requeuedAt);
    assertThatThrownBy(() -> letter.requeueFromDeadLetter(OffsetDateTime.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void publishedSanitizedDltCannotBeRequeued() {
    DossierSanitizedDeadLetter letter = deadLetter();
    letter.published(OffsetDateTime.now());

    assertThatThrownBy(() -> letter.requeueFromDeadLetter(OffsetDateTime.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static DossierOutboxEvent outbox() {
    return DossierOutboxEvent.pending(
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        UUID.randomUUID(),
        "a".repeat(64),
        "{}",
        OffsetDateTime.now());
  }

  private static DossierSanitizedDeadLetter deadLetter() {
    return DossierSanitizedDeadLetter.pending(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "rwms.media.media.v1",
        0,
        1,
        "b".repeat(64),
        "c".repeat(64),
        DossierDltFailureCode.PROCESSING_FAILED,
        null,
        null,
        OffsetDateTime.now());
  }
}
