package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierSanitizedDeadLetterTest {
  @Test
  void redeliveryDerivesTheSamePermanentFailureIdentity() {
    UUID eventId = UUID.randomUUID();
    DossierSanitizedDeadLetter first = pending(eventId);
    DossierSanitizedDeadLetter repeated = pending(eventId);

    assertThat(repeated.getId()).isEqualTo(first.getId());
    assertThat(repeated.getRecordKeySha256()).isEqualTo(first.getRecordKeySha256());
    assertThat(repeated.getMessageSha256()).isEqualTo(first.getMessageSha256());
  }

  private static DossierSanitizedDeadLetter pending(UUID eventId) {
    return DossierSanitizedDeadLetter.pending(
        eventId,
        eventId,
        "rwms.media.media.v1",
        3,
        42,
        "e".repeat(64),
        "f".repeat(64),
        DossierDltFailureCode.INVALID_PAYLOAD,
        OffsetDateTime.now());
  }
}
