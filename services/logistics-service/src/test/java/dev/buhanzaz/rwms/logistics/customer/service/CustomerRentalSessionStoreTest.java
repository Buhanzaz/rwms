package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Verifies that interrupted commands resume with their original downstream idempotency key. */
class CustomerRentalSessionStoreTest {
  private static final String HASH = "b".repeat(64);
  @Test
  void checkoutRetryWithNewTransportKeyReusesPendingDomainCommandKey() {
    UUID subjectId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID originalKey = UUID.randomUUID();
    CustomerRentalSession session =
        CustomerRentalSession.create(inquiryId, subjectId, UUID.randomUUID());
    session.beginCheckout(0, originalKey, HASH);
    CustomerRentalSessionStore store = store(session, inquiryId);

    var preparation =
        store.prepareCheckout(subjectId, inquiryId, 0, UUID.randomUUID(), HASH);

    assertThat(preparation.pendingReplay()).isTrue();
    assertThat(preparation.commandKey()).isEqualTo(originalKey);
  }

  @Test
  void selectionRetryWithNewTransportKeyReusesPendingDomainCommandKey() {
    UUID subjectId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID originalKey = UUID.randomUUID();
    CustomerRentalSession session =
        CustomerRentalSession.create(inquiryId, subjectId, UUID.randomUUID());
    session.beginSelection(0, originalKey, HASH);
    CustomerRentalSessionStore store = store(session, inquiryId);

    var preparation =
        store.prepareSelection(subjectId, inquiryId, 0, UUID.randomUUID(), HASH);

    assertThat(preparation.pendingReplay()).isTrue();
    assertThat(preparation.commandKey()).isEqualTo(originalKey);
  }

  @Test
  void firstCartCreationLocksTheInquiryBeforeCheckingTheUniqueRow() {
    UUID subjectId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    CustomerRentalSessionRepository repository = mock(CustomerRentalSessionRepository.class);
    LogisticsTransactionLock lock = mock(LogisticsTransactionLock.class);
    when(repository.findByInquiryId(inquiryId))
        .thenReturn(Optional.empty());
    when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    CustomerRentalSessionStore store = new CustomerRentalSessionStore(repository, lock);

    store.create(inquiryId, subjectId, warehouseId);

    InOrder order = inOrder(lock, repository);
    order
        .verify(lock)
        .acquire("customer-rental-session:" + inquiryId);
    order.verify(repository).findByInquiryId(inquiryId);
  }

  @Test
  void pendingCheckoutRecoveryClaimsOnlyOneBoundedDuePage() {
    CustomerRentalSessionRepository repository = mock(CustomerRentalSessionRepository.class);
    CustomerRentalSessionStore store =
        new CustomerRentalSessionStore(repository, mock(LogisticsTransactionLock.class));
    CustomerRentalSession session = pendingReceipt();
    when(repository.currentDatabaseTimestamp())
        .thenReturn(session.getRecoveryNextAttemptAt().toInstant());
    when(repository.findDueCheckoutRecoveryForUpdate(
            org.mockito.ArgumentMatchers.any(OffsetDateTime.class),
            org.mockito.ArgumentMatchers.eq(100)))
        .thenReturn(List.of(session));

    var claims = store.claimPendingBookings();

    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().inquiryId()).isEqualTo(session.getInquiryId());
    assertThat(session.getRecoveryLeaseToken()).isEqualTo(claims.getFirst().leaseToken());
    verify(repository)
        .findDueCheckoutRecoveryForUpdate(
            org.mockito.ArgumentMatchers.any(OffsetDateTime.class),
            org.mockito.ArgumentMatchers.eq(100));
    verify(repository).flush();
  }

  @Test
  void failedClaimUsesExponentialBackoffThenQuarantinesAtEightAttempts() {
    CustomerRentalSession session = pendingReceipt();
    UUID inquiryId = session.getInquiryId();
    UUID subjectId = session.getCustomerSubjectId();
    CustomerRentalSessionRepository repository = mock(CustomerRentalSessionRepository.class);
    when(repository.findByInquiryIdForUpdate(inquiryId))
        .thenReturn(Optional.of(session));
    OffsetDateTime recoveryTime = session.getRecoveryNextAttemptAt();

    for (int attempt = 1; attempt <= 8; attempt++) {
      when(repository.currentDatabaseTimestamp()).thenReturn(recoveryTime.toInstant());
      CustomerRentalSessionStore store =
          new CustomerRentalSessionStore(repository, mock(LogisticsTransactionLock.class));
      var claim =
          store.claimPendingBooking(subjectId, inquiryId).orElseThrow();
      var failure =
          store.failCheckoutRecovery(
              subjectId,
              inquiryId,
              claim.leaseToken(),
              "DEPENDENCY_PENDING");
      assertThat(failure.attemptCount()).isEqualTo(attempt);
      assertThat(failure.quarantined()).isEqualTo(attempt == 8);
      if (!failure.quarantined()) {
        recoveryTime = failure.nextAttemptAt();
      }
    }

    assertThat(session.getRecoveryQuarantinedAt()).isNotNull();
    when(repository.currentDatabaseTimestamp()).thenReturn(recoveryTime.plusHours(1).toInstant());
    CustomerRentalSessionStore afterQuarantine =
        new CustomerRentalSessionStore(repository, mock(LogisticsTransactionLock.class));
    assertThat(
            afterQuarantine.claimPendingBooking(subjectId, inquiryId))
        .isEmpty();
  }

  private static CustomerRentalSessionStore store(
      CustomerRentalSession session, UUID inquiryId) {
    CustomerRentalSessionRepository repository = mock(CustomerRentalSessionRepository.class);
    when(repository.findByInquiryIdForUpdate(inquiryId))
        .thenReturn(Optional.of(session));
    return new CustomerRentalSessionStore(repository, mock(LogisticsTransactionLock.class));
  }

  private static CustomerRentalSession pendingReceipt() {
    CustomerRentalSession session =
        CustomerRentalSession.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    UUID commandKey = UUID.randomUUID();
    session.beginCheckout(0, commandKey, HASH);
    session.recordPendingBooking(
        commandKey,
        HASH,
        UUID.randomUUID(),
        null,
        "presentation-token",
        OffsetDateTime.parse("2026-08-31T09:00:00Z"));
    return session;
  }
}
