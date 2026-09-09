package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerAction;
import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerActionRequest;
import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerProblemReport;
import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.EvidenceReservationRequest;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.WorkerMediaEventProcessor;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkerOfflineLeaseCodec;
import dev.buhanzaz.rwms.taskboard.service.WorkerTaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

/** Exercises atomic problem reports, media isolation and personal manager receipts on PostgreSQL. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WorkerProblemReportIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000891");
  private static final UUID MANAGER = UUID.fromString("00000000-0000-0000-0000-000000000892");
  private static final UUID OTHER_MANAGER = UUID.fromString("00000000-0000-0000-0000-000000000893");
  private static final String PANEL_PATH = "/api/warehouses/" + WAREHOUSE + "/task-problem-reports";

  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;
  @Autowired TaskBoardService board;
  @Autowired WorkerTaskBoardService workerBoard;
  @Autowired WorkerOfflineLeaseCodec leases;
  @Autowired WorkerMediaEventProcessor mediaEvents;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void atomicAttachmentsBecomeReadableWithoutCountingAsTaskResults() throws Exception {
    Fixture fixture = fixture();
    var first = attachment(fixture);
    var second = attachment(fixture);
    var request = request(fixture, List.of(first, second));
    String created = create(fixture, request).andExpect(status().isCreated())
        .andExpect(jsonPath("$.attachments.length()").value(2))
        .andExpect(jsonPath("$.comment").value("Нужна замена инструмента"))
        .andReturn().getResponse().getContentAsString();
    assertSchema("WorkerProblemReportRequest", json.writeValueAsString(request));
    assertSchema("WorkerProblemReport", created);

    UUID mediaId = UUID.randomUUID();
    byte[] event = readyEvent(fixture, (UUID) first.get("evidenceId"), mediaId);
    mediaEvents.process(event);
    mediaEvents.process(event);

    String own = mvc.perform(get("/api/worker/v1/problem-reports/" + request.get("operationId"))
            .with(workerJwt(fixture.workerId(), WAREHOUSE, "worker.tasks")))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readValue(own, WorkerProblemReport.class).attachments())
        .filteredOn(evidence -> evidence.state().equals("READY"))
        .singleElement().satisfies(evidence -> assertThat(evidence.mediaId()).isEqualTo(mediaId));
    var detail = workerBoard.detail(fixture.workerId(), WAREHOUSE, fixture.entry().id());
    assertThat(detail.evidence()).isEmpty();
    assertThat(workerBoard.feed(fixture.workerId(), WAREHOUSE, null, 50).feed().categories().stream()
        .flatMap(category -> category.entries().stream())
        .filter(entry -> entry.entryId().equals(fixture.entry().id()))
        .map(entry -> entry.readyEvidenceCount()).toList()).containsExactly(0);
    assertThat(jdbc.queryForObject(
        "select count(*) from outbox_event where event_type=?", Integer.class,
        TaskBoardEventTypes.TASK_EVIDENCE_READY)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from worker_media_event_inbox", Integer.class)).isOne();
    assertThat(jdbc.queryForObject(
        "select payload->'allowedWorkerIds' @> ?::jsonb from domain_event "
            + "where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF' and aggregate_id=? "
            + "order by aggregate_version desc limit 1", Boolean.class,
        "[\"" + fixture.workerId() + "\"]", fixture.entry().id().toString())).isTrue();

    UUID operationId = UUID.randomUUID();
    assertThatThrownBy(() -> workerBoard.applyAction(
        fixture.workerId(), WAREHOUSE, fixture.entry().id(), operationId.toString(),
        new WorkerActionRequest(operationId, WorkerAction.COMPLETE,
            board.entry(WAREHOUSE, fixture.entry().id()).version(), null,
            fixture.occurredAt(), fixture.leaseId())))
        .isInstanceOf(ConflictException.class).hasMessageContaining("фотограф");
  }

  @Test
  void exactReplayAndOwnReadSurviveTaskClosureButChangedReuseConflicts() throws Exception {
    Fixture fixture = fixture();
    var originalPhoto = attachment(fixture);
    var request = request(fixture, List.of(originalPhoto));
    create(fixture, request).andExpect(status().isCreated());
    board.complete(WAREHOUSE, fixture.entry().id(),
        new VersionCommand(board.entry(WAREHOUSE, fixture.entry().id()).version()), fixture.workerId());

    var replay = new LinkedHashMap<>(request);
    replay.put("offlineLeaseId", UUID.randomUUID());
    replay.put("occurredAt", fixture.occurredAt().withOffsetSameInstant(ZoneOffset.ofHours(3)));
    var equivalentPhoto = new LinkedHashMap<>(originalPhoto);
    equivalentPhoto.put("capturedAt", fixture.occurredAt().withOffsetSameInstant(ZoneOffset.ofHours(3)));
    replay.put("attachments", List.of(equivalentPhoto));
    create(fixture, replay).andExpect(status().isOk())
        .andExpect(jsonPath("$.reportId").value(request.get("operationId").toString()));
    mvc.perform(get("/api/worker/v1/problem-reports/" + request.get("operationId"))
            .with(workerJwt(fixture.workerId(), WAREHOUSE, "worker.tasks")))
        .andExpect(status().isOk());
    var wrongHome = workerJwt(fixture.workerId(), UUID.randomUUID(), "worker.tasks");
    mvc.perform(get("/api/worker/v1/problem-reports/" + request.get("operationId")).with(wrongHome))
        .andExpect(status().is4xxClientError());
    mvc.perform(post(workerCreatePath(fixture)).with(wrongHome)
            .header("Idempotency-Key", request.get("operationId"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
        .andExpect(status().is4xxClientError());
    var changed = new LinkedHashMap<>(request);
    changed.put("comment", "Другой текст");
    create(fixture, changed).andExpect(status().isConflict());
    create(fixture, request(fixture, List.of())).andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("select count(*) from task_problem_report", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from worker_task_evidence", Integer.class)).isOne();
  }

  @Test
  void invalidLaterAttachmentRollsBackTheReportAndEarlierReservation() throws Exception {
    Fixture fixture = fixture();
    var invalid = new LinkedHashMap<>(attachment(fixture));
    invalid.put("routeIndex", fixture.entry().routeIndex() + 1);
    create(fixture, request(fixture, List.of(attachment(fixture), invalid)))
        .andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("select count(*) from task_problem_report", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from worker_task_evidence", Integer.class)).isZero();
    assertThat(board.entry(WAREHOUSE, fixture.entry().id()).status().name()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void photosCannotMoveBetweenResultEvidenceAndDifferentReports() throws Exception {
    Fixture fixture = fixture();
    var resultPhoto = attachment(fixture);
    workerBoard.reserveEvidence(fixture.workerId(), WAREHOUSE, fixture.entry().id(),
        resultPhoto.get("operationId").toString(), json.convertValue(resultPhoto, EvidenceReservationRequest.class));
    create(fixture, request(fixture, List.of(resultPhoto))).andExpect(status().isConflict());

    var problemPhoto = attachment(fixture);
    create(fixture, request(fixture, List.of(problemPhoto))).andExpect(status().isCreated());
    create(fixture, request(fixture, List.of(problemPhoto))).andExpect(status().isConflict());
    assertThatThrownBy(() -> workerBoard.reserveEvidence(
        fixture.workerId(), WAREHOUSE, fixture.entry().id(), problemPhoto.get("operationId").toString(),
        json.convertValue(problemPhoto, EvidenceReservationRequest.class)))
        .isInstanceOf(ConflictException.class);
    assertThat(jdbc.queryForObject("select count(*) from task_problem_report", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from worker_task_evidence", Integer.class)).isEqualTo(2);
  }

  @Test
  void concurrentCreatesAndReadMarkersRemainIdempotent() throws Exception {
    Fixture fixture = fixture();
    var request = request(fixture, List.of(attachment(fixture)));
    assertThat(runTogether(() -> create(fixture, request).andReturn().getResponse().getStatus()))
        .containsExactlyInAnyOrder(201, 200);
    String reportId = request.get("operationId").toString();
    assertThat(runTogether(() -> mvc.perform(put(PANEL_PATH + "/" + reportId + "/read")
        .with(managerJwt(MANAGER, WAREHOUSE, "rwms.read"))).andReturn().getResponse().getStatus()))
        .containsExactly(204, 204);
    assertThat(jdbc.queryForObject("select count(*) from task_problem_report", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from task_problem_report_read_receipt", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from worker_task_evidence", Integer.class)).isOne();
  }

  @Test
  void publicBoundaryRejectsWrongIdentityScopeLeaseAndInvalidDeclarations() throws Exception {
    Fixture fixture = fixture();
    var request = request(fixture, List.of());
    String route = workerCreatePath(fixture);
    for (JwtRequestPostProcessor token : List.of(
        workerJwt(fixture.workerId(), WAREHOUSE, "driver.tasks"),
        workerJwt(fixture.workerId(), UUID.randomUUID(), "worker.tasks"),
        workerJwt(UUID.randomUUID(), WAREHOUSE, "worker.tasks"),
        managerJwt(MANAGER, WAREHOUSE, "rwms.write"))) {
      mvc.perform(post(route).with(token).header("Idempotency-Key", request.get("operationId"))
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
          .andExpect(status().is4xxClientError());
    }
    var invalidLease = new LinkedHashMap<>(request);
    invalidLease.put("offlineLeaseId", UUID.randomUUID());
    create(fixture, invalidLease).andExpect(status().is4xxClientError());
    var blank = new LinkedHashMap<>(request);
    blank.put("comment", "   ");
    create(fixture, blank).andExpect(status().isBadRequest());
    var duplicate = attachment(fixture);
    create(fixture, request(fixture, List.of(duplicate, duplicate)))
        .andExpect(status().isBadRequest());
    create(fixture, request(fixture, java.util.Collections.singletonList(null)))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select count(*) from task_problem_report", Integer.class)).isZero();
  }

  @Test
  void notificationsHavePersonalIdempotentReadReceiptsAndWarehouseAuthorization() throws Exception {
    Fixture fixture = fixture();
    var photo = attachment(fixture);
    var request = request(fixture, List.of(photo));
    create(fixture, request).andExpect(status().isCreated());
    String reportId = request.get("operationId").toString();
    String page = mvc.perform(get(PANEL_PATH).with(managerJwt(MANAGER, WAREHOUSE, "rwms.read")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1))
        .andExpect(jsonPath("$.reports[0].reportId").value(reportId))
        .andExpect(jsonPath("$.reports[0].workerName").value("Рабочий с проблемой"))
        .andExpect(jsonPath("$.reports[0].readAt").isEmpty())
        .andReturn().getResponse().getContentAsString();
    assertSchema("TaskProblemReportPage", page);
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(put(PANEL_PATH + "/" + reportId + "/read")
              .with(managerJwt(MANAGER, WAREHOUSE, "rwms.read")))
          .andExpect(status().isNoContent());
    }
    mediaEvents.process(readyEvent(fixture, (UUID) photo.get("evidenceId"), UUID.randomUUID()));
    mvc.perform(get(PANEL_PATH).with(managerJwt(MANAGER, WAREHOUSE, "rwms.read")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0))
        .andExpect(jsonPath("$.reports[0].readAt").isNotEmpty())
        .andExpect(jsonPath("$.reports[0].attachments[0].state").value("READY"));
    mvc.perform(get(PANEL_PATH).with(managerJwt(OTHER_MANAGER, WAREHOUSE, "rwms.read")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1))
        .andExpect(jsonPath("$.reports[0].readAt").isEmpty());
    for (JwtRequestPostProcessor token : List.of(
        managerJwt(MANAGER, UUID.randomUUID(), "rwms.read"),
        managerJwt(MANAGER, WAREHOUSE, "rwms.write"),
        workerJwt(fixture.workerId(), WAREHOUSE, "worker.tasks"))) {
      mvc.perform(get(PANEL_PATH).with(token)).andExpect(status().isForbidden());
      mvc.perform(put(PANEL_PATH + "/" + reportId + "/read").with(token))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get("/api/worker/v1/problem-reports/" + reportId)
            .with(workerJwt(UUID.randomUUID(), WAREHOUSE, "worker.tasks")))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void cursorPagesDoNotDropReportsWithEqualRecordedTimes() throws Exception {
    Fixture fixture = fixture();
    create(fixture, request(fixture, List.of())).andExpect(status().isCreated());
    create(fixture, request(fixture, List.of())).andExpect(status().isCreated());
    jdbc.update("update task_problem_report set recorded_at=?", fixture.occurredAt());
    String first = mvc.perform(get(PANEL_PATH).queryParam("limit", "1")
            .with(managerJwt(MANAGER, WAREHOUSE, "rwms.read")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(1))
        .andExpect(jsonPath("$.unreadCount").value(2))
        .andReturn().getResponse().getContentAsString();
    var firstPage = json.readTree(first);
    String second = mvc.perform(get(PANEL_PATH).queryParam("limit", "1")
            .queryParam("cursor", firstPage.required("nextCursor").textValue())
            .with(managerJwt(MANAGER, WAREHOUSE, "rwms.read")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").isEmpty())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(second).required("reports").required(0).required("reportId"))
        .isNotEqualTo(firstPage.required("reports").required(0).required("reportId"));
  }

  private Fixture fixture() {
    var workerClass = registry.createClass(new WorkerClassRequest(0L, "Проблемы", null, null, 10, true));
    var definition = registry.createQueueDefinition(
        QueueRegistryTestFixtures.globalDefinition(0L, "Работа", null, QueueType.REPAIR));
    QueueRegistryTestFixtures.create(registry, jdbc, WAREHOUSE,
        new QueueFixtureRequest(0L, definition.id(), true, false, false, null, null, false, null,
            List.of(new QueueBindingRequest(workerClass.id(), false))));
    jdbc.update("update queue_definition set result_photo_min_count=1 where id=?", definition.id());
    var worker = workforce.createWorker(WAREHOUSE,
        new WorkerRequest(0L, "Рабочий с проблемой", null, null, null, true, null, null, null,
            List.of(new QualificationRequest(workerClass.id(), true, null))));
    jdbc.update("update worker set app_login='worker-problem-report' where id=?", worker.id());
    var group = workforce.createGroup(WAREHOUSE,
        new WorkerGroupRequest(0L, workerClass.id(), "Бригада", null, true,
            List.of(new GroupMemberRequest(worker.id(), true))));
    workforce.setCurrentGroup(WAREHOUSE, worker.id(), new SetCurrentGroupRequest(worker.version(), group.id()));
    var created = board.createTask(WAREHOUSE,
        new CreateBoardTaskRequest(null, "Ремонт бытовки", "БТ-91", null, null, null,
            List.of(new RouteStepRequest(definition.id(), "Выполнить работу", null))));
    var entry = created.columns().stream().flatMap(column -> column.entries().stream()).findFirst().orElseThrow();
    board.take(WAREHOUSE, entry.id(), new TakeEntryRequest(entry.version(), null, worker.id()), worker.id());
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
    UUID leaseId = leases.issue(worker.id(), WAREHOUSE, workerBoard.revision(WAREHOUSE), occurredAt.minusSeconds(1)).id();
    return new Fixture(worker.id(), entry, occurredAt, leaseId);
  }

  private Map<String, Object> attachment(Fixture fixture) {
    return Map.of("operationId", UUID.randomUUID(), "evidenceId", UUID.randomUUID(),
        "routeIndex", fixture.entry().routeIndex(), "capturedAt", fixture.occurredAt(),
        "offlineLeaseId", fixture.leaseId(), "contentType", "image/jpeg", "sizeBytes", 128,
        "sha256", "a".repeat(64));
  }

  private Map<String, Object> request(Fixture fixture, List<Map<String, Object>> attachments) {
    return Map.of("operationId", UUID.randomUUID(), "comment", "  Нужна замена инструмента  ",
        "occurredAt", fixture.occurredAt(), "offlineLeaseId", fixture.leaseId(), "attachments", attachments);
  }

  private ResultActions create(Fixture fixture, Map<String, Object> request) throws Exception {
    return mvc.perform(post(workerCreatePath(fixture))
        .with(workerJwt(fixture.workerId(), WAREHOUSE, "worker.tasks"))
        .header("Idempotency-Key", request.get("operationId"))
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
  }

  private String workerCreatePath(Fixture fixture) {
    return "/api/worker/v1/entries/" + fixture.entry().id() + "/problem-reports";
  }

  private byte[] readyEvent(Fixture fixture, UUID evidenceId, UUID mediaId) {
    return """
        {"envelopeVersion":2,"eventId":"%s","eventType":"media.media.ready.v1",
         "eventVersion":1,"occurredAt":null,"recordedAt":"%s","producer":"media-service",
         "aggregateType":"MEDIA","aggregateId":"%s","aggregateVersion":2,
         "correlation":{"correlationId":"%s","causationId":null},
         "actorRef":{"subjectId":"%s","principalType":"WORKER","profileRevision":null},
         "payload":{"mediaId":"%s","ownerType":"TASK_BOARD_ENTRY","ownerId":"%s",
          "warehouseId":"%s","clientReferenceId":"%s","kind":"IMAGE","status":"READY",
          "generation":1,"rotationDegrees":0}}
        """.formatted(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC), mediaId,
            UUID.randomUUID(), fixture.workerId(), mediaId, fixture.entry().id(), WAREHOUSE, evidenceId)
        .getBytes(StandardCharsets.UTF_8);
  }

  private void assertSchema(String schemaName, String body) throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    Path path = Path.of(System.getProperty("rwms.contracts.dir"), "openapi/task-board-service.yaml");
    ObjectNode document;
    try (var input = Files.newInputStream(path)) {
      document = mapper.valueToTree(new Yaml().load(input));
    }
    document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    document.put("$ref", "#/components/schemas/" + schemaName);
    assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(document).validate(mapper.readTree(body))).isEmpty();
  }

  private static JwtRequestPostProcessor workerJwt(UUID workerId, UUID warehouseId, String scope) {
    return jwt().jwt(token -> token.subject(workerId.toString()).claim("principal_type", "WORKER")
        .claim("worker_id", workerId.toString()).claim("warehouse_id", warehouseId.toString()).claim("scope", scope));
  }

  private List<Integer> runTogether(Callable<Integer> action) throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Integer> concurrent = () -> {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
        return action.call();
      };
      var first = executor.submit(concurrent);
      var second = executor.submit(concurrent);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }
  }

  private static JwtRequestPostProcessor managerJwt(UUID managerId, UUID warehouseId, String scope) {
    return jwt().jwt(token -> token.subject(managerId.toString()).claim("principal_type", "USER")
        .claim("global_role", "WAREHOUSE_MANAGER").claim("scope", scope)
        .claim("warehouse_access", List.of(Map.of("warehouseId", warehouseId.toString(), "level", "VIEW"))));
  }

  private record Fixture(UUID workerId, BoardEntryDto entry, OffsetDateTime occurredAt, UUID leaseId) {}
}
