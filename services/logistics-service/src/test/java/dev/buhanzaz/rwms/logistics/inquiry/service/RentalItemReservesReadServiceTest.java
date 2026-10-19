package dev.buhanzaz.rwms.logistics.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalItemReservesResponse.Entry;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalItemReservesResponse.Kind;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalItemReservesResponse.Source;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException.FailureKind;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.RentalItemReserveSnapshot;
import dev.buhanzaz.rwms.logistics.integration.RentalItemReserveSnapshot.OrderReservation;
import dev.buhanzaz.rwms.logistics.integration.RentalItemReserveSnapshot.SelectionHold;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

class RentalItemReservesReadServiceTest {
  private final UUID cabin = UUID.randomUUID();
  private final UUID warehouse = UUID.randomUUID();
  private final UUID manager = UUID.randomUUID();
  private final UUID scope = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final OffsetDateTime now = OffsetDateTime.parse("2026-09-05T10:00:00Z");
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final RentalInquiryRepository inquiries = mock(RentalInquiryRepository.class);
  private final ClientPresentationRepository presentations =
      mock(ClientPresentationRepository.class);
  private final RentalOrderRepository orders = mock(RentalOrderRepository.class);
  private final RentalItemReservesReadService reads =
      new RentalItemReservesReadService(
          dependencies,
          new OrderAuthorizer(new MockEnvironment(), false),
          inquiries,
          presentations,
          orders);

  @Test
  void warehouseAuthorizationPrecedesAnyDependencyOrLocalRead() {
    var actor =
        new OrderActor(
            manager, "RENTAL_MANAGER", "Менеджер", Set.of(), Set.of(), false, false, true, true);
    assertThatThrownBy(() -> reads.read(actor, cabin, warehouse))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(dependencies, inquiries, presentations, orders);
  }

  @Test
  void ownInquiryRevealsExistingClientAndManagerButNoInventedOrder() {
    snapshot(List.of(hold("RENTAL_MANAGER")), null);
    inquiry(manager, "RENTAL_MANAGER", null);
    var result = reads.read(actor(manager, false, false), cabin, warehouse);
    assertThat(result.serverTime()).isEqualTo(now);
    assertThat(result.reserves())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.kind()).isEqualTo(Kind.SELECTION_HOLD);
              assertThat(entry.source()).isEqualTo(Source.MANAGER);
              assertThat(entry.clientDisplayName()).isEqualTo("Клиент заявки");
              assertThat(entry.managerDisplayName()).isEqualTo("Менеджер заявки");
              assertThat(entry.expiresAt()).isEqualTo(now.plusMinutes(30));
              assertThat(entry.orderId()).isNull();
              assertThat(entry.canOpenOrder()).isFalse();
            });
  }

  @Test
  void foreignInquiryAndCustomerCartStayPrivateEvenForAdministrators() {
    for (String role : List.of("RENTAL_MANAGER", "CUSTOMER")) {
      snapshot(List.of(hold(role)), null);
      inquiry(UUID.randomUUID(), role, null);
      for (OrderActor actor :
          List.of(
              actor(manager, false, false),
              actor(manager, true, false),
              actor(manager, false, true))) {
        var entry = reads.read(actor, cabin, warehouse).reserves().getFirst();
        assertHidden(entry);
        assertThat(entry.source())
            .isEqualTo("CUSTOMER".equals(role) ? Source.CUSTOMER : Source.MANAGER);
        assertThat(entry.expiresAt()).isEqualTo(now.plusMinutes(30));
      }
    }
  }

  @Test
  void linkedVisibleOrderProvidesMetadataButForeignManagerSeesOnlyOccupancy() {
    snapshot(List.of(hold("CUSTOMER")), null);
    inquiry(UUID.randomUUID(), "CUSTOMER", orderId);
    order(manager, "CUSTOMER", RentalOrderPaymentState.PENDING);
    var visible = reads.read(actor(manager, false, false), cabin, warehouse).reserves().getFirst();
    assertThat(visible.clientDisplayName()).isEqualTo("Клиент заказа");
    assertThat(visible.managerDisplayName()).isNull();
    assertThat(visible.orderId()).isEqualTo(orderId);
    assertThat(visible.orderNumber()).isEqualTo("З-123");
    assertThat(visible.canOpenOrder()).isTrue();
    assertHidden(
        reads.read(actor(UUID.randomUUID(), false, false), cabin, warehouse).reserves().getFirst());
  }

  @Test
  void legacyPresentationScopeResolvesTheExistingInquiry() {
    snapshot(List.of(hold("RENTAL_MANAGER")), null);
    UUID inquiryId = UUID.randomUUID();
    ClientPresentation presentation = mock(ClientPresentation.class);
    when(presentation.getInquiryId()).thenReturn(inquiryId);
    when(presentations.findById(scope)).thenReturn(Optional.of(presentation));
    var inquiry = inquiry(manager, "RENTAL_MANAGER", null);
    when(inquiries.findById(scope)).thenReturn(Optional.empty());
    when(inquiries.findById(inquiryId)).thenReturn(Optional.of(inquiry));
    assertThat(
            reads
                .read(actor(manager, false, false), cabin, warehouse)
                .reserves()
                .getFirst()
                .clientDisplayName())
        .isEqualTo("Клиент заявки");
  }

  @Test
  void manualHoldHasOnlyItsOwnActorDisplayNameWithoutAnInventedClient() {
    snapshot(List.of(hold("RENTAL_MANAGER")), null);
    var entry = reads.read(actor(manager, false, false), cabin, warehouse).reserves().getFirst();
    assertThat(entry.managerDisplayName()).isEqualTo("Менеджер");
    assertThat(entry.clientDisplayName()).isNull();
    assertThat(entry.canOpenOrder()).isFalse();
    assertHidden(
        reads.read(actor(UUID.randomUUID(), false, true), cabin, warehouse).reserves().getFirst());
  }

  @Test
  void orderReserveUsesPaymentDeadlineAndDoesNotDisappearDuringExpiryRecovery() {
    var reservation = reservation();
    snapshot(List.of(), reservation);
    var order = order(manager, "CUSTOMER", RentalOrderPaymentState.EXPIRING);
    when(order.getPaymentExpiresAt()).thenReturn(now.minusMinutes(1));
    var visible = reads.read(actor(manager, false, false), cabin, warehouse).reserves().getFirst();
    assertThat(visible.kind()).isEqualTo(Kind.ORDER_RESERVATION);
    assertThat(visible.source()).isEqualTo(Source.CUSTOMER);
    assertThat(visible.expiresAt()).isEqualTo(now.minusMinutes(1));
    assertThat(visible.paymentState()).isEqualTo(RentalOrderPaymentState.EXPIRING);
    assertThat(visible.canOpenOrder()).isTrue();
    var hidden =
        reads.read(actor(UUID.randomUUID(), false, false), cabin, warehouse).reserves().getFirst();
    assertHidden(hidden);
    assertThat(hidden.expiresAt()).isEqualTo(now.minusMinutes(1));
    when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.CONFIRMED);
    assertThat(
            reads
                .read(actor(manager, false, false), cabin, warehouse)
                .reserves()
                .getFirst()
                .expiresAt())
        .isNull();
  }

  @Test
  void orderWarehouseGrantIsRequiredInAdditionToPhysicalCabinWarehouseGrant() {
    snapshot(List.of(), reservation());
    var order = order(manager, "RENTAL_MANAGER", RentalOrderPaymentState.PENDING);
    when(order.getWarehouseId()).thenReturn(UUID.randomUUID());
    assertHidden(reads.read(actor(manager, false, false), cabin, warehouse).reserves().getFirst());
    assertHidden(reads.read(actor(manager, true, false), cabin, warehouse).reserves().getFirst());
    assertThat(
            reads
                .read(actor(manager, false, true), cabin, warehouse)
                .reserves()
                .getFirst()
                .canOpenOrder())
        .isTrue();
  }

  @Test
  void missingLocalOrderKeepsAssetOccupancyWithoutNavigationOrPaymentData() {
    snapshot(List.of(), reservation());
    var entry = reads.read(actor(manager, false, false), cabin, warehouse).reserves().getFirst();
    assertHidden(entry);
    assertThat(entry.expiresAt()).isEqualTo(now.plusHours(1));
  }

  @Test
  void dependencyFailuresAreExplicitAndNeverAnEmptyList() {
    for (FailureKind kind : FailureKind.values()) {
      doThrow(new LogisticsDependencyException(kind, "private failure"))
          .when(dependencies)
          .readRentalItemReserves(cabin, warehouse);
      assertProblem(HttpStatus.SERVICE_UNAVAILABLE, "RENTAL_ITEM_RESERVES_UNAVAILABLE");
    }
    doThrow(
            new LogisticsDependencyException(
                FailureKind.PERMANENT_REJECTION, "ASSET_NOT_FOUND", "private", null))
        .when(dependencies)
        .readRentalItemReserves(cabin, warehouse);
    assertProblem(HttpStatus.NOT_FOUND, "RENTAL_ITEM_NOT_FOUND");
    verifyNoInteractions(inquiries, presentations, orders);
  }

  @Test
  void malformedOrMismatchedAssetSnapshotsFailClosed() {
    var valid = hold("CUSTOMER");
    for (RentalItemReserveSnapshot bad :
        Arrays.asList(
            null,
            new RentalItemReserveSnapshot(UUID.randomUUID(), warehouse, now, List.of(), null),
            new RentalItemReserveSnapshot(cabin, UUID.randomUUID(), now, List.of(), null),
            new RentalItemReserveSnapshot(cabin, warehouse, null, List.of(), null),
            new RentalItemReserveSnapshot(cabin, warehouse, now, null, null),
            new RentalItemReserveSnapshot(
                cabin, warehouse, now, Arrays.asList((SelectionHold) null), null),
            new RentalItemReserveSnapshot(cabin, warehouse, now, List.of(valid, valid), null),
            new RentalItemReserveSnapshot(
                cabin,
                warehouse,
                now,
                List.of(
                    new SelectionHold(
                        valid.holdId(),
                        0L,
                        scope,
                        manager,
                        null,
                        now.minusMinutes(1),
                        now.plusMinutes(1))),
                null),
            new RentalItemReserveSnapshot(
                cabin,
                warehouse,
                now,
                List.of(
                    new SelectionHold(
                        valid.holdId(), 0L, scope, manager, "CUSTOMER", now.minusMinutes(1), now)),
                null),
            new RentalItemReserveSnapshot(
                cabin,
                warehouse,
                now,
                List.of(),
                new OrderReservation(
                    UUID.randomUUID(), null, orderId, manager, "CUSTOMER", now, null)))) {
      when(dependencies.readRentalItemReserves(cabin, warehouse)).thenReturn(bad);
      assertProblem(HttpStatus.BAD_GATEWAY, "RENTAL_ITEM_RESERVES_INVALID_RESPONSE");
    }
    verifyNoInteractions(inquiries, presentations, orders);
  }

  @Test
  void publicSerializationContainsNoHoldScopeActorIdsOrOpaqueOrderIds() {
    snapshot(List.of(hold("CUSTOMER")), reservation());
    var result = reads.read(actor(UUID.randomUUID(), false, false), cabin, warehouse);
    var json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(result);
    assertThat(json)
        .doesNotContain(
            "holdScopeId",
            "actorSubjectId",
            "actorRole",
            scope.toString(),
            manager.toString(),
            orderId.toString());
    assertThat(result.reserves()).hasSize(2);
  }

  private void snapshot(List<SelectionHold> holds, OrderReservation reservation) {
    when(dependencies.readRentalItemReserves(cabin, warehouse))
        .thenReturn(new RentalItemReserveSnapshot(cabin, warehouse, now, holds, reservation));
  }

  private SelectionHold hold(String role) {
    return new SelectionHold(
        UUID.randomUUID(), 0L, scope, manager, role, now.minusMinutes(1), now.plusMinutes(30));
  }

  private OrderReservation reservation() {
    return new OrderReservation(
        UUID.randomUUID(),
        2L,
        orderId,
        manager,
        "RENTAL_MANAGER",
        now.minusMinutes(1),
        now.plusHours(1));
  }

  private OrderActor actor(UUID subject, boolean localAdmin, boolean globalAdmin) {
    return new OrderActor(
        subject,
        globalAdmin ? "SYSTEM_ADMIN" : localAdmin ? "WMS_ADMIN" : "RENTAL_MANAGER",
        "Менеджер",
        Set.of(warehouse),
        Set.of(warehouse),
        globalAdmin,
        localAdmin,
        true,
        true);
  }

  private RentalInquiry inquiry(UUID owner, String role, UUID linkedOrder) {
    var inquiry = mock(RentalInquiry.class);
    var client = mock(OrderClient.class);
    when(client.getDisplayName()).thenReturn("Клиент заявки");
    when(inquiry.getClient()).thenReturn(client);
    when(inquiry.getWarehouseId()).thenReturn(warehouse);
    when(inquiry.getManagerId()).thenReturn(owner);
    when(inquiry.getManagerRole()).thenReturn(role);
    when(inquiry.getManagerDisplayName()).thenReturn("Менеджер заявки");
    when(inquiry.getRentalOrderId()).thenReturn(linkedOrder);
    when(inquiries.findById(scope)).thenReturn(Optional.of(inquiry));
    return inquiry;
  }

  private RentalOrder order(UUID owner, String originRole, RentalOrderPaymentState payment) {
    var order = mock(RentalOrder.class);
    var client = mock(OrderClient.class);
    when(client.getDisplayName()).thenReturn("Клиент заказа");
    when(order.getClient()).thenReturn(client);
    when(order.getId()).thenReturn(orderId);
    when(order.getWarehouseId()).thenReturn(warehouse);
    when(order.getManagerId()).thenReturn(owner);
    when(order.getManagerDisplayName()).thenReturn("Менеджер заказа");
    when(order.getCreatedByRole()).thenReturn(originRole);
    when(order.getOrderNumber()).thenReturn("З-123");
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getPaymentState()).thenReturn(payment);
    when(order.getPaymentExpiresAt()).thenReturn(now.plusDays(1));
    when(orders.findWithClientById(orderId)).thenReturn(Optional.of(order));
    return order;
  }

  private void assertProblem(HttpStatus status, String code) {
    assertThatThrownBy(() -> reads.read(actor(manager, false, false), cabin, warehouse))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> {
              assertThat(error.status()).isEqualTo(status);
              assertThat(error.code()).isEqualTo(code);
            });
  }

  private static void assertHidden(Entry entry) {
    assertThat(entry.clientDisplayName()).isNull();
    assertThat(entry.managerDisplayName()).isNull();
    assertThat(entry.orderId()).isNull();
    assertThat(entry.orderNumber()).isNull();
    assertThat(entry.orderStatus()).isNull();
    assertThat(entry.paymentState()).isNull();
    assertThat(entry.canOpenOrder()).isFalse();
  }
}
