package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CreateCustomerBookingChangeQuoteRequest;
import dev.buhanzaz.rwms.logistics.customer.domain.*;
import dev.buhanzaz.rwms.logistics.customer.mapper.CustomerBookingChangeResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.repository.*;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.inquiry.domain.LateChangeFeeMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalSettings;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalSettingsRepository;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.order.service.OrderAuditService;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercises real charge transitions and mapping with controlled ownership and clock fences. */
class CustomerBookingChangeChargeStoreTest {
  private final UUID subject = UUID.randomUUID(),
      booking = UUID.randomUUID(),
      order = UUID.randomUUID(),
      inquiry = UUID.randomUUID(),
      warehouse = UUID.randomUUID(),
      oldSlot = UUID.randomUUID();
  private final Instant instant = Instant.parse("2026-09-05T08:00:00Z");
  private final Clock clock = mock(Clock.class);
  private final CustomerBookingChangeChargeRepository charges =
      mock(CustomerBookingChangeChargeRepository.class);
  private final CustomerRentalSessionRepository sessions =
      mock(CustomerRentalSessionRepository.class);
  private final CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
  private final RentalSettingsService policies = mock(RentalSettingsService.class);
  private final RentalSettingsRepository settings = mock(RentalSettingsRepository.class);
  private final OrderAuditService audit = mock(OrderAuditService.class);
  private final CustomerRentalSession session = mock(CustomerRentalSession.class);
  private final CustomerDeliverySlot original = mock(CustomerDeliverySlot.class);
  private CustomerBookingChangeChargeStore store;

  @BeforeEach
  void setUp() {
    when(clock.instant()).thenReturn(instant);
    store =
        new CustomerBookingChangeChargeStore(
            charges,
            sessions,
            slots,
            policies,
            settings,
            Mappers.getMapper(CustomerBookingChangeResponseMapper.class),
            mock(LogisticsTransactionLock.class),
            audit,
            clock);
    ReflectionTestUtils.setField(store, "testPaymentEnabled", true);
    when(session.getCustomerSubjectId()).thenReturn(subject);
    when(session.getBookingId()).thenReturn(booking);
    when(session.getOrderId()).thenReturn(order);
    when(session.getInquiryId()).thenReturn(inquiry);
    when(session.getWarehouseId()).thenReturn(warehouse);
    when(session.getDeliverySlotId()).thenReturn(oldSlot);
    when(session.getState()).thenReturn(CustomerSessionState.BOOKED);
    when(session.getVersion()).thenReturn(4L);
    when(sessions.findByBookingIdForUpdate(booking)).thenReturn(Optional.of(session));
    when(original.getId()).thenReturn(oldSlot);
    when(original.getBookingId()).thenReturn(booking);
    when(original.getOrderId()).thenReturn(order);
    when(original.getInquiryId()).thenReturn(inquiry);
    when(original.getCustomerSubjectId()).thenReturn(subject);
    when(original.getWarehouseId()).thenReturn(warehouse);
    when(original.getState()).thenReturn(CustomerDeliverySlotState.CONFIRMED);
    when(original.getDeliveryDate()).thenReturn(LocalDate.of(2026, 9, 6));
    when(original.getDeliveryPriceRubles()).thenReturn(10_000L);
    when(slots.findById(oldSlot)).thenReturn(Optional.of(original));
    when(charges.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              CustomerBookingChangeCharge charge = call.getArgument(0);
              if (charge.getId() == null)
                ReflectionTestUtils.setField(charge, "id", UUID.randomUUID());
              when(charges.findById(charge.getId())).thenReturn(Optional.of(charge));
              when(charges.findForUpdate(charge.getId())).thenReturn(Optional.of(charge));
              when(charges.findByCustomerSubjectIdAndIdempotencyKey(
                      subject, charge.getIdempotencyKey()))
                  .thenReturn(Optional.of(charge));
              return charge;
            });
    policy(null, null);
  }

  @Test
  void unknownPolicySurvivesCreateExactGetAndReplayWithoutPretendingItIsFree() {
    UUID key = UUID.randomUUID();
    var response = create(key);
    assertThat(response.amountRubles()).isNull();
    assertThat(response.settlement()).isEqualTo(CustomerChangeSettlement.POLICY_UNCONFIGURED);
    assertThat(response.testPaymentAvailable()).isFalse();
    assertThat(store.get(identity(), booking, response.quoteId())).isEqualTo(response);
    assertThat(store.replay(identity(), key, booking, "a".repeat(64))).contains(response);
    assertThatThrownBy(() -> store.replay(identity(), key, booking, "b".repeat(64)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void otherCustomerCannotReadOrApplyQuote() {
    var response = create(UUID.randomUUID());
    assertThatThrownBy(
            () ->
                store.get(
                    new CustomerIdentity(UUID.randomUUID(), "other"), booking, response.quoteId()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.status().value()).isEqualTo(404));
    when(session.getCustomerSubjectId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(() -> admit(response.quoteId(), true))
        .isInstanceOf(OrderProblemException.class);
  }

  @Test
  void exactConsentIsRequiredAndTestPaymentIsRecordedOnlyAfterMatchingOwnerCompletion() {
    policy(LateChangeFeeMode.FIXED, new BigDecimal("1500"));
    var response = create(UUID.randomUUID());
    assertThatThrownBy(() -> admit(response.quoteId(), false))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("CUSTOMER_CHANGE_PAYMENT_REQUIRED"));
    var charge = admit(response.quoteId(), true);
    UUID mutation = UUID.randomUUID();
    store.bind(charge, mutation, true);
    assertThat(charge.getApplicationState()).isEqualTo(CustomerChangeApplicationState.APPLYING);
    assertThat(charge.getSettlement()).isEqualTo(CustomerChangeSettlement.PAYMENT_REQUIRED);
    assertThatThrownBy(
            () -> charge.completeApplication(UUID.randomUUID(), instant.atOffset(ZoneOffset.UTC)))
        .isInstanceOf(IllegalStateException.class);
    when(charges.findByMutationId(mutation)).thenReturn(Optional.of(charge));
    store.complete(mutation);
    store.complete(mutation);
    assertThat(charge.getApplicationState()).isEqualTo(CustomerChangeApplicationState.APPLIED);
    assertThat(charge.getSettlement()).isEqualTo(CustomerChangeSettlement.TEST_PAID);
    verify(audit, times(1)).appendForActor(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void paymentSwitchOffCannotAcceptConsentOrAdvertisePayment() {
    policy(LateChangeFeeMode.FIXED, BigDecimal.TEN);
    ReflectionTestUtils.setField(store, "testPaymentEnabled", false);
    var quote = create(UUID.randomUUID());
    assertThat(quote.testPaymentAvailable()).isFalse();
    assertThatThrownBy(() -> admit(quote.quoteId(), true))
        .isInstanceOf(OrderProblemException.class);
  }

  @Test
  void policyAndSourceVersionChangesInvalidateConsent() {
    policy(LateChangeFeeMode.FIXED, BigDecimal.TEN);
    var quote = create(UUID.randomUUID());
    when(session.getVersion()).thenReturn(5L);
    assertThatThrownBy(() -> admit(quote.quoteId(), true))
        .isInstanceOf(OrderProblemException.class);
    when(session.getVersion()).thenReturn(4L);
    policy(LateChangeFeeMode.FIXED, BigDecimal.ONE);
    assertThatThrownBy(() -> admit(quote.quoteId(), true))
        .isInstanceOf(OrderProblemException.class);
  }

  @Test
  void expiryWhileWaitingForSettingsLockIsRejected() {
    policy(LateChangeFeeMode.FIXED, BigDecimal.TEN);
    var quote = create(UUID.randomUUID());
    var current = settings.findForUpdate(RentalSettings.SINGLETON_ID);
    when(settings.findForUpdate(RentalSettings.SINGLETON_ID))
        .thenAnswer(
            call -> {
              when(clock.instant()).thenReturn(quote.expiresAt().toInstant());
              return current;
            });
    assertThatThrownBy(() -> admit(quote.quoteId(), true))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("CUSTOMER_CHANGE_QUOTE_STALE"));
  }

  @Test
  void waiverKeepsOriginalAmountAndCannotTurnIntoPaidOrSkipReason() {
    policy(LateChangeFeeMode.FIXED, BigDecimal.TEN);
    var quote = create(UUID.randomUUID());
    var charge = charges.findById(quote.quoteId()).orElseThrow();
    assertThatThrownBy(() -> charge.waive(UUID.randomUUID(), UUID.randomUUID(), 0, " "))
        .isInstanceOf(IllegalArgumentException.class);
    charge.waive(UUID.randomUUID(), UUID.randomUUID(), 0, "  Форс-мажор  ");
    assertThat(charge.getAmountRubles()).isEqualTo(10L);
    assertThat(charge.getWaiverReason()).isEqualTo("Форс-мажор");
    assertThat(store.get(identity(), booking, quote.quoteId()).amountRubles()).isZero();
    var admitted = admit(quote.quoteId(), false);
    UUID mutation = UUID.randomUUID();
    store.bind(admitted, mutation, true);
    admitted.completeApplication(mutation, instant.atOffset(ZoneOffset.UTC));
    assertThat(admitted.getSettlement()).isEqualTo(CustomerChangeSettlement.WAIVED);
    assertThat(admitted.isTestPaymentRequested()).isFalse();
  }

  private CustomerIdentity identity() {
    return new CustomerIdentity(subject, "customer");
  }

  private dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels
          .CustomerBookingChangeQuoteResponse
      create(UUID key) {
    return store.create(
        identity(),
        booking,
        key,
        "a".repeat(64),
        new CreateCustomerBookingChangeQuoteRequest(
            4L, CustomerBookingMutationOperation.CANCEL, null, null),
        new WarehouseIdentity(warehouse, 3, true, "Europe/Moscow"));
  }

  private CustomerBookingChangeCharge admit(UUID quote, boolean payment) {
    return store.admit(
        session, original, CustomerBookingMutationOperation.CANCEL, null, null, quote, 0L, payment);
  }

  private void policy(LateChangeFeeMode mode, BigDecimal value) {
    when(policies.lateChangePolicy())
        .thenReturn(new RentalSettingsService.LateChangePolicy(7, 2, mode, value, "+74951234567"));
    RentalSettings current = mock(RentalSettings.class);
    when(current.getVersion()).thenReturn(7L);
    when(current.getLateChangeNoticeDays()).thenReturn(2);
    when(current.getLateChangeFeeMode()).thenReturn(mode);
    when(current.getLateChangeFeeValue()).thenReturn(value);
    when(settings.findForUpdate(RentalSettings.SINGLETON_ID)).thenReturn(Optional.of(current));
  }
}
