package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleService;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanCommitResult;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanRemovedTaskResult;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentRemovalRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRescheduleDecisionCode;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRescheduleSlot;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRecoveryOperation;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSaga;
import dev.buhanzaz.rwms.logistics.planning.repository.PlanningPublishedRescheduleSagaRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Proves post-owner published recovery never reconstructs success from mutable booking state. */
class PlanningPublishedRescheduleSagaReceiptTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void ownerCommittedCrashFinalizesAndReplaysTheFrozenOwnerReceipt() throws Exception {
    UUID sagaId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID sourcePlanId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    UUID holdId = sagaId;
    LocalDate oldDate = LocalDate.of(2026, 9, 3);
    PlanningPublishedAssignmentWithdrawalRequest withdrawal =
        new PlanningPublishedAssignmentWithdrawalRequest(
            sourcePlanId,
            1L,
            2L,
            warehouseId,
            oldDate,
            new PlanningPublishedAssignmentRemovalRequest(
                documentId,
                externalTaskId,
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
            UUID.randomUUID(),
            3L,
            PlanningRescheduleDecisionCode.CUSTOMER_AGREED_ALTERNATIVE,
            UUID.randomUUID(),
            "Клиент согласовал 5 сентября",
            withdrawal);
    PlanningOrderRescheduleResponse frozenA =
        new PlanningOrderRescheduleResponse(
            orderId,
            8,
            UUID.randomUUID(),
            5,
            bookingId,
            warehouseId,
            new PlanningRescheduleSlot(
                request.slotId(),
                4,
                LocalDate.of(2026, 9, 5),
                "FIXED_WINDOW",
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                28_500L,
                OffsetDateTime.parse("2026-09-01T09:10:00Z")));
    ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    String requestJson = json.writeValueAsString(request);
    String requestHash =
        dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore.sha256(
            json.writeValueAsBytes(Map.of("identity", orderId, "request", request)));
    PlanningPublishedRescheduleSaga saga =
        PlanningPublishedRescheduleSaga.create(
            sagaId,
            orderId,
            bookingId,
            customerId,
            PlanningPublishedRecoveryOperation.RESCHEDULE,
            sourcePlanId,
            1,
            2,
            warehouseId,
            oldDate,
            documentId,
            externalTaskId,
            requestHash,
            requestJson,
            OffsetDateTime.now(CLOCK));
    saga.prepared(holdId, OffsetDateTime.now(CLOCK));
    saga.ownerCommitted(json.writeValueAsString(frozenA), OffsetDateTime.now(CLOCK));

    PlanningPublishedRescheduleSagaRepository sagas =
        mock(PlanningPublishedRescheduleSagaRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    CustomerRentalSessionRepository sessions = mock(CustomerRentalSessionRepository.class);
    CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
    DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    CustomerBookingLifecycleService bookingLifecycle = mock(CustomerBookingLifecycleService.class);
    RentalOrderPlanningIntegrationService planning =
        mock(RentalOrderPlanningIntegrationService.class);
    LogisticsDocumentService documentService = mock(LogisticsDocumentService.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    when(sagas.findById(sagaId)).thenReturn(Optional.of(saga));
    when(sagas.findForUpdate(sagaId)).thenReturn(Optional.of(saga));
    when(sagas.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(dependencies.commitPlanningReschedule(holdId, sagaId))
        .thenReturn(
            new PlanningReplanCommitResult(
                "APPLIED",
                holdId,
                sourcePlanId,
                2,
                new PlanningReplanRemovedTaskResult(externalTaskId, 5, "CANCELLED"),
                List.of(),
                List.of()));
    LogisticsDocument document = mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(documentId);
    when(document.getVersion()).thenReturn(6L);
    when(documents.findForUpdate(documentId)).thenReturn(Optional.of(document));
    DriverLogisticsTask removed = mock(DriverLogisticsTask.class);
    when(removed.getExternalTaskId()).thenReturn(externalTaskId);
    when(removed.getState()).thenReturn(DriverTaskState.CANCELLED);
    when(removed.getVersion()).thenReturn(7L);
    when(tasks.findAllForUpdateBySourcePlanId(sourcePlanId)).thenReturn(List.of(removed));
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
            tasks,
            documents,
            bookingLifecycle,
            planning,
            documentService,
            dependencies,
            json,
            clocks,
            noOpTransactions());

    PlanningOrderRescheduleResponse completed;
    try {
      completed = service.reschedule(orderId, sagaId, request);
    } catch (OrderProblemException problem) {
      throw new AssertionError(
          "Recovery stopped at "
              + saga.getState()
              + " with "
              + saga.getLastErrorCode()
              + ": "
              + saga.getLastErrorMessage(),
          problem);
    }
    PlanningOrderRescheduleResponse replayed = service.reschedule(orderId, sagaId, request);

    assertThat(completed.orderVersion()).isEqualTo(frozenA.orderVersion());
    assertThat(completed.sessionVersion()).isEqualTo(frozenA.sessionVersion());
    assertThat(completed.confirmedSlot()).isEqualTo(frozenA.confirmedSlot());
    assertThat(completed.publishedPlanWithdrawal().state()).isEqualTo("COMPLETE");
    assertThat(replayed).isEqualTo(completed);
    verify(orders, never()).findWithClientById(any());
    verify(sessions, never()).findFirstByOrderIdOrderByCreatedAtAscIdAsc(any());
    verify(slots, never()).findByOrderIdAndState(any(), any(CustomerDeliverySlotState.class));
    verify(dependencies).commitPlanningReschedule(holdId, sagaId);
  }

  @Test
  void freshIntentCannotBeInsertedBeforeTheExactBookingRowIsLockedAndRevalidated()
      throws Exception {
    UUID sagaId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID sourcePlanId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
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
                documentId,
                externalTaskId,
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
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    RentalOrder order = mock(RentalOrder.class);
    dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot currentSlot =
        mock(dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot.class);
    dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot selectedSlot =
        mock(dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot.class);
    UUID sessionId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    when(session.getId()).thenReturn(sessionId);
    when(session.getBookingId()).thenReturn(bookingId);
    when(session.getOrderId()).thenReturn(orderId);
    when(session.getState()).thenReturn(CustomerSessionState.BOOKED);
    when(session.getVersion()).thenReturn(4L);
    when(session.getDeliverySlotId()).thenReturn(currentSlotId);
    when(session.getWarehouseId()).thenReturn(warehouseId);
    when(session.getCustomerSubjectId()).thenReturn(customerId);
    when(sessions.findFirstByOrderIdOrderByCreatedAtAscIdAsc(orderId))
        .thenReturn(Optional.of(session));
    CountDownLatch exactSessionLocked = new CountDownLatch(1);
    CountDownLatch releaseSessionLookup = new CountDownLatch(1);
    when(sessions.findByBookingIdForUpdate(bookingId))
        .thenAnswer(
            ignored -> {
              exactSessionLocked.countDown();
              if (!releaseSessionLookup.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test did not release exact session lookup");
              }
              return Optional.of(session);
            });
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
    when(sagas.findById(sagaId)).thenReturn(Optional.empty());
    when(sagas.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              PlanningPublishedRescheduleSaga created = invocation.getArgument(0);
              created.quarantine("TEST_QUARANTINE", "stop after admission", OffsetDateTime.now(CLOCK));
              return created;
            });
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
            mock(CustomerBookingLifecycleService.class),
            mock(RentalOrderPlanningIntegrationService.class),
            mock(LogisticsDocumentService.class),
            mock(LogisticsDependencyGateway.class),
            json,
            clocks,
            noOpTransactions());

    CompletableFuture<String> start =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                service.reschedule(orderId, sagaId, request);
                return "unexpected-success";
              } catch (OrderProblemException problem) {
                return problem.code();
              }
            });
    assertThat(exactSessionLocked.await(2, TimeUnit.SECONDS)).isTrue();
    verify(sagas, never()).saveAndFlush(any());
    releaseSessionLookup.countDown();

    assertThat(start.get(5, TimeUnit.SECONDS)).isEqualTo("TEST_QUARANTINE");
    InOrder admissionOrder = org.mockito.Mockito.inOrder(sessions, sagas);
    admissionOrder.verify(sessions).findByBookingIdForUpdate(bookingId);
    admissionOrder.verify(sagas).saveAndFlush(any());
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
}
