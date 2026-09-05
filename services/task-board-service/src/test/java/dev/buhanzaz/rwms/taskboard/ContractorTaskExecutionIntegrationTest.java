package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.DriverTaskAudienceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QualificationRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterExternalTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskCommentSnapshotRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskMaterialSnapshotRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceMediaSnapshotRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceReferenceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskWorkSnapshotRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerClassRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerRequest;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskAction;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskActionRequest;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorEvidenceReservationRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateContractorDriverRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.BoardTaskRegistrationDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import dev.buhanzaz.rwms.taskboard.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.service.ContractorDriverService;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises exact contractor proof, safe snapshots and replay-safe worker state transitions. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ContractorTaskExecutionIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000761");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;
  @Autowired ContractorDriverService contractors;
  @Autowired TaskBoardService taskBoard;

  private UUID contractorId;
  private UUID externalTaskId;
  private UUID sourceId;
  private UUID sourceMediaId;
  private WorkQueueDto driverQueue;
  private BoardTaskRegistrationDto registration;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    var driverClass =
        registry.createClass(
            new WorkerClassRequest(0L, "Водитель подрядчика", null, null, 0, true));
    var definition =
        QueueRegistryTestFixtures.ensureDriverDefinition(
            registry, jdbc, "Перемещение", QueueType.MOVEMENT);
    driverQueue =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            WAREHOUSE,
            new QueueFixtureModels.QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                false,
                false,
                null,
                null,
                false,
                1,
                List.of(
                    new QueueBindingRequest(
                        driverClass.id(),
                        0,
                        false,
                        ParticipationPolicy.PRIMARY,
                        false))));
    contractorId = UUID.randomUUID();
    contractors.create(
        WAREHOUSE,
        new CreateContractorDriverRequest(
            contractorId, "Наёмный Петров", "+7 900 111-22-33", "Не выдавать логин", null));
    externalTaskId = UUID.randomUUID();
    sourceId = UUID.randomUUID();
    sourceMediaId = UUID.randomUUID();
    OffsetDateTime recordedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    registration =
        taskBoard.registerExternalTask(
            "logistics-service",
            new RegisterExternalTaskRequest(
                WAREHOUSE,
                externalTaskId,
                "Доставка бытовки",
                "БЫТ-172",
                "Доставка и обратный вывоз",
                90,
                recordedAt.plusHours(8),
                List.of(
                    new RouteStepRequest(
                        driverQueue.definitionId(),
                        "Забрать бытовку по адресу: ул. Примерная, 7",
                        30,
                        List.of(
                            new TaskWorkSnapshotRequest(
                                UUID.randomUUID(),
                                "Проверить крепления",
                                1,
                                "операция",
                                10,
                                "Фото до выезда",
                                List.of(sourceMediaId))),
                        List.of(
                            new TaskMaterialSnapshotRequest(
                                UUID.randomUUID(), "Ремень", 2, "шт")),
                        List.of(
                            new TaskCommentSnapshotRequest(
                                UUID.randomUUID(),
                                "Позвонить диспетчеру по прибытии",
                                "Логист",
                                recordedAt)),
                        List.of(
                            new TaskSourceMediaSnapshotRequest(
                                sourceMediaId,
                                3,
                                "image/webp",
                                recordedAt.minusMinutes(1),
                                recordedAt))),
                    new RouteStepRequest(
                        driverQueue.definitionId(),
                        "Вернуть бытовку на склад",
                        60,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())),
                LocalDate.of(2026, 8, 31),
                2,
                new TaskSourceReferenceDto(TaskSourceType.LOGISTICS_DRIVER_TASK, sourceId),
                TaskLane.CURRENT,
                new DriverTaskAudienceDto(
                    DriverTaskAudienceMode.ASSIGNED_DRIVER, contractorId, null)));
  }

  @Test
  void exactServiceReadsOrderedSafeWorkerFactsWithoutContactOrMediaPaths() throws Exception {
    String path = taskPath(contractorId, externalTaskId);

    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).with(userWithLogisticsScope())).andExpect(status().isForbidden());
    mvc.perform(get(path).with(otherServiceJwt())).andExpect(status().isForbidden());
    mvc.perform(get(path).with(mixedLogisticsJwt())).andExpect(status().isForbidden());

    MvcResult response =
        mvc.perform(get(path).with(logisticsJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workerId").value(contractorId.toString()))
            .andExpect(jsonPath("$.externalTaskId").value(externalTaskId.toString()))
            .andExpect(jsonPath("$.source.type").value("LOGISTICS_DRIVER_TASK"))
            .andExpect(jsonPath("$.source.sourceId").value(sourceId.toString()))
            .andExpect(jsonPath("$.title").value("Доставка бытовки"))
            .andExpect(jsonPath("$.description").value("Доставка и обратный вывоз"))
            .andExpect(jsonPath("$.unitNumber").value("БЫТ-172"))
            .andExpect(jsonPath("$.route.length()").value(2))
            .andExpect(jsonPath("$.route[0].routeStepIndex").value(0))
            .andExpect(jsonPath("$.route[0].routeStepCount").value(2))
            .andExpect(jsonPath("$.route[0].taskText").value("Забрать бытовку по адресу: ул. Примерная, 7"))
            .andExpect(jsonPath("$.route[0].works[0].name").value("Проверить крепления"))
            .andExpect(jsonPath("$.route[0].materials[0].name").value("Ремень"))
            .andExpect(jsonPath("$.route[0].comments[0].text").value("Позвонить диспетчеру по прибытии"))
            .andExpect(jsonPath("$.route[0].sourceMedia[0].mediaId").value(sourceMediaId.toString()))
            .andExpect(jsonPath("$.route[0].sourceMedia[0].generation").value(3))
            .andExpect(jsonPath("$.route[0].sourceMedia[0].contentType").value("image/webp"))
            .andExpect(jsonPath("$.route[0].resultPhotoMinCount").value(1))
            .andExpect(jsonPath("$.route[0].evidence.length()").value(0))
            .andExpect(jsonPath("$.route[0].completionAllowed").value(false))
            .andExpect(jsonPath("$.route[1].routeStepIndex").value(1))
            .andExpect(jsonPath("$.route[1].status").value("WAITING"))
            .andReturn();
    String body = response.getResponse().getContentAsString();
    assertThat(body).doesNotContain("+7 900 111-22-33", "readPath", "thumbnailPath");
  }

  @Test
  void rejectsIdorWrongSourceInactiveContractorAndStaffWorker() throws Exception {
    UUID otherContractor = UUID.randomUUID();
    contractors.create(
        WAREHOUSE,
        new CreateContractorDriverRequest(
            otherContractor, "Другой подрядчик", "+7 900 333-44-55", null, null));

    mvc.perform(get(taskPath(otherContractor, externalTaskId)).with(logisticsJwt()))
        .andExpect(status().isNotFound());
    mvc.perform(get(taskPath(contractorId, UUID.randomUUID())).with(logisticsJwt()))
        .andExpect(status().isNotFound());

    jdbc.update(
        "update task_sync_source set source_client_id='maintenance-service' where board_task_id=?",
        registration.taskId());
    mvc.perform(get(taskPath(contractorId, externalTaskId)).with(logisticsJwt()))
        .andExpect(status().isNotFound());
    jdbc.update(
        "update task_sync_source set source_client_id='logistics-service' where board_task_id=?",
        registration.taskId());

    jdbc.update("update worker set active=false where id=?", contractorId);
    mvc.perform(get(taskPath(contractorId, externalTaskId)).with(logisticsJwt()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Наёмный водитель неактивен"));

    UUID staffId = createStaffTask();
    UUID staffExternalTaskId =
        jdbc.queryForObject(
            "select external_task_id from board_task where planned_driver_worker_id=?",
            UUID.class,
            staffId);
    mvc.perform(get(taskPath(staffId, staffExternalTaskId)).with(logisticsJwt()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Задание назначено штатному водителю"));
  }

  @Test
  void commandEndpointRequiresExactServiceAssignmentAndLogisticsSource() throws Exception {
    UUID entryId = registration.route().getFirst().entryId();
    long version = registration.route().getFirst().entryVersion();
    UUID operationId = UUID.randomUUID();
    byte[] body =
        objectMapper.writeValueAsBytes(
            new ContractorTaskActionRequest(
                operationId, ContractorTaskAction.START, version, null));
    String actionPath = actionPath(contractorId, externalTaskId, entryId);

    mvc.perform(
            post(actionPath)
                .header("Idempotency-Key", operationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(actionPath)
                .with(otherServiceJwt())
                .header("Idempotency-Key", operationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    UUID otherContractor = UUID.randomUUID();
    contractors.create(
        WAREHOUSE,
        new CreateContractorDriverRequest(
            otherContractor, "Чужой подрядчик", "+7 900 555-66-77", null, null));
    action(
            otherContractor,
            externalTaskId,
            entryId,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            version,
            null)
        .andExpect(status().isNotFound());

    jdbc.update(
        "update task_sync_source set source_client_id='maintenance-service' where board_task_id=?",
        registration.taskId());
    action(
            contractorId,
            externalTaskId,
            entryId,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            version,
            null)
        .andExpect(status().isNotFound());

    jdbc.update(
        "update task_sync_source set source_client_id='logistics-service' where board_task_id=?",
        registration.taskId());
    jdbc.update("update worker set active=false where id=?", contractorId);
    action(
            contractorId,
            externalTaskId,
            entryId,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            version,
            null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Наёмный водитель неактивен"));

    UUID staffId = createStaffTask();
    UUID staffExternalTaskId =
        jdbc.queryForObject(
            "select external_task_id from board_task where planned_driver_worker_id=?",
            UUID.class,
            staffId);
    UUID staffEntryId =
        jdbc.queryForObject(
            """
            select entry.id
              from queue_entry entry
              join board_task task on task.id=entry.task_id
             where task.external_task_id=?
            """,
            UUID.class,
            staffExternalTaskId);
    Long staffEntryVersion =
        jdbc.queryForObject(
            "select version from queue_entry where id=?", Long.class, staffEntryId);
    action(
            staffId,
            staffExternalTaskId,
            staffEntryId,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            staffEntryVersion,
            null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Задание назначено штатному водителю"));
  }

  @Test
  void enforcesOrderVersionsReplayAndMandatorySelectedEvidence() throws Exception {
    UUID firstEntry = registration.route().get(0).entryId();
    UUID secondEntry = registration.route().get(1).entryId();
    long firstVersion = registration.route().get(0).entryVersion();
    long secondVersion = registration.route().get(1).entryVersion();

    action(secondEntry, UUID.randomUUID(), ContractorTaskAction.START, secondVersion, null)
        .andExpect(status().isConflict());

    UUID startOperation = UUID.randomUUID();
    MvcResult started =
        action(
                firstEntry,
                startOperation,
                ContractorTaskAction.START,
                firstVersion,
                null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("APPLIED"))
            .andExpect(jsonPath("$.task.route[0].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.task.route[0].completionAllowed").value(false))
            .andReturn();
    JsonNode startedJson = objectMapper.readTree(started.getResponse().getContentAsByteArray());
    long startedVersion = startedJson.required("currentVersion").longValue();
    assertThat(startedVersion).isGreaterThan(firstVersion);

    MvcResult replay =
        action(
                firstEntry,
                startOperation,
                ContractorTaskAction.START,
                firstVersion,
                null)
            .andExpect(status().isOk())
            .andReturn();
    assertThat(replay.getResponse().getContentAsByteArray())
        .isEqualTo(started.getResponse().getContentAsByteArray());

    action(
            firstEntry,
            startOperation,
            ContractorTaskAction.COMPLETE,
            startedVersion,
            UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("operationId уже использован другой командой"));
    action(firstEntry, UUID.randomUUID(), ContractorTaskAction.START, firstVersion, null)
        .andExpect(status().isConflict());
    action(
            firstEntry,
            UUID.randomUUID(),
            ContractorTaskAction.COMPLETE,
            startedVersion,
            null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Для завершения не хватает готовых фотографий"));

    UUID previousContractor = UUID.randomUUID();
    contractors.create(
        WAREHOUSE,
        new CreateContractorDriverRequest(
            previousContractor, "Предыдущий подрядчик", "+7 900 777-88-99", null, null));
    UUID previousEvidenceId = UUID.randomUUID();
    insertReadyEvidence(
        firstEntry,
        previousEvidenceId,
        previousContractor,
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3));
    mvc.perform(get(taskPath(contractorId, externalTaskId)).with(logisticsJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route[0].evidence.length()").value(0))
        .andExpect(jsonPath("$.route[0].completionAllowed").value(false));
    action(
            firstEntry,
            UUID.randomUUID(),
            ContractorTaskAction.COMPLETE,
            startedVersion,
            previousEvidenceId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Для завершения не хватает готовых фотографий"));

    UUID evidenceId = UUID.randomUUID();
    insertReadyEvidence(firstEntry, evidenceId);
    MvcResult readySnapshot =
        mvc.perform(get(taskPath(contractorId, externalTaskId)).with(logisticsJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.route[0].completionAllowed").value(true))
            .andExpect(jsonPath("$.route[0].evidence.length()").value(1))
            .andExpect(jsonPath("$.route[0].evidence[0].evidenceId").value(evidenceId.toString()))
            .andExpect(jsonPath("$.route[0].evidence[0].state").value("READY"))
            .andExpect(jsonPath("$.route[0].evidence[0].mediaGeneration").value(1))
            .andExpect(jsonPath("$.route[0].evidence[0].contentType").value("image/jpeg"))
            .andReturn();
    assertThat(readySnapshot.getResponse().getContentAsString())
        .doesNotContain("readPath", "thumbnailPath", "uploadPath");
    action(
            firstEntry,
            UUID.randomUUID(),
            ContractorTaskAction.COMPLETE,
            startedVersion,
            previousEvidenceId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Выбранная фотография результата ещё не готова"));

    MvcResult completed =
        action(
                firstEntry,
                UUID.randomUUID(),
                ContractorTaskAction.COMPLETE,
                startedVersion,
                evidenceId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.route[0].status").value("DONE"))
            .andExpect(jsonPath("$.task.route[1].status").value("WAITING"))
            .andReturn();
    JsonNode completedJson =
        objectMapper.readTree(completed.getResponse().getContentAsByteArray());
    assertThat(completedJson.required("currentVersion").longValue())
        .isGreaterThan(startedVersion);
    assertThat(
            jdbc.queryForObject(
                "select selected_for_completion from worker_task_evidence where evidence_id=?",
                Boolean.class,
                evidenceId))
        .isTrue();

    JsonNode second = completedJson.required("task").required("route").required(1);
    action(
            secondEntry,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            second.required("version").longValue(),
            null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.route[1].status").value("IN_PROGRESS"));
  }

  @Test
  void snapshotBoundsEvidenceToNewestFactsFromExactContractor() throws Exception {
    UUID entryId = registration.route().getFirst().entryId();
    action(
            entryId,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            registration.route().getFirst().entryVersion(),
            null)
        .andExpect(status().isOk());

    UUID otherContractor = UUID.randomUUID();
    contractors.create(
        WAREHOUSE,
        new CreateContractorDriverRequest(
            otherContractor, "Чужая история", "+7 900 121-22-23", null, null));
    UUID foreignEvidenceId = UUID.randomUUID();
    OffsetDateTime firstRecordedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
    insertReadyEvidence(entryId, foreignEvidenceId, otherContractor, firstRecordedAt);

    List<UUID> contractorEvidenceIds = new java.util.ArrayList<>();
    for (int index = 0; index < 101; index++) {
      UUID evidenceId = UUID.randomUUID();
      contractorEvidenceIds.add(evidenceId);
      insertReadyEvidence(
          entryId, evidenceId, contractorId, firstRecordedAt.plusSeconds(index + 1L));
    }

    MvcResult response =
        mvc.perform(get(taskPath(contractorId, externalTaskId)).with(logisticsJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.route[0].evidence.length()").value(100))
            .andExpect(jsonPath("$.route[0].completionAllowed").value(true))
            .andReturn();
    JsonNode evidence =
        objectMapper
            .readTree(response.getResponse().getContentAsByteArray())
            .required("route")
            .required(0)
            .required("evidence");
    assertThat(evidence.get(0).required("evidenceId").textValue())
        .isEqualTo(contractorEvidenceIds.getLast().toString());
    assertThat(evidence.get(99).required("evidenceId").textValue())
        .isEqualTo(contractorEvidenceIds.get(1).toString());
    assertThat(evidence.toString())
        .doesNotContain(
            contractorEvidenceIds.getFirst().toString(),
            foreignEvidenceId.toString(),
            "readPath",
            "thumbnailPath",
            "uploadPath");
  }

  @Test
  void evidenceReservationRequiresExactServiceTaskAndActiveContractor() throws Exception {
    UUID entryId = registration.route().getFirst().entryId();
    ContractorEvidenceReservationRequest request = evidenceRequest();
    String path = evidencePath(contractorId, externalTaskId, entryId);

    mvc.perform(
            post(path)
                .header("Idempotency-Key", request.operationId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(path)
                .with(userWithLogisticsScope())
                .header("Idempotency-Key", request.operationId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path)
                .with(mixedLogisticsJwt())
                .header("Idempotency-Key", request.operationId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path)
                .with(otherServiceJwt())
                .header("Idempotency-Key", request.operationId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isForbidden());

    UUID otherContractor = UUID.randomUUID();
    contractors.create(
        WAREHOUSE,
        new CreateContractorDriverRequest(
            otherContractor, "Чужой подрядчик", "+7 900 777-88-99", null, null));
    reserveEvidence(otherContractor, externalTaskId, entryId, evidenceRequest())
        .andExpect(status().isNotFound());
    reserveEvidence(contractorId, UUID.randomUUID(), entryId, evidenceRequest())
        .andExpect(status().isNotFound());

    jdbc.update(
        "update task_sync_source set source_client_id='maintenance-service' where board_task_id=?",
        registration.taskId());
    reserveEvidence(contractorId, externalTaskId, entryId, evidenceRequest())
        .andExpect(status().isNotFound());
    jdbc.update(
        "update task_sync_source set source_client_id='logistics-service' where board_task_id=?",
        registration.taskId());

    jdbc.update("update worker set active=false where id=?", contractorId);
    reserveEvidence(contractorId, externalTaskId, entryId, evidenceRequest())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Наёмный водитель неактивен"));

    UUID staffId = createStaffTask();
    UUID staffExternalTaskId =
        jdbc.queryForObject(
            "select external_task_id from board_task where planned_driver_worker_id=?",
            UUID.class,
            staffId);
    UUID staffEntryId =
        jdbc.queryForObject(
            """
            select entry.id
              from queue_entry entry
              join board_task task on task.id=entry.task_id
             where task.external_task_id=?
            """,
            UUID.class,
            staffExternalTaskId);
    reserveEvidence(staffId, staffExternalTaskId, staffEntryId, evidenceRequest())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Задание назначено штатному водителю"));
  }

  @Test
  void evidenceReservationPreservesStateAssignmentMetadataReplayAndOwnerProof() throws Exception {
    UUID entryId = registration.route().getFirst().entryId();
    ContractorEvidenceReservationRequest beforeStart = evidenceRequest();
    reserveEvidence(contractorId, externalTaskId, entryId, beforeStart)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.detail")
                .value("Фотографию можно добавить только к текущему этапу наёмного водителя"));

    action(
            entryId,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            registration.route().getFirst().entryVersion(),
            null)
        .andExpect(status().isOk());

    ContractorEvidenceReservationRequest future =
        new ContractorEvidenceReservationRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
            "image/jpeg",
            128,
            "a".repeat(64));
    reserveEvidence(contractorId, externalTaskId, entryId, future)
        .andExpect(status().isBadRequest());

    ContractorEvidenceReservationRequest unsupported =
        new ContractorEvidenceReservationRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
            "image/png",
            128,
            "a".repeat(64));
    reserveEvidence(contractorId, externalTaskId, entryId, unsupported)
        .andExpect(status().isBadRequest());
    ContractorEvidenceReservationRequest oversizedWebp =
        new ContractorEvidenceReservationRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
            "image/webp",
            1_048_577,
            "a".repeat(64));
    reserveEvidence(contractorId, externalTaskId, entryId, oversizedWebp)
        .andExpect(status().isBadRequest());
    ContractorEvidenceReservationRequest invalidChecksum =
        new ContractorEvidenceReservationRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
            "image/jpeg",
            128,
            "A".repeat(64));
    reserveEvidence(contractorId, externalTaskId, entryId, invalidChecksum)
        .andExpect(status().isBadRequest());

    ContractorEvidenceReservationRequest request = evidenceRequest();
    mvc.perform(
            post(evidencePath(contractorId, externalTaskId, entryId))
                .with(logisticsJwt())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isBadRequest());
    MvcResult created =
        reserveEvidence(contractorId, externalTaskId, entryId, request)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.evidenceId").value(request.evidenceId().toString()))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.state").value("RESERVED"))
            .andExpect(jsonPath("$.entryId").value(entryId.toString()))
            .andExpect(jsonPath("$.ownerType").value("TASK_BOARD_ENTRY"))
            .andExpect(jsonPath("$.ownerId").value(entryId.toString()))
            .andExpect(jsonPath("$.warehouseId").value(WAREHOUSE.toString()))
            .andExpect(jsonPath("$.clientReferenceId").value(request.evidenceId().toString()))
            .andExpect(jsonPath("$.contentType").value("image/jpeg"))
            .andExpect(jsonPath("$.sizeBytes").value(128))
            .andExpect(jsonPath("$.sha256").value("a".repeat(64)))
            .andReturn();
    String createdBody = created.getResponse().getContentAsString();
    assertThat(createdBody)
        .doesNotContain("uploadPath", "readPath", "thumbnailPath", "offlineLeaseId");
    assertThat(
            jdbc.queryForMap(
                """
                select operation_id,task_id,route_index,warehouse_id,worker_id,
                       source_type,source_id,content_type,size_bytes,sha256
                  from worker_task_evidence
                 where evidence_id=?
                """,
                request.evidenceId()))
        .containsEntry("operation_id", request.operationId())
        .containsEntry("task_id", registration.taskId())
        .containsEntry("route_index", 0)
        .containsEntry("warehouse_id", WAREHOUSE)
        .containsEntry("worker_id", contractorId)
        .containsEntry("source_type", "LOGISTICS_DRIVER_TASK")
        .containsEntry("source_id", sourceId)
        .containsEntry("content_type", "image/jpeg")
        .containsEntry("size_bytes", 128L)
        .containsEntry("sha256", "a".repeat(64));
    assertThat(
            jdbc.queryForObject(
                """
                select payload @> ?::jsonb
                  from domain_event
                 where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
                   and aggregate_id=?
                 order by aggregate_version desc
                 limit 1
                """,
                Boolean.class,
                "{\"ownerType\":\"TASK_BOARD_ENTRY\",\"ownerId\":\""
                    + entryId
                    + "\",\"warehouseId\":\""
                    + WAREHOUSE
                    + "\",\"active\":true}",
                entryId.toString()))
        .isTrue();

    MvcResult replay =
        reserveEvidence(contractorId, externalTaskId, entryId, request)
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(replay.getResponse().getContentAsByteArray())
        .isEqualTo(created.getResponse().getContentAsByteArray());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_task_evidence where entry_id=?",
                Integer.class,
                entryId))
        .isOne();

    ContractorEvidenceReservationRequest changedEvidence =
        new ContractorEvidenceReservationRequest(
            request.operationId(),
            UUID.randomUUID(),
            request.capturedAt(),
            request.contentType(),
            request.sizeBytes(),
            request.sha256());
    reserveEvidence(contractorId, externalTaskId, entryId, changedEvidence)
        .andExpect(status().isConflict());
    ContractorEvidenceReservationRequest changedOperation =
        new ContractorEvidenceReservationRequest(
            UUID.randomUUID(),
            request.evidenceId(),
            request.capturedAt(),
            request.contentType(),
            request.sizeBytes(),
            request.sha256());
    reserveEvidence(contractorId, externalTaskId, entryId, changedOperation)
        .andExpect(status().isConflict());
  }

  @Test
  void evidenceReservationReplayRepeatsExactLiveAssignmentProof() throws Exception {
    UUID entryId = registration.route().getFirst().entryId();
    action(
            entryId,
            UUID.randomUUID(),
            ContractorTaskAction.START,
            registration.route().getFirst().entryVersion(),
            null)
        .andExpect(status().isOk());
    ContractorEvidenceReservationRequest request = evidenceRequest();
    reserveEvidence(contractorId, externalTaskId, entryId, request)
        .andExpect(status().isCreated());
    UUID otherContractor = UUID.randomUUID();
    contractors.create(
        WAREHOUSE,
        new CreateContractorDriverRequest(
            otherContractor, "Подменённый подрядчик", "+7 900 999-00-11", null, null));
    jdbc.update(
        "update task_assignment set worker_id=? where queue_entry_id=? and worker_id=?",
        otherContractor,
        entryId,
        contractorId);

    reserveEvidence(contractorId, externalTaskId, entryId, request)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.detail")
                .value("Фотографию можно добавить только к текущему этапу наёмного водителя"));
  }

  private UUID createStaffTask() {
    UUID workerClassId =
        jdbc.queryForObject(
            "select worker_class_id from work_queue_class_binding where queue_id=? and binding_order=0",
            UUID.class,
            driverQueue.id());
    var staff =
        workforce.createWorker(
            WAREHOUSE,
            new WorkerRequest(
                0L,
                "Штатный водитель",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClassId, true, null))));
    taskBoard.registerExternalTask(
        "logistics-service",
        new RegisterExternalTaskRequest(
            WAREHOUSE,
            UUID.randomUUID(),
            "Штатное задание",
            null,
            null,
            10,
            null,
            List.of(new RouteStepRequest(driverQueue.definitionId(), "Старт", 10)),
            LocalDate.of(2026, 8, 31),
            3,
            new TaskSourceReferenceDto(
                TaskSourceType.LOGISTICS_DRIVER_TASK, UUID.randomUUID()),
            TaskLane.CURRENT,
            new DriverTaskAudienceDto(
                DriverTaskAudienceMode.ASSIGNED_DRIVER, staff.id(), null)));
    return staff.id();
  }

  private void insertReadyEvidence(UUID entryId, UUID evidenceId) {
    insertReadyEvidence(
        entryId,
        evidenceId,
        contractorId,
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
  }

  private void insertReadyEvidence(
      UUID entryId, UUID evidenceId, UUID workerId, OffsetDateTime recordedAt) {
    jdbc.update(
        """
        insert into worker_task_evidence(
          evidence_id,version,operation_id,entry_id,task_id,route_index,warehouse_id,
          worker_id,worker_group_id,captured_at,recorded_at,state,media_id,media_generation,
          review_reason,content_type,size_bytes,sha256,source_type,source_id,updated_at,
          selected_for_completion)
        values (?,0,?,?,?,0,?,?,null,?,?,'READY',?,1,
                null,'image/jpeg',128,?,null,null,?,false)
        """,
        evidenceId,
        UUID.randomUUID(),
        entryId,
        registration.taskId(),
        WAREHOUSE,
        workerId,
        recordedAt.minusSeconds(1),
        recordedAt,
        UUID.randomUUID(),
        "b".repeat(64),
        recordedAt);
  }

  private ContractorEvidenceReservationRequest evidenceRequest() {
    return new ContractorEvidenceReservationRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
        "image/jpeg",
        128,
        "a".repeat(64));
  }

  private org.springframework.test.web.servlet.ResultActions reserveEvidence(
      UUID workerId,
      UUID taskId,
      UUID entryId,
      ContractorEvidenceReservationRequest request)
      throws Exception {
    return mvc.perform(
        post(evidencePath(workerId, taskId, entryId))
            .with(logisticsJwt())
            .header("Idempotency-Key", request.operationId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(request)));
  }

  private org.springframework.test.web.servlet.ResultActions action(
      UUID entryId,
      UUID operationId,
      ContractorTaskAction action,
      long expectedVersion,
      UUID evidenceId)
      throws Exception {
    return action(
        contractorId,
        externalTaskId,
        entryId,
        operationId,
        action,
        expectedVersion,
        evidenceId);
  }

  private org.springframework.test.web.servlet.ResultActions action(
      UUID workerId,
      UUID taskId,
      UUID entryId,
      UUID operationId,
      ContractorTaskAction action,
      long expectedVersion,
      UUID evidenceId)
      throws Exception {
    return mvc.perform(
        post(actionPath(workerId, taskId, entryId))
            .with(logisticsJwt())
            .header("Idempotency-Key", operationId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsBytes(
                    new ContractorTaskActionRequest(
                        operationId, action, expectedVersion, evidenceId))));
  }

  private String actionPath(UUID workerId, UUID taskId, UUID entryId) {
    return taskPath(workerId, taskId) + "/entries/" + entryId + "/actions";
  }

  private String evidencePath(UUID workerId, UUID taskId, UUID entryId) {
    return taskPath(workerId, taskId) + "/entries/" + entryId + "/evidence-reservations";
  }

  private String taskPath(UUID workerId, UUID taskId) {
    return "/api/internal/task-board/v1/logistics/contractor-execution/workers/"
        + workerId
        + "/tasks/"
        + taskId;
  }

  private JwtRequestPostProcessor logisticsJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("logistics-service")
                    .claim("client_id", "logistics-service")
                    .claim("principal_type", "SERVICE")
                    .claim("scope", "task-board.logistics"));
  }

  private JwtRequestPostProcessor mixedLogisticsJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("logistics-service")
                    .claim("client_id", "logistics-service")
                    .claim("principal_type", "SERVICE")
                    .claim("scope", "task-board.logistics task-board.task-sync"));
  }

  private JwtRequestPostProcessor otherServiceJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("other-service")
                    .claim("client_id", "other-service")
                    .claim("principal_type", "SERVICE")
                    .claim("scope", "task-board.logistics"));
  }

  private JwtRequestPostProcessor userWithLogisticsScope() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("operator")
                    .claim("client_id", "panel")
                    .claim("principal_type", "USER")
                    .claim("scope", "task-board.logistics"));
  }
}
