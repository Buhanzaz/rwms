package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.api.ReplaceEstimateCreationWindowSettingsRequest;
import dev.buhanzaz.rwms.maintenance.domain.EstimateCreationWindowSettings;
import dev.buhanzaz.rwms.maintenance.mapper.EstimateCreationWindowSettingsResponseMapperImpl;
import dev.buhanzaz.rwms.maintenance.repository.EstimateCreationWindowSettingsRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EstimateCreationWindowSettingsServiceTest {
  private final EstimateCreationWindowSettingsRepository repository =
      mock(EstimateCreationWindowSettingsRepository.class);
  private final EstimateCreationWindowSettingsService service = new EstimateCreationWindowSettingsService(repository, new EstimateCreationWindowSettingsResponseMapperImpl());

  @Test
  void globalReplacementUsesStrictCasAndPolicyReadsTheSameValue() {
    EstimateCreationWindowSettings existing = EstimateCreationWindowSettings.create(7);
    when(repository.findById(EstimateCreationWindowSettings.SINGLETON_ID))
        .thenReturn(Optional.of(existing));
    when(repository.saveAndFlush(existing)).thenReturn(existing);

    assertThat(service.get().days()).isEqualTo(7);
    assertThat(service.replace(new ReplaceEstimateCreationWindowSettingsRequest(0L, 14)).days())
        .isEqualTo(14);
    assertThat(service.effectiveDays()).isEqualTo(14);
    assertThatThrownBy(
            () -> service.replace(new ReplaceEstimateCreationWindowSettingsRequest(1L, 31)))
        .isInstanceOf(MaintenanceConflictException.class);
    assertThat(service.effectiveDays()).isEqualTo(14);
  }

  @Test
  void missingSeedIsAnErrorAndNeverBecomesAnImplicitDefault() {
    when(repository.findById(EstimateCreationWindowSettings.SINGLETON_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(service::get).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(service::effectiveDays).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> service.replace(new ReplaceEstimateCreationWindowSettingsRequest(0L, 14)))
        .isInstanceOf(IllegalStateException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void domainEnforcesOneThroughThreeThousandSixHundredFiftyDays() {
    assertThatThrownBy(() -> EstimateCreationWindowSettings.create(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EstimateCreationWindowSettings.create(3651))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(EstimateCreationWindowSettings.create(3650).getDays())
        .isEqualTo(3650);
  }
}
