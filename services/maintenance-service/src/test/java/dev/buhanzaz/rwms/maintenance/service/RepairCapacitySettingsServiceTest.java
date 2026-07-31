package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.api.RepairCapacitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairCapacitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.RepairCapacitySettings;
import dev.buhanzaz.rwms.maintenance.mapper.RepairCapacitySettingsResponseMapper;
import dev.buhanzaz.rwms.maintenance.mapper.RepairCapacitySettingsResponseMapperImpl;
import dev.buhanzaz.rwms.maintenance.repository.RepairCapacitySettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RepairCapacitySettingsServiceTest {
  private final RepairCapacitySettingsRepository repository =
      mock(RepairCapacitySettingsRepository.class);
  private final RepairCapacitySettingsResponseMapper mapper =
      new RepairCapacitySettingsResponseMapperImpl();
  private RepairCapacitySettingsService service;

  @BeforeEach
  void setUp() {
    service = new RepairCapacitySettingsService(repository, mapper);
  }

  @Test
  void absentSettingsReturnDefaultWithoutWriting() {
    UUID warehouseId = UUID.randomUUID();
    when(repository.findById(warehouseId)).thenReturn(Optional.empty());

    assertThat(service.get(warehouseId))
        .isEqualTo(new RepairCapacitySettingsResponse(warehouseId, 0, 6, null, null));
    verify(repository).findById(warehouseId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void versionZeroCreatesAssignedWarehouseSettings() {
    UUID warehouseId = UUID.randomUUID();
    when(repository.findById(warehouseId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(RepairCapacitySettings.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    RepairCapacitySettingsResponse response = service.replace(
        warehouseId, new ReplaceRepairCapacitySettingsRequest(0L, 9));

    assertThat(response)
        .isEqualTo(new RepairCapacitySettingsResponse(warehouseId, 0, 9, null, null));
    ArgumentCaptor<RepairCapacitySettings> saved =
        ArgumentCaptor.forClass(RepairCapacitySettings.class);
    verify(repository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getWarehouseId()).isEqualTo(warehouseId);
    assertThat(saved.getValue().getRepairPlaceCount()).isEqualTo(9);
  }

  @Test
  void absentSettingsRejectNonzeroExpectedVersion() {
    UUID warehouseId = UUID.randomUUID();
    when(repository.findById(warehouseId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replace(
        warehouseId, new ReplaceRepairCapacitySettingsRequest(1L, 7)))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(exception -> ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_VERSION_CONFLICT");
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void existingSettingsUseStrictVersionCasAndDomainReplace() {
    UUID warehouseId = UUID.randomUUID();
    RepairCapacitySettings settings = RepairCapacitySettings.create(warehouseId, 4);
    when(repository.findById(warehouseId)).thenReturn(Optional.of(settings));
    when(repository.saveAndFlush(settings)).thenReturn(settings);

    assertThat(service.replace(
            warehouseId, new ReplaceRepairCapacitySettingsRequest(0L, 8))
        .repairPlaceCount())
        .isEqualTo(8);

    assertThatThrownBy(() -> service.replace(
        warehouseId, new ReplaceRepairCapacitySettingsRequest(1L, 10)))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(exception -> ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_VERSION_CONFLICT");
  }

  @Test
  void domainRejectsNonpositiveCapacity() {
    UUID warehouseId = UUID.randomUUID();
    assertThatThrownBy(() -> RepairCapacitySettings.create(warehouseId, 0))
        .isInstanceOf(IllegalArgumentException.class);

    RepairCapacitySettings settings = RepairCapacitySettings.create(warehouseId, 1);
    assertThatThrownBy(() -> settings.replace(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
