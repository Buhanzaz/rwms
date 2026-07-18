package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierSubjectAssociation;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierSubjectAssociationTest {
  @Test
  void warehouseSnapshotCanRefreshOnlyForTheSameCabinIdentity() {
    UUID cabinId = UUID.randomUUID();
    UUID refreshedWarehouseId = UUID.randomUUID();
    UUID refreshedEventId = UUID.randomUUID();
    DossierSubjectAssociation association = association(cabinId);

    association.reprove(
        cabinId, refreshedWarehouseId, refreshedEventId, OffsetDateTime.now());

    assertThat(association.getCabinId()).isEqualTo(cabinId);
    assertThat(association.getWarehouseId()).isEqualTo(refreshedWarehouseId);
    assertThat(association.getSourceEventId()).isEqualTo(refreshedEventId);
  }

  @Test
  void aDifferentCabinIsAnIdentityConflictAndDoesNotMutateTheProof() {
    UUID cabinId = UUID.randomUUID();
    DossierSubjectAssociation association = association(cabinId);
    UUID originalWarehouseId = association.getWarehouseId();
    UUID originalEventId = association.getSourceEventId();

    assertThatThrownBy(
            () ->
                association.reprove(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    OffsetDateTime.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DOSSIER_SUBJECT_IDENTITY_CONFLICT");
    assertThat(association.getCabinId()).isEqualTo(cabinId);
    assertThat(association.getWarehouseId()).isEqualTo(originalWarehouseId);
    assertThat(association.getSourceEventId()).isEqualTo(originalEventId);
  }

  private static DossierSubjectAssociation association(UUID cabinId) {
    return DossierSubjectAssociation.prove(
        UUID.randomUUID(),
        DossierProducer.ASSET,
        "RENTAL_ITEM",
        UUID.randomUUID(),
        cabinId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        OffsetDateTime.now());
  }
}
