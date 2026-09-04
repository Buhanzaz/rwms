package dev.buhanzaz.rwms.logistics.customer.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityPriceZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityRestrictionZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationOperation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingMutationRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.RescheduleDecision;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderCustomerLifecycleService;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderCustomerLifecycleService.CustomerOrderFence;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRecoveryOperation;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSaga;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSagaState;
import dev.buhanzaz.rwms.logistics.planning.repository.PlanningPublishedRescheduleSagaRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

/** Verifies ownership, no-start guards and the transactional ordering of booking mutations. */
class CustomerBookingLifecycleStoreTest {
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000801");
  private static final UUID OTHER_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000802");
  private static final UUID BOOKING = UUID.fromString("00000000-0000-0000-0000-000000000803");
  private static final UUID INQUIRY = UUID.fromString("00000000-0000-0000-0000-000000000804");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000805");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000806");
  private static final UUID OLD_SLOT = UUID.fromString("00000000-0000-0000-0000-000000000807");
  private static final UUID NEW_SLOT = UUID.fromString("00000000-0000-0000-0000-000000000808");
  private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000809");
  private static final LocalDate OLD_DATE = LocalDate.of(2026, 9, 2);
  private static final LocalDate NEW_DATE = LocalDate.of(2026, 9, 5);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void anotherCustomersBookingIsMaskedAsNotFoundBeforeOrderOrAssetAccess() {
    Fixture fixture = fixture();
    when(fixture.session().getCustomerSubjectId()).thenReturn(OTHER_SUBJECT);

    assertThatThrownBy(
            () -> fixture.store().prepareCancellation(identity(), BOOKING, KEY, "a".repeat(64), 4))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(problem.code()).isEqualTo("CUSTOMER_BOOKING_NOT_FOUND");
              assertThat(problem.getMessage()).isEqualTo("Бронирование не найдено");
            });
    verify(fixture.orderLifecycle(), never()).requireCancellation(any(), any());
    verify(fixture.mutations(), never()).saveAndFlush(any());
  }

  @Test
  void startedOrderWorkRejectsCancellationWithoutChangingLocalSessionOrSlot() {
    Fixture fixture = fixture();
    when(fixture.orderLifecycle().requireCancellation(any(), eq(ORDER)))
        .thenThrow(
            new OrderProblemException(
                HttpStatus.CONFLICT,
                "CUSTOMER_BOOKING_NOT_EDITABLE",
                "Бронирование нельзя отменить после начала отгрузки"));

    assertThatThrownBy(
            () -> fixture.store().prepareCancellation(identity(), BOOKING, KEY, "a".repeat(64), 4))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem ->
                assertThat(problem.getMessage())
                    .isEqualTo("Бронирование нельзя отменить после начала отгрузки"));
    verify(fixture.session(), never()).beginCancellation(any(Long.class), any());
    verify(fixture.oldSlot(), never()).releaseConfirmed(any(), any());
    verify(fixture.sessions(), never()).saveAndFlush(any());
  }

  @Test
  void staleCustomerVersionIsRejectedBeforeOrderOrSlotMutation() {
    Fixture fixture = fixture();

    assertThatThrownBy(
            () -> fixture.store().prepareCancellation(identity(), BOOKING, KEY, "a".repeat(64), 3))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_BOOKING_VERSION_CONFLICT"));
    verify(fixture.orderLifecycle(), never()).requireCancellation(any(), any());
    verify(fixture.oldSlot(), never()).releaseConfirmed(any(), any());
  }

  @Test
  void untouchedBookingPersistsARecoveryCheckpointBeforeRemoteOrderRelease() {
    Fixture fixture = fixture();
    when(fixture.orderLifecycle().requireCancellation(any(), eq(ORDER))).thenReturn(fence(8));
    when(fixture.mutations().saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var start = fixture.store().prepareCancellation(identity(), BOOKING, KEY, "a".repeat(64), 4);

    assertThat(start.mutation().getOrderId()).isEqualTo(ORDER);
    assertThat(start.mutation().getOldSlotId()).isEqualTo(OLD_SLOT);
    assertThat(start.mutation().getExpectedOrderVersion()).isEqualTo(8);
    verify(fixture.session()).beginCancellation(eq(4L), any());
    verify(fixture.sessions()).saveAndFlush(fixture.session());
  }

  @Test
  void startedShipmentDraftGuardRejectsWithoutCreatingCancellationCheckpoint() {
    Fixture fixture = fixture();
    LogisticsDocument shipment = mock(LogisticsDocument.class);
    when(fixture.orderLifecycle().requireCancellation(any(), eq(ORDER))).thenReturn(fence(8));
    when(fixture
            .documents()
            .findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
                LogisticsDocumentType.SHIPMENT, ORDER))
        .thenReturn(List.of(shipment));
    when(shipment.getState()).thenReturn(LogisticsDocumentState.IN_TRANSIT);

    assertThatThrownBy(
            () -> fixture.store().prepareCancellation(identity(), BOOKING, KEY, "a".repeat(64), 4))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_BOOKING_NOT_EDITABLE"));
    verify(fixture.session(), never()).beginCancellation(any(Long.class), any());
    verify(fixture.mutations(), never()).saveAndFlush(any());
  }

  @Test
  void rescheduleReleasesTheOldSlotOnlyAfterOrderMutationSucceeds() {
    Fixture fixture = fixture();
    fixture.stubRescheduleInputs();
    when(fixture.orderLifecycle().reschedule(any(), eq(ORDER), eq(NEW_DATE))).thenReturn(fence(9));
    when(fixture.mutations().saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    fixture.store().reschedule(identity(), BOOKING, KEY, "b".repeat(64), fixture.decision());

    InOrder ordered = inOrder(fixture.orderLifecycle(), fixture.oldSlot(), fixture.newSlot());
    ordered.verify(fixture.orderLifecycle()).reschedule(any(), eq(ORDER), eq(NEW_DATE));
    ordered.verify(fixture.oldSlot()).releaseConfirmed(BOOKING, ORDER);
    ordered.verify(fixture.newSlot()).confirmReschedule(BOOKING, ORDER, 1);
    verify(fixture.session()).reschedule(eq(4L), eq(NEW_SLOT), any());
  }

  @Test
  void oldConfirmedSlotIsUntouchedWhenOrderMutationFails() {
    Fixture fixture = fixture();
    fixture.stubRescheduleInputs();
    when(fixture.orderLifecycle().reschedule(any(), eq(ORDER), eq(NEW_DATE)))
        .thenThrow(
            new OrderProblemException(
                HttpStatus.CONFLICT,
                "CUSTOMER_BOOKING_NOT_EDITABLE",
                "Бронирование уже начали выполнять"));

    assertThatThrownBy(
            () ->
                fixture
                    .store()
                    .reschedule(identity(), BOOKING, KEY, "b".repeat(64), fixture.decision()))
        .isInstanceOf(OrderProblemException.class);
    verify(fixture.oldSlot(), never()).releaseConfirmed(any(), any());
    verify(fixture.newSlot(), never()).confirmReschedule(any(), any(), any(Integer.class));
    verify(fixture.session(), never()).reschedule(any(Long.class), any(), any());
  }

  @Test
  void capacityRaceRejectsBeforeOrderOrEitherSlotChanges() {
    Fixture fixture = fixture();
    fixture.stubRescheduleInputs();
    RescheduleDecision stale =
        new RescheduleDecision(
            4, WAREHOUSE, OLD_SLOT, OLD_DATE, NEW_DATE, NEW_SLOT, 3, "f".repeat(64), 1);

    assertThatThrownBy(
            () -> fixture.store().reschedule(identity(), BOOKING, KEY, "b".repeat(64), stale))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_DELIVERY_SLOT_TAKEN"));
    verify(fixture.orderLifecycle(), never()).reschedule(any(), any(), any());
    verify(fixture.oldSlot(), never()).releaseConfirmed(any(), any());
    verify(fixture.newSlot(), never()).confirmReschedule(any(), any(), any(Integer.class));
  }

  @Test
  void publishedRoutePreparationIsRevalidatedBeforeTheAtomicOwnerEffect() {
    Fixture fixture = fixture();
    fixture.stubRescheduleInputs();
    when(fixture.publishedRescheduleSagas().findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(List.of(saga(KEY, PlanningPublishedRescheduleSagaState.PREPARED)));
    RescheduleDecision stale =
        new RescheduleDecision(
                4,
                WAREHOUSE,
                OLD_SLOT,
                OLD_DATE,
                NEW_DATE,
                NEW_SLOT,
                3,
                "f".repeat(64),
                1)
            .withExpectedOrderVersion(8);

    assertThatThrownBy(
            () ->
                fixture
                    .store()
                    .reschedulePublishedPreStart(
                        identity(),
                        BOOKING,
                        KEY,
                        "b".repeat(64),
                        stale,
                        new CustomerBookingLifecycleStore.RescheduleAudit(
                            "CUSTOMER_AGREED", SUBJECT, null)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_DELIVERY_SLOT_TAKEN"));

    verify(fixture.orderLifecycle(), never())
        .reschedulePublishedPreStart(any(), any(), any(), any(Long.class));
    verify(fixture.oldSlot(), never()).releaseConfirmed(any(), any());
    verify(fixture.newSlot(), never()).confirmReschedule(any(), any(), any(Integer.class));
  }

  @Test
  void slotReleaseFailureCannotMarkSessionOrMutationCompleted() {
    Fixture fixture = fixture();
    UUID mutationId = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    CustomerBookingMutation mutation = mock(CustomerBookingMutation.class);
    when(fixture.mutations().findForUpdate(mutationId))
        .thenReturn(Optional.of(mutation));
    when(mutation.hasLiveLease(eq(lease), any())).thenReturn(true);
    when(mutation.getOldSlotId()).thenReturn(OLD_SLOT);
    when(mutation.getCustomerSubjectId()).thenReturn(SUBJECT);
    when(mutation.getBookingId()).thenReturn(BOOKING);
    when(mutation.getOrderId()).thenReturn(ORDER);
    when(fixture.slots().findById(OLD_SLOT))
        .thenReturn(Optional.of(fixture.oldSlot()));
    when(fixture.slots().findByIdForUpdate(OLD_SLOT))
        .thenReturn(Optional.of(fixture.oldSlot()));
    doThrow(new IllegalStateException("slot persistence failure"))
        .when(fixture.oldSlot())
        .releaseConfirmed(BOOKING, ORDER);

    assertThatThrownBy(
            () ->
                fixture
                    .store()
                    .completeCancellation(mutationId, lease))
        .isInstanceOf(IllegalStateException.class);
    verify(fixture.session(), never()).completeCancellation(any());
    verify(mutation, never()).complete(any(), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = PlanningPublishedRescheduleSagaState.class,
      names = {"COMPLETE", "RELEASED"},
      mode = EnumSource.Mode.EXCLUDE)
  void everyNonTerminalPublishedSagaRejectsOrdinaryRescheduleAfterTheSessionLock(
      PlanningPublishedRescheduleSagaState state) {
    Fixture fixture = fixture();
    when(fixture.publishedRescheduleSagas().findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(List.of(saga(KEY, state)));

    assertPublishedConflict(
        () ->
            fixture
                .store()
                .reschedule(identity(), BOOKING, KEY, "b".repeat(64), fixture.decision()));

    InOrder order = inOrder(fixture.sessions(), fixture.publishedRescheduleSagas());
    order
        .verify(fixture.sessions())
        .findByBookingIdForUpdate(BOOKING);
    order.verify(fixture.publishedRescheduleSagas()).findAllForBookingAdmission(ORDER, BOOKING);
    verify(fixture.orderLifecycle(), never()).reschedule(any(), any(), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = PlanningPublishedRescheduleSagaState.class,
      names = {"COMPLETE", "RELEASED"},
      mode = EnumSource.Mode.EXCLUDE)
  void everyNonTerminalPublishedSagaRejectsOrdinaryCancellation(
      PlanningPublishedRescheduleSagaState state) {
    Fixture fixture = fixture();
    when(fixture.publishedRescheduleSagas().findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(List.of(saga(KEY, state)));

    assertPublishedConflict(
        () -> fixture.store().prepareCancellation(identity(), BOOKING, KEY, "a".repeat(64), 4));

    verify(fixture.orderLifecycle(), never()).requireCancellation(any(), any());
    verify(fixture.session(), never()).beginCancellation(any(Long.class), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = PlanningPublishedRescheduleSagaState.class,
      names = {"COMPLETE", "RELEASED"})
  void terminalPublishedSagasDoNotFenceOrdinaryCommands(
      PlanningPublishedRescheduleSagaState state) {
    Fixture reschedule = fixture();
    reschedule.stubRescheduleInputs();
    when(reschedule.publishedRescheduleSagas().findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(List.of(saga(KEY, state)));
    when(reschedule.orderLifecycle().reschedule(any(), eq(ORDER), eq(NEW_DATE)))
        .thenReturn(fence(9));

    CustomerBookingRescheduleReceipt result =
        reschedule
            .store()
            .reschedule(identity(), BOOKING, KEY, "b".repeat(64), reschedule.decision());

    assertThat(result.confirmedSlot().slotId()).isEqualTo(NEW_SLOT);

    Fixture cancellation = fixture();
    when(cancellation.publishedRescheduleSagas().findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(List.of(saga(KEY, state)));
    when(cancellation.orderLifecycle().requireCancellation(any(), eq(ORDER)))
        .thenReturn(fence(8));
    when(cancellation.mutations().saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(
            cancellation
                .store()
                .prepareCancellation(identity(), BOOKING, KEY, "a".repeat(64), 4))
        .isNotNull();
  }

  @Test
  void publishedOwnerPathExemptsOnlyItsExactPreparedSaga() {
    Fixture fixture = fixture();
    fixture.stubRescheduleInputs();
    when(fixture.publishedRescheduleSagas().findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(List.of(saga(KEY, PlanningPublishedRescheduleSagaState.PREPARED)));
    when(fixture.orderLifecycle().reschedulePublishedPreStart(any(), eq(ORDER), eq(NEW_DATE), eq(8L)))
        .thenReturn(fence(9));

    CustomerBookingRescheduleReceipt result =
        fixture
            .store()
            .reschedulePublishedPreStart(
                identity(),
                BOOKING,
                KEY,
                "b".repeat(64),
                fixture.decision().withExpectedOrderVersion(8),
                new CustomerBookingLifecycleStore.RescheduleAudit(
                    "CUSTOMER_AGREED", SUBJECT, null));

    assertThat(result.orderVersion()).isEqualTo(9);
    verify(fixture.orderLifecycle())
        .reschedulePublishedPreStart(any(), eq(ORDER), eq(NEW_DATE), eq(8L));
  }

  @Test
  void publishedOwnerPathRejectsASecondActiveSagaForTheSameBooking() {
    Fixture fixture = fixture();
    UUID secondSagaId = UUID.randomUUID();
    when(fixture.publishedRescheduleSagas().findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(
            List.of(
                saga(KEY, PlanningPublishedRescheduleSagaState.PREPARED),
                saga(secondSagaId, PlanningPublishedRescheduleSagaState.QUARANTINED)));

    assertPublishedConflict(
        () ->
            fixture
                .store()
                .reschedulePublishedPreStart(
                    identity(),
                    BOOKING,
                    KEY,
                    "b".repeat(64),
                    fixture.decision().withExpectedOrderVersion(8),
                    new CustomerBookingLifecycleStore.RescheduleAudit(
                        "CUSTOMER_AGREED", SUBJECT, null)));

    verify(fixture.orderLifecycle(), never())
        .reschedulePublishedPreStart(any(), any(), any(), any(Long.class));
  }

  @Test
  void exactRescheduleReplayReturnsFrozenAAfterCurrentBookingMovedToB() throws Exception {
    Fixture fixture = fixture();
    CustomerBookingRescheduleReceipt frozenA = frozenReceipt(NEW_SLOT, NEW_DATE, 9, 5, 3);
    CustomerBookingMutation mutation = completedReplay(fixture, frozenA);

    CustomerBookingRescheduleReceipt replay =
        fixture
            .store()
            .rescheduleReplay(identity(), BOOKING, KEY, "b".repeat(64))
            .orElseThrow();

    assertThat(replay).isEqualTo(frozenA);
    verify(fixture.sessions(), never())
        .findByBookingIdAndCustomerSubjectId(any(), any());
    verify(fixture.sessions(), never()).findByBookingIdForUpdate(any());
    verify(mutation).getRescheduleResultJson();
  }

  @Test
  void exactRescheduleReplayReturnsFrozenAEvenAfterCurrentBookingWasCancelled() throws Exception {
    Fixture fixture = fixture();
    CustomerBookingRescheduleReceipt frozenA = frozenReceipt(NEW_SLOT, NEW_DATE, 9, 5, 3);
    completedReplay(fixture, frozenA);
    when(fixture.session().getState()).thenReturn(CustomerSessionState.CANCELLED);

    CustomerBookingRescheduleReceipt replay =
        fixture
            .store()
            .rescheduleReplay(identity(), BOOKING, KEY, "b".repeat(64))
            .orElseThrow();

    assertThat(replay).isEqualTo(frozenA);
    verify(fixture.sessions(), never()).findByBookingIdForUpdate(any());
  }

  @Test
  void legacyCompletedRescheduleWithoutReceiptFailsExplicitlyInsteadOfReadingMutableState() {
    Fixture fixture = fixture();
    CustomerBookingMutation mutation = mock(CustomerBookingMutation.class);
    when(fixture
            .mutations()
            .findByCustomerSubjectIdAndIdempotencyKey(SUBJECT, KEY))
        .thenReturn(Optional.of(mutation));
    when(mutation.matches(CustomerBookingMutationOperation.RESCHEDULE, BOOKING, "b".repeat(64)))
        .thenReturn(true);
    when(mutation.getState()).thenReturn(CustomerBookingMutationState.COMPLETED);
    when(mutation.getRescheduleResultJson()).thenReturn(null);

    assertThatThrownBy(
            () ->
                fixture
                    .store()
                    .rescheduleReplay(identity(), BOOKING, KEY, "b".repeat(64)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(problem.code())
                  .isEqualTo("CUSTOMER_BOOKING_RESCHEDULE_REPLAY_UNSUPPORTED");
            });
    verify(fixture.sessions(), never()).findByBookingIdForUpdate(any());
  }

  @Test
  void sameRescheduleKeyWithAnotherBodyIsStillRejectedBeforeReceiptDecode() {
    Fixture fixture = fixture();
    CustomerBookingMutation mutation = mock(CustomerBookingMutation.class);
    when(fixture
            .mutations()
            .findByCustomerSubjectIdAndIdempotencyKey(SUBJECT, KEY))
        .thenReturn(Optional.of(mutation));
    when(mutation.matches(CustomerBookingMutationOperation.RESCHEDULE, BOOKING, "x".repeat(64)))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                fixture
                    .store()
                    .rescheduleReplay(identity(), BOOKING, KEY, "x".repeat(64)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    verify(mutation, never()).getRescheduleResultJson();
  }

  @Test
  void receiptSerializationFailureAbortsBeforeTheCompletedMutationCheckpoint() throws Exception {
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsString(any(CustomerBookingRescheduleReceipt.class)))
        .thenThrow(mock(tools.jackson.core.JacksonException.class));
    Fixture fixture = fixture(failingMapper);
    fixture.stubRescheduleInputs();
    when(fixture.orderLifecycle().reschedule(any(), eq(ORDER), eq(NEW_DATE))).thenReturn(fence(9));

    assertThatThrownBy(
            () ->
                fixture
                    .store()
                    .reschedule(identity(), BOOKING, KEY, "b".repeat(64), fixture.decision()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt cannot be serialized");
    verify(fixture.mutations(), never()).saveAndFlush(any());
  }

  private static CustomerBookingMutation completedReplay(
      Fixture fixture, CustomerBookingRescheduleReceipt receipt) throws Exception {
    CustomerBookingMutation mutation = mock(CustomerBookingMutation.class);
    when(fixture
            .mutations()
            .findByCustomerSubjectIdAndIdempotencyKey(SUBJECT, KEY))
        .thenReturn(Optional.of(mutation));
    when(mutation.matches(CustomerBookingMutationOperation.RESCHEDULE, BOOKING, "b".repeat(64)))
        .thenReturn(true);
    when(mutation.getState()).thenReturn(CustomerBookingMutationState.COMPLETED);
    when(mutation.getRescheduleResultJson())
        .thenReturn(JsonMapper.builder().findAndAddModules().build().writeValueAsString(receipt));
    return mutation;
  }

  private static CustomerBookingRescheduleReceipt frozenReceipt(
      UUID slotId, LocalDate date, long orderVersion, long sessionVersion, long slotVersion) {
    return new CustomerBookingRescheduleReceipt(
        ORDER,
        orderVersion,
        UUID.fromString("00000000-0000-0000-0000-000000000899"),
        sessionVersion,
        BOOKING,
        WAREHOUSE,
        INQUIRY,
        "Великий Новгород, тестовый адрес",
        List.of(),
        new CustomerBookingRescheduleReceipt.Slot(
            slotId,
            slotVersion,
            date,
            "FIXED_WINDOW",
            java.time.LocalTime.of(9, 0),
            java.time.LocalTime.of(12, 0),
            28_500L,
            OffsetDateTime.parse("2026-08-31T10:00:00Z")));
  }

  private static void assertPublishedConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(problem.code())
                  .isEqualTo("CUSTOMER_BOOKING_PUBLISHED_CHANGE_IN_PROGRESS");
            });
  }

  private static PlanningPublishedRescheduleSaga saga(
      UUID id, PlanningPublishedRescheduleSagaState state) {
    OffsetDateTime now = OffsetDateTime.parse("2026-08-31T08:00:00Z");
    PlanningPublishedRescheduleSaga saga =
        PlanningPublishedRescheduleSaga.create(
            id,
            ORDER,
            BOOKING,
            SUBJECT,
            PlanningPublishedRecoveryOperation.RESCHEDULE,
            UUID.randomUUID(),
            1,
            2,
            WAREHOUSE,
            OLD_DATE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "c".repeat(64),
            "{}",
            now);
    switch (state) {
      case PENDING -> {}
      case PREPARED -> saga.prepared(UUID.randomUUID(), now.plusSeconds(1));
      case OWNER_COMMITTED -> {
        saga.prepared(UUID.randomUUID(), now.plusSeconds(1));
        saga.ownerCommitted("{}", now.plusSeconds(2));
      }
      case BOARD_COMMITTED -> {
        saga.prepared(UUID.randomUUID(), now.plusSeconds(1));
        saga.ownerCommitted("{}", now.plusSeconds(2));
        saga.boardCommitted("{}", now.plusSeconds(3));
      }
      case COMPLETE -> {
        saga.prepared(UUID.randomUUID(), now.plusSeconds(1));
        saga.ownerCommitted("{}", now.plusSeconds(2));
        saga.boardCommitted("{}", now.plusSeconds(3));
        saga.complete("{}", now.plusSeconds(4));
      }
      case RELEASE_PENDING -> {
        saga.prepared(UUID.randomUUID(), now.plusSeconds(1));
        saga.releasePending("TEST", "test", now.plusSeconds(2));
      }
      case RELEASED -> {
        saga.prepared(UUID.randomUUID(), now.plusSeconds(1));
        saga.releasePending("TEST", "test", now.plusSeconds(2));
        saga.released(now.plusSeconds(3));
      }
      case QUARANTINED -> saga.quarantine("TEST", "test", now.plusSeconds(1));
    }
    return saga;
  }

  private static Fixture fixture() {
    return fixture(JsonMapper.builder().findAndAddModules().build());
  }

  private static Fixture fixture(ObjectMapper objectMapper) {
    CustomerBookingMutationRepository mutations = mock(CustomerBookingMutationRepository.class);
    CustomerRentalSessionRepository sessions = mock(CustomerRentalSessionRepository.class);
    CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    EquipmentMovementTaskRepository movementTasks = mock(EquipmentMovementTaskRepository.class);
    EquipmentMovementTaskService movementTaskService = mock(EquipmentMovementTaskService.class);
    LogisticsDocumentService documentService = mock(LogisticsDocumentService.class);
    CustomerEquipmentCodec equipmentCodec = mock(CustomerEquipmentCodec.class);
    RentalOrderCustomerLifecycleService orderLifecycle =
        mock(RentalOrderCustomerLifecycleService.class);
    CustomerAuthorizer access = mock(CustomerAuthorizer.class);
    LogisticsTransactionLock transactionLock = mock(LogisticsTransactionLock.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    WarehouseCapacityJobRepository capacityJobs = mock(WarehouseCapacityJobRepository.class);
    WarehouseCapacityShiftRepository capacityShifts = mock(WarehouseCapacityShiftRepository.class);
    WarehouseCapacityIsochroneTariffRepository isochroneTariffs =
        mock(WarehouseCapacityIsochroneTariffRepository.class);
    WarehouseCapacityPriceZoneRepository priceZones =
        mock(WarehouseCapacityPriceZoneRepository.class);
    WarehouseCapacityRestrictionZoneRepository restrictionZones =
        mock(WarehouseCapacityRestrictionZoneRepository.class);
    WarehouseCapacitySnapshotRepository snapshots = mock(WarehouseCapacitySnapshotRepository.class);
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
    PlanningPublishedRescheduleSagaRepository publishedRescheduleSagas =
        mock(PlanningPublishedRescheduleSagaRepository.class);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    CustomerDeliverySlot oldSlot = mock(CustomerDeliverySlot.class);
    CustomerDeliverySlot newSlot = mock(CustomerDeliverySlot.class);
    when(mutations.findByCustomerSubjectIdAndIdempotencyKey(SUBJECT, KEY))
        .thenReturn(Optional.empty());
    when(mutations.currentDatabaseTimestamp())
        .thenReturn(OffsetDateTime.parse("2026-08-31T08:00:00Z").toInstant());
    when(mutations.findOpenForBookingForUpdate(eq(BOOKING), any()))
        .thenReturn(List.of());
    when(sessions.findByBookingIdForUpdate(BOOKING))
        .thenReturn(Optional.of(session));
    when(session.getCustomerSubjectId()).thenReturn(SUBJECT);
    when(session.getId()).thenReturn(UUID.randomUUID());
    when(session.getBookingId()).thenReturn(BOOKING);
    when(session.getInquiryId()).thenReturn(INQUIRY);
    when(session.getOrderId()).thenReturn(ORDER);
    when(session.getWarehouseId()).thenReturn(WAREHOUSE);
    when(session.getDeliverySlotId()).thenReturn(OLD_SLOT);
    when(session.getVersion()).thenReturn(4L);
    when(session.getState()).thenReturn(CustomerSessionState.BOOKED);
    when(session.getEquipmentSelectionJson()).thenReturn("[]");
    when(equipmentCodec.decode("[]")).thenReturn(List.of());
    when(slots.findByIdForUpdate(OLD_SLOT))
        .thenReturn(Optional.of(oldSlot));
    when(oldSlot.getId()).thenReturn(OLD_SLOT);
    when(oldSlot.getWarehouseId()).thenReturn(WAREHOUSE);
    when(oldSlot.getDeliveryDate()).thenReturn(OLD_DATE);
    when(oldSlot.getCustomerSubjectId()).thenReturn(SUBJECT);
    when(oldSlot.getInquiryId()).thenReturn(INQUIRY);
    when(oldSlot.getState()).thenReturn(CustomerDeliverySlotState.CONFIRMED);
    when(oldSlot.getBookingId()).thenReturn(BOOKING);
    when(oldSlot.getOrderId()).thenReturn(ORDER);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.SHIPMENT, ORDER))
        .thenReturn(List.of());
    when(access.orderActor(any(), eq(WAREHOUSE))).thenReturn(actor());
    when(publishedRescheduleSagas.findAllForBookingAdmission(ORDER, BOOKING))
        .thenReturn(List.of());
    return new Fixture(
        new CustomerBookingLifecycleStore(
            mutations,
            sessions,
            slots,
            documents,
            movementTasks,
            movementTaskService,
            documentService,
            equipmentCodec,
            orderLifecycle,
            access,
            transactionLock,
            capacityFence,
            capacityJobs,
            capacityShifts,
            isochroneTariffs,
            priceZones,
            restrictionZones,
            snapshots,
            driverTasks,
            publishedRescheduleSagas,
            objectMapper,
            CLOCK),
        mutations,
        sessions,
        slots,
        documents,
        orderLifecycle,
        capacityFence,
        capacityJobs,
        capacityShifts,
        isochroneTariffs,
        priceZones,
        restrictionZones,
        snapshots,
        driverTasks,
        publishedRescheduleSagas,
        session,
        oldSlot,
        newSlot);
  }

  private static CustomerOrderFence fence(long version) {
    return new CustomerOrderFence(
        ORDER,
        version,
        WAREHOUSE,
        "Великий Новгород, тестовый адрес",
        new BigDecimal("58.521475"),
        new BigDecimal("31.275475"));
  }

  private static CustomerIdentity identity() {
    return new CustomerIdentity(SUBJECT, "customer");
  }

  private static OrderActor actor() {
    return new OrderActor(
        SUBJECT,
        "CUSTOMER",
        "Клиент",
        Set.of(WAREHOUSE),
        Set.of(WAREHOUSE),
        false,
        false,
        false,
        false);
  }

  /** Complete mock graph for one short transaction. */
  private record Fixture(
      CustomerBookingLifecycleStore store,
      CustomerBookingMutationRepository mutations,
      CustomerRentalSessionRepository sessions,
      CustomerDeliverySlotRepository slots,
      LogisticsDocumentRepository documents,
      RentalOrderCustomerLifecycleService orderLifecycle,
      CustomerDeliveryCapacityFence capacityFence,
      WarehouseCapacityJobRepository capacityJobs,
      WarehouseCapacityShiftRepository capacityShifts,
      WarehouseCapacityIsochroneTariffRepository isochroneTariffs,
      WarehouseCapacityPriceZoneRepository priceZones,
      WarehouseCapacityRestrictionZoneRepository restrictionZones,
      WarehouseCapacitySnapshotRepository snapshots,
      DriverLogisticsTaskRepository driverTasks,
      PlanningPublishedRescheduleSagaRepository publishedRescheduleSagas,
      CustomerRentalSession session,
      CustomerDeliverySlot oldSlot,
      CustomerDeliverySlot newSlot) {
    void stubRescheduleInputs() {
      when(slots.findByIdForUpdate(NEW_SLOT))
          .thenReturn(Optional.of(newSlot));
      when(newSlot.getId()).thenReturn(NEW_SLOT);
      when(newSlot.getVersion()).thenReturn(3L);
      when(newSlot.getState()).thenReturn(CustomerDeliverySlotState.OFFERED);
      when(newSlot.getExpiresAt()).thenReturn(OffsetDateTime.parse("2026-08-31T10:00:00Z"));
      when(newSlot.getWarehouseId()).thenReturn(WAREHOUSE);
      when(newSlot.getDeliveryDate()).thenReturn(NEW_DATE);
      when(newSlot.getDeliveryAddress()).thenReturn("Великий Новгород, тестовый адрес");
      when(newSlot.getCustomerSubjectId()).thenReturn(SUBJECT);
      when(newSlot.getInquiryId()).thenReturn(INQUIRY);
      when(newSlot.getKind())
          .thenReturn(dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind.FIXED_WINDOW);
      when(newSlot.getWindowStart()).thenReturn(java.time.LocalTime.of(9, 0));
      when(newSlot.getWindowEnd()).thenReturn(java.time.LocalTime.of(12, 0));
      when(newSlot.getDeliveryPriceRubles()).thenReturn(28_500L);
      when(slots.findCapacityWorkloadForUpdate(
              any(), any(), any(), any(), any(), any()))
          .thenReturn(List.of());
      when(capacityJobs.findCapacityWorkload(WAREHOUSE, NEW_DATE)).thenReturn(List.of());
      when(capacityShifts.findCapacityShifts(WAREHOUSE, NEW_DATE)).thenReturn(List.of());
      when(isochroneTariffs.findTariffs(WAREHOUSE)).thenReturn(List.of());
      when(priceZones.findTariffZones(WAREHOUSE)).thenReturn(List.of());
      when(restrictionZones.findRestrictionZones(WAREHOUSE)).thenReturn(List.of());
      when(snapshots.findByWarehouseId(WAREHOUSE)).thenReturn(Optional.empty());
      when(driverTasks.countWholeDayDeliveryReservations(WAREHOUSE, NEW_DATE)).thenReturn(0L);
    }

    RescheduleDecision decision() {
      String fingerprint =
          CustomerCapacityWorkloadFingerprint.sha256(
              List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(), 0);
      return new RescheduleDecision(
          4, WAREHOUSE, OLD_SLOT, OLD_DATE, NEW_DATE, NEW_SLOT, 3, fingerprint, 1);
    }
  }
}
