package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedFact;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedReason;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierUnlinkedFactTest {
  @Test
  void resolutionIsIdempotentAndRetainsTheOriginalReason() {
    DossierUnlinkedFact fact =
        DossierUnlinkedFact.record(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DossierUnlinkedReason.MISSING_PREFIX,
            DossierProducer.ASSET,
            "RENTAL_ITEM",
            UUID.randomUUID(),
            "a".repeat(64),
            OffsetDateTime.now());
    OffsetDateTime resolvedAt = OffsetDateTime.now();

    fact.resolve(resolvedAt);
    fact.resolve(resolvedAt.plusSeconds(1));

    assertThat(fact.getResolvedAt()).isEqualTo(resolvedAt);
    assertThat(fact.getReason()).isEqualTo(DossierUnlinkedReason.MISSING_PREFIX);
  }
}
