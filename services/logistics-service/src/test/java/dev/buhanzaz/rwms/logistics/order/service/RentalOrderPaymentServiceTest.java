package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerRentalSessionStore;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.repository.PresentationBookingRepository;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.order.api.ConfirmOrderPaymentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentSource;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData.Kind;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData.Line;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderPaymentReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

class RentalOrderPaymentServiceTest {
  private static final OffsetDateTime START = OffsetDateTime.parse("2026-09-05T17:00:00Z");
  private static final UUID ORDER = UUID.randomUUID();
  private static final UUID MANAGER = UUID.randomUUID();
  private static final UUID WAREHOUSE = UUID.randomUUID();
  private static final UUID BOOKING = UUID.randomUUID();
  private static final UUID PRESENTATION = UUID.randomUUID();
  private final RentalOrderCommandStore orders = mock(RentalOrderCommandStore.class);
  private final RentalOrderPaymentReceiptStore receipts =
      mock(RentalOrderPaymentReceiptStore.class);
  private final RentalOrderPaymentReceiptRepository clock =
      mock(RentalOrderPaymentReceiptRepository.class);
  private final OrderAuditService audit = mock(OrderAuditService.class);
  private final RentalOrderEditabilityService editability =
      mock(RentalOrderEditabilityService.class);
  private final CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
  private final ClientPresentationService presentations = mock(ClientPresentationService.class);
  private final PresentationBookingRepository bookings = mock(PresentationBookingRepository.class);
  private final RentalOrderPaymentService service =
      new RentalOrderPaymentService(
          orders,
          new OrderAuthorizer(new MockEnvironment(), false),
          receipts,
          clock,
          Mappers.getMapper(RentalOrderResponseMapper.class),
          audit,
          editability,
          sessions,
          new CustomerAuthorizer(),
          presentations,
          bookings);
  private final OrderActor manager = actor("RENTAL_MANAGER", Set.of(WAREHOUSE), Set.of(WAREHOUSE));
  private RentalOrder order;
  private RentalOrderReceiptData bill;

  @BeforeEach
  void setUp() {
    order =
        RentalOrder.create(
            "ORD-1",
            mock(OrderClient.class),
            MANAGER,
            "Manager",
            MANAGER,
            "Manager",
            "RENTAL_MANAGER",
            "+79990000001",
            null,
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(order, "id", ORDER);
    ReflectionTestUtils.setField(order, "version", 7L);
    order.selectWarehouse(WAREHOUSE);
    order.replaceClientDeliveryDetails("Address", null, null, List.of());
    order.saveForFulfillment();
    order.startPaymentReservation(START);
    bill =
        new RentalOrderReceiptData(
            1,
            ORDER,
            "ORD-1",
            START,
            "RUB",
            false,
            List.of(
                new Line(
                    Kind.CABIN, UUID.randomUUID(), null, "Бытовка", "1", 2L, "1000", "2000", 3L)),
            "2000");
    when(orders.requiredOrder(ORDER)).thenReturn(order);
    when(orders.lockedOrder(ORDER)).thenReturn(order);
    when(receipts.find(ORDER)).thenReturn(Optional.of(bill));
    when(clock.currentDatabaseTimestamp()).thenReturn(START.plusMinutes(1).toInstant());
  }

  @Test
  void readReturnsOriginalBillAndDatabaseTimeWithoutMutatingTheOrder() {
    var response = service.get(manager, ORDER);
    assertThat(response.state()).isEqualTo(RentalOrderPaymentState.PENDING);
    assertThat(response.canConfirm()).isTrue();
    assertThat(response.serverTime()).isEqualTo(START.plusMinutes(1));
    assertThat(response.expiresAt()).isEqualTo(START.plusMinutes(5));
    assertThat(response.receipt().totalRubles()).isEqualTo("2000");
    assertThat(response.receipt().lines().getFirst().unitPriceRubles()).isEqualTo("1000");
    verify(orders, never()).persist(any());
    verifyNoInteractions(audit);
  }

  @Test
  void unknownHistoricalBillNeverBecomesAFreeOrConfirmableReceipt() {
    ReflectionTestUtils.setField(order, "paymentState", null);
    ReflectionTestUtils.setField(order, "paymentStartedAt", null);
    ReflectionTestUtils.setField(order, "paymentExpiresAt", null);
    when(receipts.find(ORDER)).thenReturn(Optional.empty());
    var response = service.get(manager, ORDER);
    assertThat(response.state()).isNull();
    assertThat(response.receipt()).isNull();
    assertThat(response.canConfirm()).isFalse();
    assertProblem("ORDER_RECEIPT_UNAVAILABLE", () -> confirm(7));
    verify(orders, never()).persist(any());
  }

  @Test
  void managerConfirmationRecordsOnlyAuthenticatedProvenance() {
    var response = confirm(7);
    assertThat(response.state()).isEqualTo(RentalOrderPaymentState.CONFIRMED);
    assertThat(response.source()).isEqualTo(RentalOrderPaymentSource.MANAGER_CONFIRMATION);
    assertThat(response.canConfirm()).isFalse();
    assertThat(order.getPaymentConfirmedBySubjectId()).isEqualTo(MANAGER);
    assertThat(order.getPaymentConfirmedByBookingId()).isNull();
    verify(orders).persist(order);
    verify(orders)
        .remember(
            eq(manager), eq("CONFIRM_PAYMENT_MANAGER_CONFIRMATION"), any(), anyString(), eq(order));
  }

  @Test
  void warehouseReadAndWriteAuthorizationPrecedeReceiptAccess() {
    var noRead = actor("RENTAL_MANAGER", Set.of(), Set.of());
    assertThatThrownBy(() -> service.get(noRead, ORDER)).isInstanceOf(AccessDeniedException.class);
    var readOnly = actor("RENTAL_MANAGER", Set.of(WAREHOUSE), Set.of());
    assertThatThrownBy(
            () ->
                service.confirmManager(
                    readOnly, ORDER, UUID.randomUUID(), new ConfirmOrderPaymentRequest(7L)))
        .isInstanceOf(AccessDeniedException.class);
    var viewer = actor("VIEWER", Set.of(WAREHOUSE), Set.of(WAREHOUSE));
    assertThatThrownBy(
            () ->
                service.confirmManager(
                    viewer, ORDER, UUID.randomUUID(), new ConfirmOrderPaymentRequest(7L)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(receipts, audit);
  }

  @Test
  void versionConflictAndOpenCancellationCannotConfirm() {
    assertProblem("ORDER_VERSION_CONFLICT", () -> confirm(6));
    doThrow(RentalOrderProblems.conflict("CUSTOMER_BOOKING_CANCELLATION_PENDING", "Cancelling"))
        .when(orders)
        .lockedOrder(ORDER);
    assertProblem("CUSTOMER_BOOKING_CANCELLATION_PENDING", () -> confirm(7));
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.PENDING);
    verifyNoInteractions(audit);
  }

  @Test
  void databaseDeadlineDisablesReadAffordanceAndRejectsConfirmation() {
    when(clock.currentDatabaseTimestamp()).thenReturn(START.plusMinutes(5).toInstant());
    assertThat(service.get(manager, ORDER).canConfirm()).isFalse();
    assertProblem("ORDER_PAYMENT_NOT_PENDING", () -> confirm(7));
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.PENDING);
    verifyNoInteractions(audit);
  }

  @Test
  void anAlreadyClaimedExpiryCannotBePaidEvenWithAnEarlierClock() {
    order.beginPaymentExpiry(START.plusMinutes(5));
    assertProblem("ORDER_PAYMENT_NOT_PENDING", () -> confirm(7));
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.EXPIRING);
    verifyNoInteractions(audit);
  }

  @Test
  void publicPaymentRequiresExactPresentationRevisionAndCompletedBooking() {
    var presentation = publicProof();
    when(presentation.getRevision()).thenReturn(5L);
    assertProblem(
        "PRESENTATION_BOOKING_NOT_FOUND", () -> service.getPresentation("token", BOOKING));
    when(presentation.getRevision()).thenReturn(4L);
    var booking = bookings.findById(BOOKING).orElseThrow();
    when(booking.getState()).thenReturn(PresentationBookingState.PENDING);
    assertProblem(
        "ORDER_PAYMENT_BOOKING_PENDING",
        () ->
            service.confirmPresentation(
                "token", BOOKING, UUID.randomUUID(), new ConfirmOrderPaymentRequest(7L)));
    verifyNoInteractions(receipts, audit);
  }

  @Test
  void publicPaymentRecordsBookingAndNeverImpersonatesTheOrderManager() {
    publicProof();
    var response =
        service.confirmPresentation(
            "token", BOOKING, UUID.randomUUID(), new ConfirmOrderPaymentRequest(7L));
    assertThat(response.source()).isEqualTo(RentalOrderPaymentSource.PRESENTATION_TEST);
    assertThat(order.getPaymentConfirmedBySubjectId()).isNull();
    assertThat(order.getPaymentConfirmedByBookingId()).isEqualTo(BOOKING);
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.CONFIRMED);
  }

  private ClientPresentation publicProof() {
    var presentation = mock(ClientPresentation.class);
    when(presentations.resolve("token")).thenReturn(presentation);
    when(presentation.getId()).thenReturn(PRESENTATION);
    when(presentation.getRevision()).thenReturn(4L);
    when(presentation.getBookedOrderId()).thenReturn(ORDER);
    var booking = mock(PresentationBooking.class);
    when(bookings.findById(BOOKING)).thenReturn(Optional.of(booking));
    when(booking.getPresentationId()).thenReturn(PRESENTATION);
    when(booking.getPresentationRevision()).thenReturn(4L);
    when(booking.getOrderId()).thenReturn(ORDER);
    when(booking.getState()).thenReturn(PresentationBookingState.COMPLETED);
    return presentation;
  }

  private dev.buhanzaz.rwms.logistics.order.api.OrderPaymentResponse confirm(long version) {
    return service.confirmManager(
        manager, ORDER, UUID.randomUUID(), new ConfirmOrderPaymentRequest(version));
  }

  private static void assertProblem(
      String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            OrderProblemException.class, error -> assertThat(error.code()).isEqualTo(code));
  }

  private static OrderActor actor(String role, Set<UUID> readable, Set<UUID> editable) {
    return new OrderActor(MANAGER, role, "Manager", readable, editable, false, false, true, true);
  }
}
