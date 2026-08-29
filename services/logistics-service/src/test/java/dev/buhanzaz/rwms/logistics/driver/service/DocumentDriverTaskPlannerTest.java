package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/** Verifies document-derived task planning without crossing the task-board transport boundary. */
class DocumentDriverTaskPlannerTest {
  private final DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
  private final LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final DriverTaskWorkflowStore workflowStore = mock(DriverTaskWorkflowStore.class);
  private final ShipmentTaskSettingsService shipmentTaskSettings =
      mock(ShipmentTaskSettingsService.class);
  private final CustomerDeliveryCapacityFence capacityFence =
      mock(CustomerDeliveryCapacityFence.class);
  private final DriverTaskWorkerContentCodec workerContentCodec =
      new DriverTaskWorkerContentCodec(new ObjectMapper());
  private final DocumentDriverTaskPlanner planner =
      new DocumentDriverTaskPlanner(
          tasks,
          documents,
          dependencies,
          workflowStore,
          shipmentTaskSettings,
          capacityFence,
          workerContentCodec);

  @Test
  void shipmentCreatesOneAssignedFixedDateGroupTaskAndReplaysByChecksum() {
    UUID warehouseId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    UUID firstAssetId = UUID.randomUUID();
    UUID secondAssetId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 8, 12);
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            warehouseId,
            UUID.randomUUID(),
            "ООО Клиент",
            "Иван Петров",
            workerId,
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment("Иван Петров", workerId, date);
    LogisticsDocumentLine firstLine = persistedLine(document, firstAssetId, 1);
    LogisticsDocumentLine secondLine = persistedLine(document, secondAssetId, 2);
    persist(document);
    stubDependencies(warehouseId, queueDefinitionId, firstAssetId, "БТ-101");
    stubDependencies(warehouseId, queueDefinitionId, secondAssetId, "БТ-102");
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.SHIPMENT))
        .thenReturn(Optional.empty());

    planner.plan(document, List.of(firstLine, secondLine));

    ArgumentCaptor<DriverLogisticsTask> taskCaptor =
        ArgumentCaptor.forClass(DriverLogisticsTask.class);
    verify(tasks).saveAndFlush(taskCaptor.capture());
    DriverLogisticsTask created = taskCaptor.getValue();
    assertThat(created.getWarehouseId()).isEqualTo(warehouseId);
    assertThat(created.getCabinId()).isEqualTo(firstAssetId);
    assertThat(created.getSourceType()).isEqualTo(DriverTaskSourceType.LOGISTICS_DOCUMENT);
    assertThat(created.getSourceId()).isEqualTo(document.getId());
    assertThat(created.getKind()).isEqualTo(DriverTaskKind.SHIPMENT);
    assertThat(created.getPlanningMode()).isEqualTo(DriverTaskPlanningMode.FIXED_DATE);
    assertThat(created.getScheduledDate()).isEqualTo(date);
    assertThat(created.getPriority()).isEqualTo(3);
    assertThat(created.getUnitNumber()).isEqualTo("2 бытовки");
    assertThat(created.getClientSnapshot()).isEqualTo("ООО Клиент");
    assertThat(created.getComment()).contains("Клиент: ООО Клиент", "БТ-101", "БТ-102");
    assertThat(created.getMembers())
        .extracting(
            member ->
                List.of(
                    member.getDocumentLineId(),
                    member.getCabinId(),
                    member.getUnitNumber(),
                    member.getPosition()))
        .containsExactly(
            List.of(firstLine.getId(), firstAssetId, "БТ-101", 1),
            List.of(secondLine.getId(), secondAssetId, "БТ-102", 2));
    assertThat(created.getDriverAudienceMode()).isEqualTo(DriverTaskAudienceMode.ASSIGNED_DRIVER);
    assertThat(created.getPlannedDriverWorkerId()).isEqualTo(workerId);
    assertThat(created.getPlannedDriverNameSnapshot()).isEqualTo("Иван Петров");

    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.SHIPMENT))
        .thenReturn(Optional.of(created));
    planner.plan(document, List.of(firstLine, secondLine));

    verify(tasks, times(1)).saveAndFlush(any(DriverLogisticsTask.class));
    verify(capacityFence, times(2))
        .acquireTaskDay(warehouseId, date, DriverTaskKind.SHIPMENT);
  }

  @Test
  void directRegionalShipmentCreatesDriverTaskAtItsSinglePhysicalSource() {
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 9, 14);
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            serviceWarehouseId,
            UUID.randomUUID(),
            "Региональный клиент",
            "Петров",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment("Петров", document.getDriverWorkerId(), date);
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(
            document, 1, assetId, 7, "Региональный клиент", UUID.randomUUID(), sourceWarehouseId);
    ReflectionTestUtils.setField(line, "id", UUID.randomUUID());
    persist(document);
    stubDependencies(sourceWarehouseId, queueDefinitionId, assetId, "БТ-СП-14");
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.SHIPMENT))
        .thenReturn(Optional.empty());

    planner.plan(document, List.of(line));

    ArgumentCaptor<DriverLogisticsTask> task =
        ArgumentCaptor.forClass(DriverLogisticsTask.class);
    verify(tasks).saveAndFlush(task.capture());
    assertThat(task.getValue().getWarehouseId()).isEqualTo(sourceWarehouseId);
    assertThat(document.getWarehouseId()).isEqualTo(serviceWarehouseId);
    verify(capacityFence).acquireTaskDay(sourceWarehouseId, date, DriverTaskKind.SHIPMENT);
    verify(dependencies).readWarehouseDriverQueue(sourceWarehouseId);
  }

  @Test
  void mixedPhysicalSourcesAreRejectedBeforeAnyDriverTaskIsCreated() {
    UUID serviceWarehouseId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            serviceWarehouseId,
            UUID.randomUUID(),
            "Региональный клиент",
            "Петров",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment(
        "Петров", document.getDriverWorkerId(), LocalDate.of(2026, 9, 14));
    LogisticsDocumentLine first =
        LogisticsDocumentLine.create(
            document, 1, UUID.randomUUID(), 7, "Клиент", UUID.randomUUID(), UUID.randomUUID());
    LogisticsDocumentLine second =
        LogisticsDocumentLine.create(
            document, 2, UUID.randomUUID(), 7, "Клиент", UUID.randomUUID(), UUID.randomUUID());
    ReflectionTestUtils.setField(first, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
    persist(document);

    assertThatThrownBy(() -> planner.plan(document, List.of(first, second)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("несколько складов-источников");
    verify(tasks, never()).saveAndFlush(any());
  }

  @Test
  void groupedShipmentReplansBeforeExternalRegistrationWithoutChangingMembers() {
    UUID warehouseId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            warehouseId,
            UUID.randomUUID(),
            "ООО Клиент",
            "Иван Петров",
            workerId,
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment("Иван Петров", workerId, LocalDate.of(2026, 8, 17));
    LogisticsDocumentLine line = persistedLine(document, assetId);
    persist(document);
    stubDependencies(warehouseId, queueDefinitionId, assetId, "БТ-103");
    DriverLogisticsTask existing =
        DriverLogisticsTask.createGroupedShipment(
            warehouseId,
            assetId,
            document.getId(),
            LocalDate.of(2026, 8, 16),
            3,
            "Клиент: ООО Клиент. Бытовки: БТ-103",
            "ООО Клиент",
            "1 бытовка",
            queueDefinitionId,
            DriverTaskAudienceMode.ASSIGNED_DRIVER,
            workerId,
            "Иван Петров",
            document.getRequestedBySubjectId(),
            UUID.randomUUID(),
            "a".repeat(64));
    existing.addGroupedShipmentMember(line.getId(), assetId, "БТ-103", 1);
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.SHIPMENT))
        .thenReturn(Optional.of(existing));

    planner.plan(document, List.of(line));

    assertThat(existing.getScheduledDate()).isEqualTo(document.getScheduledDate());
    assertThat(existing.getMembers())
        .extracting(member -> member.getCabinId())
        .containsExactly(assetId);
    verify(tasks).saveAndFlush(existing);
  }

  @Test
  void groupedShipmentCountSummaryUsesRussianCabinInflection() {
    assertThat(DocumentDriverTaskPlanner.groupedUnitSummary(1)).isEqualTo("1 бытовка");
    assertThat(DocumentDriverTaskPlanner.groupedUnitSummary(2)).isEqualTo("2 бытовки");
    assertThat(DocumentDriverTaskPlanner.groupedUnitSummary(5)).isEqualTo("5 бытовок");
    assertThat(DocumentDriverTaskPlanner.groupedUnitSummary(21)).isEqualTo("21 бытовка");
  }

  @Test
  void preStartLegacyShipmentLinesAreCancelledBeforeOneGroupedTripIsCreated() {
    UUID warehouseId = UUID.randomUUID();
    UUID firstAssetId = UUID.randomUUID();
    UUID secondAssetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            warehouseId,
            UUID.randomUUID(),
            "ООО Клиент",
            "Иван Петров",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment(
        "Иван Петров", document.getDriverWorkerId(), LocalDate.of(2026, 8, 19));
    LogisticsDocumentLine firstLine = persistedLine(document, firstAssetId, 1);
    LogisticsDocumentLine secondLine = persistedLine(document, secondAssetId, 2);
    persist(document);
    UUID queueDefinitionId = UUID.randomUUID();
    stubDependencies(warehouseId, queueDefinitionId, firstAssetId, "БТ-105");
    stubDependencies(warehouseId, queueDefinitionId, secondAssetId, "БТ-106");
    DriverLogisticsTask firstLegacy =
        legacyTask(document, firstLine, firstAssetId, "БТ-105", queueDefinitionId);
    DriverLogisticsTask secondLegacy =
        legacyTask(document, secondLine, secondAssetId, "БТ-106", queueDefinitionId);
    when(tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            List.of(firstLine.getId(), secondLine.getId())))
        .thenReturn(List.of(firstLegacy, secondLegacy));
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.SHIPMENT))
        .thenReturn(Optional.empty());
    when(dependencies.readDriverTask(firstLegacy.getExternalTaskId()))
        .thenReturn(waiting(firstLegacy, warehouseId));
    when(dependencies.readDriverTask(secondLegacy.getExternalTaskId()))
        .thenReturn(waiting(secondLegacy, warehouseId));
    when(dependencies.cancelDriverTaskIfPreStart(any(), anyLong(), anyString()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.DriverTaskPreStartCancellation(
                    LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.CANCELLED,
                    UUID.randomUUID(),
                    invocation.getArgument(0),
                    2,
                    "CANCELLED",
                    null));

    planner.plan(document, List.of(firstLine, secondLine));

    assertThat(firstLegacy.getState()).isEqualTo(DriverTaskState.CANCELLED);
    assertThat(secondLegacy.getState()).isEqualTo(DriverTaskState.CANCELLED);
    ArgumentCaptor<DriverLogisticsTask> created =
        ArgumentCaptor.forClass(DriverLogisticsTask.class);
    verify(tasks, times(3)).saveAndFlush(created.capture());
    assertThat(created.getAllValues().getLast().getSourceType())
        .isEqualTo(DriverTaskSourceType.LOGISTICS_DOCUMENT);
    assertThat(created.getAllValues().getLast().getMembers()).hasSize(2);
  }

  @Test
  void oneStartedLegacyLinePreventsRegroupingBeforeAnySiblingIsCancelled() {
    UUID warehouseId = UUID.randomUUID();
    UUID firstAssetId = UUID.randomUUID();
    UUID secondAssetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            warehouseId,
            UUID.randomUUID(),
            "ООО Клиент",
            "Иван Петров",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment(
        "Иван Петров", document.getDriverWorkerId(), LocalDate.of(2026, 8, 19));
    LogisticsDocumentLine firstLine = persistedLine(document, firstAssetId, 1);
    LogisticsDocumentLine secondLine = persistedLine(document, secondAssetId, 2);
    persist(document);
    UUID queueDefinitionId = UUID.randomUUID();
    stubDependencies(warehouseId, queueDefinitionId, firstAssetId, "БТ-105");
    stubDependencies(warehouseId, queueDefinitionId, secondAssetId, "БТ-106");
    DriverLogisticsTask firstLegacy =
        legacyTask(document, firstLine, firstAssetId, "БТ-105", queueDefinitionId);
    DriverLogisticsTask startedLegacy =
        legacyTask(document, secondLine, secondAssetId, "БТ-106", queueDefinitionId);
    ReflectionTestUtils.setField(startedLegacy, "state", DriverTaskState.FINALIZING);
    when(tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            List.of(firstLine.getId(), secondLine.getId())))
        .thenReturn(List.of(firstLegacy, startedLegacy));
    when(dependencies.readDriverTask(firstLegacy.getExternalTaskId()))
        .thenReturn(waiting(firstLegacy, warehouseId));

    assertThatThrownBy(() -> planner.plan(document, List.of(firstLine, secondLine)))
        .isInstanceOf(dev.buhanzaz.rwms.logistics.service.LogisticsConflictException.class);

    assertThat(firstLegacy.getState()).isEqualTo(DriverTaskState.SCHEDULED);
    assertThat(startedLegacy.getState()).isEqualTo(DriverTaskState.FINALIZING);
    verify(dependencies, never()).cancelDriverTaskIfPreStart(any(), anyLong(), anyString());
    verify(tasks, never()).saveAndFlush(any());
  }

  @Test
  void returnWithoutStableWorkerCreatesUnassignedTask() {
    UUID warehouseId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createReturn(
            warehouseId,
            UUID.randomUUID(),
            "ООО Клиент",
            "Водитель из старого документа",
            null,
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleReturn("Водитель из старого документа", null, LocalDate.of(2026, 8, 13));
    LogisticsDocumentLine line = persistedLine(document, assetId);
    persist(document);
    stubDependencies(warehouseId, UUID.randomUUID(), assetId, "БТ-102");
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE, line.getId(), DriverTaskKind.RETURN))
        .thenReturn(Optional.empty());

    planner.plan(document, List.of(line));

    ArgumentCaptor<DriverLogisticsTask> taskCaptor =
        ArgumentCaptor.forClass(DriverLogisticsTask.class);
    verify(tasks).saveAndFlush(taskCaptor.capture());
    DriverLogisticsTask created = taskCaptor.getValue();
    assertThat(created.getDriverAudienceMode()).isEqualTo(DriverTaskAudienceMode.UNASSIGNED);
    assertThat(created.getPlannedDriverWorkerId()).isNull();
    assertThat(created.getPlannedDriverNameSnapshot()).isNull();
  }

  @Test
  void transferCreatesWarehouseSharedTaskWithoutDriverIdentity() {
    UUID warehouseId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId,
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 14),
            UUID.randomUUID(),
            UUID.randomUUID());
    LogisticsDocumentLine line = persistedLine(document, assetId);
    persist(document);
    stubDependencies(warehouseId, UUID.randomUUID(), assetId, "БТ-103");
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE, line.getId(), DriverTaskKind.TRANSFER))
        .thenReturn(Optional.empty());

    planner.plan(document, List.of(line));

    ArgumentCaptor<DriverLogisticsTask> taskCaptor =
        ArgumentCaptor.forClass(DriverLogisticsTask.class);
    verify(tasks).saveAndFlush(taskCaptor.capture());
    DriverLogisticsTask created = taskCaptor.getValue();
    assertThat(created.getKind()).isEqualTo(DriverTaskKind.TRANSFER);
    assertThat(created.getDriverAudienceMode()).isEqualTo(DriverTaskAudienceMode.WAREHOUSE_DRIVERS);
    assertThat(created.getPlannedDriverWorkerId()).isNull();
    assertThat(created.getPlannedDriverNameSnapshot()).isNull();
  }

  @Test
  void furnitureOnlyTransferCreatesOneAssignedDocumentTaskAndReplaysByCargoChecksum() {
    UUID warehouseId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 8, 30);
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId, UUID.randomUUID(), date, UUID.randomUUID(), UUID.randomUUID());
    document.assignTransferTripDriver(workerId, "Петров Алексей");
    persist(document);
    when(dependencies.readWarehouseDriverQueue(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                warehouseId, queueDefinitionId, UUID.randomUUID()));
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.TRANSFER))
        .thenReturn(Optional.empty());

    DriverTaskWorkerContent workerContent =
        new DriverTaskWorkerContent(
            "Склад A → Склад B",
            List.of(
                new DriverTaskWorkerContent.Work(
                    UUID.randomUUID(), "Загрузить мебель", 1, null, null, null)),
            List.of(),
            List.of());
    planner.planTransferCargo(document, "  12 предметов мебели  ", workerContent);

    ArgumentCaptor<DriverLogisticsTask> taskCaptor =
        ArgumentCaptor.forClass(DriverLogisticsTask.class);
    verify(tasks).saveAndFlush(taskCaptor.capture());
    DriverLogisticsTask created = taskCaptor.getValue();
    assertThat(created.getCabinId()).isNull();
    assertThat(created.getMembers()).isEmpty();
    assertThat(created.isFurnitureCargoTransfer()).isTrue();
    assertThat(created.getSourceId()).isEqualTo(document.getId());
    assertThat(created.getUnitNumber()).isEqualTo("12 предметов мебели");
    assertThat(created.getComment()).isEqualTo("Межскладской груз: 12 предметов мебели");
    assertThat(created.getDriverAudienceMode()).isEqualTo(DriverTaskAudienceMode.ASSIGNED_DRIVER);
    assertThat(created.getPlannedDriverWorkerId()).isEqualTo(workerId);
    assertThat(created.getPlannedDriverNameSnapshot()).isEqualTo("Петров Алексей");
    assertThat(workerContentCodec.decode(created.getWorkerContentJson())).isEqualTo(workerContent);
    verify(capacityFence).acquireTaskDay(warehouseId, date, DriverTaskKind.TRANSFER);
    verify(shipmentTaskSettings, never()).requireWithinLimit(any(), anyInt(), any());

    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.TRANSFER))
        .thenReturn(Optional.of(created));
    planner.planTransferCargo(document, "12 предметов мебели", workerContent);

    verify(tasks, times(1)).saveAndFlush(any(DriverLogisticsTask.class));
    verify(capacityFence, times(2))
        .acquireTaskDay(warehouseId, date, DriverTaskKind.TRANSFER);
  }

  @Test
  void registeredTransferReplacesStructuredContentBeforeMovingTheUnstartedTask() {
    UUID warehouseId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID localTaskId = UUID.randomUUID();
    UUID boardTaskId = UUID.randomUUID();
    UUID oldEntryId = UUID.randomUUID();
    UUID newEntryId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 9, 14);
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId, UUID.randomUUID(), date, UUID.randomUUID(), UUID.randomUUID());
    persist(document);
    ReflectionTestUtils.setField(document, "id", documentId);
    DriverLogisticsTask existing =
        DriverLogisticsTask.createFurnitureCargoTransfer(
            warehouseId,
            documentId,
            date,
            3,
            "Межскладской груз: 4 предмета мебели",
            "4 предмета мебели",
            queueDefinitionId,
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
            null,
            null,
            document.getRequestedBySubjectId(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(existing, "id", localTaskId);
    existing.registerBoardTask(boardTaskId, 1, oldEntryId, "WAITING", "SCHEDULED", null);
    DriverTaskWorkerContent content =
        new DriverTaskWorkerContent(
            "Склад A → Склад B",
            List.of(
                new DriverTaskWorkerContent.Work(
                    UUID.randomUUID(), "Загрузить мебель", 1, null, null, null)),
            List.of(),
            List.of());
    var audience =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null);
    var current =
        boardTask(
            boardTaskId,
            1,
            warehouseId,
            existing.getExternalTaskId(),
            oldEntryId,
            0,
            date,
            audience);
    var updated =
        boardTask(
            boardTaskId,
            2,
            warehouseId,
            existing.getExternalTaskId(),
            newEntryId,
            0,
            date,
            audience);
    var moved =
        boardTask(
            boardTaskId,
            3,
            warehouseId,
            existing.getExternalTaskId(),
            newEntryId,
            1,
            date,
            audience);
    when(dependencies.readWarehouseDriverQueue(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                warehouseId, queueDefinitionId, queueId));
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, documentId, DriverTaskKind.TRANSFER))
        .thenReturn(Optional.of(existing));
    when(dependencies.readDriverTask(existing.getExternalTaskId())).thenReturn(current);
    when(dependencies.updateDriverTaskBeforeStart(
            existing.getExternalTaskId(),
            1,
            "Переместить мебель между складами",
            "4 предмета мебели",
            "Межскладской груз: 4 предмета мебели",
            queueDefinitionId,
            content))
        .thenReturn(updated);
    when(dependencies.readDriverBoard(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.DriverBoardSnapshot(
                warehouseId, queueId, 0, List.of(), List.of()));
    when(dependencies.moveDriverTask(
            existing.getExternalTaskId(), 2, 0, "SCHEDULED", date, 0, audience))
        .thenReturn(moved);
    when(tasks.findForUpdate(localTaskId)).thenReturn(Optional.of(existing));

    planner.planTransferCargo(document, "4 предмета мебели", content);

    verify(dependencies)
        .updateDriverTaskBeforeStart(
            existing.getExternalTaskId(),
            1,
            "Переместить мебель между складами",
            "4 предмета мебели",
            "Межскладской груз: 4 предмета мебели",
            queueDefinitionId,
            content);
    verify(dependencies)
        .moveDriverTask(existing.getExternalTaskId(), 2, 0, "SCHEDULED", date, 0, audience);
    verify(workflowStore).confirmStatus(localTaskId, moved);
    assertThat(workerContentCodec.decode(existing.getWorkerContentJson())).isEqualTo(content);
  }

  @Test
  void cancellationAcceptsFurnitureOnlyTransferWithoutDocumentLines() {
    UUID warehouseId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId,
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 30),
            UUID.randomUUID(),
            UUID.randomUUID());
    persist(document);
    DriverLogisticsTask task =
        DriverLogisticsTask.createFurnitureCargoTransfer(
            warehouseId,
            document.getId(),
            document.getScheduledDate(),
            3,
            "Межскладской груз: 12 предметов",
            "12 предметов",
            UUID.randomUUID(),
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
            null,
            null,
            document.getRequestedBySubjectId(),
            UUID.randomUUID(),
            "e".repeat(64));
    when(tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(document.getId())))
        .thenReturn(List.of(task));

    planner.cancelBeforeStart(document, List.of());

    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void explicitlyPublishedShipmentCreatesWarehouseSharedTaskWithoutDriverIdentity() {
    UUID warehouseId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            warehouseId,
            UUID.randomUUID(),
            "Клиент",
            "Свободное задание",
            null,
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment(
        "Свободное задание", null, LocalDate.of(2026, 8, 25), true);
    LogisticsDocumentLine line = persistedLine(document, assetId);
    persist(document);
    stubDependencies(warehouseId, UUID.randomUUID(), assetId, "БТ-POOL");
    when(tasks.findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, document.getId(), DriverTaskKind.SHIPMENT))
        .thenReturn(Optional.empty());

    planner.plan(document, List.of(line));

    ArgumentCaptor<DriverLogisticsTask> taskCaptor =
        ArgumentCaptor.forClass(DriverLogisticsTask.class);
    verify(tasks).saveAndFlush(taskCaptor.capture());
    DriverLogisticsTask created = taskCaptor.getValue();
    assertThat(created.getDriverAudienceMode()).isEqualTo(DriverTaskAudienceMode.WAREHOUSE_DRIVERS);
    assertThat(created.getPlannedDriverWorkerId()).isNull();
    assertThat(created.getPlannedDriverNameSnapshot()).isNull();
  }

  @Test
  void cancellationStopsUnregisteredIntentBeforeRelayCanPublishIt() {
    UUID warehouseId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId,
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 15),
            UUID.randomUUID(),
            UUID.randomUUID());
    LogisticsDocumentLine line = persistedLine(document, assetId);
    persist(document);
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            assetId,
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            line.getId(),
            DriverTaskKind.TRANSFER,
            DriverTaskPlanningMode.FIXED_DATE,
            document.getScheduledDate(),
            3,
            "Перемещение бытовки между складами",
            "БТ-104",
            UUID.randomUUID(),
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
            null,
            null,
            document.getRequestedBySubjectId(),
            UUID.randomUUID(),
            "a".repeat(64));
    when(tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(document.getId())))
        .thenReturn(List.of());
    when(tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE, List.of(line.getId())))
        .thenReturn(List.of(task));

    planner.cancelBeforeStart(document, List.of(line));

    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void cancellationStopsUnregisteredGroupedShipmentWithoutTouchingLegacyLineTasks() {
    UUID warehouseId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            warehouseId,
            UUID.randomUUID(),
            "ООО Клиент",
            "Иван Петров",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    document.scheduleShipment(
        "Иван Петров", document.getDriverWorkerId(), LocalDate.of(2026, 8, 18));
    LogisticsDocumentLine line = persistedLine(document, assetId);
    persist(document);
    DriverLogisticsTask grouped =
        DriverLogisticsTask.createGroupedShipment(
            warehouseId,
            assetId,
            document.getId(),
            document.getScheduledDate(),
            3,
            "Клиент: ООО Клиент. Бытовки: БТ-104",
            "ООО Клиент",
            "1 бытовка",
            UUID.randomUUID(),
            DriverTaskAudienceMode.ASSIGNED_DRIVER,
            document.getDriverWorkerId(),
            "Иван Петров",
            document.getRequestedBySubjectId(),
            UUID.randomUUID(),
            "b".repeat(64));
    grouped.addGroupedShipmentMember(line.getId(), assetId, "БТ-104", 1);
    when(tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(document.getId())))
        .thenReturn(List.of(grouped));
    when(tasks.findAllForUpdateBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE, List.of(line.getId())))
        .thenReturn(List.of());

    planner.cancelBeforeStart(document, List.of(line));

    assertThat(grouped.getState()).isEqualTo(DriverTaskState.CANCELLED);
    verify(tasks).saveAndFlush(grouped);
  }

  private void stubDependencies(
      UUID warehouseId, UUID queueDefinitionId, UUID assetId, String unitNumber) {
    when(dependencies.readWarehouseDriverQueue(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                warehouseId, queueDefinitionId, UUID.randomUUID()));
    when(dependencies.readRentalItemSnapshot(assetId))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                assetId, 7, warehouseId, unitNumber, "FREE", List.of()));
  }

  private static LogisticsDocumentLine persistedLine(LogisticsDocument document, UUID assetId) {
    return persistedLine(document, assetId, 1);
  }

  private static LogisticsDocumentLine persistedLine(
      LogisticsDocument document, UUID assetId, int lineNumber) {
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(document, lineNumber, assetId, 7, "ООО Клиент");
    ReflectionTestUtils.setField(line, "id", UUID.randomUUID());
    return line;
  }

  private static void persist(LogisticsDocument document) {
    ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
  }

  private static DriverLogisticsTask legacyTask(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID assetId,
      String unitNumber,
      UUID queueDefinitionId) {
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            document.getWarehouseId(),
            assetId,
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            line.getId(),
            DriverTaskKind.SHIPMENT,
            DriverTaskPlanningMode.FIXED_DATE,
            document.getScheduledDate(),
            3,
            "Отгрузка бытовки",
            unitNumber,
            queueDefinitionId,
            DriverTaskAudienceMode.ASSIGNED_DRIVER,
            document.getDriverWorkerId(),
            document.getDriverSnapshot(),
            document.getRequestedBySubjectId(),
            UUID.randomUUID(),
            "c".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    task.registerBoardTask(UUID.randomUUID(), 1, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private static LogisticsDependencyGateway.DriverBoardTask boardTask(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      UUID entryId,
      long entryVersion,
      LocalDate date,
      LogisticsDependencyGateway.DriverTaskAudience audience) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        taskId,
        taskVersion,
        warehouseId,
        externalTaskId,
        "Переместить мебель между складами",
        "4 предмета мебели",
        "Склад A → Склад B",
        audience,
        "ACTIVE",
        date,
        "SCHEDULED",
        3,
        false,
        null,
        entryId,
        entryVersion,
        "WAITING",
        0);
  }

  private static LogisticsDependencyGateway.DriverBoardTask waiting(
      DriverLogisticsTask task, UUID warehouseId) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        1,
        warehouseId,
        task.getExternalTaskId(),
        "Отгрузка",
        task.getUnitNumber(),
        "Отгрузка",
        "ACTIVE",
        task.getScheduledDate(),
        "SCHEDULED",
        3,
        false,
        null,
        UUID.randomUUID(),
        1,
        "WAITING",
        0);
  }
}
