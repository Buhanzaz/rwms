package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.api.EstimateCreationWindowSettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceEstimateCreationWindowSettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.EstimateCreationWindowSettings;
import dev.buhanzaz.rwms.maintenance.mapper.EstimateCreationWindowSettingsResponseMapper;
import dev.buhanzaz.rwms.maintenance.mapper.EstimateCreationWindowSettingsResponseMapperImpl;
import dev.buhanzaz.rwms.maintenance.repository.EstimateCreationWindowSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EstimateCreationWindowSettingsServiceTest {
  private final EstimateCreationWindowSettingsRepository repository =
      mock(EstimateCreationWindowSettingsRepository.class);
  private final EstimateCreationWindowSettingsResponseMapper mapper =
      new EstimateCreationWindowSettingsResponseMapperImpl();
  private EstimateCreationWindowSettingsService service;

  @BeforeEach
  void setUp() {
    service = new EstimateCreationWindowSettingsService(repository, mapper);
  }

  @Test
  void absentSettingReturnsSevenDayDefaultWithoutWriting() {
    UUID warehouseId = UUID.randomUUID();
    when(repository.findById(warehouseId)).thenReturn(Optional.empty());

    assertThat(service.get(warehouseId))
        .isEqualTo(new EstimateCreationWindowSettingsResponse(warehouseId, 0, 7, null, null));
    assertThat(service.effectiveDays(warehouseId)).isEqualTo(7);
    verify(repository, org.mockito.Mockito.times(2)).findById(warehouseId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void versionZeroCreatesWarehouseSettingAndExistingValueUsesStrictCas() {
    UUID warehouseId = UUID.randomUUID();
    when(repository.findById(warehouseId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(EstimateCreationWindowSettings.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(
            service.replace(
                warehouseId, new ReplaceEstimateCreationWindowSettingsRequest(0L, 14)))
        .extracting(EstimateCreationWindowSettingsResponse::days)
        .isEqualTo(14);

    EstimateCreationWindowSettings existing =
        EstimateCreationWindowSettings.create(warehouseId, 14);
    when(repository.findById(warehouseId)).thenReturn(Optional.of(existing));
    when(repository.saveAndFlush(existing)).thenReturn(existing);

    assertThat(
            service.replace(
                warehouseId, new ReplaceEstimateCreationWindowSettingsRequest(0L, 30))
                .days())
        .isEqualTo(30);
    assertThatThrownBy(
            () ->
                service.replace(
                    warehouseId, new ReplaceEstimateCreationWindowSettingsRequest(1L, 31)))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(exception -> ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_VERSION_CONFLICT");
  }

  @Test
  void absentSettingRejectsNonzeroExpectedVersion() {
    UUID warehouseId = UUID.randomUUID();
    when(repository.findById(warehouseId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.replace(
                    warehouseId, new ReplaceEstimateCreationWindowSettingsRequest(2L, 7)))
        .isInstanceOf(MaintenanceConflictException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void domainEnforcesOneThroughThreeThousandSixHundredFiftyDays() {
    UUID warehouseId = UUID.randomUUID();
    assertThatThrownBy(() -> EstimateCreationWindowSettings.create(warehouseId, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EstimateCreationWindowSettings.create(warehouseId, 3651))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(EstimateCreationWindowSettings.create(warehouseId, 3650).getDays())
        .isEqualTo(3650);
  }
}
