package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceRepairModelSupportStatusTest {
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
