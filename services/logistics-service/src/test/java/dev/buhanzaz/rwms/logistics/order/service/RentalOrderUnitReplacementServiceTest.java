package dev.buhanzaz.rwms.logistics.order.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderRentalItemResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitResponse;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService.ExistingShipmentFurnitureMovement;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService.ReplacementCheckpoint;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService.ReplacementPreparation;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** Verifies replacement ordering, pre-start fencing, and old furniture-task cancellation. */
class RentalOrderUnitReplacementServiceTest {
  private static final UUID ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID WAREHOUSE_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID SUPPORT_WAREHOUSE_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID OLD_1 = UUID.fromString("10000000-0000-0000-0000-000000000011");
  private static final UUID OLD_2 = UUID.fromString("10000000-0000-0000-0000-000000000012");
  private static final UUID NEW_1 = UUID.fromString("10000000-0000-0000-0000-000000000021");
  private static final UUID NEW_2 = UUID.fromString("10000000-0000-0000-0000-000000000022");
  private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000031");

  private final RentalOrderReadService reads = mock(RentalOrderReadService.class);
  private final RentalOrderReservationService reservations =
      mock(RentalOrderReservationService.class);
  private final RentalOrderMutationLocalStore orderMutations =
      mock(RentalOrderMutationLocalStore.class);
  private final RentalOrderInventorySourcePolicy inventorySources =
      mock(RentalOrderInventorySourcePolicy.class);
  private final OrderAuthorizer access = mock(OrderAuthorizer.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final ShipmentFurnitureTaskService furnitureTasks =
      mock(ShipmentFurnitureTaskService.class);
  private final EquipmentMovementTaskService movementTasks =
      mock(EquipmentMovementTaskService.class);
  private final LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
  private final LogisticsDocumentLineRepository documentLines =
      mock(LogisticsDocumentLineRepository.class);
  private final DocumentDriverTaskPlanner driverTaskPlanner = mock(DocumentDriverTaskPlanner.class);
  private final LogisticsDocumentService documentService = mock(LogisticsDocumentService.class);
  private final RentalOrderUnitReplacementService service =
      new RentalOrderUnitReplacementService(
          reads,
          reservations,
          orderMutations,
          inventorySources,
          access,
          dependencies,
          furnitureTasks,
          movementTasks,
          documents,
          documentLines,
          driverTaskPlanner,
          documentService);
  private final OrderActor actor =
      new OrderActor(
          ACTOR_ID,
          "WAREHOUSE_MANAGER",
          "Руководитель склада",
          Set.of(WAREHOUSE_ID),
          Set.of(WAREHOUSE_ID),
          false,
          true,
          true,
          true);

  @BeforeEach
  void replacementTripIsPreStartByDefault() {
    when(documentService.isRentalOrderUnitReplacementPreStart(eq(ORDER_ID), any()))
        .thenReturn(true);
    when(inventorySources.requireWritableReplacementSource(
            any(), eq(WAREHOUSE_ID), nullable(UUID.class)))
        .thenAnswer(invocation -> {
          UUID requested = invocation.getArgument(2);
          return requested == null ? WAREHOUSE_ID : requested;
        });
  }

  @Test
  void startedCabinIsRejectedByTheSharedPerUnitFenceBeforeAssetWork() {
    OrderDetailResponse current = order(OLD_1, OLD_2);
    when(reads.get(actor, ORDER_ID)).thenReturn(current);
    when(documentService.isRentalOrderUnitReplacementPreStart(ORDER_ID, OLD_1)).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.replaceDirect(
                    actor, ORDER_ID, 5, OLD_1, NEW_1, "неисправность", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("REPLACEMENT_SHIPMENT_STARTED"));

    verify(dependencies, never())
        .replaceOrderUnits(any(), any(), any(), any(), any(), any(), anyList(), anyList());
    verify(furnitureTasks, never()).checkpointReplacements(any());
  }

  @Test
  void pendingOldFurnitureTaskIsCancelledBeforeAnyReplacementAssetCall() {
    OrderDetailResponse order = order(OLD_1);
    LogisticsDocument document = waitingDocument(OLD_1);
    UUID movementTaskId = UUID.randomUUID();
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of(document));
    when(furnitureTasks.existingOrdinaryMovement(DOCUMENT_ID, OLD_1))
        .thenReturn(
            new ExistingShipmentFurnitureMovement(
                UUID.randomUUID(), movementTaskId, 7, EquipmentMovementTaskState.AWAITING_WORKER));

    assertThatThrownBy(
            () ->
                service.replaceDirect(
                    actor, ORDER_ID, 5, OLD_1, NEW_1, "неисправность", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("REPLACEMENT_FURNITURE_CANCELLING"));

    verify(movementTasks)
        .cancel(
            eq(ACTOR_ID),
            eq(movementTaskId),
            any(),
            eq(new CancelEquipmentMovementTaskRequest(7L)));
    verify(dependencies, never())
        .planOrderFurnitureMovements(any(), any(), any(), any(), anyList(), anyList());
    verify(dependencies, never())
        .replaceOrderUnits(any(), any(), any(), any(), any(), any(), anyList(), anyList());
  }

  @Test
  void executingOldFurnitureTaskBlocksReplacementBeforeAssetSwap() {
    OrderDetailResponse order = order(OLD_1);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    LogisticsDocument document = waitingDocument(OLD_1);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of(document));
    when(furnitureTasks.existingOrdinaryMovement(DOCUMENT_ID, OLD_1))
        .thenReturn(
            new ExistingShipmentFurnitureMovement(
                UUID.randomUUID(), UUID.randomUUID(), 3, EquipmentMovementTaskState.EXECUTING));

    assertThatThrownBy(
            () ->
                service.replaceDirect(
                    actor, ORDER_ID, 5, OLD_1, NEW_1, "неисправность", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("REPLACEMENT_FURNITURE_TASK_ACTIVE"));

    verify(movementTasks, never()).cancel(any(), any(), any(), any());
    verify(dependencies, never())
        .replaceOrderUnits(any(), any(), any(), any(), any(), any(), anyList(), anyList());
  }

  @Test
  void scheduledWaitingTripIsCancelledBeforeOneAtomicSwap() {
    UUID key = UUID.randomUUID();
    OrderDetailResponse order = order(OLD_1);
    LogisticsDocument document = waitingDocument(OLD_1);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        List.of(new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(NEW_1, List.of()));
    ReplacementCheckpoint checkpoint = checkpoint(key, DOCUMENT_ID, OLD_1, NEW_1, 0, null, null);
    LogisticsDependencyGateway.OrderUnitsReplacementReceipt receipt =
        new LogisticsDependencyGateway.OrderUnitsReplacementReceipt(
            List.of(
                new LogisticsDependencyGateway.OrderUnitReplacementReceipt(
                    null, null, List.of(), false)),
            false);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of(document));
    when(reservations.replacementComposition(eq(ORDER_ID), any())).thenReturn(composition);
    when(dependencies.planOrderFurnitureMovements(any(), any(), any(), any(), anyList(), anyList()))
        .thenReturn(
            new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                ORDER_ID, NEW_1, "СПБ-002", List.of()));
    when(furnitureTasks.replacementBatch(ORDER_ID, key)).thenReturn(List.of(checkpoint));
    when(documents.findById(DOCUMENT_ID)).thenReturn(java.util.Optional.of(document));
    when(dependencies.replaceOrderUnits(
            any(), any(), any(), any(), any(), any(), anyList(), anyList()))
        .thenReturn(receipt);
    when(reservations.finalizeReplacements(ORDER_ID, key, receipt))
        .thenReturn(new RentalOrderCommandOutcome(order, false));

    OrderDetailResponse result =
        service.replaceDirect(actor, ORDER_ID, 5, OLD_1, NEW_1, "неисправность", key);

    assertThat(result).isSameAs(order);
    var ordered = inOrder(driverTaskPlanner, dependencies, reservations);
    ordered.verify(driverTaskPlanner).cancelBeforeStart(eq(document), anyList());
    ordered
        .verify(dependencies)
        .replaceOrderUnits(
            eq(key),
            eq(ORDER_ID),
            eq(WAREHOUSE_ID),
            eq(null),
            eq(ACTOR_ID),
            eq("WAREHOUSE_MANAGER"),
            anyList(),
            anyList());
    ordered.verify(reservations).finalizeReplacements(ORDER_ID, key, receipt);
  }

  @Test
  void completedOldFurnitureTaskBecomesOneDirectOldToNewMovementWithoutCancellation() {
    UUID key = UUID.randomUUID();
    UUID oldMovementTaskId = UUID.randomUUID();
    UUID replacementMovementTaskId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID sourceBalanceId = UUID.randomUUID();
    OrderDetailResponse order = order(OLD_1);
    LogisticsDocument document = waitingDocument(OLD_1);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        List.of(
            new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                NEW_1,
                List.of(new LogisticsDependencyGateway.OrderEquipmentRequirement(equipmentId, 4))));
    LogisticsDependencyGateway.OrderFurnitureMovementPlanLine directLine =
        new LogisticsDependencyGateway.OrderFurnitureMovementPlanLine(
            equipmentId,
            "Кровать",
            sourceBalanceId,
            WAREHOUSE_ID,
            OLD_1,
            "CABIN",
            8,
            WAREHOUSE_ID,
            NEW_1,
            "CABIN",
            4);
    LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
        new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
            ORDER_ID, NEW_1, "СПБ-002", List.of(directLine));
    ReplacementCheckpoint checkpoint =
        checkpoint(key, DOCUMENT_ID, OLD_1, NEW_1, 0, null, replacementMovementTaskId);
    LogisticsDependencyGateway.OrderUnitReplacementMovement bundle =
        new LogisticsDependencyGateway.OrderUnitReplacementMovement(
            replacementMovementTaskId,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
            List.of(
                new LogisticsDependencyGateway.OrderUnitReplacementMovementLine(
                    UUID.randomUUID(), equipmentId, sourceBalanceId, 8, NEW_1, 4)));
    LogisticsDependencyGateway.OrderUnitsReplacementReceipt receipt =
        new LogisticsDependencyGateway.OrderUnitsReplacementReceipt(
            List.of(
                new LogisticsDependencyGateway.OrderUnitReplacementReceipt(
                    null, null, List.of(), false)),
            false);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of(document));
    when(furnitureTasks.existingOrdinaryMovement(DOCUMENT_ID, OLD_1))
        .thenReturn(
            new ExistingShipmentFurnitureMovement(
                UUID.randomUUID(), oldMovementTaskId, 12, EquipmentMovementTaskState.COMPLETED));
    when(reservations.replacementComposition(eq(ORDER_ID), any())).thenReturn(composition);
    when(dependencies.planOrderFurnitureMovements(any(), any(), any(), any(), anyList(), anyList()))
        .thenReturn(plan);
    when(furnitureTasks.replacementBatch(ORDER_ID, key)).thenReturn(List.of(checkpoint));
    when(documents.findById(DOCUMENT_ID)).thenReturn(java.util.Optional.of(document));
    when(movementTasks.replacementMovement(replacementMovementTaskId, NEW_1)).thenReturn(bundle);
    when(dependencies.replaceOrderUnits(
            any(), any(), any(), any(), any(), any(), anyList(), anyList()))
        .thenReturn(receipt);
    when(reservations.finalizeReplacements(ORDER_ID, key, receipt))
        .thenReturn(new RentalOrderCommandOutcome(order, false));

    service.replaceDirect(actor, ORDER_ID, 5, OLD_1, NEW_1, "неисправность", key);

    verify(movementTasks, never()).cancel(any(), any(), any(), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ReplacementPreparation>> prepared = ArgumentCaptor.forClass(List.class);
    verify(furnitureTasks).checkpointReplacements(prepared.capture());
    assertThat(prepared.getValue().getFirst().plan().lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.sourceRentalItemId()).isEqualTo(OLD_1);
              assertThat(line.targetRentalItemId()).isEqualTo(NEW_1);
              assertThat(line.quantity()).isEqualTo(4);
            });
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LogisticsDependencyGateway.OrderUnitReplacement>> replacements =
        ArgumentCaptor.forClass(List.class);
    verify(dependencies)
        .replaceOrderUnits(
            eq(key),
            eq(ORDER_ID),
            eq(WAREHOUSE_ID),
            eq(null),
            eq(ACTOR_ID),
            eq("WAREHOUSE_MANAGER"),
            eq(composition),
            replacements.capture());
    assertThat(replacements.getValue())
        .singleElement()
        .satisfies(replacement -> assertThat(replacement.movement()).isEqualTo(bundle));
  }

  @Test
  void authorizedCrossSourceReplacementFreezesAndReplaysThePhysicalSource() {
    UUID key = UUID.randomUUID();
    OrderDetailResponse order = order(OLD_1);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        List.of(new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(NEW_1, List.of()));
    ReplacementCheckpoint checkpoint =
        new ReplacementCheckpoint(
            UUID.randomUUID(),
            ORDER_ID,
            WAREHOUSE_ID,
            SUPPORT_WAREHOUSE_ID,
            null,
            OLD_1,
            NEW_1,
            "межскладская замена",
            ACTOR_ID,
            "WAREHOUSE_MANAGER",
            key,
            key,
            0,
            "a".repeat(64),
            null,
            null,
            null,
            null,
            null);
    LogisticsDependencyGateway.OrderUnitsReplacementReceipt receipt =
        new LogisticsDependencyGateway.OrderUnitsReplacementReceipt(
            List.of(
                new LogisticsDependencyGateway.OrderUnitReplacementReceipt(
                    null, null, List.of(), true)),
            false);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of());
    when(reservations.replacementComposition(eq(ORDER_ID), any())).thenReturn(composition);
    when(dependencies.planOrderFurnitureMovements(
            eq(ORDER_ID),
            eq(SUPPORT_WAREHOUSE_ID),
            eq(NEW_1),
            eq(OLD_1),
            anyList(),
            eq(composition)))
        .thenReturn(
            new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                ORDER_ID, NEW_1, "VN-101", List.of()));
    when(furnitureTasks.replacementBatch(ORDER_ID, key)).thenReturn(List.of(checkpoint));
    when(dependencies.replaceOrderUnits(
            eq(key),
            eq(ORDER_ID),
            eq(WAREHOUSE_ID),
            eq(SUPPORT_WAREHOUSE_ID),
            eq(null),
            eq(ACTOR_ID),
            eq("WAREHOUSE_MANAGER"),
            eq(composition),
            anyList()))
        .thenReturn(receipt);
    when(reservations.finalizeReplacements(ORDER_ID, key, receipt))
        .thenReturn(new RentalOrderCommandOutcome(order, false));

    OrderDetailResponse result =
        service.replaceDirect(
            actor,
            ORDER_ID,
            5,
            OLD_1,
            NEW_1,
            "межскладская замена",
            SUPPORT_WAREHOUSE_ID,
            key);

    assertThat(result).isSameAs(order);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ReplacementPreparation>> preparations =
        ArgumentCaptor.forClass(List.class);
    verify(furnitureTasks).checkpointReplacements(preparations.capture());
    assertThat(preparations.getValue())
        .singleElement()
        .satisfies(
            value ->
                assertThat(value.command().inventorySourceWarehouseId())
                    .isEqualTo(SUPPORT_WAREHOUSE_ID));
    verify(dependencies)
        .replaceOrderUnits(
            eq(key),
            eq(ORDER_ID),
            eq(WAREHOUSE_ID),
            eq(SUPPORT_WAREHOUSE_ID),
            eq(null),
            eq(ACTOR_ID),
            eq("WAREHOUSE_MANAGER"),
            eq(composition),
            anyList());
  }

  @Test
  void deniedCrossSourceFailsBeforePlanningOrAssetMutation() {
    OrderDetailResponse order = order(OLD_1);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(inventorySources.requireWritableReplacementSource(
            actor, WAREHOUSE_ID, SUPPORT_WAREHOUSE_ID))
        .thenThrow(new AccessDeniedException("Insufficient warehouse access"));

    assertThatThrownBy(
            () ->
                service.replaceDirect(
                    actor,
                    ORDER_ID,
                    5,
                    OLD_1,
                    NEW_1,
                    "межскладская замена",
                    SUPPORT_WAREHOUSE_ID,
                    UUID.randomUUID()))
        .isInstanceOf(AccessDeniedException.class);

    verify(dependencies, never())
        .planOrderFurnitureMovements(any(), any(), any(), any(), anyList(), anyList());
    verify(dependencies, never())
        .replaceOrderUnits(
            any(), any(), any(), any(), any(), any(), any(), anyList(), anyList());
    verify(furnitureTasks, never()).checkpointReplacements(any());
  }

  @Test
  void clientTwoPairReplacementPreservesSubmittedMappingInOneAssetBatch() {
    UUID bookingId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    OrderDetailResponse order = order(OLD_1, OLD_2);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        List.of(
            new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(NEW_1, List.of()),
            new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(NEW_2, List.of()));
    List<ReplacementCheckpoint> checkpoints =
        List.of(
            checkpoint(bookingId, null, OLD_1, NEW_2, 0, presentationId, null),
            checkpoint(bookingId, null, OLD_2, NEW_1, 1, presentationId, null));
    LogisticsDependencyGateway.OrderUnitsReplacementReceipt receipt =
        new LogisticsDependencyGateway.OrderUnitsReplacementReceipt(
            List.of(
                new LogisticsDependencyGateway.OrderUnitReplacementReceipt(
                    null, null, List.of(), false),
                new LogisticsDependencyGateway.OrderUnitReplacementReceipt(
                    null, null, List.of(), true)),
            false);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of());
    when(reservations.replacementComposition(eq(ORDER_ID), any())).thenReturn(composition);
    when(dependencies.planOrderFurnitureMovements(any(), any(), any(), any(), anyList(), anyList()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                    ORDER_ID, invocation.getArgument(2), "СПБ", List.of()));
    when(furnitureTasks.replacementBatch(ORDER_ID, bookingId)).thenReturn(checkpoints);
    when(dependencies.replaceOrderUnits(
            any(), any(), any(), any(), any(), any(), anyList(), anyList()))
        .thenReturn(receipt);
    when(reservations.finalizeReplacements(ORDER_ID, bookingId, receipt))
        .thenReturn(new RentalOrderCommandOutcome(order, false));

    service.replaceFromPresentation(
        actor, ORDER_ID, presentationId, bookingId, List.of(OLD_1, OLD_2), List.of(NEW_2, NEW_1));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ReplacementPreparation>> prepared = ArgumentCaptor.forClass(List.class);
    verify(furnitureTasks).checkpointReplacements(prepared.capture());
    assertThat(prepared.getValue())
        .extracting(value -> value.command().oldRentalItemId())
        .containsExactly(OLD_1, OLD_2);
    assertThat(prepared.getValue())
        .extracting(value -> value.command().replacementRentalItemId())
        .containsExactly(NEW_2, NEW_1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LogisticsDependencyGateway.OrderUnitReplacement>> replacements =
        ArgumentCaptor.forClass(List.class);
    verify(dependencies)
        .replaceOrderUnits(
            eq(bookingId),
            eq(ORDER_ID),
            eq(WAREHOUSE_ID),
            eq(presentationId),
            eq(ACTOR_ID),
            eq("WAREHOUSE_MANAGER"),
            eq(composition),
            replacements.capture());
    assertThat(replacements.getValue())
        .extracting(LogisticsDependencyGateway.OrderUnitReplacement::rentalItemId)
        .containsExactly(OLD_1, OLD_2);
    assertThat(replacements.getValue())
        .extracting(LogisticsDependencyGateway.OrderUnitReplacement::replacementRentalItemId)
        .containsExactly(NEW_2, NEW_1);
  }

  @Test
  void permanentTwoPairAssetRejectionNeverFinalizesLocalOrder() {
    UUID bookingId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    OrderDetailResponse order = order(OLD_1, OLD_2);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of());
    when(reservations.replacementComposition(eq(ORDER_ID), any()))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(NEW_1, List.of()),
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(NEW_2, List.of())));
    when(dependencies.planOrderFurnitureMovements(any(), any(), any(), any(), anyList(), anyList()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                    ORDER_ID, invocation.getArgument(2), "СПБ", List.of()));
    when(furnitureTasks.replacementBatch(ORDER_ID, bookingId))
        .thenReturn(
            List.of(
                checkpoint(bookingId, null, OLD_1, NEW_1, 0, presentationId, null),
                checkpoint(bookingId, null, OLD_2, NEW_2, 1, presentationId, null)));
    when(dependencies.replaceOrderUnits(
            any(), any(), any(), any(), any(), any(), anyList(), anyList()))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
                "SECOND_PAIR_INVALID",
                "second pair invalid",
                null));

    assertThatThrownBy(
            () ->
                service.replaceFromPresentation(
                    actor,
                    ORDER_ID,
                    presentationId,
                    bookingId,
                    List.of(OLD_1, OLD_2),
                    List.of(NEW_1, NEW_2)))
        .isInstanceOf(LogisticsDependencyException.class);

    verify(reservations, never()).finalizeReplacements(any(), any(), any());
    verify(furnitureTasks).rejectReplacementBatch(ORDER_ID, bookingId, "SECOND_PAIR_INVALID");
  }

  @Test
  void crashAfterAssetSuccessReplaysTheSameBatchAndConvergesLocalFinalize() {
    UUID key = UUID.randomUUID();
    OrderDetailResponse order = order(OLD_1);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        List.of(new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(NEW_1, List.of()));
    ReplacementCheckpoint checkpoint = checkpoint(key, null, OLD_1, NEW_1, 0, null, null);
    LogisticsDependencyGateway.OrderUnitsReplacementReceipt receipt =
        new LogisticsDependencyGateway.OrderUnitsReplacementReceipt(
            List.of(
                new LogisticsDependencyGateway.OrderUnitReplacementReceipt(
                    null, null, List.of(), false)),
            false);
    when(reads.get(actor, ORDER_ID)).thenReturn(order);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            any(), eq(ORDER_ID)))
        .thenReturn(List.of());
    when(reservations.replacementComposition(eq(ORDER_ID), any())).thenReturn(composition);
    when(dependencies.planOrderFurnitureMovements(any(), any(), any(), any(), anyList(), anyList()))
        .thenReturn(
            new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                ORDER_ID, NEW_1, "СПБ-002", List.of()));
    when(furnitureTasks.replacementBatch(ORDER_ID, key)).thenReturn(List.of(checkpoint));
    when(dependencies.replaceOrderUnits(
            eq(key),
            eq(ORDER_ID),
            eq(WAREHOUSE_ID),
            eq(null),
            eq(ACTOR_ID),
            eq("WAREHOUSE_MANAGER"),
            eq(composition),
            anyList()))
        .thenReturn(receipt);
    when(reservations.finalizeReplacements(ORDER_ID, key, receipt))
        .thenThrow(new RuntimeException("simulated local crash"))
        .thenReturn(new RentalOrderCommandOutcome(order, true));

    assertThatThrownBy(
            () -> service.replaceDirect(actor, ORDER_ID, 5, OLD_1, NEW_1, "неисправность", key))
        .isInstanceOf(OrderProblemException.class);

    OrderDetailResponse recovered =
        service.replaceDirect(actor, ORDER_ID, 5, OLD_1, NEW_1, "неисправность", key);

    assertThat(recovered).isSameAs(order);
    verify(dependencies, times(2))
        .replaceOrderUnits(
            eq(key),
            eq(ORDER_ID),
            eq(WAREHOUSE_ID),
            eq(null),
            eq(ACTOR_ID),
            eq("WAREHOUSE_MANAGER"),
            eq(composition),
            anyList());
    verify(reservations, times(2)).finalizeReplacements(ORDER_ID, key, receipt);
  }

  private OrderDetailResponse order(UUID... unitIds) {
    OrderDetailResponse order = mock(OrderDetailResponse.class);
    when(order.id()).thenReturn(ORDER_ID);
    when(order.version()).thenReturn(5L);
    when(order.status()).thenReturn(RentalOrderStatus.SAVED);
    when(order.warehouseId()).thenReturn(WAREHOUSE_ID);
    List<OrderUnitResponse> rows =
        java.util.Arrays.stream(unitIds)
            .map(
                unitId -> {
                  OrderRentalItemResponse unit = mock(OrderRentalItemResponse.class);
                  when(unit.id()).thenReturn(unitId);
                  OrderUnitResponse row = mock(OrderUnitResponse.class);
                  when(row.unit()).thenReturn(unit);
                  return row;
                })
            .toList();
    when(order.units()).thenReturn(rows);
    return order;
  }

  private LogisticsDocument waitingDocument(UUID oldRentalItemId) {
    LogisticsDocument document = mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(DOCUMENT_ID);
    when(document.getState()).thenReturn(LogisticsDocumentState.DRAFT);
    LogisticsDocumentLine line = mock(LogisticsDocumentLine.class);
    when(line.getAssetId()).thenReturn(oldRentalItemId);
    when(line.getState()).thenReturn(LogisticsLineState.PENDING);
    when(documentLines.findAllByDocument_IdOrderByLineNumber(DOCUMENT_ID))
        .thenReturn(List.of(line));
    return document;
  }

  private ReplacementCheckpoint checkpoint(
      UUID batchKey,
      UUID documentId,
      UUID oldUnitId,
      UUID newUnitId,
      int index,
      UUID presentationId,
      UUID movementTaskId) {
    return new ReplacementCheckpoint(
        UUID.randomUUID(),
        ORDER_ID,
        WAREHOUSE_ID,
        documentId,
        oldUnitId,
        newUnitId,
        null,
        ACTOR_ID,
        "WAREHOUSE_MANAGER",
        UUID.randomUUID(),
        batchKey,
        index,
        "a".repeat(64),
        presentationId,
        movementTaskId,
        null,
        null,
        null);
  }
}
