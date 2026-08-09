package dev.buhanzaz.rwms.logistics.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateMaintenanceEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskLineResponse;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.MaintenanceEquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLine;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskOwnerType;
import dev.buhanzaz.rwms.logistics.equipment.mapper.EquipmentMovementTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskLineRepository;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionKind;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EquipmentMovementTaskServiceMaintenanceTest {
  private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000007001");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000007002");
  private static final UUID DECISION = UUID.fromString("00000000-0000-0000-0000-000000007003");
  private static final UUID CABIN = UUID.fromString("00000000-0000-0000-0000-000000007004");
  private static final UUID EQUIPMENT = UUID.fromString("00000000-0000-0000-0000-000000007005");
  private static final UUID FIRST_MAINTENANCE_KEY =
      UUID.fromString("00000000-0000-0000-0000-000000007006");
  private static final UUID SECOND_MAINTENANCE_KEY =
      UUID.fromString("00000000-0000-0000-0000-000000007007");

  private final EquipmentMovementTaskRepository tasks = mock(EquipmentMovementTaskRepository.class);
  private final EquipmentMovementTaskLineRepository lines =
      mock(EquipmentMovementTaskLineRepository.class);
  private final EquipmentMovementTaskResponseMapper mapper =
      mock(EquipmentMovementTaskResponseMapper.class);
  private final LogisticsWarehouseLifecycle warehouseLifecycle =
      mock(LogisticsWarehouseLifecycle.class);
  private final LogisticsWarehouseOperationMarkStore warehouseOperationMarks =
      mock(LogisticsWarehouseOperationMarkStore.class);
  private final LogisticsTransactionLock transactionLock = mock(LogisticsTransactionLock.class);
  private final EquipmentMovementTaskService service =
      new EquipmentMovementTaskService(
          tasks, lines, mapper, warehouseLifecycle, warehouseOperationMarks, transactionLock);

  @BeforeEach
  void admissionTicket() {
    when(warehouseLifecycle.disabledTicket(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<AdmissionRequirement> requirements = invocation.getArgument(3);
              OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
              Map<UUID, java.time.LocalDate> dates =
                  requirements.stream()
                      .collect(
                          java.util.stream.Collectors.toMap(
                              AdmissionRequirement::warehouseId,
                              ignored -> at.toLocalDate()));
              return new AdmissionTicket(
                  UUID.randomUUID(),
                  requirements,
                  at,
                  dates,
                  true,
                  AdmissionKind.TEST_ONLY);
            });
  }

  @Test
  void maintenanceDecisionOwnsOneExactCabinToStockMovementForever() {
    AtomicReference<EquipmentMovementTask> persisted = stubNewTaskPersistence();
    CreateMaintenanceEquipmentMovementTaskRequest request = maintenanceRequest(2L);

    EquipmentMovementTaskService.CreateResult created =
        service.createFromMaintenance(FIRST_MAINTENANCE_KEY, request);

    assertThat(created.replayed()).isFalse();
    assertThat(created.response().ownerType())
        .isEqualTo(EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION);
    assertThat(created.response().ownerId()).isEqualTo(DECISION);
    assertThat(created.response().lines()).singleElement().satisfies(
        line -> {
          assertThat(line.sourceWarehouseId()).isEqualTo(WAREHOUSE);
          assertThat(line.sourceRentalItemId()).isEqualTo(CABIN);
          assertThat(line.sourceLocationKind())
              .isEqualTo(EquipmentMovementLocationKind.CABIN_NON_RENTED);
          assertThat(line.targetWarehouseId()).isEqualTo(WAREHOUSE);
          assertThat(line.targetRentalItemId()).isNull();
          assertThat(line.targetLocationKind()).isEqualTo(EquipmentMovementLocationKind.STOCK);
          assertThat(line.quantity()).isEqualTo(2L);
        });
    assertThat(persisted.get().getCreatedBySubjectId())
        .isEqualTo(
            UUID.nameUUIDFromBytes("rwms:maintenance-service".getBytes(StandardCharsets.UTF_8)));

    EquipmentMovementTaskService.CreateResult replay =
        service.createFromMaintenance(SECOND_MAINTENANCE_KEY, request);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().id()).isEqualTo(TASK_ID);
    assertThatThrownBy(
            () ->
                service.createFromMaintenance(
                    UUID.randomUUID(), maintenanceRequest(3L)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Maintenance disposition");
    verify(tasks, times(1)).saveAndFlush(any(EquipmentMovementTask.class));
  }

  @Test
  void genericMovementAllowsMoreThanTheFormerTenWorkerOperations() {
    stubNewTaskPersistence();
    List<EquipmentMovementLineRequest> movementLines =
        IntStream.range(0, 6)
            .mapToObj(
                ignored ->
                    new EquipmentMovementLineRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        EquipmentMovementLocationKind.CABIN_NON_RENTED,
                        0L,
                        UUID.randomUUID(),
                        EquipmentMovementLocationKind.CABIN_NON_RENTED,
                        1L))
            .toList();
    CreateEquipmentMovementTaskRequest request =
        new CreateEquipmentMovementTaskRequest(
            WAREHOUSE,
            "БЫТ-701",
            30,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
            movementLines);

    assertThatCode(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), request))
        .doesNotThrowAnyException();
  }

  @Test
  void maintenanceGetDoesNotRevealAPublicTask() {
    EquipmentMovementTask publicTask =
        EquipmentMovementTask.create(
            WAREHOUSE,
            "БЫТ-702",
            30,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(publicTask, "id", TASK_ID);
    when(tasks.findById(TASK_ID)).thenReturn(Optional.of(publicTask));

    assertThatThrownBy(() -> service.getMaintenance(TASK_ID))
        .isInstanceOf(LogisticsNotFoundException.class);
  }

  @Test
  void publicRetryCreatedBeforeOwnerMigrationStillReplaysOnlyItsExactPayload() {
    UUID actorId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
    CreateEquipmentMovementTaskRequest request =
        new CreateEquipmentMovementTaskRequest(
            WAREHOUSE,
            "БЫТ-703",
            30,
            deadline,
            List.of(
                new EquipmentMovementLineRequest(
                    EQUIPMENT,
                    null,
                    EquipmentMovementLocationKind.STOCK,
                    4L,
                    CABIN,
                    EquipmentMovementLocationKind.CABIN_NON_RENTED,
                    2L)));
    EquipmentMovementTask historicalTask =
        EquipmentMovementTask.create(
            WAREHOUSE,
            request.unitNumber(),
            request.plannedDurationMinutes(),
            deadline,
            actorId,
            idempotencyKey,
            legacyPublicChecksum(request));
    ReflectionTestUtils.setField(historicalTask, "id", TASK_ID);
    when(tasks.findByCreatedBySubjectIdAndIdempotencyKey(actorId, idempotencyKey))
        .thenReturn(Optional.of(historicalTask));
    when(lines.findAllByTask_IdOrderByLineNumberAsc(TASK_ID)).thenReturn(List.of());
    when(mapper.toResponse(historicalTask)).thenReturn(taskResponse(historicalTask));

    EquipmentMovementTaskService.CreateResult replay =
        service.create(actorId, idempotencyKey, request);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().id()).isEqualTo(TASK_ID);
    verify(tasks, org.mockito.Mockito.never()).saveAndFlush(any(EquipmentMovementTask.class));
  }

  private AtomicReference<EquipmentMovementTask> stubNewTaskPersistence() {
    AtomicReference<EquipmentMovementTask> persisted = new AtomicReference<>();
    when(tasks.findByOwnerTypeAndOwnerId(any(), any()))
        .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
    when(tasks.findByCreatedBySubjectIdAndIdempotencyKey(any(), any()))
        .thenReturn(Optional.empty());
    when(tasks.saveAndFlush(any(EquipmentMovementTask.class)))
        .thenAnswer(
            invocation -> {
              EquipmentMovementTask task = invocation.getArgument(0);
              ReflectionTestUtils.setField(task, "id", TASK_ID);
              persisted.set(task);
              return task;
            });
    when(lines.saveAllAndFlush(any()))
        .thenAnswer(
            invocation -> {
              Iterable<EquipmentMovementTaskLine> input = invocation.getArgument(0);
              List<EquipmentMovementTaskLine> saved = new ArrayList<>();
              input.forEach(saved::add);
              return saved;
            });
    when(mapper.toResponse(any(EquipmentMovementTask.class)))
        .thenAnswer(invocation -> taskResponse(invocation.getArgument(0)));
    when(mapper.toLineResponse(any(EquipmentMovementTaskLine.class)))
        .thenAnswer(invocation -> lineResponse(invocation.getArgument(0)));
    return persisted;
  }

  private static CreateMaintenanceEquipmentMovementTaskRequest maintenanceRequest(long quantity) {
    return new CreateMaintenanceEquipmentMovementTaskRequest(
        DECISION,
        WAREHOUSE,
        "БЫТ-700",
        30,
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
        List.of(new MaintenanceEquipmentMovementLineRequest(EQUIPMENT, CABIN, 4L, quantity)));
  }

  private static EquipmentMovementTaskResponse taskResponse(EquipmentMovementTask task) {
    return new EquipmentMovementTaskResponse(
        task.getId(),
        task.getVersion(),
        task.getWarehouseId(),
        task.getOwnerType(),
        task.getOwnerId(),
        task.getExternalTaskId(),
        task.getTaskBoardTaskId(),
        task.getTaskBoardTaskVersion(),
        task.getTaskBoardDoneAt(),
        task.getUnitNumber(),
        task.getPlannedDurationMinutes(),
        task.getDeadlineAt(),
        task.getState(),
        task.getTerminalState(),
        task.getFailureCode(),
        List.of(),
        task.getCreatedAt(),
        task.getUpdatedAt());
  }

  private static EquipmentMovementTaskLineResponse lineResponse(EquipmentMovementTaskLine line) {
    return new EquipmentMovementTaskLineResponse(
        line.getId(),
        line.getVersion(),
        line.getLineNumber(),
        line.getEquipmentId(),
        line.getEquipmentName(),
        line.getSourceWarehouseId(),
        line.getSourceRentalItemId(),
        line.getSourceLocationKind(),
        line.getExpectedSourceBalanceVersion(),
        line.getTargetWarehouseId(),
        line.getTargetRentalItemId(),
        line.getTargetLocationKind(),
        line.getQuantity(),
        line.getReservationId(),
        line.getReservationVersion(),
        line.getState(),
        line.getCreatedAt(),
        line.getUpdatedAt());
  }

  private static String legacyPublicChecksum(CreateEquipmentMovementTaskRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(request.targetWarehouseId() == null ? null : request.targetWarehouseId().toString());
    values.add(request.unitNumber());
    values.add(request.plannedDurationMinutes().toString());
    values.add(request.deadlineAt().toString());
    for (EquipmentMovementLineRequest line : request.lines()) {
      values.add(line.equipmentId().toString());
      values.add(line.sourceRentalItemId() == null ? null : line.sourceRentalItemId().toString());
      values.add(line.sourceLocationKind().name());
      values.add(line.expectedSourceBalanceVersion().toString());
      values.add(line.targetRentalItemId() == null ? null : line.targetRentalItemId().toString());
      values.add(line.targetLocationKind().name());
      values.add(line.quantity().toString());
    }
    return EquipmentMovementTaskChecksum.sha256("CREATE_EQUIPMENT_MOVEMENT_TASK", values);
  }
}
