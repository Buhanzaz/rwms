package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationOperation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingMutationRepository;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCommandRepository;
import dev.buhanzaz.rwms.logistics.order.repository.OrderCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Proves that a customer cancellation checkpoint fences every ordinary order command. */
class RentalOrderCommandStoreCustomerCancellationFenceTest {
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000941");

  private final RentalOrderRepository orders = mock(RentalOrderRepository.class);
  private final OrderCommandReceiptRepository receipts = mock(OrderCommandReceiptRepository.class);
  private final RentalOrderMutationCommandRepository orderMutations =
      mock(RentalOrderMutationCommandRepository.class);
  private final CustomerBookingMutationRepository bookingMutations =
      mock(CustomerBookingMutationRepository.class);
  private final LogisticsTransactionLock transactionLock = mock(LogisticsTransactionLock.class);
  private final RentalOrderCommandStore store =
      new RentalOrderCommandStore(
          orders, receipts, orderMutations, bookingMutations, transactionLock);

  @Test
  void ordinaryCommandLocksOrderBeforeRejectingOpenCustomerCancellation() {
    RentalOrder order = mock(RentalOrder.class);
    when(orders.findForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    when(bookingMutations.existsByOrderIdAndOperationAndStateIn(
            eq(ORDER_ID),
            eq(CustomerBookingMutationOperation.CANCEL),
            eq(
                Set.of(
                    CustomerBookingMutationState.PENDING,
                    CustomerBookingMutationState.QUARANTINED))))
        .thenReturn(true);

    assertThatThrownBy(() -> store.lockedOrder(actor(), ORDER_ID))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.code()).isEqualTo("CUSTOMER_BOOKING_CANCELLATION_PENDING");
              assertThat(problem.getMessage())
                  .isEqualTo(
                      "Бронирование уже отменяется; новые операции заказа временно недоступны");
            });

    InOrder sequence = inOrder(orders, bookingMutations);
    sequence.verify(orders).findForUpdate(ORDER_ID);
    sequence
        .verify(bookingMutations)
        .existsByOrderIdAndOperationAndStateIn(
            ORDER_ID,
            CustomerBookingMutationOperation.CANCEL,
            Set.of(CustomerBookingMutationState.PENDING, CustomerBookingMutationState.QUARANTINED));
    verify(orderMutations, never()).existsByOrder_IdAndStateIn(eq(ORDER_ID), any());
  }

  @Test
  void orderOwnedRecoveryBypassesCustomerFenceAndCanFinishCancellation() {
    RentalOrder order = mock(RentalOrder.class);
    when(orders.findForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

    assertThat(store.recoveryOrder(ORDER_ID)).isSameAs(order);

    verify(orders).findForUpdate(ORDER_ID);
    verifyNoInteractions(bookingMutations, orderMutations, receipts, transactionLock);
  }

  private static OrderActor actor() {
    return new OrderActor(
        UUID.fromString("00000000-0000-0000-0000-000000000943"),
        "SYSTEM_ADMIN",
        "admin",
        Set.of(),
        Set.of(),
        true,
        false,
        true,
        true);
  }
}
