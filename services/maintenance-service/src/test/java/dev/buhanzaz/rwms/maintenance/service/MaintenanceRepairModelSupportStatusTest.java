package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceRepairModelSupportStatusTest {
  @Test
  void isolationBlocksBothCommandReadsUntilANewerReleaseFact() {
    UUID id = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemFactProjection fact = RentalItemFactProjection.create(id, warehouse, "FREE", 0);
    var repository = mock(RentalItemFactProjectionRepository.class);
    when(repository.findById(id)).thenReturn(Optional.of(fact));
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(fact));
    var support =
        new MaintenanceRepairModelSupport(
            null, null, null, null, repository, null, null, null, null, null, null);

    assertThat(fact.apply(warehouse, "FREE", 1, true)).isTrue();
    assertThatThrownBy(() -> support.requireRentalItemFact(id, warehouse))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("isolated");
    assertThatThrownBy(() -> support.requireRentalItemFactForUpdate(id, warehouse))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("isolated");
    fact.apply(warehouse, "FREE", 2);
    assertThat(fact.isInventoryIsolated()).isTrue();
    assertThat(fact.apply(warehouse, "FREE", 1, false)).isFalse();
    assertThat(fact.isInventoryIsolated()).isTrue();
    assertThat(fact.apply(warehouse, "FREE", 3, false)).isTrue();
    assertThatCode(() -> support.requireRentalItemFact(id, warehouse)).doesNotThrowAnyException();
    assertThatCode(() -> support.requireRentalItemFactForUpdate(id, warehouse))
        .doesNotThrowAnyException();
  }

  @Test
  void visibilityCanInitializeAnIsolatedProjectionWithoutAFakeEarlierVersion() {
    var fact =
        RentalItemFactProjection.create(UUID.randomUUID(), UUID.randomUUID(), "FREE", 1, true);
    assertThat(fact.isInventoryIsolated()).isTrue();
    assertThat(fact.getAggregateVersion()).isEqualTo(1);
  }

  @Test
  void afterRentIsEligibleForDirectRepairWhileRentedRemainsRejected() {
    UUID warehouseId = UUID.randomUUID();
    assertThatCode(
            () ->
                MaintenanceRepairModelSupport.requireDirectRepairSourceStatus(
                    RentalItemFactProjection.create(
                        UUID.randomUUID(), warehouseId, "AFTER_RENT", 3)))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                MaintenanceRepairModelSupport.requireDirectRepairSourceStatus(
                    RentalItemFactProjection.create(UUID.randomUUID(), warehouseId, "RENTED", 3)))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("must not have RENTED status");
  }
}
