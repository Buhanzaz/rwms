package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleService;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentRemovalRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRescheduleDecisionCode;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRecoveryOperation;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSaga;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSagaState;
import dev.buhanzaz.rwms.logistics.planning.repository.PlanningPublishedRescheduleSagaRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Verifies creation-side admission before a published task-board hold can be prepared. */
class PlanningPublishedRescheduleSagaAdmissionTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void routingPreparationRunsBeforeTheOwnerTransactionAndItsLocks() throws Exception {
    TrackingTransactionManager transactions = new TrackingTransactionManager();
    Fixture fixture = fixture(transactions);
    String requestJson = fixture.json.writeValueAsString(fixture.request);
    String requestHash =
        LogisticsEventStore.sha256(
            fixture.json.writeValueAsBytes(
                Map.of("identity", fixture.orderId, "request", fixture.request)));
    PlanningPublishedRescheduleSaga exact =
        saga(
            fixture.commandId,
            fixture,
            fixture.sourcePlanId,
            PlanningPublishedRescheduleSagaState.PREPARED,
            requestHash,
            requestJson);
    when(fixture.sagas.findById(fixture.commandId)).thenReturn(Optional.of(exact));
    when(fixture.bookingLifecycle.preparePublishedForDispatcher(
            any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
        .thenAnswer(
            ignored -> {
              org.assertj.core.api.Assertions.assertThat(transactions.active()).isFalse();
              throw new AssertionError("stop after routing preparation");
            });

    assertThatThrownBy(
            () ->
                fixture.service.reschedule(
                    fixture.orderId, fixture.commandId, fixture.request))
        .isInstanceOf(AssertionError.class)
        .hasMessage("stop after routing preparation");

    verify(fixture.sagas, never()).findForUpdate(any());
    org.assertj.core.api.Assertions.assertThat(transactions.active()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = PlanningPublishedRescheduleSagaState.class,
      names = {"COMPLETE", "RELEASED"},
      mode = EnumSource.Mode.EXCLUDE)
  void anotherActiveSagaForTheBookingIsRejectedBeforeInsertOrRemotePrepare(
      PlanningPublishedRescheduleSagaState state) throws Exception {
    Fixture fixture = fixture();
    PlanningPublishedRescheduleSaga other =
        saga(
            UUID.randomUUID(),
            fixture,
            UUID.randomUUID(),
            state,
            "a".repeat(64),
            "{}");
    when(fixture.sagas.findAllForBookingAdmission(fixture.orderId, fixture.bookingId))
        .thenReturn(List.of(other));

    assertThatThrownBy(
            () ->
                fixture.service.reschedule(
                    fixture.orderId, fixture.commandId, fixture.request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              org.assertj.core.api.Assertions.assertThat(problem.status())
                  .isEqualTo(HttpStatus.CONFLICT);
              org.assertj.core.api.Assertions.assertThat(problem.code())
                  .isEqualTo("CUSTOMER_BOOKING_PUBLISHED_CHANGE_IN_PROGRESS");
            });

    InOrder admission = inOrder(fixture.sessions, fixture.sagas);
    admission.verify(fixture.sessions).findByBookingIdForUpdate(fixture.bookingId);
    admission
        .verify(fixture.sagas)
        .findAllForBookingAdmission(fixture.orderId, fixture.bookingId);
    verify(fixture.sagas, never()).saveAndFlush(any());
    verify(fixture.dependencies, never()).preparePlanningReschedule(any(), any(), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = PlanningPublishedRescheduleSagaState.class,
      names = {"COMPLETE", "RELEASED"})
  void terminalSagaForTheBookingAllowsANewIntent(
      PlanningPublishedRescheduleSagaState state) throws Exception {
    Fixture fixture = fixture();
    PlanningPublishedRescheduleSaga terminal =
        saga(
            UUID.randomUUID(),
            fixture,
            UUID.randomUUID(),
            state,
            "a".repeat(64),
            "{}");
    when(fixture.sagas.findAllForBookingAdmission(fixture.orderId, fixture.bookingId))
        .thenReturn(List.of(terminal));
    when(fixture.sagas.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              PlanningPublishedRescheduleSaga created = invocation.getArgument(0);
              created.quarantine("TEST_QUARANTINE", "stop after insert", now());
              return created;
            });

    assertThatThrownBy(
            () ->
                fixture.service.reschedule(
                    fixture.orderId, fixture.commandId, fixture.request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem ->
                org.assertj.core.api.Assertions.assertThat(problem.code())
                    .isEqualTo("TEST_QUARANTINE"));

    verify(fixture.sagas).saveAndFlush(any());
    verify(fixture.dependencies, never()).preparePlanningReschedule(any(), any(), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = PlanningPublishedRescheduleSagaState.class,
      names = {"PENDING", "QUARANTINED"})
  void exactIntentReplayIsNotRejectedByCreationAdmission(
      PlanningPublishedRescheduleSagaState state) throws Exception {
    Fixture fixture = fixture();
    String requestJson = fixture.json.writeValueAsString(fixture.request);
    String requestHash =
        LogisticsEventStore.sha256(
            fixture.json.writeValueAsBytes(
                Map.of("identity", fixture.orderId, "request", fixture.request)));
    PlanningPublishedRescheduleSaga exact =
        saga(
            fixture.commandId,
            fixture,
            fixture.sourcePlanId,
            state,
            requestHash,
            requestJson);
    if (state == PlanningPublishedRescheduleSagaState.PENDING) {
      exact.quarantine("EXACT_REPLAY", "stop exact replay", now());
    }
    when(fixture.sagas.findById(fixture.commandId)).thenReturn(Optional.of(exact));

    assertThatThrownBy(
            () ->
                fixture.service.reschedule(
                    fixture.orderId, fixture.commandId, fixture.request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem ->
                org.assertj.core.api.Assertions.assertThat(problem.code())
                    .isEqualTo(state == PlanningPublishedRescheduleSagaState.PENDING
                        ? "EXACT_REPLAY"
                        : "TEST_QUARANTINE"));

    verify(fixture.sessions, never()).findByBookingIdForUpdate(any());
    verify(fixture.sagas, never()).findAllForBookingAdmission(any(), any());
    verify(fixture.sagas, never()).saveAndFlush(any());
    verify(fixture.dependencies, never()).preparePlanningReschedule(any(), any(), any());
  }

  private static Fixture fixture() throws Exception {
    return fixture(noOpTransactions());
  }

  private static Fixture fixture(PlatformTransactionManager transactions) throws Exception {
    UUID commandId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID sourcePlanId = UUID.randomUUID();
    UUID currentSlotId = UUID.randomUUID();
    UUID selectedSlotId = UUID.randomUUID();
    LocalDate oldDate = LocalDate.of(2026, 9, 3);
    PlanningPublishedAssignmentWithdrawalRequest withdrawal =
        new PlanningPublishedAssignmentWithdrawalRequest(
            sourcePlanId,
            1L,
            2L,
            warehouseId,
            oldDate,
            new PlanningPublishedAssignmentRemovalRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                4L,
                warehouseId,
                oldDate,
                List.of(UUID.randomUUID())),
            List.of(),
            List.of());
    PlanningOrderRescheduleRequest request =
        new PlanningOrderRescheduleRequest(
            7L,
            4L,
            selectedSlotId,
            3L,
            PlanningRescheduleDecisionCode.CUSTOMER_AGREED_ALTERNATIVE,
            UUID.randomUUID(),
            "Клиент согласовал 5 сентября",
            withdrawal);
    PlanningPublishedRescheduleSagaRepository sagas =
        mock(PlanningPublishedRescheduleSagaRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    CustomerRentalSessionRepository sessions = mock(CustomerRentalSessionRepository.class);
    CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    CustomerBookingLifecycleService bookingLifecycle = mock(CustomerBookingLifecycleService.class);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    RentalOrder order = mock(RentalOrder.class);
    CustomerDeliverySlot currentSlot = mock(CustomerDeliverySlot.class);
    CustomerDeliverySlot selectedSlot = mock(CustomerDeliverySlot.class);
    UUID sessionId = UUID.randomUUID();
    when(session.getId()).thenReturn(sessionId);
    when(session.getBookingId()).thenReturn(bookingId);
    when(session.getOrderId()).thenReturn(orderId);
    when(session.getCustomerSubjectId()).thenReturn(customerId);
    when(session.getState()).thenReturn(CustomerSessionState.BOOKED);
    when(session.getVersion()).thenReturn(4L);
    when(session.getDeliverySlotId()).thenReturn(currentSlotId);
    when(session.getWarehouseId()).thenReturn(warehouseId);
    when(sessions.findFirstByOrderIdOrderByCreatedAtAscIdAsc(orderId))
        .thenReturn(Optional.of(session));
    when(sessions.findByBookingIdForUpdate(bookingId)).thenReturn(Optional.of(session));
    when(order.getId()).thenReturn(orderId);
    when(order.getVersion()).thenReturn(7L);
    when(orders.findWithClientById(orderId)).thenReturn(Optional.of(order));
    when(currentSlot.getId()).thenReturn(currentSlotId);
    when(currentSlot.getBookingId()).thenReturn(bookingId);
    when(currentSlot.getWarehouseId()).thenReturn(warehouseId);
    when(currentSlot.getDeliveryDate()).thenReturn(oldDate);
    when(slots.findByOrderIdAndState(orderId, CustomerDeliverySlotState.CONFIRMED))
        .thenReturn(Optional.of(currentSlot));
    when(selectedSlot.getId()).thenReturn(selectedSlotId);
    when(selectedSlot.getWarehouseId()).thenReturn(warehouseId);
    when(selectedSlot.getDeliveryDate()).thenReturn(LocalDate.of(2026, 9, 5));
    when(slots.findById(selectedSlotId)).thenReturn(Optional.of(selectedSlot));
    when(sagas.findById(commandId)).thenReturn(Optional.empty());
    ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    @SuppressWarnings("unchecked")
    ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
    when(clocks.getIfAvailable(any())).thenReturn(CLOCK);
    PlanningPublishedRescheduleSagaService service =
        new PlanningPublishedRescheduleSagaService(
            sagas,
            mock(LogisticsTransactionLock.class),
            orders,
            sessions,
            slots,
            mock(DriverLogisticsTaskRepository.class),
            mock(LogisticsDocumentRepository.class),
            bookingLifecycle,
            mock(RentalOrderPlanningIntegrationService.class),
            mock(LogisticsDocumentService.class),
            dependencies,
            json,
            clocks,
            transactions);
    return new Fixture(
        service,
        sagas,
        sessions,
        dependencies,
        bookingLifecycle,
        json,
        request,
        commandId,
        orderId,
        bookingId,
        customerId,
        warehouseId,
        sourcePlanId,
        oldDate);
  }

  private static PlanningPublishedRescheduleSaga saga(
      UUID id,
      Fixture fixture,
      UUID sourcePlanId,
      PlanningPublishedRescheduleSagaState state,
      String requestHash,
      String requestJson) {
    PlanningPublishedRescheduleSaga saga =
        PlanningPublishedRescheduleSaga.create(
            id,
            fixture.orderId,
            fixture.bookingId,
            fixture.customerId,
            PlanningPublishedRecoveryOperation.RESCHEDULE,
            sourcePlanId,
            1,
            2,
            fixture.warehouseId,
            fixture.oldDate,
            UUID.randomUUID(),
            UUID.randomUUID(),
            requestHash,
            requestJson,
            now());
    switch (state) {
      case PENDING -> {}
      case PREPARED -> saga.prepared(UUID.randomUUID(), now());
      case OWNER_COMMITTED -> {
        saga.prepared(UUID.randomUUID(), now());
        saga.ownerCommitted("{}", now());
      }
      case BOARD_COMMITTED -> {
        saga.prepared(UUID.randomUUID(), now());
        saga.ownerCommitted("{}", now());
        saga.boardCommitted("{}", now());
      }
      case COMPLETE -> {
        saga.prepared(UUID.randomUUID(), now());
        saga.ownerCommitted("{}", now());
        saga.boardCommitted("{}", now());
        saga.complete("{}", now());
      }
      case RELEASE_PENDING -> {
        saga.prepared(UUID.randomUUID(), now());
        saga.releasePending("TEST", "test", now());
      }
      case RELEASED -> {
        saga.prepared(UUID.randomUUID(), now());
        saga.releasePending("TEST", "test", now());
        saga.released(now());
      }
      case QUARANTINED -> saga.quarantine("TEST_QUARANTINE", "test", now());
    }
    return saga;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(CLOCK);
  }

  private static PlatformTransactionManager noOpTransactions() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  private static final class TrackingTransactionManager implements PlatformTransactionManager {
    private boolean active;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      if (active) throw new IllegalStateException("Nested test transaction is not supported");
      active = true;
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      active = false;
    }

    @Override
    public void rollback(TransactionStatus status) {
      active = false;
    }

    boolean active() {
      return active;
    }
  }

  private record Fixture(
      PlanningPublishedRescheduleSagaService service,
      PlanningPublishedRescheduleSagaRepository sagas,
      CustomerRentalSessionRepository sessions,
      LogisticsDependencyGateway dependencies,
      CustomerBookingLifecycleService bookingLifecycle,
      ObjectMapper json,
      PlanningOrderRescheduleRequest request,
      UUID commandId,
      UUID orderId,
      UUID bookingId,
      UUID customerId,
      UUID warehouseId,
      UUID sourcePlanId,
      LocalDate oldDate) {}
}
