package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessState;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.mapper.ShipmentFurnitureTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCommandRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;

class ShipmentFurnitureTaskServiceTest {
  private static final UUID SHIPMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000009201");
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000009202");
  private static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-000000009203");
  private static final UUID UNIT_1 = UUID.fromString("00000000-0000-0000-0000-000000009211");
  private static final UUID UNIT_2 = UUID.fromString("00000000-0000-0000-0000-000000009212");
  private static final UUID UNIT_3 = UUID.fromString("00000000-0000-0000-0000-000000009213");
  private static final UUID EQUIPMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000009221");

  @Test
  void draftReplacementCanPrepareFurnitureBeforeAPaymentWindowExists() {
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.DRAFT);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    when(orders.findForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    ShipmentFurnitureMovementTaskRepository links =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    when(links.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EquipmentMovementTaskService movement = mock(EquipmentMovementTaskService.class);
    EquipmentMovementTaskResponse movementResponse = mock(EquipmentMovementTaskResponse.class);
    UUID movementId = UUID.randomUUID();
    when(movementResponse.id()).thenReturn(movementId);
    when(movement.create(any(), any(), any(), any()))
        .thenReturn(new EquipmentMovementTaskService.CreateResult(movementResponse, false));
    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            mock(LogisticsDocumentRepository.class),
            mock(LogisticsDocumentLineRepository.class),
            orders,
            mock(RentalOrderMutationCommandRepository.class),
            mock(RentalOrderEquipmentRequirementRepository.class),
            links,
            mock(LogisticsDependencyGateway.class),
            movement,
            mock(LogisticsWarehouseLifecycle.class),
            mock(ShipmentFurnitureTaskResponseMapper.class));
    var command =
        new ShipmentFurnitureTaskService.ReplacementCheckpointCommand(
            ORDER_ID,
            0,
            WAREHOUSE_ID,
            UNIT_1,
            UNIT_2,
            "replacement",
            UUID.randomUUID(),
            "WAREHOUSE_MANAGER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            "a".repeat(64),
            null);
    var line =
        new LogisticsDependencyGateway.OrderFurnitureMovementPlanLine(
            EQUIPMENT_ID,
            "Стол",
            UUID.randomUUID(),
            WAREHOUSE_ID,
            UNIT_1,
            "CABIN_NON_RENTED",
            0,
            WAREHOUSE_ID,
            UNIT_2,
            "CABIN_NON_RENTED",
            1);
    var plan =
        new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
            ORDER_ID, UNIT_2, "СПБ-002", List.of(line));

    ShipmentFurnitureMovementTask checkpoint = service.checkpointReplacement(command, plan);

    assertThat(order.getPaymentState()).isNull();
    assertThat(checkpoint.getOrder()).isSameAs(order);
    assertThat(checkpoint.getDocument()).isNull();
    assertThat(checkpoint.getEquipmentMovementTaskId()).isEqualTo(movementId);
    assertThat(checkpoint.getLineCount()).isEqualTo(1);
    verify(movement).freezeReplacementSources(movementId, List.of(line));
    verify(movement).deferReplacementPreparation(movementId);
  }

  @ParameterizedTest
  @NullSource
  @EnumSource(
      value = RentalOrderPaymentState.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = "CONFIRMED")
  void unpaidOrderCannotReportFurnitureReadyOrStartReplacementWork(
      RentalOrderPaymentState paymentState) {
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(order.getPaymentState()).thenReturn(paymentState);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    when(orders.findForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    when(orders.findWithClientById(ORDER_ID)).thenReturn(Optional.of(order));
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocument shipment = mock(LogisticsDocument.class);
    when(shipment.getDocumentType()).thenReturn(LogisticsDocumentType.SHIPMENT);
    when(shipment.getRentalOrderId()).thenReturn(ORDER_ID);
    when(shipment.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(documents.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
    ShipmentFurnitureMovementTaskRepository links =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movement = mock(EquipmentMovementTaskService.class);
    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            documents,
            mock(LogisticsDocumentLineRepository.class),
            orders,
            mock(RentalOrderMutationCommandRepository.class),
            mock(RentalOrderEquipmentRequirementRepository.class),
            links,
            dependencies,
            movement,
            mock(LogisticsWarehouseLifecycle.class),
            mock(ShipmentFurnitureTaskResponseMapper.class));
    assertThatThrownBy(() -> service.readiness(SHIPMENT_ID))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("ORDER_PAYMENT_REQUIRED"));
    var command =
        new ShipmentFurnitureTaskService.ReplacementCheckpointCommand(
            ORDER_ID,
            1,
            WAREHOUSE_ID,
            UNIT_1,
            UNIT_2,
            "replacement",
            UUID.randomUUID(),
            "WAREHOUSE_MANAGER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            "a".repeat(64),
            null);
    assertThatThrownBy(() -> service.checkpointReplacement(command, null))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("ORDER_PAYMENT_REQUIRED"));
    verifyNoInteractions(links, dependencies, movement);
  }

  @Test
  void replacementCheckpointRejectsOpenOrderMutationAfterLockingOrder() {
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderMutationCommandRepository orderMutations =
        mock(RentalOrderMutationCommandRepository.class);
    RentalOrder order = mock(RentalOrder.class);
    // This scenario reaches the mutation fence only after payment admission succeeds.
    when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.CONFIRMED);
    when(orders.findForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    when(orderMutations.existsByOrder_IdAndStateIn(
            ORDER_ID, Set.of(State.PENDING, State.QUARANTINED)))
        .thenReturn(true);
    ShipmentFurnitureMovementTaskRepository taskLinks =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            mock(LogisticsDocumentRepository.class),
            mock(LogisticsDocumentLineRepository.class),
            orders,
            orderMutations,
            mock(RentalOrderEquipmentRequirementRepository.class),
            taskLinks,
            mock(LogisticsDependencyGateway.class),
            mock(EquipmentMovementTaskService.class),
            mock(LogisticsWarehouseLifecycle.class),
            mock(ShipmentFurnitureTaskResponseMapper.class));
    ShipmentFurnitureTaskService.ReplacementCheckpointCommand command =
        new ShipmentFurnitureTaskService.ReplacementCheckpointCommand(
            ORDER_ID,
            1,
            WAREHOUSE_ID,
            UNIT_1,
            UNIT_2,
            "replacement",
            UUID.randomUUID(),
            "WAREHOUSE_MANAGER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            "a".repeat(64),
            null);

    assertThatThrownBy(() -> service.checkpointReplacement(command, null))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("ORDER_MUTATION_PENDING"));
    verify(orders).findForUpdate(ORDER_ID);
    verify(orderMutations)
        .existsByOrder_IdAndStateIn(ORDER_ID, Set.of(State.PENDING, State.QUARANTINED));
    verifyNoInteractions(taskLinks);
  }

  @Test
  void skipsFurniturePlanningWhenSavedOrderHasNoFurnitureRequirements() {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository documentLines = mock(LogisticsDocumentLineRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderEquipmentRequirementRepository requirements =
        mock(RentalOrderEquipmentRequirementRepository.class);
    ShipmentFurnitureMovementTaskRepository taskLinks =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    ShipmentFurnitureTaskResponseMapper mapper = mock(ShipmentFurnitureTaskResponseMapper.class);
    LogisticsWarehouseLifecycle warehouseLifecycle = mock(LogisticsWarehouseLifecycle.class);

    LogisticsDocument shipment = mock(LogisticsDocument.class);
    when(shipment.getId()).thenReturn(SHIPMENT_ID);
    when(shipment.getVersion()).thenReturn(7L);
    when(shipment.getDocumentType()).thenReturn(LogisticsDocumentType.SHIPMENT);
    when(shipment.getRentalOrderId()).thenReturn(ORDER_ID);
    when(shipment.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(documents.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.CONFIRMED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(orders.findWithClientById(ORDER_ID)).thenReturn(Optional.of(order));
    when(requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            ORDER_ID))
        .thenReturn(List.of());

    LogisticsDocumentLine shipmentLine = mock(LogisticsDocumentLine.class);
    when(documentLines.findAllByDocument_IdOrderByLineNumber(SHIPMENT_ID))
        .thenReturn(List.of(shipmentLine));
    when(taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(SHIPMENT_ID)).thenReturn(List.of());

    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            documents,
            documentLines,
            orders,
            mock(RentalOrderMutationCommandRepository.class),
            requirements,
            taskLinks,
            dependencies,
            movementTasks,
            warehouseLifecycle,
            mapper);

    var readiness = service.readiness(SHIPMENT_ID);

    assertThat(readiness.shipmentId()).isEqualTo(SHIPMENT_ID);
    assertThat(readiness.shipmentVersion()).isEqualTo(7);
    assertThat(readiness.state()).isEqualTo(ShipmentFurnitureReadinessState.NOT_REQUIRED);
    assertThat(readiness.tasks()).isEmpty();
    verifyNoInteractions(dependencies, movementTasks, mapper);
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void readinessPreservesEveryCabinAndReportsMissingOrCompletedFurnitureTasks(
      boolean needsFurniture, boolean completedTask) throws Exception {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository documentLines = mock(LogisticsDocumentLineRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderEquipmentRequirementRepository requirements =
        mock(RentalOrderEquipmentRequirementRepository.class);
    ShipmentFurnitureMovementTaskRepository taskLinks =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    ShipmentFurnitureTaskResponseMapper mapper = mock(ShipmentFurnitureTaskResponseMapper.class);
    LogisticsWarehouseLifecycle warehouseLifecycle = mock(LogisticsWarehouseLifecycle.class);
    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            documents,
            documentLines,
            orders,
            mock(RentalOrderMutationCommandRepository.class),
            requirements,
            taskLinks,
            dependencies,
            movementTasks,
            warehouseLifecycle,
            mapper);

    LogisticsDocument shipment = mock(LogisticsDocument.class);
    when(shipment.getId()).thenReturn(SHIPMENT_ID);
    when(shipment.getVersion()).thenReturn(7L);
    when(shipment.getDocumentType()).thenReturn(LogisticsDocumentType.SHIPMENT);
    when(shipment.getRentalOrderId()).thenReturn(ORDER_ID);
    when(shipment.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(documents.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.CONFIRMED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(orders.findWithClientById(ORDER_ID)).thenReturn(Optional.of(order));
    RentalOrderEquipmentRequirement desired = mock(RentalOrderEquipmentRequirement.class);
    when(desired.getRentalItemId()).thenReturn(UNIT_1);
    when(desired.getEquipmentId()).thenReturn(EQUIPMENT_ID);
    when(desired.getEquipmentName()).thenReturn("Кровать");
    when(desired.getQuantity()).thenReturn(4L);
    when(requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            ORDER_ID))
        .thenReturn(List.of(desired));
    LogisticsDocumentLine first = mock(LogisticsDocumentLine.class);
    LogisticsDocumentLine second = mock(LogisticsDocumentLine.class);
    when(first.getAssetId()).thenReturn(UNIT_1);
    when(first.getInventorySourceWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(second.getAssetId()).thenReturn(UNIT_2);
    when(second.getInventorySourceWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(documentLines.findAllByDocument_IdOrderByLineNumber(SHIPMENT_ID))
        .thenReturn(List.of(first, second));
    var existingLinks = new java.util.ArrayList<ShipmentFurnitureMovementTask>();
    if (completedTask) {
      for (UUID unitId : List.of(UNIT_1, UNIT_2)) {
        UUID taskId = UUID.randomUUID();
        ShipmentFurnitureMovementTask link = mock(ShipmentFurnitureMovementTask.class);
        when(link.getRentalItemId()).thenReturn(unitId);
        when(link.getUnitNumber()).thenReturn("СПБ");
        when(link.getEquipmentMovementTaskId()).thenReturn(taskId);
        when(link.getLineCount()).thenReturn(1);
        EquipmentMovementTask task = mock(EquipmentMovementTask.class);
        when(task.getExternalTaskId()).thenReturn(UUID.randomUUID());
        when(task.getState()).thenReturn(EquipmentMovementTaskState.COMPLETED);
        when(movementTasks.required(taskId)).thenReturn(task);
        existingLinks.add(link);
      }
    }
    when(taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(SHIPMENT_ID)).thenReturn(existingLinks);
    when(dependencies.readOrderUnits(ORDER_ID))
        .thenReturn(List.of(reservation(UNIT_1), reservation(UNIT_2), reservation(UNIT_3)));
    when(dependencies.planOrderFurnitureMovements(
            org.mockito.ArgumentMatchers.eq(ORDER_ID),
            org.mockito.ArgumentMatchers.eq(WAREHOUSE_ID),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                    ORDER_ID,
                    invocation.getArgument(2),
                    "СПБ",
                    needsFurniture
                        ? List.of(
                            new LogisticsDependencyGateway.OrderFurnitureMovementPlanLine(
                                EQUIPMENT_ID,
                                "Кровать",
                                UUID.randomUUID(),
                                WAREHOUSE_ID,
                                null,
                                "STOCK",
                                1,
                                WAREHOUSE_ID,
                                invocation.getArgument(2),
                                "CABIN_NON_RENTED",
                                1))
                        : List.of()));

    var readiness = service.readiness(SHIPMENT_ID);

    assertThat(readiness.state())
        .isEqualTo(
            needsFurniture
                ? completedTask
                    ? ShipmentFurnitureReadinessState.BLOCKED
                    : ShipmentFurnitureReadinessState.REQUIRES_TASK_CREATION
                : ShipmentFurnitureReadinessState.READY);
    assertThat(readiness.tasks())
        .hasSize(2)
        .allSatisfy(
            task -> {
              assertThat(task.movementTaskCreated()).isEqualTo(completedTask);
              assertThat(task.movementTaskCompleted()).isEqualTo(completedTask);
              assertThat(task.contentReady()).isEqualTo(!needsFurniture);
              if (!completedTask) {
                assertThat(task.taskId()).isNull();
                assertThat(task.externalTaskId()).isNull();
                assertThat(task.taskState()).isNull();
                assertThat(task.lineCount()).isZero();
              }
            });
    assertReadinessMatchesCanonicalSchema(readiness);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements>> composition =
        ArgumentCaptor.forClass(List.class);
    verify(dependencies, org.mockito.Mockito.times(2))
        .planOrderFurnitureMovements(
            org.mockito.ArgumentMatchers.eq(ORDER_ID),
            org.mockito.ArgumentMatchers.eq(WAREHOUSE_ID),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.anyList(),
            composition.capture());
    assertThat(composition.getAllValues())
        .allSatisfy(
            units ->
                assertThat(units)
                    .extracting(
                        LogisticsDependencyGateway.OrderUnitEquipmentRequirements::rentalItemId)
                    .containsExactly(UNIT_1, UNIT_2, UNIT_3));
  }

  @Test
  void completedOrderLevelReplacementMovementAttachesToLaterShipmentWithoutNewTask() {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository documentLines = mock(LogisticsDocumentLineRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderEquipmentRequirementRepository requirements =
        mock(RentalOrderEquipmentRequirementRepository.class);
    ShipmentFurnitureMovementTaskRepository taskLinks =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    ShipmentFurnitureTaskResponseMapper mapper = mock(ShipmentFurnitureTaskResponseMapper.class);
    LogisticsWarehouseLifecycle warehouseLifecycle = mock(LogisticsWarehouseLifecycle.class);
    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            documents,
            documentLines,
            orders,
            mock(RentalOrderMutationCommandRepository.class),
            requirements,
            taskLinks,
            dependencies,
            movementTasks,
            warehouseLifecycle,
            mapper);
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    UUID movementTaskId = UUID.randomUUID();
    ShipmentFurnitureMovementTask link =
        ShipmentFurnitureMovementTask.createReplacement(
            order,
            null,
            UNIT_1,
            UNIT_2,
            "СПБ-002",
            movementTaskId,
            1,
            "неисправность",
            UUID.randomUUID(),
            "WAREHOUSE_MANAGER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            "a".repeat(64),
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    link.completeReplacement(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
    LogisticsDocument shipment = mock(LogisticsDocument.class);
    when(shipment.getId()).thenReturn(SHIPMENT_ID);
    when(shipment.getDocumentType()).thenReturn(LogisticsDocumentType.SHIPMENT);
    when(shipment.getRentalOrderId()).thenReturn(ORDER_ID);
    when(taskLinks.findAttachableReplacementMovementsForUpdate(ORDER_ID, java.util.Set.of(UNIT_2)))
        .thenReturn(List.of(link));

    service.attachReplacementMovementsToShipment(shipment, List.of(UNIT_2));

    assertThat(link.getDocument()).isSameAs(shipment);
    assertThat(link.getEquipmentMovementTaskId()).isEqualTo(movementTaskId);
    verify(taskLinks).saveAllAndFlush(List.of(link));
    verifyNoInteractions(dependencies, movementTasks);
  }

  @Test
  void pendingReplacementCheckpointFencesOldCabinTaskUntilPermanentRejection() {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository documentLines = mock(LogisticsDocumentLineRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderEquipmentRequirementRepository requirements =
        mock(RentalOrderEquipmentRequirementRepository.class);
    ShipmentFurnitureMovementTaskRepository taskLinks =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    ShipmentFurnitureTaskResponseMapper mapper = mock(ShipmentFurnitureTaskResponseMapper.class);
    LogisticsWarehouseLifecycle warehouseLifecycle = mock(LogisticsWarehouseLifecycle.class);
    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            documents,
            documentLines,
            orders,
            mock(RentalOrderMutationCommandRepository.class),
            requirements,
            taskLinks,
            dependencies,
            movementTasks,
            warehouseLifecycle,
            mapper);
    LogisticsDocument shipment = mock(LogisticsDocument.class);
    when(shipment.getId()).thenReturn(SHIPMENT_ID);
    when(shipment.getVersion()).thenReturn(7L);
    when(shipment.getDocumentType()).thenReturn(LogisticsDocumentType.SHIPMENT);
    when(shipment.getState()).thenReturn(LogisticsDocumentState.DRAFT);
    when(shipment.getRentalOrderId()).thenReturn(ORDER_ID);
    when(shipment.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(documents.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
    when(documents.findForUpdate(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.CONFIRMED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(orders.findForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    when(requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            ORDER_ID))
        .thenReturn(List.of());
    LogisticsDocumentLine line = mock(LogisticsDocumentLine.class);
    when(line.getAssetId()).thenReturn(UNIT_1);
    when(line.getInventorySourceWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(documentLines.findAllByDocument_IdOrderByLineNumber(SHIPMENT_ID))
        .thenReturn(List.of(line));
    when(taskLinks
            .existsByOrder_IdAndOldRentalItemIdInAndReplacementCompletedAtIsNullAndReplacementRejectedAtIsNull(
                ORDER_ID, java.util.Set.of(UNIT_1)))
        .thenReturn(true, false);
    when(taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(SHIPMENT_ID)).thenReturn(List.of());
    when(dependencies.readOrderUnits(ORDER_ID)).thenReturn(List.of(reservation(UNIT_1)));
    when(dependencies.planOrderFurnitureMovements(
            ORDER_ID,
            WAREHOUSE_ID,
            UNIT_1,
            null,
            List.of(),
            List.of(
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(UNIT_1, List.of()))))
        .thenReturn(
            new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                ORDER_ID, UNIT_1, "СПБ-001", List.of()));

    assertThatThrownBy(
            () -> service.createForShipment(UUID.randomUUID(), UUID.randomUUID(), SHIPMENT_ID, 7))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Замена бытовки ещё не завершена");
    verifyNoInteractions(dependencies, movementTasks);

    var afterRejection =
        service.createForShipment(UUID.randomUUID(), UUID.randomUUID(), SHIPMENT_ID, 7);

    assertThat(afterRejection.tasks())
        .singleElement()
        .satisfies(task -> assertThat(task.taskId()).isNull());
    verifyNoInteractions(movementTasks);
  }

  @Test
  void completedReplacementContextUsesReleasedSourceReservationAndFullPostSwapComposition() {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository documentLines = mock(LogisticsDocumentLineRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderEquipmentRequirementRepository requirements =
        mock(RentalOrderEquipmentRequirementRepository.class);
    ShipmentFurnitureMovementTaskRepository taskLinks =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    ShipmentFurnitureTaskResponseMapper mapper = mock(ShipmentFurnitureTaskResponseMapper.class);
    LogisticsWarehouseLifecycle warehouseLifecycle = mock(LogisticsWarehouseLifecycle.class);
    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            documents,
            documentLines,
            orders,
            mock(RentalOrderMutationCommandRepository.class),
            requirements,
            taskLinks,
            dependencies,
            movementTasks,
            warehouseLifecycle,
            mapper);
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    UUID taskId = UUID.randomUUID();
    UUID releasedSourceReservationId = UUID.randomUUID();
    ShipmentFurnitureMovementTask link =
        ShipmentFurnitureMovementTask.createReplacement(
            order,
            null,
            UNIT_1,
            UNIT_2,
            "СПБ-002",
            taskId,
            1,
            "неисправность",
            UUID.randomUUID(),
            "WAREHOUSE_MANAGER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            "a".repeat(64),
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    link.completeReplacement(releasedSourceReservationId, OffsetDateTime.now(ZoneOffset.UTC));
    when(taskLinks.findByEquipmentMovementTaskId(taskId)).thenReturn(Optional.of(link));
    when(orders.findWithClientById(ORDER_ID)).thenReturn(Optional.of(order));
    when(dependencies.readOrderUnits(ORDER_ID))
        .thenReturn(List.of(reservation(UNIT_2), reservation(UNIT_3)));
    RentalOrderEquipmentRequirement desired = mock(RentalOrderEquipmentRequirement.class);
    when(desired.getRentalItemId()).thenReturn(UNIT_2);
    when(desired.getEquipmentId()).thenReturn(EQUIPMENT_ID);
    when(desired.getQuantity()).thenReturn(4L);
    when(requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            ORDER_ID))
        .thenReturn(List.of(desired));

    ShipmentFurnitureTaskService.OrderMovementReservationContext context =
        service.movementReservationContext(taskId, UNIT_1);

    assertThat(context.orderId()).isEqualTo(ORDER_ID);
    assertThat(context.targetRentalItemId()).isEqualTo(UNIT_2);
    assertThat(context.replacementSourceReservationId()).isEqualTo(releasedSourceReservationId);
    assertThat(context.units())
        .extracting(LogisticsDependencyGateway.OrderUnitEquipmentRequirements::rentalItemId)
        .containsExactly(UNIT_2, UNIT_3);
    assertThat(context.units().getFirst().requirements())
        .singleElement()
        .satisfies(
            requirement -> {
              assertThat(requirement.equipmentId()).isEqualTo(EQUIPMENT_ID);
              assertThat(requirement.quantity()).isEqualTo(4);
            });
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(UUID unitId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.OrderRentalItem item =
        new LogisticsDependencyGateway.OrderRentalItem(
            unitId,
            1,
            WAREHOUSE_ID,
            unitId.toString(),
            "BOOKED",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            now,
            now);
    return new LogisticsDependencyGateway.OrderUnitReservation(
        UUID.randomUUID(),
        1,
        ORDER_ID,
        unitId,
        WAREHOUSE_ID,
        "ACTIVE",
        UUID.randomUUID(),
        "RENTAL_MANAGER",
        now,
        null,
        false,
        item);
  }

  private static void assertReadinessMatchesCanonicalSchema(
      ShipmentFurnitureReadinessView readiness) throws Exception {
    var schemaJson = new com.fasterxml.jackson.databind.ObjectMapper();
    ObjectNode contract;
    try (var input =
        Files.newInputStream(
            Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml"))) {
      contract = schemaJson.valueToTree(new Yaml().load(input));
    }
    contract.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    contract.put("$ref", "#/components/schemas/ShipmentFurnitureReadiness");
    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(contract);
    var wire =
        schemaJson.readTree(
            new tools.jackson.databind.json.JsonMapper().writeValueAsString(readiness));
    assertThat(schema.validate(wire)).isEmpty();
    for (String flag : List.of("movementTaskCreated", "movementTaskCompleted", "contentReady")) {
      ObjectNode missing = wire.deepCopy();
      ((ObjectNode) missing.required("tasks").required(0)).remove(flag);
      assertThat(schema.validate(missing)).as("required flag %s", flag).isNotEmpty();
    }
  }
}
