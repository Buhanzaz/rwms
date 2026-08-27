package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

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
    when(repository.findByInquiryId(inquiryId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    CustomerRentalSessionStore store = new CustomerRentalSessionStore(repository, lock);

    store.create(inquiryId, subjectId, warehouseId);

    InOrder order = inOrder(lock, repository);
    order.verify(lock).acquire("customer-rental-session:" + inquiryId);
    order.verify(repository).findByInquiryId(inquiryId);
  }

  @Test
  void pendingCheckoutRecoveryLoadsOnlyTheOldestHundredRows() {
    CustomerRentalSessionRepository repository = mock(CustomerRentalSessionRepository.class);
    CustomerRentalSessionStore store =
        new CustomerRentalSessionStore(repository, mock(LogisticsTransactionLock.class));
    when(repository.findAllByStateOrderByUpdatedAtAscIdAsc(
            org.mockito.ArgumentMatchers.eq(
                dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState.CHECKOUT_PENDING),
            org.mockito.ArgumentMatchers.any(Pageable.class)))
        .thenReturn(List.of());

    assertThat(store.pendingBookings()).isEmpty();

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(repository)
        .findAllByStateOrderByUpdatedAtAscIdAsc(
            org.mockito.ArgumentMatchers.eq(
                dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState.CHECKOUT_PENDING),
            page.capture());
    assertThat(page.getValue().getPageNumber()).isZero();
    assertThat(page.getValue().getPageSize()).isEqualTo(100);
  }

  private static CustomerRentalSessionStore store(
      CustomerRentalSession session, UUID inquiryId) {
    CustomerRentalSessionRepository repository = mock(CustomerRentalSessionRepository.class);
    when(repository.findByInquiryIdForUpdate(inquiryId)).thenReturn(Optional.of(session));
    return new CustomerRentalSessionStore(repository, mock(LogisticsTransactionLock.class));
  }
}
