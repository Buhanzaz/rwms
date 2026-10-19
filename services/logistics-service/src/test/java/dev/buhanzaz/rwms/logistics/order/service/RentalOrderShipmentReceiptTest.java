package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** Keeps receipt preflight on the same checksum, order and visibility fences as actual replay. */
class RentalOrderShipmentReceiptTest {
  private static final UUID ORDER = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private final OrderActor actor =
      new OrderActor(
          UUID.randomUUID(),
          "SYSTEM_ADMIN",
          "Planner",
          Set.of(),
          Set.of(),
          true,
          false,
          true,
          true);
  private final CreateOrderRentalShipmentRequest request =
      new CreateOrderRentalShipmentRequest(
          7L, "Driver", UUID.randomUUID(), LocalDate.now().plusDays(3), List.of(UUID.randomUUID()));
  private final RentalOrderCommandStore store = mock(RentalOrderCommandStore.class);
  private final OrderAuthorizer access = mock(OrderAuthorizer.class);
  private final RentalOrderReadService reads = mock(RentalOrderReadService.class);
  private final LogisticsDocumentService documents = mock(LogisticsDocumentService.class);
  private final RentalOrderInventorySourcePolicy inventorySources =
      mock(RentalOrderInventorySourcePolicy.class);
  private final RentalOrderShipmentService service =
      new RentalOrderShipmentService(store, access, reads, documents, inventorySources);

  @Test
  void missingReceiptDoesNotReadMutableOrderFacts() {
    assertThat(service.hasRentalShipmentReceipt(actor, ORDER, KEY, request)).isFalse();
    verifyNoInteractions(store, access, reads, inventorySources);
  }

  @Test
  void exactReceiptAndActualReplayShareChecksumAndReauthorizeVisibility() {
    RentalOrder order = mock(RentalOrder.class);
    when(store.requiredOrder(ORDER)).thenReturn(order);
    when(documents.rentalOrderShipmentReceiptOrderId(eq(actor.subjectId()), eq(KEY), any()))
        .thenReturn(ORDER);
    LogisticsDocumentView view = mock(LogisticsDocumentView.class);
    when(view.rentalOrderId()).thenReturn(ORDER);
    when(documents.replayRentalOrderShipment(eq(actor.subjectId()), eq(KEY), any()))
        .thenReturn(new LogisticsDocumentService.CreateResult(view, true));

    assertThat(service.hasRentalShipmentReceipt(actor, ORDER, KEY, request)).isTrue();
    assertThat(service.replayRentalShipment(actor, ORDER, KEY, request).replayed()).isTrue();

    ArgumentCaptor<String> classifiedChecksum = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> replayChecksum = ArgumentCaptor.forClass(String.class);
    verify(documents)
        .rentalOrderShipmentReceiptOrderId(
            eq(actor.subjectId()), eq(KEY), classifiedChecksum.capture());
    verify(documents)
        .replayRentalOrderShipment(eq(actor.subjectId()), eq(KEY), replayChecksum.capture());
    assertThat(classifiedChecksum.getValue()).isEqualTo(replayChecksum.getValue());
    verify(access, org.mockito.Mockito.times(2)).requireVisible(actor, order);
    verifyNoInteractions(order, reads, inventorySources);
  }

  @Test
  void wrongOrderAndLostVisibilityCannotUseAReceiptAsAdmissionProof() {
    when(documents.rentalOrderShipmentReceiptOrderId(eq(actor.subjectId()), eq(KEY), any()))
        .thenReturn(UUID.randomUUID(), ORDER);
    assertThatThrownBy(() -> service.hasRentalShipmentReceipt(actor, ORDER, KEY, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    verifyNoInteractions(store, access);
    RentalOrder order = mock(RentalOrder.class);
    when(store.requiredOrder(ORDER)).thenReturn(order);
    doThrow(new AccessDeniedException("hidden")).when(access).requireVisible(actor, order);
    assertThatThrownBy(() -> service.hasRentalShipmentReceipt(actor, ORDER, KEY, request))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(order, reads, inventorySources);
  }
}
