package dev.buhanzaz.rwms.logistics.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.UpdateRentalSettingsRequest;
import dev.buhanzaz.rwms.logistics.inquiry.domain.LateChangeFeeMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalSettings;
import dev.buhanzaz.rwms.logistics.inquiry.mapper.RentalInquiryResponseMapper;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalSettingsRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class RentalSettingsServiceTest {
  private final RentalSettingsRepository repository = mock(RentalSettingsRepository.class);
  private final RentalSettingsService service =
      new RentalSettingsService(repository, mock(RentalInquiryResponseMapper.class));

  @Test
  void absentPolicyReadDoesNotCreateASettingOrAssumeFreeChanges() {
    assertThat(service.lateChangePolicy())
        .isEqualTo(new RentalSettingsService.LateChangePolicy(0, 2, null, null, null));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void snapshotRetainsExactConfiguredAmount() {
    RentalSettings settings = RentalSettings.defaults(UUID.randomUUID(), OffsetDateTime.now());
    settings.update(
        10,
        60,
        60,
        1440,
        3,
        LateChangeFeeMode.FIXED,
        new BigDecimal("9223372036854775807"),
        "+74951234567",
        UUID.randomUUID(),
        OffsetDateTime.now());
    when(repository.findById(RentalSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
    var snapshot = service.lateChangePolicy();
    settings.update(10, 60, 60, 1440, 4, null, null, null, UUID.randomUUID(), OffsetDateTime.now());
    assertThat(snapshot.noticeDays()).isEqualTo(3);
    assertThat(snapshot.feeValue()).isEqualByComparingTo("9223372036854775807");
    assertThat(snapshot.supportPhone()).isEqualTo("+74951234567");
  }

  @Test
  void unauthorizedRoleAndMissingWriteScopeCannotMutateSettings() {
    assertThatThrownBy(() -> service.update(actor("RENTAL_MANAGER", true), request(0)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.update(actor("SYSTEM_ADMIN", false), request(0)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void staleVersionDoesNotUpdateAnyField() {
    RentalSettings value = RentalSettings.defaults(UUID.randomUUID(), OffsetDateTime.now());
    when(repository.findForUpdate(RentalSettings.SINGLETON_ID)).thenReturn(Optional.of(value));
    assertThatThrownBy(() -> service.update(actor("SYSTEM_ADMIN", true), request(1)))
        .isInstanceOf(OrderProblemException.class);
    assertThat(value.getLateChangeFeeValue()).isNull();
    verify(repository, never()).saveAndFlush(any());
  }

  private static UpdateRentalSettingsRequest request(long version) {
    return new UpdateRentalSettingsRequest(
        version, 10, 60, 60, 1440, 2, LateChangeFeeMode.FIXED, BigDecimal.TEN, null);
  }

  private static OrderActor actor(String role, boolean write) {
    return new OrderActor(
        UUID.randomUUID(), role, "Admin", Set.of(), Set.of(), true, true, write, true);
  }
}
