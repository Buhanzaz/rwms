package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void coverageRequiresAnExactPairAndResolvesWithoutChangingTransportState() {
    DossierSanitizedDeadLetter failure = pending(UUID.randomUUID());
    UUID generationId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    OffsetDateTime resolvedAt = OffsetDateTime.now();

    assertThatThrownBy(() -> failure.markCoverageUnresolved(generationId, null))
        .isInstanceOf(IllegalArgumentException.class);

    failure.markCoverageUnresolved(generationId, cabinId);
    failure.resolveCoverage(resolvedAt);

    assertThat(failure.getCoverageGenerationId()).isEqualTo(generationId);
    assertThat(failure.getCoverageSubjectCabinId()).isEqualTo(cabinId);
    assertThat(failure.getCoverageResolvedAt()).isEqualTo(resolvedAt);
    assertThat(failure.getStatus())
        .isEqualTo(dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.PENDING);
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
        null,
        null,
        OffsetDateTime.now());
  }
}
